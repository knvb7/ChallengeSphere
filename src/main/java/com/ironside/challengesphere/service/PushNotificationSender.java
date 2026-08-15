package com.ironside.challengesphere.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface PushNotificationSender {

    SendResult send(List<PushMessage> messages);

    default SendResult sendOnce(List<PushMessage> messages) {
        return send(messages);
    }

    record SendResult(Set<String> acceptedTokens, Map<String, String> rejectedTokens) {
        public SendResult {
            acceptedTokens = acceptedTokens == null ? Set.of() : Set.copyOf(acceptedTokens);
            rejectedTokens = rejectedTokens == null ? Map.of() : Map.copyOf(rejectedTokens);
        }

        public static SendResult empty() {
            return new SendResult(Set.of(), Map.of());
        }

        public boolean accepted(String expoPushToken) {
            return expoPushToken != null && acceptedTokens.contains(expoPushToken);
        }
    }

    record PushMessage(
            String to,
            String title,
            String body,
            Map<String, Object> data,
            String sound,
            String channelId,
            String priority,
            String collapseId,
            String tag,
            Integer ttl
    ) {
        public PushMessage(String to, String title, String body, Map<String, Object> data) {
            this(to, title, body, data, UUID.randomUUID().toString());
        }

        private PushMessage(
                String to,
                String title,
                String body,
                Map<String, Object> data,
                String deliveryId
        ) {
            this(
                    to,
                    title,
                    body,
                    data,
                    "default",
                    "default",
                    "high",
                    deliveryId,
                    deliveryId,
                    resolveTtlSeconds(data)
            );
        }

        private static int resolveTtlSeconds(Map<String, Object> data) {
            Object type = data == null ? null : data.get("type");
            return "DAILY_UPLOAD_REMINDER".equalsIgnoreCase(String.valueOf(type))
                    ? 5 * 60 * 60
                    : 24 * 60 * 60;
        }
    }
}
