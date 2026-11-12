package com.jobpilot.digest;

import com.jobpilot.apply.ApplicationService;
import com.jobpilot.auth.PasswordResetController;
import com.jobpilot.auth.User;
import com.jobpilot.auth.UserRepository;
import com.jobpilot.config.AppProperties;
import com.jobpilot.job.MatchService;
import com.jobpilot.shared.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.jobpilot.shared.Mailer.esc;

/**
 * Daily email: relevant jobs found since the last digest + follow-ups due. Nothing new = no email.
 * Every email has a one-click unsubscribe link signed with the JWT secret.
 */
@RestController
public class DigestController {
    private static final Logger log = LoggerFactory.getLogger(DigestController.class);
    static final int MAX_JOBS = 8;

    private final UserRepository users;
    private final MatchService match;
    private final ApplicationService apps;
    private final Mailer mailer;
    private final byte[] secret;
    private final String publicUrl;

    DigestController(UserRepository users, MatchService match, ApplicationService apps, Mailer mailer, AppProperties props,
                     @Value("${jobpilot.public-url}") String publicUrl) {
        this.users = users;
        this.match = match;
        this.apps = apps;
        this.mailer = mailer;
        this.secret = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    // ponytail: one pass over every user on one instance, sequential; batch/queue it past a few thousand users (Brevo free = 300/day)
    @Scheduled(cron = "${jobpilot.digest-cron:0 0 9 * * *}", zone = "Asia/Kolkata")
    public void sendAll() {
        if (!mailer.configured()) return;
        int sent = 0;
        for (UUID id : users.allIds()) {
            try { if (sendOne(id)) sent++; } catch (RuntimeException e) { log.warn("Digest for {} failed: {}", id, e.toString()); }
        }
        log.info("Digest: {} emails sent", sent);
    }

    /** true when an email went out. */
    boolean sendOne(UUID id) {
        User u = users.findById(id).orElse(null);
        if (u == null || !u.isEmailDigest()) return false;
        Instant now = Instant.now();
        Instant since = u.getDigestSentAt() != null ? u.getDigestSentAt() : u.getCreatedAt();
        if (since.isBefore(now.minus(Duration.ofDays(7)))) since = now.minus(Duration.ofDays(7));
        List<MatchService.NewMatch> jobs = match.newMatches(id, since, MAX_JOBS);
        List<String> due = apps.followUpsDue(id);
        if (jobs.isEmpty() && due.isEmpty()) return false;
        if (!mailer.send(u.getEmail(), subject(jobs, due), html(u.getFullName(), jobs, due, unsubscribeLink(id)))) return false;
        u.setDigestSentAt(now);
        users.save(u);
        return true;
    }

    static String subject(List<MatchService.NewMatch> jobs, List<String> due) {
        if (jobs.isEmpty()) return due.size() == 1 ? "Time to follow up on 1 application" : "Time to follow up on " + due.size() + " applications";
        MatchService.NewMatch top = jobs.get(0);
        return jobs.size() == 1 ? "New match: " + top.title() + " at " + top.company()
                : jobs.size() + " new jobs for you, including " + top.title() + " at " + top.company();
    }

    static String html(String name, List<MatchService.NewMatch> jobs, List<String> due, String unsubscribe) {
        StringBuilder b = new StringBuilder("<div style=\"font-family:system-ui,sans-serif;max-width:560px;color:#1c1b1f\">");
        b.append("<p>Hi ").append(esc(name == null ? "there" : name.split(" ")[0])).append(",</p>");
        if (!jobs.isEmpty()) {
            b.append("<h2 style=\"font-size:18px\">New jobs that fit your resume</h2><table style=\"border-collapse:collapse;width:100%\">");
            for (MatchService.NewMatch j : jobs) {
                b.append("<tr><td style=\"padding:8px 10px 8px 0;vertical-align:top\"><b style=\"display:inline-block;min-width:34px;padding:4px 6px;border-radius:8px;background:#ede9fe;color:#7c3aed;text-align:center\">")
                        .append(j.score() == null ? "–" : j.score()).append("</b></td><td style=\"padding:8px 0\">");
                String title = esc(j.title());
                b.append(j.url() == null ? "<b>" + title + "</b>" : "<a href=\"" + esc(j.url()) + "\" style=\"color:#7c3aed;font-weight:bold\">" + title + "</a>");
                b.append("<br><span style=\"color:#666\">").append(esc(j.company()));
                if (j.location() != null) b.append(" · ").append(esc(j.location()));
                b.append("</span></td></tr>");
            }
            b.append("</table><p>Open the JobPilot app to tailor your resume, do a mock interview, or apply.</p>");
        }
        if (!due.isEmpty()) {
            b.append("<h2 style=\"font-size:18px\">Follow-ups due</h2><ul>");
            for (String d : due) b.append("<li>").append(esc(d)).append("</li>");
            b.append("</ul><p>A short, polite follow-up often gets a reply. JobPilot can draft one for you on the application.</p>");
        }
        b.append("<p style=\"color:#888;font-size:12px;margin-top:24px\">You get this at most once a day, only when there's something new. ")
                .append("<a href=\"").append(esc(unsubscribe)).append("\" style=\"color:#888\">Unsubscribe</a></p></div>");
        return b.toString();
    }

    String unsubscribeLink(UUID id) { return publicUrl + "/unsubscribe?u=" + id + "&t=" + sign(id); }

    @GetMapping(value = "/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public String unsubscribe(@RequestParam(defaultValue = "") String u, @RequestParam(defaultValue = "") String t) {
        UUID id;
        try { id = UUID.fromString(u); } catch (IllegalArgumentException e) { id = null; }
        if (id == null || !MessageDigest.isEqual(sign(id).getBytes(StandardCharsets.US_ASCII), t.getBytes(StandardCharsets.US_ASCII)))
            return PasswordResetController.page("Link not valid", "<p>This unsubscribe link is broken. Use the link from the latest email.</p>");
        users.findById(id).ifPresent(x -> { x.setEmailDigest(false); users.save(x); });
        return PasswordResetController.page("Unsubscribed", "<p>You won't get JobPilot's daily emails any more.</p>");
    }

    private String sign(UUID id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("unsubscribe|" + id).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
