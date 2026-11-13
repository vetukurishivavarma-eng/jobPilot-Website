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
    private final String keyId, keySecret, webhookSecret, upiId;

    @org.springframework.beans.factory.annotation.Autowired
    Billing(PurchaseRepository purchases, @Value("${jobpilot.razorpay-key-id:}") String keyId, @Value("${jobpilot.razorpay-key-secret:}") String keySecret,
            @Value("${jobpilot.razorpay-webhook-secret:}") String webhookSecret, @Value("${jobpilot.upi-id:}") String upiId) {
        this.purchases = purchases;
        this.keyId = keyId.strip();
        this.keySecret = keySecret.strip();
        this.webhookSecret = webhookSecret.strip();
        this.upiId = upiId.strip();
    }

    Billing(PurchaseRepository purchases, String keyId, String keySecret, String webhookSecret) { this(purchases, keyId, keySecret, webhookSecret, ""); }

    /** Payments are on (credits needed, free daily limits apply): UPI QR or Razorpay configured. */
    public boolean enabled() { return upiOn() || razorpayOn(); }

    /** UPI QR (approved by hand) wins over Razorpay while UPI_ID is set. */
    public boolean upiOn() { return upiId.matches("[A-Za-z0-9._-]{2,256}@[A-Za-z0-9]{2,64}"); }

    boolean razorpayOn() { return !keyId.isEmpty() && !keySecret.isEmpty(); }

    /** UPI, RAZORPAY, or null when payments are off. */
    public String mode() { return upiOn() ? "UPI" : razorpayOn() ? "RAZORPAY" : null; }

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
        if (!razorpayOn() || upiOn()) throw new ApiException(HttpStatus.CONFLICT, "BILLING_OFF", "Card/Razorpay payments aren't switched on; pay by UPI QR.");
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
        if (!razorpayOn() || orderId == null || paymentId == null || signature == null) return false;
        if (!MessageDigest.isEqual(hmac(orderId + "|" + paymentId).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII))) {
            log.warn("Razorpay signature mismatch for order {}", orderId);
            return false;
        }
        Purchase p = purchases.findByOrderId(orderId).orElse(null);
        if (p == null) return false;
        markPaid(p, paymentId, "checkout");
        return true;
    }

    void markPaid(Purchase p, String paymentId, String via) {
        if (p.status == Purchase.Status.PAID) return; // checkout callback and webhook both arrive: count once
        p.status = Purchase.Status.PAID;
        p.paymentId = paymentId;
        p.paidAt = Instant.now();
        Plan plan = Plan.valueOf(p.plan);
        if (plan.validFor != null) p.expiresAt = p.paidAt.plus(plan.validFor);
        purchases.save(p);
        log.info("Purchase {} paid via {}: {} ({} paise)", p.id, via, p.plan, p.amountPaise);
    }

    public enum Hook { OK, BAD_SIGNATURE, OFF }

    /**
     * Razorpay's server-to-server webhook, so a payment counts even when the buyer closes the tab before checkout's
     * callback runs. Signature = HMAC-SHA256(raw body, webhook secret). payment.captured / order.paid mark the
     * order's purchase paid when the amount and currency match what we asked for; anything else is acknowledged.
     */
    @Transactional
    public Hook webhook(String rawBody, String signature) {
        if (!razorpayOn() || webhookSecret.isEmpty()) return Hook.OFF;
        if (rawBody == null || signature == null
                || !MessageDigest.isEqual(hmac(webhookSecret, rawBody).getBytes(StandardCharsets.US_ASCII), signature.strip().getBytes(StandardCharsets.US_ASCII))) {
            log.warn("Razorpay webhook signature mismatch");
            return Hook.BAD_SIGNATURE;
        }
        JsonNode root = JSON.readTree(rawBody);
        String event = root.path("event").asString("");
        if (!event.equals("payment.captured") && !event.equals("order.paid")) return Hook.OK;
        JsonNode pay = root.path("payload").path("payment").path("entity");
        String orderId = pay.path("order_id").asString(null), paymentId = pay.path("id").asString(null);
        if (orderId == null) return Hook.OK;
        Purchase p = purchases.findByOrderId(orderId).orElse(null);
        if (p == null) { log.warn("Razorpay webhook for unknown order {}", orderId); return Hook.OK; }
        if (pay.path("amount").asLong(-1) != p.amountPaise || !"INR".equals(pay.path("currency").asString(""))) {
            log.warn("Razorpay webhook amount/currency mismatch for order {}: {} {}", orderId, pay.path("amount"), pay.path("currency"));
            return Hook.OK;
        }
        markPaid(p, paymentId, "webhook " + event);
        return Hook.OK;
    }

    String hmac(String data) { return hmac(keySecret, data); }

    static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
