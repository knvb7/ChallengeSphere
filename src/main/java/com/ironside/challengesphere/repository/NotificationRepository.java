package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.util.DeliveryStatus;
import com.ironside.challengesphere.util.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);
    
    List<Notification> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(Long userId);
    
    List<Notification> findByDeliveryStatus(DeliveryStatus deliveryStatus);
    
    List<Notification> findByType(NotificationType type);
    
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.user.id = :userId AND n.isRead = false")
    long countUnreadNotificationsByUserId(Long userId);
    
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.user.id = :userId")
    void markAllAsReadForUser(Long userId);
    
    @Modifying
    @Query("UPDATE Notification n SET n.deliveryStatus = :status WHERE n.id = :notificationId")
    void updateDeliveryStatus(Long notificationId, DeliveryStatus status);
}
