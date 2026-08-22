ALTER TABLE challengesphere.official_challenge_templates
    ADD COLUMN IF NOT EXISTS cover_photo_key VARCHAR(255);

UPDATE challengesphere.official_challenge_templates
SET cover_photo_key = CASE
    WHEN categories ILIKE '%Fitness%'
        THEN 'official/global-challenges/official-fitness.jpg'
    WHEN categories ILIKE '%Productivity%'
        THEN 'official/global-challenges/official-productivity.jpg'
    WHEN categories ILIKE '%Learning%'
        THEN 'official/global-challenges/official-reading-study.jpg'
    WHEN categories ILIKE '%Creativity%'
        THEN 'official/global-challenges/official-creative.jpg'
    WHEN categories ILIKE '%Health%'
        THEN 'official/global-challenges/official-wellness.jpg'
    ELSE 'official/global-challenges/official-fun-social-bright.jpg'
END
WHERE cover_photo_key IS NULL OR BTRIM(cover_photo_key) = '';

ALTER TABLE challengesphere.official_challenge_templates
    ALTER COLUMN cover_photo_key SET NOT NULL;

CREATE OR REPLACE FUNCTION challengesphere.apply_official_challenge_cover()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.official_template_key IS NOT NULL
       AND (NEW.cover_photo_urls IS NULL OR BTRIM(NEW.cover_photo_urls) = '') THEN
        SELECT template.cover_photo_key
        INTO NEW.cover_photo_urls
        FROM challengesphere.official_challenge_templates template
        WHERE template.template_key = NEW.official_template_key;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_apply_official_challenge_cover
    ON challengesphere.challenges;

CREATE TRIGGER trg_apply_official_challenge_cover
BEFORE INSERT OR UPDATE OF official_template_key, cover_photo_urls
ON challengesphere.challenges
FOR EACH ROW
EXECUTE FUNCTION challengesphere.apply_official_challenge_cover();

UPDATE challengesphere.challenges challenge
SET cover_photo_urls = template.cover_photo_key
FROM challengesphere.official_challenge_templates template
WHERE challenge.official_template_key = template.template_key
  AND (challenge.cover_photo_urls IS NULL OR BTRIM(challenge.cover_photo_urls) = '');

COMMENT ON COLUMN challengesphere.official_challenge_templates.cover_photo_key IS
    'Controlled media object key assigned to current and future official cohorts.';
