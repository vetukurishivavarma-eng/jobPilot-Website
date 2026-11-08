package com.jobpilot.job;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MatchScorerTest {
    static final MatchScorer.Candidate ASHA = new MatchScorer.Candidate(Set.of("Java", "Spring Boot", "PostgreSQL", "Kafka"),
            new BigDecimal("4"), List.of("Backend Engineer"), List.of("Bengaluru"), false, 2_400_000L, "INR");

    static MatchScorer.JobFacts job(String title, List<String> skills, Integer years, String location, String workplace) {
        return new MatchScorer.JobFacts(title, skills, years, null, location, workplace, null, null);
    }

    @Test
    void strongMatchScoresHighWithReasons() {
        MatchScorer.Match m = MatchScorer.score(ASHA, job("Senior Backend Engineer", List.of("Java", "Spring Boot", "Kafka", "AWS"), 3, "Bangalore, India", null));
        assertTrue(m.score() >= 95, "score " + m.score());
        assertTrue(m.good().stream().anyMatch(g -> g.contains("Java")));
        assertTrue(m.gaps().stream().anyMatch(g -> g.contains("AWS")), "missing skill is named: " + m.gaps());
        assertTrue(m.good().contains("In a location you want"), "Bangalore = Bengaluru");
    }

    @Test
    void weakMatchScoresLowAndSaysWhy() {
        MatchScorer.Match m = MatchScorer.score(ASHA, job("Data Scientist", List.of("Python", "Machine Learning", "SQL"), 8, "Mountain View, California", null));
        assertTrue(m.score() <= 25, "score " + m.score());
        assertTrue(m.gaps().contains("Needs 8+ years; you have 4"));
        assertTrue(m.gaps().contains("Not one of your desired roles"));
        assertTrue(m.gaps().contains("Outside your locations"));
    }

    @Test
    void unknownPartsAreLeftOutNotPenalised() {
        // only skills are known: a full skills match is 100, not 40
        MatchScorer.Candidate skillsOnly = new MatchScorer.Candidate(Set.of("Java"), null, List.of(), List.of(), false, null, null);
        assertEquals(100, MatchScorer.score(skillsOnly, job("Anything", List.of("Java"), null, null, null)).score());
        assertNull(MatchScorer.score(skillsOnly, job("Anything", List.of(), null, null, null)).score(), "nothing to compare");
    }

    @Test
    void remoteRelocationSalaryAndSeniority() {
        assertTrue(MatchScorer.score(ASHA, job("Backend Engineer", List.of("Java"), 2, "Anywhere", "REMOTE")).good().contains("Remote"));
        MatchScorer.Candidate mover = new MatchScorer.Candidate(Set.of("Java"), new BigDecimal(4), List.of(), List.of("Pune"), true, null, null);
        MatchScorer.Match m = MatchScorer.score(mover, job("X", List.of("Java"), null, "Hyderabad", null));
        assertEquals((int) Math.round(100 * (40 + 7.5) / 55.0), m.score());
        // seniority implies years when the posting doesn't state them
        MatchScorer.Match lead = MatchScorer.score(ASHA, new MatchScorer.JobFacts("Staff Engineer", List.of(), null, "LEAD", null, null, null, null));
        assertTrue(lead.gaps().contains("Needs 8+ years; you have 4"));
        // salary only counts when stated in the same currency
        MatchScorer.Match low = MatchScorer.score(ASHA, new MatchScorer.JobFacts("Backend Engineer", List.of(), null, null, null, null, 1_200_000L, "INR"));
        assertTrue(low.gaps().contains("Top of the stated range is below your expectation"));
        assertTrue(MatchScorer.score(ASHA, new MatchScorer.JobFacts("Backend Engineer", List.of(), null, null, null, null, 100_000L, "USD")).gaps().isEmpty());
    }

    @Test
    void abroadAndBigExperienceGapsAreCapped() {
        // real case: Thane user open to relocating got 86 for a Mountain View job
        MatchScorer.Candidate thane = new MatchScorer.Candidate(Set.of("Java", "Spring Boot"), new BigDecimal(4),
                List.of("Software Engineer"), List.of("Thane"), true, null, null);
        MatchScorer.Match us = MatchScorer.score(thane, job("Software Engineer", List.of("Java", "Spring Boot"), 4, "Mountain View, California", null));
        assertTrue(us.score() <= 40 && us.gaps().getFirst().contains("another country"), us.toString());
        assertTrue(MatchScorer.score(thane, job("Software Engineer", List.of("Java"), 4, "Bangalore, Karnataka", null)).score() > 80, "relocating within India is fine");
        assertTrue(MatchScorer.score(thane, job("Software Engineer", List.of("Java"), 4, "Mumbai, India", null)).good().contains("In a location you want"), "Thane = Mumbai");
        assertTrue(MatchScorer.score(thane, job("Staff Software Engineer", List.of("Java"), 8, "Bengaluru, India", null)).score() <= 50, "4 years vs 8+");
        assertTrue(MatchScorer.score(thane, job("Software Engineer", List.of("Java"), 4, "Anywhere", "REMOTE")).score() > 80, "remote isn't abroad");
        assertTrue(MatchScorer.score(thane, job("Software Engineer", List.of("Java"), 4, "Remote - India", "REMOTE")).score() > 80);
        assertTrue(MatchScorer.score(thane, job("Software Engineer", List.of("Java"), 4, "Remote - US", "REMOTE")).score() <= 40, "remote in the US needs US work rights");
        assertTrue(MatchScorer.score(thane, job("Software Development Engineer Intern", List.of("Java"), 0, "Bangalore", null)).score() <= 50, "4 years vs an intern post");
    }

    @Test
    void cityAliases() {
        assertTrue(MatchScorer.sameCity("gurgaon, haryana", "Gurugram"));
        assertTrue(MatchScorer.sameCity("bengaluru-vtp, india", "Bangalore"));
        assertFalse(MatchScorer.sameCity("chennai", "Bengaluru"));
    }

    @org.junit.jupiter.api.Test
    void onlyPreferredLocations() {
        MatchScorer.Candidate thane = new MatchScorer.Candidate(Set.of(), null, List.of(), List.of("Thane, India", "Hyderabad"), true, null, null);
        assertTrue(MatchScorer.inDesiredLocation(thane, "Mumbai, Maharashtra", null), "Thane = Mumbai area");
        assertTrue(MatchScorer.inDesiredLocation(thane, "Secunderabad", "ONSITE"));
        assertTrue(MatchScorer.inDesiredLocation(thane, "Remote", "REMOTE"));
        assertTrue(MatchScorer.inDesiredLocation(thane, "Remote - India", null));
        assertFalse(MatchScorer.inDesiredLocation(thane, "Remote - US", "REMOTE"), "remote abroad");
        assertFalse(MatchScorer.inDesiredLocation(thane, "Bengaluru, India", "ONSITE"), "relocation doesn't widen the list");
        assertFalse(MatchScorer.inDesiredLocation(thane, null, null), "unknown and not remote");
        MatchScorer.Candidate none = new MatchScorer.Candidate(Set.of(), null, List.of(), List.of(), false, null, null);
        assertTrue(MatchScorer.inDesiredLocation(none, "Berlin", null), "no preference = everything");
    }

    @org.junit.jupiter.api.Test
    void unrelatedRoleSinks() {
        MatchScorer.Candidate dev = new MatchScorer.Candidate(Set.of("Java"), new BigDecimal(3), List.of("Java Developer", "Software Engineer"), List.of("Mumbai"), false, null, null);
        var collections = MatchScorer.score(dev, new MatchScorer.JobFacts("Area Collections Manager", List.of(), null, "MANAGER", "Mumbai", "ONSITE", null, null));
        assertTrue(collections.score() <= 40, collections.toString());
        var sde = MatchScorer.score(dev, new MatchScorer.JobFacts("Software Engineer II", List.of("Java"), 2, null, "Mumbai", "ONSITE", null, null));
        assertTrue(sde.score() > 80, sde.toString());
    }

    @org.junit.jupiter.api.Test
    void onlyMyRoleAndSkills() {
        MatchScorer.Candidate dev = new MatchScorer.Candidate(Set.of("Java", "Spring Boot"), new BigDecimal(3), List.of("Java Developer", "Software Engineer"), List.of("Hyderabad"), false, null, null);
        assertTrue(MatchScorer.fitsRoleAndSkills(dev, "Software Engineer II", List.of("Java", "Kafka")));
        assertTrue(MatchScorer.fitsRoleAndSkills(dev, "Senior Java Developer", List.of()), "no skills listed: title decides");
        assertFalse(MatchScorer.fitsRoleAndSkills(dev, "Area Collections Manager", List.of()), "other role");
        assertFalse(MatchScorer.fitsRoleAndSkills(dev, "Software Engineer", List.of("Python", "Go")), "none of my skills");
    }
}
