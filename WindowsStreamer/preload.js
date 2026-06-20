const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  getReceivers: () => ipcRenderer.invoke('get-receivers'),
  onReceiversUpdated: (callback) => ipcRenderer.on('receivers-updated', (_event, value) => callback(value))
});
