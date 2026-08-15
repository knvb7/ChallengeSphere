ALTER TABLE challengesphere.account_deletion_requests
    ADD COLUMN IF NOT EXISTS original_email_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS original_username VARCHAR(50),
    ADD COLUMN IF NOT EXISTS original_name VARCHAR(120),
    ADD COLUMN IF NOT EXISTS identity_retained_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_account_deletion_requests_original_email
    ON challengesphere.account_deletion_requests (LOWER(original_email_id))
    WHERE original_email_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_account_deletion_requests_original_username
    ON challengesphere.account_deletion_requests (LOWER(original_username))
    WHERE original_username IS NOT NULL;

COMMENT ON COLUMN challengesphere.account_deletion_requests.original_email_id IS
    'Original account email retained indefinitely in the restricted deletion audit record.';

COMMENT ON COLUMN challengesphere.account_deletion_requests.original_username IS
    'Original username retained indefinitely in the restricted deletion audit record.';

COMMENT ON COLUMN challengesphere.account_deletion_requests.original_name IS
    'Original display name retained indefinitely in the restricted deletion audit record.';

COMMENT ON COLUMN challengesphere.account_deletion_requests.identity_retained_at IS
    'Time at which the retained identity fields were captured before account anonymization.';
