// mods 폴더를 설정 목록과 동기화. Modrinth에서 1.21.1 + neoforge 최신 정식 빌드를 찾아
// 필수 의존성까지 따라가며 받고, 목록에 없는 jar는 제거.
const fs = require('fs');
const path = require('path');
const { fetchJson, download } = require('./net');

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
    want.set(slug, { filename: file.filename, url: file.url, sha512: file.hashes.sha512, version: v.version_number });
    for (const d of v.dependencies || []) {
      if (d.dependency_type !== 'required' || !d.project_id) continue;
      const p = await fetchJson(`${API}/project/${d.project_id}`);
      if (!seen.has(p.slug)) { log(`  의존성: ${slug} -> ${p.slug}`); queue.push(p.slug); }
    }
  }
  return want;
}

async function syncMods(slugs, mc, root, log, progress) {
  const dir = path.join(root, 'mods');
  fs.mkdirSync(dir, { recursive: true });
  log(`모드 목록 확인 중 (${slugs.length}개 + 의존성)...`);
  const want = await resolve(slugs, mc, log);
  const keep = new Set([...want.values()].map(w => w.filename));

  let downloaded = 0, removed = 0;
  for (const [slug, w] of want) {
    const dest = path.join(dir, w.filename);
    if (fs.existsSync(dest)) continue;
    log(`받는 중: ${slug} ${w.version}`);
    await download(w.url, dest, { algo: 'sha512', hex: w.sha512 }, progress);
    downloaded++;
  }
  for (const f of fs.readdirSync(dir)) {
    if (f.endsWith('.jar') && !keep.has(f)) { fs.rmSync(path.join(dir, f)); log(`제거: ${f}`); removed++; }
  }
  log(`모드 동기화 완료: 총 ${want.size}개, 새로 받음 ${downloaded}, 제거 ${removed}`);
  return [...want.values()].map(w => w.filename);
}

module.exports = { syncMods };
