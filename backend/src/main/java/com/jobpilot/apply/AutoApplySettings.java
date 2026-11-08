package com.jobpilot.apply;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "auto_apply_settings")
public class AutoApplySettings {
    @Id
    UUID userId;
    boolean enabled;
    boolean autoSubmit;
    int minScore = 70;
    int dailyLimit = 10;
    Instant lastRunAt;
    String lastSummary;

    protected AutoApplySettings() {}

    AutoApplySettings(UUID userId) { this.userId = userId; }
}

interface AutoApplySettingsRepository extends JpaRepository<AutoApplySettings, UUID> {
    List<AutoApplySettings> findByEnabledTrue();
}
