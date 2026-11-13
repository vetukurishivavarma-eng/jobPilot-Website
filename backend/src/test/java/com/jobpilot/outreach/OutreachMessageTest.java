package com.jobpilot.outreach;

import com.jobpilot.tailor.Tailor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OutreachMessageTest {

    private static Tailor.Facts facts() {
        return new Tailor.Facts("Asha Rao", "a@x.com", null, "Bengaluru", null, "Backend Engineer", "summary",
                "4", "Software Engineer", List.of("Java", "Spring Boot", "Kafka"), Set.of(), List.of(), List.of(), null);
    }

    private static Tailor.JobInfo job() {
        return new Tailor.JobInfo("Backend Engineer", "Razorpay", "Build payment systems with Java and Spring.", List.of("Java"));
    }

    @Test
    void templateUsesRealNamesNoPlaceholders() {
        OutreachMessage.Draft d = OutreachMessage.draft(facts(), job(), null); // no LLM -> template
        assertEquals("TEMPLATE", d.source());
        assertTrue(d.dm().contains("Razorpay") && d.dm().contains("Asha Rao"));
        assertTrue(d.subject().contains("Backend Engineer"));
        assertTrue(d.body().contains("Asha Rao"));
        for (String s : List.of(d.dm(), d.subject(), d.body()))
            assertFalse(s.contains("[") || s.contains("]"), "no placeholders: " + s);
    }

    @Test
    void usesAiJsonWhenItAnswers() {
        OutreachMessage.Draft d = OutreachMessage.draft(facts(), job(),
                p -> "{\"dm\":\"Quick hello about the role\",\"subject\":\"Backend role\",\"body\":\"Full email body here.\"}");
        assertEquals("AI", d.source());
        assertEquals("Quick hello about the role", d.dm());
        assertEquals("Full email body here.", d.body());
    }

    @Test
    void fallsBackToTemplateOnBadAiJson() {
        OutreachMessage.Draft d = OutreachMessage.draft(facts(), job(), p -> "not json at all");
        assertEquals("TEMPLATE", d.source());
    }
}
