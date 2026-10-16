const { contextBridge, ipcRenderer } = require('electron');
// Сайт бачить window.Desk: коли йде сигнал, програма виходить на передній план поверх усіх вікон
contextBridge.exposeInMainWorld('Desk', {
  setRinging: (on) => ipcRenderer.send('ring', !!on),
});
