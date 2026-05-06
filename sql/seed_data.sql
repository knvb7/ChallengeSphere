-- ============================================================
-- ChallengeSphere Seed Data Script (current schema)
-- ~500 users, 50 challenges, thousands of submissions, glories, comments, etc.
-- Run: psql -U postgres -d postgres -f sql/seed_data.sql
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ======================== CLEAN SLATE ========================
DELETE FROM challengesphere.invite_usages;
DELETE FROM challengesphere.challenge_invites;
DELETE FROM challengesphere.glories;
DELETE FROM challengesphere.comments;
DELETE FROM challengesphere.submissions;
DELETE FROM challengesphere.leaderboard_entries;
DELETE FROM challengesphere.notifications;
DELETE FROM challengesphere.reports;
DELETE FROM challengesphere.user_preferences;
DELETE FROM challengesphere.follows;
DELETE FROM challengesphere.user_challenges;
DELETE FROM challengesphere.challenges;
DELETE FROM challengesphere.users;

-- Reset sequences
ALTER SEQUENCE challengesphere.users_id_seq RESTART WITH 1;
ALTER SEQUENCE challengesphere.challenges_id_seq RESTART WITH 1;
ALTER SEQUENCE challengesphere.submissions_id_seq RESTART WITH 1;
ALTER SEQUENCE challengesphere.leaderboard_entries_id_seq RESTART WITH 1;

-- ======================== SEED USERS (500 users) ========================
DO $$
DECLARE
    i INTEGER;
    first_names TEXT[] := ARRAY[
        'Alex','Jordan','Taylor','Morgan','Casey','Riley','Quinn','Avery','Cameron','Drew',
        'Emma','Liam','Olivia','Noah','Ava','Ethan','Sophia','Mason','Isabella','William',
        'Mia','James','Charlotte','Benjamin','Amelia','Lucas','Harper','Henry','Evelyn','Alexander',
        'Luna','Sebastian','Camila','Jack','Aria','Daniel','Scarlett','Michael','Madison','Owen',
        'Layla','Penelope','Jayden','Theodore','Aaliyah','Aiden','Ella','Ryan','Zoey','Maya'
    ];
    bios TEXT[] := ARRAY[
        'Fitness enthusiast | New York', 'Bookworm | Los Angeles', 'Tech geek | Chicago',
        'Yoga lover | Houston', 'Runner | Phoenix', 'Coder | San Francisco',
        'Artist | Seattle', 'Writer | Denver', 'Music lover | Boston', 'Nature explorer | Portland',
        'Foodie | Atlanta', 'Traveler | Miami', 'Gamer | Minneapolis', 'Photographer | Austin',
        'Chef | Nashville'
    ];
BEGIN
    FOR i IN 1..500 LOOP
        INSERT INTO challengesphere.users (
            email_id, phone, username, hashed_password,
            genuineness_score, active,
            challenges_created, challenges_accepted, followers, following,
            premium_user, notifications, bio, profile_pic_url,
            created_at, last_login_at
        ) VALUES (
            'user' || i || '@example.com',
            '98765' || LPAD(i::TEXT, 5, '0'),
            'user' || i || '_' || first_names[1 + (i % 50)],
            '$2a$10$N9qo8uLOickgx2ZMRZoMy.MQDxBCEZ5K.yV.gfgKPLyTSIFZWHWjK',
            80 + (i % 20),
            true,
            0, 0, 0, 0,
            CASE WHEN i % 10 = 0 THEN 1 ELSE 0 END,
            1,
            bios[1 + (i % 15)],
            'https://i.pravatar.cc/400?img=' || (i % 70 + 1),
            NOW() - make_interval(secs => (random() * 365 * 86400)::BIGINT),
            NOW() - make_interval(secs => (random() * 30 * 86400)::BIGINT)
        );
    END LOOP;
END $$;

-- ======================== SEED USER PREFERENCES ========================
INSERT INTO challengesphere.user_preferences (user_id)
SELECT id FROM challengesphere.users;

