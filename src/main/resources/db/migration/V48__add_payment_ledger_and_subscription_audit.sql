ALTER TABLE challengesphere.subscription_webhook_events
    ADD COLUMN user_id BIGINT,
    ADD COLUMN transaction_id VARCHAR(255),
    ADD COLUMN payload_sha256 VARCHAR(64),
    ADD COLUMN processed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE challengesphere.subscription_webhook_events
    ADD CONSTRAINT fk_subscription_webhook_events_user
    FOREIGN KEY (user_id) REFERENCES challengesphere.users(id) ON DELETE SET NULL;

CREATE INDEX idx_subscription_webhook_events_user_received
    ON challengesphere.subscription_webhook_events(user_id, received_at DESC);

CREATE TABLE challengesphere.payment_transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id),
    provider VARCHAR(32) NOT NULL,
    provider_transaction_id VARCHAR(255) NOT NULL,
    original_transaction_id VARCHAR(255),
    product_id VARCHAR(255),
    entitlement_id VARCHAR(100),
    status VARCHAR(48) NOT NULL,
    last_event_type VARCHAR(100) NOT NULL,
    store VARCHAR(32) NOT NULL,
    environment VARCHAR(32),
    amount NUMERIC(14, 4),
    amount_usd NUMERIC(14, 4),
    currency VARCHAR(3),
    period_type VARCHAR(32),
    purchased_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    cancellation_reason VARCHAR(64),
    expiration_reason VARCHAR(64),
    last_event_id VARCHAR(255) NOT NULL
        REFERENCES challengesphere.subscription_webhook_events(event_id),
    last_event_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_payment_transaction_provider_store
        UNIQUE (provider, store, provider_transaction_id)
);

CREATE INDEX idx_payment_transactions_user_purchased
    ON challengesphere.payment_transactions(user_id, purchased_at DESC);

CREATE INDEX idx_payment_transactions_original
    ON challengesphere.payment_transactions(provider, original_transaction_id);

CREATE TABLE challengesphere.payment_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id),
    webhook_event_id VARCHAR(255) NOT NULL UNIQUE
        REFERENCES challengesphere.subscription_webhook_events(event_id),
    provider VARCHAR(32) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    status VARCHAR(48) NOT NULL,
    transaction_id VARCHAR(255),
    original_transaction_id VARCHAR(255),
    product_id VARCHAR(255),
    new_product_id VARCHAR(255),
    entitlement_id VARCHAR(100),
    store VARCHAR(32),
    environment VARCHAR(32),
    amount NUMERIC(14, 4),
    amount_usd NUMERIC(14, 4),
    currency VARCHAR(3),
    tax_percentage NUMERIC(7, 6),
    commission_percentage NUMERIC(7, 6),
    takehome_percentage NUMERIC(7, 6),
    period_type VARCHAR(32),
    presented_offering_id VARCHAR(255),
    country_code VARCHAR(2),
    cancellation_reason VARCHAR(64),
    expiration_reason VARCHAR(64),
    purchased_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    event_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payment_history_user_event
    ON challengesphere.payment_history(user_id, event_at DESC, id DESC);

CREATE INDEX idx_payment_history_transaction
    ON challengesphere.payment_history(provider, transaction_id);

CREATE TABLE challengesphere.subscription_audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id),
    source VARCHAR(32) NOT NULL,
    source_reference VARCHAR(255),
    action VARCHAR(48) NOT NULL,
    previous_status VARCHAR(48),
    new_status VARCHAR(48),
    previous_premium BOOLEAN NOT NULL,
    new_premium BOOLEAN NOT NULL,
    product_id VARCHAR(255),
    reason VARCHAR(100),
    event_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_subscription_audit_source_reference
    ON challengesphere.subscription_audit_log(source, source_reference)
    WHERE source_reference IS NOT NULL;

CREATE INDEX idx_subscription_audit_user_event
    ON challengesphere.subscription_audit_log(user_id, event_at DESC, id DESC);
