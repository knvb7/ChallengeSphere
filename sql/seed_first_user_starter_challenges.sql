-- Additive, idempotent starter content for the first active ChallengeSphere user.
-- Provision the matching S3 objects first, then run with an authenticated
-- PostgreSQL connection:
--   scripts/provision-first-user-starter-media.sh <first-active-user-id>
--   psql -v ON_ERROR_STOP=1 -f sql/seed_first_user_starter_challenges.sql

\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE starter_seed_target ON COMMIT DROP AS
SELECT id
FROM challengesphere.users
WHERE active = true
ORDER BY id ASC LIMIT 1;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM starter_seed_target) THEN
        RAISE EXCEPTION 'Cannot seed starter challenges: no active users found';
    END IF;
END $$;

CREATE TEMP TABLE starter_seed_challenges (
    title TEXT PRIMARY KEY,
    hashtags TEXT NOT NULL,
    categories TEXT NOT NULL,
    description TEXT NOT NULL,
    benefits TEXT NOT NULL,
    number_of_days INTEGER NOT NULL,
    cover_photo_file_1 TEXT NOT NULL,
    cover_photo_file_2 TEXT NOT NULL,
    cover_photo_file_3 TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO starter_seed_challenges (
    title,
    hashtags,
    categories,
    description,
    benefits,
    number_of_days,
    cover_photo_file_1,
    cover_photo_file_2,
    cover_photo_file_3
)
VALUES
        (
            'Morning Momentum: 30 Days of Movement',
            '#starter,#fitness,#movement,#consistency',
            'Fitness,Health',
            'Move for at least 20 minutes every day. A walk, workout, run, or stretch session all count - share the small win that kept you moving.',
            'Builds consistency, energy, and a movement habit that fits real life.',
            30,
            '1784369000001_00000000-0000-4000-8000-000000000001.jpg',
            '1784369000002_00000000-0000-4000-8000-000000000002.jpg',
            '1784369000003_00000000-0000-4000-8000-000000000003.jpg'
        ),
        (
            '30 Pages Before Bed',
            '#starter,#reading,#books,#focus',
            'Learning,Lifestyle',
            'Read 30 pages before bed each night for 30 days. Post the page, passage, or idea you want to remember tomorrow.',
            'Creates a calmer evening routine while improving focus and knowledge.',
            30,
            '1784369000004_00000000-0000-4000-8000-000000000004.jpg',
            '1784369000005_00000000-0000-4000-8000-000000000005.jpg',
            '1784369000006_00000000-0000-4000-8000-000000000006.jpg'
        ),
        (
            'Build in Public: 21 Days of Code',
            '#starter,#coding,#buildinpublic,#learning',
            'Tech,Learning',
            'Spend focused time coding every day for 21 days. Share a screenshot of the feature, bug fix, exercise, or lesson that moved your project forward.',
            'Turns steady practice into visible momentum and a useful body of work.',
            21,
            '1784369000007_00000000-0000-4000-8000-000000000007.jpg',
            '1784369000008_00000000-0000-4000-8000-000000000008.jpg',
            '1784369000009_00000000-0000-4000-8000-000000000009.jpg'
        ),
        (
            'Ten Quiet Minutes',
            '#starter,#wellness,#mindfulness,#reset',
            'Wellness,Mindfulness',
            'Take ten quiet minutes each day to breathe, meditate, journal, or simply sit without a screen. Share one thing you noticed afterward.',
            'Makes space for calm, attention, and a more intentional daily reset.',
            21,
            '1784369000010_00000000-0000-4000-8000-000000000010.jpg',
            '1784369000011_00000000-0000-4000-8000-000000000011.jpg',
            '1784369000012_00000000-0000-4000-8000-000000000012.jpg'
        );

WITH inserted_challenges AS (
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
        cover_photo_urls
    )
    SELECT
        starter.hashtags,
        starter.categories,
        starter.title,
        starter.description,
        starter.benefits,
        false,
        CURRENT_DATE - 2,
        true,
        CURRENT_TIMESTAMP,
        target.id,
        target.id,
        starter.number_of_days,
        'DAILY',
        FORMAT(
            'challenges/user_%s/covers/%s,challenges/user_%s/covers/%s,challenges/user_%s/covers/%s',
            target.id,
            starter.cover_photo_file_1,
            target.id,
            starter.cover_photo_file_2,
            target.id,
            starter.cover_photo_file_3
        )
    FROM starter_seed_challenges starter
    CROSS JOIN starter_seed_target target
    WHERE NOT EXISTS (
        SELECT 1
        FROM challengesphere.challenges existing
        WHERE existing.creator_id = target.id
          AND existing.title = starter.title
          AND existing.hashtags LIKE '%#starter%'
    )
    RETURNING creator_id
), created_counts AS (
    SELECT creator_id AS user_id, COUNT(*) AS count
    FROM inserted_challenges
    GROUP BY creator_id
)
UPDATE challengesphere.users user_record
SET challenges_created = COALESCE(user_record.challenges_created, 0) + created_counts.count
FROM created_counts
WHERE user_record.id = created_counts.user_id;

