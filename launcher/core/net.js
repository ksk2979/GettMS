// 다운로드와 JSON 요청. 모든 요청은 HTTPS, 파일은 해시로 검증.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const UA = 'GettLauncher/0.1.0';

async function fetchJson(url) {
  const r = await fetch(url, { headers: { 'User-Agent': UA } });
  if (!r.ok) throw new Error(`${url} -> HTTP ${r.status}`);
  return r.json();
}

async function fetchText(url) {
  const r = await fetch(url, { headers: { 'User-Agent': UA } });
  if (!r.ok) throw new Error(`${url} -> HTTP ${r.status}`);
  return r.text();
}

// url을 dest에 저장. expected = { algo: 'sha512'|'sha256', hex } 이면 검증.
async function download(url, dest, expected, onProgress) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  const r = await fetch(url, { headers: { 'User-Agent': UA } });
  if (!r.ok || !r.body) throw new Error(`${url} -> HTTP ${r.status}`);
  const total = Number(r.headers.get('content-length') || 0);
  const tmp = dest + '.part';
  const hash = expected ? crypto.createHash(expected.algo) : null;
  const out = fs.createWriteStream(tmp);
  let done = 0;
  try {
    for await (const chunk of r.body) {
      out.write(chunk);
      if (hash) hash.update(chunk);
      done += chunk.length;
      if (onProgress && total) onProgress(done, total);
    }
    await new Promise((res, rej) => out.end(err => (err ? rej(err) : res())));
  } catch (e) {
    // 중간에 끊기면 열린 스트림을 닫고 .part 를 지운다
    out.on('error', () => {});
    await new Promise(res => { out.once('close', res); out.destroy(); });
    fs.rmSync(tmp, { force: true });
    throw e;
  }
  if (hash) {
    const got = hash.digest('hex');
    if (got !== expected.hex.toLowerCase()) {
      fs.rmSync(tmp, { force: true });
      throw new Error(`해시 불일치: ${path.basename(dest)}`);
    }
  }
  fs.renameSync(tmp, dest);
}

module.exports = { fetchJson, fetchText, download };
