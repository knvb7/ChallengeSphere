package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FollowRepository extends JpaRepository<Follow, Long> {

    @Query(value = "SELECT * FROM follows WHERE follower_id = :followerId", nativeQuery = true)
    List<Follow> findByFollowerId(@Param("followerId") Long followerId);

    @Query(value = "SELECT * FROM follows WHERE following_id = :followingId", nativeQuery = true)
    List<Follow> findByFollowingId(@Param("followingId") Long followingId);

    @Query(value = "SELECT * FROM follows WHERE follower_id = :followerId AND following_id = :followingId", nativeQuery = true)
    Optional<Follow> findByFollowerIdAndFollowingId(@Param("followerId") Long followerId, @Param("followingId") Long followingId);

    @Query(value = "SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM follows WHERE follower_id = :followerId AND following_id = :followingId AND active = true", nativeQuery = true)
    boolean existsByFollowerIdAndFollowingIdAndActive(@Param("followerId") Long followerId, @Param("followingId") Long followingId);

    @Query(value = "DELETE FROM follows WHERE follower_id = :followerId AND following_id = :followingId", nativeQuery = true)
    void deleteByFollowerIdAndFollowingId(@Param("followerId") Long followerId, @Param("followingId") Long followingId);

    @Query(value = "SELECT * FROM follows WHERE follower_id = :followerId AND active = true", nativeQuery = true)
    List<Follow> findActiveFollowsByFollowerId(@Param("followerId") Long followerId);

    @Query(value = "SELECT * FROM follows WHERE following_id = :followingId AND active = true", nativeQuery = true)
    List<Follow> findActiveFollowsByFollowingId(@Param("followingId") Long followingId);

    @Query(value = "SELECT COUNT(*) FROM follows WHERE follower_id = :followerId AND active = true", nativeQuery = true)
    Long countActiveFollowingByFollowerId(@Param("followerId") Long followerId);

    @Query(value = "SELECT COUNT(*) FROM follows WHERE following_id = :followingId AND active = true", nativeQuery = true)
    Long countActiveFollowersByFollowingId(@Param("followingId") Long followingId);

    @Query(value = "UPDATE follows SET active = :active WHERE id = :id", nativeQuery = true)
    void updateFollowActiveStatus(@Param("id") Long id, @Param("active") Boolean active);

    @Query(value = "UPDATE follows SET active = false WHERE follower_id = :followerId AND following_id = :followingId", nativeQuery = true)
    void unfollowUser(@Param("followerId") Long followerId, @Param("followingId") Long followingId);
}
