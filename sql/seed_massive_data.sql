-- ============================================================
-- ChallengeSphere MASSIVE Dummy Data Seed Script
-- Generates ~500 users, 50 challenges, thousands of submissions, likes, glories, etc.
-- Run with: psql -U postgres -d postgres -f sql/seed_massive_data.sql
-- ============================================================

-- Enable UUID generation
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ======================== CLEAN SLATE ========================
DELETE FROM challengesphere.invite_usages;
DELETE FROM challengesphere.challenge_invites;
DELETE FROM challengesphere.glories;
DELETE FROM challengesphere.likes;
DELETE FROM challengesphere.comments;
DELETE FROM challengesphere.submissions;
DELETE FROM challengesphere.user_challenges;
DELETE FROM challengesphere.leaderboard_entries;
DELETE FROM challengesphere.notifications;
DELETE FROM challengesphere.reports;
DELETE FROM challengesphere.posts;
DELETE FROM challengesphere.follows;
DELETE FROM challengesphere.challenges;
DELETE FROM challengesphere.users;
DELETE FROM challengesphere.user_preferences;

-- Reset sequences
SELECT setval('challengesphere.users_id_seq', 1);
SELECT setval('challengesphere.challenges_id_seq', 1);
SELECT setval('challengesphere.submissions_id_seq', 1);

-- ======================== SEED USERS (500 users) ========================
DO $$
DECLARE
    i INTEGER;
    first_names TEXT[] := ARRAY['Alex', 'Jordan', 'Taylor', 'Morgan', 'Casey', 'Riley', 'Quinn', 'Avery', 'Cameron', 'Drew',
                                'Emma', 'Liam', 'Olivia', 'Noah', 'Ava', 'Ethan', 'Sophia', 'Mason', 'Isabella', 'William',
                                'Mia', 'James', 'Charlotte', 'Benjamin', 'Amelia', 'Lucas', 'Harper', 'Henry', 'Evelyn', 'Alexander',
                                'Luna', 'Sebastian', 'Camila', 'Jack', 'Aria', 'Daniel', 'Scarlett', 'Michael', 'Madison', 'Owen',
                                'Layla', 'Penelope', 'Jayden', 'Theodore', 'Aaliyah', 'Aiden', 'Ella', 'Ryan', 'Zoey', 'Maya'];
    activities TEXT[] := ARRAY['Fitness enthusiast', 'Bookworm', 'Tech geek', 'Yoga lover', 'Runner', 'Coder', 'Artist', 'Writer',
                            'Music lover', 'Nature explorer', 'Foodie', 'Traveler', 'Gamer', 'Photographer', 'Chef'];
    cities TEXT[] := ARRAY['New York', 'Los Angeles', 'Chicago', 'Houston', 'Phoenix', 'San Antonio', 'San Diego', 'Dallas', 'Austin', 'San Francisco',
                          'Seattle', 'Denver', 'Boston', 'Portland', 'Atlanta', 'Miami', 'Minneapolis', 'Detroit', 'Las Vegas', 'Nashville'];
BEGIN
    FOR i IN 1..500 LOOP
        INSERT INTO challengesphere.users (
            email_id, phone, username, hashed_password, genuineness_score, active,
            challenges_created, challenges_accepted, followers, following, premium_user, notifications,
            bio, profile_pic_url, created_at, last_login_at
        ) VALUES (
            'user' || i || '@example.com',
            '98765' || LPAD(i::TEXT, 5, '0'),
            'user' || i || '_' || first_names[1 + (i % 50)],
            '$2a$10$N9qo8uLOickgx2ZMRZoMy.MQDxBCEZ5K.yV.gfgKPLyTSIFZWHWjK',
            80 + (i % 20),
            true,
            0, 0,
            0, 0,
            CASE WHEN i % 10 = 0 THEN 1 ELSE 0 END,
            1,
            activities[1 + (i % 15)] || ' | ' || cities[1 + (i % 20)],
            'https://i.pravatar.cc/400?img=' || (i % 70 + 1),
            NOW() - (random() * 365 || ' days')::INTERVAL,
            NOW() - (random() * 30 || ' days')::INTERVAL
        );
    END LOOP;
END $$;

