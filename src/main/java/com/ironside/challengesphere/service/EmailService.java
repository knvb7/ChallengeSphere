package com.ironside.challengesphere.service;

import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.User;

import java.util.List;

public interface EmailService {

    void sendWelcomeEmail(User user);

    void sendPasswordResetEmail(User user, String resetToken);

    void sendNotificationDigest(User user, List<Notification> notifications);

    void sendPlainEmail(String to, String subject, String body);
}
