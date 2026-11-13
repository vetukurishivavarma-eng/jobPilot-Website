package com.jobpilot.billing;

import com.jobpilot.auth.PasswordResetController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jobpilot.shared.Mailer.esc;

/** Plans + credits for the app, and the hosted Razorpay checkout page (/pay/{id}) the app opens in a browser. */
@RestController
public class BillingController {
    public record PlanDto(String id, String label, int rupees, int interviews, Integer days) {}
    /**
     * proUntil: when the user's Pro ends (null = not Pro); mode: UPI (QR, approved by hand) | RAZORPAY | null (off);
     * pending: UPI payments of theirs waiting for approval; admin: may approve payments.
     */
    public record Status(boolean enabled, int credits, List<PlanDto> plans, java.time.Instant proUntil, String mode, long pending, boolean admin) {}
    /** A UPI payment to make: open upiUrl on a phone, or scan qrUrl's QR; then send the UTR. */
    public record UpiOrder(UUID purchaseId, String upiUrl, String qrUrl, int rupees, String ref, String label) {}
    public record Utr(@NotNull @jakarta.validation.constraints.Size(max = 30) String utr) {}
    public record PendingDto(UUID id, String email, String plan, int rupees, String utr, String ref, java.time.Instant createdAt) {}
    public record OrderRequest(@NotNull Plan plan) {}
    public record Order(UUID purchaseId, String payUrl) {}
    public record Verify(String razorpay_order_id, String razorpay_payment_id, String razorpay_signature) {}

    private final Billing billing;
    private final UpiPayments upi;
    private final com.jobpilot.auth.UserRepository users;

    BillingController(Billing billing, UpiPayments upi, com.jobpilot.auth.UserRepository users) {
        this.billing = billing;
        this.upi = upi;
        this.users = users;
    }

    private String emailOf(UUID userId) { return users.findById(userId).map(com.jobpilot.auth.User::getEmail).orElse(null); }

    private void requireAdmin(UUID userId) {
        if (!upi.isAdmin(emailOf(userId))) throw new com.jobpilot.shared.ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Admins only");
    }

    @GetMapping("/api/billing")
    public Status status(@AuthenticationPrincipal UUID userId) {
        return new Status(billing.enabled(), billing.credits(userId), billing.plans(userId).stream()
                .map(p -> new PlanDto(p.name(), p.label, p.paise / 100, p.interviews, p.validFor == null ? null : (int) p.validFor.toDays())).toList(),
                billing.proUntil(userId), billing.mode(), upi.pendingFor(userId), upi.isAdmin(emailOf(userId)));
    }

    // ------------------------------------------------------------ UPI QR, approved by hand

    @PostMapping("/api/billing/upi")
    public UpiOrder upiOrder(@AuthenticationPrincipal UUID userId, @Valid @RequestBody OrderRequest body) {
        Purchase p = upi.start(userId, body.plan());
        return new UpiOrder(p.id, upi.link(p), "/pay/" + p.id + "/qr.svg", p.amountPaise / 100, UpiPayments.ref(p), body.plan().label);
    }

    /** Public: the id is an unguessable UUID and the QR only holds the payee's UPI ID and the amount. */
    @GetMapping(value = "/pay/{id}/qr.svg", produces = "image/svg+xml")
    public org.springframework.http.ResponseEntity<String> qr(@PathVariable UUID id) {
        Purchase p = upi.find(id).filter(x -> x.orderId != null && x.orderId.startsWith("upi_") && x.status == Purchase.Status.CREATED).orElse(null);
        if (p == null || !upi.on()) return org.springframework.http.ResponseEntity.notFound().build();
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control", "no-store").body(upi.qrSvg(p));
    }

    @PostMapping("/api/billing/upi/{id}/utr")
    public Status claim(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @Valid @RequestBody Utr body) {
        upi.claim(userId, id, body.utr(), emailOf(userId));
        return status(userId);
    }

