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
        // every catalog job reaches the list now (no role filter at sync), so an unrelated role must sink: a collections
        // manager scored 71 for a Java developer on location + experience alone
        if (score != null && gaps.contains("Not one of your desired roles")) score = Math.min(score, 40);
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
    /** Jobs outside the user's preferred locations still show (and can be applied to) from this match score up. */
    public static final int STRONG_MATCH = 75;

    private static final java.util.regex.Pattern OPEN_TO_INDIA = java.util.regex.Pattern.compile(
            "(?i)\\b(work|hire|hiring|open to|candidates|applicants|anyone)\\b.{0,40}\\b(from )?(anywhere|worldwide|globally|any country|any location|india)\\b"
            + "|\\b(fully remote|remote)[ ,-]+(worldwide|global|anywhere)\\b");
    private static final java.util.regex.Pattern ABROAD_ONLY = java.util.regex.Pattern.compile(
            "(?i)\\b(?:must|need to|required to|should)\\s+(?:be\\s+)?(?:reside|residing|located|based|live|living)\\s+(?:in\\s+)?(?:the\\s+)?(us|u\\.s\\.|usa|united states|canada|uk|united kingdom|europe|eu)\\b"
            + "|\\b(us|u\\.s\\.|usa|united states|canada|uk|eu)[- ]?(?:based)?\\s+(?:candidates|applicants|residents|citizens)?\\s*only\\b"
            + "|\\b(?:authori[sz]ed|eligible|legally able) to work in the (us|u\\.s\\.|united states|uk|united kingdom|canada)\\b"
            + "|\\bremote\\b.{0,30}\\b(?:within|across) the (us|u\\.s\\.|united states|uk|united kingdom|canada)\\b");

    /**
     * A remote posting's real scope, from its description: "Remote" + "US residents only" -> "Remote - United States"
     * (hidden for India users); "Remote - US" + "open to candidates in India / anywhere" -> "... (open to India)" (shown).
     * Anything else is unchanged. Stored as the job's location, so the list filter needs no description.
     */
    public static String scopedLocation(String location, String workplace, String description) {
        String where = location == null ? "" : location.toLowerCase(Locale.ROOT);
        boolean remote = "REMOTE".equals(workplace) || where.contains("remote") || where.contains("anywhere");
        if (!remote || description == null || inIndia(where)) return location;
        if (namesAPlace(where)) return OPEN_TO_INDIA.matcher(description).find() ? location + " (open to India)" : location;
        if (OPEN_TO_INDIA.matcher(description).find()) return location;
        java.util.regex.Matcher m = ABROAD_ONLY.matcher(description);
        if (!m.find()) return location;
        String c = "";
        for (int i = 1; i <= m.groupCount(); i++) if (m.group(i) != null) { c = m.group(i).toLowerCase(Locale.ROOT); break; }
        String country = c.matches("us|u\\.s\\.|usa|united states") ? "United States" : c.matches("uk|united kingdom") ? "United Kingdom"
                : c.equals("canada") ? "Canada" : "Europe";
        return (location == null || location.isBlank() ? "Remote" : location) + " - " + country;
    }

    /** Outside the preferred locations but a strong match in the user's market (India, or remote open to it): still listed. */
    public static boolean strongElsewhere(String location, String workplace, Integer score) {
        return score != null && score >= STRONG_MATCH && inMarket(location, workplace);
    }

    /**
     * The city to offer adding to preferred locations after applying here: the job's first India city that isn't one
     * of theirs; null for remote jobs or when it's already theirs.
     */
    public static String newCity(List<String> preferred, String location, String workplace) {
        if (location == null || "REMOTE".equals(workplace)) return null;
        String where = location.toLowerCase(Locale.ROOT);
        if (where.contains("remote") || where.contains("anywhere")) return null;
        if (preferred.stream().anyMatch(p -> sameCity(where, p))) return null; // also offered in one of theirs
        for (String part : location.split("[;|/]")) {
            String city = part.split(",")[0].replaceAll("\\(.*?\\)", "").strip();
            if (!city.isEmpty() && inIndia(part) && !city.equalsIgnoreCase("india")) return city;
        }
        return null;
    }

    static boolean namesAPlace(String location) {
        String rest = location.toLowerCase(Locale.ROOT).replaceAll("\\b(remote|anywhere|worldwide|global|hybrid|onsite|on-site|in|apac|asia|work from home|wfh)\\b", "")
                .replaceAll("[^a-z]+", "");
        return !rest.isEmpty();
    }

    static boolean inIndia(String place) {
        String p = place.toLowerCase(Locale.ROOT);
        return INDIA.stream().anyMatch(p::contains);
    }

    // ------------------------------------------------------------------ does the job fit the resume
    // Decides which jobs a user sees at all, from their resume alone (no hand-picked roles needed).

    private static final Set<String> LANGUAGES = Set.of("java", "kotlin", "scala", "python", "javascript", "typescript", "c++", "c#",
            ".net", "go", "rust", "ruby", "php", "swift", "flutter");
    // a software-development title: "engineer" alone is too loose (security, IT automation, people systems, AI agent engineers)
    private static final java.util.regex.Pattern DEV_TITLE = java.util.regex.Pattern.compile(
            "(?i)\\b(software|developer|development engineer|sde|swe|programmer|mts|member of technical staff|full[- ]?stack|back[- ]?end)\\b");
    private static final java.util.regex.Pattern NOT_DEV_TITLE = java.util.regex.Pattern.compile(
            "(?i)\\b(sales|account executive|account manager|recruit\\w*|marketing|customer success|support engineer|solutions? (engineer|consultant|architect)"
            + "|pre-?sales|field engineer|network engineer|hardware|mechanical|electrical|civil|manager|director|head of|vp|vice president|chief)\\b");

    /** A technology named in a title, and the resume skills (canonical names, lower case) that count as having it. */
    private record Stack(java.util.regex.Pattern title, Set<String> skills) {}
    private static Stack stack(String regex, String... skills) { return new Stack(java.util.regex.Pattern.compile("(?i)" + regex), Set.of(skills)); }
    private static final List<Stack> STACKS = List.of(
            stack("\\bjava\\b(?!\\s*script)", "java"),
            stack("\\bpython\\b", "python"),
            stack("\\bgolang\\b|\\bgo\\b(?![- ]to)", "go"),
            stack("\\bruby\\b|\\brails\\b", "ruby", "ruby on rails"),
            stack("\\bphp\\b", "php"),
            stack("\\.net\\b|\\bdotnet\\b|c#", "c#", ".net"),
            stack("c\\+\\+", "c++"),
            stack("\\brust\\b", "rust"),
            stack("\\bscala\\b", "scala"),
            stack("\\bnode(\\.?js)?\\b", "node.js"),
            stack("front[- ]?end|\\breact\\b(?! native)|\\bangular\\b|\\bvue\\b|\\bui (engineer|developer)\\b", "react", "angular", "vue.js", "typescript"),
            stack("\\bios\\b|\\bswift\\b", "ios", "swift"),
            stack("\\bandroid\\b|\\bkotlin\\b", "android", "kotlin"),
            stack("\\bflutter\\b", "flutter"),
            stack("react native", "react native"),
            stack("data scien|machine learning|\\bml\\b|\\bai engineer|deep learning|\\bnlp\\b|computer vision", "machine learning", "tensorflow", "pytorch"),
            stack("data engineer", "spark", "hadoop", "airflow"),
            stack("devops|\\bsre\\b|site reliability|cloud engineer|infrastructure engineer", "kubernetes", "terraform"),
            stack("\\bsdet\\b|\\bqa\\b|quality (assurance|engineer)|\\btest(s|er|ers|ing)?\\b", "selenium", "cypress", "playwright", "test automation", "appium", "testng"),
            stack("salesforce", "salesforce"),
            stack("\\bsap\\b", "sap"),
            stack("embedded|firmware", "c++"));

    /** Years a title implies regardless of what the description says (staff and above are hard levels). */
    private static int hardLevel(String t) {
        if (t.matches("(?is).*\\b(distinguished|fellow)\\b.*")) return 12;
        if (t.matches("(?is).*\\b(principal|senior staff|sr\\.? staff)\\b.*")) return 10;
        if (t.matches("(?is).*\\b(staff|architect)\\b.*")) return 8;
        if (t.matches("(?is).*\\b(sde|software development engineer|engineer|developer)[- ]?(iv|4)\\b.*")) return 7;
        if (t.matches("(?is).*\\b(sde|software development engineer|engineer|developer)[- ]?(iii|3)\\b.*")) return 5;
        return 0;
    }

    /** How far above the candidate's years a job may ask before it's hidden. */
    static final double STRETCH_YEARS = 1.5;

    /**
     * True when the job fits what the resume shows: their field (any programming language on the resume = developer
     * titles only; otherwise their desired roles), every technology the title names that they lack hides it (a Python
     * SDE for a Java developer), the level (years the posting or title asks vs their years, intern roles hidden after
     * 2 years), and a fair share of the skills the posting lists.
     */
    public static boolean fitsResume(Candidate c, String title, List<String> jobSkills, Integer minYears, String seniority) {
        if (title == null) return false;
        Set<String> mine = new HashSet<>();
        if (c.skills() != null) c.skills().forEach(x -> mine.add(x.toLowerCase(Locale.ROOT)));

        // field
        boolean developer = mine.stream().anyMatch(LANGUAGES::contains);
        if (developer) {
            // or a title naming a technology on their resume ("Java Engineer")
            // or one of their roles (picked, or what their experience shows: "Data Engineer", "QA Engineer")
            boolean devTitle = DEV_TITLE.matcher(title).find() || RoleMatch.matches(title, c.desiredRoles())
                    || STACKS.stream().anyMatch(st -> st.title().matcher(title).find() && st.skills().stream().anyMatch(mine::contains));
            if (!devTitle || NOT_DEV_TITLE.matcher(title).find()) return false;
        } else if (c.desiredRoles() != null && !c.desiredRoles().isEmpty() && !RoleMatch.matches(title, c.desiredRoles())) return false;

        // technologies named in the title: the candidate needs at least one of them
        List<Stack> named = STACKS.stream().filter(st -> st.title().matcher(title).find()).toList();
        if (!named.isEmpty() && !mine.isEmpty() && named.stream().noneMatch(st -> st.skills().stream().anyMatch(mine::contains))) return false;

        // level
        if (c.years() != null) {
            double y = c.years().doubleValue();
            if (y >= 2 && ENTRY_TITLE.matcher(title).find()) return false;
            Integer need = minYears != null ? minYears : seniority == null ? null : SENIORITY_YEARS.get(seniority);
            int hard = hardLevel(title);
            if (hard > 0) need = need == null ? hard : Math.max(need, hard);
            if (need != null && need > y + STRETCH_YEARS) return false;
        }

        // requirements: a fair share of the listed skills (about 30%, at least one)
        if (jobSkills != null && !jobSkills.isEmpty() && !mine.isEmpty()) {
            long have = jobSkills.stream().filter(x -> mine.contains(x.toLowerCase(Locale.ROOT))).count();
            if (have < Math.max(1, Math.ceil(jobSkills.size() * 0.3))) return false;
        }
        return true;
    }

    /** Jobs JobPilot keeps at all: located in India, or remote and not tied to another country. */
    public static boolean inMarket(String location, String workplace) {
        String where = location == null ? "" : location.toLowerCase(Locale.ROOT);
        boolean remote = "REMOTE".equals(workplace) || where.contains("remote") || where.contains("anywhere");
        return inIndia(where) || (remote && !namesAPlace(where));
    }

    /**
     * The job list shows only these: a job in one of the candidate's desired locations, or remote and open to their
     * country ("Remote" or "Remote - India" for an India user, not "Remote - US"). No desired locations = everything.
     * Unknown location and not remote = hidden.
     */
    public static boolean inDesiredLocation(Candidate c, String location, String workplace) {
        if (c.desiredLocations() == null || c.desiredLocations().isEmpty()) return true;
        String where = location == null ? "" : location.toLowerCase(Locale.ROOT);
        if (c.desiredLocations().stream().anyMatch(l -> sameCity(where, l))) return true;
        boolean remote = "REMOTE".equals(workplace) || where.contains("remote") || where.contains("anywhere");
        boolean wantsIndia = c.desiredLocations().stream().anyMatch(MatchScorer::inIndia);
        // "willing to relocate" does NOT widen the list: the user's cities decide it (a Hyderabad-only user was shown
        // Bengaluru jobs, 2026-11-13). Open to another city = add it to preferred locations.
        return remote && (!namesAPlace(where) || inIndia(where) == wantsIndia);
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
