\set ON_ERROR_STOP on

BEGIN;

INSERT INTO challengesphere.users (
    email_id,
    username,
    hashed_password,
    active,
    bio,
    profile_pic_url
)
SELECT
    'official-global@challengesphere.app',
    'challengesphere',
    'OFFICIAL_ACCOUNT_NO_LOGIN',
    TRUE,
    'Official ChallengeSphere global challenges',
    'official/global-challenges/Challengesphere-logo.png'
WHERE NOT EXISTS (
    SELECT 1
    FROM challengesphere.users
    WHERE lower(username) = 'challengesphere'
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM challengesphere.users WHERE lower(username) = 'challengesphere'
    ) THEN
        RAISE EXCEPTION 'Unable to create or locate official challengesphere user';
    END IF;
END $$;

UPDATE challengesphere.users
SET profile_pic_url = 'official/global-challenges/Challengesphere-logo.png',
    bio = 'Official ChallengeSphere global challenges'
WHERE lower(username) = 'challengesphere';

WITH official AS (
    SELECT id
    FROM challengesphere.users
    WHERE lower(username) = 'challengesphere'
    ORDER BY id
    LIMIT 1
),
source (
    title,
    description,
    category,
    challenge_type,
    duration,
    rules,
    benefits
) AS (
    VALUES
        ('No Laugh Challenge', 'Try not to laugh while watching funny videos or jokes.', 'Fun', 'One-off', '1 Day', 'Watch 10 funny clips. If you laugh, restart once.', 'Boosts mood and creates funny moments.'),
        ('Random Dance Break', 'Record or perform a 30-second random dance.', 'Fun', 'One-off', '1 Day', 'Pick any song and dance without overthinking.', 'Builds confidence and energy.'),
        ('Meme Maker Challenge', 'Create one original meme about your day.', 'Creativity, Fun', 'One-off', '1 Day', 'Meme must be original and non-offensive.', 'Improves creativity and humor.'),
        ('Compliment Bomb', 'Give genuine compliments to 5 people.', 'Social', 'One-off', '1 Day', 'Compliments must be specific and honest.', 'Improves social connection.'),
        ('Mystery Snack Challenge', 'Try a snack you have never eaten before.', 'Fun', 'One-off', '1 Day', 'Pick something safe and affordable.', 'Adds novelty and fun to the day.'),
        ('Childhood Cartoon Day', 'Watch one episode of a cartoon you loved as a kid.', 'Fun', 'One-off', '1 Day', 'No binge-watching beyond 2 episodes.', 'Brings nostalgia and relaxation.'),
        ('Emoji Story Challenge', 'Tell a full story using only emojis.', 'Creativity', 'One-off', '1 Day', 'Use at least 10 emojis. Ask someone to guess it.', 'Improves playful creativity.'),
        ('Funny Selfie Challenge', 'Take the funniest selfie you can.', 'Fun', 'One-off', '1 Day', 'Keep it harmless and appropriate.', 'Builds comfort with self-expression.'),
        ('Accent Challenge', 'Speak in a funny accent for 2 minutes.', 'Fun', 'One-off', '1 Day', 'Do not mock any community or culture. Keep it playful.', 'Great icebreaker and confidence booster.'),
        ('Movie Dialogue Challenge', 'Recreate a famous movie dialogue.', 'Fun, Creativity', 'One-off', '1 Day', 'Add your own dramatic expression.', 'Improves expression and entertainment.'),
        ('10-Minute Doodle Challenge', 'Draw anything for 10 minutes without erasing.', 'Creativity', 'One-off', '1 Day', 'Do not aim for perfection. Just complete it.', 'Reduces stress and boosts creativity.'),
        ('Reverse Hand Challenge', 'Write your name using your non-dominant hand.', 'Fun', 'One-off', '1 Day', 'Try 5 times and compare attempts.', 'Fun brain-body coordination activity.'),
        ('Sing Without Music', 'Sing one song without background music.', 'Fun', 'One-off', '1 Day', 'Sing for at least 60 seconds.', 'Builds confidence and playfulness.'),
        ('Food Rating Challenge', 'Rate everything you eat today out of 10.', 'Fun', 'One-off', '1 Day', 'Give a short reason for each rating.', 'Makes daily meals more enjoyable.'),
        ('5-Minute Room Treasure Hunt', 'Find 5 random objects in your room that match a theme.', 'Fun', 'One-off', '1 Day', 'Choose a theme like red, round, old, or useful.', 'Improves observation and quick thinking.'),
        ('Best Joke Challenge', 'Tell your best joke to 3 people.', 'Social, Fun', 'One-off', '1 Day', 'Keep jokes clean and respectful.', 'Improves social confidence.'),
        ('Playlist Personality', 'Create a 5-song playlist that describes your mood.', 'Fun', 'One-off', '1 Day', 'Each song must represent one emotion.', 'Helps emotional expression.'),
        ('Dress Color Theme', 'Wear one color theme for the day.', 'Lifestyle, Fun', 'One-off', '1 Day', 'Pick one main color and style around it.', 'Adds fun to daily routine.'),
        ('Voice Note Story', 'Send a dramatic 30-second story voice note to a friend.', 'Social, Fun', 'One-off', '1 Day', 'Story can be fictional but must be entertaining.', 'Improves storytelling.'),
        ('Random Fact Battle', 'Share 5 weird but true facts with someone.', 'Learning, Fun', 'One-off', '1 Day', 'Facts should be real and harmless.', 'Makes learning fun.'),
        ('3-Day Photo Challenge', 'Click one creative photo every day.', 'Creativity, Fun', 'Daily', '3 Days', 'Each photo must follow a theme: object, person, place.', 'Builds creative observation.'),
        ('7-Day Fun Journal', 'Write one funny thing that happened each day.', 'Journaling, Fun', 'Daily', '1 Week', 'Write at least 2 lines daily.', 'Builds gratitude and humor awareness.'),
        ('7-Day Kind Prank Challenge', 'Do one harmless, kind surprise for someone daily.', 'Social, Fun', 'Daily', '1 Week', 'No embarrassing or risky pranks.', 'Creates joy and connection.'),
        ('7-Day New Song Challenge', 'Listen to one new song every day.', 'Fun', 'Daily', '1 Week', 'Try different genres. Save your favorites.', 'Expands music taste.'),
        ('2-Week Social Icebreaker', 'Start one light conversation daily.', 'Social', 'Daily', '2 Weeks', 'Keep it respectful and natural.', 'Improves social confidence.'),
        ('No Phone Morning', 'Avoid using your phone for the first 30 minutes after waking up.', 'Lifestyle', 'Daily', '1 Week', 'No social media, messages, or scrolling after waking.', 'Improves focus and reduces morning anxiety.'),
        ('10-Minute Reading', 'Read a book or article for 10 minutes daily.', 'Learning', 'Daily', '1 Week', 'Avoid short social media posts. Read meaningful content.', 'Builds knowledge and discipline.'),
        ('Daily Journal Reset', 'Write about your thoughts, mood, and goals.', 'Journaling', 'Daily', '1 Week', 'Write at least 5 lines every day.', 'Improves self-awareness.'),
        ('30-Minute Deep Work', 'Work or study with full focus for 30 minutes.', 'Study', 'Daily', '1 Week', 'No phone, tabs, or distractions during the session.', 'Improves productivity.'),
        ('Water Discipline', 'Drink enough water throughout the day.', 'Health', 'Daily', '1 Week', 'Track your intake. Avoid sugary drinks as replacement.', 'Supports energy and health.'),
        ('Sleep On Time', 'Sleep at a fixed target time every night.', 'Health', 'Daily', '1 Week', 'Avoid screens 20 minutes before sleep.', 'Improves recovery and mental clarity.'),
        ('Gratitude Practice', 'Write 3 things you are grateful for.', 'Journaling', 'Daily', '1 Week', 'Be specific, not generic.', 'Builds positivity and emotional balance.'),
        ('20-Minute Walk', 'Walk for at least 20 minutes daily.', 'Fitness', 'Daily', '1 Week', 'Maintain a comfortable pace. Avoid skipping days.', 'Improves health and mood.'),
        ('Skill Sprint', 'Spend 30 minutes learning one practical skill.', 'Learning', 'Daily', '1 Week', 'Pick one skill and continue the same skill all week.', 'Builds real progress.'),
        ('No Junk Food Day', 'Avoid junk food for one full day.', 'Health', 'One-off', '1 Day', 'Avoid fried snacks, sugary drinks, and desserts.', 'Builds food discipline.'),
        ('Digital Cleanup', 'Delete unused apps, files, and screenshots.', 'Lifestyle', 'One-off', '1 Day', 'Clean phone, laptop, or cloud storage.', 'Reduces digital clutter.'),
        ('Budget Check', 'Review your spending and write where money went.', 'Lifestyle', 'One-off', '1 Day', 'Track at least the last 7 days of spending.', 'Improves financial awareness.'),
        ('Resume Improvement', 'Improve one section of your resume.', 'Career', 'One-off', '1 Day', 'Focus on impact, metrics, and clarity.', 'Supports career growth.'),
        ('LinkedIn Upgrade', 'Improve your LinkedIn headline, about, or experience section.', 'Career', 'One-off', '1 Day', 'Make it professional and specific.', 'Improves professional presence.'),
        ('One Hard Task First', 'Finish your most important task before easy tasks.', 'Self Improvement', 'Daily', '1 Week', 'Pick the task the night before. Do it first.', 'Builds discipline and momentum.'),
        ('Meditation Starter', 'Meditate for 5 minutes daily.', 'Health', 'Daily', '1 Week', 'Sit quietly and focus on breathing.', 'Reduces stress and improves calmness.'),
        ('Study Revision Challenge', 'Revise one topic every day.', 'Study', 'Daily', '1 Week', 'Make short notes after revision.', 'Improves retention.'),
        ('No Complaint Day', 'Avoid complaining for one full day.', 'Self Improvement', 'One-off', '1 Day', 'Replace complaints with solutions or silence.', 'Improves mindset and emotional control.'),
        ('Posture Awareness', 'Check and correct your posture 5 times a day.', 'Health', 'Daily', '1 Week', 'Set reminders if needed.', 'Reduces stiffness and improves awareness.'),
        ('7-Day Planning Habit', 'Plan your next day every night.', 'Self Improvement', 'Daily', '1 Week', 'Write top 3 priorities for tomorrow.', 'Improves clarity and execution.'),
        ('2-Week Fitness Consistency', 'Do any workout or movement daily.', 'Fitness', 'Daily', '2 Weeks', 'Minimum 15 minutes per day. Active recovery allowed.', 'Builds consistency and stamina.'),
        ('2-Week Study Streak', 'Study one subject or skill daily.', 'Study', 'Daily', '2 Weeks', 'Minimum 30 focused minutes daily.', 'Builds discipline and knowledge.'),
        ('1-Month Habit Tracker', 'Track one important habit every day.', 'Self Improvement', 'Daily', '1 Month', 'Choose one habit only. Mark done or missed daily.', 'Builds long-term discipline.'),
        ('1-Month Reading Habit', 'Read at least 10 pages every day.', 'Learning', 'Daily', '1 Month', 'Use a book, not random scrolling content.', 'Builds patience and knowledge.'),
        ('3-Month Transformation', 'Commit to one major life improvement goal.', 'Self Improvement', 'Daily', '3 Months', 'Pick fitness, study, career, money, or mindset. Track weekly progress.', 'Creates serious personal growth.')
),
mapped AS (
    SELECT
        source.title,
        source.description || E'\n\nRules & Guidelines: ' || source.rules AS description,
        lower(replace(source.category, ' ', '')) AS hashtags,
        source.category AS categories,
        source.benefits,
        CASE source.challenge_type
            WHEN 'One-off' THEN 'ONE_OFF'
            ELSE 'DAILY'
        END AS challenge_mode,
        CASE source.duration
            WHEN '1 Day' THEN 1
            WHEN '3 Days' THEN 3
            WHEN '1 Week' THEN 7
            WHEN '2 Weeks' THEN 14
            WHEN '1 Month' THEN 30
            WHEN '3 Months' THEN 90
        END AS number_of_days,
        CASE
            WHEN source.category ILIKE '%Fitness%' THEN 'official/global-challenges/official-fitness.jpg'
            WHEN source.category ILIKE '%Health%' THEN 'official/global-challenges/official-wellness.jpg'
            WHEN source.category ILIKE '%Creativity%' THEN 'official/global-challenges/official-creative.jpg'
            WHEN source.category ILIKE '%Learning%'
              OR source.category ILIKE '%Study%'
              OR source.category ILIKE '%Journaling%' THEN 'official/global-challenges/official-reading-study.jpg'
            WHEN source.category ILIKE '%Career%'
              OR source.category ILIKE '%Lifestyle%'
              OR source.category ILIKE '%Self Improvement%' THEN 'official/global-challenges/official-productivity.jpg'
            ELSE 'official/global-challenges/official-fun-social-bright.jpg'
        END AS cover_photo_urls
    FROM source
)
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
    mapped.hashtags,
    mapped.categories,
    mapped.title,
    mapped.description,
    mapped.benefits,
    FALSE,
    CURRENT_DATE,
    TRUE,
    CURRENT_TIMESTAMP,
    official.id,
    official.id,
    mapped.number_of_days,
    mapped.challenge_mode,
    mapped.cover_photo_urls
