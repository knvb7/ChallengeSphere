CREATE TABLE IF NOT EXISTS challengesphere.challenge_crew_lifecycle (
    challenge_id BIGINT PRIMARY KEY REFERENCES challengesphere.challenges(id) ON DELETE CASCADE,
    started_at TIMESTAMP WITH TIME ZONE,
    started_by_user_id BIGINT REFERENCES challengesphere.users(id),
    rematch_of_challenge_id BIGINT REFERENCES challengesphere.challenges(id) ON DELETE SET NULL,
    recap_ready_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_challenge_crew_single_rematch
    ON challengesphere.challenge_crew_lifecycle (rematch_of_challenge_id)
    WHERE rematch_of_challenge_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS challengesphere.challenge_crew_invites (
    id BIGSERIAL PRIMARY KEY,
    challenge_id BIGINT NOT NULL REFERENCES challengesphere.challenges(id) ON DELETE CASCADE,
    invited_user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    invited_by_user_id BIGINT REFERENCES challengesphere.users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_challenge_crew_invitee UNIQUE (challenge_id, invited_user_id)
);

CREATE INDEX IF NOT EXISTS idx_challenge_crew_invites_challenge
    ON challengesphere.challenge_crew_invites (challenge_id);

CREATE TABLE IF NOT EXISTS challengesphere.challenge_peer_reminder_preferences (
    id BIGSERIAL PRIMARY KEY,
    challenge_id BIGINT NOT NULL REFERENCES challengesphere.challenges(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    last_nudged_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_challenge_peer_reminder_preference UNIQUE (challenge_id, user_id)
);

CREATE TABLE IF NOT EXISTS challengesphere.challenge_peer_nudges (
    id BIGSERIAL PRIMARY KEY,
    challenge_id BIGINT NOT NULL REFERENCES challengesphere.challenges(id) ON DELETE CASCADE,
    sender_user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    recipient_user_id BIGINT NOT NULL REFERENCES challengesphere.users(id) ON DELETE CASCADE,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_challenge_peer_nudges_recipient
    ON challengesphere.challenge_peer_nudges (challenge_id, recipient_user_id, sent_at DESC);

-- Existing challenges keep their current behaviour. Only challenges inserted after this
-- migration enter a lobby when they are private.
INSERT INTO challengesphere.challenge_crew_lifecycle (
    challenge_id,
    started_at,
    started_by_user_id,
    created_at,
    updated_at
)
SELECT
    challenge.id,
    COALESCE(challenge.created_at, CURRENT_TIMESTAMP),
    COALESCE(challenge.current_owner_id, challenge.creator_id),
    COALESCE(challenge.created_at, CURRENT_TIMESTAMP),
    CURRENT_TIMESTAMP
FROM challengesphere.challenges challenge
ON CONFLICT (challenge_id) DO NOTHING;

CREATE OR REPLACE FUNCTION challengesphere.initialize_challenge_crew_lifecycle()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO challengesphere.challenge_crew_lifecycle (
        challenge_id,
        started_at,
        started_by_user_id
    ) VALUES (
        NEW.id,
        CASE WHEN COALESCE(NEW.private, FALSE) THEN NULL ELSE COALESCE(NEW.created_at, CURRENT_TIMESTAMP) END,
        CASE WHEN COALESCE(NEW.private, FALSE) THEN NULL ELSE COALESCE(NEW.current_owner_id, NEW.creator_id) END
    )
    ON CONFLICT (challenge_id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_initialize_challenge_crew_lifecycle ON challengesphere.challenges;
CREATE TRIGGER trg_initialize_challenge_crew_lifecycle
AFTER INSERT ON challengesphere.challenges
FOR EACH ROW EXECUTE FUNCTION challengesphere.initialize_challenge_crew_lifecycle();

-- Direct in-app invitations are mirrored into lobby readiness without changing the
-- existing invite delivery code. Malformed legacy action objects are deliberately ignored.
CREATE OR REPLACE FUNCTION challengesphere.capture_challenge_crew_invite()
RETURNS TRIGGER AS $$
DECLARE
    payload JSONB;
    payload_challenge_id BIGINT;
    payload_actor_id BIGINT;
BEGIN
    IF UPPER(COALESCE(NEW.type, '')) <> 'CHALLENGE_INVITE' OR NEW.action_object IS NULL THEN
        RETURN NEW;
    END IF;

    BEGIN
        payload := NEW.action_object::JSONB;
        payload_challenge_id := NULLIF(payload ->> 'challengeId', '')::BIGINT;
        payload_actor_id := NULLIF(payload ->> 'actorUserId', '')::BIGINT;
    EXCEPTION WHEN OTHERS THEN
        RETURN NEW;
    END;

    IF payload_challenge_id IS NULL THEN
        RETURN NEW;
    END IF;

    INSERT INTO challengesphere.challenge_crew_invites (
        challenge_id,
        invited_user_id,
        invited_by_user_id
    ) VALUES (
        payload_challenge_id,
        NEW.user_id,
        payload_actor_id
    )
    ON CONFLICT (challenge_id, invited_user_id) DO UPDATE
    SET invited_by_user_id = EXCLUDED.invited_by_user_id,
        updated_at = CURRENT_TIMESTAMP;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_capture_challenge_crew_invite ON challengesphere.notifications;
CREATE TRIGGER trg_capture_challenge_crew_invite
AFTER INSERT ON challengesphere.notifications
FOR EACH ROW EXECUTE FUNCTION challengesphere.capture_challenge_crew_invite();