    @GetMapping("/api/admin/payments")
    public List<PendingDto> pending(@AuthenticationPrincipal UUID userId) {
        requireAdmin(userId);
        return upi.pending().stream().map(p -> new PendingDto(p.id, emailOf(p.userId), Plan.valueOf(p.plan).label, p.amountPaise / 100,
                p.paymentId == null ? null : p.paymentId.replaceFirst("^upi:", ""), UpiPayments.ref(p), p.createdAt)).toList();
    }

    @PostMapping("/api/admin/payments/{id}/approve")
    public Map<String, Boolean> approve(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        requireAdmin(userId);
        upi.approve(id);
        return Map.of("ok", true);
    }

    @PostMapping("/api/admin/payments/{id}/reject")
    public Map<String, Boolean> reject(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        requireAdmin(userId);
        upi.reject(id);
        return Map.of("ok", true);
    }

    @PostMapping("/api/billing/orders")
    public Order order(@AuthenticationPrincipal UUID userId, @Valid @RequestBody OrderRequest body) {
        Purchase p = billing.order(userId, body.plan());
        return new Order(p.id, "/pay/" + p.id);
    }

    /** Public: the purchase id is an unguessable UUID, and a payment only counts after its signature checks out. */
    @GetMapping(value = "/pay/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public String page(@PathVariable UUID id) {
        Purchase p = billing.purchase(id).orElse(null);
        if (p == null || p.orderId == null) return PasswordResetController.page("Payment not found", "<p>This payment link is not valid. Start again from the app.</p>");
        if (p.status == Purchase.Status.PAID) return PasswordResetController.page("Paid", "<p>Payment received. Go back to JobPilot and start your interview.</p>");
        Plan plan = Plan.valueOf(p.plan);
        return PasswordResetController.page("JobPilot · " + esc(plan.label), """
                <p style="font-size:28px;font-weight:700;margin:0">₹%d</p>
                <p>%s. Live AI mock interview with report and recording.</p>
                <button id="pay">Pay ₹%d</button>
                <p id="msg" style="color:#555;font-size:14px"></p>
                <script src="https://checkout.razorpay.com/v1/checkout.js"></script>
                <script>
                const msg = t => document.getElementById('msg').textContent = t
                const rzp = new Razorpay({ key: %s, order_id: %s, amount: %d, currency: 'INR', name: 'JobPilot', description: %s,
                  theme: { color: '#1F6F5C' },
                  handler: r => fetch('/pay/verify', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(r) })
                    .then(x => x.json()).then(x => x.ok ? (document.querySelector('main').innerHTML = '<h1>Paid</h1><p>Payment received. Go back to JobPilot and start your interview.</p>')
                      : msg('We could not confirm the payment. If money was taken, contact support with your payment id: ' + r.razorpay_payment_id)) })
                rzp.on('payment.failed', e => msg('Payment failed: ' + (e.error && e.error.description || 'try again')))
                document.getElementById('pay').onclick = () => rzp.open()
                </script>""".formatted(plan.paise / 100, esc(plan.label), plan.paise / 100,
                js(billing.keyId()), js(p.orderId), plan.paise, js(plan.label)));
    }

    /** Razorpay -> JobPilot (public; the signature is the authentication). 400 only for a bad signature, so Razorpay retries nothing else. */
    @PostMapping("/pay/webhook")
    public org.springframework.http.ResponseEntity<Void> webhook(@RequestBody String raw, @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        Billing.Hook h = billing.webhook(raw, signature);
        return h == Billing.Hook.BAD_SIGNATURE ? org.springframework.http.ResponseEntity.badRequest().build()
                : h == Billing.Hook.OFF ? org.springframework.http.ResponseEntity.status(503).build() : org.springframework.http.ResponseEntity.ok().build();
    }

    @PostMapping("/pay/verify")
    public Map<String, Boolean> verify(@RequestBody Verify v) {
        return Map.of("ok", billing.confirm(v.razorpay_order_id(), v.razorpay_payment_id(), v.razorpay_signature()));
    }

    /** A JS string literal (values are our own ids/labels, escaped anyway). */
    private static String js(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\u003c") + "'";
    }
}
