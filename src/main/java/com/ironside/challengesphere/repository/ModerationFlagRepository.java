package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.ModerationFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModerationFlagRepository extends JpaRepository<ModerationFlag, Long> {

    @Query(value = "SELECT * FROM challengesphere.moderation_flags WHERE reviewed_at IS NULL ORDER BY created_at ASC", nativeQuery = true)
    List<ModerationFlag> findPending();

    @Query(value = "SELECT * FROM challengesphere.moderation_flags WHERE user_id = :userId ORDER BY created_at DESC", nativeQuery = true)
    List<ModerationFlag> findByUserId(@Param("userId") Long userId);
}
