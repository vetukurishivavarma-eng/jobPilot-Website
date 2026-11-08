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
            {"currentTitle":"Software Engineer","headline":"Backend Developer","location":"Bengaluru, India",
             "summary":"Backend developer building payment APIs in Java and Spring Boot.",
             "experiences":[
              {"company":"Tata Consultancy Services","title":"Software Engineer","location":"Bengaluru","startDate":"2022-01","endDate":null,
               "bullets":["Built REST APIs in Spring Boot serving 3 million requests a day.","Migrated reporting jobs to PostgreSQL, cutting runtime by 40%."]},
              {"company":"Infosys","title":"Systems Engineer","location":"Mysuru","startDate":"Jul 2020","endDate":"Dec 2021",
               "bullets":["Maintained Java batch jobs for a banking client."]}],
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
        assertEquals(2, p.experiences().getFirst().description().lines().count());
        assertEquals("JNTU Hyderabad", p.educations().getFirst().institution());
        assertEquals(2020, p.educations().getFirst().endYear());
        // computed from the dates, not guessed: Jul 2020 to now
        assertTrue(p.yearsExperience().compareTo(new BigDecimal("5")) >= 0, p.yearsExperience().toString());
        assertEquals(List.of("Software Engineer"), p.desiredRoles(), "suggested from the current title");
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
    void dropsWhatIsNotInTheResume() {
        String liar = """
                {"currentTitle":"Engineering Manager","location":"London",
                 "experiences":[
                  {"company":"Tata Consultancy Services","title":"Software Engineer","startDate":"2022-01","endDate":null,
                   "bullets":["Built REST APIs in Spring Boot serving 3 million requests a day.","Led a team of 12 engineers across 4 countries."]},
                  {"company":"Google","title":"Staff Engineer","startDate":"2019-01","endDate":"2020-01","bullets":["Built search."]}],
                 "educations":[{"institution":"Stanford","degree":"MS","field":"CS"}]}
                """;
        ResumeImport.Result r = ResumeImport.run(RESUME, ProfileDto.empty(), p -> liar);
        ProfileDto p = r.profile();
        assertEquals(1, p.experiences().size(), "the invented Google job is dropped");
        assertEquals("Built REST APIs in Spring Boot serving 3 million requests a day.", p.experiences().getFirst().description());
        assertNull(p.location(), "London isn't in the resume");
        assertNotEquals("Engineering Manager", p.currentTitle());
        assertTrue(p.educations().isEmpty());
        String notes = String.join(" ", r.notes());
        assertTrue(notes.contains("Google") && notes.contains("1 achievement line"), notes);
    }

    @Test
    void keepsWhatTheUserAlreadyFilled() {
        ProfileDto mine = new ProfileDto("My headline", "+91 11111 11111", "Pune", "Lead Engineer", new BigDecimal("6"), 3_000_000L, "INR", 60,
                null, true, null, null, null, "My summary", List.of("Go"), List.of("Platform Engineer"), List.of("Pune"), List.of(), List.of(), null);
        ProfileDto p = ResumeImport.run(RESUME, mine, x -> HONEST_AI).profile();
        assertEquals("+91 11111 11111", p.phone());
        assertEquals("Lead Engineer", p.currentTitle());
        assertEquals(new BigDecimal("6"), p.yearsExperience());
        assertEquals(List.of("Platform Engineer"), p.desiredRoles());
        assertEquals("Go", p.skills().getFirst(), "existing skills first, resume skills added");
        assertTrue(p.skills().contains("Java"));
        assertEquals(2, p.experiences().size(), "empty lists are filled");
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
