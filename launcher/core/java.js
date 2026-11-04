// Java 찾기: 1) 런처가 받아 둔 runtime 2) JAVA_HOME 3) Program Files\Java 4) 없으면 Adoptium에서 JRE 다운로드
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const AdmZip = require('adm-zip');
const { fetchJson, download } = require('./net');

function javaMajorOf(javaExe) {
  // java -version 은 stderr 로 출력한다
  const r = spawnSync(javaExe, ['-version'], { encoding: 'utf8' });
  if (r.error) return null;
  const m = /version "(\d+)(?:\.(\d+))?/.exec((r.stderr || '') + (r.stdout || ''));
  if (!m) return null;
  return m[1] === '1' ? Number(m[2]) : Number(m[1]);
}

function findExisting(major, root) {
  const candidates = [];
  const rt = path.join(root, 'runtime');
  if (fs.existsSync(rt)) for (const d of fs.readdirSync(rt)) candidates.push(path.join(rt, d, 'bin', 'javaw.exe'));
  if (process.env.JAVA_HOME) candidates.push(path.join(process.env.JAVA_HOME, 'bin', 'javaw.exe'));
  for (const base of ['C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft']) {
    if (fs.existsSync(base)) for (const d of fs.readdirSync(base)) candidates.push(path.join(base, d, 'bin', 'javaw.exe'));
  }
  for (const c of candidates) {
    if (fs.existsSync(c) && javaMajorOf(c.replace(/javaw\.exe$/, 'java.exe')) === major) return c;
  }
  return null;
}

async function ensureJava(major, root, log, progress) {
  const found = findExisting(major, root);
  if (found) { log(`Java ${major} 발견: ${found}`); return found; }
  log(`Java ${major} 없음. Adoptium에서 받는 중...`);
  const api = `https://api.adoptium.net/v3/assets/latest/${major}/hotspot?os=windows&architecture=x64&image_type=jre&vendor=eclipse`;
  const [asset] = await fetchJson(api);
  const pkg = asset.binary.package;
  const zipPath = path.join(root, 'runtime', pkg.name);
  await download(pkg.link, zipPath, { algo: 'sha256', hex: pkg.checksum }, progress);
  new AdmZip(zipPath).extractAllTo(path.join(root, 'runtime'), true);
  fs.rmSync(zipPath, { force: true });
  const exe = findExisting(major, root);
  if (!exe) throw new Error('Java 설치 후에도 찾을 수 없음');
  log(`Java ${major} 설치 완료: ${exe}`);
  return exe;
}

module.exports = { ensureJava };
