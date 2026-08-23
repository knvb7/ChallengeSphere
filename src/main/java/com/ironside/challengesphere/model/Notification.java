package com.ironside.challengesphere.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.sql.Timestamp;

@Entity
@Table(name = "notifications")
@Data
@NoArgsConstructor
public class Notification {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "type", nullable = false, length = 500)
    private String type;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "action_object", columnDefinition = "TEXT")
    private String actionObject;

    /** Server-side target used to authorize invite previews and invalidate stale invites. */
    @JsonIgnore
    @Column(name = "challenge_id")
    private String challengeId;

    @Column(name = "sent_at", nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Timestamp sentAt;

    @Column(name = "read_at")
    private Timestamp readAt;

    @Transient
    public boolean isRead() {
        return readAt != null;
    }

    @Transient
    @JsonProperty("sentAtEpochMs")
    public Long getSentAtEpochMs() {
        return sentAt == null ? null : sentAt.getTime();
    }
}
