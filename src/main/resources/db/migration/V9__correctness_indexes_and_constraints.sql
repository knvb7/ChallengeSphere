-- V9: correctness + hot-path indexes
--  1. Index on submissions(user_id, challenge_id, day_number) — hit on every submission
--     to enforce the 5/day cap. Existing idx_submissions_user_time doesn't cover this.
--  2. CHECK on glories: (active, ungloried) can never both be TRUE. Both FALSE is
--     allowed (neutral state after toggling unglory off without giving glory).
--  3. notifications.read_at + partial index on unread rows — lets the unread count be
--     an index-only scan instead of a full table scan per user.

-- 1. Submission hot-path index
CREATE INDEX IF NOT EXISTS idx_submissions_user_challenge_day
    ON challengesphere.submissions (user_id, challenge_id, day_number);

-- 2. Glory state invariant
-- Drop first in case a prior run created a bad variant.
ALTER TABLE challengesphere.glories
    DROP CONSTRAINT IF EXISTS chk_glory_state;

-- Heal any existing violators: if a row somehow has both active=true AND ungloried=true,
-- prefer ungloried (the more recent intent) and deactivate the glory.
UPDATE challengesphere.glories
SET active = FALSE
WHERE active = TRUE AND ungloried = TRUE;

ALTER TABLE challengesphere.glories
    ADD CONSTRAINT chk_glory_state
    CHECK (NOT (active = TRUE AND ungloried = TRUE));

-- 3. Notifications read tracking
ALTER TABLE challengesphere.notifications
    ADD COLUMN IF NOT EXISTS read_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_notifications_user_unread
    ON challengesphere.notifications (user_id)
    WHERE read_at IS NULL;
