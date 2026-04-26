package com.ironside.challengesphere.scheduler;

import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.EmailService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NotificationDigestScheduler {

    private static final Logger logger = LogManager.getLogger(NotificationDigestScheduler.class);

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final EmailService emailService;

    @Value("${app.notifications.digest.enabled:false}")
    private boolean enabled;

    @Value("${app.notifications.digest.max-notifications:25}")
    private int maxNotifications;

    public NotificationDigestScheduler(UserRepository userRepository,
                                       NotificationRepository notificationRepository,
                                       EmailService emailService) {
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.emailService = emailService;
    }

    @Scheduled(cron = "${app.notifications.digest.cron:0 0 9 * * *}")
    public void sendDailyDigest() {
        if (!enabled) {
            logger.debug("Notification digest disabled, skipping");
            return;
        }
        List<User> users = userRepository.findRecentlyActiveUsers(7);
        logger.info("Dispatching notification digest to {} active users", users.size());
        for (User user : users) {
            if (user.getNotifications() == null || user.getNotifications() <= 0) {
                continue;
            }
            List<Notification> recent = notificationRepository.findRecentNotificationsByUserId(user.getId(), maxNotifications);
            if (!recent.isEmpty()) {
                emailService.sendNotificationDigest(user, recent);
            }
        }
    }
}
