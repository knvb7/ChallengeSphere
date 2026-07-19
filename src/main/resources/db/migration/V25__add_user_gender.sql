ALTER TABLE challengesphere.users
    ADD COLUMN IF NOT EXISTS gender VARCHAR(32);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_users_gender'
          AND conrelid = 'challengesphere.users'::regclass
    ) THEN
        ALTER TABLE challengesphere.users
            ADD CONSTRAINT ck_users_gender
            CHECK (
                gender IS NULL OR gender IN (
                    'WOMAN',
                    'MAN',
                    'MALE',
                    'FEMALE',
                    'NON_BINARY',
                    'OTHER',
                    'PREFER_NOT_TO_SAY'
                )
            );
    END IF;
END $$;
