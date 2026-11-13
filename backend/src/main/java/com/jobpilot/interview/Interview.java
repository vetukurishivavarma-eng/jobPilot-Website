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
    /**
     * What the camera saw while the candidate answered (all on the device; percentages of the answer's frames).
     * away = no face or head turned; eyesOff = face to the camera but eyes off to the side or down (reading-like);
     * tense = brows down / lips pressed; pasted = text pasted into the typed answer. Any field may be null.
     */
    public record Signals(Integer seconds, Integer away, Integer eyesOff, Integer tense, Integer smile, Boolean pasted) {
        static Signals clean(Signals s) {
            if (s == null) return null;
            return new Signals(clamp(s.seconds, 3600), clamp(s.away, 100), clamp(s.eyesOff, 100), clamp(s.tense, 100), clamp(s.smile, 100), s.pasted);
        }
        private static Integer clamp(Integer v, int max) { return v == null ? null : Math.max(0, Math.min(max, v)); }
        /** Reads like notes or another screen: eyes off-screen for 40%+ of a 15s+ answer, head away half the time, or pasted text. */
        boolean suspicious() {
            boolean longEnough = seconds == null || seconds >= 15;
            return Boolean.TRUE.equals(pasted) || longEnough && (eyesOff != null && eyesOff >= 40 || away != null && away >= 50);
        }
        boolean looksTense() { return tense != null && tense >= 40; }
    }
    public record Item(String type, String question, String answer, Feedback feedback, Signals signals) {
        public Item(String type, String question, String answer, Feedback feedback) { this(type, question, answer, feedback, null); }
    }
    /**
     * source: AI (model wrote the questions) or TEMPLATE (model unavailable, standard questions from the job's skills).
     * mode: SET (all questions up front; null on old sessions) or LIVE (one question at a time, each built on the answers so far).
     * report: LIVE only, written after the last answer. violations: phone seen on camera (1 = warned); ended: POLICY after the 2nd.
     */
    public record Content(String source, List<Item> items, String mode, Report report, Integer violations, String ended) {
        Content(String source, List<Item> items) { this(source, items, "SET", null, 0, null); }
        Content(String source, List<Item> items, String mode, Report report) { this(source, items, mode, report, 0, null); }
        boolean live() { return "LIVE".equals(mode); }
        int violationCount() { return violations == null ? 0 : violations; }
        /** Planned questions answered (a PROBE about notes doesn't use up a slot). */
        int planAnswered() { return (int) items.stream().filter(i -> i.answer() != null && !"PROBE".equals(i.type())).count(); }
    }
    /** readiness 0-100 from the mean rubric score; gaps compare the answers with what interviewers expect at this level. */
    public record Report(Integer readiness, String verdict, List<String> strengths, List<Gap> gaps, List<String> focus, String source) {}
    public record Gap(String area, String detail) {}

    static final int LIVE_QUESTIONS = 8;
    static final int MAX_PROBES = 2;
    static final String INTRO = "Tell me about yourself: your background, and one project you're proud of. What was your part in it?";
    static final String PROBE = "Before we go on: your eyes kept moving away from the screen, as if you were reading. Are you looking at notes or another screen? In a real interview that counts against you. Please answer the last question again, in your own words.";
    static final String POLICY = "This interview was stopped: a phone was seen on camera twice, which violates JobPilot Policy.";

    // ------------------------------------------------------------------ live interview
    //
    // Token plan (2026-11-12): each turn asks the model for ONLY the next question, as short plain text (~50 output
    // tokens), from a fixed prefix (rules -> level -> candidate -> job, cacheable) plus a compact transcript (earlier
    // questions only + the latest answer). All scoring and the report happen once, at the end, in one JSON call.
    // The opening, the closing HR question and the notes probe are templates: no model call at all.

    static Content liveStart() { return new Content("AI", List.of(new Item("INTRO", INTRO, null, null)), "LIVE", null, 0, null); }

    /** The next item after an answer: a probe if the answer looked read out, else the plan's next question. null when the plan is done. */
    static Item next(Tailor.Facts f, Tailor.JobInfo job, Content c, Function<String, String> llmText) {
        int n = c.planAnswered();
        if (n >= LIVE_QUESTIONS) return null;
        Item last = c.items().get(c.items().size() - 1);
        long probes = c.items().stream().filter(i -> "PROBE".equals(i.type())).count();
        if (!"PROBE".equals(last.type()) && last.signals() != null && last.signals().suspicious() && probes < MAX_PROBES)
            return new Item("PROBE", PROBE, null, null);
        if (n == LIVE_QUESTIONS - 1) return new Item("FOLLOW_UP", closing(job), null, null);
        String q = llmText == null ? null : clean(llmText.apply(nextPrompt(f, job, c.items(), n)));
        return new Item("FOLLOW_UP", q != null ? q : fallbackNext(f, job, n), null, null);
    }

    static String closing(Tailor.JobInfo job) {
        return "Last question: why do you want to join " + (job.company() == null ? "a team like ours" : job.company())
                + ", and where do you see yourself growing in the next two or three years?";
    }

    /** A model's question: first non-empty line, quotes/labels stripped; null if it doesn't look like a question. */
    static String clean(String raw) {
        if (raw == null) return null;
        for (String line : raw.strip().split("\\R")) {
            String s = line.strip().replaceFirst("^(?i)(next[_ ]question|question|interviewer|q\\d*)\\s*[:.-]\\s*", "")
                    .replaceAll("^[\"'*]+|[\"'*]+$", "").strip();
            if (s.length() >= 15) return cut(s, 400);
        }
        return null;
    }

    static String nextPrompt(Tailor.Facts f, Tailor.JobInfo job, List<Item> items, int n) {
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < items.size() - 1; i++) t.append("Q: ").append(cut(items.get(i).question(), 200)).append('\n'); // no old answers: tokens
        Item last = items.get(items.size() - 1);
        t.append("Q: ").append(last.question()).append("\nA: ").append(cut(last.answer(), 1500));
        String mood = last.signals() != null && last.signals().looksTense()
                ? "\nThe candidate looks tense on camera: open with a few warm words before the question." : "";
        // fixed prefix first (identical every turn -> provider prompt cache), variable part last
        return """
                You are a friendly but rigorous interviewer in a live spoken interview for the role below. Interview the way this company's interviewers typically do for this level, from what is publicly known; never claim to have seen real interview reports.
                Reply with ONLY the next question you say aloud: one or two short spoken sentences, no preamble, no labels, no answer, no lists or code.
                CANDIDATE LEVEL: %s
                CANDIDATE:
                %s
                ROLE: %s
                JOB TEXT:
                %s
                INTERVIEW SO FAR:
                %s
                NEXT QUESTION MUST BE ABOUT: %s
                Tie it to what the candidate actually said where that fits. Never repeat an earlier question.%s
                """.formatted(level(f), profileText(f), roleLine(job),
                job.description() == null ? "(none)" : cut(job.description(), 1200), t, focus(f, n), mood);
    }

    /** Model unavailable or unreadable: the standard set, in order. */
    static String fallbackNext(Tailor.Facts f, Tailor.JobInfo job, int n) {
        List<Question> qs = template(f, job);
        return qs.get(Math.min(Math.max(n - 1, 0), qs.size() - 1)).text();
    }

    /**
     * One call at the end: rubric + one tip per answer, a better version of the weakest, and the report. null if the model fails.
     * Compact on purpose (scores as "s":[relevance,specificity,technical,concision]): ~600 output tokens, under Groq's
     * 1,000 output-tokens-per-minute limit; the long key form was 1,210.
     */
    record Evaluation(List<Item> items, Report report) {}

    static Evaluation evaluate(Tailor.Facts f, Tailor.JobInfo job, List<Item> items, Function<String, String> llmJson) {
        JsonNode r = llmJson == null ? null : readJson(llmJson.apply(evaluatePrompt(f, job, items)));
        if (r == null || !r.isObject()) return null;
        List<Item> out = new ArrayList<>(items);
        JsonNode scores = r.get("scores");
        if (scores != null && scores.isArray()) for (JsonNode s : scores) {
            int q = s.path("q").asInt(0) - 1;
            if (q < 0 || q >= out.size() || out.get(q).answer() == null) continue;
            Feedback fb = compactFeedback(s);
            if (fb == null) fb = parseFeedback(s.toString()); // a model that ignored the format and spelled the keys out
            if (fb != null) out.set(q, withFeedback(out.get(q), fb));
        }
        JsonNode better = r.get("improved");
        if (better != null && better.isObject()) better = JSON.createArrayNode().add(better);
        if (better != null && better.isArray()) for (JsonNode b : better) {
            int q = b.path("q").asInt(0) - 1;
            String text = text(b, "a", "answer", "improved_answer", "text");
            if (q < 0 || q >= out.size() || text == null || out.get(q).answer() == null) continue;
            Item it = out.get(q);
            Feedback old = it.feedback() != null ? it.feedback() : new Feedback(null, Map.of(), List.of(), null, List.of());
            Feedback fb = checked(f, it, it.answer(), new Feedback(old.score(), old.rubric(), old.improvements(), cut(text.strip(), 1500), List.of()));
            out.set(q, withFeedback(it, fb));
        }
        List<Gap> gaps = new ArrayList<>();
        JsonNode g = r.get("gaps");
        if (g != null && g.isArray()) for (JsonNode x : g) {
            String area = x.isObject() ? text(x, "area", "topic") : x.isString() ? x.asString() : null;
            String detail = x.isObject() ? text(x, "detail", "vs_market", "why") : null;
            if (area != null && gaps.size() < 5) gaps.add(new Gap(cut(area, 120), detail == null ? null : cut(detail, 400)));
        }
        String verdict = text(r, "verdict", "summary");
        Integer readiness = readiness(out);
        if (verdict == null && gaps.isEmpty() && readiness == null) return null;
        return new Evaluation(out, new Report(readiness, verdict == null ? null : cut(verdict, 600), strings(r, "strengths"), gaps, strings(r, "focus"), "AI"));
    }

    /** {"q":1,"s":[5,3,4,4],"tip":"..."} -> Feedback; null if "s" isn't 4 scores. */
    static Feedback compactFeedback(JsonNode n) {
        JsonNode sc = n.get("s");
        if (sc == null || !sc.isArray() || sc.size() != 4) return null;
        String[] keys = {"relevance", "specificity", "technical", "concision"};
        Map<String, Integer> rubric = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) { Integer v = score(sc.get(i)); if (v != null) rubric.put(keys[i], v); }
        if (rubric.isEmpty()) return null;
        String tip = text(n, "tip", "fix");
        return new Feedback((int) Math.round(rubric.values().stream().mapToInt(x -> x).average().orElse(0)), rubric,
                tip == null ? List.of() : List.of(cut(tip.strip(), 300)), null, List.of());
    }

    private static Item withFeedback(Item i, Feedback fb) { return new Item(i.type(), i.question(), i.answer(), fb, i.signals()); }

    static String evaluatePrompt(Tailor.Facts f, Tailor.JobInfo job, List<Item> items) {
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.answer() == null) continue;
            t.append(i + 1).append(". Q: ").append(cut(it.question(), 300)).append("\nA: ").append(cut(it.answer(), 900));
            Signals s = it.signals();
            if (s != null && (s.suspicious() || s.looksTense()))
                t.append("\n(camera: ").append(s.suspicious() ? "eyes often off-screen, may have read notes" : "looked tense").append(')');
            t.append('\n');
        }
        return """
                You are a hiring panel scoring a finished spoken mock interview for the role below. Answers are speech-to-text: ignore filler words and transcription slips.
                Return compact JSON only, no extra keys: {"scores":[{"q":1,"s":[r,p,t,c],"tip":"..."}],"improved":{"q":1,"a":"..."},"verdict":"...","strengths":["..."],"gaps":[{"area":"...","detail":"..."}],"focus":["..."]}
                scores: one per numbered answer; s = four 1-5 scores: r relevance (answers what was asked), p specificity (concrete example; STAR for behavioural), t technical (correct at the candidate's level, not more), c concision. tip: the one most useful fix, max 12 words.
                improved: the single weakest answer rewritten, first person, max 70 words, ONLY facts from that answer and the profile; never invent numbers, employers, tools or results; write [add your own number here] where a number would help.
                verdict: 2 short honest sentences: would they likely clear this round today, and why.
                strengths: up to 2, max 12 words each, tied to what they said. gaps: up to 3 areas below what interviewers expect for this role AT THE CANDIDATE'S LEVEL (compare with strong peers at that level, not seniors); detail = what a strong candidate would have said, max 20 words. focus: up to 3 things to practise, max 10 words each.
                CANDIDATE LEVEL: %s
                CANDIDATE:
                %s
                ROLE: %s
                TRANSCRIPT:
                %s
                """.formatted(level(f), profileText(f), roleLine(job), cut(t.toString(), 9000));
    }

    static final String QUIT = "You ended this interview early, so there is no score. Start a new one when you're ready.";
    static Report quitReport() { return new Report(null, QUIT, List.of(), List.of(), List.of(), "QUIT"); }

    // ponytail: phrase match on SHORT answers only (a real answer can say "I closed the project"); add an LLM intent check only if users slip past it
    private static final java.util.regex.Pattern QUIT_WORDS = java.util.regex.Pattern.compile(
            "(?i)\\b(end|stop|close|quit|cancel|exit|finish|terminate|leave)\\b.{0,25}\\b(interview|session)\\b"
            + "|\\b(don'?t|do not) want (this|the|to (do|continue|go on))"
            + "|^\\W*(please |ok |okay )?(close|end|stop|quit|exit|cancel)( it| this| now)?\\b");

    private static final java.util.regex.Pattern NUDGE = java.util.regex.Pattern.compile("(?i)\\s*(please,? look at the camera|stay in the camera frame)[.!]?(\\s*last warning[.!]?)?");
    static String stripNudge(String transcript) { return NUDGE.matcher(transcript).replaceAll("").strip(); }

    /** The candidate asked to stop ("I don't want this interview, close it"). */
    static boolean wantsToQuit(String answer) {
        return answer.split("\\s+").length <= 20 && QUIT_WORDS.matcher(answer).find();
    }

    static final String CHEATING = "This interview was cancelled for cheating: you left the camera frame 3 times.";
    static Report cheatingReport() { return new Report(null, CHEATING, List.of(), List.of(), List.of(), "CHEATING"); }

    static Report policyReport() { return new Report(null, POLICY, List.of(), List.of(), List.of(), "POLICY"); }

    /** Mean rubric score of the scored answers as 0-100 (1 -> 0, 5 -> 100); null if none were scored. */
    static Integer readiness(List<Item> items) {
        DoubleSummaryStatistics st = items.stream().filter(i -> i.feedback() != null && i.feedback().rubric() != null)
                .flatMap(i -> i.feedback().rubric().values().stream()).mapToDouble(x -> x).summaryStatistics();
        return st.getCount() == 0 ? null : (int) Math.round((st.getAverage() - 1) / 4 * 100);
    }

    private static List<String> strings(JsonNode r, String key) {
        List<String> out = new ArrayList<>();
        JsonNode v = r == null ? null : r.get(key);
        if (v != null && v.isArray()) for (JsonNode x : v)
            if (x.isString() && !x.asString().isBlank() && out.size() < 4) out.add(cut(x.asString().strip(), 300));
        return out;
    }

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
                technical: about skills the job text asks for that the candidate also has; else the candidate's main skills. Pitch them at the candidate's level below.
                role: why this role or company, using only what the job text says. Never invent facts about the company.
                One sentence per question. No answers, no numbering.

                CANDIDATE LEVEL: %s
                CANDIDATE:
                %s
                ROLE: %s
                JOB TEXT:
                %s
                """.formatted(level(f), profileText(f), roleLine(job), job.description() == null ? "(none, use the role and the candidate's skills)" : cut(job.description(), 2000));
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
        return checked(f, item, answer, parseFeedback(raw));
    }

    /** Fact-checks the improved answer against the candidate's own facts. */
    static Feedback checked(Tailor.Facts f, Item item, String answer, Feedback fb) {
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
            else if (k.contains("improv") || k.contains("suggest") || k.contains("fix") || k.equals("tip")) {
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

    /** Years of experience as a number (first number in the profile's text), null if unknown. */
    static Double years(Tailor.Facts f) {
        if (f.years() == null) return null;
        Matcher m = FIRST_NUMBER.matcher(f.years());
        return m.find() ? Double.parseDouble(m.group()) : null;
    }

    /**
     * What interviewers in India actually test at the candidate's level. JobPilot's users are 22-30: freshers to mid-level.
     * Unknown years = treated as early-career.
     */
    /**
     * The interview plan: what question n (0 = the opening) covers, per level. Fixed in code because the model,
     * told only "max 2 per topic", spent 7 questions on one project and skipped fundamentals and HR (live test 2026-11-12).
     */
    static final List<String> FRESHER_PLAN = List.of("opening",
            "the project they described: their own contribution and why they built it that way",
            "a deeper follow-up on that project: a problem they hit, or what they would change",
            "a core CS fundamental relevant to the job: OOP or DBMS/SQL",
            "an easy-to-medium data structures problem (arrays, strings, hashing, sorting, linked lists or stacks; nothing graph-heavy): ask them to explain their approach and its time and space complexity aloud",
            "another CS fundamental: operating systems or computer networks",
            "a behavioural question about a team project, a deadline or a disagreement (STAR)",
            "HR: why this company and role, or where they see themselves growing");
    static final List<String> EARLY_PLAN = List.of("opening",
            "the work or project they described: their own contribution and the design choices",
            "a follow-up on it: a bug or production issue they debugged, or how they tested it",
            "a core fundamental the job relies on (DBMS/SQL, OOP, concurrency, or the main framework's internals)",
            "a data structures and algorithms problem: approach and complexity, explained aloud",
            "light design: how they would design a small feature or module from the job, with trade-offs",
            "a behavioural question about teamwork, feedback or a missed deadline (STAR)",
            "why this company and role, and what they want to learn next");
    static final List<String> MID_PLAN = List.of("opening",
            "the system or feature they described: architecture and the trade-offs they made",
            "a follow-up on it: a production incident, performance or scaling problem and how they handled it",
            "system design: design a service or feature relevant to the job (components, data, scaling, failure handling)",
            "a deeper design trade-off: consistency vs availability, caching, queues, or data modelling",
            "a core technical fundamental the job relies on, at depth (concurrency, database internals, or the main framework)",
            "a behavioural question about ownership, mentoring a junior, or pushing back on a decision (STAR)",
            "why this company and role, and how they would add value in the first months");

    static String focus(Tailor.Facts f, int n) {
        Double y = years(f);
        List<String> plan = y != null && y < 1 ? FRESHER_PLAN : y == null || y < 3 ? EARLY_PLAN : MID_PLAN;
        return plan.get(Math.min(Math.max(n, 1), plan.size() - 1));
    }

    static String level(Tailor.Facts f) {
        Double y = years(f);
        if (y != null && y < 1) return """
                FRESHER (0-1 years; campus or off-campus hiring). Interviewers test: CS fundamentals (OOP, DBMS and SQL, operating systems, computer networks); data structures and algorithms (ask them to explain an approach and its time/space complexity aloud, not to write full code); their academic, internship and personal projects in depth (their own contribution, why that stack, what they'd do differently); and HR questions (strengths, why this company, willingness to relocate). Do not expect production or team-lead experience.""";
        if (y == null || y < 3) return """
                EARLY-CAREER (1-3 years). Interviewers test: CS fundamentals and DSA reasoning are still asked; depth on their real work: APIs, databases and queries, debugging a production bug, testing, code reviews, working in a team; light design (how would you design this feature/module).""";
        return """
                MID-LEVEL (3+ years). Interviewers test: high- and low-level system design, owning features end to end, trade-offs and why, production incidents and performance, scaling, mentoring juniors and influencing decisions.""";
    }

    /** Compact candidate facts for prompts (not the whole resume: tokens cost time on a CPU, money on a cloud model). */
    static String profileText(Tailor.Facts f) {
        StringBuilder b = new StringBuilder();
        if (f.currentTitle() != null) b.append("Title: ").append(f.currentTitle()).append('\n');
        if (f.years() != null) b.append("Years of experience: ").append(f.years()).append('\n');
        if (!f.skills().isEmpty()) b.append("Skills: ").append(String.join(", ", f.skills().stream().limit(20).toList())).append('\n');
        for (Experience e : f.experiences())
            b.append("- ").append(e.title()).append(" at ").append(e.company()).append(": ")
                    .append(e.description() == null ? "" : cut(e.description().replaceAll("\\s*\\R\\s*", "; "), 400)).append('\n');
        for (com.jobpilot.profile.Profile.Education e : f.educations()) b.append("Education: ").append(Tailor.educationLine(e)).append('\n');
        // freshers/early-career: their projects live in the resume text, not in work experience
        Double y = years(f);
        if (f.resumeText() != null && (f.experiences().isEmpty() || y == null || y < 2)) b.append(cut(f.resumeText(), 1200));
        return cut(b.toString().strip(), 2600);
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
