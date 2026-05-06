-- V11: Google Sign-In support
-- Safe to re-run — all statements are idempotent.
-- Run apply_missing_migrations.sql first if V10 (content moderation) hasn't been applied yet.

-- 1. Add google_id column for OAuth2 users
ALTER TABLE challengesphere.users
    ADD COLUMN IF NOT EXISTS google_id VARCHAR(255) UNIQUE;

-- 2. Allow null hashed_password for social-login users (no-op if already nullable)
ALTER TABLE challengesphere.users
    ALTER COLUMN hashed_password DROP NOT NULL;
