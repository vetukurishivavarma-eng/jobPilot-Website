package com.jobpilot.apply;

import com.jobpilot.profile.Profile.Experience;
import com.jobpilot.profile.ProfileController.ProfileDto;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills a form's answers, in this order: facts from the profile, answers the user gave before (answer bank),
 * then the local LLM for required open-ended text boxes only. Anything else stays empty for the user.
 * It never makes up a fact: short factual fields (CTC, dates, yes/no) are never sent to the LLM.
 */
final class Answerer {
    private Answerer() {}

    record Candidate(String fullName, String email, ProfileDto profile, String resumeId, String resumeLabel, String resumeText) {}
    record JobInfo(String title, String company, String description) {}

    static final String NEED_INPUT = "NEED_INPUT";

    static List<FormField> answer(List<FormField> fields, Candidate c, JobInfo job, Map<String, String> bank, Function<String, String> ai) {
        List<FormField> out = new ArrayList<>();
        for (FormField f : fields) {
            String v = fromProfile(f, c);
            if (v != null) { out.add(f.answered(v, "PROFILE")); continue; }
            v = valid(f, bank.get(AnswerBank.key(f.label(), job.company())));
            if (v != null) { out.add(f.answered(v, "BANK")); continue; }
            if (ai != null && f.type().equals("TEXTAREA") && (f.required() || f.label().toLowerCase().contains("cover"))) {
                String a = ai.apply(prompt(f, c, job));
                if (a != null && !a.isBlank() && !a.contains(NEED_INPUT)) { out.add(f.answered(a.strip(), "AI")); continue; }
            }
            out.add(f.answered(null, null));
        }
        return out;
    }

    /** An answer is only usable if it fits the field: selects must name one of their options. */
    static String valid(FormField f, String v) {
        if (v == null || v.isBlank()) return null;
        if (f.type().equals("SELECT")) return option(f.options(), v);
        if (f.type().equals("MULTISELECT")) {
            List<String> picked = new ArrayList<>();
            for (String part : v.split(",")) { String o = option(f.options(), part.strip()); if (o != null) picked.add(o); }
            return picked.isEmpty() ? null : String.join(", ", picked);
        }
        return v;
    }

    static String fromProfile(FormField f, Candidate c) {
        String l = f.label().toLowerCase(Locale.ROOT), k = f.key().toLowerCase(Locale.ROOT);
        ProfileDto p = c.profile();
        String[] names = c.fullName() == null ? new String[0] : c.fullName().strip().split("\\s+", 2);

        if (f.type().equals("FILE"))
            return (k.contains("resume") || l.contains("resume") || l.contains("cv")) && c.resumeId() != null ? c.resumeLabel() : null;
        if (k.equals("first_name") || l.equals("first name")) return names.length > 0 ? names[0] : null;
        if (k.equals("last_name") || l.equals("last name")) return names.length > 1 ? names[1] : null;
        if (k.equals("name") || l.equals("full name") || l.equals("name")) return c.fullName();
        if (k.equals("email") || l.equals("email") || l.equals("email address")) return c.email();
        if (k.equals("phone") || l.matches("(mobile|phone)( number)?")) return p.phone();
        if (k.contains("linkedin") || l.contains("linkedin")) return p.linkedinUrl();
        if (k.contains("github") || l.contains("github")) return p.githubUrl();
        if (k.contains("portfolio") || l.matches(".*\\b(portfolio|personal website|website)\\b.*")) return p.portfolioUrl();
        if (k.equals("org") || l.matches("current (company|employer|organi[sz]ation)( name)?")) return currentJob(p).map(Experience::company).orElse(null);
        if (l.matches("current (job )?(title|role|designation)")) return p.currentTitle();
        if (f.type().equals("TEXT") && (k.equals("location") || l.matches("(current )?(location|city)"))) return p.location();

        if (f.type().equals("TEXT") && l.matches(".*\\b(years?|yrs)\\b.*\\b(experience|exp)\\b.*|.*\\b(experience|exp)\\b.*\\b(years?|yrs)\\b.*") && p.yearsExperience() != null)
            return plain(p.yearsExperience());
        if (l.contains("notice period") && p.noticePeriodDays() != null)
            return f.type().equals("SELECT") ? noticeOption(f.options(), p.noticePeriodDays()) : String.valueOf(p.noticePeriodDays());
        if (f.type().equals("TEXT") && l.matches(".*expected.*(ctc|compensation|salary).*") && p.expectedSalary() != null)
            return String.valueOf(p.expectedSalary());
        if (f.type().equals("SELECT") && l.matches(".*(willing|open) to relocat.*"))
            return option(f.options(), p.willingToRelocate() ? "Yes" : "No");
        return null;
    }

