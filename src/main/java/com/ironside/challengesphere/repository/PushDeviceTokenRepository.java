package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.PushDeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PushDeviceTokenRepository extends JpaRepository<PushDeviceToken, Long> {

    Optional<PushDeviceToken> findByExpoPushToken(String expoPushToken);

    @Query(value = "SELECT * FROM challengesphere.push_device_tokens WHERE user_id = :userId AND enabled = true", nativeQuery = true)
    List<PushDeviceToken> findEnabledByUserId(@Param("userId") Long userId);

    @Query(value = """
            SELECT pdt.*
            FROM challengesphere.push_device_tokens pdt
            JOIN challengesphere.users u ON u.id = pdt.user_id
            LEFT JOIN challengesphere.user_preferences up ON up.user_id = pdt.user_id
            WHERE pdt.enabled = true
              AND (COALESCE(u.notifications, 1) & 1) = 1
              AND COALESCE(up.push_notifications_enabled, true) = true
            """, nativeQuery = true)
    List<PushDeviceToken> findEnabledReminderTokens();

    @Modifying
    @Query(value = "UPDATE challengesphere.push_device_tokens SET enabled = false, updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId AND expo_push_token = :expoPushToken", nativeQuery = true)
    int disableToken(@Param("userId") Long userId, @Param("expoPushToken") String expoPushToken);
}
