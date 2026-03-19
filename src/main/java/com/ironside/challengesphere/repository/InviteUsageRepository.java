package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.InviteUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InviteUsageRepository extends JpaRepository<InviteUsage, Long> {

    @Query(value = "SELECT EXISTS(SELECT 1 FROM challengesphere.invite_usages WHERE invite_id = :inviteId AND user_id = :userId)", nativeQuery = true)
    boolean existsByInviteIdAndUserId(@Param("inviteId") Long inviteId, @Param("userId") Long userId);

    @Query(value = "SELECT * FROM challengesphere.invite_usages WHERE invite_id = :inviteId ORDER BY joined_at DESC", nativeQuery = true)
    List<InviteUsage> findByInviteId(@Param("inviteId") Long inviteId);
}

