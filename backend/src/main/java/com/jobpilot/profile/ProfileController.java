package com.jobpilot.profile;

import com.jobpilot.profile.Profile.Education;
import com.jobpilot.profile.Profile.Experience;
import com.jobpilot.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One profile per user. GET returns an empty profile until the first PUT.
 * PUT replaces the whole profile (lists included) - the client always sends the full form.
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private static final String URL = "^$|^https?://\\S+$";

    public record ProfileDto(
            @Size(max = 200) String headline,
            @Size(max = 30) @Pattern(regexp = "^$|^[+0-9 ()-]{6,30}$", message = "invalid phone number") String phone,
            @Size(max = 120) String location,
            @Size(max = 120) String currentTitle,
            @DecimalMin("0") @DecimalMax("60") BigDecimal yearsExperience,
            @PositiveOrZero Long expectedSalary,
            @Pattern(regexp = "^$|^[A-Z]{3}$", message = "3-letter currency code, e.g. INR") String salaryCurrency,
            @Min(0) @Max(365) Integer noticePeriodDays,
            @Size(max = 200) String workAuthorization,
            boolean willingToRelocate,
            @Size(max = 300) @Pattern(regexp = URL, message = "must start with http:// or https://") String linkedinUrl,
            @Size(max = 300) @Pattern(regexp = URL, message = "must start with http:// or https://") String githubUrl,
            @Size(max = 300) @Pattern(regexp = URL, message = "must start with http:// or https://") String portfolioUrl,
            @Size(max = 5000) String summary,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 80) String> skills,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 120) String> desiredRoles,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 120) String> desiredLocations,
            @NotNull @Size(max = 30) List<@Valid @NotNull Experience> experiences,
            @NotNull @Size(max = 15) List<@Valid @NotNull Education> educations,
            Instant updatedAt) {

        public static ProfileDto of(Profile p) {
            return new ProfileDto(p.headline, p.phone, p.location, p.currentTitle, p.yearsExperience,
                    p.expectedSalary, p.salaryCurrency, p.noticePeriodDays, p.workAuthorization, p.willingToRelocate,
                    p.linkedinUrl, p.githubUrl, p.portfolioUrl, p.summary,
                    List.copyOf(p.skills), List.copyOf(p.desiredRoles), List.copyOf(p.desiredLocations),
                    List.copyOf(p.experiences), List.copyOf(p.educations), p.updatedAt);
        }

        public static ProfileDto empty() {
            return new ProfileDto(null, null, null, null, null, null, null, null, null, false,
                    null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null);
        }
    }

    public record ImportResult(ProfileDto profile, List<String> notes) {}

    private final ProfileRepository profiles;
    private final com.jobpilot.resume.ResumeRepository resumes;
    private final com.jobpilot.apply.Ollama ollama;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public ProfileController(ProfileRepository profiles, com.jobpilot.resume.ResumeRepository resumes, com.jobpilot.apply.Ollama ollama,
                             org.springframework.transaction.support.TransactionTemplate tx) {
        this.tx = tx;
        this.profiles = profiles;
        this.resumes = resumes;
        this.ollama = ollama;
    }

    /**
     * A profile draft filled from the default resume (not saved: the user reviews it and PUTs it).
     * Fields already filled are kept. Takes up to a minute with a local model.
     */
    @PostMapping("/import")
    public ImportResult importFromResume(@AuthenticationPrincipal UUID userId) {
        String text = resumes.findByUserIdOrderByCreatedAtDesc(userId).stream().filter(com.jobpilot.resume.Resume::isPrimary).findFirst()
                .map(com.jobpilot.resume.Resume::extractedText).filter(t -> !t.isBlank())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NO_RESUME_TEXT",
                        "Upload your resume first (a PDF or DOCX with real text, not a scan)."));
        ProfileDto current = tx.execute(s -> profiles.findById(userId).map(ProfileDto::of).orElseGet(ProfileDto::empty));
        ResumeImport.Result r = ResumeImport.run(text, current, ollama::generateJson);
        return new ImportResult(r.profile(), r.notes());
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ProfileDto get(@AuthenticationPrincipal UUID userId) {
        return profiles.findById(userId).map(ProfileDto::of).orElseGet(ProfileDto::empty);
    }

    @PutMapping
    @Transactional
    public ProfileDto put(@AuthenticationPrincipal UUID userId, @Valid @RequestBody ProfileDto d) {
        for (Experience e : d.experiences())
            if (e.endDate() != null && e.endDate().isBefore(e.startDate()))
                throw bad("Experience at " + e.company() + " ends before it starts");
        for (Education e : d.educations())
            if (e.startYear() != null && e.endYear() != null && e.endYear() < e.startYear())
                throw bad("Education at " + e.institution() + " ends before it starts");

        Profile p = profiles.findById(userId).orElseGet(() -> new Profile(userId));
        p.headline = blankToNull(d.headline());
        p.phone = blankToNull(d.phone());
        p.location = blankToNull(d.location());
        p.currentTitle = blankToNull(d.currentTitle());
        p.yearsExperience = d.yearsExperience();
        p.expectedSalary = d.expectedSalary();
        p.salaryCurrency = blankToNull(d.salaryCurrency());
        p.noticePeriodDays = d.noticePeriodDays();
        p.workAuthorization = blankToNull(d.workAuthorization());
        p.willingToRelocate = d.willingToRelocate();
        p.linkedinUrl = blankToNull(d.linkedinUrl());
        p.githubUrl = blankToNull(d.githubUrl());
        p.portfolioUrl = blankToNull(d.portfolioUrl());
        p.summary = blankToNull(d.summary());
        replace(p.skills, d.skills().stream().map(String::trim).distinct().toList());
        replace(p.desiredRoles, d.desiredRoles().stream().map(String::trim).distinct().toList());
        replace(p.desiredLocations, d.desiredLocations().stream().map(String::trim).distinct().toList());
        replace(p.experiences, d.experiences());
        replace(p.educations, d.educations());
        p.updatedAt = Instant.now();
        return ProfileDto.of(profiles.save(p));
    }

    private static <T> void replace(List<T> target, List<T> src) {
        target.clear();
        target.addAll(src);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ApiException bad(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg);
    }
}
