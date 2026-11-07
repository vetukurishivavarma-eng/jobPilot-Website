# JobPilot AI — Progress

## RESUME HERE (stopped 2026-11-08, mid Phase 2)

Phase 1 is done (see README). Phase 2 (job discovery) is **partly written and compiles, but is not wired up or tested yet.**

### Phase 2: done so far (in `backend/src/main/java/com/jobpilot/job/`)
- `V2__jobs.sql`: tables `jobs` (global, unique source+external_id, `board` for closing vanished jobs, parsed min_years/seniority/education), `job_skills`, `user_jobs` (origin COMPANY|SEARCH|IMPORT, status NEW|SAVED|DISMISSED), `company_sources` (user follows a GREENHOUSE/LEVER board).
- `SafeHttp`: GET with a 3 MB cap and timeouts; `getUserUrl` blocks SSRF (http(s) only, ports 80/443, public IPs only, redirects re-checked).
- `JobSources`: static fetch + parse for Greenhouse board/job, Lever board/job, Adzuna search (key needed, predicted salaries dropped), and JSON-LD `JobPosting` from any page; `parseBoardUrl`, `htmlToText`, `fromUrl`.
- `JdParser` + `resources/skills.txt`: deterministic skills / min years / seniority / education.
- Real captured fixtures in `src/test/resources/fixtures/` (greenhouse-razorpay.json, lever-cred.json, lever-job-page.html).
- jsoup 1.18.3 added to the pom.

### Phase 2: still to do
1. Entities + repos: `Job` (skills `@ElementCollection` + `@BatchSize`), `UserJob` (`@ManyToOne` job), `CompanySource`.
2. `DiscoveryService`:
   - `run(userId)`: fetch the user's boards in parallel (virtual threads); upsert by (source, externalId); close jobs missing from a board (careful when the id list is empty); reopen ones seen again.
   - Link board jobs only when the title matches a desired role. Normalise developer/dev/programmer to engineer, back end to backend, front end to frontend, full stack to fullstack; drop seniority words from the desired role; match when the role's tokens are a subset of the title's tokens. No desired roles means link all.
   - Adzuna search per role x location when the key is set (Remote means no `where`, plus "remote" in `what`).
   - Per-user running guard (409 ALREADY_RUNNING). Returns per-source {fetched, linked, error}.
   - `@Scheduled(cron = ${jobpilot.discovery-cron:0 0 */6 * * *})`, set to `-` in tests; `@EnableScheduling`.
3. Config: `ADZUNA_APP_ID`, `ADZUNA_APP_KEY`, `ADZUNA_COUNTRY` (default `in`), `DISCOVERY_CRON`. Add them to AppProperties, application.yml and the README.
4. Endpoints:
   - `GET /api/jobs?status=ACTIVE|NEW|SAVED|DISMISSED|ALL&q=&includeClosed=&page=&size=` returns `{items,total,page,size}`, ordered by coalesce(posted_at, discovered_at) desc.
   - `GET /api/jobs/{id}` (detail with description). `PATCH /api/jobs/{id}` `{status}`.
   - `POST /api/jobs/import {url}` and `POST /api/jobs/manual {title, company, location?, url?, description}`.
   - `POST /api/discovery/run`.
   - `GET/POST/DELETE /api/companies`. POST takes a board URL or ats+token, validates it (Greenhouse: board name endpoint; Lever: `leverCheck`), then syncs right away.
   - `GET /api/companies/suggestions`.
5. Verified suggestion boards (checked live 2026-11-08):
   - Greenhouse: razorpaysoftwareprivatelimited (Razorpay), groww, druva, inmobi, rubrik, databricks, stripe
   - Lever: cred, meesho, zeta, paytm, fampay
6. Tests:
   - Parser tests on the fixtures: Greenhouse gives "Engineering Manager" / "Lead Security Engineer" in Bengaluru, Lever gives 3 CRED jobs, the JSON-LD page gives "accounts payable manager" at CRED.
   - JdParser unit cases.
   - `SafeHttp.isPublic` should reject 127.0.0.1, 10.x, 169.254.x, ::1 and ::ffff:127.0.0.1.
   - Endpoint test using the manual-add path (no network).
7. Frontend:
   - Jobs page: list, filters, search, save/dismiss, a "Find jobs now" button with per-source results, an expandable description and parsed requirements, source label with a link to the original ("Jobs by Adzuna" attribution when the source is Adzuna), and add-by-URL or manual entry.
   - Companies page: follow by URL, suggestion chips, sync status and errors, unfollow.
8. Live smoke test against the real APIs, update ARCHITECTURE.md (Phase 2 = Done plus the API contracts), commit.

### Source decisions (don't revisit without reason)
- **Remotive excluded:** its terms forbid showing jobs behind a signup, which JobPilot requires.
- **Arbeitnow skipped:** mostly German jobs. Ashby not added yet (couldn't confirm a board).
- **LinkedIn/Naukri:** no scraping (against their terms). Users paste the description through manual add.

### Open items from Phase 1
- Not yet run on real local Postgres 17 (needs the password; see README §1).
- Docker files untested (Docker isn't installed on this machine).
