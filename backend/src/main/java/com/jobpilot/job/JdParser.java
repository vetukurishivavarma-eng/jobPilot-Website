package com.jobpilot.job;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic job-description parser: skills (from skills.txt), minimum years, seniority, education.
 * No LLM: free, instant, testable, and it can't hallucinate a requirement that isn't in the text.
 * ponytail: dictionary + regex; an LLM pass (Phase 5 agent) can refine requirements the dictionary misses.
 */
public final class JdParser {
    private JdParser() {}

    public record Parsed(List<String> skills, Integer minYears, String seniority, String educationLevel) {}

    private record Skill(String name, Pattern pattern) {}

    private static final List<Skill> SKILLS = load();

    private static List<Skill> load() {
        List<Skill> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(JdParser.class.getResourceAsStream("/skills.txt")), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|");
                List<String> alts = new ArrayList<>();
                for (int i = 1; i < parts.length; i++) {
                    String a = parts[i].strip();
                    // (?-i:...) = case-sensitive alias
                    alts.add(a.startsWith("=") ? "(?-i:" + Pattern.quote(a.substring(1)) + ")" : Pattern.quote(a));
                }
                out.add(new Skill(parts[0].strip(), Pattern.compile(
                        "(?<![A-Za-z0-9+#.])(?:" + String.join("|", alts) + ")(?![A-Za-z0-9+#])", Pattern.CASE_INSENSITIVE)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(out);
    }

    private static final Pattern YEARS = Pattern.compile(
            "(\\d{1,2})\\s*(?:\\+|plus)?\\s*(?:(?:-|–|to)\\s*(\\d{1,2})\\s*)?\\+?\\s*(?:years?|yrs?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPERIENCE_NEAR = Pattern.compile("experience|exp\\b|work(?:ing)? in|background", Pattern.CASE_INSENSITIVE);
    private static final Pattern FRESHER = Pattern.compile("\\b(?:freshers?|new grads?|recent graduates?|graduate program|no experience required|0 years)\\b", Pattern.CASE_INSENSITIVE);

    public static Parsed parse(String title, String description) {
        String t = title == null ? "" : title;
        String text = t + "\n" + (description == null ? "" : description);

        List<String> skills = new ArrayList<>();
        for (Skill s : SKILLS) if (s.pattern.matcher(text).find()) skills.add(s.name);

        Integer minYears = minYears(text);
        return new Parsed(skills, minYears, seniority(t, minYears), education(text));
    }

    /** Largest "N+ years ... experience" lower bound: a posting asking 5+ years overall and 2 in Python needs 5. */
    static Integer minYears(String text) {
        Integer best = null;
        Matcher m = YEARS.matcher(text);
        while (m.find()) {
            int lo = Integer.parseInt(m.group(1));
            if (lo > 25) continue;
            String around = text.substring(Math.max(0, m.start() - 40), Math.min(text.length(), m.end() + 60));
            if (!EXPERIENCE_NEAR.matcher(around).find()) continue;
            best = best == null ? lo : Math.max(best, lo);
        }
        if (best == null && FRESHER.matcher(text).find()) return 0;
        return best;
    }

    static String seniority(String title, Integer minYears) {
        String t = " " + title.toLowerCase().replaceAll("[^a-z0-9]+", " ") + " ";
        if (has(t, "intern", "internship", "trainee", "apprentice")) return "INTERN";
        if (has(t, "manager", "head", "director", "vp", "vice president", "chief")) return "MANAGER";
        if (has(t, "lead", "staff", "principal", "architect")) return "LEAD";
        if (has(t, "senior", "sr", "iii", "iv")) return "SENIOR";
        if (has(t, "junior", "jr", "entry", "fresher", "graduate", "associate", "i")) return "ENTRY";
        if (minYears == null) return null;
        return minYears <= 1 ? "ENTRY" : minYears <= 4 ? "MID" : "SENIOR";
    }

    private static boolean has(String paddedTitle, String... words) {
        for (String w : words) if (paddedTitle.contains(" " + w + " ")) return true;
        return false;
    }

    private static final Pattern BACHELOR = Pattern.compile("\\b(?:bachelor'?s?|b\\.?\\s?tech|b\\.e\\.|b\\.?sc|bca|b\\.?com|undergraduate degree|graduation)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MASTER = Pattern.compile("\\b(?:master'?s?|m\\.?\\s?tech|mba|m\\.?sc|mca|post[- ]?graduat\\w*)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHD = Pattern.compile("\\b(?:ph\\.?\\s?d|doctorate|doctoral)\\b", Pattern.CASE_INSENSITIVE);

    /** Lowest degree mentioned: "Bachelor's or Master's" means a bachelor's is enough. */
    static String education(String text) {
        if (BACHELOR.matcher(text).find()) return "BACHELOR";
        if (MASTER.matcher(text).find()) return "MASTER";
        if (PHD.matcher(text).find()) return "PHD";
        return null;
    }
}
