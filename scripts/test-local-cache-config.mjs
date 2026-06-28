import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const localProperties = readFileSync(
  new URL('../src/main/resources/application-local.properties', import.meta.url),
  'utf8'
);

assert.match(
  localProperties,
  /spring\.redis\.timeout=\$\{REDIS_TIMEOUT_MS:2000\}/,
  'local Redis timeout should fail fast instead of stalling challenge requests for 60 seconds'
);
assert.match(
  localProperties,
  /spring\.redis\.jedis\.pool\.max-wait=\$\{REDIS_POOL_MAX_WAIT_MS:1000\}/,
  'local Redis pool wait should be configurable and not set to a 1ms default'
);

console.log('local cache configuration checks passed');
