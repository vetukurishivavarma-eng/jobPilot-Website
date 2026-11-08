package com.jobpilot.apply;

import com.jobpilot.job.Job;
import com.jobpilot.job.JobRepository;
import com.jobpilot.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Applications the agent prepares, the user reviews, and the agent fills in a visible browser. */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    public record ApplicationDto(UUID id, UUID jobId, String jobTitle, String company, String jobUrl, Application.Status status,
                                 UUID resumeId, List<FormField> fields, int missing, String error, boolean hasScreenshot, boolean auto,
                                 Instant createdAt, Instant updatedAt, Instant submittedAt) {}
    public record CreateRequest(@NotNull UUID jobId) {}
    public record AnswersRequest(@NotNull Map<String, String> answers) {}

    private final ApplicationRepository apps;
    private final JobRepository jobs;
    private final ApplicationService service;

    ApplicationController(ApplicationRepository apps, JobRepository jobs, ApplicationService service) {
        this.apps = apps;
        this.jobs = jobs;
        this.service = service;
    }

    @GetMapping
    public List<ApplicationDto> list(@AuthenticationPrincipal UUID userId) {
        List<Application> list = apps.findByUserIdOrderByUpdatedAtDesc(userId);
        Map<UUID, Job> byId = jobs.findAllById(list.stream().map(a -> a.jobId).toList()).stream().collect(Collectors.toMap(Job::id, j -> j));
        return list.stream().map(a -> dto(a, byId.get(a.jobId))).toList();
    }

    @GetMapping("/{id}")
    public ApplicationDto get(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        Application a = service.own(userId, id);
        return dto(a, jobs.findById(a.jobId).orElse(null));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationDto create(@AuthenticationPrincipal UUID userId, @Valid @RequestBody CreateRequest req) {
        Application a = service.prepare(userId, req.jobId(), false);
        return dto(a, jobs.findById(a.jobId).orElse(null));
    }

    /** The user's answers. Each one is also remembered for the same question on future forms. */
    @PutMapping("/{id}/answers")
    @Transactional
    public ApplicationDto answer(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @Valid @RequestBody AnswersRequest req) {
        Application a = service.own(userId, id);
        if (a.status == Application.Status.FILLING || a.status == Application.Status.SUBMITTED)
            throw new ApiException(HttpStatus.CONFLICT, "LOCKED", "This application can't be changed any more");
        Job job = jobs.findById(a.jobId).orElseThrow(() -> ApiException.notFound("Job"));
        List<FormField> out = new ArrayList<>();
        for (FormField f : ApplicationService.fields(a)) {
            if (!req.answers().containsKey(f.key()) || f.type().equals("FILE")) { out.add(f); continue; }
            String raw = req.answers().get(f.key());
            if (raw == null || raw.isBlank()) { out.add(f.answered(null, null)); continue; }
            if (raw.length() > 10_000) throw bad("Answer to \"" + f.label() + "\" is too long");
            String v = Answerer.valid(f, raw.strip());
            if (v == null) throw bad("\"" + raw + "\" is not one of the choices for \"" + f.label() + "\"");
            out.add(f.answered(v, "USER"));
            service.remember(userId, f.label(), job.company(), v);
        }
        ApplicationService.set(a, out);
        return dto(a, job);
    }

    /** Opens the form in a visible Chrome window and fills it; the user checks it and submits. */
    @PostMapping("/{id}/fill")
    public ApplicationDto fill(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        service.fill(userId, id, false);
        return get(userId, id);
    }

    @GetMapping(value = "/{id}/screenshot", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> screenshot(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) throws IOException {
        Application a = service.own(userId, id);
        Path p = a.screenshotKey == null ? null : service.screenshots.resolve(a.screenshotKey);
        if (p == null || !Files.exists(p)) throw ApiException.notFound("Screenshot");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(Files.readAllBytes(p));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        Application a = service.own(userId, id);
        if (a.status == Application.Status.FILLING) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_FILLING", "Close the browser window first");
        apps.delete(a);
    }

    private ApplicationDto dto(Application a, Job j) {
        List<FormField> f = ApplicationService.fields(a);
        return new ApplicationDto(a.id, a.jobId, j == null ? null : j.title(), j == null ? null : j.company(), j == null ? null : j.url(),
                a.status, a.resumeId, f, (int) f.stream().filter(FormField::missing).count(), a.error, a.screenshotKey != null, a.auto,
                a.createdAt, a.updatedAt, a.submittedAt);
    }

    private static ApiException bad(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg);
    }
}
