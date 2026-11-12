package com.jobpilot.apply;

import com.jobpilot.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The one door to the LLM. Every caller (answers, tailoring, resume import, interview coach) goes through
 * generate / generateJson, so cost controls live here only.
 * <ul>
 *   <li>Cloud chain (AppProperties.llms(): LLM_* then LLM2_*, each a comma list of models): any OpenAI-compatible
 *       endpoint (Groq, Gemini, OpenRouter, a paid model later). On a failure or rate limit (free tiers do) the next
 *       one is tried, so several free daily quotas stack.</li>
 *   <li>Last: local Ollama (free; absent on a hosted server, where it just fails fast).</li>
 * </ul>
 * Output is capped per call, JSON calls run at temperature 0, and every call logs tokens + time so the bill is visible.
 * Returns null when no model answers; callers already handle that.
 */
@Component
public class Ollama {
    private static final Logger log = LoggerFactory.getLogger(Ollama.class);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    /** Longest legit reply is a ~350-word cover letter (~500 tokens) or the resume-import JSON (~1500). */
    static final int MAX_TEXT_TOKENS = 900, MAX_JSON_TOKENS = 2000, MAX_SHORT_TOKENS = 300; // short: ~50 for the question + low-effort reasoning

    private final AppProperties props;

    Ollama(AppProperties props) { this.props = props; }

    public String generate(String prompt) {
        return call(prompt, false, MAX_TEXT_TOKENS, 0.3);
    }

    /** A one-line reply (the live interviewer's next question): small token cap, a little randomness so retakes differ. */
    public String generateShort(String prompt) {
        return call(prompt, false, MAX_SHORT_TOKENS, 0.6);
    }

    /** Same, but the model is constrained to reply with valid JSON. */
    public String generateJson(String prompt) {
        return call(prompt, true, MAX_JSON_TOKENS, 0);
    }

    private String call(String prompt, boolean json, int maxTokens, double temperature) {
        // ponytail: an exhausted model is re-tried on every call (~100 ms per 429); add a cooldown if that latency shows
        for (AppProperties.Llm llm : props.llms()) {
            String r = cloud(llm, prompt, json, maxTokens, temperature);
            if (r != null && !r.isEmpty()) return r;
        }
        return ollama(prompt, json, maxTokens, temperature);
    }

    private String cloud(AppProperties.Llm llm, String prompt, boolean json, int maxTokens, double temperature) {
        ObjectNode body = FormReader.JSON.createObjectNode().put("model", llm.model())
                .put("temperature", temperature).put("max_tokens", maxTokens);
        // gpt-oss and Gemini think by default and spend max_tokens on it (a cover letter came back cut off or
        // empty); "low" fixes them, and neither accepts "none". Qwen answers directly unless asked, where "low"
        // turns thinking ON (131 -> 575 tokens). Measured on Groq/Gemini 2026-11-10.
        if (!llm.model().contains("qwen")) body.put("reasoning_effort", "low");
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        if (json) body.putObject("response_format").put("type", "json_object");
        JsonNode res = post(llm.url() + "/chat/completions", body, llm.key(), 60); // an overloaded Gemini hung 150 s
        if (res == null) return null;
        JsonNode u = res.path("usage");
        if ("length".equals(res.path("choices").path(0).path("finish_reason").asString(""))) {
            // a cut-off letter or half a JSON object is worse than asking the next model
            log.warn("LLM {} hit max_tokens (in={} out={}), trying next", llm.model(), u.path("prompt_tokens").asInt(), u.path("completion_tokens").asInt());
            return null;
        }
        log.info("LLM {} in={} out={} tokens", llm.model(), u.path("prompt_tokens").asInt(), u.path("completion_tokens").asInt());
        return res.path("choices").path(0).path("message").path("content").asString("").strip();
    }

    private String ollama(String prompt, boolean json, int maxTokens, double temperature) {
        ObjectNode body = FormReader.JSON.createObjectNode()
                .put("model", props.ollamaModel()).put("prompt", prompt).put("stream", false)
                .put("keep_alive", "30m"); // a cold load costs ~35s on this CPU; keep the model in RAM between calls
        if (json) body.put("format", "json");
        body.putObject("options").put("temperature", temperature).put("num_predict", maxTokens);
        JsonNode res = post(props.ollamaUrl() + "/api/generate", body, null, 300);
        if (res == null) return null;
        log.info("LLM ollama/{} in={} out={} tokens, {} ms", props.ollamaModel(), res.path("prompt_eval_count").asInt(),
                res.path("eval_count").asInt(), res.path("total_duration").asLong() / 1_000_000);
        return res.path("response").asString("").strip();
    }

    private static JsonNode post(String url, ObjectNode body, String key, int timeoutSec) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSec))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            if (key != null) req.header("Authorization", "Bearer " + key);
            HttpResponse<String> res = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("LLM {} answered {}: {}", url, res.statusCode(), res.body().length() > 300 ? res.body().substring(0, 300) : res.body());
                return null;
            }
            return FormReader.JSON.readTree(res.body());
        } catch (Exception e) {
            log.warn("LLM unavailable at {}: {}", url, e.toString());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }
}
