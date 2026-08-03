ALTER TABLE IF EXISTS challengesphere.challenges
    ADD COLUMN IF NOT EXISTS official_template_key VARCHAR(80),
    ADD COLUMN IF NOT EXISTS cohort_key VARCHAR(120);

CREATE UNIQUE INDEX IF NOT EXISTS uk_challenges_cohort_key
    ON challengesphere.challenges (cohort_key)
    WHERE cohort_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS challengesphere.official_challenge_templates (
    template_key VARCHAR(80) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    categories VARCHAR(255) NOT NULL,
    hashtags VARCHAR(255) NOT NULL,
    rules TEXT NOT NULL,
    benefits TEXT NOT NULL,
    challenge_mode VARCHAR(20) NOT NULL DEFAULT 'DAILY',
    number_of_days INTEGER NOT NULL,
    proof_mode VARCHAR(24) NOT NULL DEFAULT 'FLEXIBLE',
    rotation_order INTEGER NOT NULL UNIQUE,
    global_cohort_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    available_through DATE NOT NULL DEFAULT DATE '2027-07-31',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_official_template_mode CHECK (challenge_mode IN ('DAILY', 'ONE_OFF')),
    CONSTRAINT chk_official_template_proof_mode CHECK (proof_mode IN ('FLEXIBLE', 'PHOTO_REQUIRED')),
    CONSTRAINT chk_official_template_duration CHECK (number_of_days BETWEEN 1 AND 365)
);

