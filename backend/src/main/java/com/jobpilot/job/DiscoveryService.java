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

import java.util.*;
import java.util.concurrent.*;

/**
 * Pulls jobs from every company in the catalog (resources/catalog.txt) and, when configured, Adzuna searches for the
 * user's desired roles and locations. Everyone sees the same catalog: no following. Only jobs in India or remote-to-India
 * are kept (the product's market); the job list then narrows to each user's preferred locations.
 */
@Service
public class DiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    /** One line of a run's report. {@code error} is null when the source worked. */
    public record SourceResult(String source, String label, int fetched, int linked, String error) {}
    record Board(JobSources.Ats ats, String token, String name) {
        String key() { return ats.name().toLowerCase() + ":" + token; }
    }

    static final int MAX_SEARCH_ROLES = 3, MAX_SEARCH_LOCATIONS = 3;
    static final List<Board> CATALOG = loadCatalog();

    private final ProfileRepository profiles;
    private final UserRepository users;
    private final JobStore store;
    private final AppProperties props;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    DiscoveryService(ProfileRepository profiles, UserRepository users, JobStore store, AppProperties props) {
        this.profiles = profiles;
        this.users = users;
        this.store = store;
        this.props = props;
    }

    static List<Board> loadCatalog() {
        try (var in = DiscoveryService.class.getResourceAsStream("/catalog.txt")) {
            List<Board> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+", 2), b = parts[0].split(":", 2);
                out.add(new Board(JobSources.Ats.valueOf(b[0].toUpperCase(Locale.ROOT)), b[1], parts.length > 1 ? parts[1] : b[1]));
            }
            return List.copyOf(out);
        } catch (java.io.IOException | NullPointerException e) {
            throw new IllegalStateException("catalog.txt missing from the classpath", e);
        }
    }

    /** "Find jobs now": the whole catalog for this user. */
    public List<SourceResult> run(UUID userId) {
        if (!running.add(userId))
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_RUNNING", "A job search is already running for you. Try again in a minute.");
        try {
            return syncAll(List.of(userId));
        } finally {
            running.remove(userId);
        }
    }

    /** Fetches each board once and adds its India/remote jobs to every given user's list. */
    List<SourceResult> syncAll(List<UUID> userIds) {
        List<Callable<SourceResult>> tasks = new ArrayList<>();
        for (Board b : CATALOG) tasks.add(() -> sync(b, userIds));
        if (props.adzunaEnabled())
            for (UUID u : userIds) {
                List<String> locs = profiles.desiredLocations(u);
                for (String role : profiles.desiredRoles(u).stream().limit(MAX_SEARCH_ROLES).toList())
                    for (String loc : locs.isEmpty() ? Collections.<String>singletonList(null) : locs.stream().limit(MAX_SEARCH_LOCATIONS).toList())
                        tasks.add(() -> search(u, role, loc));
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
    }

    SourceResult sync(Board b, List<UUID> userIds) {
        try {
            List<JobDraft> drafts = switch (b.ats()) {
                case GREENHOUSE -> JobSources.greenhouseBoard(b.token(), b.name());
                case LEVER -> JobSources.leverBoard(b.token(), b.name());
                case WORKDAY -> JobSources.workdayBoard(b.token(), b.name());
            };
            int fetched = 0, linked = 0;
            for (UUID u : userIds) {
                JobStore.Outcome o = store.save(drafts, b.key(), u, UserJob.Origin.COMPANY, d -> MatchScorer.inMarket(d.location(), d.workplace()));
                fetched = o.fetched();
                linked += o.linked();
            }
            return new SourceResult("company", b.name(), fetched, linked, null);
        } catch (RuntimeException e) {
            return new SourceResult("company", b.name(), 0, 0, message(e));
        }
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

    /** ponytail: links every catalog job to every user (one row each); move to a shared feed if users reach thousands. */
    @Scheduled(cron = "${jobpilot.discovery-cron:0 0 */6 * * *}")
    void scheduled() {
        List<UUID> all = users.allIds().stream().filter(running::add).toList();
        try { syncAll(all); }
        catch (RuntimeException e) { log.error("Scheduled discovery failed", e); }
        finally { all.forEach(running::remove); }
    }
}
