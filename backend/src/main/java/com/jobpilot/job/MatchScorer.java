package com.jobpilot.job;

import java.math.BigDecimal;
import java.util.*;

/**
 * How well a job fits the candidate, 0-100, with the reasons. Deterministic and explainable: each part is scored only
 * when both sides are known, and the weights of the unknown parts are left out (a job without a salary isn't penalised).
 */
public final class MatchScorer {
    private MatchScorer() {}

    /** What we know about the candidate. Skills are the profile's plus those found in the resume. */
    public record Candidate(Set<String> skills, BigDecimal years, List<String> desiredRoles, List<String> desiredLocations,
                            boolean willingToRelocate, Long expectedSalary, String salaryCurrency) {}

    public record JobFacts(String title, List<String> skills, Integer minYears, String seniority, String location, String workplace,
                           Long salaryMax, String salaryCurrency) {}

    /** {@code score} is null when nothing could be compared. */
    public record Match(Integer score, List<String> good, List<String> gaps) {}

    static final int W_SKILLS = 40, W_EXPERIENCE = 25, W_ROLE = 15, W_LOCATION = 15, W_SALARY = 5;

    /** Usual minimum years for a seniority when the posting doesn't state them. */
    private static final Map<String, Integer> SENIORITY_YEARS = Map.of("INTERN", 0, "ENTRY", 0, "MID", 2, "SENIOR", 5, "LEAD", 8, "MANAGER", 6);
    private static final List<List<String>> CITY_ALIASES = List.of(
            List.of("bengaluru", "bangalore"), List.of("gurugram", "gurgaon"), List.of("mumbai", "bombay", "thane", "navi mumbai"),
            List.of("chennai", "madras"), List.of("kolkata", "calcutta"), List.of("delhi", "new delhi", "ncr"),
            List.of("hyderabad", "secunderabad"), List.of("pune", "poona"), List.of("vizag", "visakhapatnam"));

