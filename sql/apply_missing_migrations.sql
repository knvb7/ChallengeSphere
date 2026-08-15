-- Apply cumulative runtime-required migrations to the challengesphere schema.
-- Safe to re-run — all statements are idempotent.

SET search_path TO challengesphere;

-- V10: content moderation
ALTER TABLE challengesphere.users
    ADD COLUMN IF NOT EXISTS banned_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS ban_reason VARCHAR(512);

CREATE TABLE IF NOT EXISTS challengesphere.moderation_flags (
    id BIGSERIAL PRIMARY KEY,
    submission_id BIGINT NOT NULL REFERENCES challengesphere.submissions(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    category VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    media_url TEXT,
    raw_response TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP WITH TIME ZONE,
    reviewed_by BIGINT REFERENCES challengesphere.users(id) ON DELETE SET NULL,
    resolution VARCHAR(32)
);

CREATE INDEX IF NOT EXISTS idx_moderation_flags_pending
    ON challengesphere.moderation_flags (created_at)
    WHERE reviewed_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_moderation_flags_user
    ON challengesphere.moderation_flags (user_id);

-- V47: Rekognition category values can contain a parent/name path longer than
-- the original provider-specific VARCHAR(32) assumption.
ALTER TABLE IF EXISTS challengesphere.moderation_flags
    ALTER COLUMN category TYPE TEXT;

-- V15: support multi-photo submission payloads from object storage.
ALTER TABLE IF EXISTS challengesphere.submissions
    ALTER COLUMN media_urls TYPE TEXT,
    ALTER COLUMN description TYPE TEXT;

-- V17: support longer notification payload JSON/messages.
ALTER TABLE IF EXISTS challengesphere.notifications
    ALTER COLUMN message TYPE TEXT,
    ALTER COLUMN action_object TYPE TEXT,
    ALTER COLUMN "type" TYPE VARCHAR(500);

-- V43: per-user challenge favorites.
ALTER TABLE challengesphere.user_challenges
    ADD COLUMN IF NOT EXISTS favorite BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS favorited_at TIMESTAMP WITH TIME ZONE;

INSERT INTO challengesphere.user_challenges (
    user_id,
    challenge_id,
    joined_at,
    completed,
    active,
    favorite
)
SELECT
    relationship.user_id,
    relationship.challenge_id,
    CURRENT_TIMESTAMP,
    FALSE,
    relationship.active,
    FALSE
FROM (
    SELECT
        c.creator_id AS user_id,
        c.id AS challenge_id,
        CASE
            WHEN c.creator_id = COALESCE(c.current_owner_id, c.creator_id)
                THEN COALESCE(c.active, FALSE)
            ELSE FALSE
        END AS active
    FROM challengesphere.challenges c
    WHERE c.creator_id IS NOT NULL
    UNION
    SELECT c.current_owner_id AS user_id, c.id AS challenge_id, COALESCE(c.active, FALSE) AS active
    FROM challengesphere.challenges c
    WHERE c.current_owner_id IS NOT NULL
) relationship
ON CONFLICT (user_id, challenge_id) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_user_challenges_favorites
    ON challengesphere.user_challenges (user_id, favorited_at DESC, challenge_id)
    WHERE favorite = TRUE AND COALESCE(banned, FALSE) = FALSE;

-- V44: Notification now maps challenge_id for private challenge invites.
-- Keep this additive subset here because production schemas are updated with
-- this idempotent script rather than automatically running Flyway migrations.
ALTER TABLE IF EXISTS challengesphere.challenge_invites
    ADD COLUMN IF NOT EXISTS recipient_user_id BIGINT;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'challengesphere'
          AND table_name = 'challenge_invites'
    ) AND NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_challenge_invites_recipient_user'
          AND conrelid = 'challengesphere.challenge_invites'::regclass
    ) THEN
        ALTER TABLE challengesphere.challenge_invites
            ADD CONSTRAINT fk_challenge_invites_recipient_user
            FOREIGN KEY (recipient_user_id)
            REFERENCES challengesphere.users(id)
            ON DELETE CASCADE;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_challenge_invites_recipient
    ON challengesphere.challenge_invites (recipient_user_id, active);

ALTER TABLE IF EXISTS challengesphere.notifications
    ADD COLUMN IF NOT EXISTS challenge_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_notifications_challenge'
          AND conrelid = 'challengesphere.notifications'::regclass
    ) THEN
        ALTER TABLE challengesphere.notifications
            ADD CONSTRAINT fk_notifications_challenge
            FOREIGN KEY (challenge_id)
            REFERENCES challengesphere.challenges(id)
            ON DELETE SET NULL;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_notifications_challenge_invite
    ON challengesphere.notifications (challenge_id, user_id)
    WHERE type = 'CHALLENGE_INVITE';

-- V34/V45: Account deletion depends on these records being available before
-- the application starts. Production currently applies this idempotent script
-- directly, so keep the runtime-required account deletion schema here too.
ALTER TABLE IF EXISTS challengesphere.users
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE IF NOT EXISTS challengesphere.account_deletion_requests (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id),
    original_email_id VARCHAR(255),
    original_username VARCHAR(50),
    original_name VARCHAR(120),
    identity_retained_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING',
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE,
    media_cleanup_count INTEGER NOT NULL DEFAULT 0
);

ALTER TABLE challengesphere.account_deletion_requests
    ADD COLUMN IF NOT EXISTS original_email_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS original_username VARCHAR(50),
    ADD COLUMN IF NOT EXISTS original_name VARCHAR(120),
    ADD COLUMN IF NOT EXISTS identity_retained_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING',
    ADD COLUMN IF NOT EXISTS requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS processed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS media_cleanup_count INTEGER NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS uq_account_deletion_request_user
    ON challengesphere.account_deletion_requests (user_id);

CREATE INDEX IF NOT EXISTS idx_account_deletion_requests_original_email
    ON challengesphere.account_deletion_requests (LOWER(original_email_id))
    WHERE original_email_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_account_deletion_requests_original_username
    ON challengesphere.account_deletion_requests (LOWER(original_username))
    WHERE original_username IS NOT NULL;

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
    failure_reason TEXT
);

ALTER TABLE challengesphere.account_media_cleanup_queue
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS processed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS failure_reason TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_account_media_cleanup_object
    ON challengesphere.account_media_cleanup_queue (requested_by_user_id, object_key);

CREATE INDEX IF NOT EXISTS idx_account_media_cleanup_pending
    ON challengesphere.account_media_cleanup_queue (status, requested_at)
    WHERE status = 'PENDING';

-- V46: RevenueCat-backed premium subscription state and webhook idempotency.
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
