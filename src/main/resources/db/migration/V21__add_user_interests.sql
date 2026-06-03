ALTER TABLE challengesphere.users
    ADD COLUMN IF NOT EXISTS interests TEXT;

CREATE INDEX IF NOT EXISTS idx_users_interests_active
    ON challengesphere.users(active, interests)
    WHERE interests IS NOT NULL AND interests <> '';
