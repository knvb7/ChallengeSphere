CREATE TABLE IF NOT EXISTS challengesphere.user_subscriptions (
    user_id BIGINT PRIMARY KEY REFERENCES challengesphere.users(id),
    provider VARCHAR(32) NOT NULL DEFAULT 'REVENUECAT',
    app_user_id VARCHAR(255) NOT NULL UNIQUE,
    entitlement_id VARCHAR(100) NOT NULL,
    product_id VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    store VARCHAR(32),
    environment VARCHAR(32),
    expires_at TIMESTAMP WITH TIME ZONE,
    will_renew BOOLEAN NOT NULL DEFAULT FALSE,
    last_synced_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS challengesphere.subscription_webhook_events (
    event_id VARCHAR(255) PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    app_user_id VARCHAR(255),
    environment VARCHAR(32),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_subscriptions_status
    ON challengesphere.user_subscriptions(status, expires_at);
