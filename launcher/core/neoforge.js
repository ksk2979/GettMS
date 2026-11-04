// NeoForge 클라이언트 설치: 공식 설치 파일을 받아 해시 검증 후 조용히 실행(--install-client).
// 이미 versions/neoforge-<v>/neoforge-<v>.json 이 있으면 건너뜀.
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');
const { fetchText, download } = require('./net');

const MAVEN = 'https://maven.neoforged.net/releases/net/neoforged/neoforge';

function versionId(v) { return `neoforge-${v}`; }

async function ensureNeoForge(version, root, javaExe, log, progress) {
  const id = versionId(version);
  const json = path.join(root, 'versions', id, `${id}.json`);
  if (fs.existsSync(json)) { log(`NeoForge ${version} 이미 설치됨`); return id; }

  log(`NeoForge ${version} 설치 파일 받는 중...`);
  const name = `neoforge-${version}-installer.jar`;
  const url = `${MAVEN}/${version}/${name}`;
  const sha = (await fetchText(url + '.sha256')).trim();
  const installer = path.join(root, 'tmp', name);
  await download(url, installer, { algo: 'sha256', hex: sha }, progress);

  // 설치 파일은 대상 폴더에 launcher_profiles.json 이 있어야 동작함
  fs.mkdirSync(root, { recursive: true });
  const profiles = path.join(root, 'launcher_profiles.json');
  if (!fs.existsSync(profiles)) fs.writeFileSync(profiles, JSON.stringify({ profiles: {} }, null, 2));

  log('NeoForge 설치 실행 중 (1~2분)...');
  const javaCli = javaExe.replace(/javaw\.exe$/i, 'java.exe');
  await new Promise((res, rej) => {
    execFile(javaCli, ['-jar', installer, '--install-client', root], { cwd: path.dirname(installer), maxBuffer: 64 * 1024 * 1024 },
      (err, stdout, stderr) => {
        if (err) { log(stdout.slice(-2000)); log(stderr.slice(-2000)); return rej(new Error('NeoForge 설치 실패')); }
        res();
      });
  });
  fs.rmSync(installer, { force: true });
  fs.rmSync(installer + '.log', { force: true });
  if (!fs.existsSync(json)) throw new Error('설치 후에도 버전 파일이 없음: ' + json);
  log(`NeoForge ${version} 설치 완료`);
  return id;
}

module.exports = { ensureNeoForge, versionId };
