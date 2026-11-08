# JobPilot AI — Architecture

> Your AI career agent: discover, match, apply, get hired.

## 1. System overview

```
 Browser (React/Vite/TS/Tailwind)
        │  HTTPS, JSON, Bearer JWT
        ▼
 nginx ──/api──► Spring Boot API (Java 21)            ◄── system of record
                   │  auth, profile, resumes, jobs,
                   │  matches, applications, audit
                   ▼
              PostgreSQL ◄────────────────┐
                   ▲                      │ reads profile/resumes,
                   │ job queue rows       │ writes jobs/matches/application events
                   │ (SELECT … FOR UPDATE │ via the API (service token)
                   │  SKIP LOCKED)        │
              Python agent worker (LangGraph) ── Playwright (headless Chromium)
                   │
                   └── LLM provider (JD parsing, scoring rationale, wording)
```

**Rules that shape everything:**

1. **The Spring Boot API owns the data.** The Python worker never writes to tables directly; it calls the API with a service token. One place enforces validation and ownership.
2. **No fabrication.** The agent may only state facts present in the user's profile or resume text. Every answer it gives to a screening question is stored with the profile field(s) it came from. A question it can't answer from verified data pauses the application for the user instead of guessing.
3. **The user stays in control.** Auto-submit is opt-in per user, has a daily cap, and every submission is logged with a screenshot. Portals with CAPTCHAs or terms that forbid automation are routed to "ready for you to submit" instead of being bypassed.

## 2. Phases

| Phase | Scope | Status |
|---|---|---|
| 1 | Auth (JWT), PostgreSQL + Flyway, profile management, resume upload + text extraction | **Done** |
| 2 | Job discovery: followed Greenhouse/Lever boards, Adzuna search (optional key), add by link (ATS API or schema.org JobPosting) or paste-in, dedupe, deterministic JD parsing | **Done** |
| 3 | Matching: 0–100 score per job (skills incl. resume skills, years/seniority, desired role, location with city aliases, stated salary), reasons, unknown parts left out | **Score done** (best-resume selection later) |
| 4 | Tailoring: per-job wording suggestions from existing resume facts only, diffed for the user to approve | |
| 5 | Application agent: form reading (Greenhouse API / Lever page), answers from profile → answer bank → local LLM, Playwright (Java, in the backend) fills a visible Chrome window, user submits, confirmation detected; auto mode (min score, daily limit, optional auto-submit, CAPTCHA handed to the user) | **Done** |
| 6 | Tracking: application status timeline, email/portal status sync, dashboard | |

Each phase ships with its migrations, endpoints, UI and tests before the next one starts.

## 3. Repository layout

```
jobpilot-ai/
├── backend/                     Spring Boot 4.1, Java 21, Maven wrapper
│   ├── src/main/java/com/jobpilot/
│   │   ├── auth/                User entity, JWT issue/verify, filter, /api/auth
│   │   ├── profile/             Profile entity (+ skills/experience/education collections), /api/profile
│   │   ├── resume/              Resume entity, file storage + text extraction, /api/resumes
│   │   ├── config/              Security (stateless JWT, CORS), typed app properties
│   │   └── shared/              ApiException + one error handler -> {code, message, fields}
│   ├── src/main/resources/
│   │   ├── application.yml      all settings from env vars
│   │   └── db/migration/        Flyway SQL (V1__init.sql …)
│   └── src/test/                integration tests (H2 in PostgreSQL mode)
├── frontend/                    React 19 + Vite 6 + TypeScript + Tailwind 4
│   ├── src/api.ts               typed API client (single fetch wrapper)
│   ├── src/session.ts           signed-in user context
│   ├── src/pages/               AuthPage, ProfilePage, ResumesPage
│   └── nginx.conf               production: static files + /api proxy
├── agent/                       (Phase 2+) Python, LangGraph, Playwright worker
├── docs/ARCHITECTURE.md
├── docker-compose.yml
└── README.md
```

## 4. Database schema (Phase 1)

Managed by Flyway (`backend/src/main/resources/db/migration`). Hibernate runs with `ddl-auto: validate`, so the app refuses to start if the schema and the entities disagree.

