package com.jobpilot.apply;

import com.jobpilot.job.*;
import com.jobpilot.shared.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto mode. Each run: takes the user's open Greenhouse/Lever jobs scoring at or above their minimum, best first,
 * prepares applications for them, up to the daily limit. The browser extension then fills (and with auto-submit,
 * submits) the ready ones in the user's own Chrome. Questions it can't answer wait for the user.
 */
@Service
public class AutoApplyService {
    private static final Logger log = LoggerFactory.getLogger(AutoApplyService.class);

    public record Summary(Instant at, int considered, int prepared, int needYourAnswers, int submitted, int waitingForYou,
                          int failed, List<String> notes) {}

    private final AutoApplySettingsRepository settings;
    private final ApplicationRepository apps;
    private final ApplicationService service;
    private final UserJobRepository links;
    private final MatchService matcher;
    private final TransactionTemplate tx;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    AutoApplyService(AutoApplySettingsRepository settings, ApplicationRepository apps, ApplicationService service,
                     UserJobRepository links, MatchService matcher, TransactionTemplate tx) {
        this.settings = settings;
        this.apps = apps;
        this.service = service;
        this.links = links;
        this.matcher = matcher;
        this.tx = tx;
    }

    AutoApplySettings settings(UUID userId) {
        return settings.findById(userId).orElseGet(() -> new AutoApplySettings(userId));
    }

    boolean running(UUID userId) { return running.contains(userId); }

    long submittedToday(UUID userId) {
        return apps.countByUserIdAndSubmittedAtAfter(userId, LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /** Starts a run in the background (manual "Run now" or the schedule). */
    void start(UUID userId) {
        if (!running.add(userId)) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_RUNNING", "Auto mode is already running");
        Thread.ofVirtual().name("auto-apply-" + userId).start(() -> {
            try { run(userId); }
            catch (Exception e) { log.error("Auto-apply run failed for {}", userId, e); }
            finally { running.remove(userId); }
        });
    }

    Summary run(UUID userId) {
        AutoApplySettings s = settings(userId);
        List<String> notes = new ArrayList<>();
        int prepared = 0, needInput = 0, submitted = 0, waiting = 0, failed = 0;
        long remaining = s.dailyLimit - submittedToday(userId);

        // 1. best-matching open jobs that have no application yet
        List<UUID> picks = pick(userId, s.minScore, Math.max(0, remaining));
        int considered = picks.size();
        if (remaining <= 0) notes.add("Daily limit of " + s.dailyLimit + " reached; nothing new was prepared.");

        // 2. prepare them
        for (UUID jobId : picks) {
            try {
                Application a = service.prepare(userId, jobId, true);
                prepared++;
                if (a.status == Application.Status.NEEDS_INPUT) needInput++;
            } catch (RuntimeException e) {
                failed++;
                notes.add("Couldn't prepare a job: " + e.getMessage());
            }
        }

        // 3. filling and submitting happens in the user's own Chrome: the JobPilot extension works through the READY
        //    auto applications one tab at a time (sites like Lever reject submits from the agent's automated window)
        if (prepared > 0) notes.add(s.autoSubmit
                ? "Prepared. The JobPilot extension in your Chrome fills and submits the ready ones, one tab at a time."
                : "Prepared. The JobPilot extension opens the ready ones filled in, one tab at a time, for you to check and submit.");

        Summary sum = new Summary(Instant.now(), considered, prepared, needInput, submitted, waiting, failed, notes);
        tx.executeWithoutResult(t -> {
            AutoApplySettings fresh = settings(userId);
            fresh.lastRunAt = sum.at();
            fresh.lastSummary = FormReader.JSON.writeValueAsString(sum);
            settings.save(fresh);
        });
        return sum;
    }

    /** Open Greenhouse/Lever jobs in the user's list, scoring at least {@code minScore}, best first, without an application. */
    List<UUID> pick(UUID userId, int minScore, long limit) {
        if (limit <= 0) return List.of();
        MatchScorer.Candidate c = matcher.candidate(userId);
        record Scored(UUID jobId, int score) {}
        return tx.execute(t -> links.search(userId, EnumSet.of(UserJob.Status.NEW, UserJob.Status.SAVED), false, "%%", Pageable.unpaged())
                .getContent().stream()
                .map(UserJob::job)
                .filter(j -> j.source().equals("greenhouse") || j.source().equals("lever"))
                .filter(j -> apps.findByUserIdAndJobId(userId, j.id()).isEmpty())
                .map(j -> new Scored(j.id(), Optional.ofNullable(matcher.score(c, j).score()).orElse(-1)))
                .filter(x -> x.score() >= minScore)
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(limit)
                .map(Scored::jobId)
                .toList());
    }

    /** ponytail: a run every 15 minutes for each user with auto mode on, one after another. */
    @Scheduled(cron = "${jobpilot.auto-apply-cron:0 */15 * * * *}")
    void scheduled() {
        for (AutoApplySettings s : settings.findByEnabledTrue()) {
            if (!running.add(s.userId)) continue;
            try { run(s.userId); }
            catch (Exception e) { log.error("Auto-apply run failed for {}", s.userId, e); }
            finally { running.remove(s.userId); }
        }
    }
}
