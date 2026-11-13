package com.jobpilot.job;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/** Upserts fetched postings and links them to a user. Every job write goes through here. */
@Component
public class JobStore {
    public record Outcome(int fetched, int linked, List<Job> jobs) {}

    private final JobRepository jobs;
    private final UserJobRepository links;
    private final TransactionTemplate tx;

    JobStore(JobRepository jobs, UserJobRepository links, TransactionTemplate tx) {
        this.jobs = jobs;
        this.links = links;
        this.tx = tx;
    }

    /**
     * @param fullBoard when the drafts are a board's complete listing, its open jobs missing from it are closed
     * @param link      which drafts to add to the user's list (already-linked ones are left as they are)
     *
     * ponytail: one global lock serialises job writes so two users syncing the same board can't race on the unique key.
     * Per-board locks if sync volume ever makes this a bottleneck.
     */
    public synchronized Outcome save(List<JobDraft> drafts, String fullBoard, UUID userId, UserJob.Origin origin, Predicate<JobDraft> link) {
        return tx.execute(s -> {
            Instant now = Instant.now();
            List<Job> saved = new ArrayList<>();
            List<Job> toLink = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JobDraft d : drafts) {
                if (d.externalId() == null || d.title() == null || !seen.add(d.source() + "|" + d.externalId())) continue;
                Job j = jobs.findBySourceAndExternalId(d.source(), Job.cut(d.externalId(), 300)).orElse(null);
                if (j == null) j = jobs.save(new Job(d, now));
                else j.apply(d, now);
                saved.add(j);
                if (link.test(d)) toLink.add(j);
            }
            if (fullBoard != null) {
                Set<UUID> present = new HashSet<>();
                saved.forEach(j -> present.add(j.id));
                for (Job j : jobs.findByBoardAndClosedAtIsNull(fullBoard)) if (!present.contains(j.id)) j.closedAt = now;
            }
            int linked = 0;
            if (!toLink.isEmpty()) {
                Set<UUID> already = links.linkedAmong(userId, toLink.stream().map(j -> j.id).toList());
                for (Job j : toLink) if (!already.contains(j.id)) { links.save(new UserJob(userId, j, origin)); linked++; }
            }
            return new Outcome(saved.size(), linked, saved);
        });
    }
}
