package com.jobpilot.interview;

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
    public record Answer(@NotBlank @Size(max = 5000) String answer) {}
    public record SessionDto(UUID id, UUID jobId, String title, String source, String mode, List<Interview.Item> items, Interview.Report report, Instant createdAt, Instant updatedAt) {
        static SessionDto of(InterviewSession s) {
            Interview.Content c = content(s);
            return new SessionDto(s.id, s.jobId, s.title, c.source(), c.live() ? "LIVE" : "SET", c.items(), c.report(), s.createdAt, s.updatedAt);
        }
    }
    /** total = planned questions (LIVE asks them one at a time); readiness = LIVE report score, null until finished. */
    public record SessionSummary(UUID id, UUID jobId, String title, String mode, int answered, int total, Double averageScore, Integer readiness, Instant createdAt) {}

    private final InterviewSessionRepository sessions;
    private final TailorService tailor;
    private final Ollama ollama;

    InterviewController(InterviewSessionRepository sessions, TailorService tailor, Ollama ollama) {
        this.sessions = sessions;
        this.tailor = tailor;
        this.ollama = ollama;
    }

    @GetMapping
    public List<SessionSummary> list(@AuthenticationPrincipal UUID userId) {
        return sessions.findByUserIdOrderByCreatedAtDesc(userId).stream().map(s -> {
            Interview.Content c = content(s);
            List<Interview.Item> items = c.items();
            List<Integer> scores = items.stream().map(i -> i.feedback() == null ? null : i.feedback().score()).filter(Objects::nonNull).toList();
            int answered = (int) items.stream().filter(i -> i.answer() != null).count();
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
        InterviewSession s = new InterviewSession(userId, body.jobId(), Interview.cut(Interview.roleLine(job), 300));
        s.content = Interview.JSON.writeValueAsString(Boolean.TRUE.equals(body.live()) ? Interview.liveStart()
                : Interview.questions(tailor.facts(userId), job, ollama::generateJson));
        return SessionDto.of(sessions.save(s));
    }

    @GetMapping("/{id}")
    public SessionDto get(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return SessionDto.of(own(userId, id));
    }

    /** Scores one answer (one model call) and saves answer + feedback. Answering again replaces both. */
    @PostMapping("/{id}/answers/{index}")
    public SessionDto answer(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @PathVariable int index, @Valid @RequestBody Answer body) {
        InterviewSession s = own(userId, id);
        Interview.Content c = content(s);
        if (index < 0 || index >= c.items().size()) throw ApiException.notFound("Question");
        Interview.Item item = c.items().get(index);
        String answer = body.answer().strip();
        if (c.live()) return liveAnswer(userId, s, c, index, item, answer);
        Interview.Feedback fb = Interview.feedback(tailor.facts(userId), s.title, item, answer, ollama::generateJson);
        if (fb == null)
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                    "The AI couldn't score this answer (is Ollama running?). Your answer is still in the box: try again.");
        List<Interview.Item> items = new ArrayList<>(c.items());
        items.set(index, new Interview.Item(item.type(), item.question(), answer, fb));
        s.content = Interview.JSON.writeValueAsString(new Interview.Content(c.source(), items));
        s.updatedAt = Instant.now();
        return SessionDto.of(sessions.save(s));
    }

    /** LIVE: only the current question can be answered; the reply scores it and asks the next one, the last answer writes the report. */
    private SessionDto liveAnswer(UUID userId, InterviewSession s, Interview.Content c, int index, Interview.Item item, String answer) {
        if (index != c.items().size() - 1 || item.answer() != null)
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_ANSWERED", "In a live interview you answer each question once, in order.");
        Tailor.JobInfo job = s.jobId != null ? tailor.jobInfo(userId, s.jobId) : new Tailor.JobInfo(s.title, null, null, List.of());
        Tailor.Facts facts = tailor.facts(userId);
        boolean last = c.items().size() >= Interview.LIVE_QUESTIONS;
        List<Interview.Item> done = c.items().subList(0, index);
        Interview.Turn turn = Interview.liveTurn(facts, job, done, item, answer, last, ollama::generateJson);
        if (turn.feedback() == null)
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                    "The AI couldn't read this answer. Your answer is still in the box: try again.");
        List<Interview.Item> items = new ArrayList<>(c.items());
        items.set(index, new Interview.Item(item.type(), item.question(), answer, turn.feedback()));
        Interview.Report report = null;
        if (last) report = Interview.report(facts, job, items, ollama::generateJson);
        else items.add(new Interview.Item("FOLLOW_UP", turn.next(), null, null));
        s.content = Interview.JSON.writeValueAsString(new Interview.Content(c.source(), items, "LIVE", report));
        s.updatedAt = Instant.now();
        return SessionDto.of(sessions.save(s));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        sessions.delete(own(userId, id));
        return ResponseEntity.noContent().build();
    }

    private InterviewSession own(UUID userId, UUID id) {
        return sessions.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Session"));
    }

    static Interview.Content content(InterviewSession s) { return Interview.JSON.readValue(s.content, Interview.Content.class); }
}
