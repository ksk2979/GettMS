// 런처 릴리스: 빌드 → GitHub 릴리스 v<버전> 에 설치 파일 업로드 → 업로드 확인 → launcher/update/latest.yml 작성.
// latest.yml 은 업로드가 끝난 뒤에만 쓴다(먼저 올라가면 모든 런처가 없는 파일을 받으려다 실패).
// 사용: npm run release   (gh 로그인 필요). 끝나면 안내대로 latest.yml 을 커밋·푸시.
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
const run = (cmd, args) => execFileSync(cmd, args, { cwd: ROOT, stdio: 'inherit', shell: process.platform === 'win32' });
const out = (cmd, args) => execFileSync(cmd, args, { cwd: ROOT, encoding: 'utf8', shell: process.platform === 'win32' });

const REPO = 'ksk2979/GettMS';

function main() {
  const version = require(path.join(ROOT, 'package.json')).version;
  const tag = `v${version}`;
  const exe = `GettLauncher-Setup-${version}.exe`;
  const dist = path.join(ROOT, 'dist');

  console.log(`== 빌드 ${version}`);
  run('npx', ['electron-builder', '--win', '--x64']);
  const files = [exe, `${exe}.blockmap`].map(f => path.join(dist, f));
  for (const f of files.concat(path.join(dist, 'latest.yml'))) {
    if (!fs.existsSync(f)) throw new Error(`빌드 결과 없음: ${f}`);
  }

  console.log(`== 릴리스 ${tag} 업로드`);
  run('gh', ['release', 'create', tag, ...files, '--repo', REPO, '--title', `Gett Launcher ${version}`,
    '--notes', `Gett Launcher ${version}. 처음 한 번은 ${exe} 를 받아 설치하고, 그 다음부터는 런처가 자동으로 업데이트합니다.`]);

  const size = fs.statSync(files[0]).size;
  const uploaded = JSON.parse(out('gh', ['release', 'view', tag, '--repo', REPO, '--json', 'assets'])).assets
    .find(a => a.name === exe);
  if (!uploaded || uploaded.size !== size) throw new Error(`업로드 확인 실패: ${exe} (로컬 ${size}, 원격 ${uploaded && uploaded.size})`);

  const url = `https://github.com/${REPO}/releases/download/${tag}/${exe}`;
  const yml = fs.readFileSync(path.join(dist, 'latest.yml'), 'utf8')
    .split(/\r?\n/)
    .map(line => line.replace(/^(\s*-?\s*url:\s*).*$/, `$1${url}`).replace(/^(path:\s*).*$/, `$1${url}`))
    .join('\n');
  const target = path.join(ROOT, 'update', 'latest.yml');
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, yml);
  console.log(`\n== 완료. launcher/update/latest.yml 을 커밋·푸시하면 기존 런처(1.2.0+)가 자동 업데이트합니다.`);
}

try { main(); } catch (e) { console.error(e.message || e); process.exit(1); }
