package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

@Entity
@Table(name = "challenges")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Challenge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(length = 10000)
    private String description;

    @Column(nullable = false)
    private Long creatorUserId;

    @Column(nullable = false)
    private Long ownerUserId; // current owner/admin of the challenge

    @Column(nullable = false)
    private Timestamp createdAt = Timestamp.from(Instant.now());

    @Column(nullable = false)
    private Timestamp updatedAt = Timestamp.from(Instant.now());

    @Column(nullable = false)
    private boolean active = true;

    @Column(unique = true, nullable = false, length = 16)
    private String shareCode;

    @Column(nullable = false)
    private Timestamp endDate;

    @Column(nullable = false)
    private int numberOfDays;

    @Column(length = 10)
    private String reminderTime;
}
