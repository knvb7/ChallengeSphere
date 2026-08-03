ALTER TABLE challengesphere.users
    ADD COLUMN IF NOT EXISTS country VARCHAR(100);
