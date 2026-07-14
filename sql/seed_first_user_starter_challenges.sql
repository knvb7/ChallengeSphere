-- Additive, idempotent starter content for the first active ChallengeSphere user.
-- Run with an authenticated PostgreSQL connection:
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

WITH starter_challenges (title, hashtags, categories, description, benefits, number_of_days, cover_photo_urls) AS (
    VALUES
        (
            'Morning Momentum: 30 Days of Movement',
            '#starter,#fitness,#movement,#consistency',
            'Fitness,Health',
            'Move for at least 20 minutes every day. A walk, workout, run, or stretch session all count - share the small win that kept you moving.',
            'Builds consistency, energy, and a movement habit that fits real life.',
            30,
            'https://images.unsplash.com/photo-1538805060514-97d9cc17730c?auto=format&fit=crop&w=1200&q=85,https://images.unsplash.com/photo-1517836357463-d25dfeac3438?auto=format&fit=crop&w=1200&q=85,https://images.unsplash.com/photo-1571019614242-c5c5dee9f50b?auto=format&fit=crop&w=1200&q=85'
        ),
        (
            '30 Pages Before Bed',
            '#starter,#reading,#books,#focus',
            'Learning,Lifestyle',
            'Read 30 pages before bed each night for 30 days. Post the page, passage, or idea you want to remember tomorrow.',
            'Creates a calmer evening routine while improving focus and knowledge.',
            30,
            'https://images.unsplash.com/photo-1524995997946-a1c2e315a42f?auto=format&fit=crop&w=1200&q=85,https://images.unsplash.com/photo-1495446815901-a7297e633e8d?auto=format&fit=crop&w=1200&q=85,https://images.unsplash.com/photo-1544947950-fa07a98d237f?auto=format&fit=crop&w=1200&q=85'
        ),
        (
            'Build in Public: 21 Days of Code',
            '#starter,#coding,#buildinpublic,#learning',
            'Tech,Learning',
            'Spend focused time coding every day for 21 days. Share a screenshot of the feature, bug fix, exercise, or lesson that moved your project forward.',
            'Turns steady practice into visible momentum and a useful body of work.',
            21,
            'https://images.unsplash.com/photo-1517694712202-14dd9538aa97?auto=format&fit=crop&w=1200&q=85,https://images.unsplash.com/photo-1461749280684-dccba630e2f6?auto=format&fit=crop&w=1200&q=85,https://images.unsplash.com/photo-1498050108023-c5249f4df085?auto=format&fit=crop&w=1200&q=85'
        ),
        (
            'Ten Quiet Minutes',
            '#starter,#wellness,#mindfulness,#reset',
            'Wellness,Mindfulness',
            'Take ten quiet minutes each day to breathe, meditate, journal, or simply sit without a screen. Share one thing you noticed afterward.',
            'Makes space for calm, attention, and a more intentional daily reset.',
            21,
            'https://images.unsplash.com/photo-1506126613408-eca07ce68773?auto=format&fit=crop&w=1200&q=85,https://images.unsplash.com/photo-1545389336-cf090694435e?auto=format&fit=crop&w=1200&q=85,https://images.unsplash.com/photo-1499209974431-9dddcece7f88?auto=format&fit=crop&w=1200&q=85'
        )
), inserted_challenges AS (
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
        starter.cover_photo_urls
    FROM starter_challenges starter
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

WITH starter_submissions (challenge_title, day_number, description, media_urls, submitted_at) AS (
    VALUES
        ('Morning Momentum: 30 Days of Movement', 1, 'Day 1: Started with a brisk morning walk before the day got busy. Keeping it simple and showing up again tomorrow.', 'https://images.unsplash.com/photo-1552674605-db6ffd4facb5?auto=format&fit=crop&w=1200&q=85', CURRENT_TIMESTAMP - INTERVAL '2 days'),
        ('Morning Momentum: 30 Days of Movement', 2, 'Day 2: A short strength session at home. The hardest part was beginning, and that is exactly why it counts.', 'https://images.unsplash.com/photo-1517836357463-d25dfeac3438?auto=format&fit=crop&w=1200&q=85', CURRENT_TIMESTAMP - INTERVAL '1 day'),
        ('Morning Momentum: 30 Days of Movement', 3, 'Day 3: Took the long route home and finished with a stretch. Small streak, already feeling better.', 'https://images.unsplash.com/photo-1538805060514-97d9cc17730c?auto=format&fit=crop&w=1200&q=85', CURRENT_TIMESTAMP),
        ('30 Pages Before Bed', 1, 'Day 1: Thirty pages and no phone beside the bed. The quiet ending to the day was the best part.', 'https://images.unsplash.com/photo-1512820790803-83ca734da794?auto=format&fit=crop&w=1200&q=85', CURRENT_TIMESTAMP - INTERVAL '2 days'),
        ('30 Pages Before Bed', 2, 'Day 2: Kept going past the page goal because the chapter got good. Writing down one idea before sleep.', 'https://images.unsplash.com/photo-1495446815901-a7297e633e8d?auto=format&fit=crop&w=1200&q=85', CURRENT_TIMESTAMP - INTERVAL '1 day'),
        ('30 Pages Before Bed', 3, 'Day 3: New chapter, warm tea, and the same thirty-page promise. This routine is starting to stick.', 'https://images.unsplash.com/photo-1524995997946-a1c2e315a42f?auto=format&fit=crop&w=1200&q=85', CURRENT_TIMESTAMP),
        ('Build in Public: 21 Days of Code', 1, 'Day 1: Set up the project structure and made the first screen responsive. Tiny commit, real progress.', 'https://images.unsplash.com/photo-1461749280684-dccba630e2f6?auto=format&fit=crop&w=1200&q=85', CURRENT_TIMESTAMP - INTERVAL '2 days'),
        ('Build in Public: 21 Days of Code', 2, 'Day 2: Chased down a state bug that looked obvious only after it was fixed. Debugging is still building.', 'https://images.unsplash.com/photo-1517694712202-14dd9538aa97?auto=format&fit=crop&w=1200&q=85', CURRENT_TIMESTAMP - INTERVAL '1 day'),
        ('Build in Public: 21 Days of Code', 3, 'Day 3: Added the interaction that makes the screen feel alive. Shipping the small pieces in public.', 'https://images.unsplash.com/photo-1498050108023-c5249f4df085?auto=format&fit=crop&w=1200&q=85', CURRENT_TIMESTAMP),
        ('Ten Quiet Minutes', 1, 'Day 1: Ten minutes of breathing with notifications off. Noticed how noisy the first few minutes were.', 'https://images.unsplash.com/photo-1506126613408-eca07ce68773?auto=format&fit=crop&w=1200&q=85', CURRENT_TIMESTAMP - INTERVAL '2 days'),
        ('Ten Quiet Minutes', 2, 'Day 2: Sat by the window and wrote down three things that felt steady today. A gentle reset.', 'https://images.unsplash.com/photo-1499209974431-9dddcece7f88?auto=format&fit=crop&w=1200&q=85', CURRENT_TIMESTAMP - INTERVAL '1 day'),
        ('Ten Quiet Minutes', 3, 'Day 3: A slow meditation before work. Ten minutes was enough to make the next hour feel clearer.', 'https://images.unsplash.com/photo-1545389336-cf090694435e?auto=format&fit=crop&w=1200&q=85', CURRENT_TIMESTAMP)
)
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
    submission.media_urls,
    false,
    submission.submitted_at,
    submission.submitted_at,
    submission.submitted_at,
    true,
    0,
    0,
    0
FROM starter_submissions submission
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
