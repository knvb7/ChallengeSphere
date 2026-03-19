package com.ironside.challengesphere.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.sql.Timestamp;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email_id", nullable = false, unique = true, length = 255)
    private String emailId;

    @Column(name = "phone", unique = true, length = 20)
    private String phone;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "hashed_password", nullable = false, length = 255)
    private String hashedPassword;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP")
    private Timestamp createdAt;

    @Column(name = "genuineness_score", columnDefinition = "INT DEFAULT 100")
    private Integer genuinenessScore = 100;

    @Column(name = "active", columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean active = true;

    @Column(name = "challenges_created", columnDefinition = "INT DEFAULT 0")
    private Integer challengesCreated = 0;

    @Column(name = "challenges_accepted", columnDefinition = "INT DEFAULT 0")
    private Integer challengesAccepted = 0;

    @Column(name = "followers", columnDefinition = "INT DEFAULT 0")
    private Integer followers = 0;

    @Column(name = "following", columnDefinition = "INT DEFAULT 0")
    private Integer following = 0;

    @Column(name = "profile_pic_url")
    private String profilePicUrl;

    @Column(name = "premium_user", columnDefinition = "INT DEFAULT 0")
    private Integer premiumUser = 0;

    @Column(name = "notifications", columnDefinition = "INT DEFAULT 1")
    private Integer notifications = 1;

    @Column(name = "bio")
    private String bio;
}
