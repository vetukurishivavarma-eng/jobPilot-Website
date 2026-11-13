package com.jobpilot.apply;

import com.jobpilot.job.Job;
import com.jobpilot.job.JobDraft;
import com.jobpilot.job.JobStore;
import com.jobpilot.job.UserJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationTrackerTest {
    @Autowired ApplicationService service;
    @Autowired ApplicationRepository apps;
    @Autowired JobStore store;
    @Autowired com.jobpilot.auth.UserRepository users;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

    private UUID newUser() {
        return users.save(new com.jobpilot.auth.User("t" + UUID.randomUUID() + "@e.com", encoder.encode("x".repeat(10)), "T")).getId();
    }

    private Job newJob(UUID uid, String title) {
        JobDraft d = new JobDraft("web", UUID.randomUUID().toString(), null, "https://x/j", null, title, "Acme",
                "Bangalore", null, null, null, null, null, "Java backend", Instant.now());
        return store.save(List.of(d), null, uid, UserJob.Origin.SEARCH, x -> true).jobs().get(0);
    }

    private Application app(UUID uid, Job j, Application.Status status) {
        Application a = new Application(uid, j.id());
        a.status = status;
        if (status == Application.Status.SUBMITTED) a.submittedAt = Instant.now();
        a.fields = "[]";
        return apps.save(a);
    }

    @Test
    void stageRecordsTimelineAndFollowUp() {
        UUID uid = newUser();
        Application a = app(uid, newJob(uid, "Backend Dev"), Application.Status.SUBMITTED);

        Application r1 = service.setStage(uid, a.id, Application.Stage.INTERVIEW, "phone screen booked", 5);
        assertEquals(Application.Stage.INTERVIEW, r1.stage);
        assertNotNull(r1.followUpAt);
        assertTrue(r1.followUpAt.isAfter(Instant.now().plusSeconds(4 * 86400)));
        assertEquals(1, ApplicationService.timeline(r1).size());
        assertEquals("phone screen booked", ApplicationService.timeline(r1).get(0).note());

        // a terminal stage clears the reminder and appends a second entry
        Application r2 = service.setStage(uid, a.id, Application.Stage.REJECTED, null, null);
        assertEquals(Application.Stage.REJECTED, r2.stage);
        assertNull(r2.followUpAt);
        assertEquals(2, ApplicationService.timeline(r2).size());
    }

    @Test
    void stageRejectedBeforeSubmit() {
        UUID uid = newUser();
        Application a = app(uid, newJob(uid, "Dev"), Application.Status.READY);
        var ex = assertThrows(com.jobpilot.shared.ApiException.class,
                () -> service.setStage(uid, a.id, Application.Stage.INTERVIEW, null, 3));
        assertEquals("NOT_SUBMITTED", ex.code);
    }

    @Test
    void funnelCountsRepliesStagesAndReplyRate() {
        UUID uid = newUser();
        Application a1 = app(uid, newJob(uid, "Dev"), Application.Status.SUBMITTED);
        Application a2 = app(uid, newJob(uid, "Dev"), Application.Status.SUBMITTED);
        app(uid, newJob(uid, "Dev"), Application.Status.SUBMITTED); // stays APPLIED (no response)

        service.setStage(uid, a1.id, Application.Stage.INTERVIEW, null, null);
        service.setStage(uid, a2.id, Application.Stage.REJECTED, null, null);

        ApplicationService.Funnel f = service.funnel(uid);
        assertEquals(3, f.submitted());
        assertEquals(1, f.interview());
        assertEquals(1, f.rejected());
        assertEquals(1, f.applied());
        assertEquals(67, f.replyRatePct()); // 2 of 3 got a response
        assertNotNull(f.avgDaysToReply());
    }

    @Test
    void followUpEmailFallsBackToTemplate() {
        UUID uid = newUser(); // AI points at a dead port in tests, so this is the template path
        Application a = app(uid, newJob(uid, "Backend Dev"), Application.Status.SUBMITTED);
        FollowUpEmail.Email e = service.followUpEmail(uid, a.id);
        assertEquals("TEMPLATE", e.source());
        assertFalse(e.subject().isBlank());
        assertTrue(e.body().contains("Backend Dev"));
    }
}
