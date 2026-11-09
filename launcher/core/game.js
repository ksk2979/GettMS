// 패치(Java → NeoForge → 라이브러리/에셋 → 모드 → 옵션)와 실행.
const fs = require('fs');
const path = require('path');
const { Client } = require('minecraft-launcher-core');
const { ensureJava } = require('./java');
const { ensureNeoForge } = require('./neoforge');
const { syncMods, syncLockedMods, jarSizes } = require('./mods');
const { activeLock, canFastStart, fingerprint } = require('./lock');
const { vanillaJson, ensureLibraries, ensureAssets, readJson } = require('./vanilla');
const { seedDefaultFiles } = require('./defaults');
const { seedOptions } = require('./options');
const { ensureServerEntry } = require('./servers');

// MCLC는 커스텀 버전 json의 jvm 인수를 쓰지 않으므로 직접 치환해 넘긴다.
function neoforgeJvmArgs(root, id) {
  const json = readJson(path.join(root, 'versions', id, `${id}.json`));
  const fields = {
    '${library_directory}': path.join(root, 'libraries'),
    '${classpath_separator}': process.platform === 'win32' ? ';' : ':',
    '${version_name}': id
  };
  const jvm = (json.arguments && json.arguments.jvm) || [];
  return jvm.filter(a => typeof a === 'string')
    .map(a => a.replace(/\$\{[a-z_]+\}/g, k => (k in fields ? fields[k] : k)));
}

// report(step, done, total, isBytes) 로 진행 상황을 알린다.
// full = 파일 검사(모든 모드 해시 확인).
async function prepare(config, root, log, report, { full = false } = {}) {
  const bytes = step => (done, total) => report(step, done, total, true);

  report('java', 0, 1);
  const javaExe = await ensureJava(config.javaMajor, root, log, bytes('java'));
  report('java', 1, 1);

  report('neoforge', 0, 1);
  const versionId = await ensureNeoForge(config.neoforgeVersion, root, javaExe, log, bytes('neoforge'));
  report('neoforge', 1, 1);

  const vjson = await vanillaJson(config.minecraftVersion, root);
  const njson = readJson(path.join(root, 'versions', versionId, `${versionId}.json`));
  const nLibs = await ensureLibraries([vjson, njson], root, report);
  const nAssets = await ensureAssets(vjson, root, report);
  if (nLibs || nAssets) log(`게임 파일 받음: 라이브러리 ${nLibs}개, 에셋 ${nAssets}개`);

  report('mods', 0, 1);
  const lock = activeLock(config);
  if (lock) await syncLockedMods(lock, root, log, bytes('mods'), { verify: full });
  else await syncMods([...config.mods, ...(config.clientOnlyMods || [])], config.minecraftVersion, root, log, bytes('mods'), config.customMods);
  report('mods', 1, 1);

  const defaults = config.defaultFiles || [];
  await seedDefaultFiles(defaults, root, log);
  // 기본 옵션 파일이 배포 목록에 있고 실제로 존재하면 내장 옵션 시드는 건너뜀 (없으면 대체 수단으로 유지)
  const optionsDeployed = defaults.some(f => f.path === 'options.txt') && fs.existsSync(path.join(root, 'options.txt'));
  if (!optionsDeployed) seedOptions(root, log);
  if (config.serverAddress) ensureServerEntry(root, config.serverName, config.serverAddress, log);
  return { javaExe, versionId };
}

function launch(config, root, auth, { javaExe, versionId }, log) {
  const launcher = new Client();
  const opts = {
    authorization: auth,
    root,
    javaPath: javaExe,
    version: { number: config.minecraftVersion, type: 'release', custom: versionId },
    memory: { max: `${config.memoryMaxMB}M`, min: `${config.memoryMinMB}M` },
    customArgs: neoforgeJvmArgs(root, versionId),
    window: { width: 1280, height: 720 }
  };
  launcher.on('debug', m => log(`[mclc] ${m}`));
  launcher.on('data', m => log(`[game] ${String(m).trimEnd()}`));
  launcher.on('close', code => log(`게임 종료 (code ${code})`));
  return launcher.launch(opts);
}

const STATE = '.gett-state.json';

function readState(root) {
  try { return JSON.parse(fs.readFileSync(path.join(root, STATE), 'utf8')); } catch { return null; }
}

// 준비가 끝난 뒤 기록: 다음 실행에서 지문이 같으면 준비를 건너뛴다.
function saveState(config, root, prepared) {
  try {
    fs.writeFileSync(path.join(root, STATE), JSON.stringify({ fingerprint: fingerprint(config), ...prepared }));
  } catch {}
}

// 바로 시작할 수 있으면 지난번 준비 결과({ javaExe, versionId }), 아니면 null.
function quickCheck(config, root) {
  const state = readState(root);
  const haveRuntime = !!state && typeof state.javaExe === 'string' && typeof state.versionId === 'string'
    && fs.existsSync(state.javaExe)
    && fs.existsSync(path.join(root, 'versions', state.versionId, `${state.versionId}.json`));
  const ok = canFastStart({ state, cfg: config, existing: jarSizes(path.join(root, 'mods')), haveRuntime });
  return ok ? { javaExe: state.javaExe, versionId: state.versionId } : null;
}

module.exports = { prepare, launch, quickCheck, saveState };
