package com.ironside.challengesphere.util;

import java.util.Locale;
import java.util.Set;

public enum NotificationType {
    FOLLOW,
    SUBMISSION,
    GLORY,
    COMMENT,
    CHALLENGE_INVITE,
    CHALLENGE_UPDATE,
    CHALLENGE_REMOVAL,
    REMINDER,
    INVITE,
    SUBMISSION_UPDATE,
    CONTENT_WARNING,
    ANNOUNCEMENT;

    private static final Set<NotificationType> IMMEDIATE_PUSH_TYPES = Set.of(
            FOLLOW,
            SUBMISSION,
            GLORY,
            COMMENT,
            CHALLENGE_INVITE,
            CHALLENGE_UPDATE,
            CHALLENGE_REMOVAL
    );
    private static final Set<NotificationType> UNTHROTTLED_PUSH_TYPES = Set.of(
            SUBMISSION,
            GLORY,
            COMMENT
    );

    public static boolean supportsImmediatePush(String type) {
        NotificationType notificationType = parse(type);
        return notificationType != null && IMMEDIATE_PUSH_TYPES.contains(notificationType);
    }

    public static boolean bypassesDailyPushQuota(String type) {
        NotificationType notificationType = parse(type);
        return notificationType != null && UNTHROTTLED_PUSH_TYPES.contains(notificationType);
    }

    private static NotificationType parse(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return NotificationType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
