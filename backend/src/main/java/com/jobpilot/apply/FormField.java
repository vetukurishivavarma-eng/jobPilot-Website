package com.jobpilot.apply;

import java.util.List;

/**
 * One question on an application form plus the agent's answer.
 *
 * @param key     the form's own field name (what the browser fills)
 * @param type    TEXT | TEXTAREA | FILE | SELECT (one of options) | MULTISELECT (comma-separated options)
 * @param source  where the answer came from: PROFILE | BANK (answered by the user before) | AI | USER, or null if unanswered
 */
public record FormField(String key, String label, String type, boolean required, List<String> options, String value, String source) {
    FormField answered(String v, String src) { return new FormField(key, label, type, required, options, v, src); }

    boolean missing() { return required && (value == null || value.isBlank()); }
}