```
users ─────────┬──────────────── 1:1 ── profiles ──┬── 1:N profile_skills            (user_id, position, skill)
 id UUID PK    │                         user_id PK ├── 1:N profile_desired_roles     (user_id, position, role)
 email UNIQUE  │                         headline   ├── 1:N profile_desired_locations (user_id, position, location)
 password_hash │                         phone …    ├── 1:N profile_experiences       (company, title, start/end_date, description)
 full_name     │                         summary    └── 1:N profile_educations        (institution, degree, field, years, grade)
 created_at    │                         updated_at
               └──────────────── 1:N ── resumes
                                         id UUID PK, label, original_filename, content_type,
                                         size_bytes, sha256, storage_key UNIQUE,
                                         extracted_text, is_primary, created_at
```

- All child rows cascade on user deletion.
- `position` keeps user-chosen order for list fields.
- `storage_key` is a random UUID; files never use user-supplied names on disk.
- `extracted_text` is what Phase 3 matching and Phase 5 answering read. It is empty for scanned PDFs, and the UI warns about that.

**Phase 2 (V2__jobs.sql):**
- `jobs`: global, UNIQUE (source, external_id); `board` (e.g. `greenhouse:groww`) lets a full board sync close jobs that vanished (`closed_at`) and reopen ones that return. Parsed requirements are `min_years`, `seniority`, `education_level` plus `job_skills`, recomputed only when title/description change.
- `user_jobs`: a user's link to a job (origin COMPANY | SEARCH | IMPORT, status NEW | SAVED | DISMISSED), UNIQUE (user_id, job_id).
- `company_sources`: boards a user follows (ats GREENHOUSE | LEVER, token, last_synced_at, last_error).

Sources, and why: Greenhouse/Lever public board APIs and Adzuna's official API allow this use. Pages users paste are read via their schema.org `JobPosting` JSON-LD. Remotive is excluded because its terms forbid showing jobs behind a signup. LinkedIn and Naukri aren't scraped (against their terms), so users paste those in. Board jobs are linked only when the title matches a desired role: token subset after normalising developer/dev/programmer → engineer, front/back/full stack, SDE/SWE, ignoring seniority words. No desired roles means every job is linked. Adzuna runs desired roles × locations, capped at 3 × 3.

**Planned (Phase 3–6), for orientation only, not yet migrated:**
`job_matches` (user_id, job_id, score, breakdown JSONB, chosen_resume_id), `applications` (user_id, job_id, resume_id, status, submitted_at), `application_events` (application_id, type, payload JSONB, screenshot_key, at), `screening_answers` (application_id, question, answer, source_fields), `agent_tasks` (queue: type, payload, status, locked_at, attempts).

## 5. API contracts (Phase 1)

Base path `/api`. JSON unless noted. Authenticated endpoints need `Authorization: Bearer <token>`.

