const { contextBridge, ipcRenderer } = require('electron');
let quickHandler = null;
// Сайт бачить window.Desk: сигнал виходить поверх усіх вікон, а Ctrl+Space додає задачу
contextBridge.exposeInMainWorld('Desk', {
  setRinging: (on) => ipcRenderer.send('ring', !!on),
  onQuick: (cb) => { quickHandler = cb; },
});
ipcRenderer.on('quick', (e, text) => {
  let res = '';
  try { if (quickHandler) res = String(quickHandler(text) || ''); else res = 'Календар ще не завантажився'; } catch (err) { res = 'Помилка: ' + err.message; }
  ipcRenderer.send('quick-result', res);
});
