package com.jobpilot.job;

import java.util.*;

/**
 * Does a job title fit one of the user's desired roles? Token-subset match after normalising synonyms,
 * so "Backend Developer" matches "Senior Software Engineer - Backend". Seniority words in the role are ignored.
 */
final class RoleMatch {
    private RoleMatch() {}

    private static final Set<String> SENIORITY = Set.of("senior", "sr", "junior", "jr", "lead", "staff", "principal",
            "i", "ii", "iii", "iv", "1", "2", "3", "intern", "associate", "entry", "level", "mid", "trainee", "fresher");
    private static final Map<String, String> SYNONYM = Map.of("developer", "engineer", "dev", "engineer", "programmer", "engineer",
            "swe", "software engineer", "sde", "software engineer");

    static boolean matches(String title, List<String> roles) {
        if (roles == null || roles.isEmpty()) return true;
        Set<String> t = tokens(title);
        for (String role : roles) {
            Set<String> r = tokens(role);
            r.removeAll(SENIORITY);
            if (!r.isEmpty() && t.containsAll(r)) return true;
        }
        return false;
    }

    static Set<String> tokens(String s) {
        String x = " " + (s == null ? "" : s.toLowerCase()).replaceAll("[^a-z0-9+#]+", " ") + " ";
        x = x.replace(" front end ", " frontend ").replace(" back end ", " backend ").replace(" full stack ", " fullstack ");
        Set<String> out = new HashSet<>();
        for (String w : x.trim().split(" +")) {
            if (w.isEmpty()) continue;
            out.addAll(List.of(SYNONYM.getOrDefault(w, w).split(" ")));
        }
        return out;
    }
}