-- ======================== SEED CHALLENGES (50 challenges) ========================
DO $$
DECLARE
    i INTEGER;
    challenge_titles TEXT[] := ARRAY[
        '30-Day Fitness Challenge', '100 Days of Meditation', 'Read 52 Books in a Year', 'Learn a New Language',
        'Daily Coding Practice', 'Run 5K Every Day', 'No Sugar Challenge', 'Daily Journaling',
        'Wake Up at 5 AM', '100 Days of Code', 'Yoga Every Morning', 'Drink 3L Water Daily',
        '30-Day Plank Challenge', 'Learn Photography', 'Read 30 Minutes Daily', 'No Social Media',
        'Daily Stretching', 'Learn Guitar', 'Cook Every Meal', 'Walk 10K Steps',
        'Gratitude Journal', 'Learn a New Recipe Weekly', '30-Day Ab Challenge', 'Read Non-Fiction',
        'Daily Meditation', 'Run a Marathon', 'Learn Spanish', 'Write 500 Words Daily',
        'No Caffeine Challenge', 'Morning Yoga', 'Evening Walks', 'Learn Piano',
        'Daily Pushups', 'Read Classics', 'No Junk Food', 'Learn Drawing',
        'Sleep 8 Hours', 'Learn Web Development', 'Daily Cold Shower', 'Read Biography',
        'Learn Machine Learning', '30-Day Squat Challenge', 'Read Self-Help', 'Learn Docker',
        'Daily Visualization', 'Run Half Marathon', 'Read History', 'Learn React',
        'No Alcohol Month', 'Learn Python'
    ];
    categories TEXT[] := ARRAY['Fitness,Health', 'Mindfulness,Wellness', 'Education,Learning', 'Tech,Programming', 
                              'Lifestyle,Hobbies', 'Diet,Nutrition', 'Sports,Running', 'Arts,Creativity'];
    hashtags_arrays TEXT[] := ARRAY[
        '#fitness,#health,#workout', '#meditation,#mindfulness,#wellness', '#reading,#books,#education',
        '#language,#learning,#spanish', '#coding,#programming,#dev', '#running,#marathon,#fitness',
        '#health,#nutrition,#diet', '#journaling,#writing,#selfcare', '#morning,#productivity,#habits',
        '#100daysofcode,#coding,#dev', '#yoga,#fitness,#wellness', '#hydration,#health,#water',
        '#fitness,#plank,#core', '#photography,#art,#creative', '#reading,#books,#daily',
        '#socialmedia,#detox,#wellness', '#stretching,#flexibility,#health', '#music,#guitar,#learning',
        '#cooking,#food,#healthy', '#walking,#steps,#fitness', '#gratitude,#journal,#wellness',
        '#cooking,#recipes,#skills', '#fitness,#abs,#core', '#reading,#books,#nonfiction',
        '#meditation,#calm,#peace', '#running,#marathon,#training', '#spanish,#language,#learning',
        '#writing,#creativity,#daily', '#caffeine,#health,#detox', '#yoga,#morning,#routine',
        '#walking,#evening,#relax', '#piano,#music,#learning', '#pushups,#fitness,#strength',
        '#reading,#classics,#literature', '#nutrition,#health,#clean', '#drawing,#art,#sketch',
        '#sleep,#health,#rest', '#webdev,#coding,#frontend', '#coldshower,#health,#habit',
        '#biography,#reading,#inspiration', '#ml,#ai,#programming', '#squats,#fitness,#legs',
        '#selfhelp,#books,#growth', '#docker,#devops,#containers', '#visualization,#goals,#success',
        '#halfmarathon,#running,#marathon', '#history,#reading,#learning', '#react,#javascript,#webdev',
        '#sober,#health,#challenge', '#python,#programming,#coding'
    ];
BEGIN
    FOR i IN 1..50 LOOP
        INSERT INTO challengesphere.challenges (
            hashtags, categories, title, description, benefits, private, start_date, active,
            creator_id, current_owner_id, number_of_days, created_at, cover_photo_urls
        ) VALUES (
            hashtags_arrays[i],
            categories[1 + (i % 8)],
            challenge_titles[i],
            'Challenge description for ' || challenge_titles[i],
            'Benefits: Improved health and wellbeing',
            CASE WHEN i % 5 = 0 THEN true ELSE false END,
            CURRENT_DATE - (i % 30),
            true,
            1 + (i % 500),
            1 + (i % 500),
            21 + (i % 40),
            NOW() - (i * INTERVAL '1 day'),
            'https://images.unsplash.com/photo-1571019614242-c5c5dee9f50b?w=800'
        );
    END LOOP;
