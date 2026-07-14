import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const sql = readFileSync(
  new URL('../sql/seed_first_user_starter_challenges.sql', import.meta.url),
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
assert.match(sql, /ON CONFLICT \(user_id, challenge_id, day_number\) DO NOTHING/);
assert.equal((sql.match(/Day [123]:/g) ?? []).length, 12);

console.log('First-user starter challenge seed contract passes.');
