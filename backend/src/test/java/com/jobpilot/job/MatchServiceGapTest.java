package com.jobpilot.job;

import com.jobpilot.profile.ProfileController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class MatchServiceGapTest {
    @Autowired MatchService match;
    @Autowired JobStore store;
    @Autowired JobRepository jobs;
    @Autowired ProfileController profile;
    @Autowired TransactionTemplate tx;
    @Autowired com.jobpilot.auth.UserRepository users;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

    private UUID user() {
        return users.save(new com.jobpilot.auth.User("g" + UUID.randomUUID() + "@e.com", encoder.encode("x".repeat(10)), "G")).getId();
    }

    private void job(UUID uid, List<String> skills) {
        JobDraft d = new JobDraft("web", UUID.randomUUID().toString(), null, "https://x/" + UUID.randomUUID(), null, "Backend", "Acme",
                "Bangalore", null, null, null, null, null, "desc", Instant.now());
        UUID id = store.save(List.of(d), null, uid, UserJob.Origin.SEARCH, x -> true).jobs().get(0).id();
        tx.executeWithoutResult(s -> { Job j = jobs.findById(id).orElseThrow(); j.skills.clear(); j.skills.addAll(skills); });
    }

    @Test
    void ranksMissingSkillsExcludingOnesTheUserHas() {
        UUID uid = user();
        profile.put(uid, new ProfileController.ProfileDto(null, null, null, null, null, null, null, null, null, false, null, true,
                null, null, null, null, List.of("Java"), List.of(), List.of(), List.of(), List.of(), null));
        job(uid, List.of("Java", "Kafka", "Kubernetes"));
        job(uid, List.of("Java", "Kafka"));
        job(uid, List.of("Go"));

        MatchService.GapReport g = match.skillGaps(uid, 8);
        assertEquals(3, g.jobsConsidered());
        List<MatchService.SkillGap> gaps = g.gaps();
        assertEquals("Kafka", gaps.get(0).skill()); // asked by 2 jobs -> top
        assertEquals(2, gaps.get(0).jobs());
        assertTrue(gaps.stream().noneMatch(x -> x.skill().equalsIgnoreCase("Java"))); // the user already lists Java
    }
}
