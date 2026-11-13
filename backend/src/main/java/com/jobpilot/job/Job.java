package com.jobpilot.job;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A posting, shared by every user it is linked to (see {@link UserJob}). Unique by (source, externalId). */
@Entity
@Table(name = "jobs")
public class Job {
    @Id
    UUID id = UUID.randomUUID();
    String source;
    String externalId;
    String board;
    String url;
    String applyUrl;
    String title;
    String company;
    String location;
    String workplace;
    String employmentType;
    Long salaryMin;
    Long salaryMax;
    String salaryCurrency;
    String description;
    Instant postedAt;
    Instant firstSeenAt;
    Instant lastSeenAt;
    Instant closedAt;
    Integer minYears;
    String seniority;
    String educationLevel;

    @ElementCollection
    @CollectionTable(name = "job_skills", joinColumns = @JoinColumn(name = "job_id"))
    @OrderColumn(name = "position")
    @Column(name = "skill")
    @BatchSize(size = 100)
    List<String> skills = new ArrayList<>();

    protected Job() {}

    Job(JobDraft d, Instant now) {
        source = d.source();
        externalId = cut(d.externalId(), 300);
        firstSeenAt = now;
        apply(d, now);
    }

    /** Copies a fresh read of the posting onto this row; re-parses requirements only when the text changed. */
    void apply(JobDraft d, Instant now) {
        String newTitle = cut(d.title().strip(), 300);
        boolean textChanged = !Objects.equals(title, newTitle) || !Objects.equals(description, d.description());
        board = cut(d.board(), 120);
        url = cut(d.url(), 1000);
        applyUrl = cut(d.applyUrl(), 1000);
        title = newTitle;
        company = cut(d.company(), 200);
        location = cut(MatchScorer.scopedLocation(d.location(), d.workplace(), d.description()), 300);
        workplace = d.workplace();
        employmentType = cut(d.employmentType(), 40);
        salaryMin = d.salaryMin();
        salaryMax = d.salaryMax();
        salaryCurrency = d.salaryCurrency() != null && d.salaryCurrency().length() == 3 ? d.salaryCurrency() : null;
        description = d.description();
        if (d.postedAt() != null) postedAt = d.postedAt();
        lastSeenAt = now;
        closedAt = null;
        if (textChanged) {
            JdParser.Parsed p = JdParser.parse(title, description);
            skills.clear();
            skills.addAll(p.skills());
            minYears = p.minYears();
            seniority = p.seniority();
            educationLevel = p.educationLevel();
        }
    }

    // read-only view for the apply agent
    public UUID id() { return id; }
    public String source() { return source; }
    public String externalId() { return externalId; }
    public String board() { return board; }
    public String url() { return url; }
    public String title() { return title; }
    public String company() { return company; }
    public String location() { return location; }
    public String workplace() { return workplace; }
    public String description() { return description; }
    public Instant closedAt() { return closedAt; }

    static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
