CREATE OR REPLACE FUNCTION challengesphere.valid_challenge_cover_media_references(
    raw_media_references TEXT,
    creator_user_id BIGINT,
    current_owner_user_id BIGINT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    raw_key TEXT;
    normalized_key TEXT;
    key_match TEXT[];
    key_owner_user_id BIGINT;
    key_count INTEGER := 0;
BEGIN
    IF raw_media_references IS NULL OR BTRIM(raw_media_references) = '' THEN
        RETURN TRUE;
    END IF;
    IF creator_user_id IS NULL OR LENGTH(raw_media_references) > 1024 THEN
        RETURN FALSE;
    END IF;

    FOREACH raw_key IN ARRAY REGEXP_SPLIT_TO_ARRAY(raw_media_references, ',') LOOP
        key_count := key_count + 1;
        IF key_count > 5 THEN
            RETURN FALSE;
        END IF;

        normalized_key := BTRIM(raw_key);
        key_match := REGEXP_MATCH(
            normalized_key,
            '^challenges/user_([0-9]+)/covers/[0-9]{13}_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.(jpg|png|gif)$'
        );
        IF key_match IS NULL THEN
            RETURN FALSE;
        END IF;

        key_owner_user_id := key_match[1]::BIGINT;
        IF key_owner_user_id IS DISTINCT FROM creator_user_id
                AND key_owner_user_id IS DISTINCT FROM current_owner_user_id THEN
            RETURN FALSE;
        END IF;
    END LOOP;

    RETURN key_count > 0;
END;
$$;

CREATE OR REPLACE FUNCTION challengesphere.valid_submission_media_references(
    raw_media_references TEXT,
    expected_user_id BIGINT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    raw_key TEXT;
    normalized_key TEXT;
    expected_pattern TEXT;
    key_count INTEGER := 0;
BEGIN
    IF raw_media_references IS NULL OR BTRIM(raw_media_references) = '' THEN
        RETURN TRUE;
    END IF;
    IF expected_user_id IS NULL OR LENGTH(raw_media_references) > 1024 THEN
        RETURN FALSE;
    END IF;

    expected_pattern := FORMAT(
        '^submissions/user_%s/(SUBMISSION_PHOTO/[0-9]{13}_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.(jpg|jpeg|png|gif)|SUBMISSION_VIDEO/[0-9]{13}_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.mp4)$',
        expected_user_id
    );

    FOREACH raw_key IN ARRAY REGEXP_SPLIT_TO_ARRAY(raw_media_references, ',') LOOP
        key_count := key_count + 1;
        IF key_count > 5 THEN
            RETURN FALSE;
        END IF;

        normalized_key := BTRIM(raw_key);
        IF normalized_key !~ expected_pattern THEN
            RETURN FALSE;
        END IF;
    END LOOP;

    RETURN key_count > 0;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_challenges_owned_cover_media_references'
          AND conrelid = 'challengesphere.challenges'::REGCLASS
    ) THEN
        ALTER TABLE challengesphere.challenges
            ADD CONSTRAINT chk_challenges_owned_cover_media_references
            CHECK (
                challengesphere.valid_challenge_cover_media_references(
                    cover_photo_urls,
                    creator_id,
                    current_owner_id
                )
            ) NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_submissions_owned_media_references'
          AND conrelid = 'challengesphere.submissions'::REGCLASS
    ) THEN
        ALTER TABLE challengesphere.submissions
            ADD CONSTRAINT chk_submissions_owned_media_references
            CHECK (
                challengesphere.valid_submission_media_references(media_urls, user_id)
            ) NOT VALID;
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM challengesphere.challenges
        WHERE NOT challengesphere.valid_challenge_cover_media_references(
            cover_photo_urls,
            creator_id,
            current_owner_id
        )
    ) THEN
        ALTER TABLE challengesphere.challenges
            VALIDATE CONSTRAINT chk_challenges_owned_cover_media_references;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM challengesphere.submissions
        WHERE NOT challengesphere.valid_submission_media_references(media_urls, user_id)
    ) THEN
        ALTER TABLE challengesphere.submissions
            VALIDATE CONSTRAINT chk_submissions_owned_media_references;
    END IF;
END;
$$;

COMMENT ON CONSTRAINT chk_challenges_owned_cover_media_references
    ON challengesphere.challenges
    IS 'Prevents new challenge cover references outside an owning user S3 namespace.';

COMMENT ON CONSTRAINT chk_submissions_owned_media_references
    ON challengesphere.submissions
    IS 'Prevents new submission media references outside the submitting user S3 namespace.';
