package com.jobpilot.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The web app is built into static/ (`npm run build:backend` in frontend/) and served from this same origin.
 * Its client-side routes load index.html; keep this list in sync with the routes in frontend/src/main.tsx.
 */
@Controller
class SpaController {
    @GetMapping({"/login", "/register", "/jobs", "/jobs/{id}/tailor", "/applications", "/applications/{id}",
            "/interview", "/interview/{id}", "/live/{id}", "/insights", "/profile", "/resumes"})
    String app() { return "forward:/index.html"; }
}
