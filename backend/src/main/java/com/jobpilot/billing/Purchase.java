package com.jobpilot.billing;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One Razorpay order for a plan; once PAID it holds `interviews` live-interview credits until expiresAt (null = no expiry). */
@Entity
@Table(name = "purchases")
public class Purchase {
    public enum Status { CREATED, PAID }

    @Id
    UUID id = UUID.randomUUID();
    UUID userId;
    String plan;
    int amountPaise;
    int interviews;
    int used;
    @Enumerated(EnumType.STRING)
    Status status = Status.CREATED;
    String orderId;
    String paymentId;
    Instant expiresAt;
    Instant createdAt = Instant.now();
    Instant paidAt;

    protected Purchase() {}

    Purchase(UUID userId, Plan plan) {
        this.userId = userId;
        this.plan = plan.name();
        this.amountPaise = plan.paise;
        this.interviews = plan.interviews;
    }

    boolean usable(Instant now) { return status == Status.PAID && used < interviews && (expiresAt == null || expiresAt.isAfter(now)); }
}

interface PurchaseRepository extends JpaRepository<Purchase, UUID> {
    List<Purchase> findByUserId(UUID userId);
    Optional<Purchase> findByOrderId(String orderId);
    boolean existsByUserIdAndStatus(UUID userId, Purchase.Status status);
}
