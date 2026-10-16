const { app, BrowserWindow, Tray, Menu, nativeImage, ipcMain, shell, powerSaveBlocker } = require('electron');
const path = require('path');
const fs = require('fs');

let SITE = 'about:blank';
try { SITE = JSON.parse(fs.readFileSync(path.join(__dirname, 'siteurl.json'), 'utf8')).url; } catch (e) {}

// сигнал вимагає відкритої сторінки, тому тримаємо один екземпляр
if (!app.requestSingleInstanceLock()) { app.quit(); }

let win = null, tray = null, quitting = false, ringing = false;
const icon = nativeImage.createFromPath(path.join(__dirname, 'icon.png'));
const flag = path.join(app.getPath('userData'), 'noautostart');

function autostartOn() { return !fs.existsSync(flag); }
function applyAutostart() {
  try { app.setLoginItemSettings({ openAtLogin: autostartOn(), args: ['--hidden'] }); } catch (e) {}
}

function show() {
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function offlinePage() {
  const html = '<meta charset="utf-8"><body style="font-family:sans-serif;background:#111;color:#ddd;text-align:center;padding-top:20vh">' +
    '<h2>Немає зв’язку з сайтом</h2><p>Повторна спроба кожні 10 секунд…</p></body>';
  return 'data:text/html;charset=utf-8,' + encodeURIComponent(html);
}

function create(hidden) {
  win = new BrowserWindow({
    width: 1100, height: 820, minWidth: 420, minHeight: 500,
    title: 'Календар планування', icon, show: !hidden, autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true, nodeIntegration: false, sandbox: true,
      backgroundThrottling: false,            // таймери працюють і коли вікно сховане в трей
      autoplayPolicy: 'no-user-gesture-required', // сирена грає без кліку
    },
  });
  win.setMenu(null);
  win.loadURL(SITE);
  win.webContents.on('did-fail-load', (e, code, desc, url, isMain) => {
    if (!isMain || code === -3) return;
    win.loadURL(offlinePage());
    setTimeout(() => { if (win && !win.isDestroyed()) win.loadURL(SITE); }, 10000);
  });
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith(SITE.split('?')[0]) && !url.startsWith('data:')) { e.preventDefault(); shell.openExternal(url); }
  });
  // закриття вікна ховає програму в трей, щоб нагадування працювали далі
  win.on('close', (e) => { if (!quitting) { e.preventDefault(); win.hide(); } });
}

function buildTray() {
  tray = new Tray(icon.resize({ width: 32, height: 32 }));
  tray.setToolTip('Календар планування');
  const menu = () => Menu.buildFromTemplate([
    { label: 'Відкрити', click: show },
    { label: 'Запускати разом з Windows', type: 'checkbox', checked: autostartOn(), click: (i) => {
        try { if (i.checked) fs.rmSync(flag, { force: true }); else fs.writeFileSync(flag, '1'); } catch (e) {}
        applyAutostart();
      } },
    { type: 'separator' },
    { label: 'Вийти повністю', click: () => { quitting = true; app.quit(); } },
  ]);
  tray.setContextMenu(menu());
  tray.on('click', show);
  tray.on('right-click', () => tray.setContextMenu(menu()));
}

ipcMain.on('ring', (e, on) => {
  if (!win) return;
  if (on) {
    // піднімаємо вікно поверх усіх при початку сигналу і якщо його згорнули/сховали
    if (!ringing || !win.isVisible() || win.isMinimized()) {
      show();
      win.setAlwaysOnTop(true, 'screen-saver');
      win.moveTop();
      win.flashFrame(true);
    }
    ringing = true;
  } else if (ringing) {
    ringing = false;
    win.setAlwaysOnTop(false);
    win.flashFrame(false);
  }
});

app.on('second-instance', show);
app.whenReady().then(() => {
  powerSaveBlocker.start('prevent-app-suspension');
  applyAutostart();
  create(process.argv.includes('--hidden'));
  buildTray();
});
app.on('before-quit', () => { quitting = true; });
app.on('window-all-closed', () => {}); // не виходимо: живемо в треї
