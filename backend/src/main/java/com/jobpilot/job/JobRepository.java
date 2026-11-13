package com.jobpilot.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {
    Optional<Job> findBySourceAndExternalId(String source, String externalId);
    List<Job> findByBoardAndClosedAtIsNull(String board);
    Optional<Job> findFirstBySourceAndApplyUrlContaining(String source, String part);
}
