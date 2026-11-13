-- Mock interview practice sessions. content is JSON (Interview.Content: questions, the user's answers, AI feedback).
CREATE TABLE interview_sessions (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id     UUID REFERENCES jobs(id) ON DELETE SET NULL,   -- null: practice for a role, not a saved job
    title      VARCHAR(300) NOT NULL,
    content    TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_interview_sessions_user ON interview_sessions (user_id, created_at);
