-- Add google_id column for OAuth2 users
ALTER TABLE challengesphere.users
    ADD COLUMN IF NOT EXISTS google_id VARCHAR(255) UNIQUE;

-- Allow null hashed_password for social-login users
ALTER TABLE challengesphere.users
    ALTER COLUMN hashed_password DROP NOT NULL;
