package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.PaymentHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, Long> {
    Page<PaymentHistory> findByUserIdOrderByEventAtDescIdDesc(Long userId, Pageable pageable);

    Optional<PaymentHistory> findFirstByUserIdOrderByEventAtDescIdDesc(Long userId);
}
