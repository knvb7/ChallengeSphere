CREATE TABLE IF NOT EXISTS challengesphere.content_reports (
    id BIGSERIAL PRIMARY KEY,
    reporter_user_id BIGINT NOT NULL REFERENCES challengesphere.users(id),
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    target_owner_user_id BIGINT NOT NULL REFERENCES challengesphere.users(id),
    reason VARCHAR(64) NOT NULL,
    details VARCHAR(2000),
    target_snapshot JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    resolution VARCHAR(64),
    reviewed_by BIGINT REFERENCES challengesphere.users(id),
    reviewed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_content_report_target_type
        CHECK (target_type IN ('SUBMISSION', 'COMMENT', 'CHALLENGE')),
    CONSTRAINT chk_content_report_not_self
        CHECK (reporter_user_id <> target_owner_user_id)
);

CREATE INDEX IF NOT EXISTS idx_content_reports_open_queue
    ON challengesphere.content_reports(status, created_at)
    WHERE status = 'OPEN';

CREATE UNIQUE INDEX IF NOT EXISTS uq_content_reports_open_reporter_target
    ON challengesphere.content_reports(reporter_user_id, target_type, target_id)
    WHERE status = 'OPEN';
