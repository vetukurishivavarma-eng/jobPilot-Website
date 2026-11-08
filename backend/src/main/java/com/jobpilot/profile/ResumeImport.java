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
 * Fills a profile from resume text. Contact details, links and skills come from exact patterns; jobs, education,
 * title, location and summary from the local LLM, and every LLM value must be traceable to the resume text
 * (a company not in the text, or a bullet whose words or numbers aren't there, is dropped). Years of experience are
 * computed from the job dates. Nothing is saved here: the result is a draft the user reviews.
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

        String lower = text.toLowerCase(Locale.ROOT);
        String title = inText(str(j, "currentTitle"), lower);
        String location = inText(str(j, "location"), lower);
        String headline = grounded(str(j, "headline"), text) ? cut(str(j, "headline"), 200) : null;
        String summary = grounded(str(j, "summary"), text) ? cut(str(j, "summary"), 5000) : null;

        List<Experience> exps = new ArrayList<>();
        int droppedBullets = 0;
        for (JsonNode e : j.path("experiences")) {
            String company = inText(str(e, "company"), lower), role = str(e, "title");
            LocalDate start = date(str(e, "startDate"), false);
            if (company == null || role == null || start == null) {
                if (str(e, "company") != null) notes.add("Skipped a job at \"" + str(e, "company") + "\": it couldn't be matched to your resume text or had no start date.");
                continue;
            }
            if (!FactCheck.sameClaim(role, text)) { notes.add("Skipped \"" + role + "\" at " + company + ": that title isn't in your resume."); continue; }
            LocalDate end = date(str(e, "endDate"), true);
            List<String> bullets = new ArrayList<>();
            for (JsonNode b : e.path("bullets")) {
                String s = b.asString("").strip();
                if (s.isEmpty()) continue;
                if (grounded(s, text)) bullets.add(s); else droppedBullets++;
            }
            exps.add(new Experience(cut(company, 160), cut(role, 160), cut(inText(str(e, "location"), lower), 120), start,
                    end != null && end.isBefore(start) ? null : end, cut(String.join("\n", bullets), 5000)));
        }
        if (droppedBullets > 0) notes.add(droppedBullets + " achievement line(s) were left out because they didn't match your resume's wording. Add them yourself if they're missing.");

        List<Education> edus = new ArrayList<>();
        for (JsonNode e : j.path("educations")) {
            String inst = inText(str(e, "institution"), lower), degree = str(e, "degree");
            if (inst == null || degree == null || !FactCheck.sameClaim(degree, text)) continue;
            edus.add(new Education(cut(inst, 200), cut(degree, 120), cut(inText(str(e, "field"), lower), 120),
                    year(str(e, "startYear")), year(str(e, "endYear")), cut(inText(str(e, "grade"), lower), 40)));
        }

        BigDecimal years = years(exps);
        ProfileDto c = current;
        List<String> mergedSkills = new ArrayList<>(c.skills());
        for (String s : skills) if (mergedSkills.stream().noneMatch(x -> x.equalsIgnoreCase(s))) mergedSkills.add(s);
        String currentTitle = or(c.currentTitle(), title != null ? cut(title, 120)
                : exps.stream().filter(e -> e.endDate() == null).map(Experience::title).findFirst().orElse(null));
        String loc = or(c.location(), cut(location, 120));

        ProfileDto draft = new ProfileDto(
                or(c.headline(), headline), or(c.phone(), cut(phone, 30)), loc, currentTitle,
                c.yearsExperience() != null ? c.yearsExperience() : years,
                c.expectedSalary(), c.salaryCurrency(), c.noticePeriodDays(), c.workAuthorization(), c.willingToRelocate(),
                or(c.linkedinUrl(), cut(linkedin, 300)), or(c.githubUrl(), cut(github, 300)), or(c.portfolioUrl(), cut(portfolio, 300)),
                or(c.summary(), summary),
                mergedSkills.stream().limit(100).toList(),
                // suggestions so matching works straight away; the user edits them
                !c.desiredRoles().isEmpty() || currentTitle == null ? c.desiredRoles() : List.of(currentTitle),
                !c.desiredLocations().isEmpty() || loc == null ? c.desiredLocations() : List.of(loc.split(",")[0].strip()),
                c.experiences().isEmpty() ? exps.stream().limit(30).toList() : c.experiences(),
                c.educations().isEmpty() ? edus.stream().limit(15).toList() : c.educations(),
                c.updatedAt());
        return new Result(draft, notes);
    }

    static String prompt(String text) {
        return """
                Extract this resume into JSON with exactly these keys:
                {"currentTitle": str|null, "headline": str|null, "location": str|null, "summary": str|null,
                 "experiences": [{"company": str, "title": str, "location": str|null, "startDate": "YYYY-MM", "endDate": "YYYY-MM"|null,
                                  "bullets": [str]}],
                 "educations": [{"institution": str, "degree": str, "field": str|null, "startYear": int|null, "endYear": int|null, "grade": str|null}]}
                Rules: copy text exactly as written in the resume, do not rephrase or invent anything. Use null when something is missing.
                endDate is null for a current job ("Present"). Each bullet is one achievement line copied word for word.
                List jobs newest first. "summary" is the resume's own summary/objective paragraph, copied, or null.

                RESUME:
                %s
                """.formatted(text.length() > 12000 ? text.substring(0, 12000) : text);
    }

    /** The value's words and numbers must come from the resume (a copy, maybe trimmed, not an invention). */
    static boolean grounded(String value, String text) {
        if (value == null || value.isBlank()) return false;
        Set<String> known = FactCheck.numbers(text);
        for (String n : FactCheck.numbers(value)) if (!known.contains(n)) return false;
        return FactCheck.sameClaim(value, text) && share(value, text) >= 0.6;
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
        return value != null && !value.isBlank() && lowerText.contains(value.strip().toLowerCase(Locale.ROOT)) ? value.strip() : null;
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