END $$;

-- ======================== SEED USER_CHALLENGES ========================
DO $$
DECLARE
    user_id_var INTEGER;
    challenge_id_var INTEGER;
    rand_challenges INTEGER;
    existing_count INTEGER;
BEGIN
    FOR user_id_var IN 1..500 LOOP
        rand_challenges := 3 + floor(random() * 6)::INTEGER;
        FOR i IN 1..rand_challenges LOOP
            challenge_id_var := 1 + floor(random() * 50)::INTEGER;
            SELECT COUNT(*) INTO existing_count 
            FROM challengesphere.user_challenges 
            WHERE user_id = user_id_var AND challenge_id = challenge_id_var;
            IF existing_count = 0 THEN
                INSERT INTO challengesphere.user_challenges (user_id, challenge_id, joined_at, completed, active)
                VALUES (
                    user_id_var,
                    challenge_id_var,
                    NOW() - (random() * 60 || ' days')::INTERVAL,
                    CASE WHEN random() > 0.7 THEN true ELSE false END,
                    true
                );
            END IF;
        END LOOP;
    END LOOP;
END $$;

-- ======================== SEED FOLLOWS ========================
DO $$
DECLARE
    follower_var INTEGER;
    following_var INTEGER;
    existing_count INTEGER;
BEGIN
    FOR follower_var IN 1..500 LOOP
        FOR i IN 1..(10 + floor(random() * 40)::INTEGER) LOOP
            following_var := 1 + floor(random() * 500)::INTEGER;
            IF following_var != follower_var THEN
                SELECT COUNT(*) INTO existing_count 
                FROM challengesphere.follows 
                WHERE follower_id = follower_var AND following_id = following_var;
                IF existing_count = 0 THEN
                    INSERT INTO challengesphere.follows (follower_id, following_id, created_at, active)
                    VALUES (follower_var, following_var, NOW() - (random() * 180 || ' days')::INTERVAL, true);
                END IF;
            END IF;
        END LOOP;
    END LOOP;
END $$;

-- Update follower/following counts
UPDATE challengesphere.users u SET
    followers = (SELECT COUNT(*) FROM challengesphere.follows f WHERE f.following_id = u.id AND f.active = true),
    following = (SELECT COUNT(*) FROM challengesphere.follows f WHERE f.follower_id = u.id AND f.active = true);

-- ======================== SEED SUBMISSIONS ========================
DO $$
DECLARE
    uc_record RECORD;
    num_days INTEGER;
    day_num INTEGER;
    desc_text TEXT;
BEGIN
    FOR uc_record IN 
        SELECT uc.user_id, uc.challenge_id, c.number_of_days
        FROM challengesphere.user_challenges uc
        JOIN challengesphere.challenges c ON c.id = uc.challenge_id
        WHERE uc.active = true
    LOOP
        num_days := 5 + floor(random() * LEAST(uc_record.number_of_days, 30))::INTEGER;
        FOR day_num IN 1..num_days LOOP
            desc_text := 'Day ' || day_num || ' complete! ' || CASE floor(random() * 5)::INTEGER
                WHEN 0 THEN 'Feeling great!'
                WHEN 1 THEN 'Consistency is key!'
                WHEN 2 THEN 'Progress not perfection!'
                WHEN 3 THEN 'Keep pushing forward!'
                ELSE 'Another win!'
            END;
            INSERT INTO challengesphere.submissions (
                user_id, challenge_id, day_number, description, media_urls, is_private,
                submission_time, active, glories_count, comments_count, version, created_at
            ) VALUES (
                uc_record.user_id, uc_record.challenge_id, day_num, desc_text,
                'https://images.unsplash.com/photo-1571019614242-c5c5dee9f50b?w=800',
                false,
                NOW() - (day_num * INTERVAL '1 day'),
                true, 0, 0, 0,
                NOW() - (day_num * INTERVAL '1 day')
            );
        END LOOP;
    END LOOP;
END $$;

-- ======================== SEED POSTS ========================
DO $$
DECLARE
    i INTEGER;
