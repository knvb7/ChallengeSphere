package com.ironside.challengesphere.service;

import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.exception.ChallengeSphereException;

import java.util.List;

public interface EmailService {

    void sendWelcomeEmail(User user);

    void sendPasswordResetEmail(User user, String resetCode);

    void sendNotificationDigest(User user, List<Notification> notifications);

    void sendPlainEmail(String to, String subject, String body);

    void sendHtmlEmail(String to, String subject, String html) throws ChallengeSphereException;
}
