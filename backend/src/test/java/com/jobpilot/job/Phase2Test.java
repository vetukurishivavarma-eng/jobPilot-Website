package com.jobpilot.job;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class Phase2Test {

    @Autowired WebApplicationContext ctx;
    @Autowired JobStore store;

    static String fixture(String name) throws Exception {
        return new String(Objects.requireNonNull(Phase2Test.class.getResourceAsStream("/fixtures/" + name)).readAllBytes(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ parsers on real captured responses

    @Test
    void greenhouseFixture() throws Exception {
        List<JobDraft> jobs = JobSources.parseGreenhouse(fixture("greenhouse-razorpay.json"), "razorpaysoftwareprivatelimited", "Razorpay");
        assertFalse(jobs.isEmpty());
        assertTrue(jobs.stream().anyMatch(j -> j.title().equals("Engineering Manager")), "titles: " + jobs.stream().map(JobDraft::title).toList());
        assertTrue(jobs.stream().anyMatch(j -> j.title().equals("Lead Security Engineer")));
        JobDraft j = jobs.getFirst();
        assertEquals("greenhouse:razorpaysoftwareprivatelimited", j.board());
        assertNotNull(j.externalId());
        assertTrue(j.url().startsWith("https://"));
        assertTrue(jobs.stream().anyMatch(x -> x.location() != null && x.location().contains("Bengaluru")));
        assertFalse(j.description().contains("<"), "description should be plain text");
    }

    @Test
    void workdayFixtures() throws Exception {
        // a country facet value "India" wins over "Indiana"
        var wf = JobSources.indiaFacet(JobSources.JSON.readTree(fixture("workday-wf-facets.json")));
        assertEquals("locationCountry", wf.getKey());
        assertEquals(List.of("c4f78be1a8f14da0ab49ce1162348a5e"), wf.getValue());
        // no country facet: every "..., India" location, never "Remote - Indiana"
        var mc = JobSources.indiaFacet(JobSources.JSON.readTree(fixture("workday-mastercard-facets.json")));
        assertEquals("locations", mc.getKey());
        assertTrue(mc.getValue().size() >= 8, "ids: " + mc.getValue());
        assertFalse(mc.getValue().contains("0cdca7f468934b6a9bdb13d7f3c9b67e"));

        JobDraft j = JobSources.parseWorkdayJob(fixture("workday-nvidia-job.json"), "nvidia.wd5/NVIDIAExternalCareerSite", "NVIDIA", "https://x/y");
        assertEquals("workday", j.source());
        assertEquals("workday:nvidia.wd5/NVIDIAExternalCareerSite", j.board());
        assertEquals("NVIDIA", j.company());
        assertTrue(j.title().startsWith("Staff Business Systems Analyst"));
        assertTrue(j.location().contains("Bengaluru"));
        assertTrue(j.url().startsWith("https://nvidia.wd5.myworkdayjobs.com/NVIDIAExternalCareerSite/job/"));
        assertFalse(j.description().contains("<"));
        assertNotNull(j.postedAt());
        assertTrue(MatchScorer.inMarket(j.location(), j.workplace()));
    }

    @Test
    void leverFixture() throws Exception {
        List<JobDraft> jobs = JobSources.parseLever(fixture("lever-cred.json"), "cred", "CRED");
        assertEquals(3, jobs.size());
        for (JobDraft j : jobs) {
            assertEquals("lever", j.source());
            assertEquals("CRED", j.company());
            assertNotNull(j.title());
            assertNotNull(j.postedAt());
            assertTrue(j.url().startsWith("https://jobs.lever.co/cred/"));
        }
    }

    @Test
    void jsonLdFixture() throws Exception {
        JobDraft d = JobSources.parseJsonLd(fixture("lever-job-page.html"), "https://jobs.lever.co/cred/abc?utm=x");
        assertNotNull(d);
        assertEquals("accounts payable manager", d.title().toLowerCase());
        assertEquals("CRED", d.company());
        assertEquals("https://jobs.lever.co/cred/abc", d.externalId());
        assertNotNull(d.description());
        assertNull(JobSources.parseJsonLd("<html><body>no data</body></html>", "https://x.example/"));
    }

    @Test
    void boardUrls() {
        assertEquals(new JobSources.BoardRef(JobSources.Ats.GREENHOUSE, "groww", null), JobSources.parseBoardUrl("https://job-boards.greenhouse.io/groww"));
        assertEquals(new JobSources.BoardRef(JobSources.Ats.GREENHOUSE, "stripe", "123"), JobSources.parseBoardUrl("https://boards.greenhouse.io/stripe/jobs/123?gh_src=x"));
        assertEquals(JobSources.Ats.LEVER, JobSources.parseBoardUrl("https://jobs.lever.co/cred").ats());
        assertNull(JobSources.parseBoardUrl("https://www.linkedin.com/jobs/view/1"));
    }

    // ------------------------------------------------------------ requirement parser, role match, SSRF guard

    @Test
    void jdParser() {
        JdParser.Parsed p = JdParser.parse("Senior Backend Engineer",
                "We need 5+ years of experience building services in Java and Spring Boot, 2 years with Kafka. "
                        + "Bachelor's or Master's degree in CS. Go is a plus, JS too.");
        assertEquals(5, p.minYears());
        assertEquals("SENIOR", p.seniority());
        assertEquals("BACHELOR", p.educationLevel());
        assertTrue(p.skills().containsAll(List.of("Java", "Kafka", "JavaScript")), "skills: " + p.skills());
        assertFalse(p.skills().contains("Go"), "bare 'Go' is ambiguous and must not match");

        // ordinary-English false positives seen on real Databricks postings
        List<String> noise = JdParser.parse("Engineer", "A culture where everyone can excel. You will work with product management.").skills();
        assertFalse(noise.contains("Excel") || noise.contains("Product Management"), "skills: " + noise);
        assertTrue(JdParser.parse("Analyst", "Advanced Excel and SQL").skills().contains("Excel"));

        assertEquals("INTERN", JdParser.parse("Software Engineering Intern", "").seniority());
        assertEquals(0, JdParser.parse("Graduate Engineer", "Freshers welcome").minYears());
        assertEquals("MID", JdParser.parse("Data Analyst", "3-5 years experience in SQL").seniority());
        assertNull(JdParser.parse("Data Analyst", "Must be 21 years old").minYears());
    }

    @Test
    void roleMatch() {
        List<String> roles = List.of("Backend Developer", "Senior Data Scientist");
        assertTrue(RoleMatch.matches("Senior Software Engineer - Backend", roles));
        assertTrue(RoleMatch.matches("Back End Engineer II", roles));
        assertTrue(RoleMatch.matches("Data Scientist", roles));
        assertFalse(RoleMatch.matches("Frontend Engineer", roles));
        assertFalse(RoleMatch.matches("Data Engineer", roles));
        assertTrue(RoleMatch.matches("SDE 2", List.of("Software Engineer")));
        assertTrue(RoleMatch.matches("Full-Stack Developer", List.of("full stack engineer")));
        assertTrue(RoleMatch.matches("Anything", List.of()));
    }

    @Test
    void ssrfGuard() throws Exception {
        for (String ip : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
                "::1", "::ffff:127.0.0.1", "fd00::1"))
            assertFalse(SafeHttp.isPublic(InetAddress.getByName(ip)), ip);
        assertTrue(SafeHttp.isPublic(InetAddress.getByName("8.8.8.8")));
        assertTrue(SafeHttp.isPublic(InetAddress.getByName("2606:4700::1111")));
        assertThrows(SafeHttp.FetchException.class, () -> SafeHttp.getUserUrl("file:///etc/passwd"));
        assertThrows(SafeHttp.FetchException.class, () -> SafeHttp.getUserUrl("http://127.0.0.1/"));
        assertThrows(SafeHttp.FetchException.class, () -> SafeHttp.getUserUrl("https://example.com:8443/"));
    }

    // ------------------------------------------------------------ store: upsert, close vanished, links

    static JobDraft draft(String id, String title) {
        return new JobDraft("greenhouse", id, "greenhouse:test-board", "https://x/" + id, null, title, "Acme", "Remote",
                "REMOTE", null, null, null, null, "Needs 3+ years of experience with Python.", Instant.now());
    }

    @Autowired JobRepository jobs;
    @Autowired UserJobRepository links;

    @Test
    void storeUpsertsClosesAndLinks() throws Exception {
        String auth = register();
        UUID user = UUID.fromString(mvc().perform(get("/api/auth/me").header("Authorization", auth)).andReturn()
                .getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
        String a = "a" + UUID.randomUUID(), b = "b" + UUID.randomUUID();

        JobStore.Outcome o = store.save(List.of(draft(a, "Backend Engineer"), draft(b, "Designer")), "greenhouse:test-board", user,
                UserJob.Origin.COMPANY, d -> RoleMatch.matches(d.title(), List.of("backend developer")));
        assertEquals(2, o.fetched());
        assertEquals(1, o.linked());
        Job ja = jobs.findBySourceAndExternalId("greenhouse", a).orElseThrow();
        assertEquals(3, ja.minYears);

        // b vanished from the board -> closed; a seen again -> stays open, not re-linked
        o = store.save(List.of(draft(a, "Backend Engineer")), "greenhouse:test-board", user, UserJob.Origin.COMPANY, d -> true);
        assertEquals(0, o.linked());
        assertEquals(1, links.linkedAmong(user, List.of(ja.id)).size());
        assertNotNull(jobs.findBySourceAndExternalId("greenhouse", b).orElseThrow().closedAt);
        assertNull(jobs.findBySourceAndExternalId("greenhouse", a).orElseThrow().closedAt);

        // b reappears -> reopened
        store.save(List.of(draft(a, "Backend Engineer"), draft(b, "Designer")), "greenhouse:test-board", user, UserJob.Origin.COMPANY, d -> false);
        assertNull(jobs.findBySourceAndExternalId("greenhouse", b).orElseThrow().closedAt);
    }

    // ------------------------------------------------------------ endpoints (manual path, no network)

    MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build(); }

    String register() throws Exception {
        String body = mvc().perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"u" + UUID.randomUUID() + "@example.com\",\"password\":\"secret123\",\"fullName\":\"Asha\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void jobEndpoints() throws Exception {
        String auth = register(), other = register();
        String manual = "{\"title\":\"Senior Java Developer\",\"company\":\"Zeta\",\"location\":\"Bengaluru (Hybrid)\","
                + "\"url\":\"https://www.linkedin.com/jobs/view/1\",\"description\":\"5+ years of experience with Java, Spring and AWS. B.Tech required.\"}";
        String body = mvc().perform(post("/api/jobs/manual").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(manual))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.origin").value("IMPORT"))
                .andExpect(jsonPath("$.workplace").value("HYBRID"))
                .andExpect(jsonPath("$.minYears").value(5))
                .andExpect(jsonPath("$.educationLevel").value("BACHELOR"))
                .andExpect(jsonPath("$.skills", hasItems("Java", "AWS")))
                .andExpect(jsonPath("$.description", containsString("Spring")))
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc().perform(get("/api/jobs").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Senior Java Developer"))
                .andExpect(jsonPath("$.items[0].description").doesNotExist());
        mvc().perform(get("/api/jobs?q=ZETA").header("Authorization", auth)).andExpect(jsonPath("$.total").value(1));
        mvc().perform(get("/api/jobs?q=python").header("Authorization", auth)).andExpect(jsonPath("$.total").value(0));
        mvc().perform(get("/api/jobs?status=bogus").header("Authorization", auth)).andExpect(status().isBadRequest());

        mvc().perform(patch("/api/jobs/" + id).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISMISSED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISMISSED"));
        mvc().perform(get("/api/jobs").header("Authorization", auth)).andExpect(jsonPath("$.total").value(0));
        mvc().perform(get("/api/jobs?status=DISMISSED").header("Authorization", auth)).andExpect(jsonPath("$.total").value(1));

        // another user can't see or change it
        mvc().perform(get("/api/jobs/" + id).header("Authorization", other)).andExpect(status().isNotFound());
        mvc().perform(patch("/api/jobs/" + id).header("Authorization", other).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SAVED\"}"))
                .andExpect(status().isNotFound());
        mvc().perform(get("/api/jobs").header("Authorization", other)).andExpect(jsonPath("$.total").value(0));

        mvc().perform(post("/api/jobs/manual").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"company\":\"X\",\"url\":\"javascript:alert(1)\",\"description\":\"d\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.title").exists())
                .andExpect(jsonPath("$.fields.url").exists());
        mvc().perform(post("/api/jobs/import").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://169.254.169.254/latest/meta-data\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FETCH_FAILED"));

        // empty test catalog, no Adzuna key -> empty report
        mvc().perform(post("/api/discovery/run").header("Authorization", auth)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void catalogParsesAndMarketFilter() {
        // the test classpath's catalog.txt is empty (no network in tests); the real one must parse
        assertTrue(DiscoveryService.CATALOG.isEmpty());
        assertTrue(MatchScorer.inMarket("Bengaluru, India", null));
        assertTrue(MatchScorer.inMarket("Remote", "REMOTE"));
        assertFalse(MatchScorer.inMarket("Remote - US", "REMOTE"));
        assertFalse(MatchScorer.inMarket("San Francisco, California", null));
        assertFalse(MatchScorer.inMarket(null, null));
    }
}