-- ======================== SEED CHALLENGES (50 challenges) ========================
DO $$
DECLARE
    i INTEGER;
    challenge_titles TEXT[] := ARRAY[
        '30-Day Fitness Challenge', '100 Days of Meditation', 'Read 52 Books in a Year',
        'Learn a New Language', 'Daily Coding Practice', 'Run 5K Every Day',
        'No Sugar Challenge', 'Daily Journaling', 'Wake Up at 5 AM', '100 Days of Code',
        'Yoga Every Morning', 'Drink 3L Water Daily', '30-Day Plank Challenge',
        'Learn Photography', 'Read 30 Minutes Daily', 'No Social Media',
        'Daily Stretching', 'Learn Guitar', 'Cook Every Meal', 'Walk 10K Steps',
        'Gratitude Journal', 'Learn a New Recipe Weekly', '30-Day Ab Challenge',
        'Read Non-Fiction', 'Daily Meditation', 'Run a Marathon', 'Learn Spanish',
        'Write 500 Words Daily', 'No Caffeine Challenge', 'Morning Yoga',
        'Evening Walks', 'Learn Piano', 'Daily Pushups', 'Read Classics',
        'No Junk Food', 'Learn Drawing', 'Sleep 8 Hours', 'Learn Web Development',
        'Daily Cold Shower', 'Read Biography', 'Learn Machine Learning',
        '30-Day Squat Challenge', 'Read Self-Help', 'Learn Docker',
        'Daily Visualization', 'Run Half Marathon', 'Read History',
        'Learn React', 'No Alcohol Month', 'Learn Python'
    ];
    descriptions TEXT[] := ARRAY[
        'Build strength and endurance with daily workouts.',
        'Cultivate inner peace through consistent meditation practice.',
        'Expand your mind by reading one book every week.',
        'Become conversational in a new language in 90 days.',
        'Sharpen your coding skills with daily algorithm challenges.',
        'Improve cardiovascular health by running every day.',
        'Reset your diet by eliminating sugar for 30 days.',
        'Capture thoughts and reflections every single day.',
        'Transform your mornings by rising before the sun.',
        'Commit to coding every day for 100 straight days.',
        'Start each day with a calming yoga flow.',
        'Stay hydrated and boost energy with 3 liters daily.',
        'Build a rock-solid core with progressive plank holds.',
        'Master composition and lighting to take stunning photos.',
        'Expand your knowledge with 30 minutes of reading daily.',
        'Reclaim your time and attention by logging off social media.',
        'Increase flexibility and reduce injury risk with daily stretching.',
        'Learn chords, scales, and your first songs on guitar.',
        'Save money and eat healthier by cooking every meal at home.',
        'Hit your step goal every day for sustained energy.',
        'Rewire your brain for positivity with a daily gratitude practice.',
        'Become a better cook by trying a new recipe each week.',
        'Sculpt your abs with a progressive 30-day program.',
        'Learn from the world''s greatest non-fiction authors.',
        'Find stillness in the chaos with a daily meditation habit.',
        'Train smart and cross the marathon finish line.',
        'Open doors to a billion new people by learning Spanish.',
        'Develop your voice and ideas through daily writing.',
        'Break the caffeine dependency and reclaim natural energy.',
        'Align body and breath every morning on the mat.',
        'Wind down with a peaceful evening walk every day.',
        'Learn to read music and play your favorite songs.',
        'Build upper-body strength with daily pushup progressions.',
        'Read the greatest works of literature ever written.',
        'Fuel your body with whole foods for 30 days.',
        'Unlock your creativity with a daily drawing practice.',
        'Prioritize rest and let your body and mind recover.',
        'Break into tech by building real web projects daily.',
        'Boost immunity and mental clarity with daily cold exposure.',
        'Draw inspiration from the lives of extraordinary people.',
        'Master the fundamentals of ML and build your first model.',
        'Build powerful legs and glutes with daily squats.',
        'Level up your mindset with the best self-help titles.',
        'Containerize applications and learn modern DevOps.',
        'Prime your subconscious by visualizing your goals daily.',
        'Build your base and conquer the half-marathon distance.',
        'Travel through time with the best history books.',
        'Build dynamic UIs with the world''s most popular JS library.',
        'Improve sleep, skin, and mental clarity by going dry.',
        'Learn the programming language powering AI and the web.'
    ];
    categories TEXT[] := ARRAY[
        'Fitness,Health', 'Mindfulness,Wellness', 'Education,Learning',
        'Tech,Programming', 'Lifestyle,Hobbies', 'Diet,Nutrition',
        'Sports,Running', 'Arts,Creativity'
    ];
    hashtag_sets TEXT[] := ARRAY[
        '#fitness,#health,#workout', '#meditation,#mindfulness,#wellness',
        '#reading,#books,#education', '#language,#learning', '#coding,#programming,#dev',
        '#running,#fitness', '#health,#nutrition,#diet', '#journaling,#writing,#selfcare',
        '#morning,#productivity,#habits', '#100daysofcode,#coding', '#yoga,#fitness,#wellness',
        '#hydration,#health', '#fitness,#plank,#core', '#photography,#art,#creative',
        '#reading,#books,#daily', '#socialmedia,#detox,#wellness', '#stretching,#flexibility',
        '#music,#guitar,#learning', '#cooking,#food,#healthy', '#walking,#steps,#fitness',
        '#gratitude,#journal,#wellness', '#cooking,#recipes,#skills', '#fitness,#abs,#core',
        '#reading,#books,#nonfiction', '#meditation,#calm,#peace', '#running,#marathon,#training',
        '#spanish,#language,#learning', '#writing,#creativity,#daily', '#caffeine,#health,#detox',
        '#yoga,#morning,#routine', '#walking,#evening,#relax', '#piano,#music,#learning',
        '#pushups,#fitness,#strength', '#reading,#classics,#literature', '#nutrition,#health,#clean',
        '#drawing,#art,#sketch', '#sleep,#health,#rest', '#webdev,#coding,#frontend',
        '#coldshower,#health,#habit', '#biography,#reading,#inspiration', '#ml,#ai,#programming',
        '#squats,#fitness,#legs', '#selfhelp,#books,#growth', '#docker,#devops,#containers',
        '#visualization,#goals,#success', '#halfmarathon,#running', '#history,#reading,#learning',
        '#react,#javascript,#webdev', '#sober,#health,#challenge', '#python,#programming,#coding'
    ];
    cover_photos TEXT[] := ARRAY[
        'https://images.unsplash.com/photo-1571019614242-c5c5dee9f50b?w=800',
        'https://images.unsplash.com/photo-1545205597-3d9d02c29597?w=800',
        'https://images.unsplash.com/photo-1506126613408-eca07ce68773?w=800',
        'https://images.unsplash.com/photo-1481627834876-b7833e8f5570?w=800',
        'https://images.unsplash.com/photo-1555066931-4365d14bab8c?w=800',
        'https://images.unsplash.com/photo-1508739773434-c26b3d09e071?w=800',
        'https://images.unsplash.com/photo-1490645935967-10de6ba17061?w=800',
        'https://images.unsplash.com/photo-1518611012118-696072aa579a?w=800'
    ];
