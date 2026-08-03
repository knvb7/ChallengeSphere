ALTER TABLE challengesphere.users
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE IF NOT EXISTS challengesphere.account_deletion_requests (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id),
    status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING',
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE,
    media_cleanup_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_account_deletion_request_user UNIQUE (user_id),
    CONSTRAINT chk_account_deletion_status
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE IF NOT EXISTS challengesphere.account_media_cleanup_queue (
    id BIGSERIAL PRIMARY KEY,
    deletion_request_id BIGINT NOT NULL
        REFERENCES challengesphere.account_deletion_requests(id) ON DELETE CASCADE,
    requested_by_user_id BIGINT NOT NULL REFERENCES challengesphere.users(id),
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    object_key TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE,
    failure_reason TEXT,
    CONSTRAINT uq_account_media_cleanup_object UNIQUE (requested_by_user_id, object_key),
    CONSTRAINT chk_account_media_cleanup_status
        CHECK (status IN ('PENDING', 'DELETED', 'SKIPPED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_account_media_cleanup_pending
    ON challengesphere.account_media_cleanup_queue (status, requested_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE challengesphere.account_media_cleanup_queue IS
    'Administrative cleanup queue only. The application records owned object keys here and never deletes S3 objects at runtime.';
