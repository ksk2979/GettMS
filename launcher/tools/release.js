// 런처 릴리스: 빌드 → GitHub 릴리스 v<버전> 에 설치 파일 업로드 → 업로드 확인 → launcher/update/latest.yml 작성.
// latest.yml 은 업로드가 끝난 뒤에만 쓴다(먼저 올라가면 모든 런처가 없는 파일을 받으려다 실패).
// 사용: npm run release   (gh 로그인 필요). 끝나면 안내대로 latest.yml 을 커밋·푸시.
//       npm run release -- --into v1.1.1 → 새 릴리스 대신 기존 릴리스 페이지(이미 알려 준 주소)를 이 버전으로 바꾼다
//       (제목·설명 교체, 설치 파일 업로드, 그 밖의 옛 파일 삭제).
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
// 셸을 거치지 않는다: Windows 셸은 띄어쓰기가 있는 인자(릴리스 제목·노트)를 쪼갠다. gh 는 gh.exe 라 셸 없이 실행되고,
// electron-builder 는 npx(.cmd) 대신 node 로 cli.js 를 직접 실행한다.
const run = (cmd, args) => execFileSync(cmd, args, { cwd: ROOT, stdio: 'inherit' });
const out = (cmd, args) => execFileSync(cmd, args, { cwd: ROOT, encoding: 'utf8' });

const REPO = 'ksk2979/GettMS';

function main() {
  const version = require(path.join(ROOT, 'package.json')).version;
  const i = process.argv.indexOf('--into');
  const into = i >= 0 ? process.argv[i + 1] : null;
  if (i >= 0 && !/^v\d+\.\d+\.\d+$/.test(into || '')) throw new Error('--into 다음에 기존 릴리스 태그(예: v1.1.1)를 적으세요');
  const tag = into || `v${version}`;
  const exe = `GettLauncher-Setup-${version}.exe`;
  const dist = path.join(ROOT, 'dist');

  console.log(`== 빌드 ${version}`);
  run(process.execPath, [require.resolve('electron-builder/cli.js'), '--win', '--x64']);
  const files = [exe, `${exe}.blockmap`].map(f => path.join(dist, f));
  for (const f of files.concat(path.join(dist, 'latest.yml'))) {
    if (!fs.existsSync(f)) throw new Error(`빌드 결과 없음: ${f}`);
  }

  const title = `Gett Launcher ${version}`;
  const notes = `Gett Launcher ${version}. 처음 한 번은 ${exe} 를 받아 설치하고, 그 다음부터는 런처가 자동으로 업데이트합니다.`;
  if (into) {
    console.log(`== 기존 릴리스 ${tag} 를 ${version} 으로 교체`);
    run('gh', ['release', 'upload', tag, ...files, '--repo', REPO, '--clobber']);
    run('gh', ['release', 'edit', tag, '--repo', REPO, '--title', title, '--notes', notes]);
    const keep = new Set(files.map(f => path.basename(f)));
    const assets = JSON.parse(out('gh', ['release', 'view', tag, '--repo', REPO, '--json', 'assets'])).assets;
    for (const a of assets) {
      if (!keep.has(a.name)) { console.log(`옛 파일 삭제: ${a.name}`); run('gh', ['release', 'delete-asset', tag, a.name, '--repo', REPO, '-y']); }
    }
  } else {
    console.log(`== 릴리스 ${tag} 업로드`);
    run('gh', ['release', 'create', tag, ...files, '--repo', REPO, '--title', title, '--notes', notes]);
  }

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
