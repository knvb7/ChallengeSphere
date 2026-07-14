# First-User Starter Challenges Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Seed the first active user with four public, media-rich starter challenges and build a verified Android release APK.

**Architecture:** A standalone PostgreSQL transaction resolves the first active user, inserts only absent `#starter` challenge records, adds that user's participation and dated submissions, and increments derived counters only for rows it just inserted. A Node test inspects the seed's stable content and idempotency clauses before the seed is run through production RDS IAM authentication.

**Tech Stack:** PostgreSQL, Node.js assertions, AWS RDS IAM authentication, Expo/React Native Android Gradle build tools.

## Global Constraints

- The APK must keep the configured HTTPS production API base URL.
- Seed inserts must be additive and idempotent; no existing challenge, submission, or user content may be deleted or overwritten.
- The first user must be selected by `id ASC` among active users at execution time.
- Each of the four challenges must be public, active, DAILY, and have exactly three HTTPS Unsplash cover URLs.
- Each challenge must have an active participation record and three public, image-backed submissions for the resolved user.

---

### Task 1: Seed Contract Test

**Files:**
- Create: `scripts/test-seed-first-user-starter-challenges.mjs`
- Test: `scripts/test-seed-first-user-starter-challenges.mjs`

**Interfaces:**
- Consumes: `sql/seed_first_user_starter_challenges.sql`
- Produces: exit status 0 only when the seed lists four named challenges, 12 submissions, `#starter`, runtime first-user selection, and conflict-safe inserts.

- [ ] **Step 1: Write the failing test**

```js
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const sql = readFileSync(new URL('../sql/seed_first_user_starter_challenges.sql', import.meta.url), 'utf8');
for (const title of ['Morning Momentum: 30 Days of Movement', '30 Pages Before Bed', 'Build in Public: 21 Days of Code', 'Ten Quiet Minutes']) {
  assert.match(sql, new RegExp(title));
}
assert.equal((sql.match(/#starter/g) ?? []).length >= 4, true);
assert.match(sql, /ORDER BY id ASC LIMIT 1/);
assert.match(sql, /ON CONFLICT \(user_id, challenge_id\) DO NOTHING/);
assert.match(sql, /ON CONFLICT \(user_id, challenge_id, day_number\) DO NOTHING/);
assert.equal((sql.match(/Day [123]:/g) ?? []).length, 12);
console.log('First-user starter challenge seed contract passes.');
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/test-seed-first-user-starter-challenges.mjs`

Expected: exit status 1 because the SQL seed does not yet exist.

### Task 2: Idempotent Database Seed

**Files:**
- Create: `sql/seed_first_user_starter_challenges.sql`
- Test: `scripts/test-seed-first-user-starter-challenges.mjs`

**Interfaces:**
- Consumes: production `challengesphere.users`, `challenges`, `user_challenges`, and `submissions` tables.
- Produces: four `#starter` challenges, four user-challenge joins, 12 distinct daily submissions, and a final count query.

- [ ] **Step 1: Write the minimal seed**

```sql
BEGIN;
CREATE TEMP TABLE starter_seed_target AS
SELECT id FROM challengesphere.users WHERE active = true ORDER BY id ASC LIMIT 1;
-- Insert absent challenge titles with public DAILY metadata and three cover URLs.
-- Insert participation rows and increment challenges_accepted only for returned inserts.
-- Insert three submissions per challenge with ON CONFLICT (user_id, challenge_id, day_number) DO NOTHING.
COMMIT;
```

- [ ] **Step 2: Run test to verify it passes**

Run: `node scripts/test-seed-first-user-starter-challenges.mjs`

Expected: `First-user starter challenge seed contract passes.`

- [ ] **Step 3: Commit source-only changes**

```bash
git add sql/seed_first_user_starter_challenges.sql scripts/test-seed-first-user-starter-challenges.mjs
git commit -m "feat: seed first user starter challenges"
```

### Task 3: Production Database Execution

**Files:**
- Execute: `sql/seed_first_user_starter_challenges.sql`

**Interfaces:**
- Consumes: valid AWS default credentials able to generate the RDS IAM token.
- Produces: production rows reported by the SQL verification query.

- [ ] **Step 1: Verify AWS identity and RDS IAM access**

Run: `./scripts/run-prod-iam-local.sh --check-db`

Expected: a successful caller identity and PostgreSQL connection.

- [ ] **Step 2: Run the seed**

Run: generate an IAM token with `aws rds generate-db-auth-token`, set it as `PGPASSWORD`, and execute `psql -v ON_ERROR_STOP=1 -f sql/seed_first_user_starter_challenges.sql` against the production RDS endpoint.

- [ ] **Step 3: Verify the result**

Run: use the script's final query to confirm exactly 4 starter challenges, 4 joined records, and 12 submissions for the selected first user.

### Task 4: Android Release APK

**Files:**
- Output: `ChallengeSphere-Frontend/output/challengesphere-updated-<timestamp>.apk`

**Interfaces:**
- Consumes: the current frontend workspace and its configured production API URL.
- Produces: a signed APK for package `com.challengesphere.app`.

- [ ] **Step 1: Build the release**

Run: `./gradlew :app:assembleRelease` from `ChallengeSphere-Frontend/android`.

- [ ] **Step 2: Copy the APK to output**

Run: copy `android/app/build/outputs/apk/release/app-release.apk` to the timestamped output path.

- [ ] **Step 3: Verify install artifact**

Run: `apksigner verify --verbose --print-certs <apk>` and `aapt dump badging <apk>`.