BEGIN
    FOR i IN 1..50 LOOP
        INSERT INTO challengesphere.challenges (
            hashtags, categories, title, description, benefits,
            private, start_date, active, created_at,
            creator_id, current_owner_id, number_of_days, cover_photo_urls
        ) VALUES (
            hashtag_sets[i],
            categories[1 + (i % 8)],
            challenge_titles[i],
            descriptions[i],
            'Improved health, discipline, and wellbeing.',
            CASE WHEN i % 8 = 0 THEN true ELSE false END,
            CURRENT_DATE - (i % 30),
            true,
            NOW() - (i * INTERVAL '1 day'),
            1 + (i % 500),
            1 + (i % 500),
            21 + (i % 60),
            cover_photos[1 + (i % 8)] || ',' || cover_photos[1 + ((i + 1) % 8)] || ',' || cover_photos[1 + ((i + 2) % 8)]
        );
    END LOOP;
END $$;

-- ======================== SEED USER_CHALLENGES ========================
DO $$
DECLARE
    u INTEGER;
    c INTEGER;
    n INTEGER;
BEGIN
    FOR u IN 1..500 LOOP
        n := 3 + floor(random() * 6)::INTEGER;
        FOR i IN 1..n LOOP
            c := 1 + floor(random() * 50)::INTEGER;
            INSERT INTO challengesphere.user_challenges (user_id, challenge_id, joined_at, completed, active)
            VALUES (
                u, c,
                NOW() - make_interval(secs => (random() * 60 * 86400)::BIGINT),
                CASE WHEN random() > 0.75 THEN true ELSE false END,
                true
            )
            ON CONFLICT (user_id, challenge_id) DO NOTHING;
        END LOOP;
    END LOOP;
