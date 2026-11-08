# JobPilot AI — Progress

## RESUME HERE (2026-11-08, Phase 2 done)

**Phases 1 and 2 are done.** 13 backend tests pass and the frontend builds. Phase 2 was smoke-tested live against the real Greenhouse and Lever APIs (on H2) and walked through in Chrome: login, the jobs inbox, expanding a job, Find jobs now, Save, following a company from a chip, and add-by-link falling back to the paste-in form.

**User's next ask: an AI agent that applies on their behalf.** That's Phase 5. It jumps ahead of Phase 3 (matching) and Phase 4 (tailoring), and we're agreeing the design before building.

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
