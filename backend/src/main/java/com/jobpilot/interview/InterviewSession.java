package com.jobpilot.interview;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "interview_sessions")
public class InterviewSession {
    @Id
    UUID id = UUID.randomUUID();
    UUID userId;
    UUID jobId;
    String title;
    String content;
    Instant createdAt = Instant.now();
    Instant updatedAt = Instant.now();

    protected InterviewSession() {}

    InterviewSession(UUID userId, UUID jobId, String title) {
        this.userId = userId;
        this.jobId = jobId;
        this.title = title;
    }
}

interface InterviewSessionRepository extends JpaRepository<InterviewSession, UUID> {
    List<InterviewSession> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<InterviewSession> findByIdAndUserId(UUID id, UUID userId);
}
