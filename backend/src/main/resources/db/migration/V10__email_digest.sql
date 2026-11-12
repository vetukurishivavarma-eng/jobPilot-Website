-- Daily email digest (new matched jobs + follow-ups due). On by default; one-click unsubscribe sets email_digest = false.
-- digest_sent_at = last digest that went out; the next one lists jobs found after it.
ALTER TABLE users ADD COLUMN email_digest BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN digest_sent_at TIMESTAMP WITH TIME ZONE;
