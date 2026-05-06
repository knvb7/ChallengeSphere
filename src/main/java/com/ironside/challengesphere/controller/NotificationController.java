package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.security.UserPrincipal;
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

    @Autowired
    private NotificationRepository notificationRepository;

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
}
