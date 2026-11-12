package com.jobpilot.interview;

import com.jobpilot.billing.Billing;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

import com.jobpilot.apply.Ollama;
import com.jobpilot.shared.ApiException;
import com.jobpilot.tailor.Tailor;
import com.jobpilot.tailor.TailorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Mock interview coach: practice sessions per job or role, an answer at a time, scored feedback. Open practice, not a live-interview aid. */
@RestController
@RequestMapping("/api/interview/sessions")
public class InterviewController {

    public record Start(UUID jobId, @Size(max = 120) String role, Boolean live) {}
    /** signals: what the camera saw during this answer (LIVE only, optional). */
    public record Answer(@NotBlank @Size(max = 5000) String answer, Interview.Signals signals) {}
    public record Violation(@NotBlank @Size(max = 20) String kind) {}
    /**
     * violations: phone-on-camera count (1 = warned); ended: POLICY when stopped; done: every planned question answered;
     * recordingExpiresAt: when the uploaded recording is deleted (null = none / already gone).
     */
    public record SessionDto(UUID id, UUID jobId, String title, String source, String mode, List<Interview.Item> items, Interview.Report report,
                             int violations, String ended, boolean done, Instant recordingExpiresAt, Instant createdAt, Instant updatedAt) {
        static SessionDto of(InterviewSession s, Instant recordedAt) {
            Interview.Content c = content(s);
            Instant expires = recordedAt == null ? null : recordedAt.plus(InterviewRecording.TTL);
            return new SessionDto(s.id, s.jobId, s.title, c.source(), c.live() ? "LIVE" : "SET", c.items(), c.report(), c.violationCount(), c.ended(),
                    c.live() && c.planAnswered() >= Interview.LIVE_QUESTIONS, expires != null && expires.isAfter(Instant.now()) ? expires : null,
                    s.createdAt, s.updatedAt);
        }
    }
    /** total = planned questions (LIVE asks them one at a time); readiness = LIVE report score, null until finished. */
    public record SessionSummary(UUID id, UUID jobId, String title, String mode, int answered, int total, Double averageScore, Integer readiness, Instant createdAt) {}

    private static final Logger log = LoggerFactory.getLogger(InterviewController.class);

    private final InterviewSessionRepository sessions;
    private final InterviewRecordingRepository recordings;
    private final TailorService tailor;
    private final Ollama ollama;
    private final SpeechToText stt;
    private final Billing billing;

    InterviewController(InterviewSessionRepository sessions, InterviewRecordingRepository recordings, TailorService tailor, Ollama ollama,
                        SpeechToText stt, Billing billing) {
        this.sessions = sessions;
        this.recordings = recordings;
        this.tailor = tailor;
        this.ollama = ollama;
        this.stt = stt;
        this.billing = billing;
    }

    private SessionDto dto(InterviewSession s) { return SessionDto.of(s, recordings.createdAt(s.id).orElse(null)); }

    @GetMapping
    public List<SessionSummary> list(@AuthenticationPrincipal UUID userId) {
        return sessions.findByUserIdOrderByCreatedAtDesc(userId).stream().map(s -> {
            Interview.Content c = content(s);
            List<Interview.Item> items = c.items();
            List<Integer> scores = items.stream().map(i -> i.feedback() == null ? null : i.feedback().score()).filter(Objects::nonNull).toList();
            int answered = c.live() ? c.planAnswered() : (int) items.stream().filter(i -> i.answer() != null).count();
            Double avg = scores.isEmpty() ? null : Math.round(scores.stream().mapToInt(x -> x).average().orElse(0) * 10) / 10.0;
            return new SessionSummary(s.id, s.jobId, s.title, c.live() ? "LIVE" : "SET", answered, c.live() ? Interview.LIVE_QUESTIONS : items.size(), avg,
                    c.report() == null ? null : c.report().readiness(), s.createdAt);
        }).toList();
    }

    /** One model call writes the whole question set; standard questions when the model is unavailable. */
    @PostMapping
    public SessionDto start(@AuthenticationPrincipal UUID userId, @Valid @RequestBody Start body) {
        Tailor.JobInfo job;
        if (body.jobId() != null) job = tailor.jobInfo(userId, body.jobId()); // 404 unless it's in the user's job list
        else if (body.role() != null && !body.role().isBlank()) job = new Tailor.JobInfo(body.role().strip(), null, null, List.of());
        else throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Pick a job or type a role to practise for");
        if (Boolean.TRUE.equals(body.live())) billing.consume(userId); // 402 without a paid interview (when billing is on)
        InterviewSession s = new InterviewSession(userId, body.jobId(), Interview.cut(Interview.roleLine(job), 300));
        s.content = Interview.JSON.writeValueAsString(Boolean.TRUE.equals(body.live()) ? Interview.liveStart()
                : Interview.questions(tailor.facts(userId), job, ollama::generateJson));
        return dto(sessions.save(s));
    }

