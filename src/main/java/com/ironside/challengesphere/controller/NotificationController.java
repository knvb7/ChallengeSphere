package com.ironside.challengesphere.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.request.JoinChallengeRequest;
import com.ironside.challengesphere.api.request.RegisterPushTokenRequest;
import com.ironside.challengesphere.api.response.ChallengeInviteDetailsResponse;
import com.ironside.challengesphere.api.response.CursorPageResponse;
import com.ironside.challengesphere.api.response.JoinChallengeResponse;
import com.ironside.challengesphere.api.response.NotificationPageResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.PushDeviceToken;
import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.ChallengeService;
import com.ironside.challengesphere.service.NotificationService;
import com.ironside.challengesphere.service.PushNotificationService;
import com.ironside.challengesphere.service.impl.RedisCacheServiceImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final Logger logger = LogManager.getLogger(NotificationController.class);
    private static final String TYPE_CHALLENGE_INVITE = "CHALLENGE_INVITE";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private PushNotificationService pushNotificationService;

    @Autowired(required = false)
    private RedisCacheServiceImpl cacheService;

    /**
     * GET /api/v1/notifications/{userId}
     * Returns recent notifications for a user (up to 50).
     */
    @GetMapping("/{userId}")
    public ResponseEntity<NotificationPageResponse> getNotifications(
            @PathVariable String userId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        assertOwnInbox(userId, principal);
        int safeLimit = clampLimit(limit);
        logger.info("Fetching notifications for userId: {}, limit: {}", userId, limit);
        CursorPageResponse<Notification> result = notificationService.getNotifications(userId, safeLimit, cursor);
        long unreadCount = unreadCount(userId);
        return ResponseEntity.ok(new NotificationPageResponse(
                result.getItems(),
                unreadCount,
                Instant.now().toEpochMilli(),
                result.getNextCursor(),
                result.isHasMore()
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<NotificationPageResponse> getMyNotifications(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        return getNotifications(requireUserId(principal), limit, cursor, principal);
    }

    @GetMapping("/{userId}/unread-count")
    public ResponseEntity<Map<String, Object>> getUnreadCount(
            @PathVariable String userId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        assertOwnInbox(userId, principal);
        long unreadCount = unreadCount(userId);
        return ResponseEntity.ok(Map.of("unreadCount", unreadCount));
    }

    @GetMapping("/me/unread-count")
    public ResponseEntity<Map<String, Object>> getMyUnreadCount(
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        return getUnreadCount(requireUserId(principal), principal);
    }

    @PatchMapping("/{userId}/read")
    @Transactional
    public ResponseEntity<Map<String, Object>> markAllRead(
            @PathVariable String userId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        assertOwnInbox(userId, principal);
        int updated = notificationRepository.markAllAsReadByUserId(userId);
        invalidateUnreadCount(userId);
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @PatchMapping("/{userId}/{notificationId}/read")
    @Transactional
    public ResponseEntity<Map<String, Object>> markRead(
            @PathVariable String userId,
            @PathVariable String notificationId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        assertOwnInbox(userId, principal);
        int updated = notificationRepository.markAsRead(userId, notificationId);
        invalidateUnreadCount(userId);
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    /**
     * DELETE /api/v1/notifications/{userId}
     * Clears all notifications for a user.
     */
    @DeleteMapping("/{userId}")
    @Transactional
    public ResponseEntity<Void> clearNotifications(
            @PathVariable String userId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        assertOwnInbox(userId, principal);
        logger.info("Clearing notifications for userId: {}", userId);
        notificationRepository.deleteByUserId(userId);
        invalidateUnreadCount(userId);
        return ResponseEntity.ok().build();
    }

    /**
     * DELETE /api/v1/notifications/{userId}/activity
     * Clears activity while preserving saved and actionable invites and follows.
     */
    @DeleteMapping("/{userId}/activity")
    @Transactional
    public ResponseEntity<Map<String, Object>> clearActivityNotifications(
            @PathVariable String userId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        assertOwnInbox(userId, principal);
        logger.info("Clearing activity notifications for userId: {}", userId);
        int deleted = notificationRepository.deleteActivityByUserId(userId);
        long unreadCount = notificationRepository.countUnreadNotificationsByUserId(userId);
        invalidateUnreadCount(userId);
        return ResponseEntity.ok(Map.of(
                "deleted", deleted,
                "unreadCount", unreadCount
        ));
    }

    @PostMapping("/me/{notificationId}/challenge-invite/accept")
    @Transactional
    public ResponseEntity<JoinChallengeResponse> acceptChallengeInvite(
            @PathVariable String notificationId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        String userId = requireUserId(principal);
        Notification notification = requireOwnChallengeInvite(notificationId, userId);
        String challengeId = extractChallengeId(notification);

        JoinChallengeRequest request = new JoinChallengeRequest();
        request.setChallengeId(challengeId);
        request.setUserIds(List.of(userId));

        JoinChallengeResponse response = challengeService.joinChallengeFromVerifiedInvite(request);
        notificationRepository.markAsRead(userId, notificationId);
        invalidateUnreadCount(userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me/{notificationId}/challenge-invite")
    public ResponseEntity<ChallengeInviteDetailsResponse> getChallengeInviteDetails(
            @PathVariable String notificationId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        String userId = requireUserId(principal);
        return ResponseEntity.ok(notificationService.getChallengeInviteDetails(notificationId, userId));
    }

    @PostMapping("/me/{notificationId}/challenge-invite/ignore")
    @Transactional
    public ResponseEntity<Map<String, Object>> ignoreChallengeInvite(
            @PathVariable String notificationId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        String userId = requireUserId(principal);
        requireOwnChallengeInvite(notificationId, userId);
        notificationRepository.markAsRead(userId, notificationId);
        invalidateUnreadCount(userId);
        return ResponseEntity.ok(Map.of(
                "handled", true,
                "notificationId", notificationId
        ));
    }

    @PostMapping("/me/push-token")
    public ResponseEntity<Map<String, Object>> registerPushToken(
            @RequestBody RegisterPushTokenRequest request,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        String userId = requireUserId(principal);
        PushDeviceToken token = pushNotificationService.registerToken(userId, request);
        return ResponseEntity.ok(Map.of(
                "registered", true,
                "tokenId", token.getId() == null ? null : token.getId().toString()
        ));
    }

    @DeleteMapping("/me/push-token")
    @Transactional
    public ResponseEntity<Map<String, Object>> disablePushToken(
            @RequestBody RegisterPushTokenRequest request,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        String userId = requireUserId(principal);
        pushNotificationService.disableToken(userId, request == null ? null : request.getExpoPushToken());
        return ResponseEntity.ok(Map.of("disabled", true));
    }

    private int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }

    private long unreadCount(String userId) {
        String key = "notifications:unread:" + userId;
        Long cached = cacheService == null ? null : cacheService.get(key, Long.class);
        if (cached != null) {
            return cached;
        }
        long count = notificationRepository.countUnreadNotificationsByUserId(userId);
        if (cacheService != null) {
            cacheService.save(key, count, Duration.ofSeconds(60));
        }
        return count;
    }

    private void invalidateUnreadCount(String userId) {
        if (cacheService == null) {
            return;
        }
        Runnable invalidate = () -> cacheService.delete("notifications:unread:" + userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
        } else {
            invalidate.run();
        }
    }

    private String requireUserId(UserPrincipal principal) throws ChallengeSphereException {
        if (principal == null || principal.getId() == null) {
            throw new ChallengeSphereException("Authenticated user required", HttpStatus.UNAUTHORIZED, logger);
        }
        return principal.getId().toString();
    }

    private void assertOwnInbox(String userId, UserPrincipal principal) throws ChallengeSphereException {
        String currentUserId = requireUserId(principal);
        if (!currentUserId.equals(userId)) {
            throw new ChallengeSphereException("You can only access your own notifications", HttpStatus.FORBIDDEN, logger);
        }
    }

    private Notification requireOwnChallengeInvite(String notificationId, String userId) throws ChallengeSphereException {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ChallengeSphereException("Notification not found", HttpStatus.NOT_FOUND, logger));
        if (notification.getUserId() == null || !userId.equals(notification.getUserId().toString())) {
            throw new ChallengeSphereException("You can only access your own notifications", HttpStatus.FORBIDDEN, logger);
        }
        if (notification.getType() == null || !TYPE_CHALLENGE_INVITE.equalsIgnoreCase(notification.getType())) {
            throw new ChallengeSphereException("Notification is not a challenge invite", HttpStatus.BAD_REQUEST, logger);
        }
        return notification;
    }

    private String extractChallengeId(Notification notification) throws ChallengeSphereException {
        if (notification.getChallengeId() != null) {
            return notification.getChallengeId().toString();
        }
        try {
            JsonNode action = objectMapper.readTree(notification.getActionObject());
            JsonNode challengeId = action.get("challengeId");
            if (challengeId == null || challengeId.asText().isBlank()) {
                throw new ChallengeSphereException("Challenge invite is missing challenge details", HttpStatus.BAD_REQUEST, logger);
            }
            return challengeId.asText();
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            throw new ChallengeSphereException("Challenge invite is missing challenge details", HttpStatus.BAD_REQUEST, logger);
        }
    }
}
