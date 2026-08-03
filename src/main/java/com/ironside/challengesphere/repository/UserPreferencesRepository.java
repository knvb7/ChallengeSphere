package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.UserPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserPreferencesRepository extends JpaRepository<UserPreferences, Long> {

    @Query(value = "SELECT * FROM challengesphere.user_preferences WHERE user_id = :userId", nativeQuery = true)
    Optional<UserPreferences> findByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM challengesphere.user_preferences WHERE user_id IN :userIds", nativeQuery = true)
    List<UserPreferences> findByUserIdIn(@Param("userIds") Collection<Long> userIds);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM challengesphere.user_preferences WHERE user_id = :userId)", nativeQuery = true)
    boolean existsByUserId(@Param("userId") Long userId);

    // Individual field update queries — same pattern as UserRepository
    @Modifying
    @Query(value = "UPDATE challengesphere.user_preferences SET push_notifications_enabled = :val, updated_at = NOW() WHERE user_id = :userId", nativeQuery = true)
    void updatePushNotificationsEnabled(@Param("userId") Long userId, @Param("val") Boolean val);

    @Modifying
    @Query(value = "UPDATE challengesphere.user_preferences SET email_notifications_enabled = :val, updated_at = NOW() WHERE user_id = :userId", nativeQuery = true)
    void updateEmailNotificationsEnabled(@Param("userId") Long userId, @Param("val") Boolean val);

    @Modifying
    @Query(value = "UPDATE challengesphere.user_preferences SET notify_on_glory = :val, updated_at = NOW() WHERE user_id = :userId", nativeQuery = true)
    void updateNotifyOnGlory(@Param("userId") Long userId, @Param("val") Boolean val);

    @Modifying
    @Query(value = "UPDATE challengesphere.user_preferences SET notify_on_comment = :val, updated_at = NOW() WHERE user_id = :userId", nativeQuery = true)
    void updateNotifyOnComment(@Param("userId") Long userId, @Param("val") Boolean val);

    @Modifying
    @Query(value = "UPDATE challengesphere.user_preferences SET notify_on_follow = :val, updated_at = NOW() WHERE user_id = :userId", nativeQuery = true)
    void updateNotifyOnFollow(@Param("userId") Long userId, @Param("val") Boolean val);

    @Modifying
    @Query(value = "UPDATE challengesphere.user_preferences SET notify_on_challenge_invite = :val, updated_at = NOW() WHERE user_id = :userId", nativeQuery = true)
    void updateNotifyOnChallengeInvite(@Param("userId") Long userId, @Param("val") Boolean val);

    @Modifying
    @Query(value = "UPDATE challengesphere.user_preferences SET notify_on_challenge_update = :val, updated_at = NOW() WHERE user_id = :userId", nativeQuery = true)
    void updateNotifyOnChallengeUpdate(@Param("userId") Long userId, @Param("val") Boolean val);

    @Modifying
    @Query(value = "UPDATE challengesphere.user_preferences SET notify_on_motivation = :val, updated_at = NOW() WHERE user_id = :userId", nativeQuery = true)
    void updateNotifyOnMotivation(@Param("userId") Long userId, @Param("val") Boolean val);

    @Modifying
    @Query(value = "UPDATE challengesphere.user_preferences SET private_profile = :val, updated_at = NOW() WHERE user_id = :userId", nativeQuery = true)
    void updatePrivateProfile(@Param("userId") Long userId, @Param("val") Boolean val);

    @Modifying
    @Query(value = "UPDATE challengesphere.user_preferences SET private_submissions = :val, updated_at = NOW() WHERE user_id = :userId", nativeQuery = true)
    void updatePrivateSubmissions(@Param("userId") Long userId, @Param("val") Boolean val);

    @Modifying
    @Query(value = "UPDATE challengesphere.user_preferences SET show_on_leaderboard = :val, updated_at = NOW() WHERE user_id = :userId", nativeQuery = true)
    void updateShowOnLeaderboard(@Param("userId") Long userId, @Param("val") Boolean val);

    @Modifying
    @Query(value = "UPDATE challengesphere.user_preferences SET allow_challenge_invites = :val, updated_at = NOW() WHERE user_id = :userId", nativeQuery = true)
    void updateAllowChallengeInvites(@Param("userId") Long userId, @Param("val") Boolean val);

    @Modifying
    @Query(value = "UPDATE challengesphere.user_preferences SET dark_mode = :val, updated_at = NOW() WHERE user_id = :userId", nativeQuery = true)
    void updateDarkMode(@Param("userId") Long userId, @Param("val") Boolean val);

    @Modifying
    @Query(value = "UPDATE challengesphere.user_preferences SET language = :val, updated_at = NOW() WHERE user_id = :userId", nativeQuery = true)
    void updateLanguage(@Param("userId") Long userId, @Param("val") String val);
}
