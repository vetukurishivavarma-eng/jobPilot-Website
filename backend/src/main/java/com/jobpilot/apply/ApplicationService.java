package com.jobpilot.apply;

import com.jobpilot.auth.User;
import com.jobpilot.auth.UserRepository;
import com.jobpilot.config.AppProperties;
import com.jobpilot.job.Job;
import com.jobpilot.job.JobRepository;
import com.jobpilot.job.UserJobRepository;
import com.jobpilot.profile.ProfileController.ProfileDto;
import com.jobpilot.profile.ProfileRepository;
import com.jobpilot.resume.Resume;
import com.jobpilot.resume.ResumeFiles;
import com.jobpilot.resume.ResumeRepository;
import com.jobpilot.shared.ApiException;
import com.jobpilot.tailor.TailorService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Prepares applications (read form, pre-fill answers) and runs the browser fill. Shared by the API and auto mode. */
@Service
public class ApplicationService {
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
    private final TailorService tailor;
    final Path screenshots;

    ApplicationService(ApplicationRepository apps, AnswerBankRepository bank, JobRepository jobs, UserJobRepository links,
                       UserRepository users, ProfileRepository profiles, ResumeRepository resumes, Ollama ollama, TransactionTemplate tx,
                       BrowserFiller filler, ResumeFiles files, AppProperties props, TailorService tailor) {
        this.tailor = tailor;
        this.apps = apps;
        this.bank = bank;
        this.jobs = jobs;
        this.links = links;
        this.users = users;
        this.profiles = profiles;
        this.resumes = resumes;
        this.ollama = ollama;
        this.tx = tx;
        this.filler = filler;
        this.files = files;
        this.screenshots = Path.of(props.storageDir()).toAbsolutePath().getParent().resolve("screenshots");
    }

    /** Reads the job's application form and pre-fills it. Returns the existing application if there is one. */
    public Application prepare(UUID userId, UUID jobId, boolean auto) {
        if (links.find(userId, jobId).isEmpty()) throw ApiException.notFound("Job");
        Job job = jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Job"));
        Optional<Application> existing = apps.findByUserIdAndJobId(userId, job.id());
        if (existing.isPresent()) return existing.get();
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
        if (tailor.resumePdf(userId, jobId).isPresent()) // the job's tailored resume is attached instead of the default one
            c = new Answerer.Candidate(c.fullName(), c.email(), c.profile(), c.resumeId(), "Tailored resume for " + job.company(), c.resumeText());
        Map<String, String> known = bank.findByUserId(userId).stream().collect(Collectors.toMap(b -> b.questionKey, b -> b.answer, (x, y) -> x));
        // ponytail: answers are generated inside the call (a few seconds per open question on a 3B model); queue it if forms grow
        List<FormField> filled = Answerer.answer(form, c, new Answerer.JobInfo(job.title(), job.company(), job.description(),
                tailor.coverLetter(userId, jobId).orElse(null)), known, ollama::generate);

        Application a = new Application(userId, job.id());
        a.resumeId = UUID.fromString(c.resumeId());
        a.auto = auto;
        set(a, filled);
        return apps.save(a);
    }

    /**
     * Opens the form in a visible Chrome window and fills it. With {@code autoSubmit} the agent also presses Submit,
     * and hands over to the user if the site asks for a human check. Otherwise the user checks the form and submits.
     * The returned future completes when the session ends.
     */
    public CompletableFuture<BrowserFiller.Report> fill(UUID userId, UUID appId, boolean autoSubmit) {
        Application a = own(userId, appId);
        if (a.status == Application.Status.SUBMITTED) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SUBMITTED", "You've already applied to this job");
        if (a.status == Application.Status.FILLING) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_FILLING", "The form is already open in a browser window");
        List<FormField> fields = fields(a);
        if (fields.stream().anyMatch(FormField::missing))
            throw new ApiException(HttpStatus.CONFLICT, "NEEDS_INPUT", "Answer the remaining required questions first");
        if (filler.busy()) throw busy();
        Job job = jobs.findById(a.jobId).orElseThrow(() -> ApiException.notFound("Job"));
        Resume r = a.resumeId == null ? null : resumes.findByIdAndUserId(a.resumeId, userId).orElse(null);
        if (r == null) throw new ApiException(HttpStatus.CONFLICT, "NO_RESUME", "The resume for this application was deleted. Upload one and start again.");

        Path dir, resume;
        try {
            // the site sees the original file name, so write the bytes to a temp file with that name
            dir = Files.createTempDirectory("jobpilot-resume");
            Optional<byte[]> tailored = tailor.resumePdf(userId, job.id());
            if (tailored.isPresent()) { // the job's tailored resume goes instead of the default one
                String name = users.findById(userId).map(User::getFullName).orElse("Resume");
                resume = dir.resolve((name + " - Resume.pdf").replaceAll("[\\\\/:*?\"<>|]", "_"));
                Files.write(resume, tailored.get());
            } else {
                resume = dir.resolve(r.originalFilename().replaceAll("[\\\\/:*?\"<>|]", "_"));
                Files.write(resume, files.read(r.storageKey()));
            }
            Files.createDirectories(screenshots);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        update(appId, x -> { x.status = Application.Status.FILLING; x.error = null; x.screenshotKey = appId + ".png"; });

        CompletableFuture<BrowserFiller.Report> done = filler.start(
                new BrowserFiller.Session(applyUrl(job), fields, resume, screenshots.resolve(appId + ".png"), autoSubmit),
                note -> update(appId, x -> x.error = note),
                end -> {
                    update(appId, x -> {
                        switch (end.outcome()) {
                            case "SUBMITTED" -> { x.status = Application.Status.SUBMITTED; x.submittedAt = Instant.now(); x.error = null; }
                            case "CLOSED" -> { x.status = Application.Status.READY; x.error = "The browser window was closed before the application was submitted."; }
                            case "TIMEOUT" -> { x.status = Application.Status.READY; x.error = "No submission seen within 30 minutes, so the window was closed."; }
                            default -> { x.status = Application.Status.FAILED; x.error = end.error(); }
                        }
                    });
                    try { Files.deleteIfExists(resume); Files.deleteIfExists(dir); } catch (IOException ignored) { }
                });
        if (done == null) {
            update(appId, x -> x.status = Application.Status.READY);
            throw busy();
        }
        return done;
    }

    void remember(UUID userId, String question, String company, String answer) {
        String key = AnswerBank.key(question, company);
        if (key.isEmpty()) return;
        AnswerBank b = bank.findByUserIdAndQuestionKey(userId, key)
                .orElseGet(() -> new AnswerBank(userId, key, question.length() > 1000 ? question.substring(0, 1000) : question, answer));
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

    Application own(UUID userId, UUID id) {
        return apps.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Application"));
    }

    void update(UUID id, Consumer<Application> change) {
        tx.executeWithoutResult(s -> apps.findById(id).ifPresent(x -> { change.accept(x); x.updatedAt = Instant.now(); }));
    }

    static void set(Application a, List<FormField> fields) {
        a.fields = FormReader.JSON.writeValueAsString(fields);
        a.status = fields.stream().anyMatch(FormField::missing) ? Application.Status.NEEDS_INPUT : Application.Status.READY;
        a.updatedAt = Instant.now();
    }

    static List<FormField> fields(Application a) {
        return FormReader.JSON.readValue(a.fields, FIELDS);
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

    private static ApiException busy() {
        return new ApiException(HttpStatus.CONFLICT, "BROWSER_BUSY", "Another application window is open. Finish or close it first.");
    }
}
