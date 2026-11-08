package com.jobpilot.apply;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "applications")
public class Application {
    public enum Status { NEEDS_INPUT, READY, FILLING, SUBMITTED, FAILED }

    @Id
    UUID id = UUID.randomUUID();
    UUID userId;
    UUID jobId;
    @Enumerated(EnumType.STRING)
    Status status;
    UUID resumeId;
    /** JSON list of {@link FormField}. */
    String fields;
    String error;
    String screenshotKey;
    /** Prepared by auto mode (only these are submitted automatically). */
    boolean auto;
    Instant createdAt = Instant.now();
    Instant updatedAt = Instant.now();
    Instant submittedAt;

    protected Application() {}

    Application(UUID userId, UUID jobId) {
        this.userId = userId;
        this.jobId = jobId;
    }
}
