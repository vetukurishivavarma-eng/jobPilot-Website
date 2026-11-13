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
    /** proUntil: when the user's Pro ends (null = not Pro). */
    public record Status(boolean enabled, int credits, List<PlanDto> plans, java.time.Instant proUntil) {}
    public record OrderRequest(@NotNull Plan plan) {}
    public record Order(UUID purchaseId, String payUrl) {}
    public record Verify(String razorpay_order_id, String razorpay_payment_id, String razorpay_signature) {}

    private final Billing billing;

    BillingController(Billing billing) { this.billing = billing; }

    @GetMapping("/api/billing")
    public Status status(@AuthenticationPrincipal UUID userId) {
        return new Status(billing.enabled(), billing.credits(userId), billing.plans(userId).stream()
                .map(p -> new PlanDto(p.name(), p.label, p.paise / 100, p.interviews, p.validFor == null ? null : (int) p.validFor.toDays())).toList(),
                billing.proUntil(userId));
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
                  theme: { color: '#7c3aed' },
                  handler: r => fetch('/pay/verify', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(r) })
                    .then(x => x.json()).then(x => x.ok ? (document.querySelector('main').innerHTML = '<h1>Paid</h1><p>Payment received. Go back to JobPilot and start your interview.</p>')
                      : msg('We could not confirm the payment. If money was taken, contact support with your payment id: ' + r.razorpay_payment_id)) })
                rzp.on('payment.failed', e => msg('Payment failed: ' + (e.error && e.error.description || 'try again')))
                document.getElementById('pay').onclick = () => rzp.open()
                </script>""".formatted(plan.paise / 100, esc(plan.label), plan.paise / 100,
                js(billing.keyId()), js(p.orderId), plan.paise, js(plan.label)));
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
