# First-User Starter Challenges Design

## Goal

Populate the production ChallengeSphere database with a small, credible starter
collection created by the first user, so a newly installed app has public
challenge content and media-backed progress to display.

## Scope

- Resolve the first user by the lowest active user ID at execution time; do not
  assume a fixed ID.
- Add four active, public DAILY challenges: `Morning Momentum: 30 Days of
  Movement`, `30 Pages Before Bed`, `Build in Public: 21 Days of Code`, and
  `Ten Quiet Minutes`.
- Give each challenge three HTTPS Unsplash image URLs selected for its exact
  subject: exercise, books, desk-based coding, or meditation.
- Add an active `user_challenges` record for the resolved user and three dated,
  public submissions per challenge with relevant image URLs and natural
  descriptions.
- Update the first user's created/accepted challenge counters only for newly
  inserted records.

## Safety And Idempotency

- Seed records are identified by a `#starter` hashtag and stable titles.
- The script uses `ON CONFLICT` and joins by title/tag rather than hard-coded
  challenge or submission IDs, so it can be rerun without duplicates.
- It contains a verification query that reports the selected user, challenge
  count, participation count, and submission count.
- It changes no pre-existing challenge, submission, or user record except the
  first user's derived counters.

## Delivery

The SQL seed is kept in the backend repository. It is run through the
production RDS IAM connection after AWS authentication is refreshed. The
frontend release APK is then rebuilt from the existing Android project and
checked with Android signing and package metadata tools.
