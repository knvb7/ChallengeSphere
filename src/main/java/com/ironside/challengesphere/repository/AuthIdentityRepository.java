package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.AuthIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthIdentityRepository extends JpaRepository<AuthIdentity, Long> {
    Optional<AuthIdentity> findByProviderAndSubject(String provider, String subject);
    Optional<AuthIdentity> findByUserIdAndProvider(Long userId, String provider);
}
