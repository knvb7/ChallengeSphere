ALTER TABLE IF EXISTS challengesphere.challenges
    ADD COLUMN IF NOT EXISTS proof_mode VARCHAR(24) NOT NULL DEFAULT 'FLEXIBLE';

UPDATE challengesphere.challenges
SET proof_mode = 'FLEXIBLE'
WHERE proof_mode IS NULL OR BTRIM(proof_mode) = '';

DO $$
BEGIN
    IF to_regclass('challengesphere.challenges') IS NOT NULL
       AND NOT EXISTS (
           SELECT 1
           FROM pg_constraint
           WHERE conname = 'chk_challenges_proof_mode'
             AND conrelid = 'challengesphere.challenges'::REGCLASS
       ) THEN
        ALTER TABLE challengesphere.challenges
            ADD CONSTRAINT chk_challenges_proof_mode
            CHECK (proof_mode IN ('FLEXIBLE', 'PHOTO_REQUIRED'));
    END IF;
END $$;

ALTER TABLE IF EXISTS public.challenges
    ADD COLUMN IF NOT EXISTS proof_mode VARCHAR(24) NOT NULL DEFAULT 'FLEXIBLE';

DO $$
BEGIN
    IF to_regclass('public.challenges') IS NOT NULL THEN
        UPDATE public.challenges
        SET proof_mode = 'FLEXIBLE'
        WHERE proof_mode IS NULL OR BTRIM(proof_mode) = '';

        IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint
            WHERE conname = 'chk_public_challenges_proof_mode'
              AND conrelid = 'public.challenges'::REGCLASS
        ) THEN
            ALTER TABLE public.challenges
                ADD CONSTRAINT chk_public_challenges_proof_mode
                CHECK (proof_mode IN ('FLEXIBLE', 'PHOTO_REQUIRED'));
        END IF;
    END IF;
END $$;
