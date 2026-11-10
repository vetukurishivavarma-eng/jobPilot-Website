package com.jobpilot.auth;

import com.jobpilot.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forgot password: POST /api/auth/forgot emails a link to GET /reset, a tiny server-rendered page (works for app and
 * web users alike, no hosted frontend needed). The token is stateless: userId.expiry signed with HMAC over the user's
 * CURRENT password hash, so it dies after 30 min or as soon as the password changes (one-time use, no table).
 * Mail goes through Brevo's HTTP API (free 300/day; SMTP is unreliable on Render's free tier).
 */
@RestController
public class PasswordResetController {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetController.class);
    static final Duration TTL = Duration.ofMinutes(30);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record ForgotRequest(String email) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final byte[] secret;
    private final String brevoKey, mailFrom;
    // ponytail: in-memory per-email cooldown, resets on restart; fine on one instance, move to the DB if scaled out
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();

    PasswordResetController(UserRepository users, PasswordEncoder encoder, AppProperties props,
                            @Value("${jobpilot.brevo-api-key:}") String brevoKey, @Value("${jobpilot.mail-from:}") String mailFrom) {
        this.users = users;
        this.encoder = encoder;
        this.secret = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        this.brevoKey = brevoKey;
        this.mailFrom = mailFrom;
    }

    /** Always 200 with the same message, so it can't be used to find out who has an account. */
    @PostMapping("/api/auth/forgot")
    public Map<String, String> forgot(@RequestBody ForgotRequest r, HttpServletRequest req) {
        String email = r.email() == null ? "" : r.email().trim().toLowerCase(Locale.ROOT);
        Instant now = Instant.now();
        Instant prev = lastSent.get(email);
        if (!email.isEmpty() && (prev == null || prev.isBefore(now.minusSeconds(60)))) {
            users.findByEmail(email).ifPresent(u -> {
                lastSent.put(email, now);
                String link = baseUrl(req) + "/reset?token=" + token(u, now.plus(TTL));
                Thread.startVirtualThread(() -> send(u, link)); // same response time whether or not the account exists
            });
        }
        return Map.of("message", "If an account exists for that email, a reset link is on its way. It works for 30 minutes.");
    }

    @GetMapping(value = "/reset", produces = MediaType.TEXT_HTML_VALUE)
    public String resetPage(@RequestParam(defaultValue = "") String token) {
        if (user(token).isEmpty()) return page("Link expired", "<p>This reset link is invalid, already used, or older than 30 minutes. Request a new one from the sign-in screen.</p>");
        return page("Set a new password", """
                <form method="post" action="/reset">
                  <input type="hidden" name="token" value="%s">
                  <label>New password<input type="password" name="password" minlength="8" maxlength="72" required autofocus></label>
                  <label>Repeat it<input type="password" name="confirm" minlength="8" maxlength="72" required></label>
                  <button>Save password</button>
                </form>""".formatted(token)); // token is safe to embed: user() only accepts [A-Za-z0-9_-.]
    }

    @PostMapping(value = "/reset", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    @Transactional
    public String reset(@RequestParam String token, @RequestParam String password, @RequestParam String confirm) {
        Optional<User> u = user(token);
        if (u.isEmpty()) return page("Link expired", "<p>This reset link is invalid, already used, or older than 30 minutes. Request a new one from the sign-in screen.</p>");
        if (password.length() < 8 || password.length() > 72 || !password.equals(confirm))
            return page("Try again", "<p>Passwords must match and be 8 to 72 characters.</p><p><a href=\"/reset?token=" + token + "\">Back</a></p>");
        u.get().setPasswordHash(encoder.encode(password));
        return page("Password changed", "<p>Done. Go back to JobPilot and sign in with your new password.</p>");
    }

    String token(User u, Instant expires) {
        String payload = u.getId() + "." + expires.getEpochSecond();
        return payload + "." + sign(payload, u.getPasswordHash());
    }

    /** The user the token is for, if it is well-formed, unexpired and signed over their current password hash. */
    Optional<User> user(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_.-]{1,200}")) return Optional.empty();
        String[] p = token.split("\\.");
        if (p.length != 3) return Optional.empty();
        try {
            if (Instant.ofEpochSecond(Long.parseLong(p[1])).isBefore(Instant.now())) return Optional.empty();
            return users.findById(UUID.fromString(p[0])).filter(u -> MessageDigest.isEqual(
                    sign(p[0] + "." + p[1], u.getPasswordHash()).getBytes(StandardCharsets.US_ASCII), p[2].getBytes(StandardCharsets.US_ASCII)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String sign(String payload, String passwordHash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] sig = mac.doFinal((payload + "|" + passwordHash).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Render terminates TLS and forwards X-Forwarded-Proto/Host. */
    private static String baseUrl(HttpServletRequest req) {
        String proto = Optional.ofNullable(req.getHeader("X-Forwarded-Proto")).orElse(req.getScheme());
        String host = Optional.ofNullable(req.getHeader("X-Forwarded-Host")).orElse(req.getHeader("Host"));
        return proto + "://" + host;
    }

    private void send(User u, String link) {
        if (brevoKey.isBlank() || mailFrom.isBlank()) {
            log.warn("Password reset requested for {} but BREVO_API_KEY/MAIL_FROM are not set; link: {}", u.getEmail(), link);
            return;
        }
        ObjectNode body = JSON.createObjectNode().put("subject", "Reset your JobPilot password")
                .put("htmlContent", "<p>Hi " + esc(u.getFullName()) + ",</p><p>Someone (hopefully you) asked to reset your JobPilot password. "
                        + "<a href=\"" + link + "\">Set a new password</a>. The link works for 30 minutes and only once.</p>"
                        + "<p>If this wasn't you, ignore this email; your password stays the same.</p>");
        body.putObject("sender").put("name", "JobPilot").put("email", mailFrom);
        body.putArray("to").addObject().put("email", u.getEmail());
        try {
            HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.brevo.com/v3/smtp/email"))
                    .timeout(Duration.ofSeconds(15)).header("api-key", brevoKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) log.warn("Reset mail to {} failed: {} {}", u.getEmail(), res.statusCode(), res.body());
            else log.info("Reset mail sent to {}", u.getEmail());
        } catch (Exception e) {
            log.warn("Reset mail to {} failed: {}", u.getEmail(), e.toString());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String page(String title, String body) {
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%1$s · JobPilot</title><style>
                body{font-family:system-ui,sans-serif;background:#f5f3ef;color:#1c1b1f;margin:0;display:grid;place-items:center;min-height:100vh;padding:16px}
                main{background:#fff;border-radius:16px;padding:28px;max-width:380px;width:100%%;box-shadow:0 2px 12px #0001}
                h1{font-size:22px;margin:0 0 16px} label{display:block;font-size:13px;margin:12px 0 4px;color:#555}
                input{display:block;width:100%%;box-sizing:border-box;padding:10px;margin-top:4px;border:1px solid #ccc;border-radius:8px;font-size:16px}
                button{margin-top:18px;width:100%%;padding:12px;border:0;border-radius:8px;background:#7c3aed;color:#fff;font-size:16px;cursor:pointer}
                a{color:#7c3aed}</style></head><body><main><h1>%1$s</h1>%2$s</main></body></html>""".formatted(title, body);
    }
}
