package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Following;
import com.ironside.challengesphere.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface FollowingRepository extends JpaRepository<Following, Long> {
    Optional<Following> findByFollowerAndFollowed(User follower, User followed);
    void deleteByFollowerAndFollowed(User follower, User followed);
    boolean existsByFollowerAndFollowed(User follower, User followed);

    @Query(value = "SELECT COUNT(f) FROM challengesphere.followers f WHERE f.followed_id = :userId", nativeQuery = true)
    Long findFollowersCount(Long userId);

    @Query(value = "SELECT COUNT(f) FROM challengesphere.followers f WHERE f.follower_id = :userId", nativeQuery = true)
    Long findFollowedByCount(Long userId);

    boolean existsByFollowerUserIdAndFollowedUserId(Long userId, Long targetUserId);
}
