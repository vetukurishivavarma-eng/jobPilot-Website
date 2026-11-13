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

    static Interview.Content after(String... answers) {
        List<Interview.Item> items = new ArrayList<>();
        items.add(new Interview.Item("INTRO", Interview.INTRO, answers[0], null));
        for (int i = 1; i < answers.length; i++) items.add(new Interview.Item("FOLLOW_UP", "Q" + i + " about something specific?", answers[i], null));
        return new Interview.Content("AI", items, "LIVE", null, 0, null);
    }

    @Test
    void nextQuestionIsOneShortCallOnWhatTheyJustSaid() {
        String answer = "I built a ledger service in Java at Acme Pay that handles 2M transactions a day.";
        List<String> prompts = new ArrayList<>();
        Interview.Item next = Interview.next(FACTS, JOB, after(answer), p -> {
            prompts.add(p);
            return "Next question: \"How did your ledger stay consistent when a Kafka consumer crashed mid-batch?\"";
        });
        assertEquals("How did your ledger stay consistent when a Kafka consumer crashed mid-batch?", next.question()); // label + quotes stripped
        String p = prompts.get(0);
        assertTrue(p.contains(answer) && p.contains("ONLY the next question") && !p.contains("JSON"));
        // token budget: old answers are not resent, only the latest
        Interview.Item later = Interview.next(FACTS, JOB, after("OLD ANSWER TEXT", "second", "latest answer here"), q -> { prompts.add(q); return "Tell me about how you tested that service?"; });
        assertFalse(prompts.get(1).contains("OLD ANSWER TEXT"));
        assertTrue(prompts.get(1).contains("latest answer here"));
        assertNotNull(later);
        // model down -> standard question, the interview goes on
        assertNotNull(Interview.next(FACTS, JOB, after(answer), q -> null).question());
        // the closing HR question is a template: no model call
        String[] eight = new String[8];
        Arrays.fill(eight, "an answer");
        Interview.Content seven = after(Arrays.copyOf(eight, 7));
        Interview.Item closing = Interview.next(FACTS, JOB, seven, q -> { throw new AssertionError("no call for the closing question"); });
        assertTrue(closing.question().contains("Groww"));
        assertNull(Interview.next(FACTS, JOB, after(eight), q -> "x")); // plan done
    }

    @Test
    void readingLikeAnswerGetsAProbeAndTenseGetsWarmth() {
        Interview.Content c = after("I built a ledger.");
        List<Interview.Item> items = new ArrayList<>(c.items());
        Interview.Signals reading = new Interview.Signals(40, 0, 55, 0, 0, false);
        items.set(0, new Interview.Item("INTRO", Interview.INTRO, "I built a ledger.", null, reading));
        Interview.Item probe = Interview.next(FACTS, JOB, new Interview.Content("AI", items, "LIVE", null, 0, null), q -> { throw new AssertionError("probe is a template"); });
        assertEquals("PROBE", probe.type());
        // the probe doesn't use up a planned question
        items.add(new Interview.Item("PROBE", Interview.PROBE, "No notes, sorry.", null, null));
        assertEquals(1, new Interview.Content("AI", items, "LIVE", null, 0, null).planAnswered());
        // short answers and pasted text
        assertFalse(new Interview.Signals(8, 0, 90, 0, 0, false).suspicious());
        assertTrue(new Interview.Signals(5, 0, 0, 0, 0, true).suspicious());
        // tense -> the next question opens warmly
        items = new ArrayList<>(c.items());
        items.set(0, new Interview.Item("INTRO", Interview.INTRO, "um I built a ledger", null, new Interview.Signals(30, 0, 0, 60, 0, false)));
        assertTrue(Interview.nextPrompt(FACTS, JOB, items, 1).contains("looks tense"));
    }

    @Test
    void oneEvaluationCallScoresEverythingAndWritesTheReport() {
        Interview.Content c = after("I built a ledger in Java.", "Kafka with idempotency keys.");
        Interview.Evaluation ev = Interview.evaluate(FACTS, JOB, c.items(), p -> """
                {"scores":[{"q":1,"s":[5,3,4,4],"tip":"Say your own part."},
                           {"q":2,"s":[2,1,2,3],"tip":"Explain how the keys are stored."}],
                 "improved":{"q":2,"a":"I used Kafka with idempotency keys and cut duplicates by 90%."},
                 "verdict":"Close.","strengths":["Clear"],"gaps":[{"area":"Kafka depth","detail":"Explain offsets"}],"focus":["STAR"]}""");
        assertEquals(4, ev.items().get(0).feedback().score());
        assertEquals(List.of("Say your own part."), ev.items().get(0).feedback().improvements());
        assertTrue(ev.items().get(1).feedback().improvedAnswer().contains(Interview.ADD_NUMBER), "invented 90% replaced");
        assertEquals("Kafka depth", ev.report().gaps().get(0).area());
        assertEquals(50, ev.report().readiness()); // mean 3.0 -> (3-1)/4
        assertNull(Interview.evaluate(FACTS, JOB, c.items(), p -> "not json"));
        // the prompt asks for the compact form; a model that spells the keys out still parses
        assertTrue(Interview.evaluatePrompt(FACTS, JOB, c.items()).contains("\"s\":[r,p,t,c]"));
        Interview.Evaluation longForm = Interview.evaluate(FACTS, JOB, c.items(), p ->
                "{\"scores\":[{\"q\":1,\"relevance\":4,\"specificity\":4,\"technical\":4,\"concision\":4}],\"verdict\":\"Good.\"}");
        assertEquals(4, longForm.items().get(0).feedback().score());
    }

    @Test
    void liveSessionFlowViolationsAndPolicyStop() throws Exception {
        String auth = register();
        String body = mvc().perform(post("/api/interview/sessions").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"Backend Engineer\",\"live\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("LIVE")).andExpect(jsonPath("$.items.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String id = Interview.JSON.readTree(body).get("id").asString();
        String base = "/api/interview/sessions/" + id;
        mvc().perform(post(base + "/answers/3").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"x\"}")).andExpect(status().isNotFound());
        // no model in tests -> the standard next question; signals are stored with the answer
        mvc().perform(post(base + "/answers/0").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"I build payment systems.\",\"signals\":{\"seconds\":20,\"eyesOff\":10,\"tense\":5}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].signals.eyesOff").value(10)).andExpect(jsonPath("$.done").value(false));
        mvc().perform(post(base + "/answers/0").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"again\"}")).andExpect(status().isConflict());
        mvc().perform(post(base + "/report").header("Authorization", auth)).andExpect(status().isConflict());
        // phone on camera: warned once, stopped the second time, and the server refuses further answers
        mvc().perform(post(base + "/violations").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"PHONE\"}"))
                .andExpect(jsonPath("$.violations").value(1)).andExpect(jsonPath("$.ended").doesNotExist());
        mvc().perform(post(base + "/violations").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"PHONE\"}"))
                .andExpect(jsonPath("$.ended").value("POLICY")).andExpect(jsonPath("$.report.source").value("POLICY"));
        mvc().perform(post(base + "/answers/1").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"x\"}")).andExpect(status().isConflict());
        mvc().perform(get("/api/interview/sessions").header("Authorization", auth))
                .andExpect(jsonPath("$[0].mode").value("LIVE")).andExpect(jsonPath("$[0].total").value(8)).andExpect(jsonPath("$[0].answered").value(1));
        // leaving: a spoken "close it" or the End button stops the interview with no model call
        String q = Interview.JSON.readTree(mvc().perform(post("/api/interview/sessions").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"Backend Engineer\",\"live\":true}")).andReturn().getResponse().getContentAsString()).get("id").asString();
        mvc().perform(post("/api/interview/sessions/" + q + "/answers/0").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"I don't want this interview, close it.\"}"))
                .andExpect(jsonPath("$.ended").value("QUIT")).andExpect(jsonPath("$.report.source").value("QUIT")).andExpect(jsonPath("$.items.length()").value(1));
        mvc().perform(post("/api/interview/sessions/" + q + "/end").header("Authorization", auth)).andExpect(jsonPath("$.ended").value("QUIT"));
        assertEquals("I led the migration.", Interview.stripNudge("I led the Please look at the camera. migration.").replace("  ", " "));
        assertEquals("", Interview.stripNudge("Please, look at the camera!"));
        assertEquals("Yes.", Interview.stripNudge("Stay in the camera frame. Last warning. Yes."));
        // leaving the camera frame a 3rd time: the page reports AWAY once and the interview is cancelled as cheating
        String g = Interview.JSON.readTree(mvc().perform(post("/api/interview/sessions").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"Backend Engineer\",\"live\":true}")).andReturn().getResponse().getContentAsString()).get("id").asString();
        mvc().perform(post("/api/interview/sessions/" + g + "/violations").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"AWAY\"}"))
                .andExpect(jsonPath("$.ended").value("CHEATING")).andExpect(jsonPath("$.report.source").value("CHEATING"));
        mvc().perform(post("/api/interview/sessions/" + g + "/answers/0").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"x\"}")).andExpect(status().isConflict());
        assertTrue(Interview.wantsToQuit("Stop the interview please"));
        assertTrue(Interview.wantsToQuit("close it"));
        assertTrue(Interview.wantsToQuit("I do not want to continue"));
        assertFalse(Interview.wantsToQuit("I'd finish it on time and close the ticket."));
        assertFalse(Interview.wantsToQuit("We had to stop the release because of a bug, then I fixed it."));
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
        String p = Interview.nextPrompt(fresher, JOB, after("I built a library app.").items(), 1);
        assertTrue(p.contains("FRESHER") && p.contains("CS fundamentals"));
        assertTrue(p.contains("Library management system"), "a fresher's projects reach the interviewer");
        assertTrue(Interview.evaluatePrompt(fresher, JOB, after("x").items()).contains("FRESHER"));
        assertTrue(Interview.nextPrompt(FACTS, JOB, after("x").items(), 1).contains("MID-LEVEL")); // 4 years
        assertTrue(Interview.questionsPrompt(fresher, JOB).contains("FRESHER"));
        // each slot has a fixed focus, so a fresher gets fundamentals, DSA and HR, not 7 questions on one project
        assertTrue(Interview.nextPrompt(fresher, JOB, after("a", "b").items(), 3).contains("OOP or DBMS"));
        assertTrue(Interview.focus(fresher, 4).contains("nothing graph-heavy"));
        assertTrue(Interview.focus(fresher, 7).startsWith("HR"));
        assertTrue(Interview.focus(FACTS, 3).startsWith("system design"));
    }

    @Autowired InterviewRecordingRepository recordings;

    @Test
    void recordingDownloadableForAnHourThenGone() throws Exception {
        String auth = register();
        String id = Interview.JSON.readTree(mvc().perform(post("/api/interview/sessions").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"Backend Engineer\",\"live\":true}")).andReturn().getResponse().getContentAsString()).get("id").asString();
        String base = "/api/interview/sessions/" + id;
        byte[] video = "fake-webm-bytes".getBytes();
        mvc().perform(put(base + "/recording").header("Authorization", auth).contentType("video/webm").content(video))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recordingExpiresAt").exists());
        mvc().perform(put(base + "/recording").header("Authorization", auth).contentType("text/html").content(video))
                .andExpect(status().isUnsupportedMediaType());
        mvc().perform(get(base + "/recording").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(content().bytes(video));
        mvc().perform(get(base + "/recording").header("Authorization", register())).andExpect(status().isNotFound()); // someone else's

        // an hour later the purge removes it
        InterviewRecording r = recordings.findById(UUID.fromString(id)).orElseThrow();
        r.createdAt = java.time.Instant.now().minus(java.time.Duration.ofMinutes(61));
        recordings.save(r);
        mvc().perform(get(base + "/recording").header("Authorization", auth)).andExpect(status().isGone());
        recordings.deleteOlderThan(java.time.Instant.now().minus(InterviewRecording.TTL));
        assertTrue(recordings.findById(UUID.fromString(id)).isEmpty());
        mvc().perform(get(base).header("Authorization", auth)).andExpect(jsonPath("$.recordingExpiresAt").doesNotExist());

        // spoken answer with no speech-to-text configured -> 503, the question stays open
        mvc().perform(multipart(base + "/answers/0/audio").file(new org.springframework.mock.web.MockMultipartFile("audio", "a.webm", "audio/webm", new byte[100]))
                .header("Authorization", auth)).andExpect(status().isServiceUnavailable());
    }

    @Test
    void companyPacksSteerTheQuestions() {
        CompanyPack tcs = CompanyPack.byId("TCS").orElseThrow();
        assertEquals("Systems Engineer (fresher) at TCS", tcs.title());
        assertEquals(tcs, CompanyPack.forTitle(tcs.title()).orElseThrow(), "a running session finds its pack from its title");
        assertTrue(Interview.focus(FACTS, tcs.job(), 4).contains("coding logic"), "the pack's own plan");
        assertEquals(Interview.focus(FACTS, 4), Interview.focus(FACTS, JOB, 4), "ordinary jobs keep the level plan");
        String prompt = Interview.nextPrompt(FACTS, tcs.job(), List.of(new Interview.Item("INTRO", "Tell me about yourself", "I built a payments app", null)), 7);
        assertTrue(prompt.contains("why TCS") && prompt.contains("never claim to have seen real interview reports"));
        for (CompanyPack p : CompanyPack.all()) assertEquals(Interview.LIVE_QUESTIONS, p.plan().size(), p.id() + ": one focus per question");
    }
}
