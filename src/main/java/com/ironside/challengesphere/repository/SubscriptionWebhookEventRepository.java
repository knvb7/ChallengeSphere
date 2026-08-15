package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.SubscriptionWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionWebhookEventRepository extends JpaRepository<SubscriptionWebhookEvent, String> {
}
