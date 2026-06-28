# ChallengeSphere Backend Load Test

This folder contains a JMeter plan for a 1000 DAU authenticated app simulation.

## Files

- `challengesphere-1000-dau.jmx`: main JMeter test plan.
- `data/users.csv`: login users. Replace with seeded test users before a real run.
- `data/challenge_ids.csv`: active public challenge IDs and hashtags used for discovery/detail journeys.
- `data/submission_ids.csv`: visible submission IDs used for feed, comment, and glory journeys.
- `data/target_users.csv`: profile/search targets used by social journeys.

## Default Journey Mix

- 45% home/feed: `/auth/me`, explore feed, trending feed, notification unread count.
- 25% challenge discovery: popular, recent, hashtag search, challenge detail, leaderboard.
- 15% social profile: suggested users, search, relationship, target user feed.
- 10% engagement: submission detail, comments, glory state, optional comment/glory writes.
- 5% challenge participation: my challenges, progress, global challenge submissions, optional join/submission writes.

By default, mutating requests are disabled. Enable them only against disposable or intentionally seeded data.

## Run

Create the results directory first:

```bash
mkdir -p docs/load-testing/results
```

Run a local full-day 1000 DAU test:

```bash
jmeter -n \
  -t docs/load-testing/challengesphere-1000-dau.jmx \
  -JBASE_PROTOCOL=http \
  -JBASE_HOST=localhost \
  -JBASE_PORT=8080 \
  -JDAU=1000 \
  -JRAMP_SECONDS=1800 \
  -JTEST_DURATION_SECONDS=86400 \
  -l docs/load-testing/results/challengesphere-1000-dau.jtl
```

Run a compressed 15-minute smoke test:

```bash
jmeter -n \
  -t docs/load-testing/challengesphere-1000-dau.jmx \
  -JBASE_HOST=localhost \
  -JBASE_PORT=8080 \
  -JDAU=25 \
  -JRAMP_SECONDS=60 \
  -JTEST_DURATION_SECONDS=900 \
  -l docs/load-testing/results/challengesphere-smoke.jtl
```

Run against a deployed staging or production URL:

```bash
jmeter -n \
  -t docs/load-testing/challengesphere-1000-dau.jmx \
  -JBASE_PROTOCOL=https \
  -JBASE_HOST=your-backend.example.com \
  -JBASE_PORT=443 \
  -JDAU=1000 \
  -JRAMP_SECONDS=1800 \
  -JTEST_DURATION_SECONDS=86400 \
  -JUSERS_CSV=/absolute/path/users.csv \
  -JCHALLENGES_CSV=/absolute/path/challenge_ids.csv \
  -JSUBMISSIONS_CSV=/absolute/path/submission_ids.csv \
  -JTARGET_USERS_CSV=/absolute/path/target_users.csv \
  -l docs/load-testing/results/challengesphere-staging.jtl
```

Enable mutating actions only for seeded load-test data:

```bash
-JENABLE_MUTATIONS=true
```

## Data Requirements

For realistic 1000 DAU, provide at least 1000 rows in `users.csv`. Each row must contain a valid backend username, password, and user ID.

The challenge and submission CSVs should reference public, active, visible records. If these IDs point to private, deleted, or inactive rows, those journey samples will fail with expected authorization or not-found responses.

## Notes

`DAU` here means 1000 unique authenticated virtual users over the configured duration. It is not the same as 1000 always-active concurrent users. For production capacity tests, start with the smoke profile, watch application, database, and Redis metrics, and then increase `DAU` and lower think time gradually.
