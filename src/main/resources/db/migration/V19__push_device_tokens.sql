CREATE TABLE IF NOT EXISTS challengesphere.push_device_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    expo_push_token VARCHAR(255) NOT NULL UNIQUE,
    platform VARCHAR(32) NOT NULL DEFAULT 'unknown',
    timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_daily_reminder_sent_date DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_push_device_tokens_user_enabled
    ON challengesphere.push_device_tokens(user_id, enabled);

CREATE INDEX IF NOT EXISTS idx_push_device_tokens_enabled_timezone
    ON challengesphere.push_device_tokens(enabled, timezone);
