-- V10: content moderation
--  1. users.banned_at / users.ban_reason for moderation-driven deactivation.
--     Existing `active` flag was overloaded for "soft-deleted" vs "banned". We keep
--     `active` as the live/not-live flag, and add banned_at/ban_reason as the audit
--     trail explaining *why* a user was flipped to inactive by moderation.
--  2. moderation_flags table — rows for submissions flagged as LIKELY (but not
--     VERY_LIKELY) by Vision SafeSearch. These need human review before the user
--     is punished, and the submission stays hidden (active=false) in the meantime.

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
