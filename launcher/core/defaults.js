// 기본 설정 파일 배포. 게임 폴더에 파일이 없을 때만 내려받는다 (플레이어가 바꾼 값은 덮어쓰지 않음).
const fs = require('fs');
const path = require('path');
const { download } = require('./net');

// 실패해도 던지지 않는다. 반환값 = 새로 쓴 파일 수.
async function seedDefaultFiles(list, root, log) {
  let written = 0;
  const base = path.resolve(root);
  for (const item of list || []) {
    let tmp = null;
    try {
      const dest = path.resolve(base, item.path);
      if (!dest.startsWith(base + path.sep)) throw new Error('경로가 게임 폴더 밖');
      if (fs.existsSync(dest)) continue;
      tmp = dest + '.part';
      await download(item.url, dest);
      written++;
    } catch (e) {
      if (tmp) { try { fs.rmSync(tmp, { force: true }); } catch {} }
      log(`기본 설정 받기 실패: ${item && item.path} (${e.message})`);
    }
  }
  if (written > 0) log(`기본 설정 적용: ${written}개 (미니맵·키 설정)`);
  return written;
}

module.exports = { seedDefaultFiles };
