# App Store screenshot fixture v1

This fixture supplies polished public content for App Store screenshots and for a fresh Apple-authenticated reviewer. It also contains one private crew for private-flow captures. It is not a login fixture and is not a Flyway migration.

## Reserved fictional identities

| ID | Username | Display name | Country | Login state |
|---:|---|---|---|---|
| `910000101` | `maya_moves` | Maya Rao | India | No password or provider identity |
| `910000102` | `nora_makes` | Nora Ellis | United Kingdom | No password or provider identity |
| `910000103` | `leo_outside` | Leo Martins | Brazil | No password or provider identity |
| `910000104` | `sami_reads` | Sami Park | Canada | No password or provider identity |
| `910000105` | `devon_daily` | Devon Brooks | United States | No password or provider identity |

Every email uses the matching local part at `appstore-fixture.invalid`. The fixed high-range IDs make every private S3 object key deterministic. Do not reuse these IDs, usernames, emails, titles, or the `#appstore_fixture_v1` tag.

## Content included

- Three tagged public challenges: morning movement, daily creativity, and one-off reading.
- One tagged private, already-started trail crew.
- Seventeen historical submissions with eighteen photos, including a two-photo morning post.
- Fifteen comments, twenty-nine glories, twelve follow relationships, sixteen participation rows, and coherent source-derived counters and leaderboards.
- All submission images are recorded as `PASSED` only after the operator provides the media-ready acknowledgement.

The six generated progress themes map coherently as follows:

- Sunrise and yoga: morning-light and trail content.
- Watercolor: Nora and Maya creative posts.
- Coding/building: Devon's thirty-minute working prototype.
- Cozy learning: Sami's reading submission.
- Meal prep: Maya's “chapter with breakfast” reading submission.

## Media preflight and exact object keys

Generate fictional-only assets, visually inspect every asset for safety and consistency, upload the bytes as actual PNG files, and set S3 `Content-Type: image/png`. The application signs these private keys; do not make the bucket public. Confirm every object with `HeadObject` before seeding.

Profiles:

```text
profiles/user_910000101/1787000000101_a0010101-0000-4000-8000-000000000101.png
profiles/user_910000102/1787000000102_a0010102-0000-4000-8000-000000000102.png
profiles/user_910000103/1787000000103_a0010103-0000-4000-8000-000000000103.png
profiles/user_910000104/1787000000104_a0010104-0000-4000-8000-000000000104.png
profiles/user_910000105/1787000000105_a0010105-0000-4000-8000-000000000105.png
```

Challenge covers:

```text
challenges/user_910000101/covers/1787000001001_c1010001-0000-4000-8000-000000000001.png
challenges/user_910000102/covers/1787000001002_c1020001-0000-4000-8000-000000000002.png
challenges/user_910000104/covers/1787000001003_c1040001-0000-4000-8000-000000000003.png
challenges/user_910000103/covers/1787000001004_c1030001-0000-4000-8000-000000000004.png
```

Submission photos:

```text
submissions/user_910000101/SUBMISSION_PHOTO/1787000010001_d1010001-0000-4000-8000-000000000001.png
submissions/user_910000101/SUBMISSION_PHOTO/1787000010002_d1010002-0000-4000-8000-000000000002.png
submissions/user_910000101/SUBMISSION_PHOTO/1787000010003_d1010003-0000-4000-8000-000000000003.png
submissions/user_910000101/SUBMISSION_PHOTO/1787000010004_d1010004-0000-4000-8000-000000000004.png
submissions/user_910000105/SUBMISSION_PHOTO/1787000010005_d1050001-0000-4000-8000-000000000005.png
submissions/user_910000105/SUBMISSION_PHOTO/1787000010006_d1050002-0000-4000-8000-000000000006.png
submissions/user_910000103/SUBMISSION_PHOTO/1787000010007_d1030001-0000-4000-8000-000000000007.png
submissions/user_910000102/SUBMISSION_PHOTO/1787000010008_d1020001-0000-4000-8000-000000000008.png
submissions/user_910000102/SUBMISSION_PHOTO/1787000010009_d1020002-0000-4000-8000-000000000009.png
submissions/user_910000102/SUBMISSION_PHOTO/1787000010010_d1020003-0000-4000-8000-000000000010.png
submissions/user_910000105/SUBMISSION_PHOTO/1787000010011_d1050004-0000-4000-8000-000000000011.png
submissions/user_910000101/SUBMISSION_PHOTO/1787000010012_d1010006-0000-4000-8000-000000000012.png
submissions/user_910000104/SUBMISSION_PHOTO/1787000010013_d1040002-0000-4000-8000-000000000013.png
submissions/user_910000101/SUBMISSION_PHOTO/1787000010014_d1010007-0000-4000-8000-000000000014.png
submissions/user_910000103/SUBMISSION_PHOTO/1787000010015_d1030002-0000-4000-8000-000000000015.png
submissions/user_910000103/SUBMISSION_PHOTO/1787000010016_d1030003-0000-4000-8000-000000000016.png
submissions/user_910000105/SUBMISSION_PHOTO/1787000010017_d1050003-0000-4000-8000-000000000017.png
submissions/user_910000101/SUBMISSION_PHOTO/1787000010018_d1010005-0000-4000-8000-000000000018.png
```

## Seed

Use the same authenticated PostgreSQL connection process described in `BACKEND_RUNBOOK.md`. The seed requires the schema through the iOS release migrations and an exact operator acknowledgement:

```sh
psql -v ON_ERROR_STOP=1 \
  -v app_store_fixture_media_ready=YES \
  -f sql/seed_app_store_screenshot_fixture_v1.sql
```

The script acquires a fixture-specific transaction advisory lock. Before inserting, it aborts on reserved ID/username/email collisions, login/device/subscription state, fixture-title or marker conflicts, non-fixture content owned by a synthetic account, mismatched historical rows, invalid media ownership paths, or duplicate deterministic keys. Re-running the unchanged v1 seed resolves to the same rows and keys.

The seed writes directly to PostgreSQL, so it does not run application cache hooks. After a successful commit, use an authenticated admin session to invalidate feeds, hashtags, recent/trending challenges, the five fixed user IDs, and the four challenge IDs printed by the seed. Do not use the all-cache deletion endpoint.

## Cleanup

Cleanup is intentionally stricter than seeding. It aborts if any real user has joined, submitted, commented, gloried, followed, been invited, reported content, or otherwise crossed the fixture boundary. This prevents cleanup from deleting real UGC or changing a real user's counters.

```sh
psql -v ON_ERROR_STOP=1 \
  -v app_store_fixture_cleanup_confirm=DELETE_APP_STORE_FIXTURE_V1 \
  -f sql/cleanup_app_store_screenshot_fixture_v1.sql
```

The cleanup transaction deletes only the exact tagged challenges, their closed synthetic dependency island, the exact internal follows, and the five exact reserved accounts. It prints the eligible S3 keys but never deletes objects. Remove those keys separately only after reviewing the output, then invalidate the same targeted cache families.

## Assumptions

- PostgreSQL schema is current through V53 and the owned-media constraints are installed.
- The fixed IDs are intentionally far outside the normal sequence range; explicit inserts do not advance the `users_id_seq` sequence.
- Screenshots are captured for the August 2026 release window. A future campaign should create v2 with new immutable dates/keys instead of mutating v1.
- Public fixture content is visible to a fresh real Apple-authenticated reviewer; the synthetic accounts themselves cannot sign in.