    @GetMapping("/{id}")
    public SessionDto get(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return dto(own(userId, id));
    }

    /** Scores one answer (one model call) and saves answer + feedback. Answering again replaces both. */
    @PostMapping("/{id}/answers/{index}")
    public SessionDto answer(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @PathVariable int index, @Valid @RequestBody Answer body) {
        InterviewSession s = own(userId, id);
        Interview.Content c = content(s);
        if (index < 0 || index >= c.items().size()) throw ApiException.notFound("Question");
        Interview.Item item = c.items().get(index);
        String answer = body.answer().strip();
        if (c.live()) return liveAnswer(userId, s, c, index, item, answer, body.signals());
        Interview.Feedback fb = Interview.feedback(tailor.facts(userId), s.title, item, answer, ollama::generateJson);
        if (fb == null)
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                    "The AI couldn't score this answer (is Ollama running?). Your answer is still in the box: try again.");
        List<Interview.Item> items = new ArrayList<>(c.items());
        items.set(index, new Interview.Item(item.type(), item.question(), answer, fb));
        s.content = Interview.JSON.writeValueAsString(new Interview.Content(c.source(), items));
        s.updatedAt = Instant.now();
        return dto(sessions.save(s));
    }

    /**
     * LIVE: only the current question can be answered, once. The answer is saved with what the camera saw; then either the
     * next question (a short model call, or a template) or, after the last one, one call that scores everything + the report.
     * If that final call fails the answers are kept and POST /{id}/report retries it.
     */
    private SessionDto liveAnswer(UUID userId, InterviewSession s, Interview.Content c, int index, Interview.Item item, String answer, Interview.Signals signals) {
        if (c.ended() != null) throw new ApiException(HttpStatus.CONFLICT, "INTERVIEW_ENDED", "This interview has ended.");
        if (index != c.items().size() - 1 || item.answer() != null)
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_ANSWERED", "In a live interview you answer each question once, in order.");
        Tailor.JobInfo job = job(userId, s);
        Tailor.Facts facts = tailor.facts(userId);
        List<Interview.Item> items = new ArrayList<>(c.items());
        items.set(index, new Interview.Item(item.type(), item.question(), answer, null, Interview.Signals.clean(signals)));
        Interview.Content answered = new Interview.Content(c.source(), items, "LIVE", null, c.violationCount(), null);
        Interview.Item next = Interview.next(facts, job, answered, ollama::generateShort);
        Interview.Content saved = answered;
        if (next != null) {
            items.add(next);
            saved = new Interview.Content(c.source(), items, "LIVE", null, c.violationCount(), null);
        } else {
            Interview.Evaluation ev = Interview.evaluate(facts, job, items, ollama::generateReport);
            if (ev != null) saved = new Interview.Content(c.source(), ev.items(), "LIVE", ev.report(), c.violationCount(), null);
        }
        return save(s, saved);
    }

    /** A spoken answer: the audio clip is transcribed (Whisper) and then handled like a typed one. The audio is not kept. */
    @PostMapping(value = "/{id}/answers/{index}/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SessionDto audioAnswer(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @PathVariable int index,
                                  @RequestPart("audio") MultipartFile audio, @RequestPart(value = "signals", required = false) String signals) throws IOException {
        InterviewSession s = own(userId, id);
        Interview.Content c = content(s);
        if (!c.live()) throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_LIVE", "Spoken answers are for live interviews.");
        if (index < 0 || index >= c.items().size()) throw ApiException.notFound("Question");
        if (audio.getSize() > 8 * 1024 * 1024) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LONG", "That answer is too long. Keep answers under about 4 minutes.");
        String text = stt.transcribe(audio.getBytes(), audio.getContentType());
        if (text == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STT_UNAVAILABLE", "Couldn't turn your answer into text right now. Answer again, or type it.");
        if (text.isBlank()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_SPEECH", "We couldn't hear an answer. Check your microphone and answer again.");
        Interview.Signals sig = null;
        if (signals != null && !signals.isBlank()) {
            try { sig = Interview.JSON.readValue(signals, Interview.Signals.class); } catch (RuntimeException e) { sig = null; }
        }
        return liveAnswer(userId, s, c, index, c.items().get(index), Interview.cut(text, 5000), sig);
    }

    /** The candidate's recording of the whole interview (uploaded once at the end). Downloadable for an hour, then deleted. */
    @PutMapping("/{id}/recording")
    public SessionDto uploadRecording(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, HttpServletRequest req) throws IOException {
        InterviewSession s = own(userId, id);
        if (!content(s).live()) throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_LIVE", "Only live interviews are recorded.");
        String type = req.getContentType() == null ? "video/webm" : req.getContentType().split(";")[0].strip();
        if (!type.matches("(video|audio)/(webm|mp4|ogg)")) throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "BAD_TYPE", "Recording must be webm, mp4 or ogg.");
        byte[] bytes = req.getInputStream().readNBytes(InterviewRecording.MAX_BYTES + 1);
        if (bytes.length > InterviewRecording.MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "The recording is too large to keep.");
        if (bytes.length == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY", "The recording is empty.");
        recordings.save(new InterviewRecording(s.id, userId, type, bytes));
        return dto(s);
    }

    @GetMapping("/{id}/recording")
    public ResponseEntity<byte[]> downloadRecording(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        InterviewSession s = own(userId, id);
        InterviewRecording r = recordings.findById(s.id).filter(x -> x.createdAt.plus(InterviewRecording.TTL).isAfter(Instant.now()))
                .orElseThrow(() -> new ApiException(HttpStatus.GONE, "RECORDING_GONE", "Recordings are kept for one hour after the interview. This one has been deleted."));
        String ext = r.contentType.endsWith("mp4") ? "mp4" : r.contentType.endsWith("ogg") ? "ogg" : "webm";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(r.contentType))
                .header("Content-Disposition", "attachment; filename=\"jobpilot-interview." + ext + "\"").body(r.content);
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
    public void purgeRecordings() {
        int n = recordings.deleteOlderThan(Instant.now().minus(InterviewRecording.TTL));
        if (n > 0) log.info("Deleted {} interview recordings older than an hour", n);
    }

    /** Retries the end-of-interview scoring + report (when the AI failed at the last answer). */
    @PostMapping("/{id}/report")
    public SessionDto report(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        InterviewSession s = own(userId, id);
        Interview.Content c = content(s);
        if (!c.live() || c.ended() != null || c.planAnswered() < Interview.LIVE_QUESTIONS)
            throw new ApiException(HttpStatus.CONFLICT, "NOT_FINISHED", "Finish the interview first.");
        if (c.report() != null) return dto(s);
        Interview.Evaluation ev = Interview.evaluate(tailor.facts(userId), job(userId, s), c.items(), ollama::generateReport);
        if (ev == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "The AI couldn't write the report right now. Try again in a minute.");
        return save(s, new Interview.Content(c.source(), ev.items(), "LIVE", ev.report(), c.violationCount(), null));
    }

    /** A phone seen on camera: the 1st time is a warning, the 2nd ends the interview (JobPilot Policy). Enforced here, not just in the app. */
    @PostMapping("/{id}/violations")
    public SessionDto violation(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @Valid @RequestBody Violation body) {
        InterviewSession s = own(userId, id);
        Interview.Content c = content(s);
        if (!c.live() || c.ended() != null || c.report() != null) return dto(s);
        int v = c.violationCount() + 1;
        boolean end = v >= 2;
        return save(s, new Interview.Content(c.source(), c.items(), "LIVE", end ? Interview.policyReport() : null, v, end ? "POLICY" : null));
    }

    private Tailor.JobInfo job(UUID userId, InterviewSession s) {
        return s.jobId != null ? tailor.jobInfo(userId, s.jobId) : new Tailor.JobInfo(s.title, null, null, List.of());
    }

    private SessionDto save(InterviewSession s, Interview.Content c) {
        s.content = Interview.JSON.writeValueAsString(c);
        s.updatedAt = Instant.now();
        return dto(sessions.save(s));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        InterviewSession s = own(userId, id);
        recordings.deleteById(s.id);
        sessions.delete(s);
        return ResponseEntity.noContent().build();
    }

    private InterviewSession own(UUID userId, UUID id) {
        return sessions.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Session"));
    }

    static Interview.Content content(InterviewSession s) { return Interview.JSON.readValue(s.content, Interview.Content.class); }
}
