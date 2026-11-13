package com.jobpilot.outreach;

import com.jobpilot.apply.Ollama;
import com.jobpilot.tailor.TailorService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Drafts a recruiter outreach message for one of the user's matched jobs. Draft only — the user sends it. */
@RestController
public class OutreachController {

    private final TailorService tailor;
    private final Ollama ollama;

    OutreachController(TailorService tailor, Ollama ollama) {
        this.tailor = tailor;
        this.ollama = ollama;
    }

    @PostMapping("/api/jobs/{jobId}/outreach")
    public OutreachMessage.Draft outreach(@AuthenticationPrincipal UUID userId, @PathVariable UUID jobId) {
        return OutreachMessage.draft(tailor.facts(userId), tailor.jobInfo(userId, jobId), ollama::generateJson);
    }
}
