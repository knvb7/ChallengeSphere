-- Backfill creators as active participants for existing challenges.
--
-- Why:
--   Submission creation validates challengesphere.user_challenges. Older
--   challenges may have creator_id/current_owner_id set, but no matching
--   user_challenges row, so creators cannot upload submissions to their own
--   challenges.
--
-- Safe to run multiple times.

BEGIN;

INSERT INTO challengesphere.user_challenges (
    user_id,
    challenge_id,
    joined_at,
    completed,
    completed_at,
    active
)
SELECT
    c.creator_id,
    c.id,
    COALESCE(c.created_at, CURRENT_TIMESTAMP),
    FALSE,
    NULL,
    TRUE
FROM challengesphere.challenges c
WHERE c.creator_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM challengesphere.user_challenges uc
      WHERE uc.user_id = c.creator_id
        AND uc.challenge_id = c.id
  );

COMMIT;

-- Verification: should return 0 after the backfill.
SELECT COUNT(*) AS missing_creator_participant_rows
FROM challengesphere.challenges c
WHERE c.creator_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM challengesphere.user_challenges uc
      WHERE uc.user_id = c.creator_id
        AND uc.challenge_id = c.id
  );