INSERT INTO challengesphere.official_challenge_templates (
    template_key, title, description, categories, hashtags, rules, benefits,
    challenge_mode, number_of_days, proof_mode, rotation_order, global_cohort_enabled
)
VALUES
    ('move-your-way-10', 'Move Your Way 10', 'Choose any comfortable movement for ten minutes and cheer on every kind of progress.', 'Fitness,Health', 'fitness,health,movement', 'Move for about ten minutes at your own comfortable pace. Walking, rolling, seated movement, stretching, dance, and assisted movement all count.', 'Build a friendly movement rhythm without comparison or pressure.', 'DAILY', 14, 'FLEXIBLE', 1, TRUE),
    ('stretch-and-reset', 'Stretch & Reset', 'Take a short, gentle reset using stretches or mobility movements that feel comfortable.', 'Fitness,Health', 'fitness,health,mobility', 'Spend three to five minutes on gentle stretching or mobility, stopping anything that feels painful or unsafe.', 'Create a small, accessible reset in the day.', 'DAILY', 7, 'FLEXIBLE', 2, TRUE),
    ('walk-roll-or-wander', 'Walk, Roll or Wander', 'Take a comfortable change-of-scene break indoors or outside and notice something new.', 'Fitness,Lifestyle', 'fitness,lifestyle,movement', 'Choose a short walk, roll, assisted outing, or indoor route that is comfortable and permitted for you.', 'Add movement and curiosity without pace targets.', 'DAILY', 14, 'FLEXIBLE', 3, TRUE),
    ('one-song-dance-break', 'One-Song Dance Break', 'Pick one song, move in any way that feels good, and bring a playful energy boost.', 'Fitness,Lifestyle', 'fitness,lifestyle,fun', 'Move for one song using standing, seated, upper-body, or imagined choreography. Keep the space clear and comfortable.', 'Create a quick, playful energy reset.', 'DAILY', 7, 'FLEXIBLE', 4, TRUE),
    ('hydration-hello', 'Hydration Hello', 'Pause for a drink that suits you and use the check-in as a friendly reminder, not a volume contest.', 'Health,Lifestyle', 'health,lifestyle,wellness', 'Take one mindful drink that is appropriate for you. There is no required amount and personal medical guidance comes first.', 'Use social support as a gentle reminder.', 'DAILY', 14, 'FLEXIBLE', 5, TRUE),
    ('one-calm-minute', 'One Calm Minute', 'Make one quiet minute part of the day and swap simple ways to pause.', 'Health,Lifestyle', 'health,lifestyle,calm', 'Pause for one minute with a calming activity of your choice, such as quiet breathing, listening, or noticing your surroundings.', 'Build a low-pressure pause into the day.', 'DAILY', 7, 'FLEXIBLE', 6, TRUE),
    ('wind-down-win', 'Wind-Down Win', 'Try one small ritual before your main sleep period without comparing sleep scores.', 'Health,Lifestyle', 'health,lifestyle,winddown', 'Choose one comfortable wind-down action without setting a required bedtime or sleep target.', 'Discover practical ways to make evenings calmer.', 'DAILY', 14, 'FLEXIBLE', 7, TRUE),
    ('fresh-view-break', 'Fresh View Break', 'Step away from the usual view for a few minutes—outside, by a window, or elsewhere indoors.', 'Health,Lifestyle', 'health,lifestyle,break', 'Take a short break in any comfortable setting that gives you a change of view. Outside is always optional.', 'Make restorative breaks easier to remember.', 'DAILY', 7, 'FLEXIBLE', 8, TRUE),
    ('read-for-ten', 'Read for Ten', 'Read or listen for ten minutes in any format or language, then share one spoiler-free thought.', 'Learning,Lifestyle', 'learning,lifestyle,reading', 'Spend about ten minutes with a book, article, audiobook, Braille text, or another reading format you enjoy.', 'Build a flexible reading rhythm and trade recommendations.', 'DAILY', 14, 'FLEXIBLE', 9, TRUE),
    ('learn-one-share-one', 'Learn One, Share One', 'Learn one small thing and make the group smarter through friendly, bite-sized discoveries.', 'Learning,Social', 'learning,social,curiosity', 'Learn one small, non-sensitive fact or technique from a source you trust and explain it in your own words.', 'Turn individual curiosity into shared learning.', 'DAILY', 7, 'FLEXIBLE', 10, TRUE),
    ('word-around-the-world', 'Word Around the World', 'Share a useful word or phrase from any language and celebrate how differently people communicate.', 'Learning,Social', 'learning,social,language', 'Choose a respectful word or short phrase, check its meaning, and include the language or signing system it comes from.', 'Learn through respectful cultural exchange.', 'DAILY', 7, 'FLEXIBLE', 11, TRUE),
    ('one-focus-sprint', 'One Focus Sprint', 'Choose one task, focus for a short block, and use the group for gentle accountability.', 'Productivity,Learning', 'productivity,learning,focus', 'Choose one suitable task and focus for ten to twenty-five minutes, taking any breaks or accommodations you need.', 'Make starting easier through shared accountability.', 'DAILY', 14, 'FLEXIBLE', 12, TRUE),
    ('five-minute-reset', 'Five-Minute Reset', 'Spend five minutes improving one tiny physical or digital space and enjoy the small win.', 'Productivity,Lifestyle', 'productivity,lifestyle,reset', 'Reset one small area for about five minutes without lifting, discarding, or handling anything unsafe.', 'Create visible progress with very low effort.', 'DAILY', 7, 'FLEXIBLE', 13, TRUE),
    ('tomorrows-top-three', 'Tomorrow''s Top Three', 'Choose up to three priorities for the next day and build a calm planning rhythm.', 'Productivity,Lifestyle', 'productivity,lifestyle,planning', 'Write up to three realistic priorities for your next day. The tasks themselves can stay completely private.', 'Reduce next-day decision friction.', 'DAILY', 14, 'FLEXIBLE', 14, TRUE),
    ('practice-a-small-skill', 'Practice a Small Skill', 'Pick a safe skill you want to improve and let consistent practice matter more than perfection.', 'Learning,Productivity', 'learning,productivity,practice', 'Practice one safe, age-appropriate skill for a short session, using suitable supervision or instruction where needed.', 'Turn small practice sessions into visible momentum.', 'DAILY', 14, 'FLEXIBLE', 15, TRUE),
    ('curiosity-capture', 'Curiosity Capture', 'Notice one everyday question, find a reliable answer, and share the discovery.', 'Learning,Creativity', 'learning,creativity,curiosity', 'Pick one harmless everyday question and check a reliable source before sharing a short answer in your own words.', 'Strengthen curiosity and source-checking habits.', 'DAILY', 7, 'FLEXIBLE', 16, TRUE),
    ('appreciation-ping', 'Appreciation Ping', 'Give someone a sincere thank-you without making private conversations public.', 'Social,Lifestyle', 'social,lifestyle,appreciation', 'Offer one respectful thank-you or note of appreciation with no expectation that the other person responds.', 'Build a warmer social habit while respecting privacy.', 'DAILY', 7, 'FLEXIBLE', 17, TRUE),
    ('twenty-minutes-together', 'Twenty Minutes Together', 'Plan one focused shared activity online or offline with someone you trust.', 'Social,Lifestyle', 'social,lifestyle,together', 'Spend about twenty focused minutes on a mutually agreed activity with a friend, family member, or existing trusted group.', 'Make intentional time for an existing relationship.', 'ONE_OFF', 1, 'FLEXIBLE', 18, FALSE),
    ('try-a-friends-pick', 'Try a Friend''s Pick', 'Ask a friend for one accessible recommendation, try it, and compare what each person chose.', 'Social,Learning', 'social,learning,recommendation', 'Try one safe recommendation that fits your time, budget, access needs, and boundaries. Declining is always okay.', 'Create an easy conversation and shared discovery.', 'ONE_OFF', 1, 'FLEXIBLE', 19, FALSE),
    ('kindness-relay', 'Kindness Relay', 'Do one small, respectful act of kindness and share the action—not the recipient.', 'Social,Lifestyle', 'social,lifestyle,kindness', 'Choose a small helpful action that respects consent, property, local rules, and the other person''s dignity.', 'Encourage considerate action without exploiting recipients.', 'DAILY', 7, 'FLEXIBLE', 20, TRUE),
    ('same-sky-snapshot', 'Same-Sky Snapshot', 'Share one patch of sky and notice how light, colour, and weather differ.', 'Social,Creativity', 'social,creativity,sky', 'Photograph the sky only from a safe, permitted place. Staying indoors and shooting through a window both count.', 'Create a simple shared moment across locations.', 'DAILY', 7, 'FLEXIBLE', 21, TRUE),
    ('tiny-creative', 'Tiny Creative', 'Make something for five minutes in any medium and let experimentation count more than polish.', 'Creativity,Lifestyle', 'creativity,lifestyle,making', 'Create for about five minutes using a safe medium you already have. Drawing, writing, music, movement, arranging, and crafting all count.', 'Lower the pressure to begin creative work.', 'DAILY', 14, 'FLEXIBLE', 22, TRUE),
    ('seven-day-photo-quest', 'Seven-Day Photo Quest', 'Use a simple daily theme such as colour, texture, shape, or light and compare interpretations.', 'Creativity,Social', 'creativity,social,photography', 'Take one original photo responding to the theme while respecting people, property, and photography rules.', 'Practice creative observation in a privacy-aware way.', 'DAILY', 7, 'PHOTO_REQUIRED', 23, TRUE),
    ('make-it-together', 'Make It Together', 'Create one small thing with someone you already know and celebrate the collaboration.', 'Creativity,Social', 'creativity,social,collaboration', 'Agree on a safe, low-pressure creation and make sure every contributor is comfortable with what is shared.', 'Make collaboration easy to start and celebrate.', 'ONE_OFF', 1, 'FLEXIBLE', 24, FALSE),
    ('reuse-once', 'Reuse Once', 'Find one practical way to reuse, repair, refill, borrow, or repurpose something you already have.', 'Lifestyle,Creativity', 'lifestyle,creativity,reuse', 'Choose one clean, safe item without handling hazardous materials or buying anything new.', 'Encourage practical resourcefulness without spending pressure.', 'DAILY', 7, 'FLEXIBLE', 25, TRUE)
