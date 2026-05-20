-- Migration: Drop posts/likes tables, link comments & reports directly to submissions.
-- This migration is intentionally idempotent because some environments were
-- upgraded manually.

-- Step 1: Add submission_id to comments.
ALTER TABLE challengesphere.comments ADD COLUMN IF NOT EXISTS submission_id BIGINT;

-- Step 2: Populate submission_id from posts join when the legacy posts shape exists.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'challengesphere' AND table_name = 'posts'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'challengesphere' AND table_name = 'comments' AND column_name = 'post_id'
    ) THEN
        UPDATE challengesphere.comments c
        SET submission_id = s.id
        FROM challengesphere.posts p
        JOIN challengesphere.submissions s ON s.user_id = p.user_id AND s.challenge_id = p.challenge_id
        WHERE c.post_id = p.id
          AND c.submission_id IS NULL;
    END IF;
END $$;

-- Step 3: For any comments that could not be mapped, delete them.
DELETE FROM challengesphere.comments WHERE submission_id IS NULL;

-- Step 4: Make submission_id NOT NULL + add FK.
ALTER TABLE challengesphere.comments ALTER COLUMN submission_id SET NOT NULL;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_comments_submission'
    ) THEN
        ALTER TABLE challengesphere.comments
            ADD CONSTRAINT fk_comments_submission FOREIGN KEY (submission_id)
            REFERENCES challengesphere.submissions(id) ON DELETE CASCADE;
    END IF;
END $$;

-- Step 5: Drop post_id from comments.
ALTER TABLE challengesphere.comments DROP COLUMN IF EXISTS post_id;

-- Step 6: Add submission_id to reports.
ALTER TABLE challengesphere.reports ADD COLUMN IF NOT EXISTS submission_id BIGINT;

-- Populate from posts join when the legacy posts shape exists.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'challengesphere' AND table_name = 'posts'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'challengesphere' AND table_name = 'reports' AND column_name = 'post_id'
    ) THEN
        UPDATE challengesphere.reports r
        SET submission_id = s.id
        FROM challengesphere.posts p
        JOIN challengesphere.submissions s ON s.user_id = p.user_id AND s.challenge_id = p.challenge_id
        WHERE r.post_id = p.id
          AND r.submission_id IS NULL;
    END IF;
END $$;

DELETE FROM challengesphere.reports WHERE submission_id IS NULL;

ALTER TABLE challengesphere.reports ALTER COLUMN submission_id SET NOT NULL;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_reports_submission'
    ) THEN
        ALTER TABLE challengesphere.reports
            ADD CONSTRAINT fk_reports_submission FOREIGN KEY (submission_id)
            REFERENCES challengesphere.submissions(id) ON DELETE CASCADE;
    END IF;
END $$;

-- Drop post_id from reports.
ALTER TABLE challengesphere.reports DROP COLUMN IF EXISTS post_id;

-- Step 7: Drop likes table (glories is the equivalent on submissions)
DROP TABLE IF EXISTS challengesphere.likes CASCADE;

-- Step 8: Drop posts table
DROP TABLE IF EXISTS challengesphere.posts CASCADE;

-- Step 9: Add indexes
CREATE INDEX IF NOT EXISTS idx_comments_submission_id ON challengesphere.comments(submission_id);
CREATE INDEX IF NOT EXISTS idx_comments_submission_active ON challengesphere.comments(submission_id, active, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reports_submission_id ON challengesphere.reports(submission_id);
