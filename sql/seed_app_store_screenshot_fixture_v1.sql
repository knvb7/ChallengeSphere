-- App Store screenshot fixture v1.
--
-- This is deliberately separate from Flyway. It is an operator-run, additive,
-- idempotent fixture for production-like screenshot/reviewer content.
--
-- Prerequisites:
--   1. Generate, human-review, and upload every object listed at the end of this
--      file (and in docs/APP_STORE_SCREENSHOT_FIXTURE_V1.md).
--   2. Run with psql and an explicit media-readiness acknowledgement:
--
--      psql -v ON_ERROR_STOP=1 \
--        -v app_store_fixture_media_ready=YES \
--        -f sql/seed_app_store_screenshot_fixture_v1.sql
--
-- The five accounts have fixed, reserved IDs and .invalid email addresses. They
-- have no password, provider identity, auth token, or device token and cannot be
-- used as reviewer/demo login credentials.

\set ON_ERROR_STOP on

\if :{?app_store_fixture_media_ready}
\else
\echo 'Refusing to seed: pass -v app_store_fixture_media_ready=YES only after all fixture media is reviewed and uploaded.'
\quit 3
\endif

BEGIN;

SELECT set_config(
    'challengesphere.app_store_fixture_media_ready',
    :'app_store_fixture_media_ready',
    TRUE
);

DO $$
BEGIN
    IF current_setting(
        'challengesphere.app_store_fixture_media_ready',
        TRUE
    ) IS DISTINCT FROM 'YES' THEN
        RAISE EXCEPTION
            'Refusing to seed App Store fixture: app_store_fixture_media_ready must be exactly YES';
    END IF;

    IF to_regclass('challengesphere.auth_identities') IS NULL
       OR to_regclass('challengesphere.user_subscriptions') IS NULL
       OR NOT EXISTS (
           SELECT 1
           FROM information_schema.columns
           WHERE table_schema = 'challengesphere'
             AND table_name = 'submissions'
             AND column_name = 'moderation_status'
       ) THEN
        RAISE EXCEPTION
            'App Store fixture requires the ChallengeSphere schema through the iOS release migrations';
    END IF;
END
$$;

-- Serialize seed/cleanup operations for this fixture without locking unrelated
-- ChallengeSphere rows.
SELECT pg_advisory_xact_lock(
    hashtext('challengesphere:app-store-screenshot-fixture:v1')::BIGINT
);

CREATE TEMP TABLE app_store_fixture_users (
    id BIGINT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email_id VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    bio TEXT NOT NULL,
    date_of_birth DATE NOT NULL,
    age INTEGER NOT NULL,
    gender VARCHAR(32) NOT NULL,
    country VARCHAR(100) NOT NULL,
    interests TEXT NOT NULL,
    profile_object_key TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
) ON COMMIT DROP;

INSERT INTO app_store_fixture_users (
    id,
    username,
    email_id,
    display_name,
    bio,
    date_of_birth,
    age,
    gender,
    country,
    interests,
    profile_object_key,
    created_at
)
VALUES
    (
        910000101,
        'maya_moves',
        'maya_moves@appstore-fixture.invalid',
        'Maya Rao',
        'Morning movement, tiny wins, and good light.',
        DATE '1995-04-12',
        31,
        'WOMAN',
        'India',
        'fitness,wellness,outdoors',
        'profiles/user_910000101/1787000000101_a0010101-0000-4000-8000-000000000101.png',
        TIMESTAMPTZ '2026-08-16 02:00:00+00'
    ),
    (
        910000102,
        'nora_makes',
        'nora_makes@appstore-fixture.invalid',
        'Nora Ellis',
        'Paper, paint, and one small creative win a day.',
        DATE '1992-09-03',
        33,
        'WOMAN',
        'United Kingdom',
        'creativity,art,learning',
        'profiles/user_910000102/1787000000102_a0010102-0000-4000-8000-000000000102.png',
        TIMESTAMPTZ '2026-08-16 02:01:00+00'
    ),
    (
        910000103,
        'leo_outside',
        'leo_outside@appstore-fixture.invalid',
        'Leo Martins',
        'Trails before traffic and coffee after climbs.',
        DATE '1990-01-26',
        36,
        'MAN',
        'Brazil',
        'outdoors,fitness,travel',
        'profiles/user_910000103/1787000000103_a0010103-0000-4000-8000-000000000103.png',
        TIMESTAMPTZ '2026-08-16 02:02:00+00'
    ),
    (
        910000104,
        'sami_reads',
        'sami_reads@appstore-fixture.invalid',
        'Sami Park',
        'Books, marginalia, and ideas worth keeping.',
        DATE '1997-11-18',
        28,
        'NON_BINARY',
        'Canada',
        'reading,learning,writing',
        'profiles/user_910000104/1787000000104_a0010104-0000-4000-8000-000000000104.png',
        TIMESTAMPTZ '2026-08-16 02:03:00+00'
    ),
    (
        910000105,
        'devon_daily',
        'devon_daily@appstore-fixture.invalid',
        'Devon Brooks',
        'Simple systems, steady practice, less noise.',
        DATE '1993-06-07',
        33,
        'MAN',
        'United States',
        'productivity,wellness,fitness',
        'profiles/user_910000105/1787000000105_a0010105-0000-4000-8000-000000000105.png',
        TIMESTAMPTZ '2026-08-16 02:04:00+00'
    );

-- A reserved ID, username, or case-insensitive .invalid email may only refer to
-- the exact fixture identity. Existing exact rows must still be non-login rows.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM challengesphere.users existing
        JOIN app_store_fixture_users fixture
          ON existing.id = fixture.id
          OR LOWER(existing.username) = LOWER(fixture.username)
          OR LOWER(existing.email_id) = LOWER(fixture.email_id)
          OR existing.profile_pic_url = fixture.profile_object_key
        WHERE existing.id IS DISTINCT FROM fixture.id
           OR existing.username IS DISTINCT FROM fixture.username
           OR existing.email_id IS DISTINCT FROM fixture.email_id
           OR existing.name IS DISTINCT FROM fixture.display_name
           OR existing.bio IS DISTINCT FROM fixture.bio
           OR existing.date_of_birth IS DISTINCT FROM fixture.date_of_birth
           OR existing.age IS DISTINCT FROM fixture.age
           OR existing.gender IS DISTINCT FROM fixture.gender
           OR existing.country IS DISTINCT FROM fixture.country
           OR existing.interests IS DISTINCT FROM fixture.interests
           OR existing.profile_pic_url IS DISTINCT FROM fixture.profile_object_key
           OR existing.created_at IS DISTINCT FROM fixture.created_at
           OR existing.active IS DISTINCT FROM TRUE
           OR existing.genuineness_score IS DISTINCT FROM 100
           OR existing.premium_user IS DISTINCT FROM 0
           OR existing.notifications IS DISTINCT FROM 1
           OR existing.role IS DISTINCT FROM 'USER'
           OR existing.phone IS NOT NULL
           OR existing.hashed_password IS NOT NULL
           OR existing.google_id IS NOT NULL
           OR existing.last_login_at IS NOT NULL
           OR existing.banned_at IS NOT NULL
           OR existing.ban_reason IS NOT NULL
           OR existing.deleted_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION
            'Reserved App Store fixture user collision or ownership mismatch; no rows were changed';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.auth_identities identity
        JOIN app_store_fixture_users fixture ON fixture.id = identity.user_id
    ) OR EXISTS (
        SELECT 1
        FROM challengesphere.auth_tokens token
        JOIN app_store_fixture_users fixture ON fixture.id = token.user_id
    ) OR EXISTS (
        SELECT 1
        FROM challengesphere.push_device_tokens token
        JOIN app_store_fixture_users fixture ON fixture.id = token.user_id
    ) OR EXISTS (
        SELECT 1
        FROM challengesphere.user_subscriptions subscription
        JOIN app_store_fixture_users fixture ON fixture.id = subscription.user_id
    ) OR EXISTS (
        SELECT 1
        FROM challengesphere.account_deletion_requests request
        JOIN app_store_fixture_users fixture ON fixture.id = request.user_id
    ) THEN
        RAISE EXCEPTION
            'Reserved App Store fixture accounts contain login, device, subscription, or deletion state';
    END IF;
