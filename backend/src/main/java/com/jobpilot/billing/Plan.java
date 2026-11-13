package com.jobpilot.billing;

import java.time.Duration;

/**
 * Pricing (INR). FIRST is only for someone who has never paid. PRO = 30 days of Pro (no daily limits on AI tailoring
 * and auto-apply, see Billing.FREE_*) with 8 live interviews included.
 */
public enum Plan {
    FIRST(1000, 1, null, "First interview (new users)"),
    SINGLE(4900, 1, null, "One interview"),
    WEEKLY(20000, 4, Duration.ofDays(7), "4 interviews in 7 days"),
    PRO(19900, 8, Duration.ofDays(30), "Pro for 30 days: 8 live interviews + no daily limits");

    public final int paise, interviews;
    public final Duration validFor;
    public final String label;

    Plan(int paise, int interviews, Duration validFor, String label) {
        this.paise = paise;
        this.interviews = interviews;
        this.validFor = validFor;
        this.label = label;
    }
}
