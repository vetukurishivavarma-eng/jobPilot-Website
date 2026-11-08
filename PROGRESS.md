# JobPilot AI — Progress

## RESUME HERE (2026-11-08, apply agent steps 1–2 done; product plan in docs/PRODUCT.md)

**Phases 1 and 2 are done. The apply agent (Phase 5) is in progress**, at the user's request, ahead of Phases 3 and 4.
19 backend tests pass and the frontend builds.

### User decisions for the agent (2026-11-08)
- **Submitting:** approval first, plus a per-user "auto-submit" toggle later.
- **AI:** local Ollama (`qwen2.5:3b-instruct`, free). A 7B model would write better answers; it's a one-line pull.
- **Sites:** Greenhouse and Lever only. No LinkedIn or Naukri (against their terms). The agent never touches CAPTCHAs: Lever forms have hCaptcha and Greenhouse has reCAPTCHA, so the plan is a **visible browser window on the user's PC**, where the user solves any CAPTCHA and presses Submit.

### Step 1, done (package `apply/`, V3 migration)
- `FormReader` reads the form's questions without a browser: Greenhouse from `boards-api …/jobs/{id}?questions=true`; Lever from the `/apply` page (standard fields as HTML, custom questions as JSON in each `cards[..][baseTemplate]` input). Real fixtures are in `test/resources/fixtures`.
- `Answerer` fills answers in order: profile facts, then the **answer bank** (whatever the user answered before, keyed by normalised question text with the company name replaced), then Ollama for **required open-ended textareas only**. If Ollama answers `NEED_INPUT`, the question goes to the user. Factual fields (CTC, yes/no) are never sent to the model.
- `POST /api/applications {jobId}`, `GET /api/applications[/{id}]`, `PUT /{id}/answers {answers:{key:value}}` (validates options and saves to the bank), `DELETE /{id}`. Statuses: NEEDS_INPUT, READY, FILLING, SUBMITTED, FAILED.
- UI: "Apply with agent" on Greenhouse/Lever job cards, an Applications list, and a review page ("agent needs N answers" first, a source tag on every field).
- Live check: real Meesho (Lever) and Groww (Greenhouse) forms were prepared in about 1s, profile answered 13 of 19 and 8 of 11 fields, and answering in the browser moved the application to READY. Ollama gave a good "why join" answer and an honest NEED_INPUT for an unsupported question (first call ~37s while the model loads, then ~8s).
- Bug the tests caught: "**Exp**ected Compensation (**Year**ly)" matched the years-of-experience rule. Fixed with word boundaries.

### Step 2, DONE: filling the real form in a visible Chrome window
- `BrowserFiller` uses Playwright Java 1.62 with `channel("chrome")`, i.e. the user's installed Chrome (no browser download; `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`). It runs a persistent profile at `<storage>/../browser-profile` on one dedicated thread, one window at a time.
- Fill flow: wait for network idle, fill each field by name/id (falling back to its label), then re-check every text field and retype any that a React re-render wiped (Greenhouse hydration race: First Name came out blank once). Radios and checkboxes match on value or label text. Native selects use selectOption. Greenhouse react-selects and location are typed then Enter. Lever location is typed key by key, then the first `.dropdown-results` suggestion is clicked.
- After filling: a full-page screenshot, then we watch the URL (`/confirmation`, `/thanks`) and the page text for 30 minutes. SUBMITTED when confirmed; window closed → READY with a message.
- `POST /api/applications/{id}/fill`, `GET /{id}/screenshot`, V4 migration (`screenshot_key`). UI: Fill button, a live banner while filling (polls every 3s), "Applied ✓", and a scrollable screenshot.
- `APPLY_DRY_RUN=true` aborts every non-GET request to greenhouse.io/lever.co (each one is logged). **Live dry runs passed:** Meesho (Lever) and Groww (Greenhouse, twice) had every field correct, including resume, dropdowns, location and phone country. Dry-run blocked only Lever's parseResume and a Cloudflare ping; Greenhouse sent nothing.
- To close the agent's window from a script: kill the chrome.exe processes whose command line contains `browser-profile`.

### NEXT
1. **First real application**: the user picks a job they actually want and applies with their real profile (APPLY_DRY_RUN off). Watch for confirmation detection on the real thank-you page.
2. Auto mode (roadmap step 7) after a few real ones; then match score (roadmap step 2), per docs/PRODUCT.md.

### Phase 2 facts worth remembering
- Live numbers: Databricks board has 922 jobs; 39 matched "Backend Engineer, Data Scientist". The first full sync takes ~23s, re-runs ~13s.
- The JD parser used to be 155 regexes (76 ms/job, so a 2.5-minute first sync). It's now indexOf plus boundary checks (2.7 ms/job, identical output, verified against the fixtures).
- Skill aliases that are ordinary English cause false positives ("excel" the verb, "product management" the team). Tighten them in skills.txt and add a case to `Phase2Test.jdParser`.
- Dev CORS: the backend allows only `http://localhost:5173`, so running Vite on any other port gives "Forbidden" in the browser (curl still works).
- Run on H2 without Postgres: set `JWT_SECRET`, `DATABASE_URL=jdbc:h2:mem:jp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH`, `DATABASE_USER=sa`, `DATABASE_PASSWORD=`, `PORT=8091`, `DISCOVERY_CRON=-`, then `./mvnw -o spring-boot:run -Dspring-boot.run.useTestClasspath=true`. Profiles passed via `-Dspring-boot.run.profiles` don't take effect, and test resources aren't on that classpath.
- Adzuna is not tested live (no key). The code path and parser are written; add `ADZUNA_APP_ID`/`ADZUNA_APP_KEY` to try it.
- Board jobs are filtered by role only, not location (a Bengaluru user sees Mountain View jobs). Location filtering belongs to Phase 3 matching.

### Source decisions (don't revisit without reason)
- **Remotive excluded:** its terms forbid showing jobs behind a signup.
- **Arbeitnow skipped:** mostly German jobs. Ashby isn't added yet.
- **LinkedIn/Naukri:** no scraping. Users paste the description in.

### Open items from Phase 1
- Not yet run on real local Postgres 17 (needs the password, see README §1).
- Docker files untested (no Docker on this machine).
