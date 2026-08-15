ALTER TABLE challengesphere.challenge_invites
    ADD COLUMN IF NOT EXISTS recipient_user_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
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

-- Existing private share links have no trustworthy intended recipient. Revoke
-- them instead of allowing them to cross the new recipient boundary.
UPDATE challengesphere.challenge_invites invite
SET active = FALSE
FROM challengesphere.challenges challenge
WHERE challenge.id = invite.challenge_id
  AND challenge.private = TRUE
  AND invite.recipient_user_id IS NULL
  AND invite.active = TRUE;

ALTER TABLE challengesphere.notifications
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

-- Preserve acceptance for existing direct invitations by moving the challenge
-- reference out of the client-visible JSON payload.
UPDATE challengesphere.notifications notification
SET challenge_id = challenge.id
FROM challengesphere.challenges challenge
WHERE notification.type = 'CHALLENGE_INVITE'
  AND notification.challenge_id IS NULL
  AND notification.action_object IS NOT NULL
  AND notification.action_object ~ '"challengeId"[[:space:]]*:[[:space:]]*[0-9]+'
  AND challenge.id = NULLIF(
        substring(notification.action_object FROM '"challengeId"[[:space:]]*:[[:space:]]*([0-9]+)'),
        ''
      )::BIGINT;

-- A private invitation issued by a participant under the former policy cannot
-- be grandfathered into the new owner-only boundary.
DELETE FROM challengesphere.notifications notification
USING challengesphere.challenges challenge
WHERE notification.type = 'CHALLENGE_INVITE'
  AND notification.challenge_id = challenge.id
  AND challenge.private = TRUE
  AND notification.action_object ~ '"actorUserId"[[:space:]]*:[[:space:]]*[0-9]+'
  AND NULLIF(
        substring(notification.action_object FROM '"actorUserId"[[:space:]]*:[[:space:]]*([0-9]+)'),
        ''
      )::BIGINT <> COALESCE(challenge.current_owner_id, challenge.creator_id, -2);

UPDATE challengesphere.notifications notification
SET message = 'You received a private challenge invitation',
    action_object = '{}'
FROM challengesphere.challenges challenge
WHERE notification.type = 'CHALLENGE_INVITE'
  AND notification.challenge_id = challenge.id
  AND challenge.private = TRUE;

-- Also redact malformed legacy payloads that explicitly identify themselves as
-- private even when no usable challenge id could be recovered.
UPDATE challengesphere.notifications
SET message = 'You received a private challenge invitation',
    action_object = '{}'
WHERE type = 'CHALLENGE_INVITE'
  AND challenge_id IS NULL
  AND action_object ~* '"isPrivate"[[:space:]]*:[[:space:]]*true';

CREATE INDEX IF NOT EXISTS idx_notifications_challenge_invite
    ON challengesphere.notifications (challenge_id, user_id)
    WHERE type = 'CHALLENGE_INVITE';

-- Remove lobby invite state created by participant-issued private invitations
-- under the former policy.
DELETE FROM challengesphere.challenge_crew_invites crew_invite
USING challengesphere.challenges challenge
WHERE crew_invite.challenge_id = challenge.id
  AND challenge.private = TRUE
  AND COALESCE(crew_invite.invited_by_user_id, -1)
      <> COALESCE(challenge.current_owner_id, challenge.creator_id, -2);

-- V35 originally derived both ids from action_object. Private notifications now
-- keep the challenge id server-side and deliberately use an opaque action body,
-- so the trigger must prefer NEW.challenge_id and derive the private inviter
-- from the challenge's effective owner.
CREATE OR REPLACE FUNCTION challengesphere.capture_challenge_crew_invite()
RETURNS TRIGGER AS $$
DECLARE
    payload JSONB := '{}'::JSONB;
    payload_challenge_id BIGINT;
    payload_actor_id BIGINT;
BEGIN
    IF UPPER(COALESCE(NEW.type, '')) <> 'CHALLENGE_INVITE' THEN
        RETURN NEW;
    END IF;

    IF NEW.action_object IS NOT NULL THEN
        BEGIN
            payload := NEW.action_object::JSONB;
        EXCEPTION WHEN OTHERS THEN
            payload := '{}'::JSONB;
        END;
    END IF;

    BEGIN
        payload_challenge_id := COALESCE(
            NEW.challenge_id,
            NULLIF(payload ->> 'challengeId', '')::BIGINT
        );
        payload_actor_id := NULLIF(payload ->> 'actorUserId', '')::BIGINT;
    EXCEPTION WHEN OTHERS THEN
        payload_challenge_id := NEW.challenge_id;
        payload_actor_id := NULL;
    END;

    IF payload_challenge_id IS NULL THEN
        RETURN NEW;
    END IF;

    IF payload_actor_id IS NULL THEN
        SELECT COALESCE(challenge.current_owner_id, challenge.creator_id)
        INTO payload_actor_id
        FROM challengesphere.challenges challenge
        WHERE challenge.id = payload_challenge_id;
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
