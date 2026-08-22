-- Exact-target cleanup for sql/seed_app_store_screenshot_fixture_v1.sql.
--
-- This file is intentionally conservative. It aborts if any real account has
-- joined or interacted with fixture content, or if a synthetic fixture account
-- has escaped the fixture boundary. It never guesses how to preserve such data.
-- It does not delete S3 objects; it prints their exact keys before commit.
--
-- Run only after reviewing the preflight contract in
-- docs/APP_STORE_SCREENSHOT_FIXTURE_V1.md:
--
--   psql -v ON_ERROR_STOP=1 \
--     -v app_store_fixture_cleanup_confirm=DELETE_APP_STORE_FIXTURE_V1 \
--     -f sql/cleanup_app_store_screenshot_fixture_v1.sql

\set ON_ERROR_STOP on

\if :{?app_store_fixture_cleanup_confirm}
\else
\echo 'Refusing cleanup: pass -v app_store_fixture_cleanup_confirm=DELETE_APP_STORE_FIXTURE_V1.'
\quit 3
\endif

BEGIN;

SELECT set_config(
    'challengesphere.app_store_fixture_cleanup_confirm',
    :'app_store_fixture_cleanup_confirm',
    TRUE
);

DO $$
BEGIN
    IF current_setting(
        'challengesphere.app_store_fixture_cleanup_confirm',
        TRUE
    ) IS DISTINCT FROM 'DELETE_APP_STORE_FIXTURE_V1' THEN
        RAISE EXCEPTION
            'Refusing cleanup: confirmation value is not exact';
    END IF;
END
$$;

SELECT pg_advisory_xact_lock(
    hashtext('challengesphere:app-store-screenshot-fixture:v1')::BIGINT
);

CREATE TEMP TABLE app_store_cleanup_users (
    id BIGINT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email_id VARCHAR(255) NOT NULL UNIQUE,
    bio TEXT NOT NULL,
    profile_object_key TEXT NOT NULL UNIQUE
) ON COMMIT DROP;

INSERT INTO app_store_cleanup_users (
    id,
    username,
    email_id,
    bio,
    profile_object_key
)
VALUES
    (910000101, 'maya_moves',  'maya_moves@appstore-fixture.invalid',  'Morning movement, tiny wins, and good light.',              'profiles/user_910000101/1787000000101_a0010101-0000-4000-8000-000000000101.png'),
    (910000102, 'nora_makes',  'nora_makes@appstore-fixture.invalid',  'Paper, paint, and one small creative win a day.',            'profiles/user_910000102/1787000000102_a0010102-0000-4000-8000-000000000102.png'),
    (910000103, 'leo_outside', 'leo_outside@appstore-fixture.invalid', 'Trails before traffic and coffee after climbs.',             'profiles/user_910000103/1787000000103_a0010103-0000-4000-8000-000000000103.png'),
    (910000104, 'sami_reads',  'sami_reads@appstore-fixture.invalid',  'Books, marginalia, and ideas worth keeping.',                'profiles/user_910000104/1787000000104_a0010104-0000-4000-8000-000000000104.png'),
    (910000105, 'devon_daily', 'devon_daily@appstore-fixture.invalid', 'Simple systems, steady practice, less noise.',               'profiles/user_910000105/1787000000105_a0010105-0000-4000-8000-000000000105.png');

CREATE TEMP TABLE app_store_cleanup_challenges (
    fixture_key TEXT PRIMARY KEY,
    title VARCHAR(255) NOT NULL UNIQUE,
    owner_user_id BIGINT NOT NULL,
    cover_object_key TEXT NOT NULL UNIQUE
) ON COMMIT DROP;

INSERT INTO app_store_cleanup_challenges (
    fixture_key,
    title,
    owner_user_id,
    cover_object_key
)
VALUES
    ('morning-light', 'Seven Days of Morning Light', 910000101, 'challenges/user_910000101/covers/1787000001001_c1010001-0000-4000-8000-000000000001.png'),
    ('creative-wins', 'Tiny Creative Wins', 910000102, 'challenges/user_910000102/covers/1787000001002_c1020001-0000-4000-8000-000000000002.png'),
    ('one-chapter',   'One Chapter, One Thought', 910000104, 'challenges/user_910000104/covers/1787000001003_c1040001-0000-4000-8000-000000000003.png'),
    ('trail-crew',    'Weekend Trail Crew', 910000103, 'challenges/user_910000103/covers/1787000001004_c1030001-0000-4000-8000-000000000004.png');