END $$;

-- ======================== SEED FOLLOWS ========================
DO $$
DECLARE
    f INTEGER;
    g INTEGER;
BEGIN
    FOR f IN 1..500 LOOP
        FOR i IN 1..(10 + floor(random() * 40)::INTEGER) LOOP
            g := 1 + floor(random() * 500)::INTEGER;
            IF g != f THEN
                INSERT INTO challengesphere.follows (follower_id, following_id, created_at, active)
                VALUES (f, g, NOW() - make_interval(secs => (random() * 180 * 86400)::BIGINT), true)
                ON CONFLICT (follower_id, following_id) DO NOTHING;
            END IF;
        END LOOP;
    END LOOP;
END $$;

-- Update follower/following counts
UPDATE challengesphere.users u SET
    followers = (SELECT COUNT(*) FROM challengesphere.follows f WHERE f.following_id = u.id AND f.active),
    following = (SELECT COUNT(*) FROM challengesphere.follows f WHERE f.follower_id = u.id AND f.active);

-- ======================== SEED SUBMISSIONS ========================
DO $$
DECLARE
    r RECORD;
    total_days INTEGER;
    day INTEGER;
    txt TEXT;
    descs TEXT[] := ARRAY[
        'Day %s complete! Feeling great today.',
        'Day %s done! Consistency is everything.',
        'Day %s in the books. Progress not perfection!',
        'Crushed day %s! Keep pushing forward.',
        'Day %s finished strong. Another win!',
        'Day %s — showing up even when it''s hard.',
        'Day %s. Small steps every day add up.',
        'Day %s complete. The grind never stops!'
    ];
    -- Diverse image pool — fitness, food, nature, reading, running, yoga, work,
    -- meditation, coffee, outdoors, art, photography. Picked randomly per submission
    -- (not by day_number) so every post doesn't look identical.
    images TEXT[] := ARRAY[
        'https://images.unsplash.com/photo-1571019614242-c5c5dee9f50b?w=800',   -- gym / fitness
        'https://images.unsplash.com/photo-1517836357463-d25dfeac3438?w=800',   -- running track
        'https://images.unsplash.com/photo-1544367567-0f2fcb009e0b?w=800',      -- yoga
        'https://images.unsplash.com/photo-1540206351-d6465b3ac5c1?w=800',      -- meditation
        'https://images.unsplash.com/photo-1534438327276-14e5300c3a48?w=800',   -- weights
        'https://images.unsplash.com/photo-1518310383802-640c2de311b2?w=800',   -- trail run
        'https://images.unsplash.com/photo-1538805060514-97d9cc17730c?w=800',   -- stretching
        'https://images.unsplash.com/photo-1549060279-7e168fcee0c2?w=800',      -- outdoor run
        'https://images.unsplash.com/photo-1506126613408-eca07ce68773?w=800',   -- yoga pose
        'https://images.unsplash.com/photo-1540575467063-178a50c2df87?w=800',   -- meal prep
        'https://images.unsplash.com/photo-1490645935967-10de6ba17061?w=800',   -- healthy plate
        'https://images.unsplash.com/photo-1498837167922-ddd27525d352?w=800',   -- salad
        'https://images.unsplash.com/photo-1484723091739-30a097e8f929?w=800',   -- breakfast
        'https://images.unsplash.com/photo-1565958011703-44f9829ba187?w=800',   -- smoothie bowl
        'https://images.unsplash.com/photo-1512621776951-a57141f2eefd?w=800',   -- veggies
        'https://images.unsplash.com/photo-1481627834876-b7833e8f5570?w=800',   -- book open
        'https://images.unsplash.com/photo-1544716278-ca5e3f4abd8c?w=800',      -- reading
        'https://images.unsplash.com/photo-1524995997946-a1c2e315a42f?w=800',   -- book pile
        'https://images.unsplash.com/photo-1519682337058-a94d519337bc?w=800',   -- book + coffee
        'https://images.unsplash.com/photo-1499750310107-5fef28a66643?w=800',   -- laptop work
        'https://images.unsplash.com/photo-1498050108023-c5249f4df085?w=800',   -- code screen
        'https://images.unsplash.com/photo-1555066931-4365d14bab8c?w=800',      -- code dark
        'https://images.unsplash.com/photo-1517694712202-14dd9538aa97?w=800',   -- keyboard
        'https://images.unsplash.com/photo-1587620962725-abab7fe55159?w=800',   -- dev setup
        'https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?w=800',   -- coffee cup
        'https://images.unsplash.com/photo-1509042239860-f550ce710b93?w=800',   -- latte art
        'https://images.unsplash.com/photo-1442512595331-e89e73853f31?w=800',   -- morning coffee
        'https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=800',   -- lake / nature
        'https://images.unsplash.com/photo-1501785888041-af3ef285b470?w=800',   -- mountain vista
        'https://images.unsplash.com/photo-1465146344425-f00d5f5c8f07?w=800',   -- forest
        'https://images.unsplash.com/photo-1472214103451-9374bd1c798e?w=800',   -- sunset outdoors
        'https://images.unsplash.com/photo-1508672019048-805c876b67e2?w=800',   -- hiker
        'https://images.unsplash.com/photo-1519681393784-d120267933ba?w=800',   -- snowy peak
        'https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=800',   -- canyon
        'https://images.unsplash.com/photo-1518655048521-f130df041f66?w=800',   -- journal / notebook
        'https://images.unsplash.com/photo-1455390582262-044cdead277a?w=800',   -- planning desk
        'https://images.unsplash.com/photo-1506905925346-21bda4d32df4?w=800',   -- scenic hike
        'https://images.unsplash.com/photo-1518611012118-696072aa579a?w=800',   -- kettlebell
        'https://images.unsplash.com/photo-1508739773434-c26b3d09e071?w=800',   -- treadmill
        'https://images.unsplash.com/photo-1545205597-3d9d02c29597?w=800',      -- push-ups
        'https://images.unsplash.com/photo-1594737625785-a6cbdabd333c?w=800',   -- art / painting
        'https://images.unsplash.com/photo-1513475382585-d06e58bcb0e0?w=800',   -- sketchbook
        'https://images.unsplash.com/photo-1513506003901-1e6a229e2d15?w=800',   -- art supplies
        'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=800',   -- guitar / music
        'https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?w=800',   -- piano keys
        'https://images.unsplash.com/photo-1489533119213-66a5cd877091?w=800',   -- swimming
        'https://images.unsplash.com/photo-1530143311094-34d807799e8f?w=800',   -- cycling
        'https://images.unsplash.com/photo-1517649763962-0c623066013b?w=800',   -- basketball
        'https://images.unsplash.com/photo-1526676037777-05a232554d77?w=800'    -- journaling pen
    ];
    pool_size INTEGER := 48;
    img_count INTEGER;
    idx_a INTEGER;
    idx_b INTEGER;
    idx_c INTEGER;
    media_urls_value TEXT;
