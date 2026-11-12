package com.jobpilot.interview;

import com.jobpilot.profile.Profile.Experience;
import com.jobpilot.tailor.Tailor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test") // ollama-url points at a closed port: no real model is ever called
class InterviewTest {

    static final Experience ACME = new Experience("Acme Pay", "Software Engineer", "Bengaluru", LocalDate.of(2022, 1, 1), null,
            "Designed a ledger service in Java handling 2M transactions a day.\nReduced p99 latency by 40% with Kafka-based batching.");
    static final Tailor.Facts FACTS = new Tailor.Facts("Asha Rao", "asha@example.com", null, "Bengaluru", null, "Backend engineer",
            "I build payment systems.", "4", "Software Engineer", List.of("Java", "Spring Boot", "Kafka"), Set.of(), List.of(ACME), List.of(), null);
    static final Tailor.JobInfo JOB = new Tailor.JobInfo("Backend Engineer", "Groww", "We need Java, Spring Boot and Kafka for our ledger.",
            List.of("Java", "Kafka", "Spring Boot"));

    @Test
    void questionSetFromOneCallParsedLeniently() {
        List<String> prompts = new ArrayList<>();
        Interview.Content c = Interview.questions(FACTS, JOB, p -> {
            prompts.add(p);
            return """
                    Here you go:
                    ```json
                    {"questions":[{"type":"Behavioral","q":"1. Tell me about a time you fixed a production outage."},
                     {"category":"technical","question":"How does Kafka guarantee ordering within a partition?"},
                     "How would you make a Spring Boot service idempotent?",
                     {"type":"role","q":"Why do you want to build ledgers at Groww?"},
                     {"type":"technical","q":"How does Kafka guarantee ordering within a partition?"},
                     {"q":"short"}]}
                    ```""";
        });
        assertEquals(1, prompts.size(), "one call for the whole set");
        assertTrue(prompts.getFirst().indexOf("Return JSON only") < prompts.getFirst().indexOf("CANDIDATE:"), "instructions first, data last");
        assertEquals("AI", c.source());
        assertEquals(List.of("BEHAVIOURAL", "TECHNICAL", "TECHNICAL", "ROLE"), c.items().stream().map(Interview.Item::type).toList(), "deduped, junk dropped");
        assertEquals("Tell me about a time you fixed a production outage.", c.items().getFirst().question());

        // garbage or no model: standard questions on the shared skills, never an error
        for (Interview.Content t : List.of(Interview.questions(FACTS, JOB, p -> "I can't do that"), Interview.questions(FACTS, JOB, p -> null))) {
            assertEquals("TEMPLATE", t.source());
            assertEquals(6, t.items().size());
            assertTrue(t.items().get(2).question().contains("Java"));
            assertTrue(t.items().getLast().question().contains("Groww"));
        }
    }

    @Test
    void messyRubricReplyParsedAndImprovedAnswerFactChecked() {
        String messy = """
                Sure! Here's my evaluation:
                ```json
                {"Relevance": "4/5", "scores": {"STAR": 2, "Technical correctness": "3"}, "Concision": 5.0,
                 "Improvements": "1. Say what the result was\\n- Name the tool you used\\n\\n",
                 "Improved Answer": "At Acme Pay I cut p99 latency by 40% and then by 70% using Kafka and AWS, saving 3x cost."}
                ```
                Hope this helps!""";
        Interview.Item q = new Interview.Item("TECHNICAL", "How did you reduce latency?", null, null);
        Interview.Feedback fb = Interview.feedback(FACTS, "Backend Engineer at Groww", q, "I batched Kafka writes to cut latency.", p -> messy);

        assertEquals(Map.of("relevance", 4, "specificity", 2, "technical", 3, "concision", 5), fb.rubric());
        assertEquals(4, fb.score(), "mean 3.5 rounds to 4");
        assertEquals(List.of("Say what the result was", "Name the tool you used"), fb.improvements());
        // 40% is the candidate's, 70% and 3x are not -> placeholder; AWS isn't in their skills -> flagged
        assertEquals("At Acme Pay I cut p99 latency by 40% and then by " + Interview.ADD_NUMBER + " using Kafka and AWS, saving "
                + Interview.ADD_NUMBER + " cost.", fb.improvedAnswer());
        String w = String.join(" ", fb.warnings());
        assertTrue(w.contains("AWS") && w.contains(Interview.ADD_NUMBER), w);

        // prompt: stable rubric first, the answer last and capped
        String prompt = Interview.feedbackPrompt(FACTS, "Backend Engineer", q, "x".repeat(10_000));
        assertTrue(prompt.indexOf("relevance:") < prompt.indexOf("ANSWER:"));
        assertTrue(prompt.length() < 6000, "answer cut to 3000 chars: " + prompt.length());

        assertNull(Interview.feedback(FACTS, "r", q, "a", p -> "no json here"), "garbage -> null, controller says AI unavailable");
        assertNull(Interview.feedback(FACTS, "r", q, "a", p -> null));
        assertEquals(1, Interview.parseFeedback("{\"relevance\": 0, \"concision\": \"ten\"}").score(), "clamped to 1-5, unreadable dropped");
    }