END
$$;

INSERT INTO challengesphere.users (
    id,
    email_id,
    phone,
    username,
    name,
    hashed_password,
    google_id,
    created_at,
    genuineness_score,
    active,
    challenges_created,
    challenges_accepted,
    followers,
    following,
    profile_pic_url,
    premium_user,
    role,
    notifications,
    bio,
    date_of_birth,
    age,
    gender,
    country,
    interests,
    last_login_at,
    banned_at,
    ban_reason,
    deleted_at
)
SELECT
    fixture.id,
    fixture.email_id,
    NULL,
    fixture.username,
    fixture.display_name,
    NULL,
    NULL,
    fixture.created_at,
    100,
    TRUE,
    0,
    0,
    0,
    0,
    fixture.profile_object_key,
    0,
    'USER',
    1,
    fixture.bio,
    fixture.date_of_birth,
    fixture.age,
    fixture.gender,
    fixture.country,
    fixture.interests,
    NULL,
    NULL,
    NULL,
    NULL
FROM app_store_fixture_users fixture
WHERE NOT EXISTS (
    SELECT 1
    FROM challengesphere.users existing
    WHERE existing.id = fixture.id
)
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    IF (
        SELECT COUNT(*)
        FROM challengesphere.users existing
        JOIN app_store_fixture_users fixture
          ON existing.id = fixture.id
         AND existing.username = fixture.username
         AND existing.email_id = fixture.email_id
    ) <> 5 THEN
        RAISE EXCEPTION
            'App Store fixture user insert did not resolve to exactly five owned identities';
    END IF;
END
$$;

CREATE TEMP TABLE app_store_fixture_challenges (
    fixture_key TEXT PRIMARY KEY,
    creator_username VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL UNIQUE,
    hashtags TEXT NOT NULL,
    categories TEXT NOT NULL,
    description TEXT NOT NULL,
    benefits TEXT NOT NULL,
    is_private BOOLEAN NOT NULL,
    start_date DATE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    number_of_days INTEGER NOT NULL,
    challenge_mode VARCHAR(20) NOT NULL,
    cover_object_key TEXT NOT NULL UNIQUE
) ON COMMIT DROP;

INSERT INTO app_store_fixture_challenges (
    fixture_key,
    creator_username,
    title,
    hashtags,
    categories,
    description,
    benefits,
    is_private,
    start_date,
    created_at,
    number_of_days,
    challenge_mode,
    cover_object_key
)
VALUES
    (
        'morning-light',
        'maya_moves',
        'Seven Days of Morning Light',
        '#morning,#movement,#wellness,#appstore_fixture_v1',
        'Fitness,Wellness',
        'Step outside and move for at least ten minutes each morning. Share one photo that captures the light, the route, or the small win.',
        'Builds an easy movement rhythm and starts the day with fresh air and intention.',
        FALSE,
        DATE '2026-08-18',
        TIMESTAMPTZ '2026-08-17 02:00:00+00',
        7,
        'DAILY',
        'challenges/user_910000101/covers/1787000001001_c1010001-0000-4000-8000-000000000001.png'
    ),
    (
        'creative-wins',
        'nora_makes',
        'Tiny Creative Wins',
        '#creative,#make,#dailypractice,#appstore_fixture_v1',
        'Creativity,Learning',
        'Make one small thing each day: a sketch, a collage, a color study, a line of type, or a playful experiment. Finished is optional; showing up is the win.',
        'Lowers the pressure to be perfect and turns creativity into a repeatable practice.',
        FALSE,
        DATE '2026-08-19',
        TIMESTAMPTZ '2026-08-18 12:00:00+00',
        7,
        'DAILY',
        'challenges/user_910000102/covers/1787000001002_c1020001-0000-4000-8000-000000000002.png'
    ),
    (
        'one-chapter',
        'sami_reads',
        'One Chapter, One Thought',
        '#reading,#books,#reflection,#appstore_fixture_v1',
        'Learning,Lifestyle',
        'Read one chapter today and share a single thought you want to carry into tomorrow.',
        'Creates a focused reading moment and makes the best ideas easier to remember.',
        FALSE,
        DATE '2026-08-21',
        TIMESTAMPTZ '2026-08-20 13:00:00+00',
        1,
        'ONE_OFF',
        'challenges/user_910000104/covers/1787000001003_c1040001-0000-4000-8000-000000000003.png'
    ),
    (
        'trail-crew',
        'leo_outside',
        'Weekend Trail Crew',
        '#trails,#outdoors,#crew,#appstore_fixture_v1',
        'Outdoors,Fitness',
        'A private five-day crew for friends who want to get outside, share the view, and keep one another moving.',
        'Adds accountability, conversation, and a little friendly momentum to time outdoors.',
        TRUE,
        DATE '2026-08-19',
        TIMESTAMPTZ '2026-08-18 03:00:00+00',
        5,
        'DAILY',
        'challenges/user_910000103/covers/1787000001004_c1030001-0000-4000-8000-000000000004.png'
    );

