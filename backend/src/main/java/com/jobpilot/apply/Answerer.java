package com.jobpilot.apply;

import com.jobpilot.profile.Profile.Education;
import com.jobpilot.profile.Profile.Experience;
import com.jobpilot.profile.ProfileController.ProfileDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
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
    /** @param coverLetter the user's cover letter for this job, if they wrote one */
    record JobInfo(String title, String company, String description, String coverLetter) {
        JobInfo(String title, String company, String description) { this(title, company, description, null); }
    }

    static final String NEED_INPUT = "NEED_INPUT";

    static List<FormField> answer(List<FormField> fields, Candidate c, JobInfo job, Map<String, String> bank, Function<String, String> ai) {
        List<FormField> out = new ArrayList<>();
        for (FormField f : fields) {
            if (job.coverLetter() != null && f.type().equals("TEXTAREA") && f.label().toLowerCase().contains("cover")) {
                out.add(f.answered(job.coverLetter(), "LETTER"));
                continue;
            }
            String v = valid(f, rule(f, c, job));
            if (v == null) v = valid(f, fromProfile(f, c));
            if (v != null) { out.add(f.answered(v, "PROFILE")); continue; }
            v = valid(f, bank.get(AnswerBank.key(f.label(), job.company())));
            if (v != null) { out.add(f.answered(v, "BANK")); continue; }
            // a question with a single choice (often a consent tick) has only one possible answer
            if ((f.type().equals("SELECT") || f.type().equals("MULTISELECT")) && f.options().size() == 1) { out.add(f.answered(f.options().getFirst(), "PROFILE")); continue; }
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
        if (p.expectedSalary() != null && p.expectedSalary() > 0 && l.matches(".*\\b(salary|ctc|compensation|pay|package|remuneration)\\b.*")
                && !l.matches(".*\\b(current|present|last|previous|existing)\\b.*"))
            return salary(f, p.expectedSalary());
        if (f.type().equals("SELECT") && l.matches(".*(willing|open) to relocat.*"))
            return option(f.options(), p.willingToRelocate() ? "Yes" : "No");
        if (f.type().equals("SELECT") && l.startsWith("what is your location") && p.location() != null) return country(f.options(), p.location());
        return null;
    }

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Standard screening questions answered from the profile without asking the user:
     * 18+ (from the date of birth), work authorization / visa sponsorship (profile checkbox, default authorized),
     * how did you hear (careers page), currently employed, worked here before, highest degree, graduation year,
     * and optional EEO questions (declined).
     */
    static String rule(FormField f, Candidate c, JobInfo job) {
        String l = f.label().toLowerCase(Locale.ROOT);
        ProfileDto p = c.profile();
        boolean choice = f.type().equals("SELECT") || f.type().equals("MULTISELECT");

        if (l.matches(".*\\b(18|eighteen)\\b.*") && l.matches(".*\\b(age|years?|older|old)\\b.*"))
            return p.dateOfBirth() == null ? null : yesNo(f, Period.between(p.dateOfBirth(), LocalDate.now(IST)).getYears() >= 18);
        if (l.matches(".*\\bsponsor.*"))
            return yesNo(f, Boolean.FALSE.equals(p.authorizedToWork())); // not set = authorized (the default)
        if (l.matches(".*\\b(authori[sz]ed|eligible|permitted|entitled|right) to work\\b.*"))
            return yesNo(f, !Boolean.FALSE.equals(p.authorizedToWork()));
        if (l.matches(".*how did you (hear|find|learn|come to know|come across)\\b.*|.*where did you (hear|find|see)\\b.*")) {
            if (!choice) return "Company careers page";
            for (String o : f.options()) if (o.toLowerCase(Locale.ROOT).matches(".*\\b(careers?|company (web)?site|website)\\b.*")) return o;
            for (String o : f.options()) if (o.toLowerCase(Locale.ROOT).matches(".*\\b(job board|job portal|online|internet)\\b.*")) return o;
            return null;
        }
        if (l.matches(".*\\b(are you|currently) (currently )?employed\\b.*"))
            return yesNo(f, currentJob(p).isPresent());
        // "worked FOR us / this company / <company> before", never "worked with Java before"
        if (job.company() != null && (l.contains("former employee") || l.matches(".*\\b(previously|ever|formerly|before)\\b.*")
                && l.matches(".*\\b(work|worked|employed)\\b.*\\b(for|at|by)\\b.*")
                && (l.matches(".*\\b(us|this company|our company|organi[sz]ation)\\b.*") || l.contains(job.company().toLowerCase(Locale.ROOT)))))
            return yesNo(f, p.experiences().stream().anyMatch(e -> sameCompany(e.company(), job.company())));
        if (l.matches(".*\\bhighest\\b.*\\b(degree|qualification|education)\\b.*|.*\\blevel of (education|qualification)\\b.*"))
            return highestDegree(f, p);
        if (l.matches(".*\\b(graduation|passing|passed out|pass ?out) year\\b.*|.*\\byear of (graduation|passing)\\b.*"))
            return p.educations().stream().map(Education::endYear).filter(Objects::nonNull).max(Integer::compare).map(String::valueOf).orElse(null);
        if (choice && l.matches(".*\\b(gender|race|ethnicity|ethnic|veteran|disability|disabled|hispanic|latino|sexual orientation)\\b.*"))
            for (String o : f.options()) if (o.toLowerCase(Locale.ROOT).matches(".*\\b(decline|prefer not|don'?t wish|do not wish|choose not|not to (say|disclose|answer))\\b.*")) return o;
        return null;
    }

    private static String yesNo(FormField f, boolean yes) {
        if (!f.type().equals("SELECT") && !f.type().equals("MULTISELECT")) return yes ? "Yes" : "No";
        return option(f.options(), yes ? "Yes" : "No");
    }

    private static final Pattern COMPANY_NOISE = Pattern.compile("\\b(pvt|private|ltd|limited|inc|llp|llc|corp|corporation|technologies|india)\\b|[^a-z0-9 ]");

    static boolean sameCompany(String a, String b) {
        String x = COMPANY_NOISE.matcher(a.toLowerCase(Locale.ROOT)).replaceAll(" ").strip().replaceAll("\\s+", " ");
        String y = COMPANY_NOISE.matcher(b.toLowerCase(Locale.ROOT)).replaceAll(" ").strip().replaceAll("\\s+", " ");
        return !x.isEmpty() && !y.isEmpty() && (x.equals(y) || x.startsWith(y + " ") || y.startsWith(x + " "));
    }

    /** Degree levels, highest first: {how a profile degree is written, how a form option names that level}. */
    private static final List<String[]> LEVELS = List.of(
            new String[]{"(ph\\.?d|doctor)", "(ph\\.?d|doctor)"},
            new String[]{"(m\\.?tech|m\\.?e\\b|m\\.?s\\b|m\\.?sc|mba|pgdm|mca|m\\.?com|m\\.?a\\b|master|post ?grad)", "(master|post ?grad|pg\\b)"},
            new String[]{"(b\\.?tech|b\\.?e\\b|b\\.?sc|bca|bba|b\\.?com|b\\.?a\\b|bachelor|graduat|undergrad)", "(bachelor|graduat|undergrad|ug\\b)"},
            new String[]{"(diploma|polytechnic)", "(diploma)"},
            new String[]{"(12th|xii|intermediate|higher secondary|hsc|puc)", "(12|high school|higher secondary|intermediate)"});

    static String highestDegree(FormField f, ProfileDto p) {
        for (String[] lvl : LEVELS) {
            Pattern has = Pattern.compile("(?i)\\b" + lvl[0]);
            Optional<Education> e = p.educations().stream().filter(x -> has.matcher(x.degree()).find()).findFirst();
            if (e.isEmpty()) continue;
            if (!f.type().equals("SELECT")) return e.get().degree();
            Pattern opt = Pattern.compile("(?i)\\b" + lvl[1]);
            for (String o : f.options()) if (opt.matcher(o).find()) return o;
            return null;
        }
        return null;
    }

    private static final Pattern DEC = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    /**
     * Expected salary (stored as yearly rupees) in the form's unit: lakhs per annum when the label or options say
     * LPA/lakh, monthly when they say month, else as is. Dropdowns get the range that holds it (or the nearest).
     */
    static String salary(FormField f, long yearly) {
        String all = (f.label() + " " + String.join(" ", f.options())).toLowerCase(Locale.ROOT);
        double v = yearly < 1000 ? yearly * 100000.0 : yearly; // someone typed 8 meaning 8 LPA
        if (all.matches("(?s).*\\b(lpa|lakhs?|lacs?)\\b.*")) v /= 100000;
        else if (all.matches("(?s).*\\b(month|monthly|per month)\\b.*")) v /= 12;
        if (!f.type().equals("SELECT")) return plain(BigDecimal.valueOf(Math.round(v * 10) / 10.0));
        String best = null;
        double bestDist = Double.MAX_VALUE;
        for (String o : f.options()) {
            List<Double> n = new ArrayList<>();
            Matcher m = DEC.matcher(o.replace(",", ""));
            while (m.find()) n.add(Double.parseDouble(m.group(1)));
            if (n.isEmpty()) continue;
            double lo = Collections.min(n), hi = Collections.max(n);
            if (n.size() >= 2 && v >= lo && v <= hi) return o;
            if (n.size() == 1 && o.toLowerCase(Locale.ROOT).matches(".*(above|more than|\\+|or more).*") && v >= lo) return o;
            double d = Math.min(Math.abs(v - lo), Math.abs(v - hi));
            if (d < bestDist) { bestDist = d; best = o; }
        }
        return best;
    }

    private static final Pattern INDIAN_CITY = Pattern.compile("\\b(india|bengaluru|bangalore|mumbai|bombay|delhi|new delhi|noida|gurgaon|gurugram|"
            + "hyderabad|secunderabad|chennai|pune|kolkata|ahmedabad|jaipur|kochi|cochin|chandigarh|indore|coimbatore|vizag|visakhapatnam|"
            + "lucknow|nagpur|bhubaneswar|trivandrum|thiruvananthapuram|mysore|mysuru|vadodara|surat)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * A country picker from a location like "Mumbai" or "Austin, United States".
     * ponytail: Indian cities are recognised by name; elsewhere the location must name the country.
     */
    static String country(List<String> options, String location) {
        for (String o : options) if (Pattern.compile("\\b" + Pattern.quote(o) + "\\b", Pattern.CASE_INSENSITIVE).matcher(location).find()) return o;
        return INDIAN_CITY.matcher(location).find() ? option(options, "India") : null;
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
        // Order = most stable first (rules, then this candidate, then this job, then this question), so the
        // model's prompt cache (Ollama KV cache, cloud prefix caching) is reused across questions and jobs.
        return """
                You help a job seeker answer one question on a job application.
                Write the answer in first person, plain text, 60 to 150 words, no greeting or sign-off.
                Use ONLY facts stated in the candidate facts or resume below. Never invent employers, numbers, degrees, dates or skills.
                If these facts are not enough to answer honestly, reply with exactly: %s

                CANDIDATE: %s
                CANDIDATE FACTS:
                %s
                RESUME:
                %s

                JOB: "%s" at %s
                JOB DESCRIPTION:
                %s

                QUESTION: %s
                """.formatted(NEED_INPUT, c.fullName(), facts, resume, job.title(), job.company(), jd, f.label());
    }
}