-- Fixed IDs/usernames/emails and the marker tag are all reserved. Any mismatch
-- means this cleanup cannot prove ownership and must stop before deleting.
DO $$
DECLARE
    resolved_user_count INTEGER;
    resolved_challenge_count INTEGER;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM challengesphere.users existing
        JOIN app_store_cleanup_users fixture
          ON existing.id = fixture.id
          OR LOWER(existing.username) = LOWER(fixture.username)
          OR LOWER(existing.email_id) = LOWER(fixture.email_id)
          OR existing.profile_pic_url = fixture.profile_object_key
        WHERE existing.id IS DISTINCT FROM fixture.id
           OR existing.username IS DISTINCT FROM fixture.username
           OR existing.email_id IS DISTINCT FROM fixture.email_id
           OR existing.bio IS DISTINCT FROM fixture.bio
           OR existing.profile_pic_url IS DISTINCT FROM fixture.profile_object_key
           OR existing.phone IS NOT NULL
           OR existing.hashed_password IS NOT NULL
           OR existing.google_id IS NOT NULL
           OR existing.last_login_at IS NOT NULL
           OR existing.banned_at IS NOT NULL
           OR existing.deleted_at IS NOT NULL
           OR existing.role IS DISTINCT FROM 'USER'
    ) THEN
        RAISE EXCEPTION
            'Reserved fixture user collision or ownership mismatch; cleanup aborted';
    END IF;

    SELECT COUNT(*)
    INTO resolved_user_count
    FROM challengesphere.users existing
    JOIN app_store_cleanup_users fixture
      ON existing.id = fixture.id
     AND existing.username = fixture.username
     AND existing.email_id = fixture.email_id;

    IF resolved_user_count NOT IN (0, 5) THEN
        RAISE EXCEPTION
            'Partial fixture account set found (% of 5); cleanup aborted',
            resolved_user_count;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.challenges existing
        JOIN app_store_cleanup_challenges fixture
          ON existing.title = fixture.title
          OR existing.cover_photo_urls = fixture.cover_object_key
        WHERE existing.creator_id IS DISTINCT FROM fixture.owner_user_id
           OR existing.current_owner_id IS DISTINCT FROM fixture.owner_user_id
           OR existing.cover_photo_urls IS DISTINCT FROM fixture.cover_object_key
           OR NOT (
               '#appstore_fixture_v1' = ANY(
                   string_to_array(REPLACE(COALESCE(existing.hashtags, ''), ' ', ''), ',')
               )
           )
    ) OR EXISTS (
        SELECT 1
        FROM challengesphere.challenges existing
        WHERE '#appstore_fixture_v1' = ANY(
                  string_to_array(REPLACE(COALESCE(existing.hashtags, ''), ' ', ''), ',')
              )
          AND NOT EXISTS (
              SELECT 1
              FROM app_store_cleanup_challenges fixture
              WHERE fixture.title = existing.title
                AND fixture.owner_user_id = existing.creator_id
                AND fixture.owner_user_id = existing.current_owner_id
                AND fixture.cover_object_key = existing.cover_photo_urls
          )
    ) THEN
        RAISE EXCEPTION
            'Fixture challenge title, marker, owner, or cover mismatch; cleanup aborted';
    END IF;

    SELECT COUNT(*)
    INTO resolved_challenge_count
    FROM challengesphere.challenges existing
    JOIN app_store_cleanup_challenges fixture
      ON existing.title = fixture.title
     AND existing.creator_id = fixture.owner_user_id
     AND existing.current_owner_id = fixture.owner_user_id
     AND existing.cover_photo_urls = fixture.cover_object_key
    WHERE '#appstore_fixture_v1' = ANY(
              string_to_array(REPLACE(COALESCE(existing.hashtags, ''), ' ', ''), ',')
          );

    IF resolved_challenge_count NOT IN (0, 4) THEN
        RAISE EXCEPTION
            'Partial fixture challenge set found (% of 4); cleanup aborted',
            resolved_challenge_count;
    END IF;

    IF resolved_user_count = 0 AND resolved_challenge_count <> 0 THEN
        RAISE EXCEPTION
            'Fixture challenges exist without all fixture accounts; cleanup aborted';
    END IF;
