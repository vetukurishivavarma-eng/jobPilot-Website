package com.jobpilot.resume;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "resumes")
public class Resume {
    @Id
    UUID id = UUID.randomUUID();
    UUID userId;
    String label;
    String originalFilename;
    String contentType;
    long sizeBytes;
    String sha256;
    /** File name on disk/object store. Random, never derived from user input. */
    String storageKey = UUID.randomUUID().toString();
    String extractedText;
    @Column(name = "is_primary")
    boolean primary;
    Instant createdAt = Instant.now();

    protected Resume() {}

    Resume(UUID userId) { this.userId = userId; }

    // read-only view for the apply agent
    public UUID id() { return id; }
    public String label() { return label; }
    public String originalFilename() { return originalFilename; }
    public String storageKey() { return storageKey; }
    public String contentType() { return contentType; }
    public String extractedText() { return extractedText; }
    public boolean isPrimary() { return primary; }
}
