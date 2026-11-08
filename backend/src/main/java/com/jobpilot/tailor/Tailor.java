package com.jobpilot.tailor;

import com.jobpilot.job.JdParser;
import com.jobpilot.profile.Profile.Education;
import com.jobpilot.profile.Profile.Experience;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tailors a resume and writes a cover letter for one job, from the candidate's own facts only.
 * Deterministic steps (reordering skills and bullets) never change a fact. AI rewrites are fact-checked:
 * a number or skill that isn't in the candidate's facts gets the rewrite thrown away (resume) or flagged (letter).
 */
public final class Tailor {
    private Tailor() {}

    public record Facts(String name, String email, String phone, String location, String linkedin, String headline, String summary,
                        String years, String currentTitle, List<String> skills, Set<String> resumeSkills,
                        List<Experience> experiences, List<Education> educations, String resumeText) {
        /** Everything the candidate has told us, as one text: the reference for fact-checking. */
        String all() {
            StringBuilder b = new StringBuilder();
            for (String s : Arrays.asList(name, email, phone, linkedin, location, headline, summary, years, currentTitle, String.join(", ", skills), resumeText))
                if (s != null) b.append(s).append('\n');
            for (Experience e : experiences) b.append(experienceText(e)).append('\n');
            for (Education e : educations) b.append(educationLine(e)).append('\n');
            return b.toString();
        }

        Set<String> allSkills() {
            Set<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            s.addAll(skills);
            s.addAll(resumeSkills);
            return s;
        }
    }

    public record JobInfo(String title, String company, String description, List<String> skills) {}

    /** One reviewable change: the user keeps the original or takes the tailored version (which they may edit). */
    public record Choice(String original, String tailored, boolean useTailored, String why) {
        String chosen() { return useTailored && tailored != null ? tailored : original; }
    }

    public record ExpBlock(String heading, String meta, Choice bullets) {}

    public record TailoredResume(String name, String contact, String headline, Choice summary, Choice skills,
                                 List<ExpBlock> experiences, List<String> education, List<String> notes) {}

    public record CoverLetter(String text, String source, List<String> warnings) {}

    // ------------------------------------------------------------------ resume

    public static TailoredResume resume(Facts f, JobInfo job, Function<String, String> ai) {
        List<String> notes = new ArrayList<>();
        Set<String> jobSkills = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        jobSkills.addAll(job.skills());
        jobSkills.addAll(JdParser.parse(job.title(), job.description()).skills());

        Choice skills = tailorSkills(f, jobSkills);
        List<ExpBlock> exps = new ArrayList<>();
        for (Experience e : f.experiences())
            exps.add(new ExpBlock(e.title() + " — " + e.company(), meta(e), tailorBullets(e, f, job, jobSkills, ai, notes)));

        Choice summary = new Choice(f.summary() == null ? "" : f.summary(), null, false, null);
        if (ai != null) {
            String s = summaryOnly(clean(ai.apply(summaryPrompt(f, job))), f.name());
            if (s != null && !s.contains("NEED_INPUT")) {
                List<String> problems = FactCheck.problems(s, f.all(), f.allSkills());
                if (problems.isEmpty()) summary = new Choice(summary.original(), s, true, "Written for this role from your profile");
                else notes.add("AI summary discarded: it " + String.join("; ", problems) + ".");
            }
        }

        List<String> education = f.educations().stream().map(Tailor::educationLine).toList();
        String contact = String.join("  ·  ", Arrays.stream(new String[]{f.email(), f.phone(), f.location(), f.linkedin()})
                .filter(s -> s != null && !s.isBlank()).toList());
        return new TailoredResume(f.name(), contact, f.headline(), summary, skills, exps, education, notes);
    }

    /** Skills the job asks for go first, including ones found in the candidate's own resume. Never adds anything else. */
    static Choice tailorSkills(Facts f, Set<String> jobSkills) {
        List<String> profile = f.skills();
        List<String> first = new ArrayList<>(), rest = new ArrayList<>(), fromResume = new ArrayList<>();
        for (String s : profile) (jobSkills.contains(s) ? first : rest).add(s);
        // skills already covered by one of the user's own ("Spring" inside "Spring Boot") aren't added again
        Set<String> covered = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        covered.addAll(profile);
        covered.addAll(JdParser.parse(null, String.join(", ", profile)).skills());
        for (String s : f.resumeSkills())
            if (jobSkills.contains(s) && !covered.contains(s)) { first.add(s); fromResume.add(s); }
        List<String> ordered = new ArrayList<>(first);
        ordered.addAll(rest);
        String original = String.join(", ", profile), tailored = String.join(", ", ordered);
        if (tailored.equals(original)) return new Choice(original, null, false, null);
        List<String> why = new ArrayList<>();
        if (!first.isEmpty()) why.add("Put the skills this job asks for first");
        if (!fromResume.isEmpty()) why.add("added from your resume: " + String.join(", ", fromResume));
        return new Choice(original, tailored, true, String.join("; ", why));
    }

