package com.jobpilot.profile;

import com.jobpilot.profile.ProfileController.ProfileDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResumeImportTest {

    static final String RESUME = """
            Sai Kumar
            Backend Developer | Bengaluru, India
            sai.kumar@example.com | +91 98480 12345 | linkedin.com/in/saikumar | https://github.com/saik
            SUMMARY
            Backend developer building payment APIs in Java and Spring Boot.
            EXPERIENCE
            Tata Consultancy Services — Software Engineer, Bengaluru (Jan 2022 – Present)
            • Built REST APIs in Spring Boot serving 3 million requests a day.
            • Migrated reporting jobs to PostgreSQL, cutting runtime by 40%.
            Infosys — Systems Engineer, Mysuru (Jul 2020 – Dec 2021)
            • Maintained Java batch jobs for a banking client.
            EDUCATION
            B.Tech, Computer Science — JNTU Hyderabad (2016–2020), 8.1 CGPA
            """;

    static final String HONEST_AI = """
            {"currentTitle":"Software Engineer","location":"Bengaluru, India",
             "experiences":[
              {"company":"Tata Consultancy Services","title":"Software Engineer","location":"Bengaluru","startDate":"2022-01","endDate":null,
               "summary":"Built Spring Boot REST APIs serving 3 million requests a day and moved reporting jobs to PostgreSQL."},
              {"company":"Infosys","title":"Systems Engineer","location":"Mysuru","startDate":"Jul 2020","endDate":"Dec 2021",
               "summary":"Maintained Java batch jobs for a banking client."}],
             "educations":[{"institution":"JNTU Hyderabad","degree":"B.Tech","field":"Computer Science","startYear":2016,"endYear":2020,"grade":"8.1 CGPA"}]}
            """;

    @Test
    void fillsEverythingFromAnHonestExtraction() {
        ResumeImport.Result r = ResumeImport.run(RESUME, ProfileDto.empty(), p -> HONEST_AI);
        ProfileDto p = r.profile();
        assertEquals("+91 98480 12345", p.phone());
        assertEquals("https://linkedin.com/in/saikumar", p.linkedinUrl());
        assertEquals("https://github.com/saik", p.githubUrl());
        assertEquals("Software Engineer", p.currentTitle());
        assertEquals("Bengaluru, India", p.location());
        assertTrue(p.skills().containsAll(List.of("Java", "Spring Boot", "PostgreSQL")), p.skills().toString());
        assertEquals(2, p.experiences().size());
        assertEquals(LocalDate.of(2022, 1, 1), p.experiences().getFirst().startDate());
        assertNull(p.experiences().getFirst().endDate(), "Present");
        assertEquals(LocalDate.of(2021, 12, 31), p.experiences().get(1).endDate());
        assertEquals("""
                Built Spring Boot REST APIs serving 3 million requests a day and moved reporting jobs to PostgreSQL.
                • Built REST APIs in Spring Boot serving 3 million requests a day.
                • Migrated reporting jobs to PostgreSQL, cutting runtime by 40%.""", p.experiences().getFirst().description(),
                "summary, then the job's own bullets cut from the text (not the next job's)");
        assertEquals("Backend developer building payment APIs in Java and Spring Boot.", p.summary(), "the resume's own summary section");
        assertTrue(p.headline().startsWith("Software Engineer · "), p.headline());
        assertEquals("JNTU Hyderabad", p.educations().getFirst().institution());
        assertEquals(2020, p.educations().getFirst().endYear());
        // computed from the dates, not guessed: Jul 2020 to now
        assertTrue(p.yearsExperience().compareTo(new BigDecimal("5")) >= 0, p.yearsExperience().toString());
        assertEquals(List.of("Software Engineer", "Backend Engineer", "Java Developer"), p.desiredRoles(), "searchable titles from the title and stack");
        assertEquals(List.of("System Engineer", "Software Engineer"), ResumeImport.suggestRoles("System Engineer (Digital Cadre)", List.of("SQL")));
        assertEquals(List.of("Bengaluru"), p.desiredLocations());
        assertTrue(r.notes().isEmpty(), r.notes().toString());
    }

    @Test
    void namesWrappedAcrossPdfLinesStillMatch() {
        String lower = "worked at tata consultancy\nservices in   bengaluru".toLowerCase().replaceAll("\\s+", " ");
        assertEquals("Tata Consultancy Services", ResumeImport.inText("Tata Consultancy Services", lower));
        assertNull(ResumeImport.inText("Infosys", lower));
    }

    @Test
    void dateRangesWrappedAcrossPdfLinesStillCount() {
        // a two-column PDF put "Nov 2020 - Nov" and "2025" on separate lines: the whole 5-year job was dropped
        var r = ResumeImport.ranges("EDUCATION\nNov 2020 - Nov\n2025\nWORK EXPERIENCE\nDec 2025 -\nPresent\n");
        assertEquals(2, r.size());
        assertEquals(java.time.LocalDate.of(2020, 11, 1), r.get(0)[0]);
        assertEquals(java.time.LocalDate.of(2025, 11, 30), r.get(0)[1]);
        assertNull(r.get(1)[1]);
    }

    @Test
    void dropsWhatIsNotInTheResume() {
        String liar = """
                {"currentTitle":"Engineering Manager","location":"London",
                 "experiences":[
                  {"company":"Tata Consultancy Services","title":"Software Engineer","startDate":"2022-01","endDate":null,
                   "summary":"Led a team of 12 engineers building Kafka pipelines."},
                  {"company":"Google","title":"Staff Engineer","startDate":"2019-01","endDate":"2020-01","summary":"Built search."}],
                 "educations":[{"institution":"Stanford","degree":"MS","field":"CS"}]}
                """;
        ResumeImport.Result r = ResumeImport.run(RESUME, ProfileDto.empty(), p -> liar);
        ProfileDto p = r.profile();
        assertEquals(1, p.experiences().size(), "the invented Google job is dropped");
        assertTrue(p.experiences().getFirst().description().startsWith("• Built REST APIs"), "invented summary (12, Kafka) dropped, real bullets kept");
        assertNull(p.location(), "London isn't in the resume");
        assertNotEquals("Engineering Manager", p.currentTitle());
        assertTrue(p.educations().isEmpty());
        String notes = String.join(" ", r.notes());
        assertTrue(notes.contains("Google") && notes.contains("1 job summary"), notes);
    }

    @Test
    void jobSummaryComesFromASecondSmallCall() {
        String noSummaries = HONEST_AI.replaceAll(",\\s*\"summary\":\"[^\"]*\"", "");
        String good = "{\"summary\":\"Built Spring Boot REST APIs serving 3 million requests a day.\"}";
        ProfileDto p = ResumeImport.run(RESUME, ProfileDto.empty(), prompt -> prompt.startsWith("Summarise")
                ? (prompt.contains("Infosys") ? "{\"summary\":\"never asked\"}" : good) : noSummaries).profile();
        assertTrue(p.experiences().getFirst().description().startsWith("Built Spring Boot REST APIs serving 3 million requests a day.\n• "));
        assertEquals("• Maintained Java batch jobs for a banking client.", p.experiences().get(1).description(), "one bullet is its own summary");
        ProfileDto liar = ResumeImport.run(RESUME, ProfileDto.empty(), prompt -> prompt.startsWith("Summarise")
                ? "{\"summary\":\"Ran 9 Kubernetes clusters.\"}" : noSummaries).profile();
        assertTrue(liar.experiences().getFirst().description().startsWith("• "), "the invented TCS summary is dropped");
    }

    @Test
    void wordSymbolBulletsAndAWrittenSummaryWhenTheResumeHasNone() {
        String resume = """
                Sai Vihari
                System Engineer
                WORK EXPERIENCE
                System Engineer, Tata Consultancy Services
                 Designed Kafka messaging for 5+ microservices, improving real-time data flow
                and reducing response times by 40%.
                 Built Spring Cache + Redis layers, reducing database load by 60%.
                01/2023 – Present
                """;
        String ai = """
                {"currentTitle":"System Engineer","experiences":[{"company":"Tata Consultancy Services","title":"System Engineer","startDate":"2023-01","endDate":null}]}""";
        ProfileDto p = ResumeImport.run(resume, ProfileDto.empty(), prompt ->
                prompt.startsWith("Write a professional summary") ? "{\"summary\":\"System Engineer at Tata Consultancy Services building Kafka messaging and Redis caching, cutting response times by 40%.\"}"
                : prompt.startsWith("Summarise") ? null : ai).profile();
        assertEquals("""
                • Designed Kafka messaging for 5+ microservices, improving real-time data flow and reducing response times by 40%.
                • Built Spring Cache + Redis layers, reducing database load by 60%.""", p.experiences().getFirst().description());
        assertTrue(p.summary().startsWith("System Engineer at Tata"), String.valueOf(p.summary()));
        // an invented number in the written summary drops it
        assertNull(ResumeImport.run(resume, ProfileDto.empty(), prompt ->
                prompt.startsWith("Write a professional summary") ? "{\"summary\":\"Engineer with 9 years of Kafka.\"}" : prompt.startsWith("Summarise") ? null : ai).profile().summary());
    }

    @Test
    void twoColumnPdfDatesAndADateOfBirthTheModelMistookForAStart() {
        String resume = """
                Sai Vihari
                saivihari@example.com 7013760799 Thane, India 2000-11-27
                WORK EXPERIENCE
                System Engineer (Digital Cadre), Tata Consultancy Services
                 Designed Kafka messaging for 5+ microservices, improving real-time data flow
                and reducing response times by 40%.
                Web Designer Intern, Tata Consultancy Services
                 Developed responsive web pages using HTML, CSS, JavaScript, and Bootstrap,
                ensuring cross-browser compatibility.

                01/2023 – Present
                Thane, India

                03/2022 – 06/2022
                Remote

                EDUCATION
                07/2018 – 06/2022
                """;
        String ai = """
                {"currentTitle":"System Engineer (Digital Cadre), Tata Consultancy Services","experiences":[
                 {"company":"Tata Consultancy Services","title":"System Engineer (Digital Cadre)","startDate":"2000-11-27","endDate":"Present"},
                 {"company":"Tata Consultancy Services","title":"Web Designer Intern","startDate":"2022-03-01","endDate":"2022-06-30"}]}""";
        ResumeImport.Result r = ResumeImport.run(resume, ProfileDto.empty(), prompt -> prompt.startsWith("Read") ? ai : null);
        ProfileDto p = r.profile();
        assertEquals(LocalDate.of(2023, 1, 1), p.experiences().getFirst().startDate(), "not the date of birth");
        assertNull(p.experiences().getFirst().endDate());
        assertEquals(LocalDate.of(2022, 3, 1), p.experiences().get(1).startDate());
        assertEquals("• Developed responsive web pages using HTML, CSS, JavaScript, and Bootstrap, ensuring cross-browser compatibility.",
                p.experiences().get(1).description(), "the bullet before a blank line is kept");
        assertEquals("System Engineer (Digital Cadre)", p.currentTitle(), "company stripped from the title");
        assertTrue(p.yearsExperience().compareTo(new BigDecimal("6")) < 0, p.yearsExperience().toString());
        assertTrue(String.join(" ", r.notes()).contains("Check the dates for System Engineer"));
    }

    @Test
    void bulletsFromPdfTextWithWrappedLinesAndNoMarkers() {
        assertEquals(List.of("Built REST APIs in Spring Boot serving 3 million requests a day across five regions."),
                ResumeImport.bullets(List.of("Software Engineer", "Jan 2022 – Present", "• Built REST APIs in Spring Boot serving 3 million",
                        "requests a day across five regions.", "EDUCATION", "• B.Tech"), "Software Engineer"));
        assertEquals(List.of("Owned the billing service end to end for retail customers.", "Cut cloud spend by moving batch jobs to spot instances."),
                ResumeImport.bullets(List.of("Owned the billing service end to end", "for retail customers.",
                        "Cut cloud spend by moving batch jobs to spot instances."), "Engineer"));
        assertEquals(0, ResumeImport.lineOf(List.of("Tata Consultancy", "Services — Engineer"), "Tata Consultancy Services", 0), "name wrapped over two lines");
    }

    @Test
    void dateOfBirthDayFirst() {
        assertEquals(java.time.LocalDate.of(1999, 5, 12), ResumeImport.dateOfBirth("Date of Birth : 12/05/1999"));
        assertEquals(java.time.LocalDate.of(1999, 5, 12), ResumeImport.dateOfBirth("DOB - 12th May 1999"));
        assertNull(ResumeImport.dateOfBirth("Born to code"));
        assertNull(ResumeImport.dateOfBirth("Date of birth: 31/02/1999"), "not a real date");
    }

    @Test
    void resumeWinsButWhatOnlyTheUserKnowsIsKept() {
        ProfileDto mine = new ProfileDto("My headline", "+91 11111 11111", "Pune", "Lead Engineer", new BigDecimal("6"), 3_000_000L, "INR", 60,
                "Indian citizen", true, null, true, null, null, "https://me.dev", "My summary", List.of("Go"), List.of("Platform Engineer"), List.of("Pune"), List.of(), List.of(), null);
        ProfileDto p = ResumeImport.run(RESUME, mine, x -> HONEST_AI).profile();
        assertEquals("+91 98480 12345", p.phone(), "the resume's value wins: it's a draft the user reviews");
        assertEquals("Software Engineer", p.currentTitle());
        assertNotEquals(new BigDecimal("6"), p.yearsExperience(), "computed from the jobs");
        assertEquals("Backend developer building payment APIs in Java and Spring Boot.", p.summary());
        assertEquals("https://me.dev", p.portfolioUrl(), "not in the resume, kept");
        assertEquals(List.of("Platform Engineer"), p.desiredRoles());
        assertEquals(3_000_000L, p.expectedSalary());
        assertEquals("Indian citizen", p.workAuthorization());
        assertTrue(p.skills().containsAll(List.of("Go", "Java")), p.skills().toString());
        assertEquals(2, p.experiences().size());
        assertEquals(60, p.noticePeriodDays());
    }

    @Test
    void withoutAiStillFillsContactAndSkills() {
        ResumeImport.Result r = ResumeImport.run(RESUME, ProfileDto.empty(), p -> null);
        assertEquals("+91 98480 12345", r.profile().phone());
        assertFalse(r.profile().skills().isEmpty());
        assertTrue(r.profile().experiences().isEmpty());
        assertTrue(r.notes().getFirst().contains("Local AI isn't available"));
        assertTrue(ResumeImport.run(RESUME, ProfileDto.empty(), p -> "not json").notes().getFirst().contains("wasn't readable"));
    }

    @Test
    void datesAndYears() {
        assertEquals(LocalDate.of(2021, 3, 1), ResumeImport.date("03/2021", false));
        assertEquals(LocalDate.of(2021, 3, 1), ResumeImport.date("March 2021", false));
        assertEquals(LocalDate.of(2021, 12, 31), ResumeImport.date("2021", true));
        assertNull(ResumeImport.date("Present", true));
        assertNull(ResumeImport.date("2099-01", false), "future dates rejected");
        // overlapping jobs counted once: Jan 2020-Dec 2021 and Jun 2021-Dec 2022 = 3 years
        var a = new Profile.Experience("A", "x", null, LocalDate.of(2020, 1, 1), LocalDate.of(2021, 12, 31), null);
        var b = new Profile.Experience("B", "y", null, LocalDate.of(2021, 6, 1), LocalDate.of(2022, 12, 31), null);
        assertEquals(new BigDecimal("3"), ResumeImport.years(List.of(a, b)));
    }
}
