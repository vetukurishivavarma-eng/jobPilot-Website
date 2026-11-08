package com.jobpilot.job;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** A job in one user's list. */
@Entity
@Table(name = "user_jobs")
public class UserJob {
    public enum Origin { COMPANY, SEARCH, IMPORT }
    public enum Status { NEW, SAVED, DISMISSED }

    @Id
    UUID id = UUID.randomUUID();
    UUID userId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id")
    Job job;
    @Enumerated(EnumType.STRING)
    Origin origin;
    @Enumerated(EnumType.STRING)
    Status status = Status.NEW;
    Instant discoveredAt = Instant.now();

    protected UserJob() {}

    UserJob(UUID userId, Job job, Origin origin) {
        this.userId = userId;
        this.job = job;
        this.origin = origin;
    }
}
