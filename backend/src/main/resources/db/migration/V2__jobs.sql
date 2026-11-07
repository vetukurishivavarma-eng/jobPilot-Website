-- Phase 2: job discovery.

-- Jobs are global and deduplicated by (source, external_id); users link to them via user_jobs.
CREATE TABLE jobs (
    id              UUID PRIMARY KEY,
    source          VARCHAR(20)  NOT NULL,      -- greenhouse | lever | adzuna | web | manual
    external_id     VARCHAR(300) NOT NULL,
    board           VARCHAR(120),               -- e.g. greenhouse:groww; used to close jobs that vanish from a board
    url             VARCHAR(1000),
    apply_url       VARCHAR(1000),
    title           VARCHAR(300) NOT NULL,
    company         VARCHAR(200) NOT NULL,
    location        VARCHAR(300),
    workplace       VARCHAR(10),                -- REMOTE | HYBRID | ONSITE | NULL (unknown)
    employment_type VARCHAR(40),
    salary_min      BIGINT,
    salary_max      BIGINT,
    salary_currency VARCHAR(3),
    description     TEXT,
    posted_at       TIMESTAMP WITH TIME ZONE,
    first_seen_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at       TIMESTAMP WITH TIME ZONE,
    -- parsed requirements (deterministic parser, see JdParser)
    min_years       INTEGER,
    seniority       VARCHAR(10),                -- INTERN | ENTRY | MID | SENIOR | LEAD | MANAGER
    education_level VARCHAR(10),                -- BACHELOR | MASTER | PHD
    CONSTRAINT uq_jobs_source_ext UNIQUE (source, external_id)
);
CREATE INDEX idx_jobs_board ON jobs(board);

CREATE TABLE job_skills (
    job_id   UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    skill    VARCHAR(80) NOT NULL,
    PRIMARY KEY (job_id, position)
);

CREATE TABLE user_jobs (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id        UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    origin        VARCHAR(10) NOT NULL,         -- COMPANY | SEARCH | IMPORT
    status        VARCHAR(10) NOT NULL,         -- NEW | SAVED | DISMISSED
    discovered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_user_jobs UNIQUE (user_id, job_id)
);
CREATE INDEX idx_user_jobs_user ON user_jobs(user_id, discovered_at);

-- Company career boards a user follows.
CREATE TABLE company_sources (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ats            VARCHAR(12)  NOT NULL,       -- GREENHOUSE | LEVER
    token          VARCHAR(100) NOT NULL,
    name           VARCHAR(200) NOT NULL,
    last_synced_at TIMESTAMP WITH TIME ZONE,
    last_error     VARCHAR(300),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_company_sources UNIQUE (user_id, ats, token)
);
