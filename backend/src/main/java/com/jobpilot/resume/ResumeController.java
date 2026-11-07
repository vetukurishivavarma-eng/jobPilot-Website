package com.jobpilot.resume;

import com.jobpilot.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {
    static final int MAX_PER_USER = 10;

    public record ResumeDto(UUID id, String label, String originalFilename, String contentType, long sizeBytes,
                            boolean primary, boolean textExtracted, Instant createdAt) {
        static ResumeDto of(Resume r) {
            return new ResumeDto(r.id, r.label, r.originalFilename, r.contentType, r.sizeBytes, r.primary,
                    r.extractedText != null && !r.extractedText.isEmpty(), r.createdAt);
        }
    }
    public record ResumeTextDto(UUID id, String extractedText) {}
    public record UpdateRequest(@Size(min = 1, max = 120) String label, Boolean primary) {}

    private final ResumeRepository resumes;
    private final ResumeFiles files;

    public ResumeController(ResumeRepository resumes, ResumeFiles files) {
        this.resumes = resumes;
        this.files = files;
    }

    @GetMapping
    public List<ResumeDto> list(@AuthenticationPrincipal UUID userId) {
        return resumes.findByUserIdOrderByCreatedAtDesc(userId).stream().map(ResumeDto::of).toList();
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ResumeDto upload(@AuthenticationPrincipal UUID userId,
                            @RequestParam("file") MultipartFile file,
                            @RequestParam(value = "label", required = false) String label) throws IOException {
        if (file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "The file is empty");
        if (resumes.countByUserId(userId) >= MAX_PER_USER)
            throw new ApiException(HttpStatus.CONFLICT, "RESUME_LIMIT", "You can keep up to " + MAX_PER_USER + " resumes. Delete one first.");

        byte[] bytes = file.getBytes();
        String type = ResumeFiles.sniff(bytes);
        String name = file.getOriginalFilename() == null ? "resume" : file.getOriginalFilename().replaceAll("[\\\\/\\r\\n\"]", "_");
        if (name.length() > 255) name = name.substring(name.length() - 255);

        Resume r = new Resume(userId);
        r.label = label == null || label.isBlank() ? name.replaceFirst("\\.[^.]+$", "") : label.strip();
        if (r.label.length() > 120) r.label = r.label.substring(0, 120);
        r.originalFilename = name;
        r.contentType = type;
        r.sizeBytes = bytes.length;
        r.sha256 = sha256(bytes);
        r.extractedText = ResumeFiles.extractText(bytes, type);
        r.primary = resumes.countByUserId(userId) == 0; // first resume is the default

        files.write(r.storageKey, bytes);
        afterRollback(() -> files.delete(r.storageKey)); // DB insert failed -> don't leave the file behind
        return ResumeDto.of(resumes.save(r));
    }

    @GetMapping("/{id}")
    public ResumeTextDto text(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        Resume r = own(userId, id);
        return new ResumeTextDto(r.id, r.extractedText);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        Resume r = own(userId, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, r.contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(r.originalFilename, StandardCharsets.UTF_8).build().toString())
                .body(files.read(r.storageKey));
    }

    @PatchMapping("/{id}")
    @Transactional
    public ResumeDto update(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @Valid @RequestBody UpdateRequest req) {
        Resume r = own(userId, id);
        if (req.label() != null) {
            if (req.label().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Label cannot be blank");
            r.label = req.label().strip();
        }
        if (Boolean.TRUE.equals(req.primary())) {
            resumes.findByUserIdOrderByCreatedAtDesc(userId).forEach(x -> x.primary = false);
            r.primary = true;
        }
        return ResumeDto.of(r);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        Resume r = own(userId, id);
        resumes.delete(r);
        if (r.primary) // keep exactly one default while any resume exists: newest remaining takes over
            resumes.findByUserIdOrderByCreatedAtDesc(userId).stream().filter(x -> !x.id.equals(id)).findFirst()
                    .ifPresent(x -> x.primary = true);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { files.delete(r.storageKey); }
        });
    }

    private Resume own(UUID userId, UUID id) {
        // Someone else's resume is reported as missing, not forbidden: don't confirm it exists.
        return resumes.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Resume"));
    }

    private static void afterRollback(Runnable r) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { if (status == STATUS_ROLLED_BACK) r.run(); }
        });
    }

    private static String sha256(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
