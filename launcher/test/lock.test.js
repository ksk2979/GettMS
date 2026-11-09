const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { validLock, lockSourceOf, activeLock, planLock, fingerprint, canFastStart } = require('../core/lock');

const H = 'a'.repeat(128);
const entry = (file, side = 'both', size = 100) => ({
  slug: file.replace('.jar', ''), file, url: `https://cdn.modrinth.com/data/x/${file}`, sha512: H, size, side
});
const base = { mods: ['a'], clientOnlyMods: [], serverOnlyMods: [], customMods: [] };
const withLock = lock => ({ ...base, minecraftVersion: '1.21.1', neoforgeVersion: '21.1.265', javaMajor: 21, defaultFiles: [],
  lock, lockSource: lockSourceOf(base) });

test('validLock accepts a well-formed lock', () => {
  assert.strictEqual(validLock([entry('a.jar'), { ...entry('g.jar'), url: 'https://github.com/ksk2979/GettMS/releases/download/x/g.jar' }]), true);
});
test('rejects lock with foreign url', () => {
  assert.strictEqual(validLock([{ ...entry('a.jar'), url: 'https://evil.example/a.jar' }]), false);
  assert.strictEqual(validLock([{ ...entry('a.jar'), url: 'https://github.com/other/repo/releases/download/x/a.jar' }]), false);
});
test('rejects bad file names, sizes and sides', () => {
  assert.strictEqual(validLock([{ ...entry('a.jar'), file: '../a.jar' }]), false);
  assert.strictEqual(validLock([entry('a.jar', 'both', 0)]), false);
  assert.strictEqual(validLock([entry('a.jar', 'weird')]), false);
  assert.strictEqual(validLock('nope'), false);
});

test('activeLock only when lockSource matches the current fields', () => {
  const cfg = withLock([entry('a.jar')]);
  assert.deepStrictEqual(activeLock(cfg), cfg.lock);
  assert.strictEqual(activeLock({ ...cfg, mods: ['a', 'b'] }), null);
  assert.strictEqual(activeLock({ ...base }), null);
});

test('planLock downloads missing or resized files and removes strays', () => {
  const lock = [entry('a.jar'), entry('b.jar', 'client', 50), entry('s.jar', 'server')];
  const plan = planLock(lock, 'client', { 'a.jar': 100, 'b.jar': 49, 'old.jar': 7 });
  assert.deepStrictEqual(plan.download.map(e => e.file), ['b.jar']);
  assert.deepStrictEqual(plan.remove, ['old.jar']);
  const server = planLock(lock, 'server', {});
  assert.deepStrictEqual(server.download.map(e => e.file), ['a.jar', 's.jar']);
});

test('fingerprint changes when the lock changes', () => {
  const a = fingerprint(withLock([entry('a.jar')]));
  assert.strictEqual(a, fingerprint(withLock([entry('a.jar')])));
  assert.notStrictEqual(a, fingerprint(withLock([{ ...entry('a.jar'), sha512: 'b'.repeat(128) }])));
});

test('fastStart only when state, files and runtime all match', () => {
  const cfg = withLock([entry('a.jar')]);
  const state = { fingerprint: fingerprint(cfg) };
  const ok = { state, cfg, existing: { 'a.jar': 100 }, haveRuntime: true };
  assert.strictEqual(canFastStart(ok), true);
  assert.strictEqual(canFastStart({ ...ok, existing: {} }), false, 'missing file');
  assert.strictEqual(canFastStart({ ...ok, existing: { 'a.jar': 99 } }), false, 'size differs');
  assert.strictEqual(canFastStart({ ...ok, existing: { 'a.jar': 100, 'x.jar': 1 } }), false, 'stray jar');
  assert.strictEqual(canFastStart({ ...ok, haveRuntime: false }), false);
  assert.strictEqual(canFastStart({ ...ok, state: { fingerprint: 'old' } }), false);
  assert.strictEqual(canFastStart({ ...ok, state: null }), false);
  assert.strictEqual(canFastStart({ ...ok, cfg: { ...base } }), false, 'no active lock');
});

test('repo config lockSource matches its fields', () => {
  const cfg = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'launcher.config.json'), 'utf8'));
  if (!cfg.lock) return; // lock 생성 전
  assert.strictEqual(validLock(cfg.lock), true, 'lock format');
  assert.strictEqual(cfg.lockSource, lockSourceOf(cfg), 'lock is stale: run `npm run lock`');
});
