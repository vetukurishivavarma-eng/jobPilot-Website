-- Interview recordings: kept for one hour after upload, then deleted by a scheduled job (InterviewRecordings.purge).
CREATE TABLE interview_recordings (
    session_id   UUID PRIMARY KEY,
    user_id      UUID NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    content      BYTEA NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX interview_recordings_created ON interview_recordings (created_at);

-- Paid live interviews. One row per Razorpay order; credits = interviews - used on PAID rows not past expires_at.
CREATE TABLE purchases (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL,
    plan         VARCHAR(20) NOT NULL,
    amount_paise INTEGER NOT NULL,
    interviews   INTEGER NOT NULL,
    used         INTEGER NOT NULL DEFAULT 0,
    status       VARCHAR(20) NOT NULL,
    order_id     VARCHAR(64),
    payment_id   VARCHAR(64),
    expires_at   TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    paid_at      TIMESTAMP WITH TIME ZONE
);
CREATE INDEX purchases_user ON purchases (user_id);
CREATE UNIQUE INDEX purchases_order ON purchases (order_id);