**Errors** always look like:
```json
{ "code": "VALIDATION_FAILED", "message": "Some fields are invalid", "fields": { "email": "must be a well-formed email address" } }
```
| code | HTTP |
|---|---|
| `VALIDATION_FAILED`, `BAD_REQUEST`, `EMPTY_FILE` | 400 |
| `UNAUTHENTICATED`, `BAD_CREDENTIALS` | 401 |
| `NOT_FOUND` (also returned for other users' resources) | 404 |
| `EMAIL_TAKEN`, `RESUME_LIMIT` | 409 |
| `FILE_TOO_LARGE` | 413 |
| `UNSUPPORTED_FILE` | 415 |
| `UNREADABLE_FILE` | 422 |

### Auth

| Method | Path | Auth | Body | Response |
|---|---|---|---|---|
| POST | `/auth/register` | — | `{email, password (8–72), fullName}` | 201 `AuthResponse` |
| POST | `/auth/login` | — | `{email, password}` | 200 `AuthResponse` |
| GET | `/auth/me` | ✓ | — | 200 `User` |

```ts
AuthResponse = { token: string, expiresInSeconds: number, user: User }
User = { id: uuid, email: string, fullName: string, createdAt: iso8601 }
```
Emails are stored lower-cased. Tokens are HS256, 24 h by default (`JWT_TTL_HOURS`). A token for a deleted user stops working at once.

### Profile

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/profile` | — | 200 `Profile` (empty lists/nulls before the first save) |
| PUT | `/profile` | `Profile` (full replacement) | 200 `Profile` |

```ts
Profile = {
  headline, phone, location, currentTitle: string|null,
  yearsExperience: number|null (0–60), expectedSalary: number|null, salaryCurrency: "INR"|… |null,
  noticePeriodDays: number|null (0–365), workAuthorization: string|null, willingToRelocate: boolean,
  linkedinUrl, githubUrl, portfolioUrl: "http(s)://…"|null, summary: string|null,
  skills: string[], desiredRoles: string[], desiredLocations: string[],   // trimmed, de-duplicated
  experiences: { company, title, location?, startDate: "YYYY-MM-DD", endDate?: date|null (null = current), description? }[],
  educations:  { institution, degree, field?, startYear?, endYear?, grade? }[],
  updatedAt: iso8601|null   // ignored on PUT
}
```
All five list fields are required on PUT (send `[]` for none). A job or degree that ends before it starts is rejected.

### Resumes

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/resumes` | — | 200 `Resume[]`, newest first |
| POST | `/resumes` | multipart: `file` (PDF/DOCX ≤ 5 MB), `label?` | 201 `Resume` |
| GET | `/resumes/{id}` | — | 200 `{id, extractedText}` |
| GET | `/resumes/{id}/file` | — | 200 original bytes, `Content-Disposition: attachment` |
| PATCH | `/resumes/{id}` | `{label?, primary?: true}` | 200 `Resume` |
| DELETE | `/resumes/{id}` | — | 204 |

```ts
Resume = { id, label, originalFilename, contentType, sizeBytes, primary: boolean, textExtracted: boolean, createdAt }
```
- The file type is decided from the file's bytes (`%PDF-` / DOCX zip with `word/document.xml`), not from its name or the client's header.
- Up to 10 resumes per user. The first upload becomes primary. Deleting the primary promotes the newest remaining one.
- Files live in `STORAGE_DIR` (a Docker volume). Bytes are deleted only after the DB delete commits.

### Jobs & discovery (Phase 2)

```ts
Job = { id, title, company, location, workplace: 'REMOTE'|'HYBRID'|'ONSITE'|null, employmentType, salaryMin, salaryMax, salaryCurrency,
        source: 'greenhouse'|'lever'|'adzuna'|'web'|'manual', url, applyUrl, postedAt, discoveredAt, closedAt,
        status: 'NEW'|'SAVED'|'DISMISSED', origin: 'COMPANY'|'SEARCH'|'IMPORT',
        skills: string[], minYears, seniority, educationLevel, description /* detail only */ }
SourceResult = { source: 'company'|'adzuna', label, fetched, linked, error: string|null }
Company = { id, ats: 'GREENHOUSE'|'LEVER', token, name, boardUrl, lastSyncedAt, lastError }
```

| Method | Path | Body / query | Response |
|---|---|---|---|
| GET | `/jobs` | `status=ACTIVE(default: new+saved)\|NEW\|SAVED\|DISMISSED\|ALL`, `q`, `includeClosed`, `page`, `size≤100` | 200 `{items: Job[], total, page, size}`, newest posted first |
| GET | `/jobs/{id}` | — | 200 `Job` with description |
| PATCH | `/jobs/{id}` | `{status}` | 200 `Job` |
| POST | `/jobs/import` | `{url}` | 201 `Job`; 422 `FETCH_FAILED` if the page has no readable job data or blocks us |
| POST | `/jobs/manual` | `{title, company, location?, url?, description}` | 201 `Job` |
| POST | `/discovery/run` | — | 200 `SourceResult[]`; 409 `ALREADY_RUNNING` |
| GET | `/companies` | — | 200 `Company[]` |
| GET | `/companies/suggestions` | — | 200 `{ats, token, name, following}[]` |
| POST | `/companies` | `{url}` or `{ats, token}` | 201 `{company, sync: SourceResult}`; 409 `ALREADY_FOLLOWING`/`COMPANY_LIMIT` (50); 422 `BOARD_NOT_FOUND` |
| DELETE | `/companies/{id}` | — | 204 (jobs already in the list stay) |

Fetch failures return `FETCH_FAILED`: 400 for bad or private links, 413 for oversize pages, 422 for unreadable pages, 502 for upstream errors.

## 6. Security notes

- BCrypt password hashes; stateless JWT; CSRF off because no cookies are used.
- The token lives in `localStorage`, which is simple but readable by any XSS. React escapes output, and there is no `dangerouslySetInnerHTML`. Move to an httpOnly cookie + CSRF token if third-party scripts are ever added.
- Ownership is checked in every resume query (`findByIdAndUserId`). Other users' resources return 404.
- User-supplied links go through `SafeHttp.getUserUrl`: http(s) only, ports 80/443, no credentials in the URL, every resolved IP must be public (rejects loopback, RFC1918, link-local/metadata, CGNAT, ULA, IPv4-mapped), and redirects are re-checked on every hop. Responses are capped at 3 MB. Trusted ATS/Adzuna calls are capped at 30 MB.
- Not done yet: login rate limiting / lockout, email verification, password reset. All three are needed before a public launch.
