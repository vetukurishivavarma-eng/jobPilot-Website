package com.jobpilot.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Email through Brevo's HTTP API (free 300/day; SMTP is unreliable on Render's free tier). Unconfigured = logged, not sent. */
@Component
public class Mailer {
    private static final Logger log = LoggerFactory.getLogger(Mailer.class);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String key, from;

    Mailer(@Value("${jobpilot.brevo-api-key:}") String key, @Value("${jobpilot.mail-from:}") String from) {
        this.key = key;
        this.from = from;
    }

    public boolean configured() { return !key.isBlank() && !from.isBlank(); }

    /** true when Brevo accepted it. */
    public boolean send(String to, String subject, String html) {
        if (!configured()) {
            log.warn("Mail '{}' to {} not sent: BREVO_API_KEY/MAIL_FROM are not set", subject, to);
            return false;
        }
        ObjectNode body = JSON.createObjectNode().put("subject", subject).put("htmlContent", html);
        body.putObject("sender").put("name", "JobPilot").put("email", from);
        body.putArray("to").addObject().put("email", to);
        try {
            HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.brevo.com/v3/smtp/email"))
                    .timeout(Duration.ofSeconds(15)).header("api-key", key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) {
                log.info("Mail '{}' sent to {}", subject, to);
                return true;
            }
            log.warn("Mail '{}' to {} failed: {} {}", subject, to, res.statusCode(), res.body());
        } catch (Exception e) {
            log.warn("Mail '{}' to {} failed: {}", subject, to, e.toString());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return false;
    }

    public static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
