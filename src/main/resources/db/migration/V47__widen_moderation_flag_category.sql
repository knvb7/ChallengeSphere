-- Rekognition returns hierarchical labels such as parent/name paths. These are
-- provider-controlled descriptive values, not the fixed 32-character enum the
-- original schema assumed.
ALTER TABLE challengesphere.moderation_flags
    ALTER COLUMN category TYPE TEXT;
