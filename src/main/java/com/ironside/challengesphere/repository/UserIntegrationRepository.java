package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.UserIntegration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserIntegrationRepository extends JpaRepository<UserIntegration, Long> {
    Optional<UserIntegration> findByUserIdAndProvider(Long userId, String provider);
    void deleteByUserIdAndProvider(Long userId, String provider);
}