BEGIN
    FOR r IN
        SELECT t.user_id, t.challenge_id, ch.number_of_days
        FROM challengesphere.user_challenges t
        JOIN challengesphere.challenges ch ON ch.id = t.challenge_id
        WHERE t.active
    LOOP
        total_days := 5 + floor(random() * LEAST(r.number_of_days, 25))::INTEGER;
        FOR day IN 1..total_days LOOP
            txt := format(descs[1 + (day % 8)], day);

            -- 60% single image, 30% two, 10% three — mirrors real social posts
            img_count := CASE
                WHEN random() < 0.6 THEN 1
                WHEN random() < 0.9 THEN 2
                ELSE 3
            END;
            idx_a := 1 + floor(random() * pool_size)::INTEGER;
            IF img_count = 1 THEN
                media_urls_value := images[idx_a];
            ELSIF img_count = 2 THEN
                idx_b := 1 + floor(random() * pool_size)::INTEGER;
                media_urls_value := images[idx_a] || ',' || images[idx_b];
            ELSE
                idx_b := 1 + floor(random() * pool_size)::INTEGER;
                idx_c := 1 + floor(random() * pool_size)::INTEGER;
                media_urls_value := images[idx_a] || ',' || images[idx_b] || ',' || images[idx_c];
            END IF;

            INSERT INTO challengesphere.submissions (
                user_id, challenge_id, day_number, description, media_urls,
                is_private, submission_time, active, glories_count, comments_count, version, created_at
            ) VALUES (
                r.user_id, r.challenge_id, day, txt,
                media_urls_value,
                false,
                NOW() - (day * INTERVAL '1 day'),
                true, 0, 0, 0,
                NOW() - (day * INTERVAL '1 day')
            );
        END LOOP;
    END LOOP;
