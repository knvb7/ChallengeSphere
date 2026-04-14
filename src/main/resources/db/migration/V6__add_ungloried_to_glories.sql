-- V6: Add ungloried column to glories table
-- Tracks whether a user has marked a submission as "not genuine" (ungloried).
-- This is separate from glory (active) — a user can toggle each independently.

ALTER TABLE challengesphere.glories
    ADD COLUMN IF NOT EXISTS ungloried BOOLEAN NOT NULL DEFAULT FALSE;

-- Index for efficiently querying unglory state per submission
CREATE INDEX IF NOT EXISTS idx_glories_submission_ungloried
    ON challengesphere.glories (submission_id, ungloried)
    WHERE ungloried = TRUE;