FROM official
CROSS JOIN mapped
WHERE NOT EXISTS (
    SELECT 1
    FROM challengesphere.challenges existing
    WHERE existing.creator_id = official.id
      AND existing.title = mapped.title
);

WITH official AS (
    SELECT id
    FROM challengesphere.users
    WHERE lower(username) = 'challengesphere'
    ORDER BY id
    LIMIT 1
)
UPDATE challengesphere.challenges challenge
SET cover_photo_urls = CASE
        WHEN challenge.categories ILIKE '%Fitness%' THEN 'official/global-challenges/official-fitness.jpg'
        WHEN challenge.categories ILIKE '%Health%' THEN 'official/global-challenges/official-wellness.jpg'
        WHEN challenge.categories ILIKE '%Creativity%' THEN 'official/global-challenges/official-creative.jpg'
        WHEN challenge.categories ILIKE '%Learning%'
          OR challenge.categories ILIKE '%Study%'
          OR challenge.categories ILIKE '%Journaling%' THEN 'official/global-challenges/official-reading-study.jpg'
        WHEN challenge.categories ILIKE '%Career%'
          OR challenge.categories ILIKE '%Lifestyle%'
          OR challenge.categories ILIKE '%Self Improvement%' THEN 'official/global-challenges/official-productivity.jpg'
        ELSE 'official/global-challenges/official-fun-social-bright.jpg'
    END
FROM official
WHERE challenge.creator_id = official.id
  AND challenge.active = TRUE
  AND challenge.private = FALSE;

WITH official AS (
    SELECT id
    FROM challengesphere.users
    WHERE lower(username) = 'challengesphere'
    ORDER BY id
    LIMIT 1
)
INSERT INTO challengesphere.user_challenges (
    user_id,
    challenge_id,
    joined_at,
    completed,
    active
)
SELECT
    official.id,
    challenge.id,
    CURRENT_TIMESTAMP,
    FALSE,
    TRUE
FROM official
JOIN challengesphere.challenges challenge
    ON challenge.creator_id = official.id
ON CONFLICT (user_id, challenge_id) DO UPDATE
SET active = TRUE;

WITH official AS (
    SELECT id
    FROM challengesphere.users
    WHERE lower(username) = 'challengesphere'
    ORDER BY id
    LIMIT 1
)
UPDATE challengesphere.users app_user
SET challenges_created = (
    SELECT COUNT(*)::INTEGER
    FROM challengesphere.challenges challenge
    WHERE challenge.creator_id = app_user.id
      AND challenge.active = TRUE
)
FROM official
WHERE app_user.id = official.id;

COMMIT;