-- Reserve both the fixture tag and its four human-facing titles. Also refuse
-- any challenge footprint owned by these accounts outside this exact fixture.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM app_store_fixture_challenges fixture
        JOIN app_store_fixture_users owner
          ON owner.username = fixture.creator_username
        WHERE NOT challengesphere.valid_challenge_cover_media_references(
            fixture.cover_object_key,
            owner.id,
            owner.id
        )
    ) THEN
        RAISE EXCEPTION
            'App Store fixture challenge cover ownership definition is invalid';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.challenges existing
        JOIN app_store_fixture_challenges fixture
          ON existing.title = fixture.title
        JOIN app_store_fixture_users owner
          ON owner.username = fixture.creator_username
        WHERE existing.creator_id IS DISTINCT FROM owner.id
           OR existing.current_owner_id IS DISTINCT FROM owner.id
           OR NOT (
               '#appstore_fixture_v1' = ANY(
                   string_to_array(REPLACE(COALESCE(existing.hashtags, ''), ' ', ''), ',')
               )
           )
    ) OR EXISTS (
        SELECT 1
        FROM challengesphere.challenges existing
        JOIN app_store_fixture_challenges fixture
          ON fixture.cover_object_key = existing.cover_photo_urls
        JOIN app_store_fixture_users owner
          ON owner.username = fixture.creator_username
        WHERE existing.title IS DISTINCT FROM fixture.title
           OR existing.creator_id IS DISTINCT FROM owner.id
           OR existing.current_owner_id IS DISTINCT FROM owner.id
    ) OR EXISTS (
        SELECT 1
        FROM challengesphere.challenges existing
        WHERE '#appstore_fixture_v1' = ANY(
                  string_to_array(REPLACE(COALESCE(existing.hashtags, ''), ' ', ''), ',')
              )
          AND NOT EXISTS (
              SELECT 1
              FROM app_store_fixture_challenges fixture
              JOIN app_store_fixture_users owner
                ON owner.username = fixture.creator_username
              WHERE fixture.title = existing.title
                AND owner.id = existing.creator_id
                AND owner.id = existing.current_owner_id
          )
    ) OR EXISTS (
        SELECT 1
        FROM challengesphere.challenges existing
        WHERE (
                existing.creator_id IN (SELECT id FROM app_store_fixture_users)
             OR existing.current_owner_id IN (SELECT id FROM app_store_fixture_users)
        )
          AND NOT EXISTS (
              SELECT 1
              FROM app_store_fixture_challenges fixture
              JOIN app_store_fixture_users owner
                ON owner.username = fixture.creator_username
              WHERE fixture.title = existing.title
                AND owner.id = existing.creator_id
                AND owner.id = existing.current_owner_id
          )
    ) THEN
        RAISE EXCEPTION
            'App Store fixture challenge title, tag, or synthetic-account ownership collision';
    END IF;

    IF EXISTS (
        SELECT fixture.fixture_key
        FROM app_store_fixture_challenges fixture
        JOIN app_store_fixture_users owner
          ON owner.username = fixture.creator_username
        JOIN challengesphere.challenges existing
          ON existing.title = fixture.title
         AND existing.creator_id = owner.id
         AND existing.current_owner_id = owner.id
        GROUP BY fixture.fixture_key
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'Duplicate App Store fixture challenge rows found; refusing to guess which row is owned';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM app_store_fixture_challenges fixture
        JOIN app_store_fixture_users owner
          ON owner.username = fixture.creator_username
        JOIN challengesphere.challenges existing
          ON existing.title = fixture.title
         AND existing.creator_id = owner.id
         AND existing.current_owner_id = owner.id
        WHERE existing.hashtags IS DISTINCT FROM fixture.hashtags
           OR existing.categories IS DISTINCT FROM fixture.categories
           OR existing.description IS DISTINCT FROM fixture.description
           OR existing.benefits IS DISTINCT FROM fixture.benefits
           OR existing.private IS DISTINCT FROM fixture.is_private
           OR existing.start_date IS DISTINCT FROM fixture.start_date
           OR existing.active IS DISTINCT FROM TRUE
           OR existing.created_at IS DISTINCT FROM fixture.created_at
           OR existing.number_of_days IS DISTINCT FROM fixture.number_of_days
           OR existing.challenge_mode IS DISTINCT FROM fixture.challenge_mode
           OR existing.proof_mode IS DISTINCT FROM 'PHOTO_REQUIRED'
           OR existing.cover_photo_urls IS DISTINCT FROM fixture.cover_object_key
           OR existing.official_template_key IS NOT NULL
           OR existing.cohort_key IS NOT NULL
    ) THEN
        RAISE EXCEPTION
            'Existing App Store fixture challenge differs from its immutable v1 definition';
    END IF;
END
$$;

INSERT INTO challengesphere.challenges (
    hashtags,
    categories,
    title,
    description,
    benefits,
    private,
    start_date,
    active,
    created_at,
    creator_id,
    current_owner_id,
    number_of_days,
    challenge_mode,
    proof_mode,
    cover_photo_urls,
    official_template_key,
    cohort_key
)
SELECT
    fixture.hashtags,
    fixture.categories,
    fixture.title,
    fixture.description,
    fixture.benefits,
    fixture.is_private,
    fixture.start_date,
    TRUE,
    fixture.created_at,
    owner.id,
    owner.id,
    fixture.number_of_days,
    fixture.challenge_mode,
    'PHOTO_REQUIRED',
    fixture.cover_object_key,
    NULL,
    NULL
FROM app_store_fixture_challenges fixture
JOIN app_store_fixture_users owner
  ON owner.username = fixture.creator_username
WHERE NOT EXISTS (
    SELECT 1
    FROM challengesphere.challenges existing
    WHERE existing.title = fixture.title
      AND existing.creator_id = owner.id
      AND existing.current_owner_id = owner.id
);

CREATE TEMP TABLE app_store_fixture_challenge_ids ON COMMIT DROP AS
SELECT
    fixture.fixture_key,
    existing.id AS challenge_id,
    owner.id AS owner_user_id,
    fixture.is_private,
    fixture.created_at
FROM app_store_fixture_challenges fixture
JOIN app_store_fixture_users owner
  ON owner.username = fixture.creator_username
JOIN challengesphere.challenges existing
  ON existing.title = fixture.title
 AND existing.creator_id = owner.id
 AND existing.current_owner_id = owner.id;

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM app_store_fixture_challenge_ids) <> 4 THEN
        RAISE EXCEPTION
            'App Store fixture challenge insert did not resolve to exactly four owned challenges';
    END IF;
END
$$;

-- Normalize lifecycle only for these exact fixture challenges. Public fixtures
-- begin when created; the private crew has an explicit owner-start event.
UPDATE challengesphere.challenge_crew_lifecycle lifecycle
SET started_at = CASE
        WHEN ids.fixture_key = 'trail-crew'
            THEN TIMESTAMPTZ '2026-08-19 00:30:00+00'
        ELSE ids.created_at
    END,
    started_by_user_id = ids.owner_user_id,
    rematch_of_challenge_id = NULL,
    recap_ready_at = NULL,
    created_at = ids.created_at,
    updated_at = CASE
        WHEN ids.fixture_key = 'trail-crew'
            THEN TIMESTAMPTZ '2026-08-19 00:30:00+00'
        ELSE ids.created_at
    END
FROM app_store_fixture_challenge_ids ids
WHERE lifecycle.challenge_id = ids.challenge_id;

CREATE TEMP TABLE app_store_fixture_participation (
    challenge_key TEXT NOT NULL,
    username VARCHAR(50) NOT NULL,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL,
    favorite BOOLEAN NOT NULL,
    favorited_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (challenge_key, username)
) ON COMMIT DROP;

