package com.jobpilot.apply;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** An answer the user gave once, reused when the same question appears on another form. */
@Entity
@Table(name = "answer_bank")
public class AnswerBank {
    @Id
    UUID id = UUID.randomUUID();
    UUID userId;
    String questionKey;
    String question;
    String answer;
    Instant updatedAt = Instant.now();

    protected AnswerBank() {}

    AnswerBank(UUID userId, String questionKey, String question, String answer) {
        this.userId = userId;
        this.questionKey = questionKey;
        this.question = question;
        this.answer = answer;
    }

    /** Same question on different forms: lowercase, punctuation dropped, the company's name replaced. */
    static String key(String question, String company) {
        String q = question.toLowerCase();
        if (company != null && !company.isBlank()) q = q.replace(company.toLowerCase(), " the company ");
        q = q.replaceAll("[^a-z0-9]+", " ").strip();
        return q.length() > 300 ? q.substring(0, 300) : q;
    }
}
