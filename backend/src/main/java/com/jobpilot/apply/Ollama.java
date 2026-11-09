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
 *   <li>LLM_API_KEY set: any OpenAI-compatible chat endpoint (Groq, Gemini, OpenRouter, a paid model later),
 *       falling back to local Ollama if it fails or rate-limits (free tiers do).</li>
 *   <li>Otherwise local Ollama (free).</li>
 * </ul>
 * Output is capped per call, JSON calls run at temperature 0, and every call logs tokens + time so the bill is visible.
 * Returns null when no model answers; callers already handle that.
 */
@Component
public class Ollama {
    private static final Logger log = LoggerFactory.getLogger(Ollama.class);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    /** Longest legit reply is a ~350-word cover letter (~500 tokens) or the resume-import JSON (~1500). */
    static final int MAX_TEXT_TOKENS = 900, MAX_JSON_TOKENS = 2000;

    private final AppProperties props;

    Ollama(AppProperties props) { this.props = props; }

    public String generate(String prompt) {
        return call(prompt, false);
    }

    /** Same, but the model is constrained to reply with valid JSON. */
    public String generateJson(String prompt) {
        return call(prompt, true);
    }

    private String call(String prompt, boolean json) {
        if (props.cloudLlmEnabled()) {
            String r = cloud(prompt, json);
            if (r != null) return r;
        }
        return ollama(prompt, json);
    }

    private String cloud(String prompt, boolean json) {
        ObjectNode body = FormReader.JSON.createObjectNode().put("model", props.llmModel())
                .put("temperature", json ? 0 : 0.3).put("max_tokens", json ? MAX_JSON_TOKENS : MAX_TEXT_TOKENS);
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        if (json) body.putObject("response_format").put("type", "json_object");
        JsonNode res = post(props.llmUrl() + "/chat/completions", body, props.llmApiKey());
        if (res == null) return null;
        JsonNode u = res.path("usage");
        log.info("LLM {} in={} out={} tokens", props.llmModel(), u.path("prompt_tokens").asInt(), u.path("completion_tokens").asInt());
        return res.path("choices").path(0).path("message").path("content").asString("").strip();
    }

    private String ollama(String prompt, boolean json) {
        ObjectNode body = FormReader.JSON.createObjectNode()
                .put("model", props.ollamaModel()).put("prompt", prompt).put("stream", false)
                .put("keep_alive", "30m"); // a cold load costs ~35s on this CPU; keep the model in RAM between calls
        if (json) body.put("format", "json");
        body.putObject("options").put("temperature", json ? 0 : 0.3).put("num_predict", json ? MAX_JSON_TOKENS : MAX_TEXT_TOKENS);
        JsonNode res = post(props.ollamaUrl() + "/api/generate", body, null);
        if (res == null) return null;
        log.info("LLM ollama/{} in={} out={} tokens, {} ms", props.ollamaModel(), res.path("prompt_eval_count").asInt(),
                res.path("eval_count").asInt(), res.path("total_duration").asLong() / 1_000_000);
        return res.path("response").asString("").strip();
    }

    private static JsonNode post(String url, ObjectNode body, String key) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(300))
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
