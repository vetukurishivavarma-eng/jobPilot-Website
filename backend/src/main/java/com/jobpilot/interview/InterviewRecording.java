package com.jobpilot.interview;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The candidate's recording of one live interview. Downloadable for {@link #TTL}, then purged. */
@Entity
@Table(name = "interview_recordings")
public class InterviewRecording {
    static final Duration TTL = Duration.ofHours(1);
    static final int MAX_BYTES = 40 * 1024 * 1024; // ~20 min at the 240p/250 kbps the page records

    @Id
    UUID sessionId;
    UUID userId;
    String contentType;
    byte[] content;
    Instant createdAt = Instant.now();

    protected InterviewRecording() {}

    InterviewRecording(UUID sessionId, UUID userId, String contentType, byte[] content) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.contentType = contentType;
        this.content = content;
    }
}

interface InterviewRecordingRepository extends JpaRepository<InterviewRecording, UUID> {
    @Query("select r.createdAt from InterviewRecording r where r.sessionId = :id")
    Optional<Instant> createdAt(UUID id);

    @Modifying
    @Transactional
    @Query("delete from InterviewRecording r where r.createdAt < :before")
    int deleteOlderThan(Instant before);
}