INSERT INTO app_store_fixture_participation (
    challenge_key,
    username,
    joined_at,
    favorite,
    favorited_at
)
VALUES
    ('morning-light', 'maya_moves',   TIMESTAMPTZ '2026-08-17 02:00:00+00', TRUE,  TIMESTAMPTZ '2026-08-17 02:01:00+00'),
    ('morning-light', 'nora_makes',   TIMESTAMPTZ '2026-08-17 15:00:00+00', FALSE, NULL),
    ('morning-light', 'leo_outside',  TIMESTAMPTZ '2026-08-17 11:00:00+00', FALSE, NULL),
    ('morning-light', 'sami_reads',   TIMESTAMPTZ '2026-08-17 16:00:00+00', FALSE, NULL),
    ('morning-light', 'devon_daily',  TIMESTAMPTZ '2026-08-17 13:00:00+00', TRUE,  TIMESTAMPTZ '2026-08-17 13:01:00+00'),
    ('creative-wins', 'nora_makes',   TIMESTAMPTZ '2026-08-18 12:00:00+00', TRUE,  TIMESTAMPTZ '2026-08-18 12:01:00+00'),
    ('creative-wins', 'sami_reads',   TIMESTAMPTZ '2026-08-18 13:00:00+00', TRUE,  TIMESTAMPTZ '2026-08-18 13:01:00+00'),
    ('creative-wins', 'maya_moves',   TIMESTAMPTZ '2026-08-18 14:00:00+00', FALSE, NULL),
    ('creative-wins', 'devon_daily',  TIMESTAMPTZ '2026-08-18 15:00:00+00', FALSE, NULL),
    ('one-chapter',   'sami_reads',   TIMESTAMPTZ '2026-08-20 13:00:00+00', TRUE,  TIMESTAMPTZ '2026-08-20 13:01:00+00'),
    ('one-chapter',   'maya_moves',   TIMESTAMPTZ '2026-08-20 14:00:00+00', TRUE,  TIMESTAMPTZ '2026-08-20 14:01:00+00'),
    ('one-chapter',   'devon_daily',  TIMESTAMPTZ '2026-08-20 15:00:00+00', FALSE, NULL),
    ('trail-crew',    'leo_outside',  TIMESTAMPTZ '2026-08-18 03:00:00+00', TRUE,  TIMESTAMPTZ '2026-08-18 03:01:00+00'),
    ('trail-crew',    'maya_moves',   TIMESTAMPTZ '2026-08-18 03:15:00+00', FALSE, NULL),
    ('trail-crew',    'nora_makes',   TIMESTAMPTZ '2026-08-18 03:20:00+00', FALSE, NULL),
    ('trail-crew',    'devon_daily',  TIMESTAMPTZ '2026-08-18 03:25:00+00', FALSE, NULL);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM challengesphere.user_challenges existing
        JOIN app_store_fixture_users fixture_user
          ON fixture_user.id = existing.user_id
        LEFT JOIN app_store_fixture_challenge_ids ids
          ON ids.challenge_id = existing.challenge_id
        WHERE ids.challenge_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'A reserved fixture account participates in a non-fixture challenge';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.user_challenges existing
        JOIN app_store_fixture_users fixture_user
          ON fixture_user.id = existing.user_id
        JOIN app_store_fixture_challenge_ids ids
          ON ids.challenge_id = existing.challenge_id
        LEFT JOIN app_store_fixture_participation planned
          ON planned.challenge_key = ids.fixture_key
         AND planned.username = fixture_user.username
        WHERE planned.username IS NULL
           OR existing.joined_at IS DISTINCT FROM planned.joined_at
           OR existing.completed IS DISTINCT FROM FALSE
           OR existing.completed_at IS NOT NULL
           OR existing.active IS DISTINCT FROM TRUE
           OR existing.favorite IS DISTINCT FROM planned.favorite
           OR existing.favorited_at IS DISTINCT FROM planned.favorited_at
           OR existing.banned IS DISTINCT FROM FALSE
           OR existing.removal_reason IS NOT NULL
           OR existing.removed_at IS NOT NULL
           OR existing.removed_by_user_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION
            'Existing fixture participation differs from the immutable v1 plan';
    END IF;
END
$$;

INSERT INTO challengesphere.user_challenges (
    user_id,
    challenge_id,
    joined_at,
    completed,
    completed_at,
    active,
    favorite,
    favorited_at,
    banned,
    removal_reason,
    removed_at,
    removed_by_user_id
)
SELECT
    fixture_user.id,
    ids.challenge_id,
    planned.joined_at,
    FALSE,
    NULL,
    TRUE,
    planned.favorite,
    planned.favorited_at,
    FALSE,
    NULL,
    NULL,
    NULL
FROM app_store_fixture_participation planned
JOIN app_store_fixture_users fixture_user
  ON fixture_user.username = planned.username
JOIN app_store_fixture_challenge_ids ids
  ON ids.fixture_key = planned.challenge_key
ON CONFLICT (user_id, challenge_id) DO NOTHING;

DO $$
BEGIN
    IF (
        SELECT COUNT(*)
        FROM challengesphere.user_challenges existing
        JOIN app_store_fixture_users fixture_user
          ON fixture_user.id = existing.user_id
        JOIN app_store_fixture_challenge_ids ids
          ON ids.challenge_id = existing.challenge_id
        JOIN app_store_fixture_participation planned
          ON planned.challenge_key = ids.fixture_key
         AND planned.username = fixture_user.username
    ) <> 16 THEN
        RAISE EXCEPTION
            'App Store fixture participation did not resolve to exactly sixteen rows';
    END IF;
END
$$;

CREATE TEMP TABLE app_store_fixture_submissions (
    submission_key TEXT PRIMARY KEY,
    challenge_key TEXT NOT NULL,
    username VARCHAR(50) NOT NULL,
    day_number INTEGER NOT NULL,
    description TEXT NOT NULL,
    media_urls TEXT NOT NULL UNIQUE,
    is_private BOOLEAN NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL
) ON COMMIT DROP;

