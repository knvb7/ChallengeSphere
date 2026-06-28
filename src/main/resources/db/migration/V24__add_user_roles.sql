ALTER TABLE challengesphere.users
    ADD COLUMN IF NOT EXISTS role VARCHAR(32) NOT NULL DEFAULT 'USER';

UPDATE challengesphere.users
SET role = 'USER'
WHERE role IS NULL OR role = '';
