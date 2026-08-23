package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.sql.Timestamp;

@Entity
@Table(name = "follows", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"follower_id", "following_id"})
})
@Data
@NoArgsConstructor
public class Follow {

    @Id
    private String id;

    @Column(name = "follower_id", nullable = false)
    private String followerId;

    @Column(name = "following_id", nullable = false)
    private String followingId;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Timestamp createdAt;

    @Column(name = "active", nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean active = true;

    /** Durable cursor for bounded historical-submission backfill after a follow begins. */
    @Transient
    private String feedTimelineBackfillCursor;

    /** Set only after this follower's historical timeline backfill is complete. */
    @Transient
    private Timestamp feedTimelineBackfillCompletedAt;
}
