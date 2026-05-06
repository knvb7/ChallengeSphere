-- V10: Defensive backfill for environments that missed V6
-- Ensures the ungloried state column exists before Hibernate maps Glory entities.

ALTER TABLE IF EXISTS challengesphere.glories
    ADD COLUMN IF NOT EXISTS ungloried BOOLEAN NOT NULL DEFAULT FALSE;

-- Some legacy environments may have glories under public schema.
ALTER TABLE IF EXISTS public.glories
    ADD COLUMN IF NOT EXISTS ungloried BOOLEAN NOT NULL DEFAULT FALSE;
