const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('api', {
  login: () => ipcRenderer.invoke('login'),
  logout: () => ipcRenderer.invoke('logout'),
  prepare: () => ipcRenderer.invoke('prepare'),
  play: () => ipcRenderer.invoke('play'),
  ping: () => ipcRenderer.invoke('ping'),
  openFolder: () => ipcRenderer.invoke('open-folder'),
  installUpdate: () => ipcRenderer.invoke('install-update'),
  on: (ch, cb) => ipcRenderer.on(ch, (_, d) => cb(d))
});
