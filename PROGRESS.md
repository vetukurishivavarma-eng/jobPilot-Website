# JobPilot AI — Progress

## RESUME HERE (latest, HEAD 3ede8d3, tree clean, 48 tests pass)
- Start: `powershell -ExecutionPolicy Bypass -File start.ps1`. Extension 0.2.0 loaded in Chrome (reload after changes).
- WAITING ON USER: checking the resume-based job list (4 jobs for the Java/Hyderabad account).
- NEXT (offered, not confirmed): Workday careers-site reader (biggest supply gain), then SmartRecruiters/Ashby/Workable; async "Find jobs now" (>45s now); server-side profile lock; ask user about Hevo SDE II stale submittedAt; HackerRank SDET II needs 5 answers from user; delete unused Playwright /fill + BrowserFiller if extension holds up.

## 2026-11-09: SmartRecruiters + Ashby readers (step 2 of 5)
- `smartRecruitersBoard` (Posting API with country=in, then one GET per posting for the description) and `ashbyBoard` (one call, descriptions included). Shared `JobSources.eachJob` = 4-at-a-time detail reads (Workday uses it too). Listing only; apply on the posting.
- catalog: SmartRecruiters ServiceNow (83 India, 44 Hyderabad), Experian, Freshworks, Bosch, Swiggy, NielsenIQ, AbbVie; Ashby Sarvam AI, SpotDraft, ElevenLabs, OpenAI, Atlan, Notion. Catalog now 69 sources.
- Workable skipped: no Indian company found on it (all probed accounts empty). Add when one turns up.
- Live: ServiceNow 83 jobs in 16s, Sarvam 47 in 0.4s.

## 2026-11-09: "Find jobs now" runs in the background
- POST /api/discovery/run starts it (returns progress; a second press just reports the running one); GET /api/discovery/status = {running, done, total, results}. Progress is in memory per user (lost on restart). Jobs page polls every 2s, button shows "Reading X/56 companies…", list refreshes as each company finishes, resumes polling if you leave and come back, report card at the end. Live-checked: ~3 min for the full catalog (Citi is the slowest).
- Order agreed with user, one by one: 1) background search DONE, 2) SmartRecruiters/Ashby/Workable readers, 3) missing Workday sites (Dell, Qualcomm, AMD, Walmart, Synopsys, BNY need real URLs), 4) Workday form filling in the extension, 5) housekeeping (server-side lock, Hevo SDE II, delete Playwright filler).

## 2026-11-08 night: Workday careers-site reader
- `JobSources.workdayBoard("tenant.wdN/site")`: POSTs the site's own `/wday/cxs/{tenant}/{site}/jobs` search, finds the India facet (`indiaFacet`: exact "India" country value, else all "..., India" location values), pages 20 at a time (cap 400), then GETs each job for the description (4 at a time). Listing only: Workday needs an account to apply, so the agent/extension doesn't apply there (Open the posting).
- catalog.txt: 18 Workday sites with India openings verified (NVIDIA, Salesforce, Wells Fargo, Mastercard, Adobe, Intel, Micron, HP, Citi, Morgan Stanley, State Street, Workday, PayPal, Cadence, Fidelity, Thomson Reuters, Novartis, Pfizer). 422 = wrong site name (Dell, Qualcomm, AMD, Walmart, Synopsys, BNY need the real site path).
- Live: Adobe 37 India jobs in 10s. "Find jobs now" now takes ~2 min (Citi alone ~400 job reads) -> async + poll is the next fix.

