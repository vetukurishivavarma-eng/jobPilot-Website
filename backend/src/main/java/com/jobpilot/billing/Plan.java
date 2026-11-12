package com.jobpilot.billing;

import java.time.Duration;

/** Live mock interview pricing (INR). FIRST is only for someone who has never paid. */
public enum Plan {
    FIRST(1000, 1, null, "First interview (new users)"),
    SINGLE(4900, 1, null, "One interview"),
    WEEKLY(20000, 4, Duration.ofDays(7), "4 interviews in 7 days");

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
