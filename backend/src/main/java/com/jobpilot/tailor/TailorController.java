package com.jobpilot.tailor;

import com.jobpilot.job.JobRepository;
import com.jobpilot.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Per-job tailored resume and cover letter: generate, review (keep original or tailored per section), edit, download. */
@RestController
@RequestMapping("/api")
public class TailorController {

    public record DocDto(UUID id, TailoredDocument.Kind kind, Tailor.TailoredResume resume, Tailor.CoverLetter letter, Instant updatedAt) {
        static DocDto of(TailoredDocument d) {
            return d.kind == TailoredDocument.Kind.RESUME
                    ? new DocDto(d.id, d.kind, TailorService.resumeOf(d), null, d.updatedAt)
                    : new DocDto(d.id, d.kind, null, TailorService.letterOf(d), d.updatedAt);
        }
    }
    public record JobDocsDto(DocDto resume, DocDto letter) {}
    /** The user's pick for one section; {@code text} replaces the tailored version when given. */
    public record Pick(boolean useTailored, @Size(max = 10000) String text) {}
    public record ResumeChoices(@NotNull @Valid Pick summary, @NotNull @Valid Pick skills, @NotNull List<@Valid @NotNull Pick> experiences) {}
    public record LetterText(@NotNull @Size(min = 1, max = 20000) String text) {}

    private final TailorService service;
    private final JobRepository jobs;

    TailorController(TailorService service, JobRepository jobs) {
        this.service = service;
        this.jobs = jobs;
    }

    @GetMapping("/jobs/{jobId}/documents")
    public JobDocsDto forJob(@AuthenticationPrincipal UUID userId, @PathVariable UUID jobId) {
        DocDto resume = null, letter = null;
        for (TailoredDocument d : service.forJob(userId, jobId)) {
            if (d.kind == TailoredDocument.Kind.RESUME) resume = DocDto.of(d);
            else letter = DocDto.of(d);
        }
        return new JobDocsDto(resume, letter);
    }

    /** (Re)generates the tailored resume. Replaces any earlier version for this job. */
    @PostMapping("/jobs/{jobId}/documents/resume")
    public DocDto tailorResume(@AuthenticationPrincipal UUID userId, @PathVariable UUID jobId) {
        return DocDto.of(service.generateResume(userId, jobId));
    }

    @PostMapping("/jobs/{jobId}/documents/letter")
    public DocDto writeLetter(@AuthenticationPrincipal UUID userId, @PathVariable UUID jobId) {
        return DocDto.of(service.generateLetter(userId, jobId));
    }

    @PutMapping("/documents/{id}/resume")
    public DocDto pickResume(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @Valid @RequestBody ResumeChoices c) {
        TailoredDocument d = service.doc(userId, id);
        if (d.kind != TailoredDocument.Kind.RESUME) throw ApiException.notFound("Resume");
        Tailor.TailoredResume r = TailorService.resumeOf(d);
        if (c.experiences().size() != r.experiences().size()) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One choice per experience is needed");
        List<Tailor.ExpBlock> exps = new ArrayList<>();
        for (int i = 0; i < r.experiences().size(); i++) {
            Tailor.ExpBlock e = r.experiences().get(i);
            exps.add(new Tailor.ExpBlock(e.heading(), e.meta(), apply(e.bullets(), c.experiences().get(i))));
        }
        service.saveContent(d, new Tailor.TailoredResume(r.name(), r.contact(), r.headline(), apply(r.summary(), c.summary()),
                apply(r.skills(), c.skills()), exps, r.education(), r.notes()));
        return DocDto.of(d);
    }

    @PutMapping("/documents/{id}/letter")
    public DocDto editLetter(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @Valid @RequestBody LetterText body) {
        TailoredDocument d = service.doc(userId, id);
        if (d.kind != TailoredDocument.Kind.LETTER) throw ApiException.notFound("Letter");
        Tailor.CoverLetter l = TailorService.letterOf(d);
        // the user's own edit: earlier AI warnings no longer describe this text
        service.saveContent(d, new Tailor.CoverLetter(body.text().strip(), "USER", body.text().strip().equals(l.text()) ? l.warnings() : List.of()));
        return DocDto.of(d);
    }

    /** format: pdf (default), docx or txt. */
    @GetMapping("/documents/{id}/file")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal UUID userId, @PathVariable UUID id,
                                           @RequestParam(defaultValue = "pdf") String format) {
        TailoredDocument d = service.doc(userId, id);
        List<Render.Line> lines = TailorService.lines(d);
        String company = jobs.findById(d.jobId).map(j -> j.company()).orElse("job");
        String name = service.facts(userId).name();
        String base = (name + " - " + (d.kind == TailoredDocument.Kind.RESUME ? "Resume" : "Cover letter") + " - " + company)
                .replaceAll("[\\\\/:*?\"<>|]", "_");
        byte[] body;
        String type;
        switch (format.toLowerCase()) {
            case "docx" -> { body = Render.docx(lines); type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; }
            case "txt" -> { body = Render.txt(lines); type = "text/plain; charset=utf-8"; }
            case "pdf" -> { body = Render.pdf(lines); type = "application/pdf"; }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "format must be pdf, docx or txt");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(base + "." + format.toLowerCase(), StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    private static Tailor.Choice apply(Tailor.Choice c, Pick p) {
        String tailored = p.text() != null && !p.text().isBlank() ? p.text().strip() : c.tailored();
        return new Tailor.Choice(c.original(), tailored, p.useTailored() && tailored != null, c.why());
    }
}
