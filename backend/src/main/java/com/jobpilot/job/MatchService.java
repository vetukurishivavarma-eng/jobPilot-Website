package com.jobpilot.job;

import com.jobpilot.profile.ProfileController.ProfileDto;
import com.jobpilot.profile.ProfileRepository;
import com.jobpilot.resume.Resume;
import com.jobpilot.resume.ResumeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Builds the candidate side of a match once, then scores jobs against it. */
@Service
public class MatchService {
    private final ProfileRepository profiles;
    private final ResumeRepository resumes;
    private final UserJobRepository userJobs;
    private final TransactionTemplate tx;

    MatchService(ProfileRepository profiles, ResumeRepository resumes, UserJobRepository userJobs, TransactionTemplate tx) {
        this.profiles = profiles;
        this.resumes = resumes;
        this.userJobs = userJobs;
        this.tx = tx;
    }

    /** A skill the user's jobs ask for that isn't on their profile, and how many of those jobs want it. */
    public record SkillGap(String skill, int jobs) {}
    public record GapReport(int jobsConsidered, List<SkillGap> gaps) {}

    /**
     * The skills most asked for across the user's still-open jobs that they don't list: what to learn next to match
     * the roles they're actually seeing. A skill is counted once per job.
     */
    public GapReport skillGaps(UUID userId, int limit) {
        Set<String> mine = new HashSet<>();
        candidate(userId).skills().forEach(sk -> mine.add(sk.toLowerCase(Locale.ROOT)));
        return tx.execute(s -> {
            List<Job> jobs = userJobs.openJobsOf(userId);
            Map<String, Integer> count = new HashMap<>();
            Map<String, String> display = new HashMap<>();
            for (Job j : jobs) {
                Set<String> seen = new HashSet<>();
                for (String sk : j.skills) {
                    String key = sk.toLowerCase(Locale.ROOT);
                    if (mine.contains(key) || !seen.add(key)) continue;
                    count.merge(key, 1, Integer::sum);
                    display.putIfAbsent(key, sk);
                }
            }
            List<SkillGap> gaps = count.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(limit)
                    .map(e -> new SkillGap(display.get(e.getKey()), e.getValue()))
                    .toList();
            return new GapReport(jobs.size(), gaps);
        });
    }

    public MatchScorer.Candidate candidate(UUID userId) {
        return tx.execute(s -> {
            ProfileDto p = profiles.findById(userId).map(ProfileDto::of).orElseGet(ProfileDto::empty);
            Set<String> skills = new LinkedHashSet<>(p.skills());
            resumes.findByUserIdOrderByCreatedAtDesc(userId).stream().filter(Resume::isPrimary).findFirst()
                    .map(Resume::extractedText).filter(t -> !t.isBlank())
                    .ifPresent(t -> skills.addAll(JdParser.parse(null, t).skills()));
            return new MatchScorer.Candidate(skills, p.yearsExperience(), ExperienceRoles.withExperience(p.desiredRoles(), p.experiences()), p.desiredLocations(),
                    p.willingToRelocate(), p.expectedSalary(), p.salaryCurrency());
        });
    }

    /** In the user's preferred locations and fitting their resume (field, stack, level, requirements): the only jobs the list and auto mode show. Needs skills loaded. */
    public boolean relevant(MatchScorer.Candidate c, Job j) {
        return MatchScorer.inDesiredLocation(c, j.location, j.workplace) && MatchScorer.fitsResume(c, j.title, j.skills, j.minYears, j.seniority);
    }

    public record NewMatch(UUID jobId, String title, String company, String location, Integer score, String url) {}

    /** Relevant jobs found for the user after `since`, best match first (the email digest). */
    public List<NewMatch> newMatches(UUID userId, java.time.Instant since, int limit) {
        MatchScorer.Candidate c = candidate(userId);
        return tx.execute(s -> userJobs.newSince(userId, since, UserJob.Status.NEW).stream()
                .filter(j -> relevant(c, j))
                .map(j -> new NewMatch(j.id(), j.title, j.company, j.location, score(c, j).score(), j.url))
                .sorted(Comparator.comparing((NewMatch m) -> m.score() == null ? -1 : m.score()).reversed())
                .limit(limit).toList());
    }

    /** Needs the job's skills loaded (inside a transaction, or a job fetched with them). */
    public MatchScorer.Match score(MatchScorer.Candidate c, Job j) {
        return MatchScorer.score(c, new MatchScorer.JobFacts(j.title, j.skills, j.minYears, j.seniority, j.location, j.workplace,
                j.salaryMax, j.salaryCurrency));
    }
}
