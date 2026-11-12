package com.jobpilot.interview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Spoken answers -> text with Whisper on an OpenAI-compatible endpoint (default: Groq's free whisper-large-v3-turbo,
 * same key as the LLM). The audio is sent once and not kept.
 */
@Component
public class SpeechToText {
    private static final Logger log = LoggerFactory.getLogger(SpeechToText.class);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String url, key, model;

    SpeechToText(@Value("${jobpilot.stt-url}") String url, @Value("${jobpilot.stt-api-key:}") String key, @Value("${jobpilot.stt-model}") String model) {
        this.url = url.replaceAll("/+$", "");
        this.key = key;
        this.model = model;
    }

    public boolean configured() { return !key.isBlank(); }

    /** The transcript, "" if nothing was said, null if the service failed. */
    public String transcribe(byte[] audio, String contentType) {
        if (!configured()) return null;
        String boundary = "jp" + UUID.randomUUID().toString().replace("-", "");
        String ext = contentType != null && contentType.contains("mp4") ? "m4a" : contentType != null && contentType.contains("ogg") ? "ogg" : "webm";
        ByteArrayOutputStream body = new ByteArrayOutputStream(audio.length + 1024);
        field(body, boundary, "model", model);
        field(body, boundary, "language", "en");
        field(body, boundary, "response_format", "json");
        field(body, boundary, "prompt", "A candidate answering a job interview question in Indian English."); // steers spelling of tech terms
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"answer." + ext + "\"\r\nContent-Type: "
                + (contentType == null ? "audio/webm" : contentType) + "\r\n\r\n");
        body.writeBytes(audio);
        write(body, "\r\n--" + boundary + "--\r\n");
        try {
            HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(url + "/audio/transcriptions"))
                    .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + key)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("STT {} answered {}: {}", model, res.statusCode(), res.body().length() > 300 ? res.body().substring(0, 300) : res.body());
                return null;
            }
            JsonNode j = Interview.JSON.readTree(res.body());
            String text = j.path("text").asString("").strip();
            log.info("STT {} {} KB -> {} chars", model, audio.length / 1024, text.length());
            return text;
        } catch (Exception e) {
            log.warn("STT failed: {}", e.toString());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void field(ByteArrayOutputStream b, String boundary, String name, String value) {
        write(b, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream b, String s) { b.writeBytes(s.getBytes(StandardCharsets.UTF_8)); }
}
