package com.jobpilot.insights;

import com.jobpilot.apply.ApplicationService;
import com.jobpilot.job.MatchService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The "how is my search actually going" view: the application funnel, reply rate and response time, plus the skills
 * most asked for across the user's jobs that they don't yet list. Read-only; composes two services.
 */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    public record Insights(ApplicationService.Funnel funnel, int jobsConsidered, List<MatchService.SkillGap> skillGaps) {}

    private final ApplicationService applications;
    private final MatchService match;

    InsightsController(ApplicationService applications, MatchService match) {
        this.applications = applications;
        this.match = match;
    }

    @GetMapping
    public Insights get(@AuthenticationPrincipal UUID userId) {
        MatchService.GapReport g = match.skillGaps(userId, 8);
        return new Insights(applications.funnel(userId), g.jobsConsidered(), g.gaps());
    }
}
