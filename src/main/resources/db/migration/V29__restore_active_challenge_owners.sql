INSERT INTO challengesphere.user_challenges (
    user_id,
    challenge_id,
    joined_at,
    completed,
    active
)
SELECT
    COALESCE(c.current_owner_id, c.creator_id),
    c.id,
    CURRENT_TIMESTAMP,
    FALSE,
    TRUE
FROM challengesphere.challenges c
WHERE c.active = TRUE
  AND COALESCE(c.current_owner_id, c.creator_id) IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM challengesphere.user_challenges uc
      WHERE uc.user_id = COALESCE(c.current_owner_id, c.creator_id)
        AND uc.challenge_id = c.id
  );

UPDATE challengesphere.user_challenges uc
SET active = TRUE
FROM challengesphere.challenges c
WHERE c.id = uc.challenge_id
  AND c.active = TRUE
  AND uc.user_id = COALESCE(c.current_owner_id, c.creator_id)
  AND uc.active = FALSE;
