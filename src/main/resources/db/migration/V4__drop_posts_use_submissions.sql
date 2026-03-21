-- Migration: Drop posts/likes tables, link comments & reports directly to submissions

-- Step 1: Add submission_id to comments
ALTER TABLE challengesphere.comments ADD COLUMN submission_id BIGINT;

-- Step 2: Populate submission_id from posts join (best-effort — matches on user_id + challenge_id)
UPDATE challengesphere.comments c
SET submission_id = s.id
FROM challengesphere.posts p
JOIN challengesphere.submissions s ON s.user_id = p.user_id AND s.challenge_id = p.challenge_id
WHERE c.post_id = p.id;

-- Step 3: For any comments that couldn't be mapped, assign a default or delete
DELETE FROM challengesphere.comments WHERE submission_id IS NULL;

-- Step 4: Make submission_id NOT NULL + add FK
ALTER TABLE challengesphere.comments ALTER COLUMN submission_id SET NOT NULL;
ALTER TABLE challengesphere.comments
    ADD CONSTRAINT fk_comments_submission FOREIGN KEY (submission_id)
    REFERENCES challengesphere.submissions(id) ON DELETE CASCADE;

-- Step 5: Drop post_id from comments
ALTER TABLE challengesphere.comments DROP COLUMN post_id;

-- Step 6: Add submission_id to reports
ALTER TABLE challengesphere.reports ADD COLUMN submission_id BIGINT;

-- Populate from posts join
UPDATE challengesphere.reports r
SET submission_id = s.id
FROM challengesphere.posts p
JOIN challengesphere.submissions s ON s.user_id = p.user_id AND s.challenge_id = p.challenge_id
WHERE r.post_id = p.id;

DELETE FROM challengesphere.reports WHERE submission_id IS NULL;

ALTER TABLE challengesphere.reports ALTER COLUMN submission_id SET NOT NULL;
ALTER TABLE challengesphere.reports
    ADD CONSTRAINT fk_reports_submission FOREIGN KEY (submission_id)
    REFERENCES challengesphere.submissions(id) ON DELETE CASCADE;

-- Drop post_id from reports
ALTER TABLE challengesphere.reports DROP COLUMN post_id;

-- Step 7: Drop likes table (glories is the equivalent on submissions)
DROP TABLE IF EXISTS challengesphere.likes CASCADE;

-- Step 8: Drop posts table
DROP TABLE IF EXISTS challengesphere.posts CASCADE;

-- Step 9: Add indexes
CREATE INDEX IF NOT EXISTS idx_comments_submission_id ON challengesphere.comments(submission_id);
CREATE INDEX IF NOT EXISTS idx_comments_submission_active ON challengesphere.comments(submission_id, active, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reports_submission_id ON challengesphere.reports(submission_id);