    static Choice tailorBullets(Experience e, Facts f, JobInfo job, Set<String> jobSkills, Function<String, String> ai, List<String> notes) {
        List<String> bullets = bullets(e.description());
        String original = String.join("\n", bullets);
        if (bullets.isEmpty()) return new Choice("", null, false, null);

        Set<String> keywords = keywords(job, jobSkills);
        List<String> ordered = new ArrayList<>(bullets);
        ordered.sort(Comparator.comparingInt((String b) -> relevance(b, keywords)).reversed()); // stable: ties keep the user's order
        String reordered = String.join("\n", ordered);
        boolean moved = !reordered.equals(original);

        if (ai != null) {
            String out = ai.apply(bulletsPrompt(e, ordered, job, keywords));
            List<String> rewritten = out == null ? List.of() : bullets(out);
            if (rewritten.size() == ordered.size()) {
                // stricter than the summary: a bullet may only name skills this role's own text names,
                // and must keep most of the original bullet's key words (a rewrite, not a new claim)
                Set<String> roleSkills = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                roleSkills.addAll(JdParser.parse(e.title(), e.description()).skills());
                List<String> problems = new ArrayList<>();
                for (int i = 0; i < rewritten.size(); i++) {
                    for (String p : FactCheck.problems(rewritten.get(i), experienceText(e), roleSkills))
                        problems.add(p.replace("which isn't in your skills", "which this role's description doesn't mention"));
                    if (!FactCheck.sameClaim(ordered.get(i), rewritten.get(i)))
                        problems.add("changes what \"" + cut(ordered.get(i), 60) + "\" says");
                }
                if (problems.isEmpty())
                    return new Choice(original, String.join("\n", rewritten), true,
                            (moved ? "Most relevant first; " : "") + "reworded toward the job's language, same facts");
                notes.add("AI rewrite for " + e.company() + " discarded: it " + String.join("; ", new LinkedHashSet<>(problems)) + ".");
            } else if (out != null) {
                notes.add("AI rewrite for " + e.company() + " discarded: it didn't keep one line per bullet.");
            }
        }
        return moved ? new Choice(original, reordered, true, "Most relevant to this job first") : new Choice(original, null, false, null);
    }

    // ------------------------------------------------------------------ cover letter

    public static CoverLetter coverLetter(Facts f, JobInfo job, Function<String, String> ai) {
        if (ai != null) {
            String text = clean(ai.apply(letterPrompt(f, job, List.of())));
            if (text != null && !text.contains("NEED_INPUT")) {
                List<String> problems = FactCheck.problems(text, f.all() + "\n" + job.company() + " " + job.title(), f.allSkills());
                if (!problems.isEmpty()) { // one retry, telling the model what to drop
                    String retry = clean(ai.apply(letterPrompt(f, job, problems)));
                    if (retry != null && !retry.contains("NEED_INPUT")) {
                        text = retry;
                        problems = FactCheck.problems(text, f.all() + "\n" + job.company() + " " + job.title(), f.allSkills());
                    }
                }
                return new CoverLetter(text, "AI", problems.stream().map(p -> "This letter " + p + ". Check it before sending.").toList());
            }
        }
        return new CoverLetter(template(f, job), "TEMPLATE", List.of());
    }

    /** Used when no model is available: plain, factual, and short. */
    static String template(Facts f, JobInfo job) {
        StringBuilder b = new StringBuilder("Dear Hiring Team at ").append(job.company()).append(",\n\n");
        b.append("I'm applying for the ").append(job.title()).append(" role.");
        Experience now = f.experiences().stream().filter(e -> e.endDate() == null).findFirst().orElse(null);
        if (f.currentTitle() != null || now != null) {
            b.append(" I'm currently ").append(article(f.currentTitle() != null ? f.currentTitle() : now.title()));
            if (now != null) b.append(" at ").append(now.company());
            if (f.years() != null) b.append(", with ").append(f.years()).append(" years of experience");
            b.append('.');
        }
        Set<String> jobSkills = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        jobSkills.addAll(job.skills());
        List<String> shared = f.allSkills().stream().filter(jobSkills::contains).limit(5).toList();
        if (!shared.isEmpty()) b.append(" The role calls for ").append(join(shared)).append(", which I work with day to day.");
        b.append("\n\n");
        if (now != null) {
            List<String> top = bullets(now.description());
            if (!top.isEmpty()) b.append("Recently: ").append(lowerFirst(stripEnd(top.getFirst()))).append(".\n\n");
        }
        b.append("I'd welcome the chance to talk about how I could help ").append(job.company()).append(". Thank you for your time.\n\n");
        b.append(f.name());
        String contact = String.join(" · ", Arrays.stream(new String[]{f.email(), f.phone()}).filter(s -> s != null && !s.isBlank()).toList());
        if (!contact.isEmpty()) b.append('\n').append(contact);
        return b.toString();
    }

