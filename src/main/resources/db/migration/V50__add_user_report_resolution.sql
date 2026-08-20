ALTER TABLE challengesphere.user_reports
    ADD COLUMN IF NOT EXISTS resolution VARCHAR(64),
    ADD COLUMN IF NOT EXISTS reviewed_by BIGINT REFERENCES challengesphere.users(id),
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP WITH TIME ZONE;
