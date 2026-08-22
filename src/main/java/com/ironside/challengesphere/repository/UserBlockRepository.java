package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.UserBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    @Query(value = "SELECT * FROM challengesphere.user_blocks WHERE blocker_user_id = :blockerUserId AND blocked_user_id = :blockedUserId", nativeQuery = true)
    Optional<UserBlock> findByBlockerUserIdAndBlockedUserId(@Param("blockerUserId") Long blockerUserId,
                                                            @Param("blockedUserId") Long blockedUserId);

    @Query(value = "SELECT * FROM challengesphere.user_blocks WHERE blocker_user_id = :blockerUserId AND active = true ORDER BY created_at DESC", nativeQuery = true)
    List<UserBlock> findActiveByBlockerUserId(@Param("blockerUserId") Long blockerUserId);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM challengesphere.user_blocks WHERE blocker_user_id = :blockerUserId AND blocked_user_id = :blockedUserId AND active = true)", nativeQuery = true)
    boolean existsActiveBlock(@Param("blockerUserId") Long blockerUserId,
                              @Param("blockedUserId") Long blockedUserId);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM challengesphere.user_blocks WHERE active = true AND ((blocker_user_id = :firstUserId AND blocked_user_id = :secondUserId) OR (blocker_user_id = :secondUserId AND blocked_user_id = :firstUserId)))", nativeQuery = true)
    boolean existsActiveBlockBetween(@Param("firstUserId") Long firstUserId,
                                     @Param("secondUserId") Long secondUserId);

    @Query(value = """
            SELECT CASE
                     WHEN blocker_user_id = :viewerUserId THEN blocked_user_id
                     ELSE blocker_user_id
                   END
            FROM challengesphere.user_blocks
            WHERE active = true
              AND (blocker_user_id = :viewerUserId OR blocked_user_id = :viewerUserId)
            """, nativeQuery = true)
    List<Long> findActiveBlockedCounterpartIds(@Param("viewerUserId") Long viewerUserId);
}