INSERT INTO app_store_fixture_submissions (
    submission_key,
    challenge_key,
    username,
    day_number,
    description,
    media_urls,
    is_private,
    submitted_at
)
VALUES
    ('morning-maya-1', 'morning-light', 'maya_moves', 1,
     'Day 1: Took the quiet route before the city woke up. Ten minutes became twenty without trying.',
     'submissions/user_910000101/SUBMISSION_PHOTO/1787000010001_d1010001-0000-4000-8000-000000000001.png',
     FALSE, TIMESTAMPTZ '2026-08-18 01:35:00+00'),
    ('morning-maya-2', 'morning-light', 'maya_moves', 2,
     'Day 2: Soft light, quick walk, clearer head. Keeping the promise small is making it easy to keep.',
     'submissions/user_910000101/SUBMISSION_PHOTO/1787000010002_d1010002-0000-4000-8000-000000000002.png',
     FALSE, TIMESTAMPTZ '2026-08-19 01:40:00+00'),
    ('morning-maya-3', 'morning-light', 'maya_moves', 3,
     'Day 3: The sky did most of the motivating today. Three mornings and the rhythm is starting to feel real.',
     'submissions/user_910000101/SUBMISSION_PHOTO/1787000010003_d1010003-0000-4000-8000-000000000003.png',
     FALSE, TIMESTAMPTZ '2026-08-20 01:32:00+00'),
    ('morning-maya-4', 'morning-light', 'maya_moves', 4,
     'Day 4: Sunrise on the way out, long shadows on the way home. Saving both parts of today.',
     'submissions/user_910000101/SUBMISSION_PHOTO/1787000010004_d1010004-0000-4000-8000-000000000004.png,submissions/user_910000101/SUBMISSION_PHOTO/1787000010018_d1010005-0000-4000-8000-000000000018.png',
     FALSE, TIMESTAMPTZ '2026-08-21 01:28:00+00'),
    ('morning-devon-1', 'morning-light', 'devon_daily', 1,
     'Day 1: Shoes by the door worked. One lap around the park before opening the laptop.',
     'submissions/user_910000105/SUBMISSION_PHOTO/1787000010005_d1050001-0000-4000-8000-000000000005.png',
     FALSE, TIMESTAMPTZ '2026-08-18 02:10:00+00'),
    ('morning-devon-2', 'morning-light', 'devon_daily', 2,
     'Day 2: No perfect weather required. A brisk block, a little rain, and the streak continues.',
     'submissions/user_910000105/SUBMISSION_PHOTO/1787000010006_d1050002-0000-4000-8000-000000000006.png',
     FALSE, TIMESTAMPTZ '2026-08-19 02:18:00+00'),
    ('morning-leo-1', 'morning-light', 'leo_outside', 1,
     'Day 1: First light above the ridge. This is exactly why the early alarm is worth it.',
     'submissions/user_910000103/SUBMISSION_PHOTO/1787000010007_d1030001-0000-4000-8000-000000000007.png',
     FALSE, TIMESTAMPTZ '2026-08-18 00:52:00+00'),
    ('creative-nora-1', 'creative-wins', 'nora_makes', 1,
     'Day 1: A five-minute color study turned into a page I actually like. Stopping while it still feels playful.',
     'submissions/user_910000102/SUBMISSION_PHOTO/1787000010008_d1020001-0000-4000-8000-000000000008.png',
     FALSE, TIMESTAMPTZ '2026-08-19 14:05:00+00'),
    ('creative-nora-2', 'creative-wins', 'nora_makes', 2,
     'Day 2: Cut paper, crooked edges, good mood. The imperfect pieces made the composition better.',
     'submissions/user_910000102/SUBMISSION_PHOTO/1787000010009_d1020002-0000-4000-8000-000000000009.png',
     FALSE, TIMESTAMPTZ '2026-08-20 13:42:00+00'),
    ('creative-nora-3', 'creative-wins', 'nora_makes', 3,
     'Day 3: One tiny poster before breakfast. Today the type finally found its place.',
     'submissions/user_910000102/SUBMISSION_PHOTO/1787000010010_d1020003-0000-4000-8000-000000000010.png',
     FALSE, TIMESTAMPTZ '2026-08-21 01:12:00+00'),
    ('creative-devon-1', 'creative-wins', 'devon_daily', 1,
     'Day 1: Gave a tiny app idea thirty focused minutes and ended with a working screen. Prototype beats someday.',
     'submissions/user_910000105/SUBMISSION_PHOTO/1787000010011_d1050004-0000-4000-8000-000000000011.png',
     FALSE, TIMESTAMPTZ '2026-08-19 15:20:00+00'),
    ('creative-maya-1', 'creative-wins', 'maya_moves', 1,
     'Day 1: Sketched the view from this morning instead of scrolling through it again.',
     'submissions/user_910000101/SUBMISSION_PHOTO/1787000010012_d1010006-0000-4000-8000-000000000012.png',
     FALSE, TIMESTAMPTZ '2026-08-20 14:10:00+00'),
    ('reading-sami-1', 'one-chapter', 'sami_reads', 1,
     'The thought I am keeping: attention grows around whatever we choose to notice repeatedly.',
     'submissions/user_910000104/SUBMISSION_PHOTO/1787000010013_d1040002-0000-4000-8000-000000000013.png',
     FALSE, TIMESTAMPTZ '2026-08-21 00:45:00+00'),
    ('reading-maya-1', 'one-chapter', 'maya_moves', 1,
     'One chapter with breakfast. My note for tomorrow: make the good choice the easy choice.',
     'submissions/user_910000101/SUBMISSION_PHOTO/1787000010014_d1010007-0000-4000-8000-000000000014.png',
     FALSE, TIMESTAMPTZ '2026-08-21 01:02:00+00'),
    ('trail-leo-1', 'trail-crew', 'leo_outside', 1,
     'Crew day 1: Easy climb, cool air, and a view worth sharing with exactly this group.',
     'submissions/user_910000103/SUBMISSION_PHOTO/1787000010015_d1030002-0000-4000-8000-000000000015.png',
     TRUE, TIMESTAMPTZ '2026-08-19 00:58:00+00'),
    ('trail-leo-2', 'trail-crew', 'leo_outside', 2,
     'Crew day 2: Found the quieter fork and took it. Same hill, completely different morning.',
     'submissions/user_910000103/SUBMISSION_PHOTO/1787000010016_d1030003-0000-4000-8000-000000000016.png',
     TRUE, TIMESTAMPTZ '2026-08-20 00:54:00+00'),
    ('trail-devon-1', 'trail-crew', 'devon_daily', 1,
     'Crew day 1: Swapped the treadmill for a trail and immediately understood the assignment.',
     'submissions/user_910000105/SUBMISSION_PHOTO/1787000010017_d1050003-0000-4000-8000-000000000017.png',
     TRUE, TIMESTAMPTZ '2026-08-19 01:25:00+00');

CREATE TEMP TABLE app_store_fixture_media_objects (
    object_key TEXT PRIMARY KEY,
    media_role TEXT NOT NULL,
    fixture_reference TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO app_store_fixture_media_objects (object_key, media_role, fixture_reference)
SELECT profile_object_key, 'profile', username
FROM app_store_fixture_users
UNION ALL
SELECT cover_object_key, 'challenge-cover', fixture_key
FROM app_store_fixture_challenges
UNION ALL
SELECT
    BTRIM(media_key),
    'submission-photo',
    submission.submission_key
FROM app_store_fixture_submissions submission
CROSS JOIN LATERAL unnest(string_to_array(submission.media_urls, ','))
    AS expanded(media_key);

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM app_store_fixture_media_objects) <> 27 THEN
        RAISE EXCEPTION
            'App Store fixture media manifest must contain exactly twenty-seven unique object keys';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM app_store_fixture_submissions planned
        JOIN app_store_fixture_users fixture_user
          ON fixture_user.username = planned.username
        JOIN app_store_fixture_challenge_ids ids
          ON ids.fixture_key = planned.challenge_key
        WHERE NOT challengesphere.valid_submission_media_references(
            planned.media_urls,
            fixture_user.id
        )
           OR planned.is_private IS DISTINCT FROM ids.is_private
    ) THEN
        RAISE EXCEPTION
            'App Store fixture submission media ownership or privacy definition is invalid';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.submissions existing
        JOIN app_store_fixture_users fixture_user
          ON fixture_user.id = existing.user_id
        LEFT JOIN app_store_fixture_challenge_ids ids
          ON ids.challenge_id = existing.challenge_id
        WHERE ids.challenge_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'A reserved fixture account has a submission in a non-fixture challenge';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.submissions existing
        JOIN app_store_fixture_users fixture_user
          ON fixture_user.id = existing.user_id
        JOIN app_store_fixture_challenge_ids ids
          ON ids.challenge_id = existing.challenge_id
        LEFT JOIN app_store_fixture_submissions planned
          ON planned.challenge_key = ids.fixture_key
         AND planned.username = fixture_user.username
         AND planned.media_urls = existing.media_urls
        WHERE planned.submission_key IS NULL
           OR existing.day_number IS DISTINCT FROM planned.day_number
           OR existing.description IS DISTINCT FROM planned.description
           OR existing.is_private IS DISTINCT FROM planned.is_private
           OR existing.submission_time IS DISTINCT FROM planned.submitted_at
           OR existing.created_at IS DISTINCT FROM planned.submitted_at
           OR existing.updated_at IS DISTINCT FROM planned.submitted_at
           OR existing.active IS DISTINCT FROM TRUE
           OR existing.moderation_status IS DISTINCT FROM 'PASSED'
           OR existing.moderation_started_at IS DISTINCT FROM planned.submitted_at + INTERVAL '1 minute'
           OR existing.moderated_at IS DISTINCT FROM planned.submitted_at + INTERVAL '2 minutes'
           OR existing.moderation_error IS NOT NULL
    ) THEN
        RAISE EXCEPTION
            'Existing fixture submission differs from the immutable v1 plan';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.submissions existing
        JOIN app_store_fixture_media_objects media
          ON media.media_role = 'submission-photo'
         AND media.object_key = ANY(
             string_to_array(REPLACE(COALESCE(existing.media_urls, ''), ' ', ''), ',')
         )
        WHERE NOT EXISTS (
            SELECT 1
            FROM app_store_fixture_submissions planned
            JOIN app_store_fixture_users fixture_user
              ON fixture_user.username = planned.username
            JOIN app_store_fixture_challenge_ids ids
              ON ids.fixture_key = planned.challenge_key
            WHERE existing.user_id = fixture_user.id
              AND existing.challenge_id = ids.challenge_id
              AND existing.media_urls = planned.media_urls
        )
    ) THEN
        RAISE EXCEPTION
            'A deterministic fixture submission object key is already referenced by another row';
    END IF;
