console.log("Preload is running!")

const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld('myAPI', {
    getVlcStatus: (options) => ipcRenderer.invoke('vlc:get-status', options),
    sendVlcCommand: (command, value, options) => ipcRenderer.invoke('vlc:command', {command, value, options}),
    launchVlc: (options) => ipcRenderer.invoke('vlc:launch', options),
    openMediaFile: (options) => ipcRenderer.invoke('vlc:open-file', options),
    showOsdMessage: (payload) => ipcRenderer.send('osd:show', payload),
    startServer: (port) => ipcRenderer.invoke('server:start', port),
    getServerStatus: () => ipcRenderer.invoke('server:status'),
    getAppInfo: () => ipcRenderer.invoke('app:get-info'),
    getUpdateStatus: () => ipcRenderer.invoke('update:get-status'),
    checkForUpdates: () => ipcRenderer.invoke('update:check'),
    installUpdate: () => ipcRenderer.invoke('update:install'),
    onUpdateStatus: (callback) => {
        const listener = (_event, status) => callback(status);
        ipcRenderer.on('update:status', listener);
        return () => ipcRenderer.removeListener('update:status', listener);
    },
})
