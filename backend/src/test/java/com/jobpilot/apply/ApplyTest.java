package com.jobpilot.apply;

import com.jobpilot.profile.Profile.Experience;
import com.jobpilot.profile.ProfileController.ProfileDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class ApplyTest {

    static String fixture(String name) throws Exception {
        return new String(Objects.requireNonNull(ApplyTest.class.getResourceAsStream("/fixtures/" + name)).readAllBytes(), StandardCharsets.UTF_8);
    }

    static FormField field(List<FormField> fs, String labelPart) {
        return fs.stream().filter(f -> f.label().toLowerCase().contains(labelPart.toLowerCase())).findFirst()
                .orElseThrow(() -> new AssertionError("no field '" + labelPart + "' in " + fs.stream().map(FormField::label).toList()));
    }

    // ------------------------------------------------------------ reading real forms

    @Test
    void greenhouseForm() throws Exception {
        List<FormField> f = FormReader.parseGreenhouse(fixture("greenhouse-groww-questions.json"));
        assertEquals("first_name", field(f, "First Name").key());
        FormField resume = field(f, "Resume");
        assertEquals("FILE", resume.type());
        assertEquals("resume", resume.key());
        FormField notice = field(f, "notice period");
        assertEquals("SELECT", notice.type());
        assertEquals(7, notice.options().size());
        assertTrue(field(f, "relative").required());
        assertTrue(f.stream().noneMatch(x -> x.label().equals("Latitude")), "hidden fields are skipped");
    }

    @Test
    void leverForm() throws Exception {
        List<FormField> f = FormReader.parseLever(fixture("lever-meesho-apply.html"));
        assertEquals("FILE", f.getFirst().type());
        assertEquals("resume", f.getFirst().key());
        assertEquals("name", field(f, "Full name").key());
        assertEquals("org", field(f, "Current company").key());
        FormField gender = field(f, "gender");
        assertEquals("SELECT", gender.type());
        assertEquals(List.of("Male", "Female", "Other", "Prefer not to disclose"), gender.options());
        assertTrue(gender.key().matches("cards\\[[0-9a-f-]+]\\[field0]"));
        assertEquals("TEXT", field(f, "Total Years of experience").type());
        assertEquals("SELECT", field(f, "How did you get to know").type());
        assertEquals(f.size(), f.stream().map(FormField::key).distinct().count(), "no duplicate keys");
    }

    /** Real Paytm form (2026-11-08): a demographic survey whose answers live under [responses], plus a nameless country picker. */
    @Test
    void leverSurveyForm() throws Exception {
        List<FormField> f = FormReader.parseLever(fixture("lever-paytm-apply.html"));
        assertEquals(f.size(), f.stream().map(FormField::label).distinct().count(), "no question read twice: " + f.stream().map(FormField::label).toList());
        FormField age = field(f, "age range");
        assertEquals("SELECT", age.type());
        assertTrue(age.key().endsWith("[responses][field0]"), age.key());
        assertTrue(age.options().contains("21-29"));
        assertEquals("MULTISELECT", field(f, "ethnicity").type());
        FormField country = field(f, "What is your location");
        assertTrue(country.key().startsWith(FormReader.BY_CSS));
        assertTrue(country.options().contains("India"));
        assertTrue(f.indexOf(country) < f.indexOf(age), "country is set before the survey it controls");

        List<FormField> out = Answerer.answer(f, ASHA, new Answerer.JobInfo("SDE", "Paytm", ""), Map.of(), null);
        assertEquals("India", field(out, "What is your location").value(), "Bengaluru -> India");
        assertNull(field(out, "age range").value(), "personal survey questions stay the user's choice");
        assertEquals("United States", Answerer.country(List.of("India", "United States"), "Austin, United States"));
        assertNull(Answerer.country(List.of("India", "Iceland"), "Somewhere"));
    }

    // ------------------------------------------------------------ answering

    static ProfileDto profile() {
        return new ProfileDto("Backend engineer", "+91 98765 43210", "Bengaluru", "Software Engineer", new BigDecimal("4.0"),
                2_400_000L, "INR", 30, "Indian citizen", true, "https://linkedin.com/in/asha", null, null, "I build payment systems.",
                List.of("Java", "Spring"), List.of("Backend Engineer"), List.of("Bengaluru"),
                List.of(new Experience("Acme Pay", "Software Engineer", "Bengaluru", LocalDate.of(2022, 1, 1), null, "Payments APIs")),
                List.of(), null);
    }

    static final Answerer.Candidate ASHA = new Answerer.Candidate("Asha Rao Kumar", "asha@example.com", profile(), UUID.randomUUID().toString(), "Asha CV", "Java developer at Acme Pay");

    @Test
    void answersFromProfileBankAndAi() throws Exception {
        List<FormField> form = FormReader.parseLever(fixture("lever-meesho-apply.html"));
        form = new ArrayList<>(form);
        form.add(new FormField("why", "Why do you want to join us?", "TEXTAREA", true, List.of(), null, null));
        form.add(new FormField("ctc", "Current CTC", "TEXT", true, List.of(), null, null));

        List<String> prompts = new ArrayList<>();
        Map<String, String> bank = Map.of(AnswerBank.key("Are you based out of Bangalore ?", "Meesho"), "yes");
        List<FormField> out = Answerer.answer(form, ASHA, new Answerer.JobInfo("SDE", "Meesho", "Build things"), bank,
                p -> { prompts.add(p); return "I have built payment APIs at Acme Pay."; });

        assertEquals("Asha Rao Kumar", field(out, "Full name").value());
        assertEquals("Acme Pay", field(out, "Current company").value());
        assertEquals("Asha CV", field(out, "Resume").value());
        assertEquals("4", field(out, "Total Years of experience").value());
        assertEquals("30", field(out, "Notice Period").value());
        assertEquals("2400000", field(out, "Expected Compensation").value());
        assertEquals("Yes", field(out, "willing to relocate").value());
        assertEquals("Yes", field(out, "based out of Bangalore").value(), "bank answer normalised to the option's spelling");
        assertEquals("BANK", field(out, "based out of Bangalore").source());
        assertEquals("AI", field(out, "Why do you want").source());
        assertNull(field(out, "Current Compensation").value(), "a fact we don't have is never invented");
        assertNull(field(out, "Current CTC").value());
        assertNull(field(out, "gender").value(), "personal questions are left to the user");
        assertEquals(1, prompts.size(), "only the open-ended question goes to the model");
        assertTrue(prompts.getFirst().contains("Acme Pay") && prompts.getFirst().contains(Answerer.NEED_INPUT));

        // the model declining leaves it for the user
        out = Answerer.answer(List.of(new FormField("why", "Why us?", "TEXTAREA", true, List.of(), null, null)), ASHA,
                new Answerer.JobInfo("SDE", "Meesho", ""), Map.of(), p -> "NEED_INPUT");
        assertTrue(out.getFirst().missing());
    }

    @Test
    void greenhouseAnswers() throws Exception {
        List<FormField> out = Answerer.answer(FormReader.parseGreenhouse(fixture("greenhouse-groww-questions.json")), ASHA,
                new Answerer.JobInfo("Analyst", "Groww", ""), Map.of(), null);
        assertEquals("Asha", field(out, "First Name").value());
        assertEquals("Rao Kumar", field(out, "Last Name").value());
        assertEquals("+91 98765 43210", field(out, "Phone").value());
        assertEquals("https://linkedin.com/in/asha", field(out, "LinkedIn").value());
        FormField notice = field(out, "notice period");
        assertNotNull(notice.value(), "options: " + notice.options());
        assertTrue(notice.options().contains(notice.value()));
    }

    @Test
    void helpers() {
        List<String> opts = List.of("Immediate", "15 days", "30 days", "60 days", "90 days");
        assertEquals("Immediate", Answerer.noticeOption(opts, 0));
        assertEquals("30 days", Answerer.noticeOption(opts, 30));
        assertEquals("60 days", Answerer.noticeOption(opts, 50));
        assertEquals("1-2 months", Answerer.noticeOption(List.of("0-15 days", "1-2 months", "3 months"), 45));
        assertEquals("Yes, I do", Answerer.option(List.of("Yes, I do", "No"), "yes"));
        assertNull(Answerer.option(List.of("Yes", "No"), "maybe"));
        assertEquals(AnswerBank.key("Do you have a relative working in Groww?", "Groww"),
                AnswerBank.key("Do you have a relative working in CRED?", "CRED"), "same question at another company");
    }

    @Test
    void browserHelpers() {
        // the ATS-hosted form, never the company page that embeds it
        assertEquals("https://jobs.lever.co/meesho/abc/apply", ApplicationService.applyUrl("lever", "lever:meesho", "abc", "https://jobs.lever.co/meesho/abc"));
        assertEquals("https://job-boards.eu.greenhouse.io/embed/job_app?for=groww&token=1", ApplicationService.applyUrl("greenhouse", "greenhouse:groww", "1", "https://job-boards.eu.greenhouse.io/groww/jobs/1"));
        assertEquals("https://job-boards.greenhouse.io/embed/job_app?for=databricks&token=7", ApplicationService.applyUrl("greenhouse", "greenhouse:databricks", "7", "https://databricks.com/careers?gh_jid=7"));
        assertEquals("https://job-boards.greenhouse.io/embed/job_app?for=x&token=7", ApplicationService.applyUrl("greenhouse", "greenhouse:x", "7", "https://evil.example/greenhouse.io/"));

        // confirmation detection: the sites' thank-you pages, not the form itself
        assertTrue(BrowserFiller.CONFIRMED_URL.matcher("https://job-boards.greenhouse.io/groww/jobs/1/confirmation").find());
        assertTrue(BrowserFiller.CONFIRMED_URL.matcher("https://jobs.lever.co/meesho/abc/thanks").find());
        assertFalse(BrowserFiller.CONFIRMED_URL.matcher("https://jobs.lever.co/meesho/abc/apply").find());
        assertFalse(BrowserFiller.CONFIRMED_URL.matcher("https://jobs.lever.co/thanksgiving-co/abc/apply").find());
        assertTrue(BrowserFiller.CONFIRMED_TEXT.matcher("Thank you for applying to Groww!").find());
        assertFalse(BrowserFiller.CONFIRMED_TEXT.matcher("Submit your application").find());
        assertEquals("cards[a\\\"b]", BrowserFiller.css("cards[a\"b]"));
    }

    // ------------------------------------------------------------ auto mode

    @Autowired AutoApplyService auto;

    @Test
    void autoModePicksBestOpenAtsJobsAndRespectsSettings() throws Exception {
        String reg = mvc().perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"auto" + UUID.randomUUID() + "@example.com\",\"password\":\"secret123\",\"fullName\":\"Asha Rao\"}"))
                .andReturn().getResponse().getContentAsString();
        String auth = "Bearer " + reg.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        UUID user = UUID.fromString(reg.replaceAll(".*\"user\":\\{\"id\":\"([^\"]+)\".*", "$1"));
        mvc().perform(put("/api/profile").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""
                {"willingToRelocate":false,"yearsExperience":4,"skills":["Java","Spring Boot","Kafka"],"desiredRoles":["Backend Engineer"],
                 "desiredLocations":["Bengaluru"],"experiences":[],"educations":[]}""")).andExpect(status().isOk());

        String tag = UUID.randomUUID().toString().substring(0, 8);
        java.util.function.BiFunction<String, String, com.jobpilot.job.JobDraft> draft = (title, desc) -> new com.jobpilot.job.JobDraft(
                "greenhouse", tag + title, "greenhouse:acme" + tag, "https://job-boards.greenhouse.io/acme/jobs/1", null, title, "Acme",
                "Bengaluru, India", null, null, null, null, null, desc, java.time.Instant.now());
        store.save(List.of(
                draft.apply("Backend Engineer", "Java, Spring Boot and Kafka. 3+ years of experience."),
                draft.apply("Backend Engineer II", "Java and Spring Boot. 3+ years of experience."),
                draft.apply("Data Scientist", "Python and machine learning. 8+ years of experience.")
        ), null, user, com.jobpilot.job.UserJob.Origin.COMPANY, d -> true);
        String manual = mvc().perform(post("/api/jobs/manual").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Backend Engineer\",\"company\":\"Paste Co\",\"location\":\"Bengaluru\",\"description\":\"Java Spring Boot Kafka\"}"))
                .andReturn().getResponse().getContentAsString();

        // best first, threshold applied, manual jobs (no ATS form) never picked
        List<UUID> picks = auto.pick(user, 70, 10);
        assertEquals(2, picks.size(), "the data-science job is below 70");
        assertFalse(picks.toString().contains(manual.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1")));
        assertEquals(1, auto.pick(user, 70, 1).size(), "limit");
        assertEquals(List.of(), auto.pick(user, 70, 0));

        // jobs list carries the score, sorts by it, and hides other roles (the Data Scientist job)
        mvc().perform(get("/api/jobs?sort=match").header("Authorization", auth))
                .andExpect(jsonPath("$.items[0].match.score").value(greaterThanOrEqualTo(90)))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].title", org.hamcrest.Matchers.not(hasItem("Data Scientist"))));

        // settings: validated, defaults off
        mvc().perform(get("/api/auto-apply").header("Authorization", auth))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.autoSubmit").value(false))
                .andExpect(jsonPath("$.minScore").value(70)).andExpect(jsonPath("$.submittedToday").value(0));
        mvc().perform(put("/api/auto-apply").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"autoSubmit\":false,\"minScore\":150,\"dailyLimit\":10}"))
                .andExpect(status().isBadRequest());
        mvc().perform(put("/api/auto-apply").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"autoSubmit\":true,\"minScore\":80,\"dailyLimit\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.autoSubmit").value(true));

        // daily limit already used up by a manual submission: the run prepares nothing and says why
        Application done = new Application(user, UUID.fromString(manual.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1")));
        ApplicationService.set(done, List.of());
        done.status = Application.Status.SUBMITTED;
        done.submittedAt = java.time.Instant.now();
        apps.save(done);
        AutoApplyService.Summary s = auto.run(user);
        assertEquals(0, s.prepared());
        assertTrue(s.notes().stream().anyMatch(n -> n.contains("Daily limit")), s.notes().toString());
        mvc().perform(get("/api/auto-apply").header("Authorization", auth))
                .andExpect(jsonPath("$.submittedToday").value(1)).andExpect(jsonPath("$.lastSummary.prepared").value(0));
    }

    // ------------------------------------------------------------ endpoints

    @Autowired WebApplicationContext ctx;
    @Autowired ApplicationRepository apps;
    @Autowired AnswerBankRepository bank;
    @Autowired com.jobpilot.job.JobStore store;

    MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build(); }

    @Test
    void reviewFlow() throws Exception {
        String reg = mvc().perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a" + UUID.randomUUID() + "@example.com\",\"password\":\"secret123\",\"fullName\":\"Asha\"}"))
                .andReturn().getResponse().getContentAsString();
        String auth = "Bearer " + reg.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        UUID user = UUID.fromString(reg.replaceAll(".*\"user\":\\{\"id\":\"([^\"]+)\".*", "$1"));

        // a manually added job can't be applied to by the agent
        String job = mvc().perform(post("/api/jobs/manual").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Dev\",\"company\":\"Groww\",\"description\":\"Java\"}"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc().perform(post("/api/applications").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{\"jobId\":\"" + job + "\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("UNSUPPORTED_SITE"));

        // a prepared application with two open questions
        Application a = new Application(user, UUID.fromString(job));
        ApplicationService.set(a, List.of(
                new FormField("q1", "Do you have a relative working in Groww?", "SELECT", true, List.of("Yes", "No"), null, null),
                new FormField("q2", "Current CTC", "TEXT", true, List.of(), null, null),
                new FormField("email", "Email", "TEXT", true, List.of(), "a@example.com", "PROFILE")));
        apps.save(a);

        mvc().perform(get("/api/applications").header("Authorization", auth))
                .andExpect(jsonPath("$[0].status").value("NEEDS_INPUT")).andExpect(jsonPath("$[0].missing").value(2))
                .andExpect(jsonPath("$[0].company").value("Groww"));
        mvc().perform(put("/api/applications/" + a.id + "/answers").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":{\"q1\":\"maybe\"}}"))
                .andExpect(status().isBadRequest());
        mvc().perform(put("/api/applications/" + a.id + "/answers").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":{\"q1\":\"no\",\"q2\":\"1800000\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.fields[0].value").value("No"))
                .andExpect(jsonPath("$.fields[0].source").value("USER"));

        // remembered for the same question at another company
        assertEquals("No", bank.findByUserIdAndQuestionKey(user, AnswerBank.key("Do you have a relative working in CRED?", "CRED")).orElseThrow().answer);

        String other = mvc().perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"b" + UUID.randomUUID() + "@example.com\",\"password\":\"secret123\",\"fullName\":\"B\"}"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        mvc().perform(get("/api/applications/" + a.id).header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
    }
}
