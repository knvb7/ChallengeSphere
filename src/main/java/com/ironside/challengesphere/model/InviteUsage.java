package com.ironside.challengesphere.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Entity
@Table(name = "invite_usages")
@Data
@NoArgsConstructor
public class InviteUsage {

    @Id
    private String id;

    @Column(name = "invite_id", nullable = false)
    private String inviteId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "joined_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP")
    private Timestamp joinedAt;
}
