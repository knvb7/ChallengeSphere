package com.ironside.challengesphere.model;

import com.ironside.challengesphere.util.UserRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_users_email", columnList = "email_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "email_id", nullable = false, unique = true, length = 100)
    private String emailId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", length = 100)
    private String fullName;
    
    @Column(name = "location", length = 100)
    private String location;
    
    @Column(name = "bio", length = 500)
    private String bio;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "active")
    private boolean active = true;
    
    @Column(name = "profile_pic_url")
    private String profilePicUrl;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP")
    private OffsetDateTime createdAt;

    @Column(name = "last_login_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime lastLoginAt;

    @Column(name = "language_preference", length = 10, columnDefinition = "VARCHAR(10) DEFAULT 'en'")
    private String languagePreference = "en";

    @Column(name = "preferred_tone", length = 50, columnDefinition = "VARCHAR(50) DEFAULT 'motivational'")
    private String preferredTone = "motivational";
    
    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        if (role == null) {
            role = UserRole.USER;
        }
    }
}
