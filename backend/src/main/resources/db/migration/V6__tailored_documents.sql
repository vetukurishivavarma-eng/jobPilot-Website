-- Per-job tailored resume and cover letter. content is JSON (Tailor.TailoredResume or Tailor.CoverLetter).
CREATE TABLE tailored_documents (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id     UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    kind       VARCHAR(12) NOT NULL,   -- RESUME | LETTER
    content    TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_tailored UNIQUE (user_id, job_id, kind)
);
