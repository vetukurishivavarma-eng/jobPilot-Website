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

    @Test
    void offMeansFreeOnMeansPaid() {
        UUID u = UUID.randomUUID();
        assertFalse(off.enabled());
        off.consume(u); // free, no exception

        Billing on = new Billing(purchases, "rzp_test_x", "s3cret");
        ApiException e = assertThrows(ApiException.class, () -> on.consume(u));
        assertEquals("PAYMENT_REQUIRED", e.code);
        assertEquals(List.of(Plan.FIRST, Plan.WEEKLY, Plan.PRO), on.plans(u)); // never paid -> the Rs 10 first interview
    }

    @Test
    void signedPaymentGivesCreditsAndOnlyThen() {
        Billing on = new Billing(purchases, "rzp_test_x", "s3cret");
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
        Billing on = new Billing(purchases, "rzp_test_x", "s3cret");
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
}
