# JobPilot AI

**Your AI career agent: discover, match, apply, get hired.**

Enter your professional details and upload your resume once. The agent finds jobs, scores them against your profile, picks the best resume, fills applications using only verified data, and tracks every application.

| | |
|---|---|
| Frontend | React 19, Vite 6, TypeScript, Tailwind 4 |
| Backend | Java 21, Spring Boot 4.1, Spring Security (JWT), Flyway |
| Database | PostgreSQL 17 |
| Agent (Phase 5) | Python, LangGraph, Playwright |
| Deploy | Docker / docker compose |

Architecture, DB schema, API contracts and the phase plan: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

**Status: Phase 1 done.** Auth, PostgreSQL, profile management, and resume upload with text extraction all work.

---

## Local development

### Prerequisites
- JDK 21 (`java -version`)
- Node 20+ (`node -v`)
- PostgreSQL 15+ running locally

Maven is not needed; the backend ships with the Maven wrapper (`mvnw`).

### 1. Database (one time)

```sql
-- psql -U postgres
CREATE USER jobpilot WITH PASSWORD 'jobpilot';
CREATE DATABASE jobpilot OWNER jobpilot;
```
Flyway creates the tables on first start.

### 2. Backend → http://localhost:8080

```bash
cd backend
export JWT_SECRET="$(openssl rand -base64 48)"   # PowerShell: $env:JWT_SECRET = "<32+ random chars>"
./mvnw spring-boot:run                          # Windows: mvnw.cmd spring-boot:run
```

| Env var | Default | Notes |
|---|---|---|
| `JWT_SECRET` | — (required) | ≥ 32 chars; the app refuses to start without it |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/jobpilot` | JDBC URL |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `jobpilot` / `jobpilot` | |
| `JWT_TTL_HOURS` | `24` | |
| `STORAGE_DIR` | `./data/resumes` | where resume files are written |
| `CORS_ORIGINS` | `http://localhost:5173` | comma separated; only needed if the frontend is on another origin |
| `PORT` | `8080` | |

Health check: `GET /actuator/health`.

### 3. Frontend → http://localhost:5173

```bash
cd frontend
npm install
npm run dev
```
Vite proxies `/api` to `http://localhost:8080`; override with `API_URL=...`.

### Tests

```bash
cd backend && ./mvnw test        # integration tests: auth, profile, resumes (H2, no DB server needed)
cd frontend && npm run build     # type-check + production build
```

Want to click around without Postgres? Run `./mvnw spring-boot:test-run -Dspring-boot.run.profiles=test` for the real backend on in-memory H2. Data is lost on restart.

---

## Deployment (Docker)

```bash
cp .env.example .env      # set DATABASE_PASSWORD and JWT_SECRET
docker compose up --build -d
# open http://localhost:3000
```

- `db`: Postgres 17, data in the `pgdata` volume
- `backend`: Spring Boot, resumes in the `resumes` volume (back it up together with the DB)
- `frontend`: nginx serving the built SPA, proxying `/api/` to the backend (6 MB upload limit)

On a single VM (any cloud), put Caddy or nginx with TLS in front of port 3000. On a managed platform (Render, Railway, Fly), deploy `backend/` and `frontend/` as two services from their Dockerfiles, use managed Postgres for `DATABASE_URL`, and attach a persistent disk at `/data/resumes`. Without a disk, uploaded resumes are lost on redeploy.

## Known gaps (to add before a public launch)
- No login rate limiting, email verification or password reset yet.
- Resume files are stored on local disk: fine for one instance, move to object storage (S3/GCS) to scale out.
- Scanned (image-only) PDFs give no text. The UI flags them; OCR is a later addition.
