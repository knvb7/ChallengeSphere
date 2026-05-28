package com.ironside.challengesphere.service;

import java.util.List;
import java.util.Map;

public interface PushNotificationSender {

    void send(List<PushMessage> messages);

    record PushMessage(String to, String title, String body, Map<String, Object> data) {
    }
}
