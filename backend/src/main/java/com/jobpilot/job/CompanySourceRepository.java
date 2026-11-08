package com.jobpilot.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanySourceRepository extends JpaRepository<CompanySource, UUID> {
    List<CompanySource> findByUserIdOrderByNameAsc(UUID userId);
    Optional<CompanySource> findByIdAndUserId(UUID id, UUID userId);
    boolean existsByUserIdAndAtsAndToken(UUID userId, JobSources.Ats ats, String token);
    long countByUserId(UUID userId);

    @Transactional
    @Modifying
    @Query("update CompanySource c set c.lastSyncedAt = :at, c.lastError = null where c.id = :id")
    void synced(UUID id, Instant at);

    @Transactional
    @Modifying
    @Query("update CompanySource c set c.lastError = :error where c.id = :id")
    void failed(UUID id, String error);
}