## 2026-11-09 later: jobs chosen from the resume (`MatchScorer.fitsResume`), for every profile
- Field: any programming language in resume/profile skills = developer -> title must say software/developer/SDE/SWE/backend/full stack/programmer/MTS, or name a technology they have ("Java Engineer"); not sales/support/solutions/manager etc. Non-developers: desired roles (RoleMatch) decide.
- Stack: every technology a title names (STACKS table: Java, Python, Go, .NET, frontend, iOS, Android, data science, data eng, DevOps/SRE, QA/test, Salesforce, SAP...) -> candidate needs one of them.
- Level: years = JD minYears, else seniority default, raised to hard title levels (staff 8, principal 10, SDE-III 5, SDE-IV 7); hidden if > candidate years + 1.5. Intern/new-grad hidden at 2+ years.
- Requirements: >= 30% (min 1) of the posting's listed skills on the resume.
- Jobs the user added ("Add a job", origin IMPORT) always show. Live user (Java/Spring, ~3y10m, Hyderabad): 4 jobs (Zscaler Java SDE, Coinbase x2, Twilio L3).
- Earlier I told the user ~3 years; it's 3y10m (01/2023-now). Profile says 4.

## 2026-11-09: no more following -> shared company catalog; list = location + role + skills
- `backend/src/main/resources/catalog.txt`: 38 Greenhouse/Lever boards verified (probe 2026-11-08) to have India openings. Discovery ("Find jobs now" + 6h cron) fetches every board once and links its India/remote-India jobs (`MatchScorer.inMarket`) to the user(s). Test classpath has an empty catalog.txt (no network in tests). CompanyController/CompanySource + Companies page REMOVED (company_sources table left in DB, unused).
- Jobs list + auto mode show only `MatchService.relevant`: preferred location AND title matches a desired role (RoleMatch) AND (posting lists no skills OR shares one with the candidate). Unrelated-role score also capped at 40.
- User changed preferred location to Hyderabad (by request, via API): 15 jobs (Zscaler, HighRadius, Coinbase, Twilio, Druva, New Relic...).
- User asked why not company careers sites directly: answered = per-platform readers. NEXT candidates: Workday (big Mumbai/Hyd IT + banks), then SmartRecruiters/Ashby/Workable public APIs, then Darwinbox/Keka. Listing only; auto-apply stays Greenhouse/Lever.
- "Find jobs now" over the whole catalog takes >45s (synchronous request); consider async + poll like auto mode.

## 2026-11-08 late night: preferred-location filter + profile lock
- Jobs list (and auto mode's picks) show only jobs in the profile's desired locations, or remote open to India (not "Remote - US"); unknown location hidden; relocation does NOT widen it. `MatchScorer.inDesiredLocation` (+ test). List now always filters/pages in memory.
- Jobs/Applications/Companies (incl. tailor + application pages) locked until `profileGaps` (frontend api.ts) is empty: resume, headline, title, phone, location, years, summary, skills, desired roles, preferred locations, experience (if 1+ yrs), education, salary+currency, notice, work auth, LinkedIn. Banner with % + missing list; ProfilePage save / ResumesPage refresh fire `jobpilot:profile` to re-check. UI-only gate (API not enforced).
- User's preferred locations = ["Thane"] -> only 3 jobs (remote India) remain; most found jobs are Bengaluru. Asked the user whether to add cities.

## 2026-11-08 night: LIVE-TESTED extension (4e21a95): Lever AND Greenhouse end to end
- Okta (Greenhouse embed) submitted by the user, SUBMITTED auto-detected from /embed/job_app/confirmation. Greenhouse new boards re-render the whole document after load (removed our button): button re-attached every 1s. Left: HackerRank needs 5 answers; Hevo SDE II stale submittedAt (ask user).

## (earlier) LIVE-TESTED extension queue (9396b58)
- Lever: queue opened Hevo SDE I, filled, user submitted, thank-you page marked it SUBMITTED. Lever location fixed (widget ignores synthetic typing -> call /searchLocations, set input + hidden selectedLocation {name,id}).
- Greenhouse (Okta): board URL redirects to okta.com's own Drupal form (extension can't run there) -> applyUrl is now the embed form `job-boards.greenhouse.io/embed/job_app?for=X&token=ID`. React-selects open on MOUSEUP (wrapper div onMouseUp), and the phone widget has ~244 hidden role=option rows -> pick only from the input's aria-controls listbox. Verified on the live Okta form: text fields, resume, country, city, yes/no questions all fill. Demographic selects not re-checked after the fix.
- Review page button is now "Open and fill" (link to applyUrl; extension fills). The Playwright /fill endpoint + BrowserFiller are unused by the UI now (delete later if the extension holds up).
- Paytm was applied outside JobPilot -> marked submitted by hand. Hevo SDE II has a stale submittedAt; queue skips anything with submittedAt.
- Extension skip list (chrome.storage.local 'skip') can hold Okta if its tab was closed: use "Open and fill" on the review page.

