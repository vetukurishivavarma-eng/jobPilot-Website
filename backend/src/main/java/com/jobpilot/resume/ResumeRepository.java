package com.jobpilot.resume;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeRepository extends JpaRepository<Resume, UUID> {
    List<Resume> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<Resume> findByIdAndUserId(UUID id, UUID userId);
    long countByUserId(UUID userId);
}
