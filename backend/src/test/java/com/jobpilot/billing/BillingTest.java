package com.jobpilot.billing;

import com.jobpilot.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class BillingTest {
    @Autowired PurchaseRepository purchases;
    @Autowired Billing off; // no keys in tests
    @Autowired com.jobpilot.shared.Mailer mailer; // no Brevo key in tests: sends nothing

    @Test
    void offMeansFreeOnMeansPaid() {
        UUID u = UUID.randomUUID();
        assertFalse(off.enabled());
        off.consume(u); // free, no exception

        Billing on = new Billing(purchases, "rzp_test_x", "s3cret", "wh00k");
        ApiException e = assertThrows(ApiException.class, () -> on.consume(u));
        assertEquals("PAYMENT_REQUIRED", e.code);
        assertEquals(List.of(Plan.FIRST, Plan.WEEKLY, Plan.PRO), on.plans(u)); // never paid -> the Rs 10 first interview
    }

    @Test
    void signedPaymentGivesCreditsAndOnlyThen() {
        Billing on = new Billing(purchases, "rzp_test_x", "s3cret", "wh00k");
        UUID u = UUID.randomUUID();
        Purchase p = new Purchase(u, Plan.WEEKLY);
        p.orderId = "order_" + UUID.randomUUID().toString().substring(0, 8);
        purchases.save(p);

        assertFalse(on.confirm(p.orderId, "pay_1", "forged"));
        assertEquals(0, on.credits(u));
        assertTrue(on.confirm(p.orderId, "pay_1", on.hmac(p.orderId + "|pay_1")));
        assertTrue(on.confirm(p.orderId, "pay_1", on.hmac(p.orderId + "|pay_1"))); // idempotent
        assertEquals(4, on.credits(u));
        Purchase paid = purchases.findById(p.id).orElseThrow();
        assertTrue(paid.expiresAt.isAfter(Instant.now().plus(Duration.ofDays(6))));
        assertEquals(List.of(Plan.SINGLE, Plan.WEEKLY, Plan.PRO), on.plans(u)); // no more first-interview price

        for (int i = 0; i < 4; i++) on.consume(u);
        assertEquals(0, on.credits(u));
        assertThrows(ApiException.class, () -> on.consume(u));
    }

    @Test
    void proLiftsDailyLimitsOnlyWhilePaidAndActive() {
        Billing on = new Billing(purchases, "rzp_test_x", "s3cret", "wh00k");
        UUID u = UUID.randomUUID();
        assertTrue(off.unlimited(u), "payments off: everything is free");
        assertFalse(on.unlimited(u));
        on.allow(u, 4, 5, "things"); // under the free allowance
        ApiException e = assertThrows(ApiException.class, () -> on.allow(u, 5, 5, "things"));
        assertEquals("PRO_REQUIRED", e.code);

        Purchase p = new Purchase(u, Plan.PRO);
        p.orderId = "order_" + UUID.randomUUID().toString().substring(0, 8);
        purchases.save(p);
        assertNull(on.proUntil(u), "not Pro until paid");
        assertTrue(on.confirm(p.orderId, "pay_2", on.hmac(p.orderId + "|pay_2")));
        assertTrue(on.proUntil(u).isAfter(Instant.now().plus(Duration.ofDays(29))));
        assertTrue(on.unlimited(u));
        on.allow(u, 500, 5, "things");
        assertEquals(8, on.credits(u), "Pro includes 8 live interviews");

        Purchase expired = purchases.findById(p.id).orElseThrow();
        expired.expiresAt = Instant.now().minusSeconds(1);
        purchases.save(expired);
        assertNull(on.proUntil(u));
        assertFalse(on.unlimited(u));
    }

    @Test
    void webhookCountsAPaymentOnceAndOnlyWhenSignedAndExact() {
        Billing on = new Billing(purchases, "rzp_test_x", "s3cret", "wh00k");
        UUID u = UUID.randomUUID();
        Purchase p = new Purchase(u, Plan.SINGLE);
        p.orderId = "order_" + UUID.randomUUID().toString().substring(0, 8);
        purchases.save(p);
        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_9\",\"order_id\":\"" + p.orderId
                + "\",\"amount\":4900,\"currency\":\"INR\",\"status\":\"captured\"}}}}";

        assertEquals(Billing.Hook.BAD_SIGNATURE, on.webhook(body, "forged"));
        assertEquals(Billing.Hook.BAD_SIGNATURE, on.webhook(body, Billing.hmac("s3cret", body)), "the key secret is not the webhook secret");
        assertEquals(0, on.credits(u));

        String cheap = body.replace("4900", "100");
        assertEquals(Billing.Hook.OK, on.webhook(cheap, Billing.hmac("wh00k", cheap)));
        assertEquals(0, on.credits(u), "amount must match the order");

        assertEquals(Billing.Hook.OK, on.webhook(body, Billing.hmac("wh00k", body)));
        assertEquals(Billing.Hook.OK, on.webhook(body, Billing.hmac("wh00k", body)), "retried delivery");
        assertTrue(on.confirm(p.orderId, "pay_9", on.hmac(p.orderId + "|pay_9")), "checkout callback after the webhook");
        assertEquals(1, on.credits(u), "counted once");
        assertEquals("pay_9", purchases.findById(p.id).orElseThrow().paymentId);

        assertEquals(Billing.Hook.OFF, new Billing(purchases, "rzp_test_x", "s3cret", "").webhook(body, "x"), "no webhook secret set");
    }

    @Test
    void upiPaymentStartsAtOnceAndARevokeEndsIt() {
        Billing on = new Billing(purchases, "", "", "", "owner.pay@okhdfcbank");
        UpiPayments upi = new UpiPayments(purchases, on, mailer, "owner.pay@okhdfcbank", "JobPilot", "Boss@Example.com");
        assertTrue(on.enabled() && on.upiOn());
        assertEquals("UPI", on.mode());
        assertTrue(upi.isAdmin("boss@example.com"));
        assertFalse(upi.isAdmin("someone@example.com"));
        assertEquals(Billing.Hook.OFF, on.webhook("{}", "x"), "Razorpay stays off");

        UUID u = UUID.randomUUID();
        Purchase p = upi.start(u, Plan.PRO);
        String link = upi.link(p);
        assertTrue(link.startsWith("upi://pay?pa=owner.pay%40okhdfcbank&pn=JobPilot&am=199.00&cu=INR&tn=JobPilot%20"), link);
        assertTrue(upi.qrSvg(p).startsWith("<svg"));

        ApiException bad = assertThrows(ApiException.class, () -> upi.claim(u, p.id, "12345", "buyer@example.com"));
        assertEquals("BAD_UTR", bad.code);
        assertThrows(ApiException.class, () -> upi.claim(UUID.randomUUID(), p.id, "123456789012", "x@example.com"), "someone else's payment");
        String utr = String.valueOf(100000000000L + (System.nanoTime() % 899999999999L));
        upi.claim(u, p.id, utr, "buyer@example.com");
        assertEquals(8, on.credits(u), "trust first: active at once");
        assertNotNull(on.proUntil(u));
        assertTrue(upi.toCheck().stream().anyMatch(x -> x.id.equals(p.id)), "listed for the admin to check");

        Purchase other = upi.start(u, Plan.WEEKLY);
        assertEquals("UTR_USED", assertThrows(ApiException.class, () -> upi.claim(u, other.id, utr, "buyer@example.com")).code);

        upi.received(p.id);
        assertTrue(upi.toCheck().stream().noneMatch(x -> x.id.equals(p.id)));
        assertEquals("NOT_TO_CHECK", assertThrows(ApiException.class, () -> upi.revoke(p.id)).code, "checked ones stay");

        String utr2 = String.valueOf(Long.parseLong(utr) + 1 > 999999999999L ? 100000000001L : Long.parseLong(utr) + 1);
        upi.claim(u, other.id, utr2, "buyer@example.com");
        assertEquals(12, on.credits(u));
        upi.revoke(other.id);
        assertEquals(8, on.credits(u), "revoked: its credits stop counting");
        assertEquals("REVOKED", assertThrows(ApiException.class, () -> upi.claim(u, other.id, utr2, "buyer@example.com")).code);
    }
}
