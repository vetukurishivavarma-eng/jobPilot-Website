package com.jobpilot.apply;

import com.jobpilot.auth.User;
import com.jobpilot.auth.UserRepository;
import com.jobpilot.job.Job;
import com.jobpilot.job.JobRepository;
import com.jobpilot.job.UserJobRepository;
import com.jobpilot.profile.ProfileController.ProfileDto;
import com.jobpilot.profile.ProfileRepository;
import com.jobpilot.resume.Resume;
import com.jobpilot.config.AppProperties;
import com.jobpilot.resume.ResumeFiles;
import com.jobpilot.resume.ResumeRepository;
import com.jobpilot.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Applications the agent prepares: read the form, pre-fill answers, let the user review and fix them,
 * then fill the real form in a visible browser for the user to submit.
 */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    public record ApplicationDto(UUID id, UUID jobId, String jobTitle, String company, String jobUrl, Application.Status status,
                                 UUID resumeId, List<FormField> fields, int missing, String error, boolean hasScreenshot,
                                 Instant createdAt, Instant updatedAt, Instant submittedAt) {}
    public record CreateRequest(@NotNull UUID jobId) {}
    public record AnswersRequest(@NotNull Map<String, String> answers) {}

    private static final TypeReference<List<FormField>> FIELDS = new TypeReference<>() {};

    private final ApplicationRepository apps;
    private final AnswerBankRepository bank;
    private final JobRepository jobs;
    private final UserJobRepository links;
    private final UserRepository users;
    private final ProfileRepository profiles;
    private final ResumeRepository resumes;
    private final Ollama ollama;
    private final TransactionTemplate tx;
    private final BrowserFiller filler;
    private final ResumeFiles files;
    private final Path screenshots;

    ApplicationController(ApplicationRepository apps, AnswerBankRepository bank, JobRepository jobs, UserJobRepository links,
                          UserRepository users, ProfileRepository profiles, ResumeRepository resumes, Ollama ollama, TransactionTemplate tx,
                          BrowserFiller filler, ResumeFiles files, AppProperties props) {
        this.filler = filler;
        this.files = files;
        this.screenshots = Path.of(props.storageDir()).toAbsolutePath().getParent().resolve("screenshots");
        this.apps = apps;
        this.bank = bank;
        this.jobs = jobs;
        this.links = links;
        this.users = users;
        this.profiles = profiles;
        this.resumes = resumes;
        this.ollama = ollama;
        this.tx = tx;
    }

    @GetMapping
    public List<ApplicationDto> list(@AuthenticationPrincipal UUID userId) {
        List<Application> list = apps.findByUserIdOrderByUpdatedAtDesc(userId);
        Map<UUID, Job> byId = jobs.findAllById(list.stream().map(a -> a.jobId).toList()).stream().collect(Collectors.toMap(Job::id, j -> j));
        return list.stream().map(a -> dto(a, byId.get(a.jobId))).toList();
    }

    @GetMapping("/{id}")
    public ApplicationDto get(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        Application a = own(userId, id);
        return dto(a, jobs.findById(a.jobId).orElse(null));
    }

    /** Reads the job's application form and pre-fills it. Returns the existing application if there is one. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationDto create(@AuthenticationPrincipal UUID userId, @Valid @RequestBody CreateRequest req) {
        if (links.find(userId, req.jobId()).isEmpty()) throw ApiException.notFound("Job");
        Job job = jobs.findById(req.jobId()).orElseThrow(() -> ApiException.notFound("Job"));
        Optional<Application> existing = apps.findByUserIdAndJobId(userId, job.id());
        if (existing.isPresent()) return dto(existing.get(), job);
        if (job.closedAt() != null) throw new ApiException(HttpStatus.CONFLICT, "JOB_CLOSED", "This job is no longer open");

        String[] board = job.board() == null ? new String[0] : job.board().split(":", 2);
        List<FormField> form;
        if (board.length == 2 && job.source().equals("greenhouse")) form = FormReader.greenhouse(board[1], job.externalId());
        else if (board.length == 2 && job.source().equals("lever")) form = FormReader.lever(board[1], job.externalId());
        else throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_SITE",
                    "The agent can apply on Greenhouse and Lever forms for now. Open the original posting to apply yourself.");

        Answerer.Candidate c = candidate(userId);
        if (c.resumeId() == null)
            throw new ApiException(HttpStatus.CONFLICT, "NO_RESUME", "Upload a resume first. Every application needs one.");
        Map<String, String> known = bank.findByUserId(userId).stream().collect(Collectors.toMap(b -> b.questionKey, b -> b.answer, (x, y) -> x));
        // ponytail: answers are generated inside the request (a few seconds per open question on a 3B model); queue it if forms grow
        List<FormField> filled = Answerer.answer(form, c, new Answerer.JobInfo(job.title(), job.company(), job.description()), known, ollama::generate);

        Application a = new Application(userId, job.id());
        a.resumeId = UUID.fromString(c.resumeId());
        set(a, filled);
        return dto(apps.save(a), job);
    }

    /** The user's answers. Each one is also remembered for the same question on future forms. */
    @PutMapping("/{id}/answers")
    @Transactional
    public ApplicationDto answer(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @Valid @RequestBody AnswersRequest req) {
        Application a = own(userId, id);
        if (a.status == Application.Status.FILLING || a.status == Application.Status.SUBMITTED)
            throw new ApiException(HttpStatus.CONFLICT, "LOCKED", "This application can't be changed any more");
        Job job = jobs.findById(a.jobId).orElseThrow(() -> ApiException.notFound("Job"));
        List<FormField> out = new ArrayList<>();
        for (FormField f : fields(a)) {
            if (!req.answers().containsKey(f.key()) || f.type().equals("FILE")) { out.add(f); continue; }
            String raw = req.answers().get(f.key());
            if (raw == null || raw.isBlank()) { out.add(f.answered(null, null)); continue; }
            if (raw.length() > 10_000) throw bad("Answer to \"" + f.label() + "\" is too long");
            String v = Answerer.valid(f, raw.strip());
            if (v == null) throw bad("\"" + raw + "\" is not one of the choices for \"" + f.label() + "\"");
            out.add(f.answered(v, "USER"));
            remember(userId, f.label(), job.company(), v);
        }
        set(a, out);
        return dto(a, job);
    }

    /**
     * Opens the form in a visible Chrome window and fills it. The user checks it, solves any CAPTCHA and submits;
     * the status follows: FILLING, then SUBMITTED (confirmation page seen) or back to READY (window closed).
     */
    @PostMapping("/{id}/fill")
    public ApplicationDto fill(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) throws IOException {
        Application a = own(userId, id);
        if (a.status == Application.Status.SUBMITTED) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SUBMITTED", "You've already applied to this job");
        if (a.status == Application.Status.FILLING) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_FILLING", "The form is already open in a browser window");
        List<FormField> fields = fields(a);
        if (fields.stream().anyMatch(FormField::missing))
            throw new ApiException(HttpStatus.CONFLICT, "NEEDS_INPUT", "Answer the remaining required questions first");
        if (filler.busy()) throw busy();
        Job job = jobs.findById(a.jobId).orElseThrow(() -> ApiException.notFound("Job"));
        Resume r = a.resumeId == null ? null : resumes.findByIdAndUserId(a.resumeId, userId).orElse(null);
        if (r == null) throw new ApiException(HttpStatus.CONFLICT, "NO_RESUME", "The resume for this application was deleted. Upload one and start again.");

        // the site sees the original file name, so write the bytes to a temp file with that name
        Path dir = Files.createTempDirectory("jobpilot-resume");
        Path resume = dir.resolve(r.originalFilename().replaceAll("[\\\\/:*?\"<>|]", "_"));
        Files.write(resume, files.read(r.storageKey()));
        Files.createDirectories(screenshots);
        UUID appId = a.id;
        update(appId, x -> { x.status = Application.Status.FILLING; x.error = null; x.screenshotKey = appId + ".png"; });

        boolean started = filler.start(new BrowserFiller.Session(a, applyUrl(job), fields, resume, screenshots.resolve(appId + ".png")),
                filled -> update(appId, x -> x.error = filled.notFilled().isEmpty() ? null
                        : "Couldn't fill: " + String.join("; ", filled.notFilled()) + ". Please fill these in the browser window."),
                done -> {
                    update(appId, x -> {
                        switch (done.outcome()) {
                            case "SUBMITTED" -> { x.status = Application.Status.SUBMITTED; x.submittedAt = Instant.now(); x.error = null; }
                            case "CLOSED" -> { x.status = Application.Status.READY; x.error = "The browser window was closed before the application was submitted."; }
                            case "TIMEOUT" -> { x.status = Application.Status.READY; x.error = "No submission seen within 30 minutes, so the window was closed."; }
                            default -> { x.status = Application.Status.FAILED; x.error = done.error(); }
                        }
                    });
                    try { Files.deleteIfExists(resume); Files.deleteIfExists(dir); } catch (IOException ignored) { }
                });
        if (!started) {
            update(appId, x -> x.status = Application.Status.READY);
            throw busy();
        }
        return get(userId, id);
    }

    @GetMapping(value = "/{id}/screenshot", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> screenshot(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) throws IOException {
        Application a = own(userId, id);
        Path p = a.screenshotKey == null ? null : screenshots.resolve(a.screenshotKey);
        if (p == null || !Files.exists(p)) throw ApiException.notFound("Screenshot");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(Files.readAllBytes(p));
    }

    /** The ATS-hosted form, not the company careers page that embeds it. */
    static String applyUrl(Job job) {
        return applyUrl(job.source(), job.board(), job.externalId(), job.url());
    }

    static String applyUrl(String source, String board, String externalId, String url) {
        String token = board.split(":", 2)[1];
        if (source.equals("lever")) return "https://jobs.lever.co/" + token + "/" + externalId + "/apply";
        if (url != null && url.matches("https://([a-z0-9-]+\\.)*greenhouse\\.io/.*")) return url;
        return "https://job-boards.greenhouse.io/" + token + "/jobs/" + externalId;
    }

    private void update(UUID id, Consumer<Application> change) {
        tx.executeWithoutResult(s -> apps.findById(id).ifPresent(x -> { change.accept(x); x.updatedAt = Instant.now(); }));
    }

    private static ApiException busy() {
        return new ApiException(HttpStatus.CONFLICT, "BROWSER_BUSY", "Another application window is open. Finish or close it first.");
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        apps.delete(own(userId, id));
    }

    // ------------------------------------------------------------------ helpers

    private void remember(UUID userId, String question, String company, String answer) {
        String key = AnswerBank.key(question, company);
        if (key.isEmpty()) return;
        AnswerBank b = bank.findByUserIdAndQuestionKey(userId, key).orElseGet(() -> new AnswerBank(userId, key, question.length() > 1000 ? question.substring(0, 1000) : question, answer));
        b.answer = answer;
        b.updatedAt = Instant.now();
        bank.save(b);
    }

    private Answerer.Candidate candidate(UUID userId) {
        return tx.execute(s -> {
            User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
            ProfileDto p = profiles.findById(userId).map(ProfileDto::of).orElseGet(ProfileDto::empty);
            Resume r = resumes.findByUserIdOrderByCreatedAtDesc(userId).stream().filter(Resume::isPrimary).findFirst().orElse(null);
            return new Answerer.Candidate(u.getFullName(), u.getEmail(), p,
                    r == null ? null : r.id().toString(), r == null ? null : r.label(), r == null ? null : r.extractedText());
        });
    }

    static void set(Application a, List<FormField> fields) {
        a.fields = FormReader.JSON.writeValueAsString(fields);
        a.status = fields.stream().anyMatch(FormField::missing) ? Application.Status.NEEDS_INPUT : Application.Status.READY;
        a.updatedAt = Instant.now();
    }

    static List<FormField> fields(Application a) {
        return FormReader.JSON.readValue(a.fields, FIELDS);
    }

    private ApplicationDto dto(Application a, Job j) {
        List<FormField> f = fields(a);
        return new ApplicationDto(a.id, a.jobId, j == null ? null : j.title(), j == null ? null : j.company(), j == null ? null : j.url(),
                a.status, a.resumeId, f, (int) f.stream().filter(FormField::missing).count(), a.error, a.screenshotKey != null,
                a.createdAt, a.updatedAt, a.submittedAt);
    }

    private Application own(UUID userId, UUID id) {
        return apps.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Application"));
    }

    private static ApiException bad(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg);
    }
}
