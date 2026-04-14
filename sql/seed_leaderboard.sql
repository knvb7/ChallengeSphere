-- ============================================================
-- Leaderboard Seed Script
-- Inserts / refreshes leaderboard_entries for every user who
-- has joined a challenge (user_challenges table).
--
-- Scoring formula (mirrors LeaderboardServiceImpl logic):
--   base          = submission_count * 10
--   glory_bonus   = total glories received on those submissions * 2
--   streak_bonus  = current_streak * 5
--   score         = base + glory_bonus + streak_bonus
--
-- Safe to re-run: uses ON CONFLICT DO UPDATE to refresh scores.
--
-- Run with:
--   psql -U postgres -d postgres -f sql/seed_leaderboard.sql
-- ============================================================

BEGIN;

-- ── Step 1: Compute per-user per-challenge stats from live data ───────────
WITH submission_stats AS (
    -- Count active submissions and sum glories received per (user, challenge)
    SELECT
        s.user_id,
        s.challenge_id,
        COUNT(*)                   AS submission_count,
        COALESCE(SUM(s.glories_count), 0) AS total_glories,
        MAX(s.submission_time)     AS last_submission_at,
        MIN(s.submission_time)     AS first_submission_at,
        -- streak = length of the longest consecutive day run ending at the max day
        MAX(s.day_number)          AS max_day
    FROM challengesphere.submissions s
    WHERE s.active = true
    GROUP BY s.user_id, s.challenge_id
),

streak_calc AS (
    -- For each user+challenge, collect submitted day numbers and
    -- compute streak as consecutive days counting back from the max day.
    -- PostgreSQL: use a window function trick with row_number to find gaps.
    SELECT
        s.user_id,
        s.challenge_id,
        s.day_number,
        s.day_number
            - ROW_NUMBER() OVER (
                PARTITION BY s.user_id, s.challenge_id
                ORDER BY s.day_number
              )::INT AS grp
    FROM (
        SELECT DISTINCT user_id, challenge_id, day_number
        FROM challengesphere.submissions
        WHERE active = true
    ) s
),

streak_lengths AS (
    SELECT
        user_id,
        challenge_id,
        grp,
        COUNT(*)  AS run_length,
        MAX(day_number) AS run_end
    FROM streak_calc
    GROUP BY user_id, challenge_id, grp
),

current_streaks AS (
    -- The streak that ends at the user's latest submitted day
    SELECT DISTINCT ON (sl.user_id, sl.challenge_id)
        sl.user_id,
        sl.challenge_id,
        sl.run_length AS current_streak
    FROM streak_lengths sl
    JOIN submission_stats ss
      ON ss.user_id = sl.user_id AND ss.challenge_id = sl.challenge_id
    WHERE sl.run_end = ss.max_day
    ORDER BY sl.user_id, sl.challenge_id, sl.run_end DESC
),

scored AS (
    SELECT
        uc.user_id,
        uc.challenge_id,
        COALESCE(st.submission_count, 0)                        AS submission_count,
        COALESCE(st.total_glories, 0)                           AS glory_points,
        COALESCE(cs.current_streak, 0)                          AS streak_days,
        -- score formula
        (COALESCE(st.submission_count, 0) * 10
         + COALESCE(st.total_glories, 0) * 2
         + COALESCE(cs.current_streak, 0) * 5)::DOUBLE PRECISION AS score,
        st.last_submission_at,
        st.first_submission_at
    FROM challengesphere.user_challenges uc
    LEFT JOIN submission_stats  st ON st.user_id = uc.user_id AND st.challenge_id = uc.challenge_id
    LEFT JOIN current_streaks   cs ON cs.user_id = uc.user_id AND cs.challenge_id = uc.challenge_id
    WHERE uc.active = true
),

-- ── Step 2: Assign ranks within each challenge ────────────────────────────
ranked AS (
    SELECT
        *,
        RANK() OVER (PARTITION BY challenge_id ORDER BY score DESC) AS current_rank
    FROM scored
)

-- ── Step 3: Upsert ────────────────────────────────────────────────────────
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
    r.challenge_id,
    r.user_id,
    r.score,
    r.current_rank::INT,
    NULL,                    -- previous_rank (unknown on first insert)
    r.submission_count::INT,
    r.glory_points::INT,
    r.streak_days::INT,
    r.score,                 -- best_score = current score on seed
    r.last_submission_at,
    r.first_submission_at,
    'ACTIVE',
    NOW(),
    NOW()
FROM ranked r
ON CONFLICT (challenge_id, user_id) DO UPDATE
    SET score              = EXCLUDED.score,
        previous_rank      = leaderboard_entries.current_rank,  -- preserve old rank
        current_rank       = EXCLUDED.current_rank,
        submission_count   = EXCLUDED.submission_count,
        glory_points       = EXCLUDED.glory_points,
        streak_days        = EXCLUDED.streak_days,
        best_score         = GREATEST(leaderboard_entries.best_score, EXCLUDED.score),
        last_submission_at = EXCLUDED.last_submission_at,
        updated_at         = NOW();

COMMIT;

-- ── Verification ──────────────────────────────────────────────────────────
SELECT
    c.title                   AS challenge,
    u.username                AS player,
    le.current_rank           AS rank,
    le.score,
    le.submission_count       AS subs,
    le.glory_points           AS glories,
    le.streak_days            AS streak
FROM challengesphere.leaderboard_entries le
JOIN challengesphere.challenges c ON c.id = le.challenge_id
JOIN challengesphere.users      u ON u.id = le.user_id
ORDER BY c.id, le.current_rank;
