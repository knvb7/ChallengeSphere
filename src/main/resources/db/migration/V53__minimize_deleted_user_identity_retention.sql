DROP INDEX IF EXISTS challengesphere.idx_account_deletion_requests_original_email;
DROP INDEX IF EXISTS challengesphere.idx_account_deletion_requests_original_username;

ALTER TABLE challengesphere.account_deletion_requests
    DROP COLUMN IF EXISTS original_email_id,
    DROP COLUMN IF EXISTS original_username,
    DROP COLUMN IF EXISTS original_name,
    DROP COLUMN IF EXISTS identity_retained_at,
    ADD COLUMN IF NOT EXISTS email_identity_hmac CHAR(64),
    ADD COLUMN IF NOT EXISTS username_identity_hmac CHAR(64),
    ADD COLUMN IF NOT EXISTS identity_hash_expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS identity_hash_purged_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE challengesphere.account_deletion_requests
    ADD CONSTRAINT chk_account_deletion_email_identity_hmac
        CHECK (email_identity_hmac IS NULL OR email_identity_hmac ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT chk_account_deletion_username_identity_hmac
        CHECK (username_identity_hmac IS NULL OR username_identity_hmac ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT chk_account_deletion_identity_hash_expiry
        CHECK (
            (email_identity_hmac IS NULL AND username_identity_hmac IS NULL)
            OR identity_hash_expires_at IS NOT NULL
        );

CREATE INDEX IF NOT EXISTS idx_account_deletion_requests_email_hmac
    ON challengesphere.account_deletion_requests (email_identity_hmac)
    WHERE email_identity_hmac IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_account_deletion_requests_username_hmac
    ON challengesphere.account_deletion_requests (username_identity_hmac)
    WHERE username_identity_hmac IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_account_deletion_requests_identity_expiry
    ON challengesphere.account_deletion_requests (identity_hash_expires_at)
    WHERE email_identity_hmac IS NOT NULL OR username_identity_hmac IS NOT NULL;

COMMENT ON COLUMN challengesphere.account_deletion_requests.email_identity_hmac IS
    'Keyed pseudonymous fingerprint of normalized email, automatically cleared after 90 days.';

COMMENT ON COLUMN challengesphere.account_deletion_requests.username_identity_hmac IS
    'Keyed pseudonymous fingerprint of normalized username, automatically cleared after 90 days.';

COMMENT ON COLUMN challengesphere.account_deletion_requests.identity_hash_expires_at IS
    'Deadline after which the pseudonymous identity fingerprints must be cleared.';

COMMENT ON COLUMN challengesphere.account_deletion_requests.identity_hash_purged_at IS
    'Audit timestamp recording when the pseudonymous identity fingerprints were cleared.';
