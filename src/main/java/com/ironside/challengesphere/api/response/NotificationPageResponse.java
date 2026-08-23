package com.ironside.challengesphere.api.response;

import com.ironside.challengesphere.model.Notification;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class NotificationPageResponse extends CursorResponse {

    private List<Notification> notifications = List.of();
    private long unreadCount;
    private long serverTimeEpochMs;

    public NotificationPageResponse(
            List<Notification> notifications,
            long unreadCount,
            long serverTimeEpochMs,
            String nextCursor,
            boolean hasMore
    ) {
        super(nextCursor, hasMore);
        this.notifications = notifications == null ? List.of() : List.copyOf(notifications);
        this.unreadCount = unreadCount;
        this.serverTimeEpochMs = serverTimeEpochMs;
    }
}
