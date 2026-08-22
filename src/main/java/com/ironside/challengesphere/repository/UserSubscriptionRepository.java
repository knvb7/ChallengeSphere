package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.UserSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {
    Optional<UserSubscription> findByAppUserId(String appUserId);
}
