package com.jobpilot.resume;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Resume file bytes, kept in the DB so they survive redeploys (Render's disk is ephemeral). */
@Entity
@Table(name = "resume_files")
class ResumeBlob {
    @Id
    @Column(name = "storage_key")
    String storageKey;
    byte[] content;

    protected ResumeBlob() {}

    ResumeBlob(String storageKey, byte[] content) {
        this.storageKey = storageKey;
        this.content = content;
    }

    byte[] content() { return content; }
}
