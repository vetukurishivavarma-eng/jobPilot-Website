package com.jobpilot.digest;

import com.jobpilot.auth.User;
import com.jobpilot.auth.UserRepository;
import com.jobpilot.job.MatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class DigestTest {
    @Autowired WebApplicationContext ctx;
    @Autowired DigestController digest;
    @Autowired UserRepository users;

    @Test
    void emailListsJobsAndFollowUpsEscaped() {
        var jobs = List.of(new MatchService.NewMatch(UUID.randomUUID(), "Backend <Engineer>", "Groww", "Bengaluru", 86, "https://x.test/1"),
                new MatchService.NewMatch(UUID.randomUUID(), "SDE II", "Zscaler", null, null, null));
        String html = DigestController.html("Asha Rao", jobs, List.of("SDE I at Hevo"), "https://api.test/unsubscribe?u=1&t=2");
        assertTrue(html.contains("Hi Asha,"));
        assertTrue(html.contains("Backend &lt;Engineer&gt;") && !html.contains("<Engineer>"));
        assertTrue(html.contains("86") && html.contains("SDE I at Hevo") && html.contains("Unsubscribe"));
        assertTrue(html.contains("u=1&amp;t=2"));
        assertEquals("2 new jobs for you, including Backend <Engineer> at Groww", DigestController.subject(jobs, List.of()));
        assertEquals("Time to follow up on 1 application", DigestController.subject(List.of(), List.of("x")));
    }

    @Test
    void unsubscribeNeedsTheSignedLink() throws Exception {
        User u = users.save(new User("d" + UUID.randomUUID() + "@example.com", "x", "Asha Rao"));
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        mvc.perform(get("/unsubscribe").param("u", u.getId().toString()).param("t", "forged"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Link not valid")));
        assertTrue(users.findById(u.getId()).orElseThrow().isEmailDigest());

        String link = digest.unsubscribeLink(u.getId());
        String t = link.substring(link.indexOf("&t=") + 3);
        mvc.perform(get("/unsubscribe").param("u", u.getId().toString()).param("t", t))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Unsubscribed")));
        assertFalse(users.findById(u.getId()).orElseThrow().isEmailDigest());
    }

    @Test
    void nothingNewMeansNoEmail() {
        User u = users.save(new User("n" + UUID.randomUUID() + "@example.com", "x", "New User"));
        assertFalse(digest.sendOne(u.getId())); // runs the new-jobs + follow-ups queries; nothing to say -> no mail
        assertNull(users.findById(u.getId()).orElseThrow().getDigestSentAt());
    }
}
