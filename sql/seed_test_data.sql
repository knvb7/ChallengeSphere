-- ============================================================
-- ChallengeSphere Test Data Seed Script
-- Based on actual DB schema (challengesphereschema.sql)
-- Run with: psql -U postgres -d postgres -f sql/seed_test_data.sql
-- ============================================================
-- Password hash = BCrypt for "test12345"

BEGIN;

-- ======================== CLEAN SLATE ========================
TRUNCATE TABLE challengesphere.invite_usages       CASCADE;
TRUNCATE TABLE challengesphere.challenge_invites   CASCADE;
TRUNCATE TABLE challengesphere.glories             CASCADE;
TRUNCATE TABLE challengesphere.likes               CASCADE;
TRUNCATE TABLE challengesphere.comments            CASCADE;
TRUNCATE TABLE challengesphere.submissions         CASCADE;
TRUNCATE TABLE challengesphere.user_challenges     CASCADE;
TRUNCATE TABLE challengesphere.leaderboard_entries CASCADE;
TRUNCATE TABLE challengesphere.notifications       CASCADE;
TRUNCATE TABLE challengesphere.reports             CASCADE;
TRUNCATE TABLE challengesphere.posts               CASCADE;
TRUNCATE TABLE challengesphere.follows             CASCADE;
TRUNCATE TABLE challengesphere.challenges          CASCADE;
TRUNCATE TABLE challengesphere.user_preferences    CASCADE;
TRUNCATE TABLE challengesphere.users               CASCADE;

