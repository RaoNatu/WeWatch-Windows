const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("wewatchOsd", {
    onMessage(callback) {
        const listener = (_event, payload) => callback(payload);
        ipcRenderer.on("osd:message", listener);
        return () => ipcRenderer.removeListener("osd:message", listener);
    },
});
