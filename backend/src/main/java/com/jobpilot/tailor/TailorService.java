package com.jobpilot.tailor;

import com.jobpilot.apply.Ollama;
import com.jobpilot.auth.User;
import com.jobpilot.auth.UserRepository;
import com.jobpilot.job.JdParser;
import com.jobpilot.job.Job;
import com.jobpilot.job.JobRepository;
import com.jobpilot.job.UserJobRepository;
import com.jobpilot.profile.ProfileController.ProfileDto;
import com.jobpilot.profile.ProfileRepository;
import com.jobpilot.resume.Resume;
import com.jobpilot.resume.ResumeRepository;
import com.jobpilot.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Generates, stores and renders per-job tailored resumes and cover letters. Also used by the apply agent. */
@Service
public class TailorService {
    static final JsonMapper JSON = JsonMapper.builder().build();

    private final TailoredDocumentRepository docs;
    private final UserRepository users;
    private final ProfileRepository profiles;
    private final ResumeRepository resumes;
    private final JobRepository jobs;
    private final UserJobRepository links;
    private final Ollama ollama;
    private final TransactionTemplate tx;

    TailorService(TailoredDocumentRepository docs, UserRepository users, ProfileRepository profiles, ResumeRepository resumes,
                  JobRepository jobs, UserJobRepository links, Ollama ollama, TransactionTemplate tx) {
        this.docs = docs;
        this.users = users;
        this.profiles = profiles;
        this.resumes = resumes;
        this.jobs = jobs;
        this.links = links;
        this.ollama = ollama;
        this.tx = tx;
    }

    /** AI-tailored resumes and letters the user (re)generated today (India time): the free daily allowance. */
    long tailoredToday(UUID userId) {
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        return docs.countByUserIdAndUpdatedAtAfter(userId, java.time.LocalDate.now(ist).atStartOfDay(ist).toInstant());
    }

    TailoredDocument generateResume(UUID userId, UUID jobId) {
        Tailor.Facts f = facts(userId);
        if (f.experiences().isEmpty())
            throw new ApiException(HttpStatus.CONFLICT, "NO_EXPERIENCE",
                    "Add your work experience on the Profile page first. Tailoring only uses facts you've given.");
        Tailor.TailoredResume r = Tailor.resume(f, jobInfo(userId, jobId), ollama::generate);
        return save(userId, jobId, TailoredDocument.Kind.RESUME, JSON.writeValueAsString(r));
    }

    TailoredDocument generateLetter(UUID userId, UUID jobId) {
        Tailor.CoverLetter l = Tailor.coverLetter(facts(userId), jobInfo(userId, jobId), ollama::generate);
        return save(userId, jobId, TailoredDocument.Kind.LETTER, JSON.writeValueAsString(l));
    }

    List<TailoredDocument> forJob(UUID userId, UUID jobId) {
        own(userId, jobId);
        return docs.findByUserIdAndJobId(userId, jobId);
    }

    TailoredDocument doc(UUID userId, UUID docId) {
        return docs.findByIdAndUserId(docId, userId).orElseThrow(() -> ApiException.notFound("Document"));
    }

    void saveContent(TailoredDocument d, Object content) {
        d.content = JSON.writeValueAsString(content);
        d.updatedAt = Instant.now();
        docs.save(d);
    }

    static Tailor.TailoredResume resumeOf(TailoredDocument d) { return JSON.readValue(d.content, Tailor.TailoredResume.class); }
    static Tailor.CoverLetter letterOf(TailoredDocument d) { return JSON.readValue(d.content, Tailor.CoverLetter.class); }

    static List<Render.Line> lines(TailoredDocument d) {
        return d.kind == TailoredDocument.Kind.RESUME ? Render.resume(resumeOf(d)) : Render.letter(letterOf(d).text());
    }

    // ------------------------------------------------------------------ for the apply agent

    /** The tailored resume as a PDF, if the user made one for this job. */
    public Optional<byte[]> resumePdf(UUID userId, UUID jobId) {
        return docs.findByUserIdAndJobIdAndKind(userId, jobId, TailoredDocument.Kind.RESUME).map(d -> Render.pdf(lines(d)));
    }

    public Optional<String> coverLetter(UUID userId, UUID jobId) {
        return docs.findByUserIdAndJobIdAndKind(userId, jobId, TailoredDocument.Kind.LETTER).map(d -> letterOf(d).text());
    }

    // ------------------------------------------------------------------ helpers

    private TailoredDocument save(UUID userId, UUID jobId, TailoredDocument.Kind kind, String content) {
        TailoredDocument d = docs.findByUserIdAndJobIdAndKind(userId, jobId, kind).orElseGet(() -> new TailoredDocument(userId, jobId, kind));
        d.content = content;
        d.updatedAt = Instant.now();
        return docs.save(d);
    }

    private Job own(UUID userId, UUID jobId) {
        if (links.find(userId, jobId).isEmpty()) throw ApiException.notFound("Job");
        return jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Job"));
    }

    public Tailor.JobInfo jobInfo(UUID userId, UUID jobId) {
        return tx.execute(s -> {
            Job j = own(userId, jobId);
            return new Tailor.JobInfo(j.title(), j.company(), j.description(),
                    new ArrayList<>(JdParser.parse(j.title(), j.description()).skills()));
        });
    }

    public Tailor.Facts facts(UUID userId) {
        return tx.execute(s -> {
            User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
            ProfileDto p = profiles.findById(userId).map(ProfileDto::of).orElseGet(ProfileDto::empty);
            String resumeText = resumes.findByUserIdOrderByCreatedAtDesc(userId).stream().filter(Resume::isPrimary).findFirst()
                    .map(Resume::extractedText).orElse(null);
            Set<String> resumeSkills = resumeText == null ? Set.of() : new LinkedHashSet<>(JdParser.parse(null, resumeText).skills());
            BigDecimal y = p.yearsExperience();
            return new Tailor.Facts(u.getFullName(), u.getEmail(), p.phone(), p.location(), p.linkedinUrl(), p.headline(), p.summary(),
                    y == null ? null : y.stripTrailingZeros().toPlainString(), p.currentTitle(), p.skills(), resumeSkills,
                    p.experiences(), p.educations(), resumeText);
        });
    }
}
