// 모드 버전 고정 목록(lock): 설정의 `lock` 필드. 순수 함수만 둔다(파일·네트워크는 호출 측).
const crypto = require('crypto');

const URL_PREFIXES = ['https://github.com/ksk2979/GettMS/releases/download/', 'https://cdn.modrinth.com/'];
const SIDES = ['both', 'client', 'server'];

function validEntry(e) {
  return !!e && typeof e === 'object'
    && typeof e.slug === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(e.slug)
    && typeof e.file === 'string' && /^[A-Za-z0-9._+-]+\.jar$/.test(e.file) && !e.file.includes('..')
    && typeof e.url === 'string' && URL_PREFIXES.some(p => e.url.startsWith(p)) && !/\.\.|%2e/i.test(e.url)
    && typeof e.sha512 === 'string' && /^[0-9a-fA-F]{128}$/.test(e.sha512)
    && Number.isInteger(e.size) && e.size > 0
    && SIDES.includes(e.side);
}

// 항목이 하나라도 이상하면 lock 전체를 버린다(일부만 쓰지 않음).
function validLock(arr) {
  return Array.isArray(arr) && arr.length > 0 && arr.length <= 400 && arr.every(validEntry)
    && new Set(arr.map(e => e.file)).size === arr.length;
}

const sha256 = s => crypto.createHash('sha256').update(s).digest('hex');

// lock 을 만든 원본 필드의 지문. 사람이 고친 필드와 lock 이 어긋났는지 판단한다.
function lockSourceOf(cfg) {
  return sha256(JSON.stringify({
    mods: cfg.mods || [], clientOnlyMods: cfg.clientOnlyMods || [],
    serverOnlyMods: cfg.serverOnlyMods || [], customMods: cfg.customMods || []
  }));
}

// 쓸 수 있는 lock(형식 정상 + 원본 일치), 아니면 null(옛 방식).
function activeLock(cfg) {
  if (!cfg || !validLock(cfg.lock) || cfg.lockSource !== lockSourceOf(cfg)) return null;
  return cfg.lock;
}

const forSide = (lock, side) => lock.filter(e => e.side === 'both' || e.side === side);

// existing = { 파일이름: 크기 } (mods 폴더의 jar). 없거나 크기가 다른 항목은 받고, lock 밖 jar 는 지운다.
function planLock(lock, side, existing) {
  const mine = forSide(lock, side);
  const keep = new Set(mine.map(e => e.file));
  return {
    download: mine.filter(e => existing[e.file] !== e.size),
    remove: Object.keys(existing).filter(f => !keep.has(f))
  };
}

// 게임에 영향을 주는 설정 + lock 의 지문. 지난번 성공 때와 같으면 준비 단계를 건너뛸 수 있다.
function fingerprint(cfg) {
  return sha256(JSON.stringify({
    mc: cfg.minecraftVersion, neoforge: cfg.neoforgeVersion, java: cfg.javaMajor,
    defaults: cfg.defaultFiles || [], lock: activeLock(cfg)
  }));
}

// 바로 시작: lock 이 쓰이고, 상태 지문이 같고, 클라이언트 파일이 전부 같은 크기로 있고 다른 jar 가 없고, Java·NeoForge 가 있음.
function canFastStart({ state, cfg, existing, haveRuntime }) {
  const lock = activeLock(cfg);
  if (!lock || !haveRuntime || !state || state.fingerprint !== fingerprint(cfg)) return false;
  const plan = planLock(lock, 'client', existing || {});
  return plan.download.length === 0 && plan.remove.length === 0;
}

module.exports = { validLock, lockSourceOf, activeLock, planLock, fingerprint, canFastStart, forSide };
