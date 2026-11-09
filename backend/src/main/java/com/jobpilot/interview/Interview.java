package com.jobpilot.interview;

import com.jobpilot.job.JdParser;
import com.jobpilot.profile.Profile.Experience;
import com.jobpilot.tailor.Tailor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Open mock-interview coach: a practice question set per job or role, and scored feedback per typed answer.
 * One model call per question set and one per answer. Parsing is lenient (small models return messy JSON) and never throws.
 * The improved answer is fact-checked against the candidate's own facts, like {@link Tailor}.
 */
public final class Interview {
    private Interview() {}

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ADD_NUMBER = "[add your own number here]";

    public record Question(String type, String text) {}
    /** score = rounded mean of the rubric; rubric keys: relevance, specificity, technical, concision (1-5 each). */
    public record Feedback(Integer score, Map<String, Integer> rubric, List<String> improvements, String improvedAnswer, List<String> warnings) {}
    public record Item(String type, String question, String answer, Feedback feedback) {}
    /** source: AI (model wrote the questions) or TEMPLATE (model unavailable, standard questions from the job's skills). */
    public record Content(String source, List<Item> items) {}

    // ------------------------------------------------------------------ questions

    static Content questions(Tailor.Facts f, Tailor.JobInfo job, Function<String, String> llmJson) {
        List<Question> qs = llmJson == null ? List.of() : parseQuestions(llmJson.apply(questionsPrompt(f, job)));
        String source = "AI";
        if (qs.isEmpty()) { qs = template(f, job); source = "TEMPLATE"; }
        return new Content(source, qs.stream().map(q -> new Item(q.type(), q.text(), null, null)).toList());
    }

    static String questionsPrompt(Tailor.Facts f, Tailor.JobInfo job) {
        // fixed rules first, then the candidate (same for every job and question), then the job: the longest reusable
        // prefix for the model's prompt cache, and the variable part last
        return """
                You are an interview coach. Write a practice interview for the candidate below.
                Return JSON only: {"questions":[{"type":"behavioural|technical|role","q":"..."}]}
                Exactly 6 questions: 2 behavioural, 3 technical, 1 role.
                behavioural: a past situation ("Tell me about a time..."), fitting the candidate's experience.
                technical: about skills the job text asks for that the candidate also has; else the candidate's main skills.
                role: why this role or company, using only what the job text says. Never invent facts about the company.
                One sentence per question. No answers, no numbering.

                CANDIDATE:
                %s
                ROLE: %s
                JOB TEXT:
                %s
                """.formatted(profileText(f), roleLine(job), job.description() == null ? "(none, use the role and the candidate's skills)" : cut(job.description(), 2000));
    }

    /** Accepts {"questions":[{type,q}]}, a bare array, plain strings, or {"technical":[...], ...}. */
    static List<Question> parseQuestions(String raw) {
        Map<String, Question> out = new LinkedHashMap<>();
        JsonNode root = readJson(raw);
        if (root != null) collect(root, null, out, false);
        return out.values().stream().limit(8).toList();
    }

    private static void collect(JsonNode n, String hint, Map<String, Question> out, boolean inArray) {
        if (n.isObject()) {
            String type = text(n, "type", "category", "kind");
            String q = text(n, "q", "question", "text");
            if (q != null) { add(out, type != null ? type : hint, q); return; }
            for (Map.Entry<String, JsonNode> e : n.properties()) collect(e.getValue(), e.getKey(), out, false);
        } else if (n.isArray()) {
            for (JsonNode c : n) collect(c, hint, out, true);
        } else if (n.isString() && inArray) {
            add(out, hint, n.asString());
        }
    }

    private static void add(Map<String, Question> out, String type, String q) {
        String t = q.strip().replaceFirst("^(?:\\d+[.)]|[-*•])\\s*", "");
        if (t.length() < 10) return;
        out.putIfAbsent(t.toLowerCase(Locale.ROOT), new Question(type(type), cut(t, 400)));
    }

    static String type(String s) {
        String t = s == null ? "" : s.toLowerCase(Locale.ROOT);
        if (t.contains("behav")) return "BEHAVIOURAL";
        if (t.contains("role") || t.contains("company") || t.contains("motiv") || t.contains("culture")) return "ROLE";
        return "TECHNICAL";
    }

