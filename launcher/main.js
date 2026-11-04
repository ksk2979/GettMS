// Electron 메인. 로그인(토큰 암호화 저장), 패치, 실행, 서버 핑.
const { app, BrowserWindow, ipcMain, safeStorage, shell } = require('electron');
const fs = require('fs');
const path = require('path');
const { Auth } = require('msmc');
const { loadConfig } = require('./core/config');
const { prepare, launch } = require('./core/game');
const { ping } = require('./core/ping');

const ROOT = path.join(app.getPath('appData'), 'GettLauncher');
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
  config = await loadConfig(log);
  send('config', { ...config, root: ROOT, version: app.getVersion() });
  const name = await tryRestoreLogin();
  if (name) send('account', name);
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

ipcMain.handle('prepare', async () => {
  prepared = await prepare(config, ROOT, log, report);
  return true;
});

ipcMain.handle('play', async () => {
  if (!mcToken) throw new Error('먼저 로그인하세요');
  if (gameRunning) throw new Error('게임이 이미 실행 중입니다');
  prepared = await prepare(config, ROOT, log, report);
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