## 2026-11-08 late: extension now runs auto mode hands-free
- User asked to drop the manual "press Fill" step. Server auto mode now ONLY prepares; the Playwright submit loop is gone from it (Lever rejects that window). Manual "Fill application form" button still uses Playwright.
- Extension 0.2.0 (reload it in chrome://extensions): `background.js` queue, chrome.alarms every 2 min (+ on sign-in): if auto mode is on and under the daily limit, opens the next READY `auto` application with 0 missing (DTO now has `applyUrl`) in a background tab. `fill.js` fills on load on ANY prepared form (button = refill). Queue tab + "Submit for me" -> clicks Submit, waits 30s for the thank-you page -> marks applied, closes tab, next. CAPTCHA challenge / unfilled field / no Submit button / review mode (Submit for me off) -> notification + tab brought to front + app added to the extension's skip list (not reopened). Closing the tab = skip.
- FIRST LIVE TEST: start.ps1, reload extension, open localhost:5173 signed in, Auto mode on (+Submit for me), Run now; watch the Hevo/Paytm tabs.

## RESUME HERE (2026-11-08 end of day, shut down by user)

**Start:** `powershell -ExecutionPolicy Bypass -File start.ps1` (backend 8080 + frontend 5173; `-BackendOnly` restarts just the backend and now stops the old one first). Model qwen2.5:3b (7B too slow on this 7.8 GB CPU-only PC). 45 tests pass.

**Next, in order:**
1. User installs `extension/` (chrome://extensions -> Developer mode -> Load unpacked), opens localhost:5173 signed in, then on https://jobs.lever.co/hevodata/6cbbe304-e065-4711-bf3e-756795d2bc2a/apply presses "Fill with JobPilot". FIRST LIVE TEST of fill.js: check every field (Lever location, cards[] textareas, resume upload), then submit and confirm "Applied" on /thanks.
2. Then Greenhouse via the extension (HackerRank SDET II, Okta embed) once their missing answers are given.
3. 6 applications prepared by auto mode (14:23): Paytm + Hevo Business System Engineer READY (latter probably unwanted: matched "System Engineer" role); Hevo SDE I READY after user answers; Hevo SDE II, HackerRank SDET II, Okta Java NEEDS_INPUT.
4. Consider: "Fill application form" (Playwright window) is rejected by Lever's verification; maybe make the UI point to the extension for Lever. Auto "Submit for me" can't work on CAPTCHA sites.
5. Other laptop: user wants Claude to control it; waiting on them to run the OpenSSH setup block (key ~/.ssh/lan_laptop) and send username + IP.

### 2026-11-08: browser extension (extension/), because Lever rejects submits from the agent's Chrome
- Live finding: Lever answered "There was an error verifying your application" after the user solved hCaptcha in the Playwright window (automated Chrome). Decision: don't disguise the automation; fill in the user's own Chrome instead.
- `extension/` (MV3, load unpacked): `token.js` copies `jobpilot.token` from localhost:5173 into extension storage; `background.js` calls the API (content scripts can't: page origin + local-network rules); `fill.js` on Lever/Greenhouse (all frames, so Okta's embedded Greenhouse iframe too) shows "Fill with JobPilot", fills like BrowserFiller (native value setter for React, radios/checkboxes, selects, react-select options, Lever location suggestions, resume via DataTransfer), outlines what it couldn't fill, and marks the application SUBMITTED on the thank-you page.
- Backend: `GET /api/applications/for-page?url=` (Lever/Greenhouse/embed URL -> job -> application), `GET /{id}/resume` (tailored PDF if any), `POST /{id}/submitted`; CORS allows `chrome-extension://*`. Endpoints checked against the real Hevo app; **the in-page filling itself is not yet tested on a live form**.

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