-- Repair pre-existing starter rows created by older versions of this seed that
-- stored external URLs rejected by the backend read policy.
UPDATE challengesphere.challenges challenge
SET cover_photo_urls = FORMAT(
        'challenges/user_%s/covers/%s,challenges/user_%s/covers/%s,challenges/user_%s/covers/%s',
        target.id,
        starter.cover_photo_file_1,
        target.id,
        starter.cover_photo_file_2,
        target.id,
        starter.cover_photo_file_3
    )
FROM starter_seed_target target
JOIN starter_seed_challenges starter ON TRUE
WHERE challenge.creator_id = target.id
  AND challenge.title = starter.title
  AND challenge.hashtags LIKE '%#starter%'
  AND challenge.cover_photo_urls IS DISTINCT FROM FORMAT(
        'challenges/user_%s/covers/%s,challenges/user_%s/covers/%s,challenges/user_%s/covers/%s',
        target.id,
        starter.cover_photo_file_1,
        target.id,
        starter.cover_photo_file_2,
        target.id,
        starter.cover_photo_file_3
    );

WITH starter_titles (title) AS (
    VALUES
        ('Morning Momentum: 30 Days of Movement'),
        ('30 Pages Before Bed'),
        ('Build in Public: 21 Days of Code'),
        ('Ten Quiet Minutes')
), inserted_participation AS (
    INSERT INTO challengesphere.user_challenges (user_id, challenge_id, joined_at, completed, active)
    SELECT target.id, challenge.id, CURRENT_TIMESTAMP - INTERVAL '2 days', false, true
    FROM starter_seed_target target
    JOIN challengesphere.challenges challenge
      ON challenge.creator_id = target.id
    JOIN starter_titles starter
      ON starter.title = challenge.title
    WHERE challenge.hashtags LIKE '%#starter%'
    ON CONFLICT (user_id, challenge_id) DO NOTHING
    RETURNING user_id
), participation_counts AS (
    SELECT user_id, COUNT(*) AS count
    FROM inserted_participation
    GROUP BY user_id
)
UPDATE challengesphere.users user_record
SET challenges_accepted = COALESCE(user_record.challenges_accepted, 0) + participation_counts.count
FROM participation_counts
WHERE user_record.id = participation_counts.user_id;

CREATE TEMP TABLE starter_seed_submissions (
    challenge_title TEXT NOT NULL,
    day_number INTEGER NOT NULL,
    description TEXT NOT NULL,
    media_file_name TEXT NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (challenge_title, day_number)
) ON COMMIT DROP;