END
$$;

INSERT INTO challengesphere.submissions (
    user_id,
    challenge_id,
    day_number,
    description,
    media_urls,
    is_private,
    submission_time,
    created_at,
    updated_at,
    active,
    moderation_status,
    moderation_started_at,
    moderated_at,
    moderation_error,
    glories_count,
    comments_count,
    version
)
SELECT
    fixture_user.id,
    ids.challenge_id,
    planned.day_number,
    planned.description,
    planned.media_urls,
    planned.is_private,
    planned.submitted_at,
    planned.submitted_at,
    planned.submitted_at,
    TRUE,
    'PASSED',
    planned.submitted_at + INTERVAL '1 minute',
    planned.submitted_at + INTERVAL '2 minutes',
    NULL,
    0,
    0,
    0
FROM app_store_fixture_submissions planned
JOIN app_store_fixture_users fixture_user
  ON fixture_user.username = planned.username
JOIN app_store_fixture_challenge_ids ids
  ON ids.fixture_key = planned.challenge_key
WHERE NOT EXISTS (
    SELECT 1
    FROM challengesphere.submissions existing
    WHERE existing.user_id = fixture_user.id
      AND existing.challenge_id = ids.challenge_id
      AND existing.media_urls = planned.media_urls
);

CREATE TEMP TABLE app_store_fixture_submission_ids ON COMMIT DROP AS
SELECT
    planned.submission_key,
    existing.id AS submission_id,
    existing.user_id,
    existing.challenge_id,
    planned.submitted_at
FROM app_store_fixture_submissions planned
JOIN app_store_fixture_users fixture_user
  ON fixture_user.username = planned.username
JOIN app_store_fixture_challenge_ids ids
  ON ids.fixture_key = planned.challenge_key
JOIN challengesphere.submissions existing
  ON existing.user_id = fixture_user.id
 AND existing.challenge_id = ids.challenge_id
 AND existing.media_urls = planned.media_urls;

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM app_store_fixture_submission_ids) <> 17 THEN
        RAISE EXCEPTION
            'App Store fixture submissions did not resolve to exactly seventeen owned rows';
    END IF;
END
$$;

CREATE TEMP TABLE app_store_fixture_comments (
    submission_key TEXT NOT NULL,
    commenter_username VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (submission_key, commenter_username, content)
) ON COMMIT DROP;

INSERT INTO app_store_fixture_comments (
    submission_key,
    commenter_username,
    content,
    created_at
)
VALUES
    ('morning-maya-3', 'nora_makes',  'The light in this one is beautiful. Three mornings already!', TIMESTAMPTZ '2026-08-20 02:05:00+00'),
    ('morning-maya-3', 'devon_daily', 'This streak is getting convincing. See you out there tomorrow.', TIMESTAMPTZ '2026-08-20 02:12:00+00'),
    ('morning-maya-4', 'leo_outside', 'Two-photo proof is a strong finish to the morning.', TIMESTAMPTZ '2026-08-21 01:42:00+00'),
    ('morning-devon-2', 'maya_moves', 'Rain absolutely counts. Nice work keeping it simple.', TIMESTAMPTZ '2026-08-19 02:32:00+00'),
    ('morning-leo-1', 'sami_reads',   'That ridge would get me out of bed too.', TIMESTAMPTZ '2026-08-18 01:15:00+00'),
    ('creative-nora-2', 'maya_moves', 'The crooked edges are the best part.', TIMESTAMPTZ '2026-08-20 13:55:00+00'),
    ('creative-nora-2', 'sami_reads', 'This palette feels like a whole little story.', TIMESTAMPTZ '2026-08-20 14:02:00+00'),
    ('creative-nora-3', 'devon_daily','Tiny win, very good poster.', TIMESTAMPTZ '2026-08-21 01:25:00+00'),
    ('creative-nora-3', 'maya_moves', 'The color balance feels so calm. Love this.', TIMESTAMPTZ '2026-08-21 01:27:00+00'),
    ('creative-nora-3', 'sami_reads',  'This would make a lovely morning ritual.', TIMESTAMPTZ '2026-08-21 01:29:00+00'),
    ('creative-devon-1', 'nora_makes', 'Thirty focused minutes and a working screen is a very good win.', TIMESTAMPTZ '2026-08-19 15:34:00+00'),
    ('reading-sami-1', 'maya_moves',  'Saving that thought for my next walk.', TIMESTAMPTZ '2026-08-21 00:58:00+00'),
    ('reading-maya-1', 'sami_reads',  'That is exactly the line I underlined too.', TIMESTAMPTZ '2026-08-21 01:16:00+00'),
    ('trail-leo-2', 'devon_daily',    'Quieter fork tomorrow. I am in.', TIMESTAMPTZ '2026-08-20 01:11:00+00'),
    ('trail-devon-1', 'leo_outside',  'Assignment understood perfectly.', TIMESTAMPTZ '2026-08-19 01:39:00+00');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM challengesphere.comments existing
        JOIN app_store_fixture_users commenter
          ON commenter.id = existing.user_id
        LEFT JOIN app_store_fixture_submission_ids ids
          ON ids.submission_id = existing.submission_id
        WHERE ids.submission_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'A reserved fixture account has a comment outside fixture submissions';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.comments existing
        JOIN app_store_fixture_users commenter
          ON commenter.id = existing.user_id
        JOIN app_store_fixture_submission_ids ids
          ON ids.submission_id = existing.submission_id
        LEFT JOIN app_store_fixture_comments planned
          ON planned.submission_key = ids.submission_key
         AND planned.commenter_username = commenter.username
         AND planned.content = existing.content
        WHERE planned.content IS NULL
           OR existing.created_at IS DISTINCT FROM planned.created_at
           OR existing.updated_at IS DISTINCT FROM planned.created_at
           OR existing.active IS DISTINCT FROM TRUE
    ) THEN
        RAISE EXCEPTION
            'Existing fixture-authored comment differs from the immutable v1 plan';
    END IF;
END
$$;

INSERT INTO challengesphere.comments (
    submission_id,
    user_id,
    content,
    created_at,
    updated_at,
    active
)
SELECT
    ids.submission_id,
    commenter.id,
    planned.content,
    planned.created_at,
    planned.created_at,
    TRUE
FROM app_store_fixture_comments planned
JOIN app_store_fixture_submission_ids ids
  ON ids.submission_key = planned.submission_key
JOIN app_store_fixture_users commenter
  ON commenter.username = planned.commenter_username
WHERE NOT EXISTS (
    SELECT 1
    FROM challengesphere.comments existing
    WHERE existing.submission_id = ids.submission_id
      AND existing.user_id = commenter.id
      AND existing.content = planned.content
);

CREATE TEMP TABLE app_store_fixture_glories (
    submission_key TEXT NOT NULL,
    glorier_username VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (submission_key, glorier_username)
) ON COMMIT DROP;

