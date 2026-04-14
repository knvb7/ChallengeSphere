package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    @Query(value = "SELECT * FROM challengesphere.users WHERE username = :username", nativeQuery = true)
    Optional<User> findByUsername(@Param("username") String username);

    @Query(value = "SELECT * FROM challengesphere.users WHERE email_id = :emailId", nativeQuery = true)
    Optional<User> findByEmailId(@Param("emailId") String emailId);

    @Query(value = "SELECT * FROM challengesphere.users WHERE phone = :phone", nativeQuery = true)
    Optional<User> findByPhone(@Param("phone") String phone);

    @Query(value = "SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END " +
           "FROM challengesphere.users WHERE username = :username OR email_id = :emailId OR phone = :phone",
           nativeQuery = true)
    boolean existsByUsernameOrEmailIdOrPhone(@Param("username") String username,
                                             @Param("emailId") String emailId,
                                             @Param("phone") String phone);

    @Query(value = "SELECT * FROM challengesphere.users WHERE active = true", nativeQuery = true)
    List<User> findAllActiveUsers();

    @Query(value = "SELECT * FROM challengesphere.users WHERE premium_user = 1", nativeQuery = true)
    List<User> findAllPremiumUsers();

    @Query(value = "SELECT * FROM challengesphere.users WHERE username ILIKE %:searchTerm% OR email_id ILIKE %:searchTerm%",
           nativeQuery = true)
    List<User> searchUsersByUsernameOrEmail(@Param("searchTerm") String searchTerm);

    @Modifying
    @Query(value = "UPDATE challengesphere.users SET active = :active WHERE id = :id", nativeQuery = true)
    void updateUserActiveStatus(@Param("id") Long id, @Param("active") Boolean active);

    @Modifying
    @Query(value = "UPDATE challengesphere.users SET followers = followers + :increment WHERE id = :id", nativeQuery = true)
    void updateFollowersCount(@Param("id") Long id, @Param("increment") Integer increment);

    @Modifying
    @Query(value = "UPDATE challengesphere.users SET following = following + :increment WHERE id = :id", nativeQuery = true)
    void updateFollowingCount(@Param("id") Long id, @Param("increment") Integer increment);

    @Modifying
    @Query(value = "UPDATE challengesphere.users SET last_login_at = NOW() WHERE id = :id", nativeQuery = true)
    void updateLastLoginAt(@Param("id") Long id);

    @Modifying
    @Query(value = "UPDATE challengesphere.users SET last_login_at = NOW() WHERE id IN :ids", nativeQuery = true)
    int batchUpdateLastLoginAt(@Param("ids") List<Long> ids);

    @Query(value = "SELECT * FROM challengesphere.users WHERE last_login_at >= NOW() - CAST(:days || ' days' AS INTERVAL) AND active = true", nativeQuery = true)
    List<User> findRecentlyActiveUsers(@Param("days") int days);
}