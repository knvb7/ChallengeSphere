-- Add cover_photo_urls column to challenges table
-- Stores up to 5 comma-separated S3 object keys or URLs for challenge cover photos
ALTER TABLE challengesphere.challenges
    ADD COLUMN IF NOT EXISTS cover_photo_urls TEXT DEFAULT NULL;
