// mods 폴더를 설정 목록과 동기화. Modrinth에서 1.21.1 + neoforge 최신 정식 빌드를 찾아
// 필수 의존성까지 따라가며 받고, 목록에 없는 jar는 제거.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { fetchJson, download } = require('./net');
const { planLock, forSide } = require('./lock');

const API = 'https://api.modrinth.com/v2';

async function resolve(slugs, mc, log) {
  const want = new Map(); // slug -> {filename,url,sha512}
  const queue = [...slugs];
  const seen = new Set();
  while (queue.length) {
    const slug = queue.shift();
    if (seen.has(slug)) continue;
    seen.add(slug);
    const all = await fetchJson(`${API}/project/${slug}/version?loaders=${encodeURIComponent('["neoforge"]')}&game_versions=${encodeURIComponent(JSON.stringify([mc]))}`);
    const vs = all.filter(v => v.loaders.includes('neoforge') && v.game_versions.includes(mc));
    if (!vs.length) throw new Error(`모드 "${slug}"의 ${mc} NeoForge 빌드가 없음`);
    const v = vs.find(x => x.version_type === 'release') || vs[0];
    const file = v.files.find(f => f.primary) || v.files[0];
    want.set(slug, { filename: file.filename, url: file.url, sha512: file.hashes.sha512, size: file.size, version: v.version_number });
    for (const d of v.dependencies || []) {
      if (d.dependency_type !== 'required' || !d.project_id) continue;
      const p = await fetchJson(`${API}/project/${d.project_id}`);
      // 여러 로더용 jar 는 다른 로더의 의존성(예: Fabric API)도 적어 둔다. NeoForge 판이 아예 없는 건 건너뜀.
      if (!(p.loaders || []).includes('neoforge')) { log(`  의존성 건너뜀(NeoForge용 아님): ${slug} -> ${p.slug}`); continue; }
      if (!seen.has(p.slug)) { log(`  의존성: ${slug} -> ${p.slug}`); queue.push(p.slug); }
    }
  }
  return want;
}

function planCustom(customMods, side) {
  return (customMods || [])
    .filter(c => c.side === 'both' || c.side === side)
    .map(c => ({ filename: c.file, url: c.url, sha512: c.sha512 }));
}

async function syncMods(slugs, mc, root, log, progress, customMods) {
  const dir = path.join(root, 'mods');
  fs.mkdirSync(dir, { recursive: true });
  log(`모드 목록 확인 중 (${slugs.length}개 + 의존성)...`);
  const want = await resolve(slugs, mc, log);
  const custom = planCustom(customMods, 'client');
  const keep = new Set([...want.values()].map(w => w.filename).concat(custom.map(c => c.filename)));

  let downloaded = 0, removed = 0;
  for (const [slug, w] of want) {
    const dest = path.join(dir, w.filename);
    if (fs.existsSync(dest)) continue;
    log(`받는 중: ${slug} ${w.version}`);
    await download(w.url, dest, { algo: 'sha512', hex: w.sha512 }, progress);
    downloaded++;
  }
  for (const c of custom) {
    const dest = path.join(dir, c.filename);
    if (fs.existsSync(dest)) continue;
    log(`받는 중(직접 URL): ${c.filename}`);
    await download(c.url, dest, { algo: 'sha512', hex: c.sha512 }, progress);
    downloaded++;
  }
  for (const f of fs.readdirSync(dir)) {
    if (f.endsWith('.jar') && !keep.has(f)) { fs.rmSync(path.join(dir, f)); log(`제거: ${f}`); removed++; }
  }
  log(`모드 동기화 완료: 총 ${want.size + custom.length}개, 새로 받음 ${downloaded}, 제거 ${removed}`);
  return [...want.values()].map(w => w.filename).concat(custom.map(c => c.filename));
}

// mods 폴더의 jar → { 파일이름: 크기 }.
function jarSizes(dir) {
  const out = {};
  if (!fs.existsSync(dir)) return out;
  for (const f of fs.readdirSync(dir)) {
    if (f.endsWith('.jar')) out[f] = fs.statSync(path.join(dir, f)).size;
  }
  return out;
}

function sha512Of(file) {
  return crypto.createHash('sha512').update(fs.readFileSync(file)).digest('hex');
}

// lock 기준 동기화: 없거나 크기가 다른 파일만 받고(해시 검증), lock 밖 jar 는 지운다. verify 면 있는 파일도 해시를 계산해
// 다르면 다시 받는다(파일 검사).
async function syncLockedMods(lock, root, log, progress, { verify = false } = {}) {
  const dir = path.join(root, 'mods');
  fs.mkdirSync(dir, { recursive: true });
  const plan = planLock(lock, 'client', jarSizes(dir));
  const todo = [...plan.download];
  if (verify) {
    log('파일 검사: 모든 모드 해시 확인 중...');
    const queued = new Set(todo.map(e => e.file));
    for (const e of forSide(lock, 'client')) {
      if (queued.has(e.file)) continue;
      if (sha512Of(path.join(dir, e.file)) !== e.sha512.toLowerCase()) { log(`손상됨: ${e.file}`); todo.push(e); }
    }
  }
  for (const e of todo) {
    log(`받는 중: ${e.slug} (${e.file})`);
    await download(e.url, path.join(dir, e.file), { algo: 'sha512', hex: e.sha512 }, progress);
  }
  for (const f of plan.remove) { fs.rmSync(path.join(dir, f)); log(`제거: ${f}`); }
  log(`모드 확인 완료 (고정 목록): 총 ${forSide(lock, 'client').length}개, 새로 받음 ${todo.length}, 제거 ${plan.remove.length}`);
}

module.exports = { syncMods, planCustom, resolve, syncLockedMods, jarSizes };
