-- ============================================================
-- Add cover_photo_urls column to challenges table
-- and seed example cover photos for existing challenges.
--
-- Run with:
--   psql -U postgres -d postgres -f sql/add_challenge_cover_photos.sql
--
-- NOTE: If you use Flyway, V7__add_cover_photo_urls_to_challenges.sql
-- already adds the column on app startup.  Run only the UPDATE
-- block below if the column already exists.
-- ============================================================

BEGIN;

-- ── STEP 1: Add column (safe to run even if already present) ──────────────
ALTER TABLE challengesphere.challenges
    ADD COLUMN IF NOT EXISTS cover_photo_urls TEXT DEFAULT NULL;

-- ── STEP 2: Seed cover photos for the 5 example challenges ────────────────
-- Each challenge gets 2-5 thematic Unsplash images (comma-separated).
-- In production these will be S3 object keys following the pattern:
--   challenges/user_{id}/covers/{filename}

-- Challenge 1 · 100 Pushups Challenge  (Fitness)
UPDATE challengesphere.challenges
SET cover_photo_urls =
    'https://images.unsplash.com/photo-1571019614242-c5c5dee9f50b?w=800,'  -- person doing pushups
    'https://images.unsplash.com/photo-1534438327276-14e5300c3a48?w=800,'  -- gym workout
    'https://images.unsplash.com/photo-1583454110551-21f2fa2afe61?w=800,'  -- pushup close-up
    'https://images.unsplash.com/photo-1517836357463-d25dfeac3438?w=800,'  -- fitness motivation
    'https://images.unsplash.com/photo-1601422407692-ec4eeec1d9b3?w=800'   -- outdoor workout
WHERE id = 1;

-- Challenge 2 · 30 Days of Reading  (Education / Lifestyle)
UPDATE challengesphere.challenges
SET cover_photo_urls =
    'https://images.unsplash.com/photo-1512820790803-83ca734da794?w=800,'  -- stack of books
    'https://images.unsplash.com/photo-1524995997946-a1c2e315a42f?w=800,'  -- open book on table
    'https://images.unsplash.com/photo-1507842217343-583bb7270b66?w=800,'  -- cosy reading nook
    'https://images.unsplash.com/photo-1544947950-fa07a98d237f?w=800'      -- book and coffee
WHERE id = 2;

-- Challenge 3 · LeetCode Daily Grind  (Tech / Coding)
UPDATE challengesphere.challenges
SET cover_photo_urls =
    'https://images.unsplash.com/photo-1555066931-4365d14bab8c?w=800,'    -- code on screen
    'https://images.unsplash.com/photo-1542831371-29b0f74f9713?w=800,'    -- dark terminal
    'https://images.unsplash.com/photo-1461749280684-dccba630e2f6?w=800,' -- laptop coding
    'https://images.unsplash.com/photo-1607705703571-c5a8695f18f6?w=800,' -- developer workspace
    'https://images.unsplash.com/photo-1516116216624-53e697fedbea?w=800'  -- multiple monitors
WHERE id = 3;

-- Challenge 4 · 21-Day Meditation  (Health / Wellness)
UPDATE challengesphere.challenges
SET cover_photo_urls =
    'https://images.unsplash.com/photo-1506126613408-eca07ce68773?w=800,' -- person meditating outdoor
    'https://images.unsplash.com/photo-1545389336-cf090694435e?w=800,'    -- calm lake meditation
    'https://images.unsplash.com/photo-1499728603263-13726abce5fd?w=800'  -- zen stones
WHERE id = 4;

-- Challenge 5 · No Sugar for 14 Days  (Health / Diet)
UPDATE challengesphere.challenges
SET cover_photo_urls =
    'https://images.unsplash.com/photo-1490645935967-10de6ba17061?w=800,' -- healthy meal prep
    'https://images.unsplash.com/photo-1610832958506-aa56368176cf?w=800,' -- fruit bowl
    'https://images.unsplash.com/photo-1568702846914-96b305d2ebb1?w=800,' -- vegetables flat lay
    'https://images.unsplash.com/photo-1504674900247-0877df9cc836?w=800'  -- clean eating
WHERE id = 5;

COMMIT;

-- ── STEP 3: Verification ──────────────────────────────────────────────────
SELECT
    id,
    title,
    CASE
        WHEN cover_photo_urls IS NULL THEN '⚠ no cover'
        ELSE CONCAT(
            ARRAY_LENGTH(STRING_TO_ARRAY(cover_photo_urls, ','), 1)::TEXT,
            ' photo(s) — ',
            LEFT(cover_photo_urls, 60),
            '...'
        )
    END AS cover_status
FROM challengesphere.challenges
ORDER BY id;
