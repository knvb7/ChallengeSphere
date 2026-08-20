package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.UserReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserReportRepository extends JpaRepository<UserReport, Long> {
    List<UserReport> findByStatusOrderByCreatedAtAsc(String status);
}