END $$;

-- ======================== SEED GLORIES ========================
DO $$
DECLARE
    sub RECORD;
    g INTEGER;
BEGIN
    FOR sub IN
        SELECT id, user_id FROM challengesphere.submissions
        WHERE active ORDER BY id LIMIT 8000
    LOOP
        FOR i IN 1..(floor(random() * 12)::INTEGER) LOOP
            g := 1 + floor(random() * 500)::INTEGER;
            IF g != sub.user_id THEN
                INSERT INTO challengesphere.glories (submission_id, user_id, created_at, active)
                VALUES (sub.id, g, NOW() - make_interval(secs => (random() * 30 * 86400)::BIGINT), true)
                ON CONFLICT (submission_id, user_id) DO NOTHING;
            END IF;
        END LOOP;
    END LOOP;
END $$;

-- Update glories_count on submissions
UPDATE challengesphere.submissions s SET
    glories_count = (SELECT COUNT(*) FROM challengesphere.glories g WHERE g.submission_id = s.id AND g.active);

-- ======================== SEED COMMENTS ON SUBMISSIONS ========================
DO $$
DECLARE
    sub RECORD;
    commenter INTEGER;
    texts TEXT[] := ARRAY[
        'Great progress! Keep it up!', 'This is so inspiring!', 'Love seeing your journey!',
        'You got this!', 'Amazing work!', 'This motivates me so much!', 'Consistency is key!',
        'Looking great!', 'Can''t wait to see your final day!', 'You''re crushing it!',
        'So proud of you!', 'This is what dedication looks like!', 'Incredible effort!',
        'You make it look easy!', 'Goals! This is exactly what I needed to see today.'
    ];
BEGIN
    FOR sub IN
        SELECT id, user_id FROM challengesphere.submissions
        WHERE active ORDER BY id LIMIT 5000
    LOOP
        FOR i IN 1..(1 + floor(random() * 8)::INTEGER) LOOP
            commenter := 1 + floor(random() * 500)::INTEGER;
            INSERT INTO challengesphere.comments (submission_id, user_id, content, created_at, updated_at, active)
            VALUES (
                sub.id, commenter,
                texts[1 + (floor(random() * 15)::INTEGER)],
                NOW() - make_interval(secs => (random() * 25 * 86400)::BIGINT),
                NOW() - make_interval(secs => (random() * 25 * 86400)::BIGINT),
                true
            );
        END LOOP;
    END LOOP;
END $$;

-- Update comments_count on submissions
UPDATE challengesphere.submissions s SET
    comments_count = (SELECT COUNT(*) FROM challengesphere.comments c WHERE c.submission_id = s.id AND c.active);

-- ======================== SEED LEADERBOARD ENTRIES ========================
DO $$
DECLARE
    uc RECORD;
    sub_count INTEGER;
    glory_pts INTEGER;
    sc DOUBLE PRECISION;
BEGIN
    FOR uc IN
        SELECT user_id, challenge_id FROM challengesphere.user_challenges WHERE active
    LOOP
        SELECT COUNT(*), COALESCE(SUM(glories_count), 0)
        INTO sub_count, glory_pts
        FROM challengesphere.submissions
        WHERE user_id = uc.user_id AND challenge_id = uc.challenge_id AND active;

        sc := sub_count * 10.0 + glory_pts * 2.0;

        INSERT INTO challengesphere.leaderboard_entries (
            challenge_id, user_id, score, current_rank, previous_rank,
            submission_count, glory_points, streak_days, best_score,
            last_submission_at, first_submission_at,
            status, created_at, updated_at
        ) VALUES (
            uc.challenge_id, uc.user_id, sc, NULL, NULL,
            sub_count, glory_pts, LEAST(sub_count, 14), sc,
            NOW() - make_interval(secs => (random() * 5 * 86400)::BIGINT),
            NOW() - make_interval(secs => (random() * 60 * 86400)::BIGINT),
            'ACTIVE', NOW() - INTERVAL '30 days', NOW()
        )
        ON CONFLICT (challenge_id, user_id) DO NOTHING;
    END LOOP;
