package com.jobpilot.tailor;

import com.jobpilot.profile.Profile.Education;
import com.jobpilot.profile.Profile.Experience;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipInputStream;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class TailorTest {

    static final Experience ACME = new Experience("Acme Pay", "Software Engineer", "Bengaluru", LocalDate.of(2022, 1, 1), null,
            "Built internal admin dashboards in React.\nDesigned a ledger service in Java handling 2M transactions a day.\nReduced p99 latency by 40% with Kafka-based batching.");
    static final Tailor.Facts FACTS = new Tailor.Facts("Asha Rao", "asha@example.com", "+91 90000 00000", "Bengaluru", null,
            "Backend engineer", "I build payment systems.", "4", "Software Engineer", List.of("React", "Java", "Spring Boot"),
            Set.of("Kafka", "Java"), List.of(ACME), List.of(new Education("IIT Madras", "B.Tech", "Computer Science", 2017, 2021, null)),
            "Java developer. Kafka. 2M transactions a day.");
    static final Tailor.JobInfo JOB = new Tailor.JobInfo("Backend Engineer", "Groww", "Java, Spring Boot and Kafka for our ledger.",
            List.of("Java", "Kafka", "Spring Boot"));

    @Test
    void skillsReorderedNeverInvented() {
        Tailor.Choice c = Tailor.tailorSkills(FACTS, new TreeSet<>(String.CASE_INSENSITIVE_ORDER) {{ addAll(JOB.skills()); }});
        assertEquals("React, Java, Spring Boot", c.original());
        assertEquals("Java, Spring Boot, Kafka, React", c.tailored(), "job skills first; Kafka comes from the user's own resume");
        assertTrue(c.why().contains("added from your resume: Kafka"));
        assertFalse(c.tailored().contains("AWS"));
    }

    @Test
    void deterministicTailoringWithoutAi() {
        Tailor.TailoredResume r = Tailor.resume(FACTS, JOB, null);
        List<String> bullets = Tailor.bullets(r.experiences().getFirst().bullets().tailored());
        assertTrue(bullets.getFirst().contains("ledger") || bullets.getFirst().contains("Kafka"), "most relevant first: " + bullets);
        assertEquals("Built internal admin dashboards in React.", bullets.getLast());
        assertEquals("I build payment systems.", r.summary().chosen(), "no AI: profile summary kept");
        assertEquals("B.Tech, Computer Science — IIT Madras (2017–2021)", r.education().getFirst());
        assertTrue(r.experiences().getFirst().meta().contains("Jan 2022 – Present"));
    }

    @Test
    void aiRewritesAreFactChecked() {
        // honest model: same facts, new wording -> accepted
        Function<String, String> honest = p -> p.contains("BULLETS:")
                ? "Designed a Java ledger service processing 2M transactions a day.\nCut p99 latency 40% using Kafka batching.\nBuilt React admin dashboards."
                : "Backend engineer with 4 years building Java payment systems.";
        Tailor.TailoredResume ok = Tailor.resume(FACTS, JOB, honest);
        assertTrue(ok.experiences().getFirst().bullets().useTailored());
        assertTrue(ok.experiences().getFirst().bullets().tailored().contains("Cut p99 latency 40%"));
        assertEquals("Backend engineer with 4 years building Java payment systems.", ok.summary().chosen());
        assertTrue(ok.notes().isEmpty(), ok.notes().toString());

        // inventing model: new number and a skill the user never listed -> thrown away, user told why
        Function<String, String> liar = p -> p.contains("BULLETS:")
                ? "Designed a ledger handling 5M transactions on AWS.\nReduced latency 40% with Kafka.\nBuilt dashboards."
                : "Backend engineer with 7 years of experience.";
        Tailor.TailoredResume bad = Tailor.resume(FACTS, JOB, liar);
        assertFalse(bad.experiences().getFirst().bullets().chosen().contains("5M"));
        assertEquals("I build payment systems.", bad.summary().chosen());
        String notes = String.join(" ", bad.notes());
        assertTrue(notes.contains("\"5\"") && notes.contains("AWS") && notes.contains("\"7\""), notes);

        // seen live from qwen2.5:3b: a role's bullets replaced with plausible new claims using the user's *other* skills
        Experience zeta = new Experience("Zeta Labs", "Associate Engineer", "Hyderabad", LocalDate.of(2021, 1, 1), LocalDate.of(2021, 12, 31),
                "Wrote REST APIs in Spring Boot for card onboarding.\nAdded integration tests that cut release bugs.");
        Tailor.Facts withZeta = new Tailor.Facts(FACTS.name(), FACTS.email(), FACTS.phone(), FACTS.location(), null, FACTS.headline(),
                FACTS.summary(), "4", FACTS.currentTitle(), List.of("Java", "Kafka", "Spring Boot"), Set.of(), List.of(zeta), List.of(), null);
        Tailor.TailoredResume invented = Tailor.resume(withZeta, JOB, p -> p.contains("BULLETS:")
                ? "Implemented backend services in Java for payments processing.\nDesigned and integrated Kafka for real-time data streams." : null);
        assertEquals(zeta.description(), invented.experiences().getFirst().bullets().chosen(), "original kept");
        String why = String.join(" ", invented.notes());
        assertTrue(why.contains("Kafka") && why.contains("changes what"), why);

        // seen live: the summary echoed the resume header line
        assertEquals("Backend engineer with 4 years building Java payment systems.",
                Tailor.summaryOnly("Asha Rao - Backend Engineer\nSummary: Backend engineer with 4 years building Java payment systems.", "Asha Rao"));
        // number words count as numbers
        assertFalse(Tailor.FactCheck.problems("Mentored five engineers.", "Mentored 2 engineers", Set.of()).isEmpty());
        assertTrue(Tailor.FactCheck.problems("Mentored two engineers.", "Mentored 2 engineers", Set.of()).isEmpty());

        // a model that loses bullets is rejected too
        Tailor.TailoredResume lossy = Tailor.resume(FACTS, JOB, p -> p.contains("BULLETS:") ? "Only one line." : null);
        assertTrue(String.join(" ", lossy.notes()).contains("one line per bullet"));
    }

    @Test
    void coverLetterAiWarningsAndTemplate() {
        Tailor.CoverLetter t = Tailor.coverLetter(FACTS, JOB, null);
        assertEquals("TEMPLATE", t.source());
        assertTrue(t.text().startsWith("Dear Hiring Team at Groww,"));
        assertTrue(t.text().contains("Backend Engineer") && t.text().contains("Acme Pay") && t.text().endsWith("asha@example.com · +91 90000 00000"));
        assertEquals(List.of(), Tailor.FactCheck.problems(t.text(), FACTS.all() + " Groww Backend Engineer", FACTS.allSkills()),
                "the template only uses facts");

        List<String> prompts = new ArrayList<>();
        Tailor.CoverLetter l = Tailor.coverLetter(FACTS, JOB, p -> { prompts.add(p); return "Dear Hiring Team at Groww,\nI led 12 engineers on AWS.\nAsha Rao"; });
        assertEquals(2, prompts.size(), "one retry with the problems spelled out");
        assertTrue(prompts.get(1).contains("AWS"));
        assertEquals("AI", l.source());
        assertEquals(2, l.warnings().size(), l.warnings().toString());
    }

    @Test
    void rendersPdfDocxTxt() throws Exception {
        Tailor.TailoredResume r = Tailor.resume(FACTS, JOB, null);
        List<Render.Line> lines = Render.resume(r);

        byte[] pdf = Render.pdf(lines);
        try (var doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Asha Rao") && text.contains("EXPERIENCE") && text.contains("2M transactions"), text);
        }
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(Render.docx(Render.letter("R&D <team> at Groww"))))) {
            Set<String> names = new HashSet<>();
            String xml = null;
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                names.add(e.getName());
                if (e.getName().equals("word/document.xml")) xml = new String(z.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertTrue(names.containsAll(List.of("[Content_Types].xml", "_rels/.rels", "word/document.xml")));
            assertTrue(xml.contains("R&amp;D &lt;team&gt; at Groww"));
        }
        assertTrue(new String(Render.txt(lines), StandardCharsets.UTF_8).contains("- Designed a ledger service"));
        // the standard PDF fonts can't draw ₹ or Telugu: mapped or replaced, never a crash
        assertNotNull(Render.pdf(Render.letter("Expected ₹24,00,000 · తెలుగు")));
    }

    // ------------------------------------------------------------ endpoints

    @Autowired WebApplicationContext ctx;
    MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build(); }

    @Test
    void endpoints() throws Exception {
        String reg = mvc().perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"t" + UUID.randomUUID() + "@example.com\",\"password\":\"secret123\",\"fullName\":\"Asha Rao\"}"))
                .andReturn().getResponse().getContentAsString();
        String auth = "Bearer " + reg.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        String job = mvc().perform(post("/api/jobs/manual").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Backend Engineer\",\"company\":\"Groww\",\"description\":\"Java, Spring Boot and Kafka.\"}"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc().perform(post("/api/jobs/" + job + "/documents/resume").header("Authorization", auth))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_EXPERIENCE"));
        mvc().perform(put("/api/profile").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""
                {"willingToRelocate":false,"yearsExperience":4,"skills":["React","Java","Spring Boot"],"desiredRoles":[],"desiredLocations":[],
                 "experiences":[{"company":"Acme Pay","title":"Software Engineer","startDate":"2022-01-01","endDate":null,
                 "description":"Built React dashboards.\\nDesigned a Java ledger handling 2M transactions a day."}],"educations":[]}""")).andExpect(status().isOk());

        String doc = mvc().perform(post("/api/jobs/" + job + "/documents/resume").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("RESUME"))
                .andExpect(jsonPath("$.resume.skills.tailored").value("Java, Spring Boot, React"))
                .andReturn().getResponse().getContentAsString();
        String docId = doc.replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1");

        mvc().perform(put("/api/documents/" + docId + "/resume").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":{\"useTailored\":false},\"skills\":{\"useTailored\":false},\"experiences\":[{\"useTailored\":true,\"text\":\"Designed a Java ledger.\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resume.skills.useTailored").value(false))
                .andExpect(jsonPath("$.resume.experiences[0].bullets.tailored").value("Designed a Java ledger."));

        mvc().perform(get("/api/documents/" + docId + "/file?format=docx").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("Asha Rao - Resume - Groww.docx")));
        mvc().perform(get("/api/documents/" + docId + "/file?format=pdf").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf"));
        mvc().perform(get("/api/documents/" + docId + "/file?format=exe").header("Authorization", auth)).andExpect(status().isBadRequest());

        String letter = mvc().perform(post("/api/jobs/" + job + "/documents/letter").header("Authorization", auth))
                .andExpect(jsonPath("$.letter.source").value("TEMPLATE"))
                .andReturn().getResponse().getContentAsString().replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1");
        mvc().perform(put("/api/documents/" + letter + "/letter").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Dear team, my own words.\"}"))
                .andExpect(jsonPath("$.letter.text").value("Dear team, my own words.")).andExpect(jsonPath("$.letter.source").value("USER"));
        mvc().perform(get("/api/jobs/" + job + "/documents").header("Authorization", auth))
                .andExpect(jsonPath("$.resume.id").value(docId)).andExpect(jsonPath("$.letter.id").value(letter));

        String other = mvc().perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"o" + UUID.randomUUID() + "@example.com\",\"password\":\"secret123\",\"fullName\":\"O\"}"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        mvc().perform(get("/api/documents/" + docId + "/file").header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
        mvc().perform(get("/api/jobs/" + job + "/documents").header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
    }
}
