// 기본 설정 파일 배포. 게임 폴더에 파일이 없을 때만 내려받는다 (플레이어가 바꾼 값은 덮어쓰지 않음).
const fs = require('fs');
const path = require('path');
const { download } = require('./net');
const { validDefaultFile } = require('./config');

// 실패해도 던지지 않는다. 반환값 = 새로 쓴 파일 수.
async function seedDefaultFiles(list, root, log) {
  let written = 0;
  const base = path.resolve(root);
  for (const item of list || []) {
    let tmp = null;
    if (!validDefaultFile(item)) { log(`기본 설정 항목 무시: ${item && item.path} (형식 오류)`); continue; }
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

// "옵션 갱신" 버튼: 서버에 올라온 기본 설정으로 덮어쓴다. 먼저 옆에 받아 두고, 다 받은 뒤에만 바꾼다
// (받다 실패하면 내 파일은 그대로). 바꾸기 전 파일은 <이름>.backup 으로 남긴다.
async function refreshDefaultFiles(list, root, log) {
  let written = 0, failed = 0;
  const base = path.resolve(root);
  for (const item of list || []) {
    let fresh = null;
    try {
      if (!validDefaultFile(item)) throw new Error('형식 오류');
      const dest = path.resolve(base, item.path);
      if (!dest.startsWith(base + path.sep)) throw new Error('경로가 게임 폴더 밖');
      fresh = dest + '.new';
      await download(item.url, fresh);
      if (fs.existsSync(dest)) fs.copyFileSync(dest, dest + '.backup');
      fs.renameSync(fresh, dest);
      written++;
    } catch (e) {
      failed++;
      if (fresh) { try { fs.rmSync(fresh, { force: true }); fs.rmSync(fresh + '.part', { force: true }); } catch {} }
      log(`옵션 갱신 실패: ${item && item.path} (${e.message})`);
    }
  }
  log(`옵션 갱신: ${written}개 바꿈${failed ? `, ${failed}개 실패` : ''}`);
  return { written, failed };
}

module.exports = { seedDefaultFiles, refreshDefaultFiles };
