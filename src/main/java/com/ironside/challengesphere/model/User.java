package com.ironside.challengesphere.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ironside.challengesphere.util.UserRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
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

    @JsonIgnore
    @Column(name = "hashed_password", length = 255)
    private String hashedPassword;

    @Column(name = "google_id", unique = true, length = 255)
    private String googleId;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP")
    private Timestamp createdAt;

    @Column(name = "genuineness_score", columnDefinition = "INT DEFAULT 100")
    private Integer genuinenessScore = 100;

    @JsonProperty("genuinityScore")
    public Integer getGenuinityScore() {
        return genuinenessScore;
    }

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

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private UserRole role = UserRole.USER;

    @Column(name = "notifications", columnDefinition = "INT DEFAULT 1")
    private Integer notifications = 1;

    @Column(name = "bio")
    private String bio;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "age")
    private Integer age;

    @Convert(converter = StringListConverter.class)
    @Column(name = "interests")
    private List<String> interests;

    @Column(name = "last_login_at")
    private Timestamp lastLoginAt;

    @Column(name = "banned_at")
    private Timestamp bannedAt;

    @Column(name = "ban_reason", length = 512)
    private String banReason;

    @Transient
    private Integer submissionsCount;
}
