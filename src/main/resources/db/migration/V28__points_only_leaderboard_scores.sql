WITH active_daily_media AS (
    SELECT
        s.challenge_id,
        s.user_id,
        s.day_number,
        SUM(
            CASE
                WHEN s.media_urls IS NULL OR BTRIM(s.media_urls) = '' THEN 0
                ELSE CARDINALITY(REGEXP_SPLIT_TO_ARRAY(s.media_urls, '\\s*,\\s*'))
            END
        ) AS media_count
    FROM challengesphere.submissions s
    WHERE s.active = TRUE
      AND s.day_number IS NOT NULL
    GROUP BY s.challenge_id, s.user_id, s.day_number
),
point_totals AS (
    SELECT
        challenge_id,
        user_id,
        SUM(10 + GREATEST(media_count - 1, 0))::DOUBLE PRECISION AS points,
        COUNT(*)::INTEGER AS submitted_days
    FROM active_daily_media
    GROUP BY challenge_id, user_id
),
resolved AS (
    SELECT
        le.id,
        COALESCE(pt.points, 0)::DOUBLE PRECISION AS points,
        COALESCE(pt.submitted_days, 0)::INTEGER AS submitted_days
    FROM challengesphere.leaderboard_entries le
    LEFT JOIN point_totals pt
      ON pt.challenge_id = le.challenge_id
     AND pt.user_id = le.user_id
)
UPDATE challengesphere.leaderboard_entries le
SET score = resolved.points,
    submission_count = resolved.submitted_days,
    updated_at = NOW()
FROM resolved
WHERE le.id = resolved.id;

WITH ranked AS (
    SELECT
        id,
        ROW_NUMBER() OVER (PARTITION BY challenge_id ORDER BY score DESC, user_id ASC) AS rank
    FROM challengesphere.leaderboard_entries
    WHERE status = 'ACTIVE'
)
UPDATE challengesphere.leaderboard_entries le
SET previous_rank = le.current_rank,
    current_rank = ranked.rank,
    updated_at = NOW()
FROM ranked
WHERE le.id = ranked.id;
