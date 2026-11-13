package com.jobpilot.job;

import com.jobpilot.profile.Profile.Experience;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExperienceRolesTest {
    static Experience job(String title, String bullets, int yearsAgoStart, Integer yearsAgoEnd) {
        LocalDate now = LocalDate.now();
        return new Experience("Acme", title, null, now.minusYears(yearsAgoStart), yearsAgoEnd == null ? null : now.minusYears(yearsAgoEnd), bullets);
    }

    @Test
    void rolesComeFromTheWorkDone() {
        List<String> java = ExperienceRoles.infer(List.of(
                job("System Engineer", "• Built REST APIs in Java and Spring Boot with Hibernate\n• Made a monthly report in Excel", 3, null)));
        assertTrue(java.contains("Backend Engineer") && java.contains("Java Developer"), java.toString());
        assertFalse(java.contains("Data Analyst"), "one Excel report is not a field: " + java);

        List<String> qa = ExperienceRoles.infer(List.of(
                job("Associate", "• Automated regression suites with Selenium and TestNG\n• Wrote Cypress tests for the web app\n• Small Python scripts", 2, null)));
        assertTrue(qa.contains("QA Engineer") && qa.contains("SDET"), qa.toString());

        List<String> data = ExperienceRoles.infer(List.of(
                job("Analyst", "• Built ETL pipelines on Spark and Airflow into Snowflake", 2, null),
                job("Intern", "• Dashboards in Power BI and Tableau", 4, 3)));
        assertEquals("Data Engineer", data.getFirst(), "recent, longer work leads: " + data);

        assertEquals(List.of(), ExperienceRoles.infer(List.of()));
        // the user's picks stay first; an inferred duplicate isn't added twice
        assertEquals(List.of("Java Developer", "Backend Engineer"),
                ExperienceRoles.withExperience(List.of("Java Developer"), List.of(job("Dev", "Java, Spring Boot", 2, null))));
    }

    @Test
    void aQaPersonWithPythonNowSeesQaJobs() {
        Experience e = job("Associate", "• Automated regression suites with Selenium and TestNG", 3, null);
        MatchScorer.Candidate c = new MatchScorer.Candidate(Set.of("python", "selenium", "testng"), new BigDecimal("3"),
                ExperienceRoles.withExperience(List.of(), List.of(e)), List.of(), false, null, null);
        // before: Python on the resume meant "developer titles only", so every QA posting was hidden
        assertTrue(MatchScorer.fitsResume(c, "QA Engineer II", List.of("selenium"), 2, null));
        assertTrue(MatchScorer.fitsResume(c, "SDET", List.of(), null, null));
        assertFalse(MatchScorer.fitsResume(c, "Sales Manager", List.of(), null, null));
    }
}
