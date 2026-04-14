-- ============================================================
-- Standalone SQL script: Add last_login_at to users table
-- Run this directly on your PostgreSQL database.
-- ============================================================

-- 1. Add the column
ALTER TABLE challengesphere.users
    ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ NULL;

-- 2. Partial index for efficiently querying recently active users
--    Only indexes rows where last_login_at is set, keeping the index small.
CREATE INDEX IF NOT EXISTS idx_users_last_login_at
    ON challengesphere.users (last_login_at DESC)
    WHERE last_login_at IS NOT NULL;

-- 3. Backfill existing users: set last_login_at = created_at
--    so they aren't treated as "never logged in"
UPDATE challengesphere.users
    SET last_login_at = created_at
    WHERE last_login_at IS NULL;

-- ============================================================
-- Useful queries you can run after this:
-- ============================================================

-- Users active in the last 7 days
-- SELECT * FROM challengesphere.users
--     WHERE last_login_at >= NOW() - INTERVAL '7 days'
--       AND active = true
--     ORDER BY last_login_at DESC;

-- Users who haven't logged in for 30+ days (candidates for cache eviction)
-- SELECT id, username, last_login_at
--     FROM challengesphere.users
--     WHERE last_login_at < NOW() - INTERVAL '30 days'
--        OR last_login_at IS NULL
--     ORDER BY last_login_at ASC NULLS FIRST;

-- Count of daily active users (DAU)
-- SELECT COUNT(*)
--     FROM challengesphere.users
--     WHERE last_login_at >= NOW() - INTERVAL '1 day'
--       AND active = true;

-- Count of weekly active users (WAU)
-- SELECT COUNT(*)
--     FROM challengesphere.users
--     WHERE last_login_at >= NOW() - INTERVAL '7 days'
--       AND active = true;

-- Count of monthly active users (MAU)
-- SELECT COUNT(*)
--     FROM challengesphere.users
--     WHERE last_login_at >= NOW() - INTERVAL '30 days'
--       AND active = true;