-- ======================== USERS (IDs 1-4) ========================
INSERT INTO challengesphere.users (id, email_id, phone, username, hashed_password, genuineness_score, active, challenges_created, challenges_accepted, followers, following, premium_user, notifications, bio, profile_pic_url)
OVERRIDING SYSTEM VALUE
VALUES
  (1, 'mihir@example.com',  '9876543210', 'mihir',  '$2a$12$LJ3m4ys3Gzf0UOHnOdKMi.xGYqFYON6WuB6UxLpSICzGnBFKfGOuS', 100, true, 3, 4, 3, 1, 0, 1, 'Founder of ChallengeSphere 🚀',              'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=400&h=400&fit=crop&crop=face'),
  (2, 'arjun@example.com',  '9876543211', 'arjun',  '$2a$12$LJ3m4ys3Gzf0UOHnOdKMi.xGYqFYON6WuB6UxLpSICzGnBFKfGOuS', 95,  true, 1, 3, 2, 1, 0, 1, 'Fitness enthusiast & coder',                 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=400&h=400&fit=crop&crop=face'),
  (3, 'priya@example.com',  '9876543212', 'priya',  '$2a$12$LJ3m4ys3Gzf0UOHnOdKMi.xGYqFYON6WuB6UxLpSICzGnBFKfGOuS', 90,  true, 1, 4, 1, 2, 1, 1, 'Yoga lover 🧘‍♀️ | 30-day streak queen',   'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=400&h=400&fit=crop&crop=face'),
  (4, 'rahul@example.com',  '9876543213', 'rahul',  '$2a$12$LJ3m4ys3Gzf0UOHnOdKMi.xGYqFYON6WuB6UxLpSICzGnBFKfGOuS', 85,  true, 0, 2, 0, 2, 0, 1, 'Just here for the challenges',              'https://images.unsplash.com/photo-1506794778202-cad84cf45f1d?w=400&h=400&fit=crop&crop=face');

SELECT setval('challengesphere.users_id_seq', 4);

-- ======================== CHALLENGES (IDs 1-5) ========================
INSERT INTO challengesphere.challenges (id, hashtags, categories, title, description, benefits, private, start_date, active, creator_id, current_owner_id, number_of_days)
OVERRIDING SYSTEM VALUE
VALUES
  (1, '#fitness,#pushups',        'Fitness,Health',      '100 Pushups Challenge',  'Do 100 pushups every day for 30 days. Track your progress and push your limits!',   'Builds upper body strength, discipline, and consistency', false, '2026-03-01', true, 1, 1, 30),
  (2, '#reading,#books',          'Education,Lifestyle', '30 Days of Reading',     'Read at least 30 pages every day for a month. Share your book picks and progress!', 'Improves focus, knowledge, and vocabulary',               false, '2026-03-05', true, 1, 1, 30),
  (3, '#coding,#leetcode',        'Tech,Education',      'LeetCode Daily Grind',   'Solve one LeetCode problem every day. Easy, Medium, or Hard — your choice!',       'Sharpens problem-solving and interview readiness',        false, '2026-03-10', true, 2, 2, 60),
  (4, '#meditation,#mindfulness', 'Health,Wellness',     '21-Day Meditation',      'Meditate for at least 10 minutes daily. Build inner calm and focus.',               'Reduces stress, improves focus and emotional health',     true,  '2026-03-15', true, 3, 3, 21),
  (5, '#nosugar,#health',         'Health,Diet',         'No Sugar for 14 Days',   'Cut out added sugar completely for 2 weeks. Document your meals and how you feel.', 'Better energy levels, clearer skin, weight management',   false, '2026-03-12', true, 1, 1, 14);

SELECT setval('challengesphere.challenges_id_seq', 5);

-- ======================== USER_CHALLENGES ========================
INSERT INTO challengesphere.user_challenges (user_id, challenge_id, joined_at, completed, active)
VALUES
  (1, 1, NOW() - INTERVAL '16 days', false, true),
  (1, 2, NOW() - INTERVAL '12 days', false, true),
  (1, 3, NOW() - INTERVAL '7 days',  false, true),
  (1, 5, NOW() - INTERVAL '5 days',  false, true),
  (2, 1, NOW() - INTERVAL '15 days', false, true),
  (2, 2, NOW() - INTERVAL '10 days', false, true),
  (2, 3, NOW() - INTERVAL '7 days',  false, true),
  (3, 1, NOW() - INTERVAL '14 days', false, true),
  (3, 3, NOW() - INTERVAL '6 days',  false, true),
  (3, 4, NOW() - INTERVAL '2 days',  false, true),
  (3, 5, NOW() - INTERVAL '4 days',  false, true),
  (4, 1, NOW() - INTERVAL '13 days', false, true),
  (4, 2, NOW() - INTERVAL '9 days',  false, true);

-- ======================== FOLLOWS ========================
INSERT INTO challengesphere.follows (follower_id, following_id, active)
VALUES
  (2, 1, true),
  (3, 1, true),
  (4, 1, true),
  (1, 2, true),
  (3, 2, true),
  (4, 3, true);

-- ======================== POSTS (UUID PKs) ========================
INSERT INTO challengesphere.posts (id, user_id, challenge_id, title, content, media_url, active, likes_count, comments_count)
VALUES
  ('a1111111-1111-1111-1111-111111111101', 1, 1, 'Day 1 — 100 Pushups!',
   'Day 1 done! 100 pushups in 4 sets. Arms are already sore 💪',
   'https://images.unsplash.com/photo-1571019614242-c5c5dee9f50b?w=800',
   true, 3, 2),
  ('a1111111-1111-1111-1111-111111111102', 1, 1, 'Day 2 — Getting Faster',
   'Day 2 — got faster! 3 sets this time. Feeling the pump.',
   'https://images.unsplash.com/photo-1583454110551-21f2fa2afe61?w=800',
   true, 2, 1),
  ('a1111111-1111-1111-1111-111111111103', 2, 1, 'Pushups Before Breakfast 🔥',
   'Starting strong! 100 pushups done before breakfast!',
   'https://images.unsplash.com/photo-1534438327276-14e5300c3a48?w=800',
   true, 2, 1),
  ('a1111111-1111-1111-1111-111111111104', 3, 1, 'Modified Pushups — Day 1',
   'Day 1! Did modified pushups to start. Progress not perfection 🙏',
   'https://images.unsplash.com/photo-1518611012118-696072aa579a?w=800',
   true, 4, 3),
  ('a2222222-2222-2222-2222-222222222201', 1, 2, 'Atomic Habits — 40 Pages',
   'Reading "Atomic Habits" by James Clear. 40 pages today 📖',
   'https://images.unsplash.com/photo-1512820790803-83ca734da794?w=800',
   true, 2, 2),
  ('a2222222-2222-2222-2222-222222222202', 2, 2, 'Deep Work — Started!',
   'Starting with "Deep Work" by Cal Newport. Great so far!',
   'https://images.unsplash.com/photo-1524995997946-a1c2e315a42f?w=800',
   true, 3, 1),
  ('a3333333-3333-3333-3333-333333333301', 1, 3, 'Two Sum — Solved!',
   'Solved Two Sum (Easy). Classic warm-up problem. HashMap FTW.',
   'https://images.unsplash.com/photo-1555066931-4365d14bab8c?w=800',
   true, 1, 1),
  ('a3333333-3333-3333-3333-333333333302', 2, 3, 'Merge Intervals — Medium',
   'Solved Merge Intervals (Medium). Took 25 mins. Not bad!',
   'https://images.unsplash.com/photo-1542831371-29b0f74f9713?w=800',
   true, 1, 1),
  ('a3333333-3333-3333-3333-333333333303', 3, 3, 'Valid Parentheses ✓',
   'Solved Valid Parentheses. Stack problems are fun!',
   'https://images.unsplash.com/photo-1607705703571-c5a8695f18f6?w=800',
   true, 2, 1),
  ('a4444444-4444-4444-4444-444444444401', 3, 4, '10 Minutes of Calm',
   '10 min guided meditation on Headspace. Felt so peaceful 🧘',
   'https://images.unsplash.com/photo-1506126613408-eca07ce68773?w=800',
   true, 1, 0),
  ('a5555555-5555-5555-5555-555555555501', 1, 5, 'No Sugar Day 1',
   'Day 1 no sugar. Had oats, eggs, and black coffee. Cravings are real!',
   'https://images.unsplash.com/photo-1490645935967-10de6ba17061?w=800',
   true, 2, 1),
  ('a5555555-5555-5555-5555-555555555502', 3, 5, 'Swapped Dessert for Fruit',
   'No sugar day 1. Swapped dessert for fruit. Surprisingly easy!',
   'https://images.unsplash.com/photo-1610832958506-aa56368176cf?w=800',
   true, 3, 2);

-- ======================== SUBMISSIONS ========================
INSERT INTO challengesphere.submissions (id, user_id, challenge_id, day_number, description, media_urls, is_private, submission_time, active, glories_count, comments_count, version)
OVERRIDING SYSTEM VALUE
VALUES
  (1,  1, 1, 1, 'Day 1 done! 100 pushups in 4 sets. Arms are already sore 💪',
   'https://images.unsplash.com/photo-1571019614242-c5c5dee9f50b?w=800,https://images.unsplash.com/photo-1598971639058-fab3c3109a00?w=800',
   false, NOW() - INTERVAL '15 days', true, 3, 2, 0),
  (2,  1, 1, 2, 'Day 2 — got faster! 3 sets this time. Feeling the pump.',
   'https://images.unsplash.com/photo-1583454110551-21f2fa2afe61?w=800',
   false, NOW() - INTERVAL '14 days', true, 2, 1, 0),
  (3,  1, 1, 3, 'Day 3. Muscle memory kicking in. 100 in under 8 minutes!',
   'https://images.unsplash.com/photo-1601422407692-ec4eeec1d9b3?w=800,https://picsum.photos/seed/pushup3/800/600',
   false, NOW() - INTERVAL '13 days', true, 1, 0, 0),
  (4,  2, 1, 1, 'Starting strong 🔥 100 pushups done before breakfast!',
   'https://images.unsplash.com/photo-1534438327276-14e5300c3a48?w=800',
   false, NOW() - INTERVAL '14 days', true, 2, 1, 0),
  (5,  2, 1, 2, 'Day 2 — added diamond pushups for extra challenge',
   'https://images.unsplash.com/photo-1574680096145-d05b474e2155?w=800,https://images.unsplash.com/photo-1517836357463-d25dfeac3438?w=800',
   false, NOW() - INTERVAL '13 days', true, 3, 2, 0),
  (6,  3, 1, 1, 'Day 1! Did modified pushups to start. Progress not perfection 🙏',
   'https://images.unsplash.com/photo-1518611012118-696072aa579a?w=800,https://images.unsplash.com/photo-1544367567-0f2fcb009e0b?w=800',
   false, NOW() - INTERVAL '13 days', true, 4, 3, 0),
  (7,  4, 1, 1, 'Let''s go!! 100 pushups in 5 sets. Hardest thing I''ve done.',
   'https://images.unsplash.com/photo-1581009146145-b5ef050c2e1e?w=800',
   false, NOW() - INTERVAL '12 days', true, 1, 1, 0),
  (8,  1, 2, 1, 'Reading "Atomic Habits" by James Clear. 40 pages today 📖',
   'https://images.unsplash.com/photo-1512820790803-83ca734da794?w=800,https://images.unsplash.com/photo-1544947950-fa07a98d237f?w=800',
   false, NOW() - INTERVAL '11 days', true, 2, 2, 0),
  (9,  1, 2, 2, 'Finished Chapter 3 of Atomic Habits. The 1% rule is 🔑',
   'https://images.unsplash.com/photo-1507842217343-583bb7270b66?w=800',
   false, NOW() - INTERVAL '10 days', true, 1, 0, 0),
  (10, 2, 2, 1, 'Starting with "Deep Work" by Cal Newport. Great so far!',
   'https://images.unsplash.com/photo-1524995997946-a1c2e315a42f?w=800,https://images.unsplash.com/photo-1495446815901-a7297e633e8d?w=800',
   false, NOW() - INTERVAL '9 days',  true, 3, 1, 0),
  (11, 1, 3, 1, 'Solved Two Sum (Easy). Classic warm-up problem.',
   'https://images.unsplash.com/photo-1555066931-4365d14bab8c?w=800',
   false, NOW() - INTERVAL '6 days',  true, 1, 1, 0),
  (12, 2, 3, 1, 'Solved Binary Search (Easy). Brushing up on fundamentals.',
   'https://images.unsplash.com/photo-1461749280684-dccba630e2f6?w=800',
   false, NOW() - INTERVAL '6 days',  true, 2, 0, 0),
  (13, 2, 3, 2, 'Solved Merge Intervals (Medium). Took 25 mins. Not bad!',
   'https://images.unsplash.com/photo-1542831371-29b0f74f9713?w=800',
   false, NOW() - INTERVAL '5 days',  true, 1, 1, 0),
  (14, 3, 3, 1, 'Solved Valid Parentheses. Stack problems are fun!',
   'https://images.unsplash.com/photo-1607705703571-c5a8695f18f6?w=800,https://picsum.photos/seed/leetcode_priya/800/600',
   false, NOW() - INTERVAL '5 days',  true, 2, 1, 0),
  (15, 3, 4, 1, '10 min guided meditation on Headspace. Felt so peaceful 🧘',
   'https://images.unsplash.com/photo-1506126613408-eca07ce68773?w=800,https://images.unsplash.com/photo-1545389336-cf090694435e?w=800',
   false, NOW() - INTERVAL '1 day',   true, 1, 0, 0),
  (16, 1, 5, 1, 'Day 1 no sugar. Had oats, eggs, and black coffee. Cravings real!',
   'https://images.unsplash.com/photo-1490645935967-10de6ba17061?w=800,https://images.unsplash.com/photo-1504674900247-0877df9cc836?w=800',
   false, NOW() - INTERVAL '4 days',  true, 2, 1, 0),
  (17, 3, 5, 1, 'No sugar day 1. Swapped dessert for fruit. Surprisingly easy!',
   'https://images.unsplash.com/photo-1610832958506-aa56368176cf?w=800,https://images.unsplash.com/photo-1568702846914-96b305d2ebb1?w=800',
   false, NOW() - INTERVAL '3 days',  true, 3, 2, 0);

SELECT setval('challengesphere.submissions_id_seq', 17);

-- ======================== COMMENTS (post_id references posts) ========================
INSERT INTO challengesphere.comments (post_id, user_id, content, active)
VALUES
  ('a1111111-1111-1111-1111-111111111101', 2, 'Beast mode! Keep it up 🔥',                       true),
  ('a1111111-1111-1111-1111-111111111101', 3, 'Inspiring! Starting mine tomorrow',                true),
  ('a1111111-1111-1111-1111-111111111102', 4, 'Faster already? Respect!',                         true),
  ('a1111111-1111-1111-1111-111111111103', 1, 'Before breakfast?! You''re built different 💪',     true),
  ('a1111111-1111-1111-1111-111111111104', 1, 'Progress > perfection. Love this mindset!',        true),
  ('a1111111-1111-1111-1111-111111111104', 2, 'Modified pushups are totally valid. Great start!',  true),
  ('a1111111-1111-1111-1111-111111111104', 4, 'You got this Priya! 💯',                           true),
  ('a2222222-2222-2222-2222-222222222201', 2, 'Atomic Habits is amazing. Ch 4 will blow your mind!', true),
  ('a2222222-2222-2222-2222-222222222201', 3, 'I need to read this book too',                     true),
  ('a2222222-2222-2222-2222-222222222202', 1, 'Deep Work changed my life. You''ll love it.',      true),
  ('a3333333-3333-3333-3333-333333333301', 2, 'Classic! HashMap solution?',                       true),
  ('a3333333-3333-3333-3333-333333333302', 1, '25 mins for merge intervals is solid!',            true),
  ('a3333333-3333-3333-3333-333333333303', 2, 'Stacks are underrated. Nice one!',                 true),
  ('a5555555-5555-5555-5555-555555555501', 3, 'The cravings get easier after day 3, trust me!',   true),
  ('a5555555-5555-5555-5555-555555555502', 1, 'Fruit is nature''s candy 🍎',                      true),
  ('a5555555-5555-5555-5555-555555555502', 4, 'I should try this too...',                         true);

-- ======================== LIKES (on posts) ========================
INSERT INTO challengesphere.likes (post_id, user_id, active)
VALUES
  ('a1111111-1111-1111-1111-111111111101', 2, true),
  ('a1111111-1111-1111-1111-111111111101', 3, true),
  ('a1111111-1111-1111-1111-111111111101', 4, true),
  ('a1111111-1111-1111-1111-111111111102', 2, true),
  ('a1111111-1111-1111-1111-111111111102', 3, true),
  ('a1111111-1111-1111-1111-111111111103', 1, true),
  ('a1111111-1111-1111-1111-111111111103', 3, true),
  ('a1111111-1111-1111-1111-111111111104', 1, true),
  ('a1111111-1111-1111-1111-111111111104', 2, true),
  ('a1111111-1111-1111-1111-111111111104', 4, true),
  ('a2222222-2222-2222-2222-222222222201', 2, true),
  ('a2222222-2222-2222-2222-222222222201', 3, true),
  ('a2222222-2222-2222-2222-222222222202', 1, true),
  ('a2222222-2222-2222-2222-222222222202', 3, true),
  ('a2222222-2222-2222-2222-222222222202', 4, true),
  ('a3333333-3333-3333-3333-333333333301', 2, true),
  ('a3333333-3333-3333-3333-333333333302', 1, true),
  ('a3333333-3333-3333-3333-333333333303', 1, true),
  ('a3333333-3333-3333-3333-333333333303', 2, true),
  ('a4444444-4444-4444-4444-444444444401', 1, true),
  ('a5555555-5555-5555-5555-555555555501', 3, true),
  ('a5555555-5555-5555-5555-555555555501', 4, true),
  ('a5555555-5555-5555-5555-555555555502', 1, true),
  ('a5555555-5555-5555-5555-555555555502', 2, true),
  ('a5555555-5555-5555-5555-555555555502', 4, true);

-- ======================== GLORIES (on submissions) ========================
INSERT INTO challengesphere.glories (submission_id, user_id, active)
VALUES
  (1, 2, true), (1, 3, true), (1, 4, true),
  (2, 2, true), (2, 3, true),
  (3, 2, true),
  (4, 1, true), (4, 3, true),
  (5, 1, true), (5, 3, true), (5, 4, true),
  (6, 1, true), (6, 2, true), (6, 3, true), (6, 4, true),
  (7, 1, true),
  (8, 2, true), (8, 3, true),
  (9, 3, true),
  (10, 1, true), (10, 3, true), (10, 4, true),
  (11, 2, true),
  (12, 1, true), (12, 3, true),
  (13, 1, true),
  (14, 1, true), (14, 2, true),
  (15, 1, true),
  (16, 3, true), (16, 4, true),
  (17, 1, true), (17, 2, true), (17, 4, true);

-- ======================== LEADERBOARD ENTRIES ========================
INSERT INTO challengesphere.leaderboard_entries (challenge_id, user_id, score, current_rank, previous_rank, submission_count, glory_points, streak_days, best_score, status)
VALUES
  (1, 1, 85.0, 1, NULL, 3, 6, 3, 85.0, 'ACTIVE'),
  (1, 2, 72.0, 2, NULL, 2, 5, 2, 72.0, 'ACTIVE'),
  (1, 3, 65.0, 3, NULL, 1, 4, 1, 65.0, 'ACTIVE'),
  (1, 4, 40.0, 4, NULL, 1, 1, 1, 40.0, 'ACTIVE'),
  (2, 1, 55.0, 1, NULL, 2, 3, 2, 55.0, 'ACTIVE'),
  (2, 2, 45.0, 2, NULL, 1, 3, 1, 45.0, 'ACTIVE'),
  (3, 2, 60.0, 1, NULL, 2, 3, 2, 60.0, 'ACTIVE'),
  (3, 3, 50.0, 2, NULL, 1, 2, 1, 50.0, 'ACTIVE'),
  (3, 1, 35.0, 3, NULL, 1, 1, 1, 35.0, 'ACTIVE'),
  (4, 3, 30.0, 1, NULL, 1, 1, 1, 30.0, 'ACTIVE'),
  (5, 3, 50.0, 1, NULL, 1, 3, 1, 50.0, 'ACTIVE'),
  (5, 1, 40.0, 2, NULL, 1, 2, 1, 40.0, 'ACTIVE');

-- ======================== NOTIFICATIONS ========================
INSERT INTO challengesphere.notifications (user_id, type, message, action_object)
VALUES
  (1, 'FOLLOW',  'arjun started following you',                                     '{"followerId": 2}'),
  (1, 'FOLLOW',  'priya started following you',                                     '{"followerId": 3}'),
  (1, 'FOLLOW',  'rahul started following you',                                     '{"followerId": 4}'),
  (1, 'GLORY',   'arjun gave glory to your pushup submission',                      '{"submissionId": 1, "challengeId": 1}'),
  (1, 'COMMENT', 'arjun commented: "Beast mode! Keep it up 🔥"',                    '{"postId": "a1111111-1111-1111-1111-111111111101"}'),
  (2, 'FOLLOW',  'mihir started following you',                                     '{"followerId": 1}'),
  (2, 'GLORY',   'mihir gave glory to your pushup submission',                      '{"submissionId": 4, "challengeId": 1}'),
  (2, 'COMMENT', 'mihir commented: "Before breakfast?! Built different"',            '{"postId": "a1111111-1111-1111-1111-111111111103"}'),
  (3, 'FOLLOW',  'rahul started following you',                                     '{"followerId": 4}'),
  (3, 'GLORY',   'mihir gave glory to your pushup submission',                      '{"submissionId": 6, "challengeId": 1}'),
  (3, 'COMMENT', 'mihir commented: "Progress > perfection. Love this mindset!"',    '{"postId": "a1111111-1111-1111-1111-111111111104"}'),
  (3, 'GLORY',   'arjun gave glory to your pushup submission',                      '{"submissionId": 6, "challengeId": 1}'),
  (4, 'COMMENT', 'mihir commented: "Welcome to the grind! 🎯"',                     '{"submissionId": 7}');

-- ======================== USER PREFERENCES ========================
INSERT INTO challengesphere.user_preferences
    (user_id, push_notifications_enabled, email_notifications_enabled,
     notify_on_glory, notify_on_comment, notify_on_follow, notify_on_challenge_invite, notify_on_challenge_update,
     private_profile, private_submissions, show_on_leaderboard, allow_challenge_invites,
     dark_mode, language)
VALUES
  (1, true,  true,  true,  true,  true,  true,  true,  false, false, true,  true,  false, 'en'),
  (2, true,  false, true,  true,  true,  true,  false, false, false, true,  true,  false, 'en'),
  (3, true,  true,  true,  true,  true,  true,  true,  true,  false, true,  true,  true,  'en'),
  (4, false, false, true,  false, true,  false, false, false, false, false, true,  false, 'en');

-- ======================== REPORTS ========================
INSERT INTO challengesphere.reports (post_id, user_id, reason, description, status, active)
VALUES
  ('a1111111-1111-1111-1111-111111111101', 4, 'SPAM',          'Looks like a bot post',       'PENDING', true),
  ('a3333333-3333-3333-3333-333333333302', 3, 'INAPPROPRIATE', 'Code looks plagiarized',      'PENDING', true);

-- ======================== CHALLENGE INVITES ========================
INSERT INTO challengesphere.challenge_invites (challenge_id, created_by, invite_code, expires_at, max_uses, use_count, active)
VALUES
  (1, 1, 'PUSH2026', NOW() + INTERVAL '7 days',  50,  2, true),
  (1, 2, 'ARJFIT26', NOW() + INTERVAL '3 days',  10,  0, true),
  (3, 2, 'LEET2026', NOW() + INTERVAL '14 days', 100, 1, true),
  (4, 3, 'MEDITATE', NOW() + INTERVAL '7 days',  20,  0, true),
  (5, 1, 'NOSUGAR1', NOW() - INTERVAL '1 day',   30,  0, false);

-- ======================== INVITE USAGES ========================
INSERT INTO challengesphere.invite_usages (invite_id, user_id)
VALUES
  (1, 3),
  (1, 4),
  (3, 1);

COMMIT;

-- ======================== VERIFICATION ========================
SELECT 'users'             AS tbl, COUNT(*) AS cnt FROM challengesphere.users
UNION ALL SELECT 'challenges',        COUNT(*) FROM challengesphere.challenges
UNION ALL SELECT 'user_challenges',   COUNT(*) FROM challengesphere.user_challenges
UNION ALL SELECT 'follows',           COUNT(*) FROM challengesphere.follows
UNION ALL SELECT 'posts',             COUNT(*) FROM challengesphere.posts
UNION ALL SELECT 'submissions',       COUNT(*) FROM challengesphere.submissions
UNION ALL SELECT 'comments',          COUNT(*) FROM challengesphere.comments
UNION ALL SELECT 'likes',             COUNT(*) FROM challengesphere.likes
UNION ALL SELECT 'glories',           COUNT(*) FROM challengesphere.glories
UNION ALL SELECT 'leaderboard',       COUNT(*) FROM challengesphere.leaderboard_entries
UNION ALL SELECT 'notifications',     COUNT(*) FROM challengesphere.notifications
UNION ALL SELECT 'reports',           COUNT(*) FROM challengesphere.reports
UNION ALL SELECT 'user_preferences',  COUNT(*) FROM challengesphere.user_preferences
UNION ALL SELECT 'challenge_invites', COUNT(*) FROM challengesphere.challenge_invites
UNION ALL SELECT 'invite_usages',     COUNT(*) FROM challengesphere.invite_usages
ORDER BY tbl;
