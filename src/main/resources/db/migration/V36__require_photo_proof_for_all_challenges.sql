ALTER TABLE challengesphere.challenges
    ALTER COLUMN proof_mode SET DEFAULT 'PHOTO_REQUIRED';

UPDATE challengesphere.challenges
SET proof_mode = 'PHOTO_REQUIRED'
WHERE proof_mode IS DISTINCT FROM 'PHOTO_REQUIRED';

ALTER TABLE challengesphere.official_challenge_templates
    ALTER COLUMN proof_mode SET DEFAULT 'PHOTO_REQUIRED';

UPDATE challengesphere.official_challenge_templates
SET proof_mode = 'PHOTO_REQUIRED',
    updated_at = CURRENT_TIMESTAMP
WHERE proof_mode IS DISTINCT FROM 'PHOTO_REQUIRED';

-- V32 also supported deployments that still expose the legacy public schema.
-- Keep that compatibility table aligned when it exists without making it a
-- requirement for normal ChallengeSphere installations.
DO $$
BEGIN
    IF to_regclass('public.challenges') IS NOT NULL THEN
        ALTER TABLE public.challenges
            ALTER COLUMN proof_mode SET DEFAULT 'PHOTO_REQUIRED';

        UPDATE public.challenges
        SET proof_mode = 'PHOTO_REQUIRED'
        WHERE proof_mode IS DISTINCT FROM 'PHOTO_REQUIRED';
    END IF;
END $$;
