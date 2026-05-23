ALTER TABLE IF EXISTS challengesphere.challenges
    ADD COLUMN IF NOT EXISTS challenge_mode VARCHAR(20) NOT NULL DEFAULT 'DAILY';

UPDATE challengesphere.challenges
SET challenge_mode = 'DAILY'
WHERE challenge_mode IS NULL OR challenge_mode = '';

ALTER TABLE IF EXISTS public.challenges
    ADD COLUMN IF NOT EXISTS challenge_mode VARCHAR(20) NOT NULL DEFAULT 'DAILY';

DO $$
BEGIN
    IF to_regclass('public.challenges') IS NOT NULL THEN
        UPDATE public.challenges
        SET challenge_mode = 'DAILY'
        WHERE challenge_mode IS NULL OR challenge_mode = '';
    END IF;
END $$;
