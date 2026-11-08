package com.jobpilot.job;

import com.jobpilot.job.JobSources.Ats;
import com.jobpilot.shared.ApiException;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Company career boards the user follows. Following one syncs it straight away. */
@RestController
@RequestMapping("/api/companies")
public class CompanyController {
    static final int MAX_PER_USER = 50;

    public record CompanyDto(UUID id, Ats ats, String token, String name, String boardUrl, Instant lastSyncedAt, String lastError) {
        static CompanyDto of(CompanySource c) {
            return new CompanyDto(c.id, c.ats, c.token, c.name, CompanyController.boardUrl(c.ats, c.token), c.lastSyncedAt, c.lastError);
        }
    }
    public record FollowRequest(@Size(max = 500) String url, Ats ats, @Size(max = 100) String token) {}
    public record FollowResult(CompanyDto company, DiscoveryService.SourceResult sync) {}
    public record Suggestion(Ats ats, String token, String name, boolean following) {}

    /** Boards confirmed live on 2026-11-08. */
    static final List<Suggestion> SUGGESTIONS = List.of(
            new Suggestion(Ats.GREENHOUSE, "razorpaysoftwareprivatelimited", "Razorpay", false),
            new Suggestion(Ats.GREENHOUSE, "groww", "Groww", false),
            new Suggestion(Ats.GREENHOUSE, "druva", "Druva", false),
            new Suggestion(Ats.GREENHOUSE, "inmobi", "InMobi", false),
            new Suggestion(Ats.GREENHOUSE, "rubrik", "Rubrik", false),
            new Suggestion(Ats.GREENHOUSE, "databricks", "Databricks", false),
            new Suggestion(Ats.GREENHOUSE, "stripe", "Stripe", false),
            // checked 2026-11-08: engineering openings in India
            new Suggestion(Ats.GREENHOUSE, "okta", "Okta", false),
            new Suggestion(Ats.GREENHOUSE, "twilio", "Twilio", false),
            new Suggestion(Ats.GREENHOUSE, "hackerrank", "HackerRank", false),
            new Suggestion(Ats.GREENHOUSE, "slice", "slice", false),
            new Suggestion(Ats.LEVER, "hevodata", "Hevo Data", false),
            new Suggestion(Ats.LEVER, "cred", "CRED", false),
            new Suggestion(Ats.LEVER, "meesho", "Meesho", false),
            new Suggestion(Ats.LEVER, "zeta", "Zeta", false),
            new Suggestion(Ats.LEVER, "paytm", "Paytm", false),
            new Suggestion(Ats.LEVER, "fampay", "FamPay", false));

    private final CompanySourceRepository companies;
    private final DiscoveryService discovery;

    CompanyController(CompanySourceRepository companies, DiscoveryService discovery) {
        this.companies = companies;
        this.discovery = discovery;
    }

    @GetMapping
    public List<CompanyDto> list(@AuthenticationPrincipal UUID userId) {
        return companies.findByUserIdOrderByNameAsc(userId).stream().map(CompanyDto::of).toList();
    }

    @GetMapping("/suggestions")
    public List<Suggestion> suggestions(@AuthenticationPrincipal UUID userId) {
        Set<String> followed = companies.findByUserIdOrderByNameAsc(userId).stream().map(CompanySource::board).collect(Collectors.toSet());
        return SUGGESTIONS.stream()
                .map(s -> new Suggestion(s.ats(), s.token(), s.name(), followed.contains(s.ats().name().toLowerCase() + ":" + s.token())))
                .toList();
    }

    /** Takes a careers link (boards.greenhouse.io/x, jobs.lever.co/x) or ats + token. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FollowResult follow(@AuthenticationPrincipal UUID userId, @RequestBody FollowRequest req) {
        Ats ats;
        String token;
        if (req.url() != null && !req.url().isBlank()) {
            JobSources.BoardRef ref = JobSources.parseBoardUrl(req.url());
            if (ref == null) throw bad("Paste a Greenhouse (boards.greenhouse.io/…) or Lever (jobs.lever.co/…) careers link");
            ats = ref.ats();
            token = ref.token();
        } else {
            if (req.ats() == null || req.token() == null || !JobSources.TOKEN.matcher(req.token()).matches())
                throw bad("Give a careers link, or an ATS and board name");
            ats = req.ats();
            token = req.token();
        }
        token = token.toLowerCase();
        if (companies.existsByUserIdAndAtsAndToken(userId, ats, token))
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_FOLLOWING", "You already follow this company");
        if (companies.countByUserId(userId) >= MAX_PER_USER)
            throw new ApiException(HttpStatus.CONFLICT, "COMPANY_LIMIT", "You can follow up to " + MAX_PER_USER + " companies");

        CompanySource c = companies.save(new CompanySource(userId, ats, token, validatedName(ats, token)));
        return new FollowResult(CompanyDto.of(c), discovery.sync(c));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfollow(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        companies.delete(companies.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Company")));
    }

    /** Confirms the board exists and returns the company's display name. Jobs already in the user's list stay there. */
    private static String validatedName(Ats ats, String token) {
        String known = SUGGESTIONS.stream().filter(s -> s.ats() == ats && s.token().equals(token)).map(Suggestion::name).findFirst().orElse(null);
        try {
            if (ats == Ats.GREENHOUSE) {
                String name = JobSources.greenhouseBoardName(token);
                return known != null ? known : name != null ? Job.cut(name, 200) : JobSources.prettify(token);
            }
            JobSources.leverCheck(token);
            return known != null ? known : JobSources.prettify(token);
        } catch (SafeHttp.FetchException e) {
            if (e.status == 404) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BOARD_NOT_FOUND",
                    "No " + (ats == Ats.GREENHOUSE ? "Greenhouse" : "Lever") + " board called \"" + token + "\"");
            throw e;
        }
    }

    static String boardUrl(Ats ats, String token) {
        return ats == Ats.GREENHOUSE ? "https://job-boards.greenhouse.io/" + token : "https://jobs.lever.co/" + token;
    }

    private static ApiException bad(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg);
    }
}
