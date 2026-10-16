const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('Q', {
  submit: (t) => ipcRenderer.send('q-submit', String(t || '')),
  close: () => ipcRenderer.send('q-close'),
  onShow: (cb) => ipcRenderer.on('q-show', () => cb()),
});
