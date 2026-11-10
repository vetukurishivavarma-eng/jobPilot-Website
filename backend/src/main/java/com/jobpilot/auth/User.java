package com.jobpilot.auth;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
    @Id
    private UUID id = UUID.randomUUID();
    private String email;
    private String passwordHash;
    private String fullName;
    private Instant createdAt = Instant.now();

    protected User() {}

    public User(String email, String passwordHash, String fullName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFullName() { return fullName; }
    public Instant getCreatedAt() { return createdAt; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
