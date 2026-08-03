package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.PushDeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface PushDeviceTokenRepository extends JpaRepository<PushDeviceToken, Long> {

    Optional<PushDeviceToken> findByExpoPushToken(String expoPushToken);

    @Query(value = """
            SELECT pdt.*
            FROM challengesphere.push_device_tokens pdt
            JOIN challengesphere.users u ON u.id = pdt.user_id
            LEFT JOIN challengesphere.user_preferences up ON up.user_id = pdt.user_id
            WHERE pdt.user_id = :userId
              AND pdt.enabled = true
              AND COALESCE(u.active, true) = true
              AND (COALESCE(u.notifications, 1) & 1) = 1
              AND COALESCE(up.push_notifications_enabled, true) = true
              AND CASE UPPER(CAST(:type AS TEXT))
                    WHEN 'FOLLOW' THEN COALESCE(up.notify_on_follow, true)
                    WHEN 'GLORY' THEN COALESCE(up.notify_on_glory, true)
                    WHEN 'COMMENT' THEN COALESCE(up.notify_on_comment, true)
                    WHEN 'CHALLENGE_INVITE' THEN
                        COALESCE(up.notify_on_challenge_invite, true)
                        AND COALESCE(up.allow_challenge_invites, true)
                    WHEN 'INVITE' THEN
                        COALESCE(up.notify_on_challenge_invite, true)
                        AND COALESCE(up.allow_challenge_invites, true)
                    WHEN 'SUBMISSION' THEN COALESCE(up.notify_on_challenge_update, true)
                    WHEN 'SUBMISSION_UPDATE' THEN COALESCE(up.notify_on_challenge_update, true)
                    WHEN 'CHALLENGE_UPDATE' THEN COALESCE(up.notify_on_challenge_update, true)
                    WHEN 'MOMENTUM_MILESTONE' THEN COALESCE(up.notify_on_motivation, true)
                    ELSE true
                  END
            ORDER BY pdt.last_seen_at DESC
            """, nativeQuery = true)
    List<PushDeviceToken> findEligibleByUserIdAndType(
            @Param("userId") Long userId,
            @Param("type") String type);

    @Query(value = """
            SELECT pdt.*
            FROM challengesphere.push_device_tokens pdt
            JOIN challengesphere.users u ON u.id = pdt.user_id
            LEFT JOIN challengesphere.user_preferences up ON up.user_id = pdt.user_id
            WHERE pdt.enabled = true
              AND COALESCE(u.active, true) = true
              AND (COALESCE(u.notifications, 1) & 1) = 1
              AND COALESCE(up.push_notifications_enabled, true) = true
              AND COALESCE(up.notify_on_motivation, true) = true
            ORDER BY pdt.user_id, pdt.last_seen_at DESC
            """, nativeQuery = true)
    List<PushDeviceToken> findEnabledReminderTokens();

    @Modifying
    @Query(value = "UPDATE challengesphere.push_device_tokens SET enabled = false, updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId AND expo_push_token = :expoPushToken", nativeQuery = true)
    int disableToken(@Param("userId") Long userId, @Param("expoPushToken") String expoPushToken);

    @Modifying
    @Transactional
    @Query(value = "UPDATE challengesphere.push_device_tokens SET enabled = false, updated_at = CURRENT_TIMESTAMP WHERE expo_push_token = :expoPushToken", nativeQuery = true)
    int disableTokenByExpoPushToken(@Param("expoPushToken") String expoPushToken);
}
