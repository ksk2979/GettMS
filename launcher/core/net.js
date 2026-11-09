// 다운로드와 JSON 요청. 모든 요청은 HTTPS, 파일은 해시로 검증.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const UA = 'GettLauncher/0.1.0';

const httpError = (url, status) => Object.assign(new Error(`${url} -> HTTP ${status}`), { status });

// 일시적인 실패(서버 5xx, 네트워크 끊김)만 다시 시도. 4xx·해시 불일치 등은 바로 실패.
function isRetryable(e) {
  if (e && typeof e.status === 'number') return e.status >= 500;
  return e instanceof TypeError || (e && /ECONNRESET|ETIMEDOUT|ENOTFOUND|EAI_AGAIN|socket|network/i.test(String(e.code || e.message)));
}

const wait = ms => new Promise(res => setTimeout(res, ms));

// fn 을 실행하고, 다시 시도할 만한 실패면 delays 만큼 기다렸다 다시 (기본 총 3번).
async function withRetry(fn, { delays = [1000, 3000], sleep = wait } = {}) {
  for (let i = 0; ; i++) {
    try { return await fn(); }
    catch (e) {
      if (i >= delays.length || !isRetryable(e)) throw e;
      await sleep(delays[i]);
    }
  }
}

async function fetchJson(url) {
  return withRetry(async () => {
    const r = await fetch(url, { headers: { 'User-Agent': UA } });
    if (!r.ok) throw httpError(url, r.status);
    return r.json();
  });
}

async function fetchText(url) {
  return withRetry(async () => {
    const r = await fetch(url, { headers: { 'User-Agent': UA } });
    if (!r.ok) throw httpError(url, r.status);
    return r.text();
  });
}

// url을 dest에 저장. expected = { algo: 'sha512'|'sha256', hex } 이면 검증. 일시적 실패는 다시 시도.
async function download(url, dest, expected, onProgress) {
  return withRetry(() => downloadOnce(url, dest, expected, onProgress));
}

async function downloadOnce(url, dest, expected, onProgress) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  const r = await fetch(url, { headers: { 'User-Agent': UA } });
  if (!r.ok || !r.body) throw httpError(url, r.status);
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
    if (!out.closed) await new Promise(res => { out.once('close', res); out.destroy(); });
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

module.exports = { fetchJson, fetchText, download, isRetryable, withRetry };
