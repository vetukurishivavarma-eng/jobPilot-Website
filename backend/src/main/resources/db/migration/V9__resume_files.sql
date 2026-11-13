-- Resume bytes live in the DB, not on disk: Render's filesystem is ephemeral and wiped on every
-- redeploy, which was silently losing uploaded resumes. storage_key matches resumes.storage_key.
CREATE TABLE resume_files (
    storage_key VARCHAR(64) PRIMARY KEY,
    content     BYTEA NOT NULL
);
