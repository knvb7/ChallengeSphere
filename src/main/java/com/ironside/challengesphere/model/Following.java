package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.OffsetDateTime;

@Entity
@Table(name = "followers", schema = "challengesphere", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"follower_id", "followed_id"})
})
@NoArgsConstructor
@Data
public class Following {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // The user who follows
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "follower_id", nullable = false)
    private User follower;

    // The user being followed
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "followed_id", nullable = false)
    private User followed;

    @Column(name = "created_at", nullable = false)
    private Timestamp createdAt;

}