INSERT INTO starter_seed_submissions (
    challenge_title,
    day_number,
    description,
    media_file_name,
    submitted_at
)
VALUES
    ('Morning Momentum: 30 Days of Movement', 1, 'Day 1: Started with a brisk morning walk before the day got busy. Keeping it simple and showing up again tomorrow.', '1784369000101_00000000-0000-4000-8000-000000000101.jpg', CURRENT_TIMESTAMP - INTERVAL '2 days'),
    ('Morning Momentum: 30 Days of Movement', 2, 'Day 2: A short strength session at home. The hardest part was beginning, and that is exactly why it counts.', '1784369000102_00000000-0000-4000-8000-000000000102.jpg', CURRENT_TIMESTAMP - INTERVAL '1 day'),
    ('Morning Momentum: 30 Days of Movement', 3, 'Day 3: Took the long route home and finished with a stretch. Small streak, already feeling better.', '1784369000103_00000000-0000-4000-8000-000000000103.jpg', CURRENT_TIMESTAMP),
    ('30 Pages Before Bed', 1, 'Day 1: Thirty pages and no phone beside the bed. The quiet ending to the day was the best part.', '1784369000104_00000000-0000-4000-8000-000000000104.jpg', CURRENT_TIMESTAMP - INTERVAL '2 days'),
    ('30 Pages Before Bed', 2, 'Day 2: Kept going past the page goal because the chapter got good. Writing down one idea before sleep.', '1784369000105_00000000-0000-4000-8000-000000000105.jpg', CURRENT_TIMESTAMP - INTERVAL '1 day'),
    ('30 Pages Before Bed', 3, 'Day 3: New chapter, warm tea, and the same thirty-page promise. This routine is starting to stick.', '1784369000106_00000000-0000-4000-8000-000000000106.jpg', CURRENT_TIMESTAMP),
    ('Build in Public: 21 Days of Code', 1, 'Day 1: Set up the project structure and made the first screen responsive. Tiny commit, real progress.', '1784369000107_00000000-0000-4000-8000-000000000107.jpg', CURRENT_TIMESTAMP - INTERVAL '2 days'),
    ('Build in Public: 21 Days of Code', 2, 'Day 2: Chased down a state bug that looked obvious only after it was fixed. Debugging is still building.', '1784369000108_00000000-0000-4000-8000-000000000108.jpg', CURRENT_TIMESTAMP - INTERVAL '1 day'),
    ('Build in Public: 21 Days of Code', 3, 'Day 3: Added the interaction that makes the screen feel alive. Shipping the small pieces in public.', '1784369000109_00000000-0000-4000-8000-000000000109.jpg', CURRENT_TIMESTAMP),
    ('Ten Quiet Minutes', 1, 'Day 1: Ten minutes of breathing with notifications off. Noticed how noisy the first few minutes were.', '1784369000110_00000000-0000-4000-8000-000000000110.jpg', CURRENT_TIMESTAMP - INTERVAL '2 days'),
    ('Ten Quiet Minutes', 2, 'Day 2: Sat by the window and wrote down three things that felt steady today. A gentle reset.', '1784369000111_00000000-0000-4000-8000-000000000111.jpg', CURRENT_TIMESTAMP - INTERVAL '1 day'),
    ('Ten Quiet Minutes', 3, 'Day 3: A slow meditation before work. Ten minutes was enough to make the next hour feel clearer.', '1784369000112_00000000-0000-4000-8000-000000000112.jpg', CURRENT_TIMESTAMP);

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
    glories_count,
    comments_count,
    version
)
SELECT
    target.id,
    challenge.id,
    submission.day_number,
    submission.description,
    FORMAT(
        'submissions/user_%s/SUBMISSION_PHOTO/%s',
        target.id,
        submission.media_file_name
    ),
    false,
    submission.submitted_at,
    submission.submitted_at,
    submission.submitted_at,
    true,
    0,
    0,
    0
FROM starter_seed_submissions submission
JOIN starter_seed_target target ON true
JOIN challengesphere.challenges challenge
  ON challenge.creator_id = target.id
 AND challenge.title = submission.challenge_title
 AND challenge.hashtags LIKE '%#starter%'
WHERE NOT EXISTS (
    SELECT 1
    FROM challengesphere.submissions existing_submission
    WHERE existing_submission.user_id = target.id
      AND existing_submission.challenge_id = challenge.id
      AND existing_submission.day_number = submission.day_number
);

-- Repair pre-existing starter submissions that used external URLs.
UPDATE challengesphere.submissions existing_submission
SET media_urls = FORMAT(
        'submissions/user_%s/SUBMISSION_PHOTO/%s',
        target.id,
        starter_submission.media_file_name
    ),
    updated_at = CURRENT_TIMESTAMP
FROM starter_seed_target target
JOIN challengesphere.challenges challenge
  ON challenge.creator_id = target.id
 AND challenge.hashtags LIKE '%#starter%'
JOIN starter_seed_submissions starter_submission
  ON starter_submission.challenge_title = challenge.title
WHERE existing_submission.user_id = target.id
  AND existing_submission.challenge_id = challenge.id
  AND existing_submission.day_number = starter_submission.day_number
  AND existing_submission.media_urls IS DISTINCT FROM FORMAT(
        'submissions/user_%s/SUBMISSION_PHOTO/%s',
        target.id,
        starter_submission.media_file_name
    );

SELECT
    target.id AS first_user_id,
    user_record.username AS first_user_username,
    COUNT(DISTINCT challenge.id) FILTER (WHERE challenge.hashtags LIKE '%#starter%') AS starter_challenges,
    COUNT(DISTINCT user_challenge.challenge_id) FILTER (WHERE challenge.hashtags LIKE '%#starter%') AS starter_participation_records,
    COUNT(submission.id) FILTER (WHERE challenge.hashtags LIKE '%#starter%') AS starter_submissions
FROM starter_seed_target target
JOIN challengesphere.users user_record ON user_record.id = target.id
LEFT JOIN challengesphere.challenges challenge ON challenge.creator_id = target.id
LEFT JOIN challengesphere.user_challenges user_challenge
  ON user_challenge.user_id = target.id
 AND user_challenge.challenge_id = challenge.id
LEFT JOIN challengesphere.submissions submission
  ON submission.user_id = target.id
 AND submission.challenge_id = challenge.id
GROUP BY target.id, user_record.username;

COMMIT;
