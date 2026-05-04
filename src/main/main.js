const server = require('./server');
const { app, BrowserWindow, dialog, ipcMain, powerSaveBlocker } = require('electron');
const vlc = require('./vlc');
const { setupUpdates } = require("./updater");
const path = require("node:path");

let mainWindow = null;
let powerSaveBlockerId = null;
const appIcon = path.join(__dirname, "../assets/icon.png");

app.setName("WeWatch");
app.commandLine.appendSwitch("disable-background-timer-throttling");
app.commandLine.appendSwitch("disable-renderer-backgrounding");
app.commandLine.appendSwitch("disable-backgrounding-occluded-windows");

function createWindow() {
    mainWindow = new BrowserWindow({
        width: 1160,
        height: 860,
        minWidth: 900,
        minHeight: 700,
        backgroundColor: "#08090d",
        autoHideMenuBar: true,
        title: "WeWatch",
        icon: appIcon,
        webPreferences: {
            nodeIntegration: true,
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            backgroundThrottling: false,
        }
    });

    mainWindow.loadFile(path.join(__dirname, "../renderer/index.html"));
}

ipcMain.handle('vlc:get-status', (_event, options) => vlc.getStatus(options));

ipcMain.handle('vlc:command', (_event, payload) => {
    return vlc.sendCommand(payload.command, payload.value, payload.options);
});

ipcMain.handle('vlc:launch', async (_event, options) => {
    return vlc.launchVlc(options);
});

ipcMain.handle('vlc:open-file', async (_event, options) => {
    const result = await dialog.showOpenDialog(mainWindow, {
        title: "Open media",
        properties: ["openFile"],
        filters: [
            { name: "Media files", extensions: ["mkv", "mp4", "avi", "mov", "webm", "mp3", "flac", "wav", "m4v"] },
            { name: "All files", extensions: ["*"] },
        ],
    });

    if (result.canceled || !result.filePaths.length) {
        return { ok: false, canceled: true };
    }

    const filePath = result.filePaths[0];
    const openResult = await vlc.openFile(filePath, options);

    return {
        ...openResult,
        filePath,
    };
});

ipcMain.handle('server:start', async (_event, port) => {
    try {
        return await server.startServer(port);
    } catch (err) {
        return { ok: false, message: err.message };
    }
});

ipcMain.handle('server:status', () => {
    return server.getServerStatus();
});

app.whenReady().then(() => {
    createWindow();
    setupUpdates({ app, ipcMain, getWindow: () => mainWindow });

    if (!powerSaveBlockerId) {
        powerSaveBlockerId = powerSaveBlocker.start("prevent-app-suspension");
    }
});

app.on("before-quit", () => {
    if (powerSaveBlockerId && powerSaveBlocker.isStarted(powerSaveBlockerId)) {
        powerSaveBlocker.stop(powerSaveBlockerId);
    }

    vlc.closeVlc();
    server.stopServer();
});
