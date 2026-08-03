ALTER TABLE challengesphere.user_challenges
    ADD COLUMN IF NOT EXISTS banned BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS removal_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS removed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS removed_by_user_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_user_challenges_banned
    ON challengesphere.user_challenges (challenge_id, user_id)
    WHERE banned = TRUE;