    /** No model: standard questions, technical ones on the skills the job and the candidate share. */
    static List<Question> template(Tailor.Facts f, Tailor.JobInfo job) {
        Set<String> mine = f.allSkills();
        List<String> skills = new ArrayList<>(job.skills().stream().filter(mine::contains).toList());
        for (String s : f.skills()) if (!skills.contains(s)) skills.add(s); // then the candidate's own stack
        List<Question> q = new ArrayList<>(List.of(
                new Question("BEHAVIOURAL", "Tell me about a time you delivered something under a tight deadline. What did you do, and what was the result?"),
                new Question("BEHAVIOURAL", "Describe a disagreement with a teammate about a technical decision. How was it resolved?")));
        for (String s : skills.stream().limit(3).toList())
            q.add(new Question("TECHNICAL", "Walk me through a real project where you used " + s + ". What trade-offs did you make?"));
        while (q.size() < 5) q.add(new Question("TECHNICAL", "Explain the design of a system you built recently, and what you would change today."));
        q.add(new Question("ROLE", job.company() == null ? "Why do you want to work as a " + job.title() + ", and what makes you a good fit?"
                : "Why do you want the " + job.title() + " role at " + job.company() + ", and how does your experience fit it?"));
        return q;
    }

    // ------------------------------------------------------------------ feedback

    /** null when the model is unavailable or its reply has nothing usable. */
    static Feedback feedback(Tailor.Facts f, String role, Item item, String answer, Function<String, String> llmJson) {
        String raw = llmJson == null ? null : llmJson.apply(feedbackPrompt(f, role, item, answer));
        Feedback fb = parseFeedback(raw);
        if (fb == null || fb.improvedAnswer() == null) return fb;
        // the candidate's own answer is a source of facts too
        String source = f.all() + "\n" + answer + "\n" + item.question();
        Set<String> skills = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        skills.addAll(f.allSkills());
        skills.addAll(JdParser.parse(null, answer).skills());
        String improved = stripNumbers(fb.improvedAnswer(), source);
        List<String> warnings = new ArrayList<>();
        if (!improved.equals(fb.improvedAnswer())) warnings.add("Numbers you never gave were replaced with " + ADD_NUMBER + ". Use your real ones.");
        for (String p : Tailor.FactCheck.problems(improved, source, skills))
            warnings.add("The improved answer " + p + ". Only say it if it's true.");
        return new Feedback(fb.score(), fb.rubric(), fb.improvements(), improved, warnings);
    }

    static String feedbackPrompt(Tailor.Facts f, String role, Item item, String answer) {
        return """
                You are an interview coach. Score the candidate's answer to one practice question.
                Return JSON only: {"relevance":1-5,"specificity":1-5,"technical":1-5,"concision":1-5,"improvements":["..."],"improved_answer":"..."}
                relevance: answers the question that was asked.
                specificity: a concrete example; for behavioural questions STAR (situation, task, action, result).
                technical: correct and precise (non-technical question: clear reasoning).
                concision: fits in 2 minutes spoken, no filler.
                improvements: 2 or 3 concrete fixes for this answer.
                improved_answer: this answer rewritten better, first person, at most 150 words, using ONLY facts from the answer and the candidate profile. Never invent numbers, employers, tools or results; where a number would help, write [add your own number here].

                CANDIDATE:
                %s
                ROLE: %s
                QUESTION (%s): %s
                ANSWER:
                %s
                """.formatted(profileText(f), role, item.type().toLowerCase(Locale.ROOT), item.question(), cut(answer, 3000));
    }

