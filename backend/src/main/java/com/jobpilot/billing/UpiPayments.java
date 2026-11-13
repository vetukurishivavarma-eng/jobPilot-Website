package com.jobpilot.billing;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.jobpilot.shared.ApiException;
import com.jobpilot.shared.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Payment by UPI QR to the owner's own UPI ID, trust first: the buyer pays the exact amount and enters the 12-digit
 * UPI reference (UTR); the plan starts at once (JobPilot can't see the owner's bank). Admins (ADMIN_EMAILS) get an
 * email and, on /admin/payments, mark it received or revoke it (the plan ends). A UTR works once. On when UPI_ID is
 * set; Razorpay stays built but unused.
 */
@Service
public class UpiPayments {
    private static final Logger log = LoggerFactory.getLogger(UpiPayments.class);

    private final PurchaseRepository purchases;
    private final Billing billing;
    private final Mailer mailer;
    private final String upiId, payee;
    private final Set<String> admins;

    UpiPayments(PurchaseRepository purchases, Billing billing, Mailer mailer, @Value("${jobpilot.upi-id:}") String upiId,
                @Value("${jobpilot.upi-name:JobPilot}") String payee, @Value("${jobpilot.admin-emails:}") String admins) {
        this.purchases = purchases;
        this.billing = billing;
        this.mailer = mailer;
        this.upiId = upiId.strip();
        this.payee = payee.isBlank() ? "JobPilot" : payee.strip();
        this.admins = new HashSet<>();
        for (String a : admins.split(",")) if (!a.isBlank()) this.admins.add(a.strip().toLowerCase(Locale.ROOT));
    }

    public boolean on() { return billing.upiOn(); }

    public boolean isAdmin(String email) { return email != null && admins.contains(email.toLowerCase(Locale.ROOT)); }

    /** A purchase waiting for the buyer's UPI payment. */
    @Transactional
    public Purchase start(UUID userId, Plan plan) {
        if (!on()) throw new ApiException(HttpStatus.CONFLICT, "BILLING_OFF", "Payments aren't switched on yet; everything is free for now.");
        if (!billing.plans(userId).contains(plan)) throw new ApiException(HttpStatus.BAD_REQUEST, "PLAN_UNAVAILABLE", "That plan isn't available for your account.");
        Purchase p = new Purchase(userId, plan);
        p.orderId = "upi_" + p.id.toString().replace("-", "").substring(0, 20); // unique, shown as the payment note
        return purchases.save(p);
    }

    /** Short reference the buyer sees and the UPI note carries, to match the payment in the bank app. */
    public static String ref(Purchase p) { return p.id.toString().substring(0, 8).toUpperCase(Locale.ROOT); }

    /** upi://pay link: opens any UPI app with payee, exact amount and note filled in. */
    public String link(Purchase p) {
        String rupees = String.format(Locale.ROOT, "%d.%02d", p.amountPaise / 100, p.amountPaise % 100);
        return "upi://pay?pa=" + enc(upiId) + "&pn=" + enc(payee) + "&am=" + rupees + "&cu=INR&tn=" + enc("JobPilot " + ref(p));
    }

    /** The link as a QR code, SVG (no image libraries needed). */
    public String qrSvg(Purchase p) {
        try {
            BitMatrix m = new QRCodeWriter().encode(link(p), BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            StringBuilder path = new StringBuilder();
            for (int y = 0; y < m.getHeight(); y++)
                for (int x = 0; x < m.getWidth(); x++)
                    if (m.get(x, y)) path.append('M').append(x).append(',').append(y).append("h1v1h-1z");
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + m.getWidth() + " " + m.getHeight() + "\" shape-rendering=\"crispEdges\">"
                    + "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\"" + path + "\"/></svg>";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The buyer paid: the plan starts now; admins are asked to check the UTR. A UTR can only be claimed once. */
    @Transactional
    public Purchase claim(UUID userId, UUID purchaseId, String utr, String buyerEmail) {
        Purchase p = purchases.findById(purchaseId).filter(x -> x.userId.equals(userId) && x.orderId != null && x.orderId.startsWith("upi_"))
                .orElseThrow(() -> ApiException.notFound("Payment"));
        String u = utr == null ? "" : utr.replaceAll("\\s", "");
        if (!u.matches("\\d{12}")) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_UTR", "Enter the 12-digit UPI reference number (UTR) from your UPI app's payment details.");
        if (p.status == Purchase.Status.PAID) return p;
        if (p.status == Purchase.Status.REVOKED) throw new ApiException(HttpStatus.CONFLICT, "REVOKED", "This payment was not received. Start a new one.");
        if (purchases.existsByPaymentId("upi:" + u)) throw new ApiException(HttpStatus.CONFLICT, "UTR_USED", "That UPI reference was already used for another payment.");
        billing.markPaid(p, "upi:" + u, "upi (trust first)");
        for (String a : admins)
            mailer.send(a, "JobPilot: UPI payment of Rs " + p.amountPaise / 100 + " to check",
                    "<p>" + esc(buyerEmail) + " says they paid <b>Rs " + p.amountPaise / 100 + "</b> for " + esc(Plan.valueOf(p.plan).label)
                            + ". Their plan is active now.</p><p>UTR: <b>" + u + "</b> &middot; note: JobPilot " + ref(p)
                            + "</p><p>Check your UPI app. Not there? Revoke it in JobPilot: /admin/payments.</p>");
        return p;
    }

    /** UPI payments not checked yet, oldest first. */
    public List<Purchase> toCheck() {
        return purchases.findByStatusAndPaymentIdStartingWithAndCheckedAtIsNullOrderByPaidAtAsc(Purchase.Status.PAID, "upi:");
    }

    private Purchase unchecked(UUID purchaseId) {
        Purchase p = purchases.findById(purchaseId).orElseThrow(() -> ApiException.notFound("Payment"));
        if (p.status != Purchase.Status.PAID || p.paymentId == null || !p.paymentId.startsWith("upi:") || p.checkedAt != null)
            throw new ApiException(HttpStatus.CONFLICT, "NOT_TO_CHECK", "This payment was already checked.");
        return p;
    }

    /** Admin saw the money arrive. */
    @Transactional
    public void received(UUID purchaseId) {
        Purchase p = unchecked(purchaseId);
        p.checkedAt = Instant.now();
        purchases.save(p);
    }

    /** The money never arrived: the plan ends now (credits and Pro stop counting). */
    @Transactional
    public void revoke(UUID purchaseId) {
        Purchase p = unchecked(purchaseId);
        p.status = Purchase.Status.REVOKED;
        p.checkedAt = Instant.now();
        purchases.save(p);
        log.info("UPI payment revoked: purchase {} ({})", p.id, p.paymentId);
    }

    Optional<Purchase> find(UUID id) { return purchases.findById(id); }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
