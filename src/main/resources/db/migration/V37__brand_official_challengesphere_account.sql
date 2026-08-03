UPDATE challengesphere.users
SET profile_pic_url = 'official/global-challenges/Challengesphere-logo.png',
    bio = 'Official ChallengeSphere challenges, updates, and weekly cohorts'
WHERE LOWER(username) = 'challengesphere_official'
  AND LOWER(email_id) = 'official-global@challengesphere.app';