    // ------------------------------------------------------------ endpoints

    @Autowired WebApplicationContext ctx;
    MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build(); }

    String register() throws Exception {
        String reg = mvc().perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"i" + UUID.randomUUID() + "@example.com\",\"password\":\"secret123\",\"fullName\":\"Asha Rao\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + reg.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void endpoints() throws Exception {
        String auth = register();
        String job = mvc().perform(post("/api/jobs/manual").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Backend Engineer\",\"company\":\"Groww\",\"description\":\"Java, Spring Boot and Kafka.\"}"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc().perform(post("/api/interview/sessions").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        String s = mvc().perform(post("/api/interview/sessions").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":\"" + job + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Backend Engineer at Groww"))
                .andExpect(jsonPath("$.source").value("TEMPLATE"))
                .andExpect(jsonPath("$.items.length()").value(6))
                .andReturn().getResponse().getContentAsString().replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1");
        mvc().perform(post("/api/interview/sessions").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"Data Engineer\"}"))
                .andExpect(jsonPath("$.title").value("Data Engineer")).andExpect(jsonPath("$.jobId").isEmpty());

        // no model in tests: a clear 503, never a 500
        mvc().perform(post("/api/interview/sessions/" + s + "/answers/0").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"I shipped it on time.\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
        mvc().perform(post("/api/interview/sessions/" + s + "/answers/9").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"x\"}")).andExpect(status().isNotFound());
        mvc().perform(get("/api/interview/sessions").header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id=='" + s + "')].total").value(org.hamcrest.Matchers.contains(6)))
                .andExpect(jsonPath("$[?(@.id=='" + s + "')].answered").value(org.hamcrest.Matchers.contains(0)));

        String other = register();
        mvc().perform(get("/api/interview/sessions/" + s).header("Authorization", other)).andExpect(status().isNotFound());
        mvc().perform(post("/api/interview/sessions").header("Authorization", other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"" + job + "\"}")).andExpect(status().isNotFound());
        mvc().perform(delete("/api/interview/sessions/" + s).header("Authorization", other)).andExpect(status().isNotFound());
        mvc().perform(delete("/api/interview/sessions/" + s).header("Authorization", auth)).andExpect(status().isNoContent());
    }

    @Test
    void liveInterviewFollowsUpOnWhatTheCandidateSaid() {
        Interview.Item intro = Interview.liveStart().items().get(0);
        String answer = "I built a ledger service in Java at Acme Pay that handles 2M transactions a day.";
        List<String> prompts = new ArrayList<>();
        Interview.Turn t = Interview.liveTurn(FACTS, JOB, List.of(), intro, answer, false, p -> {
            prompts.add(p);
            return "{\"relevance\":4,\"specificity\":3,\"technical\":4,\"concision\":5,\"improvements\":[\"Say your part\"],"
                    + "\"improved_answer\":\"I built a ledger in Java.\",\"next_question\":\"How did your ledger stay consistent when a Kafka consumer crashed mid-batch?\"}";
        });
        assertTrue(prompts.get(0).contains(answer) && prompts.get(0).contains("next_question"));
        assertEquals(4, t.feedback().score());
        assertTrue(t.next().contains("ledger"));

        // unreadable reply: still scored nothing, but the next question falls back to the standard set
        Interview.Turn bad = Interview.liveTurn(FACTS, JOB, List.of(), intro, answer, false, p -> "{\"relevance\":3}");
        assertNotNull(bad.next());
        // last question: no follow-up asked
        Interview.Turn last = Interview.liveTurn(FACTS, JOB, List.of(), intro, answer, true, p -> {
            assertFalse(p.contains("next_question"));
            return "{\"relevance\":3}";
        });
        assertNull(last.next());
    }

    @Test
    void reportReadinessAndFallback() {
        Interview.Feedback low = new Interview.Feedback(2, Map.of("relevance", 3, "specificity", 1, "technical", 2, "concision", 5), List.of(), null, List.of());
        List<Interview.Item> items = List.of(new Interview.Item("INTRO", "q", "a", low), new Interview.Item("FOLLOW_UP", "q2", "a2", low));
        assertEquals(44, Interview.readiness(items)); // mean 2.75 -> (2.75-1)/4
        Interview.Report fb = Interview.report(FACTS, JOB, items, p -> "not json");
        assertEquals("TEMPLATE", fb.source());
        assertEquals("specificity", fb.gaps().get(0).area()); // weakest first
        Interview.Report ai = Interview.report(FACTS, JOB, items, p ->
                "{\"verdict\":\"Not yet.\",\"strengths\":[\"Clear\"],\"gaps\":[{\"area\":\"System design\",\"detail\":\"Explain trade-offs\"}],\"focus\":[\"STAR\"]}");
        assertEquals("AI", ai.source());
        assertEquals("System design", ai.gaps().get(0).area());
        assertEquals(44, ai.readiness());
    }

    @Test
    void liveSessionAnswersInOrderOnly() throws Exception {
        String auth = register();
        String body = mvc().perform(post("/api/interview/sessions").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"Backend Engineer\",\"live\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("LIVE")).andExpect(jsonPath("$.items.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String id = Interview.JSON.readTree(body).get("id").asString();
        // a later question can't be answered first
        mvc().perform(post("/api/interview/sessions/" + id + "/answers/3").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"x\"}")).andExpect(status().isNotFound());
        // no model in tests -> 503, the question stays open
        mvc().perform(post("/api/interview/sessions/" + id + "/answers/0").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"I build payment systems.\"}")).andExpect(status().isServiceUnavailable());
        mvc().perform(get("/api/interview/sessions").header("Authorization", auth))
                .andExpect(jsonPath("$[0].mode").value("LIVE")).andExpect(jsonPath("$[0].total").value(8));
        // the phone's eye-contact page loads without a token
        mvc().perform(get("/eye.html")).andExpect(status().isOk());
        // the web app is served from this origin: its pages are public, the API is not
        mvc().perform(get("/jobs")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc().perform(get("/interview/" + id)).andExpect(forwardedUrl("/index.html"));
        mvc().perform(get("/api/jobs")).andExpect(status().isUnauthorized());
    }

    @Test
    void questionsArePitchedAtTheCandidatesLevel() {
        Tailor.Facts fresher = new Tailor.Facts("Ravi K", "r@example.com", null, "Hyderabad", null, "CSE graduate", null, "0",
                null, List.of("Java", "SQL"), Set.of(), List.of(), List.of(),
                "PROJECTS: Library management system in Java and MySQL with JWT login.");
        String p = Interview.livePrompt(fresher, JOB, List.of(), Interview.liveStart().items().get(0), "I built a library app.", false);
        assertTrue(p.contains("FRESHER") && p.contains("CS fundamentals"));
        assertTrue(p.contains("Library management system"), "a fresher's projects reach the interviewer");
        assertTrue(Interview.reportPrompt(fresher, JOB, List.of()).contains("FRESHER"));
        assertTrue(Interview.livePrompt(FACTS, JOB, List.of(), Interview.liveStart().items().get(0), "x", false).contains("MID-LEVEL")); // 4 years
        assertTrue(Interview.questionsPrompt(fresher, JOB).contains("FRESHER"));
    }
}
