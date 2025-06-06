package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmailId(String emailId);
    
    Optional<User> findByUsername(String username);
    
    boolean existsByEmailId(String emailId);
    
    Optional<User> findByPhoneNumber(String phoneNumber);

    boolean existsByPhoneNumber(String phoneNumber);

    @Query(value = "SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END " +
            "FROM users " +
            "WHERE username = :username OR email_id = :emailId OR phone_number = :phoneNumber",
            nativeQuery = true)
    boolean existsByUsernameOrEmailOrPhoneNumber(@Param("username") String username,
                                                 @Param("emailId") String emailId,
                                                 @Param("phoneNumber") String phoneNumber);    boolean existsByUsername(String username);
    
    @Modifying
    @Query("UPDATE User u SET u.lastLoginAt = :loginTime WHERE u.userId = :userId")
    void updateLastLoginTime(Long userId, OffsetDateTime loginTime);
}