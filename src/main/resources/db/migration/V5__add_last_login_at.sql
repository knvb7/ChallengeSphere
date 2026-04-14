-- V5: Add last_login_at column to users table
-- Tracks the most recent time a user accessed the app.
-- Used to determine which users are "recently active" so we only
-- cache / warm data for users who actually use the app.

ALTER TABLE challengesphere.users
    ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ NULL;

-- Index for efficiently querying recently active users
-- (e.g. "all users who logged in within the last 7 days")
CREATE INDEX IF NOT EXISTS idx_users_last_login_at
    ON challengesphere.users (last_login_at DESC)
    WHERE last_login_at IS NOT NULL;

-- Backfill: set last_login_at = created_at for all existing users
-- so they aren't treated as "never logged in"
UPDATE challengesphere.users
    SET last_login_at = created_at
    WHERE last_login_at IS NULL;

