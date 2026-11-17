const $ = id => document.getElementById(id);
const lines = [];
const say = m => { lines.push(m); if (lines.length > 400) lines.shift(); $('log').textContent = lines.join('\n'); $('log').scrollTop = 1e9; };
let loggedIn = false, busy = false;

api.on('log', say);
api.on('config', c => {
  $('serverName').textContent = c.serverName; $('title').textContent = c.serverName; $('subtitle').textContent = c.subtitle || '';
  $('meta').textContent = `Minecraft ${c.minecraftVersion} · NeoForge ${c.neoforgeVersion} · 모드 ${c.mods.length + (c.clientOnlyMods||[]).length}개 · 런처 v${c.version}`;
  refreshStatus(); setInterval(refreshStatus, 30000);
});
api.on('account', setAccount);
api.on('progress', p => {
  const pct = p.total ? Math.round(p.done / p.total * 100) : 0;
  $('fill').style.width = pct + '%';
  $('stepLabel').textContent = p.label;
  $('stepCount').textContent = p.isBytes ? `${(p.done/1048576).toFixed(1)} / ${(p.total/1048576).toFixed(1)} MB` : (p.total > 1 ? `${p.done} / ${p.total}` : '');
});
let updateState = null;
const mb = n => (n / 1048576).toFixed(1);
api.on('update-state', u => {
  updateState = u.state;
  $('updateBar').classList.remove('hidden');
  const btn = $('updateBtn');
  btn.classList.toggle('hidden', u.state === 'downloading');
  if (u.state === 'available') { $('updateText').textContent = u.failed ? `새 런처 ${u.version} 받기 실패` : `새 런처 ${u.version} 있음`; btn.textContent = u.failed ? '다시 받기' : '업데이트 받기'; }
  else if (u.state === 'downloading') $('updateText').textContent = `새 런처 ${u.version} 받는 중... ` + (u.total ? `${Math.round(u.done / u.total * 100)}% (${mb(u.done)} / ${mb(u.total)} MB)` : '');
  else { $('updateText').textContent = `새 런처 ${u.version} 준비됨 · 끌 때 자동 설치`; btn.textContent = '재시작해서 업데이트'; }
});
$('updateBtn').onclick = async () => {
  try { if (updateState === 'available') await api.downloadUpdate(); else await api.installUpdate(); } catch (e) { $('stepLabel').textContent = String(e.message || e).replace(/^Error invoking remote method '[^']+': (Error: )?/, ''); }
};
api.on('game-closed', () => { setBusy(false); $('stepLabel').textContent = '게임 종료됨'; });

function setAccount(name) {
  loggedIn = !!name;
  $('accountName').textContent = name || '로그인 안 됨';
  $('accountName').classList.toggle('muted', !name);
  $('loginBtn').classList.toggle('hidden', !!name);
  $('logoutBtn').classList.toggle('hidden', !name);
  updatePlay();
}
function setBusy(b) { busy = b; updatePlay(); $('loginBtn').disabled = b; $('optionsBtn').disabled = b; }
function updatePlay() { $('playBtn').disabled = busy || !loggedIn; $('playBtn').textContent = busy ? '진행 중...' : (loggedIn ? '게임 시작' : '로그인 후 시작'); }

async function refreshStatus() {
  const r = await api.ping();
  if (r.online === null) { $('statusDot').parentElement.classList.add('hidden'); return; }
  $('statusDot').className = 'sdot ' + (r.online ? 'on' : 'off');
  $('statusText').textContent = r.online ? `온라인 · 접속자 ${r.players?.online ?? 0} / ${r.players?.max ?? '?'}` : '오프라인';
}
async function guard(fn) {
  if (busy) return; setBusy(true);
  try { await fn(); } catch (e) { say('오류: ' + (e.message || e)); $('stepLabel').textContent = '오류: ' + (e.message || e); $('log').classList.remove('hidden'); setBusy(false); }
}

$('loginBtn').onclick = () => guard(async () => { setAccount(await api.login()); setBusy(false); });
$('logoutBtn').onclick = async () => { await api.logout(); setAccount(null); };
$('playBtn').onclick = () => guard(async () => { await api.play(); $('fill').style.width = '100%'; $('stepLabel').textContent = '게임 실행 중'; $('stepCount').textContent = ''; });
$('patchLink').onclick = e => { e.preventDefault(); guard(async () => { await api.prepare(); $('fill').style.width = '100%'; $('stepLabel').textContent = '파일 검사 완료'; $('stepCount').textContent = ''; setBusy(false); }); };
$('folderLink').onclick = e => { e.preventDefault(); api.openFolder(); };
$('logLink').onclick = e => { e.preventDefault(); $('log').classList.toggle('hidden'); };
$('optionsBtn').onclick = () => guard(async () => {
  const r = await api.refreshOptions();
  if (r) $('stepLabel').textContent = r.failed ? `옵션 갱신: ${r.written}개 바꿈, ${r.failed}개 실패 (자세히 보기)` : `옵션 갱신 완료 (${r.written}개)`;
  setBusy(false);
});
