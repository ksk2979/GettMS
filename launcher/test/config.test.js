const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { loadConfig } = require('../core/config');

function setup() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'gett-cfg-'));
  const localFile = path.join(dir, 'local.json');
  fs.writeFileSync(localFile, JSON.stringify({ remoteConfigUrl: 'https://example/cfg.json', serverName: 'local', memoryMaxMB: 4096, memoryMinMB: 1024 }));
  return { dir, localFile, cacheFile: path.join(dir, 'cache.json') };
}
const quiet = () => {};

test('remote success is applied and cached', async () => {
  const { localFile, cacheFile } = setup();
  const cfg = await loadConfig(quiet, { localFile, cacheFile, fetch: async () => ({ serverName: 'remote' }) });
  assert.strictEqual(cfg.serverName, 'remote');
  assert.strictEqual(cfg.source, 'remote');
  assert.strictEqual(JSON.parse(fs.readFileSync(cacheFile, 'utf8')).serverName, 'remote');
});

test('loadConfig uses cache when remote fails', async () => {
  const { localFile, cacheFile } = setup();
  fs.writeFileSync(cacheFile, JSON.stringify({ serverName: 'cached' }));
  const cfg = await loadConfig(quiet, { localFile, cacheFile, fetch: async () => { throw new Error('HTTP 504'); } });
  assert.strictEqual(cfg.serverName, 'cached');
  assert.strictEqual(cfg.source, 'cache');
});

test('falls back to built-in config when remote and cache both fail', async () => {
  const { localFile, cacheFile } = setup();
  const cfg = await loadConfig(quiet, { localFile, cacheFile, fetch: async () => { throw new Error('offline'); } });
  assert.strictEqual(cfg.serverName, 'local');
  assert.strictEqual(cfg.source, 'local-fallback');
});
