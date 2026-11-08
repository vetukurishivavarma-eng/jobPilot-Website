-- Auto mode: per-user settings, and which applications auto mode prepared.

CREATE TABLE auto_apply_settings (
    user_id      UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    enabled      BOOLEAN NOT NULL,
    auto_submit  BOOLEAN NOT NULL,     -- also press Submit when every answer is in (else: prepare only, user reviews)
    min_score    INTEGER NOT NULL,     -- only jobs with a match score at or above this
    daily_limit  INTEGER NOT NULL,     -- submissions per day, manual ones included
    last_run_at  TIMESTAMP WITH TIME ZONE,
    last_summary TEXT                  -- JSON of the last run's report
);

ALTER TABLE applications ADD COLUMN auto BOOLEAN NOT NULL DEFAULT FALSE;
