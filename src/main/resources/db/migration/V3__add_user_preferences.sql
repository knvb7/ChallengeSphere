-- User Preferences Table
-- Stores notification, privacy, and app preferences per user
CREATE TABLE IF NOT EXISTS challengesphere.user_preferences (
    id                          BIGSERIAL PRIMARY KEY,
    user_id                     BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,

    -- Notification preferences
    push_notifications_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
    email_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    notify_on_glory             BOOLEAN NOT NULL DEFAULT TRUE,  -- someone gloried your submission
    notify_on_comment           BOOLEAN NOT NULL DEFAULT TRUE,  -- someone commented on your submission
    notify_on_follow            BOOLEAN NOT NULL DEFAULT TRUE,  -- someone followed you
    notify_on_challenge_invite  BOOLEAN NOT NULL DEFAULT TRUE,  -- you received a challenge invite
    notify_on_challenge_update  BOOLEAN NOT NULL DEFAULT TRUE,  -- a challenge you joined was updated

    -- Privacy preferences
    private_profile             BOOLEAN NOT NULL DEFAULT FALSE, -- hide profile from non-followers
    private_submissions         BOOLEAN NOT NULL DEFAULT FALSE, -- all submissions private by default
    show_on_leaderboard         BOOLEAN NOT NULL DEFAULT TRUE,  -- appear in public leaderboards
    allow_challenge_invites     BOOLEAN NOT NULL DEFAULT TRUE,  -- allow others to invite you

    -- App preferences
    dark_mode                   BOOLEAN NOT NULL DEFAULT FALSE,
    language                    VARCHAR(10) NOT NULL DEFAULT 'en',

    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_user_preferences UNIQUE (user_id)
);

CREATE INDEX IF NOT EXISTS idx_user_preferences_user_id ON challengesphere.user_preferences(user_id);

