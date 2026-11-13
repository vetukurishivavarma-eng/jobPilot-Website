package com.jobpilot.apply;

import com.jobpilot.tailor.Tailor;
import tools.jackson.databind.JsonNode;

import java.util.function.Function;

/**
 * Drafts a short, polite follow-up email for an application the user already submitted. Facts only: it uses the
 * candidate's own name and the job title/company, never invented details. Falls back to a plain template when no
 * model answers, so the feature works with the AI off.
 */
public final class FollowUpEmail {
    public record Email(String subject, String body, String source) {}

    private FollowUpEmail() {}

    static Email draft(Tailor.Facts f, Tailor.JobInfo job, int daysSince, Function<String, String> llmJson) {
        if (llmJson != null) {
            try {
                JsonNode r = FormReader.JSON.readTree(llmJson.apply(prompt(f, job, daysSince)));
                String subject = text(r, "subject"), body = text(r, "body");
                if (!subject.isEmpty() && !body.isEmpty()) return new Email(subject, body, "AI");
            } catch (Exception ignore) { /* fall through to template */ }
        }
        return template(f, job);
    }

    static String prompt(Tailor.Facts f, Tailor.JobInfo job, int daysSince) {
        String role = job.company() == null ? job.title() : job.title() + " at " + job.company();
        return """
                Write a short, polite follow-up email from a job applicant checking on their application.
                Rules:
                - At most 110 words, warm but professional, no flattery or clichés.
                - Reaffirm interest in the role in one line; do NOT invent facts, numbers, interviews or conversations.
                - Sign off with the candidate's name only. No placeholders like [Company] or [Name].
                Reply as JSON: {"subject": "...", "body": "..."} and nothing else.

                Candidate name: %s
                Role: %s
                Days since they applied: %d
                """.formatted(f.name() == null ? "the applicant" : f.name(), role, daysSince);
    }

    private static Email template(Tailor.Facts f, Tailor.JobInfo job) {
        String role = job.title() == null ? "the role" : "the " + job.title() + " role";
        String at = job.company() == null ? "" : " at " + job.company();
        String name = f.name() == null ? "" : f.name();
        String body = "Hi,\n\nI hope you're doing well. I'm writing to follow up on my application for " + role + at
                + ". I remain very interested in the opportunity and would welcome the chance to discuss how I can contribute.\n\n"
                + "Thank you for your time, and I look forward to hearing from you.\n\nBest regards,\n" + name;
        return new Email("Following up on my application for " + (job.title() == null ? "the role" : job.title()), body, "TEMPLATE");
    }

    private static String text(JsonNode n, String field) {
        return n.path(field).asString("").strip();
    }
}
