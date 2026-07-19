import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const sql = readFileSync(
  new URL('../sql/seed_first_user_starter_challenges.sql', import.meta.url),
  'utf8',
);
const provisionScript = readFileSync(
  new URL('./provision-first-user-starter-media.sh', import.meta.url),
  'utf8',
);
const ownershipMigration = readFileSync(
  new URL(
    '../src/main/resources/db/migration/V31__enforce_owned_media_references.sql',
    import.meta.url,
  ),
  'utf8',
);

for (const title of [
  'Morning Momentum: 30 Days of Movement',
  '30 Pages Before Bed',
  'Build in Public: 21 Days of Code',
  'Ten Quiet Minutes',
]) {
  assert.match(sql, new RegExp(title));
}

assert.equal((sql.match(/#starter/g) ?? []).length >= 4, true);
assert.match(sql, /ORDER BY id ASC LIMIT 1/);
assert.match(sql, /ON CONFLICT \(user_id, challenge_id\) DO NOTHING/);
assert.match(
  sql,
  /WHERE NOT EXISTS \(\s*SELECT 1\s*FROM challengesphere\.submissions existing_submission/,
);
assert.equal((sql.match(/Day [123]:/g) ?? []).length, 12);
assert.doesNotMatch(sql, /https?:\/\//i);
assert.match(sql, /challenges\/user_%s\/covers\/%s/);
assert.match(sql, /submissions\/user_%s\/SUBMISSION_PHOTO\/%s/);
assert.match(sql, /Repair pre-existing starter rows/);
assert.match(sql, /Repair pre-existing starter submissions/);

const mediaFilePattern = /\d{13}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.jpg/gi;
const sqlMediaFiles = [...new Set(sql.match(mediaFilePattern) ?? [])].sort();
const provisionedMediaFiles = [
  ...new Set(provisionScript.match(mediaFilePattern) ?? []),
].sort();
assert.equal(sqlMediaFiles.length, 24);
assert.deepEqual(provisionedMediaFiles, sqlMediaFiles);

assert.match(
  ownershipMigration,
  /chk_challenges_owned_cover_media_references/,
);
assert.match(ownershipMigration, /chk_submissions_owned_media_references/);
assert.equal((ownershipMigration.match(/NOT VALID/g) ?? []).length, 2);
assert.equal(
  (ownershipMigration.match(/VALIDATE CONSTRAINT/g) ?? []).length,
  2,
);

console.log('First-user starter challenge seed contract passes.');
