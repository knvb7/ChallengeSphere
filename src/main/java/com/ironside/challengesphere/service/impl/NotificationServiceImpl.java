package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.EmailService;
import com.ironside.challengesphere.service.NotificationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Logger logger = LogManager.getLogger(NotificationServiceImpl.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    public NotificationServiceImpl(NotificationRepository notificationRepository,
                                   UserRepository userRepository,
                                   EmailService emailService) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    @Override
    @Transactional
    public Notification createNotification(Long userId, String type, String message, String actionObject) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setMessage(message);
        n.setActionObject(actionObject);
        n.setSentAt(Timestamp.from(Instant.now()));
        Notification saved = notificationRepository.save(n);
        logger.info("Created notification id={} for userId={} type={}", saved.getId(), userId, type);
        return saved;
    }

    @Override
    @Transactional
    public Notification createAndEmail(Long userId, String type, String message, String actionObject) {
        Notification saved = createNotification(userId, type, message, actionObject);
        userRepository.findById(userId).ifPresent(user -> {
            if (shouldEmail(user)) {
                emailService.sendPlainEmail(user.getEmailId(),
                        "[ChallengeSphere] " + type,
                        message);
            }
        });
        return saved;
    }

    private boolean shouldEmail(User user) {
        return user.getEmailId() != null
                && !user.getEmailId().isBlank()
                && user.getNotifications() != null
                && user.getNotifications() > 0;
    }
}
