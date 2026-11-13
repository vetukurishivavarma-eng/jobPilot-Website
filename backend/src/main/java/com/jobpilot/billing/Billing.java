package com.jobpilot.billing;

import com.jobpilot.shared.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
import java.util.*;

/**
 * Credits for live interviews, bought through Razorpay's hosted checkout (no card data ever touches JobPilot).
 * Off until RAZORPAY_KEY_ID + RAZORPAY_KEY_SECRET are set: then live interviews are free and nothing is charged.
 */
@Service
public class Billing {
    private static final Logger log = LoggerFactory.getLogger(Billing.class);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PurchaseRepository purchases;
    private final String keyId, keySecret;

    Billing(PurchaseRepository purchases, @Value("${jobpilot.razorpay-key-id:}") String keyId, @Value("${jobpilot.razorpay-key-secret:}") String keySecret) {
        this.purchases = purchases;
        this.keyId = keyId.strip();
        this.keySecret = keySecret.strip();
    }

    public boolean enabled() { return !keyId.isEmpty() && !keySecret.isEmpty(); }

    /** Free accounts, per day (only while payments are on): AI-tailored resumes/letters, and auto-applied jobs. */
    public static final int FREE_TAILORED_PER_DAY = 5, FREE_AUTO_APPLY_PER_DAY = 5;

    /** When the user's Pro ends (null = not Pro). */
    public Instant proUntil(UUID userId) {
        Instant now = Instant.now();
        return purchases.findByUserId(userId).stream()
                .filter(p -> p.status == Purchase.Status.PAID && Plan.PRO.name().equals(p.plan) && p.expiresAt != null && p.expiresAt.isAfter(now))
                .map(p -> p.expiresAt).max(Instant::compareTo).orElse(null);
    }

    /** No daily limits: Pro, or payments not switched on yet (everything is free then). */
    public boolean unlimited(UUID userId) { return !enabled() || proUntil(userId) != null; }

    /** 402 PRO_REQUIRED once a free account has used today's allowance of something. */
    public void allow(UUID userId, long usedToday, int freePerDay, String what) {
        if (usedToday < freePerDay || unlimited(userId)) return;
        throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "PRO_REQUIRED",
                "Free accounts get " + freePerDay + " " + what + " a day. Go Pro for no daily limits, or come back tomorrow.");
    }
    String keyId() { return keyId; }

    public int credits(UUID userId) {
        Instant now = Instant.now();
        return purchases.findByUserId(userId).stream().filter(p -> p.usable(now)).mapToInt(p -> p.interviews - p.used).sum();
    }

    /** Plans this user can buy now: FIRST only before their first payment, and then instead of SINGLE. */
    public List<Plan> plans(UUID userId) {
        return purchases.existsByUserIdAndStatus(userId, Purchase.Status.PAID) ? List.of(Plan.SINGLE, Plan.WEEKLY, Plan.PRO) : List.of(Plan.FIRST, Plan.WEEKLY, Plan.PRO);
    }

    /** Uses one credit (the soonest-expiring first). Free when billing is off. 402 when there are none. */
    @Transactional
    public void consume(UUID userId) {
        if (!enabled()) return;
        Instant now = Instant.now();
        Purchase p = purchases.findByUserId(userId).stream().filter(x -> x.usable(now))
                .min(Comparator.comparing((Purchase x) -> x.expiresAt == null ? Instant.MAX : x.expiresAt)).orElseThrow(() ->
                        new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_REQUIRED", "Buy an interview to start a live mock interview."));
        p.used++;
        purchases.save(p);
    }

    /** Creates a Razorpay order for the plan; the user pays on /pay/{purchaseId}. */
    @Transactional
    public Purchase order(UUID userId, Plan plan) {
        if (!enabled()) throw new ApiException(HttpStatus.CONFLICT, "BILLING_OFF", "Payments aren't switched on yet; live interviews are free for now.");
        if (!plans(userId).contains(plan)) throw new ApiException(HttpStatus.BAD_REQUEST, "PLAN_UNAVAILABLE", "That plan isn't available for your account.");
        Purchase p = purchases.save(new Purchase(userId, plan));
        var body = JSON.createObjectNode().put("amount", plan.paise).put("currency", "INR").put("receipt", p.id.toString().substring(0, 36));
        body.putObject("notes").put("plan", plan.name()).put("purchase", p.id.toString());
        try {
            HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.razorpay.com/v1/orders"))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8)))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode j = JSON.readTree(res.body());
            if (res.statusCode() / 100 != 2 || !j.path("id").isString()) {
                log.warn("Razorpay order failed: {} {}", res.statusCode(), res.body());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER", "Couldn't start the payment. Try again in a minute.");
            }
            p.orderId = j.path("id").asString();
            return purchases.save(p);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER", "Couldn't reach the payment provider. Try again in a minute.");
        }
    }

    Optional<Purchase> purchase(UUID id) { return purchases.findById(id); }

    /** Razorpay checkout's success callback: signature = HMAC-SHA256(order_id|payment_id, key secret). Idempotent. */
    @Transactional
    public boolean confirm(String orderId, String paymentId, String signature) {
        if (!enabled() || orderId == null || paymentId == null || signature == null) return false;
        if (!MessageDigest.isEqual(hmac(orderId + "|" + paymentId).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII))) {
            log.warn("Razorpay signature mismatch for order {}", orderId);
            return false;
        }
        Purchase p = purchases.findByOrderId(orderId).orElse(null);
        if (p == null) return false;
        if (p.status != Purchase.Status.PAID) {
            p.status = Purchase.Status.PAID;
            p.paymentId = paymentId;
            p.paidAt = Instant.now();
            Plan plan = Plan.valueOf(p.plan);
            if (plan.validFor != null) p.expiresAt = p.paidAt.plus(plan.validFor);
            purchases.save(p);
            log.info("Purchase {} paid: {} ({} paise)", p.id, p.plan, p.amountPaise);
        }
        return true;
    }

    String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
