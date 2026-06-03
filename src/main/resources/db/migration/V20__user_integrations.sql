CREATE TABLE IF NOT EXISTS challengesphere.user_integrations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL,
    external_user_id VARCHAR(128),
    external_username VARCHAR(255),
    display_name VARCHAR(255),
    avatar_url VARCHAR(1024),
    access_token TEXT,
    refresh_token TEXT,
    access_token_expires_at TIMESTAMPTZ,
    scopes VARCHAR(512),
    snapshot_json TEXT,
    connected_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    last_synced_at TIMESTAMPTZ,
    CONSTRAINT uk_user_integrations_user_provider UNIQUE (user_id, provider)
);

CREATE INDEX IF NOT EXISTS idx_user_integrations_user_id
    ON challengesphere.user_integrations(user_id);
