// 첫 실행이면 기본 옵션(한국어, 키 설정, 안내 화면 생략 등)을 게임 폴더에 심는다.
const fs = require('fs');
const path = require('path');

function seedOptions(root, log) {
  const dest = path.join(root, 'options.txt');
  if (fs.existsSync(dest)) return false;
  const src = path.join(__dirname, '..', 'assets', 'default-options.txt');
  fs.mkdirSync(root, { recursive: true });
  fs.copyFileSync(src, dest);
  log('기본 옵션 적용 (한국어, 안내 화면 생략)');
  return true;
}

module.exports = { seedOptions };