    public static Match score(Candidate c, JobFacts j) {
        double total = 0, weights = 0;
        List<String> good = new ArrayList<>(), gaps = new ArrayList<>();

        // skills
        if (!j.skills().isEmpty() && !c.skills().isEmpty()) {
            Set<String> mine = new HashSet<>();
            c.skills().forEach(s -> mine.add(s.toLowerCase(Locale.ROOT)));
            List<String> have = j.skills().stream().filter(s -> mine.contains(s.toLowerCase(Locale.ROOT))).toList();
            List<String> missing = j.skills().stream().filter(s -> !mine.contains(s.toLowerCase(Locale.ROOT))).toList();
            // postings list nice-to-haves too: about 70% of the listed skills counts as a full match
            double ratio = Math.min(1, have.size() / Math.max(1.0, Math.ceil(j.skills().size() * 0.7)));
            total += W_SKILLS * ratio;
            weights += W_SKILLS;
            if (!have.isEmpty()) good.add("Skills you have: " + String.join(", ", have.subList(0, Math.min(6, have.size()))));
            if (!missing.isEmpty()) gaps.add("Skills you haven't listed: " + String.join(", ", missing.subList(0, Math.min(6, missing.size()))));
        }

        // experience
        Integer need = j.minYears() != null ? j.minYears() : j.seniority() == null ? null : SENIORITY_YEARS.get(j.seniority());
        if (need != null && c.years() != null) {
            double y = c.years().doubleValue();
            double fit = y >= need ? 1 : need == 0 ? 1 : Math.max(0, 1 - 1.5 * (need - y) / Math.max(need, 2));
            total += W_EXPERIENCE * fit;
            weights += W_EXPERIENCE;
            String yrs = c.years().stripTrailingZeros().toPlainString();
            if (y >= need) good.add(need == 0 ? "Open to your experience level" : "Needs " + need + "+ years; you have " + yrs);
            else gaps.add("Needs " + need + "+ years; you have " + yrs);
        }

        // role
        if (c.desiredRoles() != null && !c.desiredRoles().isEmpty() && j.title() != null) {
            boolean fits = RoleMatch.matches(j.title(), c.desiredRoles());
            total += fits ? W_ROLE : 0;
            weights += W_ROLE;
            if (fits) good.add("Matches a role you want");
            else gaps.add("Not one of your desired roles");
        }

        // location
        if (j.location() != null || "REMOTE".equals(j.workplace())) {
            String where = j.location() == null ? "" : j.location().toLowerCase(Locale.ROOT);
            boolean remote = "REMOTE".equals(j.workplace());
            boolean wanted = remote || (c.desiredLocations() != null && c.desiredLocations().stream().anyMatch(l -> sameCity(where, l)));
            if (remote || (c.desiredLocations() != null && !c.desiredLocations().isEmpty())) {
                double fit = wanted ? 1 : c.willingToRelocate() ? 0.5 : 0;
                total += W_LOCATION * fit;
                weights += W_LOCATION;
                if (remote) good.add("Remote");
                else if (wanted) good.add("In a location you want");
                else gaps.add(c.willingToRelocate() ? "Would mean relocating (you're open to it)" : "Outside your locations");
            }
        }

        // salary (only when the employer states one in your currency)
        if (j.salaryMax() != null && c.expectedSalary() != null && c.expectedSalary() > 0
                && j.salaryCurrency() != null && j.salaryCurrency().equalsIgnoreCase(c.salaryCurrency())) {
            double fit = Math.min(1, j.salaryMax() / (double) c.expectedSalary());
            total += W_SALARY * fit;
            weights += W_SALARY;
            if (fit >= 1) good.add("Pays at or above your expectation");
            else gaps.add("Top of the stated range is below your expectation");
        }

        Integer score = weights == 0 ? null : (int) Math.round(100 * total / weights);
        // ponytail: hard caps, not weights: a 15-point location weight still left a Mountain View job at 86 for a Thane user.
        // India only for now (the product's market); add a country table when other countries' users arrive.
        // "Remote - US" is abroad too: only a bare "Remote"/"Anywhere" counts as open to India
        boolean abroad = j.location() != null && namesAPlace(j.location())
                && c.desiredLocations() != null && c.desiredLocations().stream().anyMatch(MatchScorer::inIndia) && !inIndia(j.location());
        if (score != null && abroad) { score = Math.min(score, 40); gaps.addFirst("In another country (needs a work visa)"); }
        if (score != null && need != null && c.years() != null && need - c.years().doubleValue() >= 3) score = Math.min(score, 50);
        if (score != null && c.years() != null && c.years().doubleValue() >= 2 && j.title() != null && ENTRY_TITLE.matcher(j.title()).find()) {
            score = Math.min(score, 50);
            gaps.addFirst("An intern / new-grad role; you have " + c.years().stripTrailingZeros().toPlainString() + " years");
        }
        return new Match(score, good, gaps);
    }

    private static final List<String> INDIA = List.of("india", "bengaluru", "bangalore", "mumbai", "bombay", "thane", "pune", "hyderabad",
            "secunderabad", "chennai", "delhi", "ncr", "gurugram", "gurgaon", "noida", "kolkata", "ahmedabad", "jaipur", "kochi",
            "vizag", "visakhapatnam", "chandigarh", "indore", "coimbatore", "trivandrum", "thiruvananthapuram", "mysuru", "mysore");

    private static final java.util.regex.Pattern ENTRY_TITLE = java.util.regex.Pattern.compile("(?i)\\b(intern(ship)?|new grad|graduate|fresher|trainee|apprentice)\\b");

    /** False for "Remote", "Anywhere", "Hybrid": words that don't name a country or city. */
    static boolean namesAPlace(String location) {
        String rest = location.toLowerCase(Locale.ROOT).replaceAll("\\b(remote|anywhere|worldwide|global|hybrid|onsite|on-site|in|apac|asia|work from home|wfh)\\b", "")
                .replaceAll("[^a-z]+", "");
        return !rest.isEmpty();
    }

    static boolean inIndia(String place) {
        String p = place.toLowerCase(Locale.ROOT);
        return INDIA.stream().anyMatch(p::contains);
    }

    static boolean sameCity(String jobLocationLower, String wanted) {
        String w = wanted.toLowerCase(Locale.ROOT).strip();
        if (w.isEmpty()) return false;
        if (jobLocationLower.contains(w)) return true;
        for (List<String> names : CITY_ALIASES)
            if (names.stream().anyMatch(w::contains) && names.stream().anyMatch(jobLocationLower::contains)) return true;
        return false;
    }
}
