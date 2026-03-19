package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query(value = "SELECT * FROM challengesphere.notifications WHERE user_id = :userId ORDER BY sent_at DESC", nativeQuery = true)
    List<Notification> findByUserIdOrderBySentAtDesc(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM challengesphere.notifications WHERE type = :type", nativeQuery = true)
    List<Notification> findByType(@Param("type") String type);

    @Query(value = "SELECT * FROM challengesphere.notifications WHERE user_id = :userId AND type = :type ORDER BY sent_at DESC", nativeQuery = true)
    List<Notification> findByUserIdAndTypeOrderBySentAtDesc(@Param("userId") Long userId, @Param("type") String type);

    @Query(value = "SELECT * FROM challengesphere.notifications WHERE action_object = :actionObject", nativeQuery = true)
    List<Notification> findByActionObject(@Param("actionObject") String actionObject);

    @Query(value = "SELECT * FROM challengesphere.notifications WHERE user_id = :userId AND action_object = :actionObject", nativeQuery = true)
    List<Notification> findByUserIdAndActionObject(@Param("userId") Long userId, @Param("actionObject") String actionObject);

    @Query(value = "SELECT * FROM challengesphere.notifications ORDER BY sent_at DESC LIMIT :limit", nativeQuery = true)
    List<Notification> findRecentNotifications(@Param("limit") Integer limit);

    @Query(value = "SELECT * FROM challengesphere.notifications WHERE user_id = :userId ORDER BY sent_at DESC LIMIT :limit", nativeQuery = true)
    List<Notification> findRecentNotificationsByUserId(@Param("userId") Long userId, @Param("limit") Integer limit);

    @Query(value = "SELECT COUNT(*) FROM challengesphere.notifications WHERE user_id = :userId", nativeQuery = true)
    Long countNotificationsByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT COUNT(*) FROM challengesphere.notifications WHERE user_id = :userId AND type = :type", nativeQuery = true)
    Long countNotificationsByUserIdAndType(@Param("userId") Long userId, @Param("type") String type);

    @Modifying
    @Query(value = "DELETE FROM challengesphere.notifications WHERE user_id = :userId", nativeQuery = true)
    void deleteByUserId(@Param("userId") Long userId);

    @Modifying
    @Query(value = "DELETE FROM challengesphere.notifications WHERE user_id = :userId AND type = :type", nativeQuery = true)
    void deleteByUserIdAndType(@Param("userId") Long userId, @Param("type") String type);
}
