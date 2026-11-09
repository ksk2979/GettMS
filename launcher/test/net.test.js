const test = require('node:test');
const assert = require('node:assert');
const { isRetryable, withRetry } = require('../core/net');

const httpErr = status => Object.assign(new Error(`HTTP ${status}`), { status });
const noSleep = () => Promise.resolve();

test('5xx and network errors are retryable, 4xx are not', () => {
  for (const s of [500, 502, 503, 504]) assert.strictEqual(isRetryable(httpErr(s)), true, String(s));
  assert.strictEqual(isRetryable(new TypeError('fetch failed')), true);
  for (const s of [403, 404]) assert.strictEqual(isRetryable(httpErr(s)), false, String(s));
});

test('retries until success', async () => {
  let n = 0;
  const r = await withRetry(async () => { n++; if (n < 3) throw httpErr(504); return 'ok'; }, { sleep: noSleep });
  assert.strictEqual(r, 'ok');
  assert.strictEqual(n, 3);
});

test('4xx fails at once', async () => {
  let n = 0;
  await assert.rejects(withRetry(async () => { n++; throw httpErr(404); }, { sleep: noSleep }), /404/);
  assert.strictEqual(n, 1);
});

test('gives up with the last error after all retries', async () => {
  let n = 0;
  await assert.rejects(withRetry(async () => { n++; throw httpErr(500 + n); }, { sleep: noSleep }), /503/);
  assert.strictEqual(n, 3);
});
