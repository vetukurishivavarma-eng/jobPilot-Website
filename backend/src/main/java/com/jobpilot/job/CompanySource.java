package com.jobpilot.job;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** A company careers board (Greenhouse/Lever) a user follows. */
@Entity
@Table(name = "company_sources")
public class CompanySource {
    @Id
    UUID id = UUID.randomUUID();
    UUID userId;
    @Enumerated(EnumType.STRING)
    JobSources.Ats ats;
    String token;
    String name;
    Instant lastSyncedAt;
    String lastError;
    Instant createdAt = Instant.now();

    protected CompanySource() {}

    CompanySource(UUID userId, JobSources.Ats ats, String token, String name) {
        this.userId = userId;
        this.ats = ats;
        this.token = token;
        this.name = name;
    }

    String board() { return ats.name().toLowerCase() + ":" + token; }
}