END
$$;

CREATE TEMP TABLE app_store_cleanup_challenge_ids ON COMMIT DROP AS
SELECT existing.id AS challenge_id, fixture.fixture_key
FROM challengesphere.challenges existing
JOIN app_store_cleanup_challenges fixture
  ON existing.title = fixture.title
 AND existing.creator_id = fixture.owner_user_id
 AND existing.current_owner_id = fixture.owner_user_id
 AND existing.cover_photo_urls = fixture.cover_object_key
WHERE '#appstore_fixture_v1' = ANY(
          string_to_array(REPLACE(COALESCE(existing.hashtags, ''), ' ', ''), ',')
      );

CREATE TEMP TABLE app_store_cleanup_submission_ids ON COMMIT DROP AS
SELECT submission.id AS submission_id
FROM challengesphere.submissions submission
JOIN app_store_cleanup_challenge_ids fixture
  ON fixture.challenge_id = submission.challenge_id;

CREATE TEMP TABLE app_store_cleanup_comment_ids ON COMMIT DROP AS
SELECT comment.id AS comment_id
FROM challengesphere.comments comment
JOIN app_store_cleanup_submission_ids fixture
  ON fixture.submission_id = comment.submission_id;

-- The fixture is safe to delete only while it is a closed synthetic island.
-- These checks explicitly refuse real memberships, UGC, follows, invites,
-- reports, moderation work, or other cross-boundary state.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM challengesphere.challenges challenge
        WHERE (
                challenge.creator_id IN (SELECT id FROM app_store_cleanup_users)
             OR challenge.current_owner_id IN (SELECT id FROM app_store_cleanup_users)
        )
          AND challenge.id NOT IN (
              SELECT challenge_id FROM app_store_cleanup_challenge_ids
          )
    ) THEN
        RAISE EXCEPTION 'Synthetic account owns a non-fixture challenge; cleanup aborted';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.user_challenges participation
        WHERE (
                participation.user_id IN (SELECT id FROM app_store_cleanup_users)
             OR participation.challenge_id IN (SELECT challenge_id FROM app_store_cleanup_challenge_ids)
        )
          AND NOT (
              participation.user_id IN (SELECT id FROM app_store_cleanup_users)
              AND participation.challenge_id IN (
                  SELECT challenge_id FROM app_store_cleanup_challenge_ids
              )
          )
    ) THEN
        RAISE EXCEPTION 'Real/fixture challenge participation boundary found; cleanup aborted';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.submissions submission
        WHERE (
                submission.user_id IN (SELECT id FROM app_store_cleanup_users)
             OR submission.challenge_id IN (SELECT challenge_id FROM app_store_cleanup_challenge_ids)
        )
          AND NOT (
              submission.user_id IN (SELECT id FROM app_store_cleanup_users)
              AND submission.challenge_id IN (
                  SELECT challenge_id FROM app_store_cleanup_challenge_ids
              )
          )
    ) THEN
        RAISE EXCEPTION 'Real/fixture submission boundary found; cleanup aborted';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.comments comment
        WHERE (
                comment.user_id IN (SELECT id FROM app_store_cleanup_users)
             OR comment.submission_id IN (SELECT submission_id FROM app_store_cleanup_submission_ids)
        )
          AND NOT (
              comment.user_id IN (SELECT id FROM app_store_cleanup_users)
              AND comment.submission_id IN (
                  SELECT submission_id FROM app_store_cleanup_submission_ids
              )
          )
    ) OR EXISTS (
        SELECT 1
        FROM challengesphere.glories glory
        WHERE (
                glory.user_id IN (SELECT id FROM app_store_cleanup_users)
             OR glory.submission_id IN (SELECT submission_id FROM app_store_cleanup_submission_ids)
        )
          AND NOT (
              glory.user_id IN (SELECT id FROM app_store_cleanup_users)
              AND glory.submission_id IN (
                  SELECT submission_id FROM app_store_cleanup_submission_ids
              )
          )
    ) THEN
        RAISE EXCEPTION 'Real/fixture comment or glory boundary found; cleanup aborted';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.follows follow
        WHERE (
            follow.follower_id IN (SELECT id FROM app_store_cleanup_users)
        ) <> (
            follow.following_id IN (SELECT id FROM app_store_cleanup_users)
        )
    ) THEN
        RAISE EXCEPTION 'Real/fixture follow boundary found; cleanup aborted';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.leaderboard_entries entry
        WHERE (
                entry.user_id IN (SELECT id FROM app_store_cleanup_users)
             OR entry.challenge_id IN (SELECT challenge_id FROM app_store_cleanup_challenge_ids)
        )
          AND NOT (
              entry.user_id IN (SELECT id FROM app_store_cleanup_users)
              AND entry.challenge_id IN (
                  SELECT challenge_id FROM app_store_cleanup_challenge_ids
              )
          )
    ) THEN
        RAISE EXCEPTION 'Real/fixture leaderboard boundary found; cleanup aborted';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.challenge_invites invite
        WHERE invite.challenge_id IN (SELECT challenge_id FROM app_store_cleanup_challenge_ids)
           OR invite.created_by IN (SELECT id FROM app_store_cleanup_users)
           OR invite.recipient_user_id IN (SELECT id FROM app_store_cleanup_users)
    ) OR EXISTS (
        SELECT 1
        FROM challengesphere.challenge_crew_invites invite
        WHERE invite.challenge_id IN (SELECT challenge_id FROM app_store_cleanup_challenge_ids)
           OR invite.invited_user_id IN (SELECT id FROM app_store_cleanup_users)
           OR invite.invited_by_user_id IN (SELECT id FROM app_store_cleanup_users)
    ) OR EXISTS (
        SELECT 1
        FROM challengesphere.challenge_peer_nudges nudge
        WHERE nudge.challenge_id IN (SELECT challenge_id FROM app_store_cleanup_challenge_ids)
           OR nudge.sender_user_id IN (SELECT id FROM app_store_cleanup_users)
           OR nudge.recipient_user_id IN (SELECT id FROM app_store_cleanup_users)
    ) THEN
        RAISE EXCEPTION 'Invite or nudge state touches the fixture; cleanup aborted';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.content_reports report
        WHERE report.reporter_user_id IN (SELECT id FROM app_store_cleanup_users)
           OR report.target_owner_user_id IN (SELECT id FROM app_store_cleanup_users)
           OR (
               report.target_type = 'CHALLENGE'
               AND report.target_id IN (SELECT challenge_id FROM app_store_cleanup_challenge_ids)
           )
           OR (
               report.target_type = 'SUBMISSION'
               AND report.target_id IN (SELECT submission_id FROM app_store_cleanup_submission_ids)
           )
           OR (
               report.target_type = 'COMMENT'
               AND report.target_id IN (SELECT comment_id FROM app_store_cleanup_comment_ids)
           )
    ) OR EXISTS (
        SELECT 1
        FROM challengesphere.moderation_flags flag
        WHERE flag.submission_id IN (SELECT submission_id FROM app_store_cleanup_submission_ids)
           OR flag.user_id IN (SELECT id FROM app_store_cleanup_users)
           OR flag.reviewed_by IN (SELECT id FROM app_store_cleanup_users)
    ) THEN
        RAISE EXCEPTION 'Report or moderation state touches the fixture; cleanup aborted';
    END IF;

    IF EXISTS (
        SELECT 1 FROM challengesphere.auth_identities row
        WHERE row.user_id IN (SELECT id FROM app_store_cleanup_users)
    ) OR EXISTS (
        SELECT 1 FROM challengesphere.auth_tokens row
        WHERE row.user_id IN (SELECT id FROM app_store_cleanup_users)
    ) OR EXISTS (
        SELECT 1 FROM challengesphere.push_device_tokens row
        WHERE row.user_id IN (SELECT id FROM app_store_cleanup_users)
    ) OR EXISTS (
        SELECT 1 FROM challengesphere.user_subscriptions row
        WHERE row.user_id IN (SELECT id FROM app_store_cleanup_users)
    ) OR EXISTS (
        SELECT 1 FROM challengesphere.account_deletion_requests row
        WHERE row.user_id IN (SELECT id FROM app_store_cleanup_users)
    ) OR EXISTS (
        SELECT 1 FROM challengesphere.user_blocks row
        WHERE row.blocker_user_id IN (SELECT id FROM app_store_cleanup_users)
           OR row.blocked_user_id IN (SELECT id FROM app_store_cleanup_users)
    ) OR EXISTS (
        SELECT 1 FROM challengesphere.user_reports row
        WHERE row.reporter_user_id IN (SELECT id FROM app_store_cleanup_users)
           OR row.reported_user_id IN (SELECT id FROM app_store_cleanup_users)
    ) THEN
        RAISE EXCEPTION 'Login, subscription, deletion, block, or report state touches the fixture; cleanup aborted';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.notifications notification
        WHERE notification.user_id IN (SELECT id FROM app_store_cleanup_users)
           OR notification.challenge_id IN (
               SELECT challenge_id FROM app_store_cleanup_challenge_ids
           )
    ) OR EXISTS (
        SELECT 1
        FROM challengesphere.feature_requests request
        WHERE request.user_id IN (SELECT id FROM app_store_cleanup_users)
    ) OR EXISTS (
        SELECT 1
        FROM challengesphere.user_challenge_milestones milestone
        WHERE milestone.user_id IN (SELECT id FROM app_store_cleanup_users)
           OR milestone.challenge_id IN (
               SELECT challenge_id FROM app_store_cleanup_challenge_ids
           )
    ) THEN
        RAISE EXCEPTION 'Notification, feature request, or milestone state touches the fixture; cleanup aborted';
    END IF;
