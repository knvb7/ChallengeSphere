package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByProviderAndStoreAndProviderTransactionId(
            String provider,
            String store,
            String providerTransactionId
    );
}
