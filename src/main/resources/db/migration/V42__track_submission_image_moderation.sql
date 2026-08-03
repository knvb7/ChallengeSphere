-- Durable Rekognition state for the 15-minute submission image moderation sweep.
-- Existing rows intentionally start PENDING so the first deployment backfills them.
ALTER TABLE challengesphere.submissions
    ADD COLUMN IF NOT EXISTS moderation_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS moderation_started_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS moderated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS moderation_error VARCHAR(512);

ALTER TABLE challengesphere.submissions
    DROP CONSTRAINT IF EXISTS chk_submissions_moderation_status;

ALTER TABLE challengesphere.submissions
    ADD CONSTRAINT chk_submissions_moderation_status
        CHECK (moderation_status IN ('PENDING', 'PROCESSING', 'PASSED', 'FLAGGED', 'FAILED'));

CREATE INDEX IF NOT EXISTS idx_submissions_moderation_queue
    ON challengesphere.submissions (id)
    WHERE moderation_status IN ('PENDING', 'FAILED', 'PROCESSING')
      AND NULLIF(BTRIM(media_urls), '') IS NOT NULL;
