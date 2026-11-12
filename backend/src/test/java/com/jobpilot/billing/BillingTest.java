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
        assertEquals(List.of(Plan.FIRST, Plan.WEEKLY), on.plans(u)); // never paid -> the Rs 10 first interview
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
        assertEquals(List.of(Plan.SINGLE, Plan.WEEKLY), on.plans(u)); // no more first-interview price

        for (int i = 0; i < 4; i++) on.consume(u);
        assertEquals(0, on.credits(u));
        assertThrows(ApiException.class, () -> on.consume(u));
    }
}