END $$;

-- Assign ranks per challenge
DO $$
DECLARE
    ch RECORD;
    rk INTEGER;
    en RECORD;
BEGIN
    FOR ch IN SELECT DISTINCT challenge_id FROM challengesphere.leaderboard_entries LOOP
        rk := 1;
        FOR en IN
            SELECT id FROM challengesphere.leaderboard_entries
            WHERE challenge_id = ch.challenge_id ORDER BY score DESC
        LOOP
            UPDATE challengesphere.leaderboard_entries SET current_rank = rk WHERE id = en.id;
            rk := rk + 1;
        END LOOP;
    END LOOP;
END $$;

-- ======================== SEED NOTIFICATIONS ========================
DO $$
DECLARE
    i INTEGER;
    types TEXT[] := ARRAY['FOLLOW','GLORY','COMMENT','CHALLENGE_INVITE','MILESTONE'];
    msgs  TEXT[] := ARRAY[
        'Someone started following you!',
        'Someone gave glory to your submission!',
        'Someone commented on your submission!',
        'You''ve been invited to a challenge!',
        'Congratulations on reaching a milestone!'
    ];
BEGIN
    FOR i IN 1..2000 LOOP
        INSERT INTO challengesphere.notifications (user_id, type, message, sent_at)
        VALUES (
            1 + floor(random() * 500)::INTEGER,
            types[1 + (i % 5)],
            msgs[1 + (i % 5)],
            NOW() - make_interval(secs => (random() * 60 * 86400)::BIGINT)
        );
    END LOOP;
END $$;

-- ======================== SEED CHALLENGE INVITES ========================
DO $$
DECLARE
    i INTEGER;
BEGIN
    FOR i IN 1..50 LOOP
        INSERT INTO challengesphere.challenge_invites (
            challenge_id, created_by, invite_code,
            expires_at, max_uses, use_count, active, created_at
        ) VALUES (
            1 + (i % 50),
            1 + (i % 500),
            UPPER(SUBSTRING(MD5(i::TEXT || 'invite') FROM 1 FOR 8)),
            NOW() + (30 + (i % 60)) * INTERVAL '1 day',
            10 + (i % 40),
            i % 6,
            true,
            NOW() - (i * INTERVAL '1 day')
        );
    END LOOP;
END $$;

-- ======================== UPDATE USER CHALLENGE COUNTS ========================
UPDATE challengesphere.users u SET
    challenges_created  = (SELECT COUNT(*) FROM challengesphere.challenges c  WHERE c.creator_id  = u.id),
    challenges_accepted = (SELECT COUNT(*) FROM challengesphere.user_challenges t WHERE t.user_id = u.id AND t.active);

-- ======================== VERIFICATION ========================
SELECT tbl, cnt FROM (
    SELECT 'users'              AS tbl, COUNT(*) AS cnt FROM challengesphere.users
    UNION ALL SELECT 'user_preferences',    COUNT(*) FROM challengesphere.user_preferences
    UNION ALL SELECT 'challenges',          COUNT(*) FROM challengesphere.challenges
    UNION ALL SELECT 'user_challenges',     COUNT(*) FROM challengesphere.user_challenges
    UNION ALL SELECT 'follows',             COUNT(*) FROM challengesphere.follows
    UNION ALL SELECT 'submissions',         COUNT(*) FROM challengesphere.submissions
    UNION ALL SELECT 'glories',             COUNT(*) FROM challengesphere.glories
    UNION ALL SELECT 'comments',            COUNT(*) FROM challengesphere.comments
    UNION ALL SELECT 'leaderboard_entries', COUNT(*) FROM challengesphere.leaderboard_entries
    UNION ALL SELECT 'notifications',       COUNT(*) FROM challengesphere.notifications
    UNION ALL SELECT 'challenge_invites',   COUNT(*) FROM challengesphere.challenge_invites
) q ORDER BY tbl;
