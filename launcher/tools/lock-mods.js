// 모드 버전 고정 목록(lock) 생성: launcher.config.json 의 mods/clientOnlyMods/serverOnlyMods/customMods 를
// Modrinth(1.21.1 NeoForge 최신 release + 필수 의존성)로 풀어 `lock`, `lockSource` 로 같은 파일에 쓴다.
// 사용: npm run lock            → 이미 lock 에 있는 모드는 그 버전 유지, 새 모드만 최신으로
//       npm run lock -- --update → 전부 최신으로 다시 고정
// 사람은 옛 필드만 고치고 lock 은 항상 이 스크립트로 만든다(저장소 테스트가 어긋남을 잡는다).
const fs = require('fs');
const os = require('os');
const path = require('path');
const { resolve } = require('../core/mods');
const { download, fetchJson } = require('../core/net');
const { lockSourceOf, validLock } = require('../core/lock');

const FILE = path.join(__dirname, '..', 'launcher.config.json');

const RELEASE_URL = /^https:\/\/github\.com\/([^/]+)\/([^/]+)\/releases\/download\/([^/]+)\/([^/]+)$/;

// 이 저장소 릴리스 파일의 크기(GitHub API). 해당 없으면 null.
async function releaseAssetSize(url) {
  const m = RELEASE_URL.exec(url);
  if (!m) return null;
  const [, owner, repo, tag, name] = m;
  const rel = await fetchJson(`https://api.github.com/repos/${owner}/${repo}/releases/tags/${encodeURIComponent(tag)}`);
  const asset = (rel.assets || []).find(a => a.name === decodeURIComponent(name));
  if (!asset) throw new Error(`릴리스 ${tag} 에 ${name} 이 없음`);
  return asset.size;
}

async function downloadSize(c) {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'gett-lock-'));
  try {
    const dest = path.join(tmpDir, c.file);
    await download(c.url, dest, { algo: 'sha512', hex: c.sha512 });
    return fs.statSync(dest).size;
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
}

async function main() {
  const update = process.argv.includes('--update');
  const cfg = JSON.parse(fs.readFileSync(FILE, 'utf8'));
  const mc = cfg.minecraftVersion;
  const old = new Map((Array.isArray(cfg.lock) ? cfg.lock : []).map(e => [e.slug, e]));
  const log = m => console.log(m);

  // 그룹별로 풀고(의존성 포함) side 를 합친다: 공통 → both, 클라 전용·서버 전용 양쪽에서 나오면 both.
  const groups = [['both', cfg.mods || []], ['client', cfg.clientOnlyMods || []], ['server', cfg.serverOnlyMods || []]];
  const bySlug = new Map();
  for (const [side, slugs] of groups) {
    if (!slugs.length) continue;
    log(`[${side}] ${slugs.length}개 풀기...`);
    const want = await resolve(slugs, mc, log);
    for (const [slug, w] of want) {
      const prev = bySlug.get(slug);
      const merged = !prev ? side : (prev.side === side ? side : 'both');
      bySlug.set(slug, { slug, file: w.filename, url: w.url, sha512: w.sha512, size: w.size, side: merged });
    }
  }

  const lock = [];
  for (const e of bySlug.values()) {
    const keep = !update && old.get(e.slug);
    lock.push(keep ? { ...keep, side: e.side } : e);
    if (!keep) log(`고정: ${e.slug} ${e.file}`);
  }

  // customMods: 해시는 설정 값(릴리스 때 확인함)을 쓰고, 크기는 이 저장소 릴리스면 GitHub API 로 조회한다
  // (github.com 다운로드 주소가 504 일 때도 API 는 응답함). 그 밖의 주소는 받아서 해시 확인 + 크기 측정.
  for (const c of cfg.customMods || []) {
    const size = (await releaseAssetSize(c.url)) ?? (await downloadSize(c));
    lock.push({ slug: c.name, file: c.file, url: c.url, sha512: c.sha512.toLowerCase(), size, side: c.side });
    log(`직접 URL: ${c.file} (${size} bytes)`);
  }

  lock.sort((a, b) => a.slug.localeCompare(b.slug));
  if (!validLock(lock)) throw new Error('만든 lock 이 형식 검사를 통과하지 못함 (URL·파일명 확인)');
  cfg.lock = lock;
  cfg.lockSource = lockSourceOf(cfg);
  fs.writeFileSync(FILE, JSON.stringify(cfg, null, 2) + '\n');
  log(`완료: ${lock.length}개 → launcher.config.json`);
}

main().catch(e => { console.error(e.message || e); process.exit(1); });
