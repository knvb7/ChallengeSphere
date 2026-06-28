ALTER TABLE challengesphere.submissions
    ADD COLUMN IF NOT EXISTS proof_payload TEXT;
