# JobPilot AI — Progress

## RESUME HERE (2026-11-08 evening, paused by user mid-task)

**State:** live on local Postgres. Start with `powershell -ExecutionPolicy Bypass -File start.ps1` (`-BackendOnly` restarts just the backend). Secrets are in git-ignored `.env.local`, logs in `C:/Users/Shiva/jobpilot-data/logs/backend.log`, Ollama model qwen2.5:7b-instruct (installed; first call ~50s, then ~10s). 38 tests pass.

**The user started real use** (their own account). First real application: Paytm (Lever) "Senior Software Engineer – Backend (Travel)" — prepared and filled, NOT submitted. That session exposed Lever demographic-survey bugs, fixed in `160cfe0` (survey keys under `[responses]`, no duplicate questions, radio options, CSS-targeted country picker pre-answered "India", FILLING reset on restart).
**Backend restarted 2026-11-08 with `160cfe0` + profile import.** The user deletes the Paytm application (Applications → Delete draft) and presses "Apply with agent" again so it's re-read with the fixed parser.

### 2026-11-08 afternoon: real-resume import + match fixes (live-tested on the user's resume)
- Model is now **qwen2.5:3b** (`.env.local`): the PC has 7.8 GB RAM, CPU only; 7B ran at 0.5-2.5 tok/s and timed out. Ollama timeout 300s.
- Import = model gives structure only; bullets cut from the text per job; one short summary per job (separate call, ≤40 words, skipped for 1 bullet); profile summary from the resume's section or AI-written (≤75 words, fact-checked); dates must be a range written in the resume (the model took the DOB as a start date); resume wins on re-import except salary/notice/auth/relocation/desired roles+locations. ~2 min on the user's resume. `LiveImportRun` (-Dlive.resume=path) runs it against the real model.
- Desired roles are suggested as searchable titles (company titles like "System Engineer (Digital Cadre)" matched no posting, so company sync linked nothing).
- MatchScorer caps: abroad (incl. "Remote - US") ≤40, 3+ year shortfall ≤50, intern/new-grad for 2+ years ≤50; Thane = Mumbai. Auto mode had 0 picks because the best score was 67 (vs min 75).
- Followed 11 more companies for the user (Stripe, Okta, Twilio, HackerRank, slice, Druva, Rubrik, InMobi, Hevo, Meesho, FamPay): 397 jobs, 6 at ≥75, all in India. PhonePe/Swiggy/Zomato/CRED etc. aren't on Greenhouse/Lever.
- start.ps1 -BackendOnly now stops the running backend first.

### DONE (not live-tested yet): profile from resume (user asked: "ask their resume and auto fill, don't take manual entry for profile")
- DONE (backend, `bf3400a`): `profile/ResumeImport` (pure). Regex for email/phone/LinkedIn/GitHub/portfolio; JdParser for skills; Ollama `generateJson` (format=json) for title/location/headline/summary/experiences/educations. Every LLM value must be traceable to the resume text (`inText`, `grounded` = numbers ⊆ resume plus FactCheck.sameClaim plus 60% word share); invented jobs, bullets and education are dropped with notes. Years computed from merged job intervals. Merge keeps fields the user already filled; desired role/location are suggested from current title/city. `POST /api/profile/import` returns `{profile (draft, NOT saved), notes}`. Tests: `ResumeImportTest`.
- DONE (frontend): Profile page "Fill from my resume" button + "check and press Save" banner with notes; Resumes upload → `/profile?import=1` (auto-import, ref-guarded against StrictMode double call) when the profile has no experiences; register → `/resumes?welcome=1` prompt. `inText` now collapses whitespace (PDF line wraps mid-name); `grounded` was already word-based.
- NEXT: live-test with the user's real resume through the 7B model (Profile → Fill from my resume) and check what the notes drop.
- Then: the user finishes the Paytm application and confirms "Applied ✓" detection on a real thank-you page.

## Earlier (2026-11-08): apply agent, match score, auto mode, tailored resume and cover letter done; plan in docs/PRODUCT.md

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

