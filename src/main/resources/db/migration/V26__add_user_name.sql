ALTER TABLE challengesphere.users
    ADD COLUMN IF NOT EXISTS name VARCHAR(120);
