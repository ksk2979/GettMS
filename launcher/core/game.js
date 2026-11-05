// 패치(Java → NeoForge → 라이브러리/에셋 → 모드 → 옵션)와 실행.
const path = require('path');
const { Client } = require('minecraft-launcher-core');
const { ensureJava } = require('./java');
const { ensureNeoForge } = require('./neoforge');
const { syncMods } = require('./mods');
const { vanillaJson, ensureLibraries, ensureAssets, readJson } = require('./vanilla');
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
async function prepare(config, root, log, report) {
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
  await syncMods([...config.mods, ...(config.clientOnlyMods || [])], config.minecraftVersion, root, log, bytes('mods'), config.customMods);
  report('mods', 1, 1);

  seedOptions(root, log);
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

module.exports = { prepare, launch };
