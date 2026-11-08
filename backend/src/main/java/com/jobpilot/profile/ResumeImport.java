package com.jobpilot.profile;

import com.jobpilot.job.JdParser;
import com.jobpilot.profile.Profile.Education;
import com.jobpilot.profile.Profile.Experience;
import com.jobpilot.profile.ProfileController.ProfileDto;
import com.jobpilot.tailor.Tailor.FactCheck;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills a profile from resume text. Contact details, links, skills, the summary paragraph and each job's bullet lines
 * come straight from the text; the local LLM only gives the structure (jobs, titles, dates, education) plus a short
 * "what you did" summary per job, kept short so a 3B model on CPU answers in about a minute. Every LLM value is checked
 * against the text (a company not in it is dropped; a job summary with numbers or skills not under that job is dropped).
 * Years of experience are computed from the job dates. Nothing is saved here: the result is a draft the user reviews.
 */
final class ResumeImport {
    private ResumeImport() {}

    record Result(ProfileDto profile, List<String> notes) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<![\\d])(\\+?\\d[\\d ()-]{8,18}\\d)(?![\\d])");
    private static final Pattern URL = Pattern.compile("(?i)\\b((?:https?://)?(?:www\\.)?(?:linkedin\\.com|github\\.com)/[A-Za-z0-9_/%.-]+|https?://[^\\s,;)]+)");

    /** Merges what the resume says into {@code current}: fields the user already filled are kept. */
    static Result run(String resume, ProfileDto current, Function<String, String> llmJson) {
        List<String> notes = new ArrayList<>();
        String text = resume == null ? "" : resume;

        // exact patterns
        String phone = first(PHONE, text), linkedin = null, github = null, portfolio = null;
        Matcher u = URL.matcher(text);
        while (u.find()) {
            String url = u.group(1).replaceAll("[.]+$", "");
            if (!url.toLowerCase().startsWith("http")) url = "https://" + url;
            String l = url.toLowerCase();
            if (l.contains("linkedin.com/") && linkedin == null) linkedin = url;
            else if (l.contains("github.com/") && github == null) github = url;
            else if (portfolio == null && !l.contains("linkedin") && !l.contains("github") && !EMAIL.matcher(url).find()) portfolio = url;
        }
        List<String> skills = JdParser.parse(null, text).skills();

        // LLM extraction, checked against the text
        JsonNode j = JSON.createObjectNode();
        String raw = llmJson == null ? null : llmJson.apply(prompt(text));
        if (raw != null) try { j = JSON.readTree(raw); } catch (RuntimeException e) { notes.add("The AI's answer wasn't readable, so only contact details and skills were filled."); }
        else notes.add("Local AI isn't available, so only contact details and skills were filled. Add your jobs on this page.");

        String lower = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); // PDF extraction wraps lines mid-name
        String title = inText(str(j, "currentTitle"), lower);
        String location = inText(str(j, "location"), lower);
        String summary = cut(section(text, SUMMARY_HEAD), 5000); // the resume's own summary paragraph, no model

        // The model gives the structure (jobs, dates) and a short summary per job; bullets are cut from the text itself.
        List<String> lines = Arrays.stream(text.split("\\R")).map(String::strip).toList();
        List<JsonNode> jobs = new ArrayList<>();
        j.path("experiences").forEach(jobs::add);
        List<Integer> headers = new ArrayList<>();
        int from = 0;
        for (JsonNode e : jobs) {
            int h = lineOf(lines, str(e, "company"), from);
            headers.add(h);
            if (h >= 0) from = h + 1;
        }
        // Dates must be a range written in the resume: a 3B model once took the date of birth as a start date.
        // Two-column PDFs put the ranges away from the jobs, so an unmatched job gets the next unused range in text order.
        List<LocalDate[]> ranges = ranges(text);
        LocalDate[][] when = new LocalDate[jobs.size()][];
        Set<Integer> used = new HashSet<>();
        for (int k = 0; k < jobs.size(); k++) {
            LocalDate s = date(str(jobs.get(k), "startDate"), false);
            for (int r = 0; r < ranges.size(); r++)
                if (s != null && !used.contains(r) && YearMonth.from(ranges.get(r)[0]).equals(YearMonth.from(s))) { when[k] = ranges.get(r); used.add(r); break; }
        }
        for (int k = 0; k < jobs.size(); k++) {
            if (when[k] != null || ranges.isEmpty()) continue;
            for (int r = 0; r < ranges.size(); r++) if (!used.contains(r)) { when[k] = ranges.get(r); used.add(r); break; }
            if (when[k] != null && str(jobs.get(k), "company") != null) notes.add("Check the dates for " + str(jobs.get(k), "title") + " at " + str(jobs.get(k), "company") + ": they were matched by position in your resume.");
        }

        List<Experience> exps = new ArrayList<>();
        int droppedSummaries = 0;
        for (int k = 0; k < jobs.size(); k++) {
            JsonNode e = jobs.get(k);
            String company = inText(str(e, "company"), lower), role = str(e, "title");
            LocalDate start = when[k] != null ? when[k][0] : ranges.isEmpty() ? date(str(e, "startDate"), false) : null;
            if (company == null || role == null || start == null) {
                if (str(e, "company") != null) notes.add("Skipped a job at \"" + str(e, "company") + "\": it couldn't be matched to your resume text or had no start date.");
                continue;
            }
            if (!FactCheck.sameClaim(role, text)) { notes.add("Skipped \"" + role + "\" at " + company + ": that title isn't in your resume."); continue; }
            LocalDate end = when[k] != null ? when[k][1] : date(str(e, "endDate"), true);
            int h = headers.get(k), next = lines.size();
            for (int m = k + 1; m < headers.size(); m++) if (headers.get(m) > h) { next = headers.get(m); break; }
            List<String> bullets = h < 0 ? List.of() : bullets(lines.subList(h + 1, next), role);
            String sum = str(e, "summary");
            if (sum == null && bullets.size() >= 2 && raw != null) sum = jobSummary(llmJson, role, company, bullets);
            if (sum != null && !jobSummaryOk(sum, role + "\n" + String.join("\n", bullets), text)) { sum = null; droppedSummaries++; }
            String desc = ((sum == null ? "" : sum + "\n") + String.join("\n", bullets.stream().map(x -> "• " + x).toList())).strip();
            exps.add(new Experience(cut(company, 160), cut(role, 160), cut(inText(str(e, "location"), lower), 120), start,
                    end != null && end.isBefore(start) ? null : end, desc.isEmpty() ? null : cut(desc, 5000)));
        }
        if (droppedSummaries > 0) notes.add(droppedSummaries + " job summary(ies) were left out because they mentioned things not written under that job. Add a line yourself under \"What you did\".");

        List<Education> edus = new ArrayList<>();
        for (JsonNode e : j.path("educations")) {
            String inst = inText(str(e, "institution"), lower), degree = str(e, "degree");
            if (inst == null || degree == null || !FactCheck.sameClaim(degree, text)) continue;
            edus.add(new Education(cut(inst, 200), cut(degree, 120), cut(inText(str(e, "field"), lower), 120),
                    year(str(e, "startYear")), year(str(e, "endYear")), cut(inText(str(e, "grade"), lower), 40)));
        }

        // The user pressed "Fill from my resume" and reviews a draft, so what the resume says wins; what only the user
        // knows (salary, notice, work authorization, relocation, desired roles/locations) and anything the resume lacks is kept.
        BigDecimal years = years(exps);
        ProfileDto c = current;
        for (Experience e : exps) if (title != null && title.length() > e.title().length() && title.startsWith(e.title()) && title.contains(e.company())) title = e.title();
        String currentTitle = or(title != null ? cut(title, 120)
                : exps.stream().filter(e -> e.endDate() == null).map(Experience::title).findFirst().orElse(null), c.currentTitle());
        String loc = or(cut(location, 120), c.location());
        if (summary == null && raw != null && !exps.isEmpty()) {
            summary = profileSummary(llmJson, currentTitle, years, skills, exps, text);
            if (summary == null) notes.add("Couldn't write a summary that sticks to your resume's facts. Add a few lines under Summary yourself.");
        }
        // the current job's skills lead (the headline shows the first three)
        List<String> mergedSkills = new ArrayList<>();
        if (!exps.isEmpty()) mergedSkills.addAll(JdParser.parse(exps.getFirst().title(), exps.getFirst().description()).skills());
        for (String s : skills) if (mergedSkills.stream().noneMatch(x -> x.equalsIgnoreCase(s))) mergedSkills.add(s);
        for (String s : c.skills()) if (mergedSkills.stream().noneMatch(x -> x.equalsIgnoreCase(s))) mergedSkills.add(s);

        ProfileDto draft = new ProfileDto(
                or(currentTitle == null ? null
                        : cut(currentTitle + (mergedSkills.isEmpty() ? "" : " · " + String.join(", ", mergedSkills.stream().limit(3).toList())), 200), c.headline()),
                or(cut(phone, 30), c.phone()), loc, currentTitle,
                years != null ? years : c.yearsExperience(),
                c.expectedSalary(), c.salaryCurrency(), c.noticePeriodDays(), c.workAuthorization(), c.willingToRelocate(),
                or(cut(linkedin, 300), c.linkedinUrl()), or(cut(github, 300), c.githubUrl()), or(cut(portfolio, 300), c.portfolioUrl()),
                or(summary, c.summary()),
                mergedSkills.stream().limit(100).toList(),
                // suggestions so matching works straight away; the user edits them
                !c.desiredRoles().isEmpty() || currentTitle == null ? c.desiredRoles() : List.of(currentTitle),
                !c.desiredLocations().isEmpty() || loc == null ? c.desiredLocations() : List.of(loc.split(",")[0].strip()),
                exps.isEmpty() ? c.experiences() : exps.stream().limit(30).toList(),
                edus.isEmpty() ? c.educations() : edus.stream().limit(15).toList(),
                c.updatedAt());
        return new Result(draft, notes);
    }

    static String prompt(String text) {
        return """
                Read this resume and return JSON with exactly these keys:
                {"currentTitle": str|null, "location": str|null,
                 "experiences": [{"company": str, "title": str, "location": str|null, "startDate": "YYYY-MM", "endDate": "YYYY-MM"|null}],
                 "educations": [{"institution": str, "degree": str, "field": str|null, "startYear": int|null, "endYear": int|null, "grade": str|null}]}
                Rules: copy company, title, institution and degree exactly as written. Use null when something is missing.
                endDate is null for a current job ("Present"). List jobs newest first.

                RESUME:
                %s
                """.formatted(text.length() > 8000 ? text.substring(0, 8000) : text);
    }

    private static final Pattern SUMMARY_HEAD = Pattern.compile("(?i)^(?:professional |career |profile |executive )?(?:summary|profile|objective|overview|about me)\\s*:?$");
    private static final Pattern ANY_HEAD = Pattern.compile("(?i)^(?:work |professional )?(?:experience|employment(?: history)?|education"
            + "|academic.*|projects?|(?:technical |key )?skills|certifications?|achievements|awards|languages|interests|hobbies|publications"
            + "|declaration|personal (?:details|information)|(?:professional |career )?(?:summary|profile|objective)|about me)\\s*:?$");
    // -: Symbol/Wingdings bullets from Word PDFs (e.g. U+F0B7)
    private static final Pattern BULLET = Pattern.compile("^(?:[-•*·▪●◦➢✓\\uF000-\\uF0FF]|\\d+[.)])\\s*");
    private static final Pattern DATEISH = Pattern.compile("(?i)(?:19|20)\\d\\d|present|current");

    /** The paragraph under a heading such as "Summary", up to the next heading. */
    static String section(String text, Pattern head) {
        StringBuilder out = null;
        for (String l : text.split("\\R")) {
            String s = l.strip();
            if (out == null) { if (head.matcher(s).matches()) out = new StringBuilder(); continue; }
            if (ANY_HEAD.matcher(s).matches()) break;
            if (!s.isEmpty()) out.append(out.isEmpty() ? "" : " ").append(BULLET.matcher(s).replaceFirst(""));
        }
        return out == null || out.isEmpty() ? null : out.toString();
    }

    /** Index of the first line at or after {@code from} naming the company (also when the name wraps onto the next line). */
    static int lineOf(List<String> lines, String company, int from) {
        if (company == null) return -1;
        String c = company.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (int i = from; i < lines.size(); i++) {
            String l = lines.get(i).toLowerCase(Locale.ROOT);
            if (l.contains(c)) return i;
            if (l.isBlank() || i + 1 >= lines.size()) continue;
            String next = lines.get(i + 1).toLowerCase(Locale.ROOT);
            if (!next.contains(c) && (l + " " + next).contains(c)) return i;
        }
        return -1;
    }

    /** The achievement lines under one job: bullets with wrapped lines rejoined, stopping at the next section. */
    static List<String> bullets(List<String> body, String role) {
        boolean marked = body.stream().anyMatch(l -> BULLET.matcher(l).find());
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        for (String l : body) {
            if (ANY_HEAD.matcher(l).matches()) break;
            if (l.isEmpty()) { if (cur != null) out.add(cur.toString()); cur = null; continue; }
            boolean isBullet = BULLET.matcher(l).find();
            String t = BULLET.matcher(l).replaceFirst("").strip();
            boolean meta = t.length() < 70 && (DATEISH.matcher(t).find() || t.equalsIgnoreCase(role)); // title/date lines
            boolean starts = marked ? isBullet : !meta && t.length() >= 25 && (cur == null || !Character.isLowerCase(t.charAt(0)));
            if (starts) {
                if (cur != null) out.add(cur.toString());
                cur = new StringBuilder(t);
            } else if (cur != null && !meta) cur.append(' ').append(t); // a wrapped line
        }
        if (cur != null) out.add(cur.toString());
        return out.stream().map(x -> cut(x, 600)).limit(15).toList();
    }

    private static final String D = "(\\d{1,2}/\\d{4}|[A-Za-z]{3,9}\\.? \\d{4}|\\d{4})";
    private static final Pattern RANGE = Pattern.compile("(?i)\\b" + D + "\\s*(?:[–—-]+|to)\\s*(" + D.substring(1, D.length() - 1) + "|present|current|now|till date)\\b");

    /** Date ranges written in the resume, in text order: {start, end or null for "Present"}. */
    static List<LocalDate[]> ranges(String text) {
        List<LocalDate[]> out = new ArrayList<>();
        Matcher m = RANGE.matcher(text);
        while (m.find()) {
            LocalDate s = date(m.group(1), false);
            if (s != null) out.add(new LocalDate[]{s, date(m.group(2), true)});
        }
        return out;
    }

    private static int words(String s) { return s.strip().split("\\s+").length; }

    /** One small model call per job (a 3B model skips keys in one big extraction): "what you did", from that job's lines only. */
    static String jobSummary(Function<String, String> llmJson, String role, String company, List<String> bullets) {
        String raw = llmJson.apply("""
                Summarise what this person did as %s at %s in ONE sentence of at most 30 words, written without "I". Pick the 2 or 3 most important things; do not list everything.
                Use only the facts below. Copy numbers exactly. Do not add tools, skills or results that aren't listed.
                Return JSON: {"summary": str}

                %s
                """.formatted(role, company, String.join("\n", bullets.stream().map(b -> "- " + b).toList())));
        if (raw == null) return null;
        String s;
        try { s = str(JSON.readTree(raw), "summary"); } catch (RuntimeException e) { return null; }
        return s != null && words(s) <= 40 ? s : null; // a 3B model often just joins every bullet
    }

    /** For resumes without a summary section: 2-3 sentences from the extracted facts, checked against the resume. */
    static String profileSummary(Function<String, String> llmJson, String title, BigDecimal years, List<String> skills,
                                 List<Experience> exps, String resume) {
        String facts = "Title: " + title + "\nYears of experience: " + (years == null ? "unknown" : years.toPlainString())
                + "\nSkills: " + String.join(", ", skills.stream().limit(15).toList()) + "\nJobs:\n"
                + String.join("\n", exps.stream().map(e -> "- " + e.title() + " at " + e.company() + ": "
                        + (e.description() == null ? "" : cut(e.description().replace("\n", " "), 600))).toList());
        String raw = llmJson.apply("""
                Write a professional summary of 2 or 3 sentences (at most 60 words) for the top of this person's profile, written without "I". Mention the 4 or 5 most relevant skills, not all of them.
                Use only the facts below. Copy numbers exactly. Do not add tools, skills, employers or results that aren't listed.
                Return JSON: {"summary": str}

                %s
                """.formatted(facts));
        if (raw == null) return null;
        String s;
        try { s = str(JSON.readTree(raw), "summary"); } catch (RuntimeException e) { return null; }
        String known = resume + "\n" + (years == null ? "" : years.toPlainString()); // the computed years are a fact too
        return s != null && words(s) <= 75 && jobSummaryOk(s, known, known) ? cut(s, 5000) : null;
    }

    /** A job summary may only use numbers from the resume and skills named in that job's own lines. */
    static boolean jobSummaryOk(String summary, String jobText, String resume) {
        Set<String> known = FactCheck.numbers(resume);
        for (String n : FactCheck.numbers(summary)) if (!known.contains(n)) return false;
        if (!new HashSet<>(JdParser.parse(null, jobText).skills()).containsAll(JdParser.parse(null, summary).skills())) return false;
        return share(summary, jobText) >= 0.5;
    }

    /** Share of the value's longer words that appear in the text. */
    private static double share(String value, String text) {
        String t = text.toLowerCase(Locale.ROOT);
        List<String> words = Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^a-z0-9+#]+")).filter(w -> w.length() >= 4).toList();
        if (words.isEmpty()) return 1;
        return words.stream().filter(t::contains).count() / (double) words.size();
    }

    /** The value only if the resume contains it (case-insensitive). */
    static String inText(String value, String lowerText) {
        return value != null && !value.isBlank() && lowerText.contains(value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")) ? value.strip() : null;
    }

    private static final Map<String, Integer> MONTHS = new HashMap<>();
    static {
        String[] m = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
        for (int i = 0; i < 12; i++) MONTHS.put(m[i], i + 1);
    }

    /** "2022-03", "03/2022", "Mar 2022", "2022" (endOfPeriod: last day for an end date). "Present" and blanks are null. */
    static LocalDate date(String s, boolean endOfPeriod) {
        if (s == null) return null;
        String v = s.strip().toLowerCase(Locale.ROOT);
        Matcher m;
        YearMonth ym = null;
        if ((m = Pattern.compile("^(\\d{4})-(\\d{1,2})").matcher(v)).find()) ym = ym(m.group(1), m.group(2));
        else if ((m = Pattern.compile("^(\\d{1,2})/(\\d{4})").matcher(v)).find()) ym = ym(m.group(2), m.group(1));
        else if ((m = Pattern.compile("^([a-z]{3})[a-z]*\\.?\\s+(\\d{4})").matcher(v)).find() && MONTHS.containsKey(m.group(1)))
            ym = ym(m.group(2), String.valueOf(MONTHS.get(m.group(1))));
        else if ((m = Pattern.compile("^(\\d{4})$").matcher(v)).find()) ym = ym(m.group(1), endOfPeriod ? "12" : "1");
        if (ym == null || ym.getYear() < 1960 || ym.isAfter(YearMonth.now())) return null;
        return endOfPeriod ? ym.atEndOfMonth() : ym.atDay(1);
    }

    private static YearMonth ym(String y, String mo) {
        int month = Integer.parseInt(mo);
        return month < 1 || month > 12 ? null : YearMonth.of(Integer.parseInt(y), month);
    }

    /** Total time in jobs, overlapping jobs counted once, to the nearest half year. */
    static BigDecimal years(List<Experience> exps) {
        if (exps.isEmpty()) return null;
        List<LocalDate[]> spans = exps.stream().map(e -> new LocalDate[]{e.startDate(), e.endDate() == null ? LocalDate.now() : e.endDate()})
                .sorted(Comparator.comparing(s -> s[0])).toList();
        long months = 0;
        LocalDate curStart = spans.getFirst()[0], curEnd = spans.getFirst()[1];
        for (LocalDate[] s : spans.subList(1, spans.size())) {
            if (!s[0].isAfter(curEnd)) { if (s[1].isAfter(curEnd)) curEnd = s[1]; }
            else { months += ChronoUnit.MONTHS.between(curStart, curEnd); curStart = s[0]; curEnd = s[1]; }
        }
        months += ChronoUnit.MONTHS.between(curStart, curEnd);
        return BigDecimal.valueOf(Math.round(months / 6.0) / 2.0).setScale(1, RoundingMode.UNNECESSARY).stripTrailingZeros();
    }

    private static Integer year(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("(19[6-9]\\d|20\\d\\d)").matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String str(JsonNode n, String k) {
        JsonNode v = n.path(k);
        if (v.isMissingNode() || v.isNull() || v.isObject() || v.isArray()) return null;
        String s = v.asString("").strip();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static String first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(m.groupCount() > 0 ? 1 : 0).strip() : null;
    }

    private static String or(String keep, String fill) { return keep != null && !keep.isBlank() ? keep : fill; }
    private static String cut(String s, int n) { return s == null ? null : s.length() > n ? s.substring(0, n) : s; }
}
