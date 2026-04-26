package com.ironside.challengesphere.service;

import com.ironside.challengesphere.model.Notification;

public interface NotificationService {

    Notification createNotification(Long userId, String type, String message, String actionObject);

    Notification createAndEmail(Long userId, String type, String message, String actionObject);
}
