package com.jobpilot.tailor;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "tailored_documents")
public class TailoredDocument {
    public enum Kind { RESUME, LETTER }

    @Id
    UUID id = UUID.randomUUID();
    UUID userId;
    UUID jobId;
    @Enumerated(EnumType.STRING)
    Kind kind;
    String content;
    Instant createdAt = Instant.now();
    Instant updatedAt = Instant.now();

    protected TailoredDocument() {}

    TailoredDocument(UUID userId, UUID jobId, Kind kind) {
        this.userId = userId;
        this.jobId = jobId;
        this.kind = kind;
    }
}

interface TailoredDocumentRepository extends JpaRepository<TailoredDocument, UUID> {
    Optional<TailoredDocument> findByUserIdAndJobIdAndKind(UUID userId, UUID jobId, TailoredDocument.Kind kind);
    List<TailoredDocument> findByUserIdAndJobId(UUID userId, UUID jobId);
    Optional<TailoredDocument> findByIdAndUserId(UUID id, UUID userId);
}
