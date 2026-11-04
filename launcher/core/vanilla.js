// 바닐라 라이브러리와 에셋을 "패치" 단계에서 미리 받아 둔다.
// MCLC는 실행 직전에 같은 파일을 확인하므로, 미리 받아 두면 시작이 바로 된다.
const fs = require('fs');
const path = require('path');
const { fetchJson, download } = require('./net');

const MANIFEST = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';
const RESOURCES = 'https://resources.download.minecraft.net';

function readJson(p) { return JSON.parse(fs.readFileSync(p, 'utf8')); }

async function vanillaJson(mc, root) {
  const local = path.join(root, 'versions', mc, `${mc}.json`);
  if (fs.existsSync(local)) return readJson(local);
  const entry = (await fetchJson(MANIFEST)).versions.find(v => v.id === mc);
  if (!entry) throw new Error(`마인크래프트 ${mc} 버전을 찾을 수 없음`);
  const json = await fetchJson(entry.url);
  fs.mkdirSync(path.dirname(local), { recursive: true });
  fs.writeFileSync(local, JSON.stringify(json));
  return json;
}

function allowedOnWindows(lib) {
  if (!lib.rules) return true;
  let ok = false;
  for (const r of lib.rules) {
    const matches = !r.os || r.os.name === 'windows';
    if (matches) ok = r.action === 'allow';
  }
  return ok;
}

// 여러 작업을 동시에 (최대 n개)
async function pool(items, n, fn) {
  let i = 0;
  const workers = Array.from({ length: n }, async () => { while (i < items.length) await fn(items[i++]); });
  await Promise.all(workers);
}

async function ensureLibraries(jsons, root, report) {
  const libDir = path.join(root, 'libraries');
  const todo = [];
  for (const json of jsons) for (const lib of json.libraries || []) {
    if (!allowedOnWindows(lib)) continue;
    const a = lib.downloads && lib.downloads.artifact;
    if (!a || !a.url || !a.path) continue;
    const dest = path.join(libDir, a.path);
    if (!fs.existsSync(dest)) todo.push({ url: a.url, dest, sha1: a.sha1 });
  }
  let done = 0;
  report('libraries', done, todo.length);
  await pool(todo, 6, async t => {
    await download(t.url, t.dest, t.sha1 ? { algo: 'sha1', hex: t.sha1 } : null);
    report('libraries', ++done, todo.length);
  });
  return todo.length;
}

async function ensureAssets(vjson, root, report) {
  const idx = vjson.assetIndex;
  const idxPath = path.join(root, 'assets', 'indexes', `${idx.id}.json`);
  if (!fs.existsSync(idxPath)) await download(idx.url, idxPath, { algo: 'sha1', hex: idx.sha1 });
  const objects = readJson(idxPath).objects;
  const todo = [];
  for (const { hash } of Object.values(objects)) {
    const dest = path.join(root, 'assets', 'objects', hash.slice(0, 2), hash);
    if (!fs.existsSync(dest)) todo.push({ hash, dest });
  }
  let done = 0;
  report('assets', done, todo.length);
  await pool(todo, 16, async t => {
    await download(`${RESOURCES}/${t.hash.slice(0, 2)}/${t.hash}`, t.dest, { algo: 'sha1', hex: t.hash });
    report('assets', ++done, todo.length);
  });
  return todo.length;
}

module.exports = { vanillaJson, ensureLibraries, ensureAssets, readJson };