ON CONFLICT (template_key) DO UPDATE SET
    title = EXCLUDED.title,
    description = EXCLUDED.description,
    categories = EXCLUDED.categories,
    hashtags = EXCLUDED.hashtags,
    rules = EXCLUDED.rules,
    benefits = EXCLUDED.benefits,
    challenge_mode = EXCLUDED.challenge_mode,
    number_of_days = EXCLUDED.number_of_days,
    proof_mode = EXCLUDED.proof_mode,
    rotation_order = EXCLUDED.rotation_order,
    global_cohort_enabled = EXCLUDED.global_cohort_enabled,
    available_through = EXCLUDED.available_through,
    updated_at = CURRENT_TIMESTAMP;

CREATE OR REPLACE FUNCTION challengesphere.ensure_rolling_global_cohorts(
    as_of_date DATE DEFAULT CURRENT_DATE
)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    official_user_id BIGINT;
    week_start DATE;
    catalog_count INTEGER;
    rotation_offset INTEGER;
    cohort_id BIGINT;
    cohort_identifier VARCHAR(120);
    created_count INTEGER := 0;
    template_record RECORD;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM challengesphere.users
        WHERE (
            LOWER(username) = 'challengesphere_official'
            OR LOWER(email_id) = 'official-global@challengesphere.app'
        )
          AND NOT (
            LOWER(username) = 'challengesphere_official'
            AND LOWER(email_id) = 'official-global@challengesphere.app'
          )
    ) THEN
        RAISE EXCEPTION 'Reserved official ChallengeSphere account identifiers are already in use';
    END IF;

    INSERT INTO challengesphere.users (
        email_id, username, hashed_password, active, bio, profile_pic_url
    )
    SELECT
        'official-global@challengesphere.app',
        'challengesphere_official',
        'OFFICIAL_ACCOUNT_NO_LOGIN',
        TRUE,
        'Official ChallengeSphere weekly cohorts',
        NULL
    WHERE NOT EXISTS (
        SELECT 1
        FROM challengesphere.users
        WHERE LOWER(username) = 'challengesphere_official'
          AND LOWER(email_id) = 'official-global@challengesphere.app'
    )
    ON CONFLICT DO NOTHING;

    SELECT id
    INTO official_user_id
    FROM challengesphere.users
    WHERE LOWER(username) = 'challengesphere_official'
      AND LOWER(email_id) = 'official-global@challengesphere.app'
    ORDER BY id
    LIMIT 1;

    IF official_user_id IS NULL THEN
        RAISE EXCEPTION 'Unable to create or locate the official ChallengeSphere user';
    END IF;

    UPDATE challengesphere.users
    SET active = TRUE,
        bio = 'Official ChallengeSphere weekly cohorts'
    WHERE id = official_user_id;

    week_start := as_of_date - (EXTRACT(ISODOW FROM as_of_date)::INTEGER - 1);

    -- The app is pre-launch: retire legacy static official rows so discovery shows
    -- a small real cohort choice instead of a wall of empty challenges.
    UPDATE challengesphere.challenges
    SET active = FALSE
    WHERE creator_id = official_user_id
      AND private = FALSE
      AND cohort_key IS NULL
      AND active = TRUE;

    UPDATE challengesphere.challenges
    SET active = FALSE
    WHERE creator_id = official_user_id
      AND cohort_key IS NOT NULL
      AND active = TRUE
      AND start_date + number_of_days <= week_start;

    SELECT COUNT(*)::INTEGER
    INTO catalog_count
    FROM challengesphere.official_challenge_templates
    WHERE global_cohort_enabled = TRUE
      AND available_through >= as_of_date;

    IF catalog_count < 3 THEN
        RAISE EXCEPTION 'At least three enabled official challenge templates are required';
    END IF;

    rotation_offset := MOD((((week_start - DATE '2026-01-05') / 7) * 3), catalog_count);

    FOR template_record IN
        WITH eligible AS (
            SELECT
                template.*,
                ROW_NUMBER() OVER (ORDER BY template.rotation_order) - 1 AS eligible_index
            FROM challengesphere.official_challenge_templates template
            WHERE template.global_cohort_enabled = TRUE
              AND template.available_through >= as_of_date
        )
        SELECT eligible.*
        FROM eligible
        ORDER BY MOD(
            eligible.eligible_index::INTEGER - rotation_offset + catalog_count,
            catalog_count
        )
        LIMIT 3
    LOOP
        cohort_identifier := template_record.template_key || ':' || week_start::TEXT;
        cohort_id := NULL;

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
        VALUES (
            'official,cohort,' || template_record.hashtags,
            template_record.categories,
            template_record.title,
            template_record.description || E'\n\nRules & Guidelines: ' || template_record.rules,
            template_record.benefits,
            FALSE,
            week_start,
            TRUE,
            CURRENT_TIMESTAMP,
            official_user_id,
            official_user_id,
            LEAST(template_record.number_of_days, 7),
            'DAILY',
            template_record.proof_mode,
            NULL,
            template_record.template_key,
            cohort_identifier
        )
        ON CONFLICT DO NOTHING
        RETURNING id INTO cohort_id;

        IF cohort_id IS NOT NULL THEN
            created_count := created_count + 1;
        ELSE
            SELECT id
            INTO cohort_id
            FROM challengesphere.challenges
            WHERE cohort_key = cohort_identifier;
        END IF;

        IF cohort_id IS NOT NULL THEN
            INSERT INTO challengesphere.user_challenges (
                user_id, challenge_id, joined_at, completed, active
            )
            VALUES (
                official_user_id, cohort_id, CURRENT_TIMESTAMP, FALSE, TRUE
            )
            ON CONFLICT (user_id, challenge_id) DO UPDATE
            SET active = TRUE;
        END IF;
    END LOOP;

    UPDATE challengesphere.users app_user
    SET challenges_created = (
        SELECT COUNT(*)::INTEGER
        FROM challengesphere.challenges challenge
        WHERE challenge.creator_id = app_user.id
          AND challenge.active = TRUE
    )
    WHERE app_user.id = official_user_id;

    RETURN created_count;
END;
$$;

COMMENT ON TABLE challengesphere.official_challenge_templates IS
    'Curated evergreen catalog. Only three templates are materialized as public cohorts each week.';

COMMENT ON FUNCTION challengesphere.ensure_rolling_global_cohorts(DATE) IS
    'Idempotently publishes three official weekly cohorts and retires expired or legacy static official challenges.';
