package com.jobpilot.outreach;

import com.jobpilot.tailor.Tailor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.function.Function;

/**
 * Drafts a short outreach message to the recruiter / hiring manager for a matched job: a LinkedIn
 * connection note and an email. Facts only — the candidate's own name, title and skills, the job's title
 * and company — never invented achievements. Template fallback so it works with the AI off. The user sends
 * it themselves; JobPilot never messages anyone or scrapes contacts.
 */
public final class OutreachMessage {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Draft(String dm, String subject, String body, String source) {}

    private OutreachMessage() {}

    public static Draft draft(Tailor.Facts f, Tailor.JobInfo job, Function<String, String> llmJson) {
        if (llmJson != null) {
            try {
                JsonNode r = JSON.readTree(llmJson.apply(prompt(f, job)));
                String dm = text(r, "dm"), subject = text(r, "subject"), body = text(r, "body");
                if (!dm.isEmpty() && !subject.isEmpty() && !body.isEmpty()) return new Draft(dm, subject, body, "AI");
            } catch (Exception ignore) { /* fall through to template */ }
        }
        return template(f, job);
    }

    static String prompt(Tailor.Facts f, Tailor.JobInfo job) {
        return """
                Write outreach from a job applicant to the recruiter or hiring manager for the role below.
                Rules:
                - Facts only, taken from the candidate; never invent metrics, employers, titles or results.
                - Warm, specific, no flattery or clichés. Sign with the candidate's name; no placeholders like [Name] or [Company].
                - dm: a LinkedIn connection note, at most 60 words, one short paragraph.
                - subject: an email subject line.
                - body: the email, at most 130 words — a greeting, one line on why this role, one or two relevant strengths, a request for a short chat, then the sign-off.
                Reply as JSON {"dm": "...", "subject": "...", "body": "..."} and nothing else.

                Candidate: %s
                Top skills: %s
                Role: %s
                Job description: %s
                """.formatted(
                candidate(f), String.join(", ", top(f, 6)), roleLine(job),
                job.description() == null ? "(none — use the role title and the candidate's skills)" : cut(job.description(), 1500));
    }

    private static Draft template(Tailor.Facts f, Tailor.JobInfo job) {
        String name = f.name() == null ? "" : f.name();
        String role = job.title() == null ? "the role" : "the " + job.title() + " role";
        String at = job.company() == null ? "" : " at " + job.company();
        String skills = String.join(", ", top(f, 3));
        String strength = skills.isEmpty() ? "my background" : "my experience with " + skills;
        String dm = "Hi, I came across " + role + at + " and I'm genuinely interested — " + strength
                + " lines up well with it. I'd love to connect and share how I could contribute. — " + name;
        String body = "Hi,\n\nI'm reaching out about " + role + at + ". It's a strong match for " + strength
                + ", and I'd welcome the chance to contribute to your team.\n\n"
                + "Would you be open to a short chat? I'm happy to work around your schedule.\n\nThank you,\n" + name;
        return new Draft(dm, "Interested in " + (job.title() == null ? "the role" : job.title()) + at, body, "TEMPLATE");
    }

    private static String candidate(Tailor.Facts f) {
        String who = f.name() == null ? "an applicant" : f.name();
        String title = f.headline() != null ? f.headline() : f.currentTitle();
        if (title != null) who += ", " + title;
        if (f.years() != null && !f.years().isBlank()) who += " (" + f.years() + " yrs)";
        return who;
    }

    private static List<String> top(Tailor.Facts f, int n) {
        return f.allSkills().stream().limit(n).toList();
    }

    private static String roleLine(Tailor.JobInfo job) {
        return job.company() == null ? job.title() : job.title() + " at " + job.company();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String text(JsonNode n, String field) {
        return n.path(field).asString("").strip();
    }
}
