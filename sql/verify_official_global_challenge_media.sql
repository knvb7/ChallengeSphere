\set ON_ERROR_STOP on

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM challengesphere.users
        WHERE lower(username) = 'challengesphere'
          AND profile_pic_url IS NOT NULL
          AND profile_pic_url <> ''
    ) THEN
        RAISE EXCEPTION 'Official profile logo is missing';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM challengesphere.challenges challenge
        JOIN challengesphere.users app_user
            ON app_user.id = challenge.creator_id
        WHERE lower(app_user.username) = 'challengesphere'
          AND challenge.active = TRUE
          AND (
              challenge.cover_photo_urls IS NULL
              OR challenge.cover_photo_urls = ''
          )
    ) THEN
        RAISE EXCEPTION 'Official challenge cover images are missing';
    END IF;
END $$;

SELECT 'official_profile_with_logo=' || COUNT(*)
FROM challengesphere.users
WHERE lower(username) = 'challengesphere'
  AND profile_pic_url IS NOT NULL
  AND profile_pic_url <> '';

SELECT 'official_challenges_with_covers=' || COUNT(*)
FROM challengesphere.challenges challenge
JOIN challengesphere.users app_user
    ON app_user.id = challenge.creator_id
WHERE lower(app_user.username) = 'challengesphere'
  AND challenge.active = TRUE
  AND challenge.cover_photo_urls IS NOT NULL
  AND challenge.cover_photo_urls <> '';
