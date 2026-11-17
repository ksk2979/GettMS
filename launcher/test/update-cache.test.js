const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { hasPending, clearUpdateCache } = require('../core/update-cache');

function cache() {
  const dir = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'gett-')), 'gett-launcher-updater');
  fs.mkdirSync(path.join(dir, 'pending'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'installer.exe'), 'old');
  fs.writeFileSync(path.join(dir, 'pending', 'Setup-9.exe'), 'new');
  fs.writeFileSync(path.join(dir, 'pending', 'update-info.json'), JSON.stringify({ fileName: 'Setup-9.exe', sha512: 'abc' }));
  return dir;
}
test('hasPending: only when hash matches and the file is there', () => {
  const dir = cache();
  assert.ok(hasPending(dir, 'abc'));
  assert.ok(!hasPending(dir, 'other'));
  assert.ok(!hasPending(dir, undefined));
  fs.rmSync(path.join(dir, 'pending', 'Setup-9.exe'));
  assert.ok(!hasPending(dir, 'abc'));
  assert.ok(!hasPending(path.join(dir, 'nope'), 'abc'));
});
test('clearUpdateCache: removes installers, refuses other folders', () => {
  const dir = cache();
  assert.ok(clearUpdateCache(dir));
  assert.ok(!fs.existsSync(dir));
  assert.ok(!clearUpdateCache(dir));
  const other = fs.mkdtempSync(path.join(os.tmpdir(), 'gett-'));
  assert.ok(!clearUpdateCache(other));
  assert.ok(fs.existsSync(other));
});
