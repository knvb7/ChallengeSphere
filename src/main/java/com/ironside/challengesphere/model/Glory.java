package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Timestamp;

@Entity
@Table(name = "glories", uniqueConstraints = @UniqueConstraint(
        name = "uk_glories", columnNames = {"submission_id", "user_id"}))
@Data
public class Glory {
    @Id
    private String id;

    @Column(name = "submission_id", nullable = false)
    private String submissionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "created_at", nullable = false)
    private Timestamp createdAt;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "ungloried", nullable = false)
    private Boolean ungloried = false;
}