END
$$;

CREATE TEMP TABLE app_store_cleanup_media_objects (
    object_key TEXT PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO app_store_cleanup_media_objects (object_key)
SELECT profile_object_key FROM app_store_cleanup_users
UNION
SELECT cover_object_key FROM app_store_cleanup_challenges
UNION
SELECT BTRIM(media_key)
FROM challengesphere.submissions submission
JOIN app_store_cleanup_submission_ids fixture
  ON fixture.submission_id = submission.id
CROSS JOIN LATERAL unnest(string_to_array(submission.media_urls, ','))
    AS expanded(media_key);

-- Target challenge deletion cascades only through rows already proven to be the
-- closed fixture island. Internal follows are then removed by exact fixed IDs.
DELETE FROM challengesphere.challenges challenge
WHERE challenge.id IN (
    SELECT challenge_id FROM app_store_cleanup_challenge_ids
);

DELETE FROM challengesphere.follows follow
WHERE follow.follower_id IN (SELECT id FROM app_store_cleanup_users)
  AND follow.following_id IN (SELECT id FROM app_store_cleanup_users);

DELETE FROM challengesphere.users existing
USING app_store_cleanup_users fixture
WHERE existing.id = fixture.id
  AND existing.username = fixture.username
  AND existing.email_id = fixture.email_id
  AND existing.bio = fixture.bio
  AND existing.profile_pic_url = fixture.profile_object_key;

SELECT COUNT(*) AS deleted_fixture_users
FROM app_store_cleanup_users fixture
WHERE NOT EXISTS (
    SELECT 1
    FROM challengesphere.users existing
    WHERE existing.id = fixture.id
);

-- These are the only S3 keys eligible for a separate, explicit object cleanup.
-- This SQL does not call AWS or remove media.
SELECT object_key
FROM app_store_cleanup_media_objects
ORDER BY object_key;

COMMIT;