    // ------------------------------------------------------------------ prompts

    static String summaryPrompt(Facts f, JobInfo job) {
        return """
                Write a 2-3 sentence professional summary for the top of %s's resume, aimed at a "%s" role at %s.
                Resume style with no pronouns (like "Backend engineer with N years building ...").
                Use ONLY facts below. Never add numbers, employers, degrees or skills that are not listed.
                If the facts are too thin, reply exactly NEED_INPUT. Reply with the summary only.

                FACTS:
                %s
                JOB DESCRIPTION (for emphasis only, not a source of facts):
                %s
                """.formatted(f.name(), job.title(), job.company(), f.all(), cut(job.description(), 2500));
    }

    static String bulletsPrompt(Experience e, List<String> bullets, JobInfo job, Set<String> keywords) {
        return """
                Rewrite these resume bullet points from %s at %s for a "%s" application.
                Keep every fact exactly: the same numbers, tools and scope. Do not add skills, tools, numbers or achievements.
                Copy phrases that contain numbers word for word (e.g. "by 40%%" stays "by 40%%").
                You may use stronger verbs and the job's terms where the original already describes that thing.
                Return exactly %d lines, one per bullet, in the same order, with no numbering or bullet symbols.

                JOB TERMS: %s
                BULLETS:
                %s
                """.formatted(e.title(), e.company(), job.title(), bullets.size(), String.join(", ", keywords),
                String.join("\n", bullets.stream().map(b -> "- " + b).toList()));
    }

    static String letterPrompt(Facts f, JobInfo job, List<String> avoid) {
        return """
                Write a cover letter from %s for the "%s" role at %s. 150 to 250 words, plain text, first person.
                Start with "Dear Hiring Team at %s," and end with the candidate's name.
                Use ONLY facts from the candidate facts. Never invent employers, numbers, degrees, dates or skills.%s
                If the facts are not enough, reply exactly NEED_INPUT.

                CANDIDATE FACTS:
                %s
                JOB DESCRIPTION:
                %s
                """.formatted(f.name(), job.title(), job.company(), job.company(),
                avoid.isEmpty() ? "" : "\nYour last draft broke these rules, fix them: it " + String.join("; it ", avoid) + ".",
                f.all(), cut(job.description(), 3000));
    }

    // ------------------------------------------------------------------ helpers

    private static final Pattern BULLET_PREFIX = Pattern.compile("^\\s*(?:[-•*·▪●◦➢✓\\uF000-\\uF0FF]|\\d+[.)])\\s*");

