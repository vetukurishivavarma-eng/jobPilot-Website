-- Phase 5 (step 1): applications the agent prepares, and answers the user taught it.

CREATE TABLE applications (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id       UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    status       VARCHAR(12) NOT NULL,      -- NEEDS_INPUT | READY | FILLING | SUBMITTED | FAILED
    resume_id    UUID REFERENCES resumes(id) ON DELETE SET NULL,
    fields       TEXT NOT NULL,             -- JSON list of form fields with the prepared answers (see FormField)
    error        VARCHAR(500),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_applications UNIQUE (user_id, job_id)
);
CREATE INDEX idx_applications_user ON applications(user_id, updated_at);

-- Answers a user gave once, reused on later forms. question_key is the normalised question text.
CREATE TABLE answer_bank (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    question_key VARCHAR(300) NOT NULL,
    question     VARCHAR(1000) NOT NULL,
    answer       TEXT NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_answer_bank UNIQUE (user_id, question_key)
);
