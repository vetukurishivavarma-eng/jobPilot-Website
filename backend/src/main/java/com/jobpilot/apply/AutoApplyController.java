package com.jobpilot.apply;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/auto-apply")
public class AutoApplyController {

    public record SettingsDto(boolean enabled, boolean autoSubmit, int minScore, int dailyLimit,
                              boolean running, long submittedToday, Instant lastRunAt, AutoApplyService.Summary lastSummary) {}
    public record SettingsRequest(boolean enabled, boolean autoSubmit, @Min(0) @Max(100) int minScore, @Min(1) @Max(50) int dailyLimit) {}

    private final AutoApplyService auto;
    private final AutoApplySettingsRepository repo;

    AutoApplyController(AutoApplyService auto, AutoApplySettingsRepository repo) {
        this.auto = auto;
        this.repo = repo;
    }

    @GetMapping
    public SettingsDto get(@AuthenticationPrincipal UUID userId) {
        AutoApplySettings s = auto.settings(userId);
        return new SettingsDto(s.enabled, s.autoSubmit, s.minScore, s.dailyLimit, auto.running(userId), auto.submittedToday(userId),
                s.lastRunAt, s.lastSummary == null ? null : FormReader.JSON.readValue(s.lastSummary, AutoApplyService.Summary.class));
    }

    @PutMapping
    public SettingsDto put(@AuthenticationPrincipal UUID userId, @Valid @RequestBody SettingsRequest r) {
        AutoApplySettings s = auto.settings(userId);
        s.enabled = r.enabled();
        s.autoSubmit = r.autoSubmit();
        s.minScore = r.minScore();
        s.dailyLimit = r.dailyLimit();
        repo.save(s);
        return get(userId);
    }

    /** Runs auto mode now, in the background; poll GET for the result. */
    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SettingsDto run(@AuthenticationPrincipal UUID userId) {
        auto.start(userId);
        return get(userId);
    }
}