INSERT INTO app_store_fixture_glories (
    submission_key,
    glorier_username,
    created_at
)
VALUES
    ('morning-maya-1', 'nora_makes',   TIMESTAMPTZ '2026-08-18 01:50:00+00'),
    ('morning-maya-1', 'devon_daily',  TIMESTAMPTZ '2026-08-18 02:20:00+00'),
    ('morning-maya-2', 'leo_outside',  TIMESTAMPTZ '2026-08-19 01:58:00+00'),
    ('morning-maya-2', 'sami_reads',   TIMESTAMPTZ '2026-08-19 02:04:00+00'),
    ('morning-maya-3', 'nora_makes',   TIMESTAMPTZ '2026-08-20 02:01:00+00'),
    ('morning-maya-3', 'devon_daily',  TIMESTAMPTZ '2026-08-20 02:10:00+00'),
    ('morning-maya-3', 'leo_outside',  TIMESTAMPTZ '2026-08-20 02:18:00+00'),
    ('morning-maya-4', 'nora_makes',   TIMESTAMPTZ '2026-08-21 01:36:00+00'),
    ('morning-maya-4', 'leo_outside',  TIMESTAMPTZ '2026-08-21 01:40:00+00'),
    ('morning-maya-4', 'sami_reads',   TIMESTAMPTZ '2026-08-21 01:44:00+00'),
    ('morning-devon-1', 'maya_moves',  TIMESTAMPTZ '2026-08-18 02:22:00+00'),
    ('morning-devon-2', 'maya_moves',  TIMESTAMPTZ '2026-08-19 02:28:00+00'),
    ('morning-leo-1', 'maya_moves',    TIMESTAMPTZ '2026-08-18 01:12:00+00'),
    ('morning-leo-1', 'devon_daily',   TIMESTAMPTZ '2026-08-18 01:20:00+00'),
    ('creative-nora-1', 'sami_reads',  TIMESTAMPTZ '2026-08-19 14:18:00+00'),
    ('creative-nora-1', 'maya_moves',  TIMESTAMPTZ '2026-08-19 14:25:00+00'),
    ('creative-nora-2', 'maya_moves',  TIMESTAMPTZ '2026-08-20 13:52:00+00'),
    ('creative-nora-2', 'sami_reads',  TIMESTAMPTZ '2026-08-20 13:58:00+00'),
    ('creative-nora-3', 'devon_daily', TIMESTAMPTZ '2026-08-21 01:22:00+00'),
    ('creative-nora-3', 'maya_moves',  TIMESTAMPTZ '2026-08-21 01:24:00+00'),
    ('creative-devon-1', 'nora_makes', TIMESTAMPTZ '2026-08-19 15:30:00+00'),
    ('creative-maya-1', 'nora_makes',  TIMESTAMPTZ '2026-08-20 14:22:00+00'),
    ('reading-sami-1', 'maya_moves',   TIMESTAMPTZ '2026-08-21 00:55:00+00'),
    ('reading-sami-1', 'devon_daily',  TIMESTAMPTZ '2026-08-21 01:05:00+00'),
    ('reading-maya-1', 'sami_reads',   TIMESTAMPTZ '2026-08-21 01:12:00+00'),
    ('trail-leo-1', 'maya_moves',      TIMESTAMPTZ '2026-08-19 01:10:00+00'),
    ('trail-leo-1', 'devon_daily',     TIMESTAMPTZ '2026-08-19 01:30:00+00'),
    ('trail-leo-2', 'devon_daily',     TIMESTAMPTZ '2026-08-20 01:08:00+00'),
    ('trail-devon-1', 'leo_outside',   TIMESTAMPTZ '2026-08-19 01:36:00+00');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM challengesphere.glories existing
        JOIN app_store_fixture_users glorier
          ON glorier.id = existing.user_id
        LEFT JOIN app_store_fixture_submission_ids ids
          ON ids.submission_id = existing.submission_id
        WHERE ids.submission_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'A reserved fixture account has a glory outside fixture submissions';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.glories existing
        JOIN app_store_fixture_users glorier
          ON glorier.id = existing.user_id
        JOIN app_store_fixture_submission_ids ids
          ON ids.submission_id = existing.submission_id
        LEFT JOIN app_store_fixture_glories planned
          ON planned.submission_key = ids.submission_key
         AND planned.glorier_username = glorier.username
        WHERE planned.glorier_username IS NULL
           OR existing.created_at IS DISTINCT FROM planned.created_at
           OR existing.active IS DISTINCT FROM TRUE
           OR existing.ungloried IS DISTINCT FROM FALSE
    ) THEN
        RAISE EXCEPTION
            'Existing fixture-authored glory differs from the immutable v1 plan';
    END IF;
END
$$;

INSERT INTO challengesphere.glories (
    submission_id,
    user_id,
    created_at,
    active,
    ungloried
)
SELECT
    ids.submission_id,
    glorier.id,
    planned.created_at,
    TRUE,
    FALSE
FROM app_store_fixture_glories planned
JOIN app_store_fixture_submission_ids ids
  ON ids.submission_key = planned.submission_key
JOIN app_store_fixture_users glorier
  ON glorier.username = planned.glorier_username
ON CONFLICT (submission_id, user_id) DO NOTHING;

CREATE TEMP TABLE app_store_fixture_follows (
    follower_username VARCHAR(50) NOT NULL,
    following_username VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    PRIMARY KEY (follower_username, following_username),
    CHECK (follower_username <> following_username)
) ON COMMIT DROP;

INSERT INTO app_store_fixture_follows (
    follower_username,
    following_username,
    created_at
)
VALUES
    ('maya_moves',  'nora_makes',  TIMESTAMP '2026-08-16 08:00:00'),
    ('maya_moves',  'leo_outside', TIMESTAMP '2026-08-16 08:01:00'),
    ('maya_moves',  'sami_reads',  TIMESTAMP '2026-08-16 08:02:00'),
    ('nora_makes',  'maya_moves',  TIMESTAMP '2026-08-16 08:03:00'),
    ('nora_makes',  'sami_reads',  TIMESTAMP '2026-08-16 08:04:00'),
    ('leo_outside', 'maya_moves',  TIMESTAMP '2026-08-16 08:05:00'),
    ('leo_outside', 'devon_daily', TIMESTAMP '2026-08-16 08:06:00'),
    ('sami_reads',  'maya_moves',  TIMESTAMP '2026-08-16 08:07:00'),
    ('sami_reads',  'nora_makes',  TIMESTAMP '2026-08-16 08:08:00'),
    ('devon_daily', 'maya_moves',  TIMESTAMP '2026-08-16 08:09:00'),
    ('devon_daily', 'leo_outside', TIMESTAMP '2026-08-16 08:10:00'),
    ('devon_daily', 'nora_makes',  TIMESTAMP '2026-08-16 08:11:00');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM challengesphere.follows existing
        JOIN app_store_fixture_users follower
          ON follower.id = existing.follower_id
        LEFT JOIN app_store_fixture_users followed
          ON followed.id = existing.following_id
        WHERE followed.id IS NULL
    ) THEN
        RAISE EXCEPTION
            'A non-login fixture account follows a non-fixture user';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.follows existing
        JOIN app_store_fixture_users follower
          ON follower.id = existing.follower_id
        JOIN app_store_fixture_users followed
          ON followed.id = existing.following_id
        LEFT JOIN app_store_fixture_follows planned
          ON planned.follower_username = follower.username
         AND planned.following_username = followed.username
        WHERE planned.follower_username IS NULL
           OR existing.created_at IS DISTINCT FROM planned.created_at
           OR existing.active IS DISTINCT FROM TRUE
    ) THEN
        RAISE EXCEPTION
            'Existing fixture follow relationship differs from the immutable v1 plan';
    END IF;
END
$$;

INSERT INTO challengesphere.follows (
    follower_id,
    following_id,
    created_at,
    active
)
SELECT
    follower.id,
    followed.id,
    planned.created_at,
    TRUE
FROM app_store_fixture_follows planned
JOIN app_store_fixture_users follower
  ON follower.username = planned.follower_username
JOIN app_store_fixture_users followed
  ON followed.username = planned.following_username
