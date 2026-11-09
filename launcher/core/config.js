// 설정 읽기. remoteConfigUrl 이 있으면 GitHub 등 원격 JSON 을 우선 적용하고, 실패하면 내장 설정 사용.
// 원격 값은 형식을 검사해서 통과한 항목만 받아들인다 (경로 조작, 이상한 값 차단).
const fs = require('fs');
const path = require('path');
const { fetchJson } = require('./net');
const { validLock } = require('./lock');

const LOCAL = path.join(__dirname, '..', 'launcher.config.json');

// 기본 설정 파일 항목 검사: 경로는 options.txt 또는 config/ 아래만, URL 은 이 저장소의 raw 주소만.
function validDefaultFile(f) {
  if (!f || typeof f !== 'object' || typeof f.path !== 'string' || typeof f.url !== 'string') return false;
  const p = f.path;
  if (p.length === 0 || p.length > 200) return false;
  if (p !== 'options.txt' && !p.startsWith('config/')) return false;
  if (!p.split('/').every(seg => /^[A-Za-z0-9._-]+$/.test(seg) && seg !== '..' && seg !== '.')) return false;
  if (/\.\.|%2e/i.test(f.url)) return false;
  try {
    const u = new URL(f.url);
    return u.origin === 'https://raw.githubusercontent.com' && u.pathname.startsWith('/ksk2979/GettMS/');
  } catch { return false; }
}

const RULES = {
  serverName: v => typeof v === 'string' && v.length > 0 && v.length <= 40,
  subtitle: v => typeof v === 'string' && v.length <= 80,
  serverAddress: v => typeof v === 'string' && (v === '' || /^[a-z0-9.-]+(:\d{1,5})?$/i.test(v)),
  minecraftVersion: v => typeof v === 'string' && /^\d+(\.\d+){1,2}$/.test(v),
  neoforgeVersion: v => typeof v === 'string' && /^\d+(\.\d+){1,3}(-beta)?$/.test(v),
  javaMajor: v => Number.isInteger(v) && v >= 17 && v <= 30,
  memoryMaxMB: v => Number.isInteger(v) && v >= 1024 && v <= 32768,
  memoryMinMB: v => Number.isInteger(v) && v >= 512 && v <= 16384,
  mods: v => Array.isArray(v) && v.length <= 300 && v.every(s => typeof s === 'string' && /^[a-z0-9_-]{1,64}$/i.test(s)),
  clientOnlyMods: v => RULES.mods(v),
  serverOnlyMods: v => RULES.mods(v),
  customMods: v => Array.isArray(v) && v.length <= 50 && v.every(c => c && typeof c === 'object'
    && typeof c.name === 'string' && /^[a-z0-9_-]+$/.test(c.name)
    && typeof c.file === 'string' && /^[A-Za-z0-9._-]+\.jar$/.test(c.file)
    && typeof c.url === 'string' && (c.url.startsWith('https://github.com/') || c.url.startsWith('https://cdn.modrinth.com/'))
    && typeof c.sha512 === 'string' && /^[0-9a-fA-F]{128}$/.test(c.sha512)
    && ['both', 'client', 'server'].includes(c.side)),
  defaultFiles: v => Array.isArray(v) && v.length <= 100 && v.every(validDefaultFile),
  news: v => Array.isArray(v) && v.length <= 20,
  lock: v => validLock(v),
  lockSource: v => typeof v === 'string' && /^[0-9a-f]{64}$/.test(v),
  updateUrl: v => typeof v === 'string' && v.startsWith('https://raw.githubusercontent.com/ksk2979/GettMS/') && !/\.\.|%2e/i.test(v)
};

function sanitize(remote, log) {
  const out = {};
  for (const [k, ok] of Object.entries(RULES)) {
    if (!(k in remote)) continue;
    if (ok(remote[k])) out[k] = remote[k];
    else log(`원격 설정 무시: ${k} 값이 올바르지 않음`);
  }
  return out;
}

async function loadConfig(log) {
  const local = JSON.parse(fs.readFileSync(LOCAL, 'utf8'));
  if (!local.remoteConfigUrl) return { ...local, source: 'local' };
  try {
    const sep = local.remoteConfigUrl.includes('?') ? '&' : '?';
    const remote = await fetchJson(local.remoteConfigUrl + sep + 't=' + Date.now());
    const merged = { ...local, ...sanitize(remote, log), source: 'remote' };
    if (merged.memoryMinMB > merged.memoryMaxMB) merged.memoryMinMB = Math.min(2048, merged.memoryMaxMB);
    log('원격 설정 적용');
    return merged;
  } catch (e) {
    log(`원격 설정을 읽지 못해 내장 설정 사용 (${e.message})`);
    return { ...local, source: 'local-fallback' };
  }
}

module.exports = { loadConfig, RULES, validDefaultFile };
