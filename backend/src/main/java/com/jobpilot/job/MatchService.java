package com.jobpilot.job;

import com.jobpilot.profile.ProfileController.ProfileDto;
import com.jobpilot.profile.ProfileRepository;
import com.jobpilot.resume.Resume;
import com.jobpilot.resume.ResumeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Builds the candidate side of a match once, then scores jobs against it. */
@Service
public class MatchService {
    private final ProfileRepository profiles;
    private final ResumeRepository resumes;
    private final TransactionTemplate tx;

    MatchService(ProfileRepository profiles, ResumeRepository resumes, TransactionTemplate tx) {
        this.profiles = profiles;
        this.resumes = resumes;
        this.tx = tx;
    }

    public MatchScorer.Candidate candidate(UUID userId) {
        return tx.execute(s -> {
            ProfileDto p = profiles.findById(userId).map(ProfileDto::of).orElseGet(ProfileDto::empty);
            Set<String> skills = new LinkedHashSet<>(p.skills());
            resumes.findByUserIdOrderByCreatedAtDesc(userId).stream().filter(Resume::isPrimary).findFirst()
                    .map(Resume::extractedText).filter(t -> !t.isBlank())
                    .ifPresent(t -> skills.addAll(JdParser.parse(null, t).skills()));
            return new MatchScorer.Candidate(skills, p.yearsExperience(), p.desiredRoles(), p.desiredLocations(),
                    p.willingToRelocate(), p.expectedSalary(), p.salaryCurrency());
        });
    }

    /** Needs the job's skills loaded (inside a transaction, or a job fetched with them). */
    public MatchScorer.Match score(MatchScorer.Candidate c, Job j) {
        return MatchScorer.score(c, new MatchScorer.JobFacts(j.title, j.skills, j.minYears, j.seniority, j.location, j.workplace,
                j.salaryMax, j.salaryCurrency));
    }
}
