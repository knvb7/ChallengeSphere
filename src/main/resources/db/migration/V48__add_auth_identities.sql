CREATE TABLE IF NOT EXISTS challengesphere.auth_identities (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    provider_email VARCHAR(255),
    email_forwarding_enabled BOOLEAN,
    encrypted_refresh_token TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_auth_identity_provider_subject UNIQUE (provider, subject),
    CONSTRAINT uq_auth_identity_user_provider UNIQUE (user_id, provider)
);

INSERT INTO challengesphere.auth_identities (
    user_id,
    provider,
    subject,
    provider_email,
    created_at,
    last_used_at
)
SELECT
    id,
    'GOOGLE',
    google_id,
    email_id,
    COALESCE(created_at, CURRENT_TIMESTAMP),
    COALESCE(last_login_at, created_at, CURRENT_TIMESTAMP)
FROM challengesphere.users
WHERE google_id IS NOT NULL
  AND BTRIM(google_id) <> ''
ON CONFLICT DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_auth_identities_user_id
    ON challengesphere.auth_identities(user_id);

CREATE TABLE IF NOT EXISTS challengesphere.provider_token_revocations (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    encrypted_token TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(512),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_provider_token_revocations_pending
    ON challengesphere.provider_token_revocations(status, next_attempt_at)
    WHERE status IN ('PENDING', 'RETRY');
