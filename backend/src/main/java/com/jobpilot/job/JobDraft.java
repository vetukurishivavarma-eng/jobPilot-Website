package com.jobpilot.job;

import java.time.Instant;

/** A job as read from a source, before it is stored. */
public record JobDraft(String source, String externalId, String board, String url, String applyUrl,
                       String title, String company, String location, String workplace, String employmentType,
                       Long salaryMin, Long salaryMax, String salaryCurrency, String description, Instant postedAt) {}
