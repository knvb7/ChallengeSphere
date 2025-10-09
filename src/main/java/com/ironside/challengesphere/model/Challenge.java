package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.sql.Timestamp;

@Entity
@Table(name = "challenges")
@Data
@NoArgsConstructor
public class Challenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hashtags")
    private String hashtags;

    @Column(name = "categories")
    private String categories;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "benefits")
    private String benefits;

    @Column(name = "private", columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean isPrivate = false;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "active", columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean active = true;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP")
    private Timestamp createdAt;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "current_owner_id", nullable = false)
    private Long currentOwnerId;

    @Column(name = "number_of_days", nullable = false)
    private Integer numberOfDays;
}
