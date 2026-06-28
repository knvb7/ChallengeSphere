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
    platform,
    title,
    description,
    challenge_type,
    duration,
    rules,
    benefits
) AS (
    VALUES
        ('LeetCode', 'LeetCode Easy Warmup Week', 'Solve one Easy LeetCode problem every day for a week.', 'Daily', '1 Week', 'Use accepted submissions only. Revisit the editorial after solving and write one takeaway.', 'Builds a steady DSA habit without overwhelming beginners.'),
        ('LeetCode', 'LeetCode Medium Ladder', 'Complete a curated run of Medium LeetCode problems across common interview patterns.', 'Daily', '2 Weeks', 'Solve at least one Medium problem per day. Tag each solution by pattern such as sliding window, graph, DP, or binary search.', 'Improves pattern recognition and interview readiness.'),
        ('LeetCode', 'LeetCode Blind 75 Sprint', 'Work through Blind 75 style LeetCode practice with daily accepted submissions.', 'Daily', '1 Month', 'Pick one Blind 75 problem daily. If stuck, document the missing idea before checking hints.', 'Creates broad coverage across core coding interview topics.'),
        ('LeetCode', 'LeetCode Dynamic Programming Reset', 'Practice dynamic programming on LeetCode with small, focused daily wins.', 'Daily', '2 Weeks', 'Solve or deeply study one DP problem daily. Submit accepted code or notes explaining the recurrence.', 'Strengthens recurrence design and state transition thinking.'),
        ('LeetCode', 'LeetCode Graph Practice Run', 'Solve graph and traversal problems on LeetCode.', 'Daily', '1 Week', 'Complete one BFS, DFS, shortest path, or topological sort problem daily.', 'Builds fluency with graph modeling and traversal choices.'),
        ('LeetCode', 'LeetCode SQL Drill', 'Practice database problems on LeetCode SQL.', 'Daily', '1 Week', 'Submit one accepted SQL problem daily and note the key clause or join pattern used.', 'Improves SQL query confidence for interviews and backend work.'),
        ('LeetCode', 'LeetCode Daily Challenge Streak', 'Keep up with the LeetCode daily challenge.', 'Daily', '1 Month', 'Complete the official daily problem or a comparable fallback problem each day.', 'Builds consistency and exposes you to varied topics.'),
        ('LeetCode', 'LeetCode Hard Problem Study', 'Do a focused one-day study session on one Hard LeetCode problem.', 'One-off', '1 Day', 'Spend at least 60 minutes. Accepted submission is ideal; a clear written solution review is acceptable if the problem is new.', 'Develops deeper problem analysis and resilience.'),
        ('LeetCode', 'LeetCode Two Pointer Day', 'Solve two LeetCode problems using two pointers or fast-slow pointer techniques.', 'One-off', '1 Day', 'Submit accepted solutions and note why the pointer movement is correct.', 'Sharpens a common array and linked-list technique.'),
        ('LeetCode', 'LeetCode Interview Mock Set', 'Complete a three-problem LeetCode interview-style set.', 'One-off', '1 Day', 'Solve one Easy, one Medium, and one review problem under a 90-minute limit.', 'Practices pacing, prioritization, and post-round review.'),
        ('LeetCode', 'LeetCode Recursion Practice', 'Work through recursive thinking with LeetCode backtracking or tree problems.', 'Daily', '1 Week', 'Solve one recursion-heavy problem daily and write the base case before coding.', 'Improves recursive decomposition and debugging.'),
        ('LeetCode', 'LeetCode Review Your Mistakes', 'Re-solve previous failed or slow LeetCode attempts.', 'Daily', '1 Week', 'Pick one old problem daily, solve it again, then compare with your first approach.', 'Converts mistakes into durable skill gains.'),
        ('Codeforces', 'Codeforces A Problem Streak', 'Solve one Codeforces A problem every day.', 'Daily', '1 Week', 'Submit an accepted solution daily. Prefer contests you have not practiced before.', 'Builds speed with implementation and basic math.'),
        ('Codeforces', 'Codeforces Rating 800-1000 Ladder', 'Climb a beginner Codeforces ladder with accepted submissions.', 'Daily', '2 Weeks', 'Solve one 800-1000 rated problem per day. Upsolve if your first attempt fails.', 'Improves fundamentals and contest confidence.'),
        ('Codeforces', 'Codeforces Constructive Practice', 'Practice constructive algorithms on Codeforces.', 'Daily', '1 Week', 'Solve one constructive problem daily and write the construction idea before coding.', 'Strengthens proof-driven implementation.'),
        ('Codeforces', 'Codeforces Greedy Week', 'Solve greedy problems from Codeforces.', 'Daily', '1 Week', 'Complete one accepted greedy problem daily and note the exchange or ordering argument.', 'Builds intuition for local-choice correctness.'),
        ('Codeforces', 'Codeforces Div 3 Upsolve', 'Upsolve problems from a Codeforces Div 3 round.', 'Daily', '1 Week', 'Pick one unsolved or slow problem daily from a Div 3 contest and submit accepted code.', 'Turns contest misses into measurable progress.'),
        ('Codeforces', 'Codeforces Virtual Contest', 'Run one Codeforces virtual contest under timed conditions.', 'One-off', '1 Day', 'Choose a past Div 3 or Div 4 round. Compete honestly, then upsolve one missed problem.', 'Practices contest pacing and pressure management.'),
        ('Codeforces', 'Codeforces Implementation Speedrun', 'Solve three short Codeforces implementation problems.', 'One-off', '1 Day', 'Pick problems rated 800-1100. Keep solutions clean and accepted.', 'Improves speed and reduces avoidable bugs.'),
        ('Codeforces', 'Codeforces Math Practice', 'Practice number theory, combinatorics, or arithmetic Codeforces problems.', 'Daily', '2 Weeks', 'Solve one math-tagged problem daily and write the key observation.', 'Builds contest math fluency.'),
        ('Codeforces', 'Codeforces Binary Search Drill', 'Solve Codeforces problems that use binary search on answer or sorted data.', 'Daily', '1 Week', 'Submit one accepted solution daily and identify the monotonic predicate.', 'Improves a high-frequency competitive programming pattern.'),
        ('Codeforces', 'Codeforces Graph Basics', 'Practice Codeforces graph fundamentals.', 'Daily', '1 Week', 'Solve one graph problem daily using BFS, DFS, connected components, or shortest paths.', 'Builds reliable graph implementation habits.'),
        ('Codeforces', 'Codeforces B Problem Push', 'Focus on Codeforces B problems to move beyond warmups.', 'Daily', '2 Weeks', 'Solve one B problem daily. If blocked for 30 minutes, read hints and finish the implementation.', 'Improves transition from easy implementation to real contest thinking.'),
        ('Codeforces', 'Codeforces Editorial Rewrite', 'Study and rewrite one Codeforces editorial solution in your own code.', 'One-off', '1 Day', 'Choose a problem you could not solve. Read the editorial, then implement without copying code.', 'Helps convert new techniques into usable memory.')
),
mapped AS (
    SELECT
        title,
        description || E'\n\nRules & Guidelines: ' || rules AS description,
        lower(platform) || ',coding,programming,dsa,algorithms' AS hashtags,
        'Coding, Programming, DSA, ' || platform AS categories,
        benefits,
        CASE challenge_type
            WHEN 'One-off' THEN 'ONE_OFF'
            ELSE 'DAILY'
        END AS challenge_mode,
        CASE duration
            WHEN '1 Day' THEN 1
            WHEN '1 Week' THEN 7
            WHEN '2 Weeks' THEN 14
            WHEN '1 Month' THEN 30
        END AS number_of_days,
        'official/global-challenges/official-reading-study.jpg' AS cover_photo_urls
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
WHERE challenge.title IN (
    'LeetCode Easy Warmup Week',
    'LeetCode Medium Ladder',
    'LeetCode Blind 75 Sprint',
    'LeetCode Dynamic Programming Reset',
    'LeetCode Graph Practice Run',
    'LeetCode SQL Drill',
    'LeetCode Daily Challenge Streak',
    'LeetCode Hard Problem Study',
    'LeetCode Two Pointer Day',
    'LeetCode Interview Mock Set',
    'LeetCode Recursion Practice',
    'LeetCode Review Your Mistakes',
    'Codeforces A Problem Streak',
    'Codeforces Rating 800-1000 Ladder',
    'Codeforces Constructive Practice',
    'Codeforces Greedy Week',
    'Codeforces Div 3 Upsolve',
    'Codeforces Virtual Contest',
    'Codeforces Implementation Speedrun',
    'Codeforces Math Practice',
    'Codeforces Binary Search Drill',
    'Codeforces Graph Basics',
    'Codeforces B Problem Push',
    'Codeforces Editorial Rewrite'
)
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
