// Electron 메인. 로그인(토큰 암호화 저장), 바로 시작/파일 검사, 실행, 서버 핑.
const { app, BrowserWindow, ipcMain, safeStorage, shell } = require('electron');
const fs = require('fs');
const path = require('path');
const { Auth } = require('msmc');
const { loadConfig } = require('./core/config');
const { prepare, launch, quickCheck, saveState } = require('./core/game');
const { ping } = require('./core/ping');
const { autoUpdater } = require('electron-updater');

const ROOT = path.join(app.getPath('appData'), 'GettLauncher');
const UPDATE_URL = 'https://raw.githubusercontent.com/ksk2979/GettMS/main/launcher/update/';
const TOKEN_FILE = path.join(ROOT, 'account.bin');
let win, config, mcToken, prepared, gameRunning = false;

const send = (ch, data) => win?.webContents.send(ch, data);
const log = m => { console.log(m); send('log', String(m)); };

const STEP_LABEL = { java: 'Java 확인', neoforge: 'NeoForge 확인', libraries: '게임 라이브러리', assets: '게임 리소스', mods: '모드 패치' };
function report(step, done, total, isBytes) {
  send('progress', { step, label: STEP_LABEL[step] || step, done, total, isBytes: !!isBytes });
}

async function tryRestoreLogin() {
  try {
    if (!fs.existsSync(TOKEN_FILE) || !safeStorage.isEncryptionAvailable()) return null;
    const refresh = safeStorage.decryptString(fs.readFileSync(TOKEN_FILE));
    const xbox = await new Auth('select_account').refresh(refresh);
    mcToken = await xbox.getMinecraft();
    saveLogin(xbox);
    return mcToken.profile.name;
  } catch (e) { log(`자동 로그인 실패: ${e.message}`); return null; }
}
function saveLogin(xbox) {
  try { if (safeStorage.isEncryptionAvailable()) { fs.mkdirSync(ROOT, { recursive: true }); fs.writeFileSync(TOKEN_FILE, safeStorage.encryptString(xbox.save())); } } catch {}
}

app.whenReady().then(async () => {
  win = new BrowserWindow({
    width: 900, height: 600, resizable: false, autoHideMenuBar: true,
    backgroundColor: '#14161c', title: 'Gett Launcher',
    webPreferences: { preload: path.join(__dirname, 'preload.js') }
  });
  await win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  config = await loadConfig(log, { cacheFile: path.join(ROOT, 'config-cache.json') });
  send('config', { ...config, root: ROOT, version: app.getVersion() });
  const name = await tryRestoreLogin();
  if (name) send('account', name);
  setupUpdater();
});

// 런처 자동 업데이트: 켜질 때 확인 → 뒤에서 받음 → 화면에 "재시작해서 업데이트"(누르면 즉시) + 안 누르면 끌 때 설치.
// 업데이트 정보는 고정 주소(launcher/update/latest.yml)에서 읽는다 — 같은 저장소의 gettcore 릴리스를 런처로 오인하지 않게.
// 확인 실패는 로그만 남기고 게임 시작에는 영향 없음. 개발 실행(npm start)에서는 건너뜀.
function setupUpdater() {
  if (!app.isPackaged) return;
  try {
    autoUpdater.setFeedURL({ provider: 'generic', url: config.updateUrl || UPDATE_URL });
    autoUpdater.autoDownload = true;
    autoUpdater.autoInstallOnAppQuit = true;
    autoUpdater.on('update-available', info => log(`새 런처 ${info.version} 받는 중...`));
    autoUpdater.on('update-downloaded', info => send('update-ready', { version: info.version }));
    autoUpdater.on('error', e => log(`런처 업데이트 확인 실패: ${e && e.message || e}`));
    autoUpdater.checkForUpdates().catch(e => log(`런처 업데이트 확인 실패: ${e && e.message || e}`));
  } catch (e) { log(`런처 업데이트 확인 실패: ${e.message}`); }
}

ipcMain.handle('install-update', async () => {
  if (gameRunning) throw new Error('게임 실행 중에는 업데이트할 수 없습니다. 게임을 끈 뒤 눌러 주세요');
  autoUpdater.quitAndInstall(true, true);
  return true;
});

const LOGIN_ERRORS = {
  'error.gui.closed': '로그인 창이 닫혔습니다. 다시 시도해 주세요',
  'error.auth.xsts.userNotFound': '이 Microsoft 계정에 Xbox 프로필이 없습니다',
  'error.auth.xsts.child': '미성년자 계정은 가족 설정에서 허용이 필요합니다',
  'error.auth.minecraft.profile': '이 계정에 마인크래프트 자바 에디션이 없습니다'
};
ipcMain.handle('login', async () => {
  let xbox;
  try { xbox = await new Auth('select_account').launch('electron'); }
  catch (e) { throw new Error(LOGIN_ERRORS[e] || LOGIN_ERRORS[e && e.message] || `로그인 실패: ${e && e.message || e}`); }
  mcToken = await xbox.getMinecraft();
  saveLogin(xbox);
  log(`로그인: ${mcToken.profile.name}`);
  return mcToken.profile.name;
});

ipcMain.handle('logout', async () => { mcToken = null; fs.rmSync(TOKEN_FILE, { force: true }); return true; });

// 파일 검사: 모든 준비 단계 + 모든 모드 해시 확인.
ipcMain.handle('prepare', async () => {
  prepared = await prepare(config, ROOT, log, report, { full: true });
  saveState(config, ROOT, prepared);
  return true;
});

ipcMain.handle('play', async () => {
  if (!mcToken) throw new Error('먼저 로그인하세요');
  if (gameRunning) throw new Error('게임이 이미 실행 중입니다');
  const ready = quickCheck(config, ROOT);
  if (ready) {
    log('변경 없음 — 바로 시작');
    report('mods', 1, 1);
    prepared = ready;
  } else {
    prepared = await prepare(config, ROOT, log, report);
    saveState(config, ROOT, prepared);
  }
  log('게임 시작');
  gameRunning = true;
  const proc = await launch(config, ROOT, mcToken.mclc(), prepared, log);
  if (!proc) { gameRunning = false; throw new Error('게임 실행 실패. 자세히 보기에서 로그를 확인하세요'); }
  proc.on('close', () => { gameRunning = false; send('game-closed', true); });
  return true;
});

ipcMain.handle('ping', async () => {
  if (!config.serverAddress) return { online: null };
  try { const r = await ping(config.serverAddress); return { online: true, players: r.players, version: r.version?.name }; }
  catch (e) { return { online: false, error: e.message }; }
});

ipcMain.handle('open-folder', async () => { fs.mkdirSync(ROOT, { recursive: true }); shell.openPath(ROOT); });

app.on('window-all-closed', () => app.quit());
