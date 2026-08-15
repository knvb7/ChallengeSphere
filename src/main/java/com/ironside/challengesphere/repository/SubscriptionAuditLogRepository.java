package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.SubscriptionAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionAuditLogRepository extends JpaRepository<SubscriptionAuditLog, Long> {
}
