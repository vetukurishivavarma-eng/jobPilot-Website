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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
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
    private final ResumeFiles files;
    private final TailorService tailor;
    final Path screenshots;

    ApplicationService(ApplicationRepository apps, AnswerBankRepository bank, JobRepository jobs, UserJobRepository links,
                       UserRepository users, ProfileRepository profiles, ResumeRepository resumes, Ollama ollama, TransactionTemplate tx,
                       ResumeFiles files, AppProperties props, TailorService tailor) {
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
     * A draft made before an answer rule existed (or before the profile had the fact): fills the blanks the rules and
     * answer bank can answer now. No AI call. Only drafts still waiting on the user.
     */
    public Application refreshed(UUID userId, Application a) {
        if (a.status != Application.Status.NEEDS_INPUT) return a;
        Job job = jobs.findById(a.jobId).orElse(null);
        if (job == null) return a;
        Answerer.Candidate c = candidate(userId);
        Map<String, String> known = bank.findByUserId(userId).stream().collect(Collectors.toMap(b -> b.questionKey, b -> b.answer, (x, y) -> x));
        Answerer.JobInfo info = new Answerer.JobInfo(job.title(), job.company(), null);
        List<FormField> fs = new ArrayList<>(fields(a));
        boolean changed = false;
        for (int i = 0; i < fs.size(); i++) {
            FormField f = fs.get(i);
            if (f.value() != null && !f.value().isBlank() || f.type().equals("FILE")) continue;
            FormField n = Answerer.answer(List.of(f), c, info, known, null).getFirst();
            if (n.value() != null) { fs.set(i, n); changed = true; }
        }
        if (!changed) return a;
        set(a, fs);
        return apps.save(a);
    }

    public record ResumeFile(String name, String contentType, byte[] bytes) {}

    /** The file to attach: the job's tailored resume (as PDF) if there is one, otherwise the application's resume. */
    public ResumeFile resumeFile(UUID userId, Application a, Job job) throws IOException {
        Optional<byte[]> tailored = tailor.resumePdf(userId, job.id());
        if (tailored.isPresent()) {
            String name = users.findById(userId).map(User::getFullName).orElse("Resume");
            return new ResumeFile(safeName(name + " - Resume.pdf"), "application/pdf", tailored.get());
        }
        Resume r = a.resumeId == null ? null : resumes.findByIdAndUserId(a.resumeId, userId).orElse(null);
        if (r == null) throw new ApiException(HttpStatus.CONFLICT, "NO_RESUME", "The resume for this application was deleted. Upload one and start again.");
        return new ResumeFile(safeName(r.originalFilename()), r.contentType(), files.read(r.storageKey()));
    }

    private static String safeName(String s) { return s.replaceAll("[\\\\/:*?\"<>|]", "_"); }

    /** The application for the job whose apply page is at {@code url} (the browser extension asks this). */
    public Optional<Application> forPage(UUID userId, String url) {
        String[] job = jobOfPage(url);
        if (job == null) return Optional.empty();
        return jobs.findBySourceAndExternalId(job[0], job[1]).flatMap(j -> apps.findByUserIdAndJobId(userId, j.id()));
    }

    private static final java.util.regex.Pattern LEVER_PAGE = java.util.regex.Pattern.compile("^https://jobs\\.(?:eu\\.)?lever\\.co/[^/]+/([0-9a-f-]{36})");
    private static final java.util.regex.Pattern GH_PAGE = java.util.regex.Pattern.compile("^https://(?:job-boards|boards)(?:\\.eu)?\\.greenhouse\\.io/[^/?]+/jobs/(\\d+)");
    private static final java.util.regex.Pattern GH_PARAM = java.util.regex.Pattern.compile("[?&](?:gh_jid|token)=(\\d+)");

    /** {source, externalId} for a Lever or Greenhouse job page (including Greenhouse's embedded form), or null. */
    static String[] jobOfPage(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m;
        if ((m = LEVER_PAGE.matcher(url)).find()) return new String[]{"lever", m.group(1)};
        if ((m = GH_PAGE.matcher(url)).find()) return new String[]{"greenhouse", m.group(1)};
        if (url.contains("greenhouse.io/") && (m = GH_PARAM.matcher(url)).find()) return new String[]{"greenhouse", m.group(1)};
        return null;
    }

    /** The user submitted it themselves (the extension saw the site's thank-you page). */
    public void markSubmitted(UUID userId, UUID appId) {
        own(userId, appId);
        update(appId, x -> { x.status = Application.Status.SUBMITTED; x.submittedAt = Instant.now(); x.error = null; });
    }

    public record TimelineEntry(Application.Stage stage, String note, Instant at) {}
    private static final TypeReference<List<TimelineEntry>> TIMELINE = new TypeReference<>() {};

    static List<TimelineEntry> timeline(Application a) {
        return a.timeline == null || a.timeline.isBlank() ? List.of() : FormReader.JSON.readValue(a.timeline, TIMELINE);
    }

    /**
     * Records an outcome stage for a submitted application, appends it to the timeline, and sets the follow-up reminder:
     * followUpInDays null = leave as is, &lt;= 0 = clear, &gt; 0 = remind that many days from now. A terminal stage
     * (rejected/accepted/withdrawn) always clears any pending reminder.
     */
    public Application setStage(UUID userId, UUID id, Application.Stage stage, String note, Integer followUpInDays) {
        Application a = own(userId, id);
        if (a.status != Application.Status.SUBMITTED)
            throw new ApiException(HttpStatus.CONFLICT, "NOT_SUBMITTED", "Track an outcome only after the application is submitted");
        String trimmed = note == null ? null : (note.length() > 500 ? note.substring(0, 500) : note.strip());
        return tx.execute(s -> {
            Application x = apps.findById(id).orElseThrow();
            List<TimelineEntry> t = new ArrayList<>(timeline(x));
            t.add(new TimelineEntry(stage, trimmed == null || trimmed.isEmpty() ? null : trimmed, Instant.now()));
            x.timeline = FormReader.JSON.writeValueAsString(t);
            x.stage = stage;
            if (stage.terminal()) x.followUpAt = null;
            else if (followUpInDays != null) x.followUpAt = followUpInDays <= 0 ? null : Instant.now().plus(Duration.ofDays(Math.min(followUpInDays, 365)));
            x.updatedAt = Instant.now();
            return x;
        });
    }

    /** A drafted follow-up email for a submitted application (AI when available, template otherwise). */
    public FollowUpEmail.Email followUpEmail(UUID userId, UUID id) {
        Application a = own(userId, id);
        if (a.status != Application.Status.SUBMITTED)
            throw new ApiException(HttpStatus.CONFLICT, "NOT_SUBMITTED", "A follow-up email is for an application you've already submitted");
        long days = a.submittedAt == null ? 0 : Math.max(0, Duration.between(a.submittedAt, Instant.now()).toDays());
        return FollowUpEmail.draft(tailor.facts(userId), tailor.jobInfo(userId, a.jobId), (int) days, ollama::generateJson);
    }

    /** The job-search funnel for the Insights view. */
    public record Funnel(int total, int submitted, int submittedThisWeek,
                         int applied, int screening, int interview, int offer, int rejected, int accepted, int withdrawn,
                         Integer replyRatePct, Integer avgDaysToReply) {}

    private static boolean isResponse(Application.Stage s) {
        return s == Application.Stage.SCREENING || s == Application.Stage.INTERVIEW || s == Application.Stage.OFFER
                || s == Application.Stage.REJECTED || s == Application.Stage.ACCEPTED;
    }

    /** "Title at Company" for each submitted application whose follow-up reminder is due (the email digest). */
    public List<String> followUpsDue(UUID userId) {
        Instant now = Instant.now();
        return apps.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .filter(a -> a.status == Application.Status.SUBMITTED && a.followUpAt != null && !a.followUpAt.isAfter(now)
                        && (a.stage == null || !a.stage.terminal()))
                .map(a -> jobs.findById(a.jobId).map(j -> j.title() + " at " + j.company()).orElse(null))
                .filter(java.util.Objects::nonNull).toList();
    }

    public Funnel funnel(UUID userId) {
        List<Application> list = apps.findByUserIdOrderByUpdatedAtDesc(userId);
        List<Application> submitted = list.stream().filter(a -> a.status == Application.Status.SUBMITTED).toList();
        Instant weekAgo = Instant.now().minus(Duration.ofDays(7));
        int thisWeek = (int) submitted.stream().filter(a -> a.submittedAt != null && a.submittedAt.isAfter(weekAgo)).count();
        int[] c = new int[Application.Stage.values().length];
        int responded = 0, repliedWithDate = 0;
        long totalDays = 0;
        for (Application a : submitted) {
            Application.Stage st = a.stage == null ? Application.Stage.APPLIED : a.stage;
            c[st.ordinal()]++;
            if (isResponse(st)) {
                responded++;
                Long d = daysToFirstResponse(a);
                if (d != null) { totalDays += d; repliedWithDate++; }
            }
        }
        Integer replyRate = submitted.isEmpty() ? null : (int) Math.round(responded * 100.0 / submitted.size());
        Integer avgDays = repliedWithDate == 0 ? null : (int) Math.round((double) totalDays / repliedWithDate);
        return new Funnel(list.size(), submitted.size(), thisWeek,
                c[Application.Stage.APPLIED.ordinal()], c[Application.Stage.SCREENING.ordinal()], c[Application.Stage.INTERVIEW.ordinal()],
                c[Application.Stage.OFFER.ordinal()], c[Application.Stage.REJECTED.ordinal()], c[Application.Stage.ACCEPTED.ordinal()],
                c[Application.Stage.WITHDRAWN.ordinal()], replyRate, avgDays);
    }

    /** Days from submission to the first timeline entry that is a real response, or null if unknown. */
    private static Long daysToFirstResponse(Application a) {
        if (a.submittedAt == null) return null;
        return timeline(a).stream().filter(t -> isResponse(t.stage())).map(TimelineEntry::at).min(Instant::compareTo)
                .map(at -> Math.max(0, Duration.between(a.submittedAt, at).toDays())).orElse(null);
    }

    /** A browser session can't outlive the backend: anything left "filling" by a restart goes back to ready. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void releaseInterruptedSessions() {
        tx.executeWithoutResult(s -> apps.findAll().stream().filter(a -> a.status == Application.Status.FILLING).forEach(a -> {
            a.status = Application.Status.READY;
            a.error = "JobPilot restarted while the form was open. Press Open and fill to open it again.";
            a.updatedAt = Instant.now();
        }));
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
        if (!source.equals("lever") && !source.equals("greenhouse")) return url; // Workday etc.: the posting itself
        String token = board.split(":", 2)[1];
        if (source.equals("lever")) return "https://jobs.lever.co/" + token + "/" + externalId + "/apply";
        // the embed form: a board page can redirect to the company's own careers form (Okta does), the embed never does
        String host = url != null && url.matches("https://([a-z0-9-]+\\.)*eu\\.greenhouse\\.io/.*") ? "job-boards.eu.greenhouse.io" : "job-boards.greenhouse.io";
        return "https://" + host + "/embed/job_app?for=" + token + "&token=" + externalId;
    }

}