### Match score + auto mode, DONE
- `job/MatchScorer` (pure, `MatchScorerTest`): skills 40 (profile skills plus skills JdParser finds in the primary resume; ~70% of listed skills counts as full), experience 25 (minYears, or a seniority default), role 15 (RoleMatch on desired roles), location 15 (city aliases, remote, relocation scores half), salary 5 (stated, same currency only). Unknown parts are left out of the denominator. Reasons come back in `good`/`gaps`.
- `GET /api/jobs?sort=match` scores the whole list in memory (ponytail note). Every JobDto carries `match`.
- Auto mode: `AutoApplyService` + `auto_apply_settings` (V5; also `applications.auto`). Each run picks open Greenhouse/Lever jobs with no application and score ≥ min, best first, up to the remaining daily limit; prepares them (the answer bank fills repeat questions); if `autoSubmit`, fills and presses Submit one window at a time (`BrowserFiller.submit`: click, wait 45s for confirmation; on a visible hCaptcha/reCAPTCHA/Cloudflare challenge or no confirmation, hand over with a note and wait for the user). Handed-over apps get `auto=false` so runs don't reopen them. Cron `AUTO_APPLY_CRON` every 15 min; scheduler pool size 2.
- `GET/PUT /api/auto-apply`, `POST /api/auto-apply/run` (async; poll GET). UI: Auto mode panel (on, min-score slider, daily limit, "Submit for me", Run now, last-run summary), "auto" tag, match badge + reasons, Best match/Newest sort.
- **Dry run now blocks ALL non-GET requests** (since auto mode presses Submit). Live run on Groww: prepared the best match, waited for 3 answers, then on the next run filled, pressed Submit, didn't get confirmation (the resume upload to S3 was blocked, so the form refused) and handed over correctly. The second job reused the answers and came out READY immediately.
- Not visually checked: the Chrome window was minimized (viewport 0x0) at the end. Builds and typechecks.

### Tailored resume + cover letter, DONE (package `tailor/`, V6)
- `Tailor` (pure): skills (job's skills first, plus skills from the user's own resume unless already covered, e.g. Spring inside Spring Boot; never anything else), bullets (stable reorder by job keywords, then an AI rewrite), summary (AI, `summaryOnly` strips echoed header lines), cover letter (AI with one retry naming the problems, otherwise a factual template).
- **Fact check** (`Tailor.FactCheck`): numbers and number words must appear in the user's facts; skills must be the user's. For bullets it's stricter: only skills named in *that role's* text, and `sameClaim` (≥50% of the original's 5-letter stems survive). Failing AI output is discarded (resume) or flagged (letter), with a note.
- **Live with qwen2.5:3b:** round 1 invented Zeta Labs bullets (Java/Kafka "payments processing"); the global-skill check missed it. The per-role check plus sameClaim now catch it (regression test). It had also flipped "by 40%" to "to 40%"; the prompt now asks it to copy number phrases verbatim. The cover letter's AWS/Docker are legitimately in the demo resume.
- `Render`: PDF (PDFBox Helvetica; unsupported characters mapped or replaced, ₹→Rs), DOCX (hand-written WordprocessingML), TXT. PDF checked visually: clean, single column, ATS-readable.
- API: `GET /api/jobs/{id}/documents`, `POST …/documents/resume|letter` (regenerate), `PUT /api/documents/{id}/resume` (per-section useTailored and optional edited text), `PUT …/letter`, `GET /api/documents/{id}/file?format=pdf|docx|txt`.
- Apply agent: a job's tailored resume (as PDF, "<Name> - Resume.pdf") is attached instead of the default, and cover-letter textareas get the job's letter (source LETTER).
- UI: /jobs/:id/tailor (a "Tailor resume & cover letter" link in the expanded job card). Resume tab shows original vs tailored per section, click to choose, tailored text editable, fact-check notes, downloads. Letter tab: source, warnings, editable, rewrite, downloads. **Not visually checked in the browser** (Chrome tab was closed). Typecheck/build pass.
- Tests set `ollama-url: http://127.0.0.1:9` so they never call the real model.

### NEXT
1. **First real application** with the user's real profile (APPLY_DRY_RUN off).
2. Roadmap step 4: profile import from resume / LinkedIn export (so tailoring has structured experience without manual typing).

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