ON CONFLICT (follower_id, following_id) DO NOTHING;

-- Reconcile denormalized interaction counts for fixture submissions. Real-user
-- comments/glories, if any were added after the first run, remain untouched and
-- are included in the count.
UPDATE challengesphere.submissions submission
SET comments_count = (
        SELECT COUNT(*)
        FROM challengesphere.comments comment
        WHERE comment.submission_id = submission.id
          AND comment.active = TRUE
    ),
    glories_count = (
        SELECT COUNT(*)
        FROM challengesphere.glories glory
        WHERE glory.submission_id = submission.id
          AND glory.active = TRUE
          AND glory.ungloried = FALSE
    )
WHERE submission.id IN (
    SELECT submission_id
    FROM app_store_fixture_submission_ids
);

-- Rebuild durable leaderboard rows only for the synthetic participants. The
-- points formula mirrors LeaderboardServiceImpl: ten per submitted day plus one
-- for each additional media item on the same day. Every planned day is
-- contiguous, so its distinct-day count is also the fixture streak.
WITH fixture_leaderboard AS (
    SELECT
        participation.challenge_id,
        participation.user_id,
        COALESCE(points.score, 0)::DOUBLE PRECISION AS score,
        COALESCE(points.submission_count, 0)::INTEGER AS submission_count,
        COALESCE(points.glory_points, 0)::INTEGER AS glory_points,
        COALESCE(points.submission_count, 0)::INTEGER AS streak_days,
        points.first_submission_at AT TIME ZONE 'UTC' AS first_submission_at,
        points.last_submission_at AT TIME ZONE 'UTC' AS last_submission_at
    FROM challengesphere.user_challenges participation
    JOIN app_store_fixture_users fixture_user
      ON fixture_user.id = participation.user_id
    JOIN app_store_fixture_challenge_ids ids
      ON ids.challenge_id = participation.challenge_id
    LEFT JOIN (
        SELECT
            daily.challenge_id,
            daily.user_id,
            SUM(10 + GREATEST(daily.media_count - 1, 0)) AS score,
            COUNT(*) AS submission_count,
            SUM(daily.glories_count) AS glory_points,
            MIN(daily.first_submission_at) AS first_submission_at,
            MAX(daily.last_submission_at) AS last_submission_at
        FROM (
            SELECT
                submission.challenge_id,
                submission.user_id,
                submission.day_number,
                SUM(
                    CARDINALITY(string_to_array(submission.media_urls, ','))
                ) AS media_count,
                SUM(submission.glories_count) AS glories_count,
                MIN(submission.submission_time) AS first_submission_at,
                MAX(submission.submission_time) AS last_submission_at
            FROM challengesphere.submissions submission
            JOIN app_store_fixture_submission_ids fixture_submission
              ON fixture_submission.submission_id = submission.id
            WHERE submission.active = TRUE
            GROUP BY
                submission.challenge_id,
                submission.user_id,
                submission.day_number
        ) daily
        GROUP BY daily.challenge_id, daily.user_id
    ) points
      ON points.challenge_id = participation.challenge_id
     AND points.user_id = participation.user_id
    WHERE participation.active = TRUE
      AND participation.banned = FALSE
)
INSERT INTO challengesphere.leaderboard_entries (
    challenge_id,
    user_id,
    score,
    current_rank,
    previous_rank,
    submission_count,
    glory_points,
    streak_days,
    best_score,
    last_submission_at,
    first_submission_at,
    status,
    created_at,
    updated_at
)
SELECT
    fixture.challenge_id,
    fixture.user_id,
    fixture.score,
    NULL,
    NULL,
    fixture.submission_count,
    fixture.glory_points,
    fixture.streak_days,
    fixture.score,
    fixture.last_submission_at,
    fixture.first_submission_at,
    'ACTIVE',
    TIMESTAMP '2026-08-21 02:30:00',
    TIMESTAMP '2026-08-21 02:30:00'
FROM fixture_leaderboard fixture
ON CONFLICT (challenge_id, user_id) DO UPDATE
SET score = EXCLUDED.score,
    submission_count = EXCLUDED.submission_count,
    glory_points = EXCLUDED.glory_points,
    streak_days = EXCLUDED.streak_days,
    best_score = EXCLUDED.best_score,
    last_submission_at = EXCLUDED.last_submission_at,
    first_submission_at = EXCLUDED.first_submission_at,
    status = 'ACTIVE',
    updated_at = TIMESTAMP '2026-08-21 02:30:00';

WITH ranked AS (
    SELECT
        entry.id,
        ROW_NUMBER() OVER (
            PARTITION BY entry.challenge_id
            ORDER BY entry.score DESC, entry.user_id ASC
        )::INTEGER AS resolved_rank
    FROM challengesphere.leaderboard_entries entry
    WHERE entry.status = 'ACTIVE'
      AND entry.challenge_id IN (
          SELECT challenge_id
          FROM app_store_fixture_challenge_ids
      )
)
UPDATE challengesphere.leaderboard_entries entry
SET previous_rank = COALESCE(entry.current_rank, ranked.resolved_rank),
    current_rank = ranked.resolved_rank,
    updated_at = TIMESTAMP '2026-08-21 02:30:00'
FROM ranked
WHERE entry.id = ranked.id
  AND entry.user_id IN (
      SELECT id
      FROM app_store_fixture_users
  );

-- Recompute counters from source-of-truth rows, but update only the five fixed
-- synthetic users. Incoming real-user follows are counted without being changed.
UPDATE challengesphere.users fixture_user
SET challenges_created = (
        SELECT COUNT(*)
        FROM challengesphere.challenges challenge
        WHERE challenge.creator_id = fixture_user.id
    ),
    challenges_accepted = (
        SELECT COUNT(*)
        FROM challengesphere.user_challenges participation
        WHERE participation.user_id = fixture_user.id
          AND participation.active = TRUE
          AND participation.banned = FALSE
    ),
    followers = (
        SELECT COUNT(*)
        FROM challengesphere.follows follow
        WHERE follow.following_id = fixture_user.id
          AND follow.active = TRUE
    ),
    following = (
        SELECT COUNT(*)
        FROM challengesphere.follows follow
        WHERE follow.follower_id = fixture_user.id
          AND follow.active = TRUE
    )
WHERE fixture_user.id IN (
    SELECT id
    FROM app_store_fixture_users
);

-- Compact operator summary followed by the exact S3 object manifest. No user,
-- challenge, or submission is chosen by ordering or by an ambient account.
SELECT
    (SELECT COUNT(*) FROM app_store_fixture_users) AS fixture_users,
    (SELECT COUNT(*) FROM app_store_fixture_challenge_ids) AS fixture_challenges,
    (SELECT COUNT(*) FROM app_store_fixture_participation) AS fixture_participations,
    (SELECT COUNT(*) FROM app_store_fixture_submission_ids) AS fixture_submissions,
    (SELECT COUNT(*) FROM app_store_fixture_comments) AS fixture_comments,
    (SELECT COUNT(*) FROM app_store_fixture_glories) AS fixture_glories,
    (SELECT COUNT(*) FROM app_store_fixture_follows) AS fixture_follows,
    (SELECT COUNT(*) FROM app_store_fixture_media_objects) AS fixture_media_objects;

SELECT
    media_role,
    fixture_reference,
    object_key
FROM app_store_fixture_media_objects
ORDER BY media_role, fixture_reference, object_key;

SELECT
    ids.fixture_key,
    ids.challenge_id
FROM app_store_fixture_challenge_ids ids
ORDER BY ids.fixture_key;

COMMIT;