BEGIN
    FOR i IN 1..300 LOOP
        INSERT INTO challengesphere.posts (
            id, user_id, challenge_id, title, content, media_url, created_at,
            active, likes_count, comments_count
        ) VALUES (
            gen_random_uuid(),
            1 + (i % 500),
            1 + (i % 50),
            'Post #' || i || ' - Check out my progress!',
            'Sharing my journey! Day ' || (i % 30 + 1) || ' of my challenge.',
            'https://images.unsplash.com/photo-1571019614242-c5c5dee9f50b?w=800',
            NOW() - (i * INTERVAL '1 hour'),
            true,
            5 + floor(random() * 50)::INTEGER,
            1 + floor(random() * 20)::INTEGER
        );
    END LOOP;
END $$;

-- ======================== SEED LIKES ON POSTS ========================
DO $$
DECLARE
    post_record RECORD;
    liker_id INTEGER;
BEGIN
    FOR post_record IN SELECT id FROM challengesphere.posts LOOP
        FOR i IN 1..(5 + floor(random() * 25)::INTEGER) LOOP
            liker_id := 1 + floor(random() * 500)::INTEGER;
            BEGIN
                INSERT INTO challengesphere.likes (post_id, user_id, created_at, active)
                VALUES (post_record.id, liker_id, NOW() - (random() * 30 || ' days')::INTERVAL, true)
                ON CONFLICT (post_id, user_id) DO NOTHING;
            EXCEPTION WHEN OTHERS THEN NULL;
            END;
        END LOOP;
    END LOOP;
END $$;

-- ======================== SEED GLORIES ON SUBMISSIONS ========================
DO $$
DECLARE
    submission_record RECORD;
    glorier_id INTEGER;
BEGIN
    FOR submission_record IN SELECT id, user_id FROM challengesphere.submissions LIMIT 5000 LOOP
        FOR i IN 1..(floor(random() * 10)::INTEGER) LOOP
            glorier_id := 1 + floor(random() * 500)::INTEGER;
            IF glorier_id != submission_record.user_id THEN
                BEGIN
                    INSERT INTO challengesphere.glories (submission_id, user_id, created_at, active)
                    VALUES (submission_record.id, glorier_id, NOW() - (random() * 30 || ' days')::INTERVAL, true)
                    ON CONFLICT (submission_id, user_id) DO NOTHING;
                EXCEPTION WHEN OTHERS THEN NULL;
                END;
            END IF;
        END LOOP;
    END LOOP;
END $$;

-- ======================== SEED COMMENTS ON POSTS ========================
DO $$
DECLARE
    post_record RECORD;
    commenter_id INTEGER;
    comment_texts TEXT[] := ARRAY[
        'Great progress! Keep it up!', 'This is so inspiring!', 'Love seeing your journey!',
        'You got this!', 'Amazing work!', 'This motivates me so much!', 'Consistency is key!',
        'Looking great!', 'Can''t wait to see day 30!', 'You''re crushing it!'
    ];
BEGIN
    FOR post_record IN SELECT id FROM challengesphere.posts LOOP
        FOR i IN 1..(1 + floor(random() * 10)::INTEGER) LOOP
            commenter_id := 1 + floor(random() * 500)::INTEGER;
            INSERT INTO challengesphere.comments (post_id, user_id, content, created_at, updated_at, active)
            VALUES (
                post_record.id, commenter_id,
                comment_texts[1 + (i % 10)],
                NOW() - (random() * 30 || ' days')::INTERVAL,
                NOW() - (random() * 30 || ' days')::INTERVAL,
                true
            );
        END LOOP;
    END LOOP;
END $$;

-- ======================== SEED LEADERBOARD ENTRIES ========================
DO $$
DECLARE
    uc_record RECORD;
    submission_count INTEGER;
    glory_points INTEGER;
    score FLOAT;
BEGIN
    FOR uc_record IN 
        SELECT uc.user_id, uc.challenge_id
        FROM challengesphere.user_challenges uc
        WHERE uc.active = true
    LOOP
        SELECT COUNT(*), COALESCE(SUM(s.glories_count), 0)
        INTO submission_count, glory_points
        FROM challengesphere.submissions s
        WHERE s.user_id = uc_record.user_id AND s.challenge_id = uc_record.challenge_id AND s.active = true;
        
        score := submission_count * 10.0 + glory_points * 1.0;
        
        INSERT INTO challengesphere.leaderboard_entries (
            challenge_id, user_id, score, current_rank, previous_rank,
            submission_count, glory_points, streak_days, best_score,
            status, created_at, updated_at
        ) VALUES (
            uc_record.challenge_id, uc_record.user_id, score, NULL, NULL,
            submission_count, glory_points, LEAST(submission_count, 7), score,
            'ACTIVE', NOW() - INTERVAL '7 days', NOW()
        ) ON CONFLICT (challenge_id, user_id) DO NOTHING;
    END LOOP;
