-- Allow up to 5 submissions per (user, challenge, day).
-- Previously the unique constraint blocked any second submission on the same day.
-- Enforcement of the 5-submission cap is now done in the application layer.
ALTER TABLE challengesphere.submissions
    DROP CONSTRAINT IF EXISTS uk_submission_day;
