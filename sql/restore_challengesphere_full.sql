\set ON_ERROR_STOP on

-- ChallengeSphere full local restore
-- Run with:
-- psql -h /tmp -p 5432 -U postgres -d mihirdalal -f /Users/mihirdalal/Documents/Startup/challengesphere/sql/restore_challengesphere_full.sql

BEGIN;
CREATE SCHEMA IF NOT EXISTS challengesphere;
COMMIT;

-- 1) Base tables/indexes/history
\i /Users/mihirdalal/Documents/Startup/challengesphere/sql/create_missing_tables.sql

-- 2) Migrations expected by current app
\i /Users/mihirdalal/Documents/Startup/challengesphere/sql/apply_missing_migrations.sql
\i /Users/mihirdalal/Documents/Startup/challengesphere/sql/apply_google_signin.sql
\i /Users/mihirdalal/Documents/Startup/challengesphere/sql/add_last_login_at.sql

-- 3) Compatibility fixes needed by seed_data.sql
CREATE TABLE IF NOT EXISTS challengesphere.user_preferences (
  user_id BIGINT PRIMARY KEY REFERENCES challengesphere.users(id) ON DELETE CASCADE,
  push_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  email_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  notify_on_glory BOOLEAN NOT NULL DEFAULT TRUE,
  notify_on_comment BOOLEAN NOT NULL DEFAULT TRUE,
  notify_on_follow BOOLEAN NOT NULL DEFAULT TRUE,
  notify_on_challenge_invite BOOLEAN NOT NULL DEFAULT TRUE,
  notify_on_challenge_update BOOLEAN NOT NULL DEFAULT TRUE,
  private_profile BOOLEAN NOT NULL DEFAULT FALSE,
  private_submissions BOOLEAN NOT NULL DEFAULT FALSE,
  show_on_leaderboard BOOLEAN NOT NULL DEFAULT TRUE,
  allow_challenge_invites BOOLEAN NOT NULL DEFAULT TRUE,
  dark_mode BOOLEAN NOT NULL DEFAULT FALSE,
  language VARCHAR(8) NOT NULL DEFAULT 'en',
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE challengesphere.challenges
  ADD COLUMN IF NOT EXISTS cover_photo_urls TEXT;

-- 4) Seed baseline dataset
\i /Users/mihirdalal/Documents/Startup/challengesphere/sql/seed_data.sql

-- 5) Optional: recompute leaderboard from live seeded data
-- \i /Users/mihirdalal/Documents/Startup/challengesphere/sql/seed_leaderboard.sql
