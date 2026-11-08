package com.jobpilot.job;

import com.jobpilot.auth.UserRepository;
import com.jobpilot.config.AppProperties;
import com.jobpilot.profile.ProfileRepository;
import com.jobpilot.shared.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Pulls jobs from the boards a user follows and (when configured) Adzuna searches for their desired roles. */
@Service
public class DiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    /** One line of a run's report. {@code error} is null when the source worked. */
    public record SourceResult(String source, String label, int fetched, int linked, String error) {}

    static final int MAX_SEARCH_ROLES = 3, MAX_SEARCH_LOCATIONS = 3;

    private final CompanySourceRepository companies;
    private final ProfileRepository profiles;
    private final UserRepository users;
    private final JobStore store;
    private final AppProperties props;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    DiscoveryService(CompanySourceRepository companies, ProfileRepository profiles, UserRepository users, JobStore store, AppProperties props) {
        this.companies = companies;
        this.profiles = profiles;
        this.users = users;
        this.store = store;
        this.props = props;
    }

    public List<SourceResult> run(UUID userId) {
        if (!running.add(userId))
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_RUNNING", "A job search is already running for you. Try again in a minute.");
        try {
            List<String> roles = profiles.desiredRoles(userId);
            List<Callable<SourceResult>> tasks = new ArrayList<>();
            for (CompanySource c : companies.findByUserIdOrderByNameAsc(userId)) tasks.add(() -> sync(c, roles));
            if (props.adzunaEnabled()) {
                List<String> locs = profiles.desiredLocations(userId);
                for (String role : roles.stream().limit(MAX_SEARCH_ROLES).toList())
                    for (String loc : locs.isEmpty() ? Collections.<String>singletonList(null) : locs.stream().limit(MAX_SEARCH_LOCATIONS).toList())
                        tasks.add(() -> search(userId, role, loc));
            }
            try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
                List<SourceResult> out = new ArrayList<>();
                for (Future<SourceResult> f : ex.invokeAll(tasks)) out.add(f.get()); // tasks report their own failures
                return out;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        } finally {
            running.remove(userId);
        }
    }

    /** Syncs one followed board; links only jobs whose title matches one of the user's desired roles. */
    public SourceResult sync(CompanySource c, List<String> roles) {
        try {
            List<JobDraft> drafts = c.ats == JobSources.Ats.GREENHOUSE
                    ? JobSources.greenhouseBoard(c.token, c.name) : JobSources.leverBoard(c.token, c.name);
            JobStore.Outcome o = store.save(drafts, c.board(), c.userId, UserJob.Origin.COMPANY, d -> RoleMatch.matches(d.title(), roles));
            companies.synced(c.id, Instant.now());
            return new SourceResult("company", c.name, o.fetched(), o.linked(), null);
        } catch (RuntimeException e) {
            String msg = message(e);
            companies.failed(c.id, Job.cut(msg, 300));
            return new SourceResult("company", c.name, 0, 0, msg);
        }
    }

    public SourceResult sync(CompanySource c) {
        return sync(c, profiles.desiredRoles(c.userId));
    }

    private SourceResult search(UUID userId, String role, String location) {
        boolean remote = location != null && location.strip().equalsIgnoreCase("remote");
        String label = role + (location == null ? "" : " · " + location);
        try {
            List<JobDraft> drafts = JobSources.adzunaSearch(props.adzunaAppId(), props.adzunaAppKey(), props.adzunaCountry(),
                    remote ? role + " remote" : role, remote ? null : location);
            JobStore.Outcome o = store.save(drafts, null, userId, UserJob.Origin.SEARCH, d -> true);
            return new SourceResult("adzuna", label, o.fetched(), o.linked(), null);
        } catch (RuntimeException e) {
            return new SourceResult("adzuna", label, 0, 0, message(e));
        }
    }

    private static String message(RuntimeException e) {
        if (e instanceof SafeHttp.FetchException) return e.getMessage();
        log.error("Discovery source failed", e);
        return "Unexpected error while reading this source";
    }

    /** ponytail: sequential over all users and refetches a board once per follower; batch by board when users grow. */
    @Scheduled(cron = "${jobpilot.discovery-cron:0 0 */6 * * *}")
    void scheduled() {
        for (UUID u : users.allIds()) {
            try { run(u); } catch (ApiException ignored) { /* user's manual run is in progress */ }
        }
    }
}
