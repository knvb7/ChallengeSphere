package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.FeatureRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FeatureRequestRepository extends JpaRepository<FeatureRequest, Long> {
}
