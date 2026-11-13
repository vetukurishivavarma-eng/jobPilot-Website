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
    void remoteScopeFromDescription() {
        MatchScorer.Candidate hyd = new MatchScorer.Candidate(Set.of(), null, List.of(), List.of("Hyderabad"), false, null, null);
        // a US company hiring remote from anywhere: shown
        String open = MatchScorer.scopedLocation("Remote - US", "REMOTE", "We're a fully remote team and hire candidates from anywhere in the world.");
        assertEquals("Remote - US (open to India)", open);
        assertTrue(MatchScorer.inDesiredLocation(hyd, open, "REMOTE"));
        assertTrue(MatchScorer.inMarket(open, "REMOTE"), "kept when jobs are collected");
        // plain "Remote" but US-only: hidden
        String usOnly = MatchScorer.scopedLocation("Remote", "REMOTE", "This role is remote. You must be located in the United States.");
        assertEquals("Remote - United States", usOnly);
        assertFalse(MatchScorer.inDesiredLocation(hyd, usOnly, "REMOTE"));
        assertEquals("Remote - United States", MatchScorer.scopedLocation(null, "REMOTE", "Must be authorized to work in the US."));
        assertEquals("Remote - Canada", MatchScorer.scopedLocation("Remote", "REMOTE", "Canada residents only."));
        // plain remote, nothing said: shown as before
        assertEquals("Remote", MatchScorer.scopedLocation("Remote", "REMOTE", "Build APIs in Java."));
        assertTrue(MatchScorer.inDesiredLocation(hyd, "Remote", "REMOTE"));
        // onsite and India postings are never touched
        assertEquals("Bengaluru", MatchScorer.scopedLocation("Bengaluru", "ONSITE", "You must be located in the United States."));
        assertEquals("Remote - India", MatchScorer.scopedLocation("Remote - India", "REMOTE", "US citizens only"));
    }

    @org.junit.jupiter.api.Test
    void strongMatchElsewhereAndCityToAdd() {
        assertTrue(MatchScorer.strongElsewhere("Bengaluru, Karnataka", "ONSITE", 80));
        assertFalse(MatchScorer.strongElsewhere("Bengaluru, Karnataka", "ONSITE", 74));
        assertFalse(MatchScorer.strongElsewhere("Mountain View, CA", "ONSITE", 95), "never abroad");
        assertFalse(MatchScorer.strongElsewhere("Bengaluru", "ONSITE", null));

        List<String> mine = List.of("Hyderabad");
        assertEquals("Bengaluru", MatchScorer.newCity(mine, "Bengaluru, Karnataka, India", "ONSITE"));
        assertEquals("Pune", MatchScorer.newCity(mine, "Pune; Mumbai", null));
        assertNull(MatchScorer.newCity(mine, "Bengaluru; Hyderabad", null), "also offered in one of theirs");
        assertNull(MatchScorer.newCity(mine, "Secunderabad", null), "same city as Hyderabad");
        assertNull(MatchScorer.newCity(mine, "Remote - India", "REMOTE"));
        assertNull(MatchScorer.newCity(mine, "London, UK", "ONSITE"), "never suggest abroad");
    }

    @org.junit.jupiter.api.Test
    void onlyPreferredLocations() {
        MatchScorer.Candidate thane = new MatchScorer.Candidate(Set.of(), null, List.of(), List.of("Thane, India", "Hyderabad"), true, null, null);
        assertTrue(MatchScorer.inDesiredLocation(thane, "Mumbai, Maharashtra", null), "Thane = Mumbai area");
        assertTrue(MatchScorer.inDesiredLocation(thane, "Secunderabad", "ONSITE"));
        assertTrue(MatchScorer.inDesiredLocation(thane, "Remote", "REMOTE"));
        assertTrue(MatchScorer.inDesiredLocation(thane, "Remote - India", null));
        assertFalse(MatchScorer.inDesiredLocation(thane, "Remote - US", "REMOTE"), "remote abroad");
        assertFalse(MatchScorer.inDesiredLocation(thane, "Bengaluru, India", "ONSITE"), "not a preferred city, even if open to relocating");
        assertTrue(MatchScorer.inDesiredLocation(thane, "Bengaluru; Hyderabad, India", "ONSITE"), "a job offered in a preferred city too");
        assertFalse(MatchScorer.inDesiredLocation(thane, "Mountain View, CA", "ONSITE"), "relocating never means abroad");
        MatchScorer.Candidate stays = new MatchScorer.Candidate(Set.of(), null, List.of(), List.of("Mumbai"), false, null, null);
        assertFalse(MatchScorer.inDesiredLocation(stays, "Bengaluru, India", "ONSITE"), "not relocating: only their cities");
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
    void fitsResume() {
        // the live user's resume: Java/Spring backend developer, ~2.8 years
        MatchScorer.Candidate dev = new MatchScorer.Candidate(Set.of("Java", "Spring Boot", "Kafka", "Redis", "PostgreSQL", "Docker",
                "Jenkins", "SQL", "Hibernate", "Maven", "Git", "JavaScript"), new BigDecimal("2.8"), List.of("System Engineer"), List.of("Hyderabad"), false, null, null);
        assertTrue(MatchScorer.fitsResume(dev, "Software Engineer II", List.of("Java", "Kafka"), 2, "MID"));
        assertTrue(MatchScorer.fitsResume(dev, "Software Development Engineer - Java + Distributed Systems", List.of("Java"), 3, null));
        assertTrue(MatchScorer.fitsResume(dev, "Senior Software Engineer", List.of("Java", "Spring Boot"), 3, "SENIOR"), "JD says 3+: senior in name only");
        assertFalse(MatchScorer.fitsResume(dev, "Senior Software Engineer", List.of("Java"), null, "SENIOR"), "senior, no years stated = 5");
        assertFalse(MatchScorer.fitsResume(dev, "Python SDE-III", List.of("Python", "Java"), null, "SENIOR"), "other language + level");
        assertFalse(MatchScorer.fitsResume(dev, "Staff Software Development Engineer - Java/Go", List.of("Java", "Go"), 3, "LEAD"), "staff is a hard level");
        assertFalse(MatchScorer.fitsResume(dev, "Golang Engineer", List.of(), null, null));
        assertFalse(MatchScorer.fitsResume(dev, "Frontend Engineer", List.of("React", "TypeScript"), 2, null), "JavaScript from an internship isn't frontend");
        assertFalse(MatchScorer.fitsResume(dev, "Area Collections Manager", List.of(), null, null), "not a developer title");
        assertFalse(MatchScorer.fitsResume(dev, "Software Engineer Intern", List.of("Java"), null, "INTERN"), "below his level");
        assertFalse(MatchScorer.fitsResume(dev, "Backend Engineer", List.of("Python", "Go", "Kubernetes", "Terraform", "Java"), 2, null), "1 of 5 listed skills");
        assertFalse(MatchScorer.fitsResume(dev, "Software Engineer Manager (Database Observability)", List.of(), null, "MANAGER"));
        assertTrue(MatchScorer.fitsResume(dev, "Java Engineer", List.of("Java"), 3, null), "names his language");
        for (String other : List.of("Agent Developer Test III", "People Systems Engineer", "AI Agent Engineer", "Product Security Engineer II", "Senior IT Automation Engineer"))
            assertFalse(MatchScorer.fitsResume(dev, other, List.of("Java", "SQL"), 3, null), other);
        // someone without programming languages: their desired roles decide the field
        MatchScorer.Candidate accountant = new MatchScorer.Candidate(Set.of("Tally", "GST"), new BigDecimal(4), List.of("Accountant"), List.of(), false, null, null);
        assertTrue(MatchScorer.fitsResume(accountant, "Senior Accountant", List.of("GST"), 3, "SENIOR"));
        assertFalse(MatchScorer.fitsResume(accountant, "Software Engineer", List.of(), null, null));
    }
}
