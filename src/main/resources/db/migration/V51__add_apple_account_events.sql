CREATE TABLE IF NOT EXISTS challengesphere.apple_account_events (
    jti VARCHAR(255) PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    event_time BIGINT,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_apple_account_events_subject
    ON challengesphere.apple_account_events(subject, received_at DESC);
