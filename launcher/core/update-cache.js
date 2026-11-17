// 런처 업데이트 설치 파일 보관 폴더(%LOCALAPPDATA%\gett-launcher-updater) 다루기.
// 받아 둔 설치 파일은 pending\ 에, 설치가 끝나면 설치 프로그램이 자기 사본을 installer.exe 로 남긴다 — 둘 다 설치 뒤에는 필요 없다.
const fs = require('fs');
const os = require('os');
const path = require('path');

function updateCacheDir() {
  return path.join(process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local'), 'gett-launcher-updater');
}

// 이 해시의 설치 파일을 이미 다 받아 뒀는가 (받기만 하고 설치는 안 누른 경우).
function hasPending(dir, sha512) {
  try {
    const info = JSON.parse(fs.readFileSync(path.join(dir, 'pending', 'update-info.json'), 'utf8'));
    return !!sha512 && info.sha512 === sha512 && typeof info.fileName === 'string'
      && path.basename(info.fileName) === info.fileName && fs.existsSync(path.join(dir, 'pending', info.fileName));
  } catch { return false; }
}

// 설치 파일을 모두 지운다. 실패해도 던지지 않는다. 반환값 = 지웠는가.
function clearUpdateCache(dir) {
  try {
    if (path.basename(dir) !== 'gett-launcher-updater' || !fs.existsSync(dir)) return false;
    fs.rmSync(dir, { recursive: true, force: true });
    return true;
  } catch { return false; }
}

module.exports = { updateCacheDir, hasPending, clearUpdateCache };
