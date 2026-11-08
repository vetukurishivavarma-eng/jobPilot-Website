package com.jobpilot.apply;

import com.jobpilot.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Local LLM (Ollama) for open-ended application questions. Returns null when Ollama isn't running. */
@Component
public class Ollama {
    private static final Logger log = LoggerFactory.getLogger(Ollama.class);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final AppProperties props;

    Ollama(AppProperties props) { this.props = props; }

    public String generate(String prompt) {
        return call(prompt, false);
    }

    /** Same, but Ollama constrains the reply to valid JSON. */
    public String generateJson(String prompt) {
        return call(prompt, true);
    }

    private String call(String prompt, boolean json) {
        ObjectNode body = FormReader.JSON.createObjectNode()
                .put("model", props.ollamaModel()).put("prompt", prompt).put("stream", false);
        if (json) body.put("format", "json");
        body.putObject("options").put("temperature", 0.3);
        try {
            HttpResponse<String> res = CLIENT.send(HttpRequest.newBuilder(URI.create(props.ollamaUrl() + "/api/generate"))
                    .timeout(Duration.ofSeconds(180)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("Ollama answered {}: {}", res.statusCode(), res.body());
                return null;
            }
            return FormReader.JSON.readTree(res.body()).path("response").asString("").strip();
        } catch (Exception e) {
            log.warn("Ollama unavailable at {}: {}", props.ollamaUrl(), e.toString());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }
}
