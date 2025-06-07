package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Challenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChallengeRepository extends JpaRepository<Challenge, Long> {
    List<Challenge> findByCreatorUserId(Long creatorUserId);
}
