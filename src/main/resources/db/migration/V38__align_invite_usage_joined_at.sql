DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'challengesphere'
          AND table_name = 'invite_usages'
          AND column_name = 'used_at'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'challengesphere'
          AND table_name = 'invite_usages'
          AND column_name = 'joined_at'
    ) THEN
        ALTER TABLE challengesphere.invite_usages
            RENAME COLUMN used_at TO joined_at;
    ELSIF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'challengesphere'
          AND table_name = 'invite_usages'
          AND column_name = 'joined_at'
    ) THEN
        ALTER TABLE challengesphere.invite_usages
            ADD COLUMN joined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
    END IF;
END
$$;
