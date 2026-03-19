package com.ironside.challengesphere.controller;

import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.repository.NotificationRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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
            @RequestParam(defaultValue = "50") int limit) {
        logger.info("Fetching notifications for userId: {}, limit: {}", userId, limit);
        List<Notification> notifications = notificationRepository.findRecentNotificationsByUserId(userId, limit);
        long total = notificationRepository.countNotificationsByUserId(userId);
        return ResponseEntity.ok(Map.of(
                "notifications", notifications,
                "total", total
        ));
    }

    /**
     * DELETE /api/v1/notifications/{userId}
     * Clears all notifications for a user.
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> clearNotifications(@PathVariable Long userId) {
        logger.info("Clearing notifications for userId: {}", userId);
        notificationRepository.deleteByUserId(userId);
        return ResponseEntity.ok().build();
    }
}
