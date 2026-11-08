package com.jobpilot.job;

import com.jobpilot.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.stream.Stream;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class JobController {

    /** {@code description} is only filled on the detail endpoint. */
    public record JobDto(UUID id, String title, String company, String location, String workplace, String employmentType,
                         Long salaryMin, Long salaryMax, String salaryCurrency, String source, String url, String applyUrl,
                         Instant postedAt, Instant discoveredAt, Instant closedAt, UserJob.Status status, UserJob.Origin origin,
                         List<String> skills, Integer minYears, String seniority, String educationLevel, String description,
                         MatchScorer.Match match) {
        static JobDto of(UserJob uj, boolean withDescription, MatchScorer.Match match) {
            Job j = uj.job;
            return new JobDto(j.id, j.title, j.company, j.location, j.workplace, j.employmentType, j.salaryMin, j.salaryMax,
                    j.salaryCurrency, j.source, j.url, j.applyUrl, j.postedAt, uj.discoveredAt, j.closedAt, uj.status, uj.origin,
                    List.copyOf(j.skills), j.minYears, j.seniority, j.educationLevel, withDescription ? j.description : null, match);
        }
    }
    public record PageDto(List<JobDto> items, long total, int page, int size) {}
    public record StatusRequest(@NotNull UserJob.Status status) {}
    public record ImportRequest(@NotBlank @Size(max = 1000) String url) {}
    public record ManualRequest(@NotBlank @Size(max = 300) String title,
                                @NotBlank @Size(max = 200) String company,
                                @Size(max = 300) String location,
                                @Size(max = 1000) @Pattern(regexp = "^$|^https?://\\S+$", message = "must start with http:// or https://") String url,
                                @NotBlank @Size(max = 50000) String description) {}

    private final UserJobRepository links;
    private final JobStore store;
    private final DiscoveryService discovery;
    private final TransactionTemplate tx;
    private final MatchService matcher;

    JobController(UserJobRepository links, JobStore store, DiscoveryService discovery, TransactionTemplate tx, MatchService matcher) {
        this.matcher = matcher;
        this.links = links;
        this.store = store;
        this.discovery = discovery;
        this.tx = tx;
    }

    /** status: ACTIVE (default: new + saved), NEW, SAVED, DISMISSED or ALL. sort: recent (default) or match. */
    @GetMapping("/jobs")
    @Transactional(readOnly = true)
    public PageDto list(@AuthenticationPrincipal UUID userId,
                        @RequestParam(defaultValue = "ACTIVE") String status,
                        @RequestParam(defaultValue = "") String q,
                        @RequestParam(defaultValue = "false") boolean includeClosed,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size,
                        @RequestParam(defaultValue = "recent") String sort) {
        EnumSet<UserJob.Status> st = switch (status.toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> EnumSet.of(UserJob.Status.NEW, UserJob.Status.SAVED);
            case "ALL" -> EnumSet.allOf(UserJob.Status.class);
            case "NEW", "SAVED", "DISMISSED" -> EnumSet.of(UserJob.Status.valueOf(status.toUpperCase(Locale.ROOT)));
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Unknown status " + status);
        };
        page = Math.max(page, 0);
        size = Math.clamp(size, 1, 100);
        String like = "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
        MatchScorer.Candidate c = matcher.candidate(userId);
        // only jobs in the user's preferred locations, role and skills (profile); filtered in memory, so paging happens here
        // ponytail: scores every job in the list in memory; store scores if a user's list grows past a few thousand
        Stream<JobDto> jobs = links.search(userId, st, includeClosed, like, Pageable.unpaged()).getContent().stream()
                .filter(uj -> matcher.relevant(c, uj.job))
                .map(uj -> JobDto.of(uj, false, matcher.score(c, uj.job)));
        if (sort.equalsIgnoreCase("match"))
            jobs = jobs.sorted(Comparator.comparing((JobDto d) -> d.match().score(), Comparator.nullsLast(Comparator.reverseOrder())));
        List<JobDto> all = jobs.toList();
        int from = Math.min(all.size(), page * size);
        return new PageDto(all.subList(from, Math.min(all.size(), from + size)), all.size(), page, size);
    }

    @GetMapping("/jobs/{id}")
    @Transactional(readOnly = true)
    public JobDto get(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        UserJob uj = own(userId, id);
        return JobDto.of(uj, true, matcher.score(matcher.candidate(userId), uj.job));
    }

    @PatchMapping("/jobs/{id}")
    @Transactional
    public JobDto setStatus(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @Valid @RequestBody StatusRequest req) {
        UserJob uj = own(userId, id);
        uj.status = req.status();
        return JobDto.of(uj, false, null);
    }

    /** Add a posting from a link: Greenhouse/Lever job links via their API, other sites via their schema.org JobPosting data. */
    @PostMapping("/jobs/import")
    @ResponseStatus(HttpStatus.CREATED)
    public JobDto importUrl(@AuthenticationPrincipal UUID userId, @Valid @RequestBody ImportRequest req) {
        return added(userId, JobSources.fromUrl(req.url().strip()));
    }

    /** For sites that block reading (LinkedIn, Naukri...): the user pastes the posting. */
    @PostMapping("/jobs/manual")
    @ResponseStatus(HttpStatus.CREATED)
    public JobDto manual(@AuthenticationPrincipal UUID userId, @Valid @RequestBody ManualRequest r) {
        String url = r.url() == null || r.url().isBlank() ? null : r.url().strip();
        String location = r.location() == null || r.location().isBlank() ? null : r.location().strip();
        return added(userId, new JobDraft("manual", UUID.randomUUID().toString(), null, url, url, r.title().strip(), r.company().strip(),
                location, JobSources.workplaceFrom(location, null), null, null, null, null, r.description().strip(), null));
    }

    @PostMapping("/discovery/run")
    public List<DiscoveryService.SourceResult> run(@AuthenticationPrincipal UUID userId) {
        return discovery.run(userId);
    }

    private JobDto added(UUID userId, JobDraft d) {
        UUID jobId = store.save(List.of(d), null, userId, UserJob.Origin.IMPORT, x -> true).jobs().getFirst().id;
        return tx.execute(s -> JobDto.of(own(userId, jobId), true, null)); // own tx: the save above must commit before its lock is released
    }

    private UserJob own(UUID userId, UUID jobId) {
        return links.find(userId, jobId).orElseThrow(() -> ApiException.notFound("Job"));
    }
}
