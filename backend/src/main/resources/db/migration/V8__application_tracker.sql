-- Post-submission tracking: outcome stage, a follow-up reminder, and a timeline of changes.
ALTER TABLE applications ADD COLUMN stage        VARCHAR(16);                 -- null until the user tracks an outcome
ALTER TABLE applications ADD COLUMN follow_up_at TIMESTAMP WITH TIME ZONE;    -- when to nudge the user; null = none
ALTER TABLE applications ADD COLUMN timeline     TEXT NOT NULL DEFAULT '[]';  -- JSON array of {stage, note, at}
