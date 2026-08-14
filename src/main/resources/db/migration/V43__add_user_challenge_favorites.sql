ALTER TABLE challengesphere.user_challenges
    ADD COLUMN IF NOT EXISTS favorite BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS favorited_at TIMESTAMP WITH TIME ZONE;

INSERT INTO challengesphere.user_challenges (
    user_id,
    challenge_id,
    joined_at,
    completed,
    active,
    favorite
)
SELECT
    relationship.user_id,
    relationship.challenge_id,
    CURRENT_TIMESTAMP,
    FALSE,
    relationship.active,
    FALSE
FROM (
    SELECT
        c.creator_id AS user_id,
        c.id AS challenge_id,
        CASE
            WHEN c.creator_id = COALESCE(c.current_owner_id, c.creator_id)
                THEN COALESCE(c.active, FALSE)
            ELSE FALSE
        END AS active
    FROM challengesphere.challenges c
    WHERE c.creator_id IS NOT NULL
    UNION
    SELECT c.current_owner_id AS user_id, c.id AS challenge_id, COALESCE(c.active, FALSE) AS active
    FROM challengesphere.challenges c
    WHERE c.current_owner_id IS NOT NULL
) relationship
ON CONFLICT (user_id, challenge_id) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_user_challenges_favorites
    ON challengesphere.user_challenges (user_id, favorited_at DESC, challenge_id)
    WHERE favorite = TRUE AND COALESCE(banned, FALSE) = FALSE;
