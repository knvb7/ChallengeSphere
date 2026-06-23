-- Apply missing migrations to challengesphere schema.
-- Status at 2026-04-20: V1–V9 applied; V10 (content moderation) is missing.
-- Safe to re-run — all statements are idempotent.

SET search_path TO challengesphere;

-- V10: content moderation
ALTER TABLE challengesphere.users
    ADD COLUMN IF NOT EXISTS banned_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS ban_reason VARCHAR(512);

CREATE TABLE IF NOT EXISTS challengesphere.moderation_flags (
    id BIGSERIAL PRIMARY KEY,
    submission_id BIGINT NOT NULL REFERENCES challengesphere.submissions(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    category VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    media_url TEXT,
    raw_response TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP WITH TIME ZONE,
    reviewed_by BIGINT REFERENCES challengesphere.users(id) ON DELETE SET NULL,
    resolution VARCHAR(32)
);

CREATE INDEX IF NOT EXISTS idx_moderation_flags_pending
    ON challengesphere.moderation_flags (created_at)
    WHERE reviewed_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_moderation_flags_user
    ON challengesphere.moderation_flags (user_id);

-- V15: support multi-photo submission payloads from object storage.
ALTER TABLE IF EXISTS challengesphere.submissions
    ALTER COLUMN media_urls TYPE TEXT,
    ALTER COLUMN description TYPE TEXT;

-- V17: support longer notification payload JSON/messages.
ALTER TABLE IF EXISTS challengesphere.notifications
    ALTER COLUMN message TYPE TEXT,
    ALTER COLUMN action_object TYPE TEXT,
    ALTER COLUMN "type" TYPE VARCHAR(500);
