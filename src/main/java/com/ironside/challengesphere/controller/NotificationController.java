package com.ironside.challengesphere.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.request.JoinChallengeRequest;
import com.ironside.challengesphere.api.response.JoinChallengeResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.security.UserPrincipal;
import com.ironside.challengesphere.service.ChallengeService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    /**
     * GET /api/v1/notifications/{userId}
     * Returns recent notifications for a user (up to 50).
     */
    @GetMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> getNotifications(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        assertOwnInbox(userId, principal);
        int safeLimit = clampLimit(limit);
        logger.info("Fetching notifications for userId: {}, limit: {}", userId, limit);
        List<Notification> notifications = notificationRepository.findRecentNotificationsByUserId(userId, safeLimit);
        long total = notificationRepository.countNotificationsByUserId(userId);
        long unreadCount = notificationRepository.countUnreadNotificationsByUserId(userId);
        return ResponseEntity.ok(Map.of(
                "notifications", notifications,
                "total", total,
                "unreadCount", unreadCount
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getMyNotifications(
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        return getNotifications(requireUserId(principal), limit, principal);
    }

    @GetMapping("/{userId}/unread-count")
    public ResponseEntity<Map<String, Object>> getUnreadCount(
            @PathVariable Long userId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        assertOwnInbox(userId, principal);
        long unreadCount = notificationRepository.countUnreadNotificationsByUserId(userId);
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
            @PathVariable Long userId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        assertOwnInbox(userId, principal);
        int updated = notificationRepository.markAllAsReadByUserId(userId);
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @PatchMapping("/{userId}/{notificationId}/read")
    @Transactional
    public ResponseEntity<Map<String, Object>> markRead(
            @PathVariable Long userId,
            @PathVariable Long notificationId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        assertOwnInbox(userId, principal);
        int updated = notificationRepository.markAsRead(userId, notificationId);
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    /**
     * DELETE /api/v1/notifications/{userId}
     * Clears all notifications for a user.
     */
    @DeleteMapping("/{userId}")
    @Transactional
    public ResponseEntity<Void> clearNotifications(
            @PathVariable Long userId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        assertOwnInbox(userId, principal);
        logger.info("Clearing notifications for userId: {}", userId);
        notificationRepository.deleteByUserId(userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/me/{notificationId}/challenge-invite/accept")
    @Transactional
    public ResponseEntity<JoinChallengeResponse> acceptChallengeInvite(
            @PathVariable Long notificationId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        Long userId = requireUserId(principal);
        Notification notification = requireOwnChallengeInvite(notificationId, userId);
        Long challengeId = extractChallengeId(notification);

        JoinChallengeRequest request = new JoinChallengeRequest();
        request.setChallengeId(challengeId);
        request.setUserIds(List.of(userId));

        JoinChallengeResponse response = challengeService.joinChallenge(request);
        notificationRepository.markAsRead(userId, notificationId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/me/{notificationId}/challenge-invite/ignore")
    @Transactional
    public ResponseEntity<Map<String, Object>> ignoreChallengeInvite(
            @PathVariable Long notificationId,
            @AuthenticationPrincipal UserPrincipal principal) throws ChallengeSphereException {
        Long userId = requireUserId(principal);
        requireOwnChallengeInvite(notificationId, userId);
        notificationRepository.markAsRead(userId, notificationId);
        return ResponseEntity.ok(Map.of(
                "handled", true,
                "notificationId", notificationId
        ));
    }

    private int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }

    private Long requireUserId(UserPrincipal principal) throws ChallengeSphereException {
        if (principal == null || principal.getId() == null) {
            throw new ChallengeSphereException("Authenticated user required", HttpStatus.UNAUTHORIZED, logger);
        }
        return principal.getId();
    }

    private void assertOwnInbox(Long userId, UserPrincipal principal) throws ChallengeSphereException {
        Long currentUserId = requireUserId(principal);
        if (!currentUserId.equals(userId)) {
            throw new ChallengeSphereException("You can only access your own notifications", HttpStatus.FORBIDDEN, logger);
        }
    }

    private Notification requireOwnChallengeInvite(Long notificationId, Long userId) throws ChallengeSphereException {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ChallengeSphereException("Notification not found", HttpStatus.NOT_FOUND, logger));
        if (!userId.equals(notification.getUserId())) {
            throw new ChallengeSphereException("You can only access your own notifications", HttpStatus.FORBIDDEN, logger);
        }
        if (notification.getType() == null || !TYPE_CHALLENGE_INVITE.equalsIgnoreCase(notification.getType())) {
            throw new ChallengeSphereException("Notification is not a challenge invite", HttpStatus.BAD_REQUEST, logger);
        }
        return notification;
    }

    private Long extractChallengeId(Notification notification) throws ChallengeSphereException {
        try {
            JsonNode action = objectMapper.readTree(notification.getActionObject());
            JsonNode challengeId = action.get("challengeId");
            if (challengeId == null || !challengeId.canConvertToLong()) {
                throw new ChallengeSphereException("Challenge invite is missing challenge details", HttpStatus.BAD_REQUEST, logger);
            }
            return challengeId.asLong();
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            throw new ChallengeSphereException("Challenge invite is missing challenge details", HttpStatus.BAD_REQUEST, logger);
        }
    }
}