END $$;

-- Update ranks for all leaderboards
DO $$
DECLARE
    challenge_record RECORD;
    rank_counter INTEGER;
BEGIN
    FOR challenge_record IN SELECT DISTINCT challenge_id FROM challengesphere.leaderboard_entries LOOP
        rank_counter := 1;
        FOR entry IN 
            SELECT id FROM challengesphere.leaderboard_entries
            WHERE challenge_id = challenge_record.challenge_id
            ORDER BY score DESC
        LOOP
            UPDATE challengesphere.leaderboard_entries SET current_rank = rank_counter WHERE id = entry.id;
            rank_counter := rank_counter + 1;
        END LOOP;
    END LOOP;
END $$;

-- ======================== SEED NOTIFICATIONS ========================
DO $$
DECLARE
    i INTEGER;
    notif_types TEXT[] := ARRAY['FOLLOW', 'GLORY', 'COMMENT', 'CHALLENGE_INVITE', 'MILESTONE'];
    notif_messages TEXT[] := ARRAY[
        'Someone started following you!', 'Someone gave glory to your submission!',
        'Someone commented on your post!', 'You''ve been invited to a challenge!',
        'Congratulations on reaching a milestone!'
    ];
BEGIN
    FOR i IN 1..1000 LOOP
        INSERT INTO challengesphere.notifications (user_id, type, message, sent_at)
        VALUES (
            1 + floor(random() * 500)::INTEGER,
            notif_types[1 + (i % 5)],
            notif_messages[1 + (i % 5)],
            NOW() - (random() * 30 || ' days')::INTERVAL
        );
    END LOOP;
END $$;

-- ======================== SEED CHALLENGE INVITES ========================
DO $$
DECLARE
    i INTEGER;
    invite_codes TEXT[] := ARRAY['CHAL2026A', 'FIT2026B', 'READ26C', 'CODE26D', 'YOGA26E', 'RUN26F', 'WELL26G', 'HEAL26H',
                                 'GROW26I', 'LEARN26J', 'FITNESS27', 'HEALTH27', 'CHALLEN28', 'ACTIVE28', 'STRONG29'];
BEGIN
    FOR i IN 1..30 LOOP
        INSERT INTO challengesphere.challenge_invites (
            challenge_id, created_by, invite_code, expires_at, max_uses, use_count, active, created_at
        ) VALUES (
            1 + (i % 50),
            1 + (i % 500),
            invite_codes[1 + (i % 15)] || i,
            NOW() + (30 + (i % 60)) * INTERVAL '1 day',
            10 + (i % 40),
            i % 5,
            true,
            NOW() - (i * INTERVAL '1 day')
        );
    END LOOP;
END $$;

-- Update challenge counts for users
UPDATE challengesphere.users u SET
    challenges_created = (SELECT COUNT(*) FROM challengesphere.challenges c WHERE c.creator_id = u.id),
    challenges_accepted = (SELECT COUNT(*) FROM challengesphere.user_challenges uc WHERE uc.user_id = u.id AND uc.active = true);

-- ======================== VERIFICATION ========================
SELECT 'users' AS tbl, COUNT(*) AS cnt FROM challengesphere.users
UNION ALL SELECT 'challenges', COUNT(*) FROM challengesphere.challenges
UNION ALL SELECT 'user_challenges', COUNT(*) FROM challengesphere.user_challenges
UNION ALL SELECT 'follows', COUNT(*) FROM challengesphere.follows
UNION ALL SELECT 'posts', COUNT(*) FROM challengesphere.posts
UNION ALL SELECT 'submissions', COUNT(*) FROM challengesphere.submissions
UNION ALL SELECT 'comments', COUNT(*) FROM challengesphere.comments
UNION ALL SELECT 'likes', COUNT(*) FROM challengesphere.likes
UNION ALL SELECT 'glories', COUNT(*) FROM challengesphere.glories
UNION ALL SELECT 'leaderboard', COUNT(*) FROM challengesphere.leaderboard_entries
UNION ALL SELECT 'notifications', COUNT(*) FROM challengesphere.notifications
UNION ALL SELECT 'challenge_invites', COUNT(*) FROM challengesphere.challenge_invites
ORDER BY tbl;
