const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { RULES } = require('../core/config');
const { seedDefaultFiles } = require('../core/defaults');

const U = 'https://raw.githubusercontent.com/ksk2979/GettMS/main/launcher/defaults/';
const ok = p => RULES.defaultFiles([{ path: p, url: U + p }]);

test('config: valid shape accepted', () => {
  assert.ok(ok('options.txt'));
  assert.ok(ok('config/xaero/minimap/client.cfg'));
  assert.ok(RULES.defaultFiles([]));
});
test('config: bad paths/urls rejected', () => {
  for (const p of ['../x', 'a/../x', '/abs', 'C:/x', 'C:x', 'a\\b', '', 'a//b']) assert.ok(!ok(p), p);
  assert.ok(!RULES.defaultFiles([{ path: 'a', url: 'https://evil.com/a' }]));
  assert.ok(!RULES.defaultFiles([{ path: 'a', url: 'http://raw.githubusercontent.com/ksk2979/GettMS/a' }]));
  assert.ok(!RULES.defaultFiles('x'));
});

function withFetch(impl, fn) {
  const orig = global.fetch;
  global.fetch = impl;
  return Promise.resolve(fn()).finally(() => { global.fetch = orig; });
}
const body = s => async () => new Response(s, { status: 200 });

test('seedDefaultFiles: writes missing, skips existing', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gett-'));
  fs.writeFileSync(path.join(root, 'options.txt'), 'mine');
  const list = [{ path: 'options.txt', url: U + 'options.txt' }, { path: 'config/a/b.cfg', url: U + 'config/a/b.cfg' }];
  const logs = [];
  const n = await withFetch(body('default'), () => seedDefaultFiles(list, root, m => logs.push(m)));
  assert.strictEqual(n, 1);
  assert.strictEqual(fs.readFileSync(path.join(root, 'options.txt'), 'utf8'), 'mine');
  assert.strictEqual(fs.readFileSync(path.join(root, 'config/a/b.cfg'), 'utf8'), 'default');
  assert.ok(logs.some(l => l.includes('기본 설정 적용: 1개')));
  fs.rmSync(root, { recursive: true, force: true });
});
test('seedDefaultFiles: failure does not throw, no .part left', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gett-'));
  const list = [{ path: 'config/x.cfg', url: U + 'x' }, { path: '../out.txt', url: U + 'y' }];
  const logs = [];
  const bad = async () => ({ ok: true, status: 200, headers: new Headers(), body: (async function* () { yield Buffer.from('a'); throw new Error('끊김'); })() });
  const n = await withFetch(bad, () => seedDefaultFiles(list, root, m => logs.push(m)));
  assert.strictEqual(n, 0);
  assert.ok(!fs.existsSync(path.join(root, 'config/x.cfg.part')));
  assert.ok(!fs.existsSync(path.join(root, 'config/x.cfg')));
  assert.strictEqual(logs.length, 2);
  fs.rmSync(root, { recursive: true, force: true });
});