    /** Lenient: finds rubric scores by key name at any depth ("Relevance", "STAR", "Technical correctness"), "4/5" or 4.0. */
    static Feedback parseFeedback(String raw) {
        JsonNode root = readJson(raw);
        if (root == null || !root.isObject()) return null;
        Map<String, Integer> rubric = new LinkedHashMap<>();
        List<String> improvements = new ArrayList<>();
        String[] improved = {null};
        walk(root, (key, v) -> {
            String k = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (k.contains("answer") || k.contains("rewrite")) { if (v.isString() && !v.asString().isBlank()) improved[0] = v.asString().strip(); }
            else if (k.contains("improv") || k.contains("suggest") || k.contains("fix")) {
                List<String> xs = new ArrayList<>();
                if (v.isArray()) for (JsonNode x : v) xs.add(x.isString() ? x.asString() : x.toString());
                else if (v.isString()) xs.addAll(Arrays.asList(v.asString().split("\\R")));
                for (String x : xs) {
                    String s = x.strip().replaceFirst("^(?:\\d+[.)]|[-*•])\\s*", "");
                    if (!s.isEmpty() && improvements.size() < 3) improvements.add(cut(s, 300));
                }
            } else {
                String crit = k.contains("relev") ? "relevance" : k.contains("specif") || k.contains("star") ? "specificity"
                        : k.contains("tech") || k.contains("correct") ? "technical" : k.contains("concis") || k.contains("brev") ? "concision" : null;
                Integer s = score(v);
                if (crit != null && s != null) rubric.putIfAbsent(crit, s);
            }
        });
        if (rubric.isEmpty() && improvements.isEmpty() && improved[0] == null) return null;
        Integer overall = rubric.isEmpty() ? null : (int) Math.round(rubric.values().stream().mapToInt(i -> i).average().orElse(0));
        return new Feedback(overall, rubric, improvements, improved[0] == null ? null : cut(improved[0], 2000), List.of());
    }

    private static void walk(JsonNode n, java.util.function.BiConsumer<String, JsonNode> leaf) {
        for (Map.Entry<String, JsonNode> e : n.properties()) {
            if (e.getValue().isObject()) walk(e.getValue(), leaf);
            else leaf.accept(e.getKey(), e.getValue());
        }
    }

    private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    static Integer score(JsonNode v) {
        String s = v.isNumber() ? v.asString() : v.isString() ? v.asString() : null;
        if (s == null) return null;
        Matcher m = FIRST_NUMBER.matcher(s);
        return m.find() ? (int) Math.max(1, Math.min(5, Math.round(Double.parseDouble(m.group())))) : null;
    }

    // a number with its unit ("40%", "2M", "10x", "500+") is replaced whole
    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)(?:%|[xX+]|[kKmMbB]\\b)?");

    /** Numbers the candidate never gave become a placeholder for their own. */
    static String stripNumbers(String text, String source) {
        Set<String> known = Tailor.FactCheck.numbers(source);
        return NUMBER.matcher(text).replaceAll(r -> known.contains(r.group(1).replace(",", "")) ? Matcher.quoteReplacement(r.group()) : Matcher.quoteReplacement(ADD_NUMBER));
    }

    // ------------------------------------------------------------------ helpers

    static String roleLine(Tailor.JobInfo job) { return job.company() == null ? job.title() : job.title() + " at " + job.company(); }

    /** Compact candidate facts for prompts (not the whole resume: tokens cost time on a CPU, money on a cloud model). */
    static String profileText(Tailor.Facts f) {
        StringBuilder b = new StringBuilder();
        if (f.currentTitle() != null) b.append("Title: ").append(f.currentTitle()).append('\n');
        if (f.years() != null) b.append("Years of experience: ").append(f.years()).append('\n');
        if (!f.skills().isEmpty()) b.append("Skills: ").append(String.join(", ", f.skills().stream().limit(20).toList())).append('\n');
        for (Experience e : f.experiences())
            b.append("- ").append(e.title()).append(" at ").append(e.company()).append(": ")
                    .append(e.description() == null ? "" : cut(e.description().replaceAll("\\s*\\R\\s*", "; "), 400)).append('\n');
        if (f.experiences().isEmpty() && f.resumeText() != null) b.append(cut(f.resumeText(), 1200));
        return cut(b.toString().strip(), 2000);
    }

    /** JSON from a reply that may be wrapped in prose or ``` fences; null when unreadable. */
    static JsonNode readJson(String raw) {
        if (raw == null) return null;
        int s = indexOf(raw, '{', '['), e = Math.max(raw.lastIndexOf('}'), raw.lastIndexOf(']'));
        if (s < 0 || e <= s) return null;
        try { return JSON.readTree(raw.substring(s, e + 1)); } catch (RuntimeException ex) { return null; }
    }

    private static int indexOf(String s, char a, char b) {
        int i = s.indexOf(a), j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    private static String text(JsonNode n, String... keys) {
        for (String k : keys) for (Map.Entry<String, JsonNode> e : n.properties())
            if (e.getKey().equalsIgnoreCase(k) && e.getValue().isString() && !e.getValue().asString().isBlank()) return e.getValue().asString();
        return null;
    }

    static String cut(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n); }
}
