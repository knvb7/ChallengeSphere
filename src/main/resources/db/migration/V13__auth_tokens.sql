CREATE TABLE IF NOT EXISTS challengesphere.auth_tokens (
    token_hash VARCHAR(64) NOT NULL,
    token_type VARCHAR(20) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (token_hash, token_type)
);

CREATE INDEX IF NOT EXISTS idx_auth_tokens_user_id
    ON challengesphere.auth_tokens (user_id);

CREATE INDEX IF NOT EXISTS idx_auth_tokens_expires_at
    ON challengesphere.auth_tokens (expires_at);
