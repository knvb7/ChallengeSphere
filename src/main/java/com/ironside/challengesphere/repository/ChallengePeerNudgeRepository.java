package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.ChallengePeerNudge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChallengePeerNudgeRepository extends JpaRepository<ChallengePeerNudge, Long> {
}