    static Optional<Experience> currentJob(ProfileDto p) {
        return p.experiences().stream().filter(e -> e.endDate() == null).findFirst();
    }

    /** Case-insensitive option match; "yes"/"no" also match options like "Yes, I do". */
    static String option(List<String> options, String want) {
        for (String o : options) if (o.equalsIgnoreCase(want.strip())) return o;
        String w = want.strip().toLowerCase(Locale.ROOT);
        if (w.equals("yes") || w.equals("no"))
            for (String o : options) if (o.toLowerCase(Locale.ROOT).matches(w + "\\b.*")) return o;
        return null;
    }

    private static final Pattern NUM = Pattern.compile("(\\d+)");

    /** Picks the option covering the notice period: "15-30 days" contains 30; otherwise the nearest one. */
    static String noticeOption(List<String> options, int days) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String o : options) {
            String lo = o.toLowerCase(Locale.ROOT);
            int unit = lo.contains("month") ? 30 : lo.contains("week") ? 7 : 1;
            List<Integer> n = new ArrayList<>();
            Matcher m = NUM.matcher(lo);
            while (m.find()) n.add(Integer.parseInt(m.group(1)) * unit);
            if (lo.contains("immediate")) n.add(0);
            if (n.isEmpty()) continue;
            if (n.size() >= 2 && days >= Collections.min(n) && days <= Collections.max(n)) return o;
            int d = n.stream().mapToInt(x -> Math.abs(x - days)).min().orElse(Integer.MAX_VALUE);
            if (d < bestDist) { bestDist = d; best = o; }
        }
        return best;
    }

    static String plain(BigDecimal b) {
        return b.stripTrailingZeros().toPlainString();
    }

    static String prompt(FormField f, Candidate c, JobInfo job) {
        ProfileDto p = c.profile();
        StringBuilder facts = new StringBuilder();
        if (p.headline() != null) facts.append("Headline: ").append(p.headline()).append('\n');
        if (p.currentTitle() != null) facts.append("Current title: ").append(p.currentTitle()).append('\n');
        if (p.yearsExperience() != null) facts.append("Years of experience: ").append(plain(p.yearsExperience())).append('\n');
        if (!p.skills().isEmpty()) facts.append("Skills: ").append(String.join(", ", p.skills())).append('\n');
        for (Experience e : p.experiences())
            facts.append("Worked as ").append(e.title()).append(" at ").append(e.company()).append(" from ").append(e.startDate())
                    .append(e.endDate() == null ? " to now" : " to " + e.endDate()).append(e.description() == null ? "" : ": " + e.description()).append('\n');
        if (p.summary() != null) facts.append("Summary: ").append(p.summary()).append('\n');
        String resume = c.resumeText() == null ? "" : c.resumeText().substring(0, Math.min(6000, c.resumeText().length()));
        String jd = job.description() == null ? "" : job.description().substring(0, Math.min(3000, job.description().length()));
        return """
                You are helping %s answer one question on a job application for "%s" at %s.
                Write the answer in first person, plain text, 60 to 150 words, no greeting or sign-off.
                Use ONLY facts stated in the candidate facts or resume below. Never invent employers, numbers, degrees, dates or skills.
                If these facts are not enough to answer honestly, reply with exactly: %s

                QUESTION: %s

                CANDIDATE FACTS:
                %s
                RESUME:
                %s

                JOB DESCRIPTION:
                %s
                """.formatted(c.fullName(), job.title(), job.company(), NEED_INPUT, f.label(), facts, resume, jd);
    }
}