    /** Bullet lines from a description: one per line, or one per sentence for a single paragraph. */
    static List<String> bullets(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\\R")) {
            String s = BULLET_PREFIX.matcher(l).replaceFirst("").strip();
            if (!s.isEmpty()) lines.add(s);
        }
        if (lines.size() == 1 && lines.getFirst().length() > 160) {
            List<String> sentences = new ArrayList<>();
            for (String s : lines.getFirst().split("(?<=[.!?])\\s+(?=[A-Z])")) if (!s.isBlank()) sentences.add(s.strip());
            return sentences;
        }
        return lines;
    }

    private static final Set<String> STOP = Set.of("with", "will", "from", "that", "this", "your", "have", "team", "work", "role",
            "about", "into", "they", "their", "what", "which", "such", "able", "more", "other", "years", "experience", "strong");

    static Set<String> keywords(JobInfo job, Set<String> jobSkills) {
        Set<String> k = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        k.addAll(jobSkills);
        for (String w : (job.title() == null ? "" : job.title()).toLowerCase(Locale.ROOT).split("[^a-z0-9+#]+"))
            if (w.length() >= 4 && !STOP.contains(w)) k.add(w);
        return k;
    }

    static int relevance(String bullet, Set<String> keywords) {
        String b = bullet.toLowerCase(Locale.ROOT);
        int n = 0;
        for (String k : keywords) if (JdParser.containsWord(b, k.toLowerCase(Locale.ROOT))) n++;
        return n;
    }

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    static String meta(Experience e) {
        String dates = fmt(e.startDate()) + " – " + (e.endDate() == null ? "Present" : fmt(e.endDate()));
        return e.location() == null || e.location().isBlank() ? dates : e.location() + "  ·  " + dates;
    }

    private static String fmt(LocalDate d) { return d == null ? "" : d.format(MONTH); }

    static String experienceText(Experience e) {
        return e.title() + " at " + e.company() + (e.location() == null ? "" : ", " + e.location()) + " " + e.startDate() + " "
                + (e.endDate() == null ? "present" : e.endDate()) + "\n" + (e.description() == null ? "" : e.description());
    }

    static String educationLine(Education e) {
        String years = e.startYear() == null && e.endYear() == null ? ""
                : " (" + (e.startYear() == null ? "" : e.startYear()) + (e.endYear() == null ? "" : "–" + e.endYear()) + ")";
        return e.degree() + (e.field() == null || e.field().isBlank() ? "" : ", " + e.field()) + " — " + e.institution() + years
                + (e.grade() == null || e.grade().isBlank() ? "" : " · " + e.grade());
    }

    /** The model sometimes echoes the resume header: keep only summary sentences, as one paragraph. */
    static String summaryOnly(String s, String name) {
        if (s == null) return null;
        List<String> keep = new ArrayList<>();
        for (String line : s.split("\\R")) {
            String l = line.strip();
            if (l.isEmpty() || (name != null && l.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT)))) continue;
            if (l.toLowerCase(Locale.ROOT).matches("(professional )?summary:?")) continue;
            keep.add(l.replaceFirst("(?i)^(professional )?summary:\\s*", ""));
        }
        String out = String.join(" ", keep).strip();
        return out.isEmpty() ? null : out;
    }

    /** Drops wrapping quotes/markdown the model sometimes adds. */
    static String clean(String s) {
        if (s == null) return null;
        String t = s.strip().replaceAll("^```\\w*\\s*|\\s*```$", "").replaceAll("\\*\\*", "").strip();
        if (t.length() > 1 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1).strip();
        return t.isEmpty() ? null : t;
    }

    private static String cut(String s, int n) { return s == null ? "" : s.substring(0, Math.min(n, s.length())); }
    private static String join(List<String> xs) {
        return xs.size() == 1 ? xs.getFirst() : String.join(", ", xs.subList(0, xs.size() - 1)) + " and " + xs.getLast();
    }
    private static String article(String noun) { return ("aeiou".indexOf(Character.toLowerCase(noun.charAt(0))) >= 0 ? "an " : "a ") + noun; }
    private static String lowerFirst(String s) { return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1); }
    private static String stripEnd(String s) { return s.replaceAll("[.\\s]+$", ""); }

    /** Numbers and skills in generated text must come from the candidate's own facts. */
    public static final class FactCheck {
        private FactCheck() {}

        private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

        private static final List<String> WORDS = List.of("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
                "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty");
        private static final Pattern NUMBER_WORD = Pattern.compile("\\b(" + String.join("|", WORDS) + ")\\b", Pattern.CASE_INSENSITIVE);

        public static List<String> problems(String text, String source, Set<String> allowedSkills) {
            List<String> out = new ArrayList<>();
            Set<String> known = numbers(source);
            Matcher m = NUMBER.matcher(text);
            while (m.find()) {
                String n = m.group().replace(",", "");
                if (!known.contains(n)) out.add("mentions \"" + m.group() + "\", which isn't in your profile");
            }
            // "five engineers" is a number too ("one" is too common in plain English to check)
            m = NUMBER_WORD.matcher(text);
            while (m.find()) {
                int v = WORDS.indexOf(m.group(1).toLowerCase(Locale.ROOT));
                if (v > 1 && !known.contains(String.valueOf(v)) && !source.toLowerCase(Locale.ROOT).contains(m.group(1).toLowerCase(Locale.ROOT)))
                    out.add("mentions \"" + m.group(1) + "\", which isn't in your profile");
            }
            // "Spring Boot" in your skills also covers "Spring": expand to everything the parser finds inside them
            Set<String> allowed = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            allowed.addAll(allowedSkills);
            allowed.addAll(JdParser.parse(null, String.join(", ", allowedSkills)).skills());
            for (String s : JdParser.parse(null, text).skills())
                if (!allowed.contains(s)) out.add("mentions " + s + ", which isn't in your skills");
            return out;
        }

        /**
         * A rewrite keeps the claim when most of the original's key words survive (compared on a 5-letter stem,
         * so "designed"/"designing" match). Catches a model replacing a bullet with something new.
         */
        public static boolean sameClaim(String original, String rewrite) {
            Set<String> key = stems(original);
            if (key.isEmpty()) return true;
            Set<String> kept = stems(rewrite);
            long survived = key.stream().filter(kept::contains).count();
            return survived >= Math.ceil(key.size() * 0.5);
        }

        private static Set<String> stems(String s) {
            Set<String> out = new HashSet<>();
            for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9+#]+"))
                if (w.length() >= 4 && !STOP.contains(w)) out.add(w.length() > 5 ? w.substring(0, 5) : w);
            return out;
        }

        public static Set<String> numbers(String s) {
            Set<String> out = new HashSet<>();
            Matcher m = NUMBER.matcher(s == null ? "" : s);
            while (m.find()) out.add(m.group().replace(",", ""));
            return out;
        }
    }
}
