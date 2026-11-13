package com.jobpilot.job;

import com.jobpilot.profile.Profile.Experience;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Job-board titles that fit the work a person has actually done: each family is triggered by its skills appearing in a
 * job's title + bullets, weighted by how long they did that job (recent work counts double). Matching adds these to
 * the roles the user picked, so they see every opening their experience fits, not only the titles they typed.
 */
final class ExperienceRoles {
    private ExperienceRoles() {}

    private record Family(List<String> titles, Set<String> skills) {}
    private static Family family(List<String> titles, String... skills) { return new Family(titles, Set.of(skills)); }

    // skill names are JdParser's canonical names, lower case
    private static final List<Family> FAMILIES = List.of(
            family(List.of("Backend Engineer", "Java Developer"), "java", "spring boot", "spring", "hibernate"),
            family(List.of("Backend Engineer", "Node.js Developer"), "node.js", "express"),
            family(List.of("Backend Engineer", "Python Developer"), "django", "flask", "fastapi"),
            family(List.of("Backend Engineer", "Go Developer"), "go"),
            family(List.of("Backend Engineer", ".NET Developer"), "c#", ".net", "asp.net"),
            family(List.of("PHP Developer"), "php", "laravel"),
            family(List.of("Frontend Engineer", "React Developer"), "react", "angular", "vue.js", "next.js", "redux"),
            family(List.of("Android Developer", "Mobile Engineer"), "android", "kotlin"),
            family(List.of("iOS Developer", "Mobile Engineer"), "ios", "swift"),
            family(List.of("Flutter Developer", "Mobile Engineer"), "flutter", "react native"),
            family(List.of("Data Engineer"), "spark", "airflow", "hadoop", "etl", "snowflake", "dbt", "kafka", "databricks"),
            family(List.of("Data Analyst", "Business Analyst"), "power bi", "tableau", "excel", "data analysis", "looker"),
            family(List.of("Data Scientist", "Machine Learning Engineer"), "machine learning", "pytorch", "tensorflow", "deep learning", "nlp", "scikit-learn"),
            family(List.of("DevOps Engineer", "Site Reliability Engineer", "Cloud Engineer"), "kubernetes", "terraform", "jenkins", "ci/cd", "ansible", "docker"),
            family(List.of("QA Engineer", "SDET", "Test Engineer"), "selenium", "test automation", "cypress", "playwright", "testng", "manual testing", "appium"),
            family(List.of("Salesforce Developer"), "salesforce", "apex"),
            family(List.of("SAP Consultant"), "sap"),
            family(List.of("UI/UX Designer", "Product Designer"), "figma", "adobe xd"));

    static final int MAX_FAMILIES = 3;

    static List<String> infer(List<Experience> jobs) {
        if (jobs == null || jobs.isEmpty()) return List.of();
        LocalDate now = LocalDate.now();
        Map<Family, Double> score = new LinkedHashMap<>();
        for (Experience e : jobs) {
            LocalDate end = e.endDate() == null ? now : e.endDate();
            double years = Math.max(0.25, ChronoUnit.MONTHS.between(e.startDate(), end) / 12.0);
            double weight = years * (ChronoUnit.YEARS.between(end, now) < 2 ? 2 : 1);
            Set<String> used = new HashSet<>();
            JdParser.parse(e.title(), e.description()).skills().forEach(s -> used.add(s.toLowerCase(Locale.ROOT)));
            for (Family f : FAMILIES) {
                long hits = f.skills().stream().filter(used::contains).count();
                if (hits > 0) score.merge(f, hits * weight, Double::sum);
            }
        }
        // ponytail: fixed families and a fixed bar; an LLM read of the bullets if users' work falls outside them
        // a side skill (a Java developer's one Excel report) must not add a field: keep families within 40% of the main one
        double top = score.values().stream().mapToDouble(d -> d).max().orElse(0);
        Set<String> out = new LinkedHashSet<>();
        score.entrySet().stream().filter(x -> x.getValue() >= 0.5 && x.getValue() >= 0.4 * top)
                .sorted(Map.Entry.<Family, Double>comparingByValue().reversed())
                .limit(MAX_FAMILIES)
                .forEach(x -> out.addAll(x.getKey().titles()));
        return List.copyOf(out);
    }

    /** The user's own roles first, then the ones their experience adds. */
    static List<String> withExperience(List<String> picked, List<Experience> jobs) {
        Set<String> out = new LinkedHashSet<>(picked == null ? List.of() : picked);
        for (String r : infer(jobs)) if (out.stream().noneMatch(p -> RoleMatch.tokens(p).equals(RoleMatch.tokens(r)))) out.add(r);
        return List.copyOf(out);
    }
}
