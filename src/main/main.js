const server = require('./server');
const { app, BrowserWindow, dialog, ipcMain, powerSaveBlocker, screen } = require('electron');
const vlc = require('./vlc');
const { setupUpdates } = require("./updater");
const { execFile } = require("node:child_process");
const path = require("node:path");

let mainWindow = null;
let osdWindow = null;
let osdReadyPromise = null;
let osdHideTimer = null;
let powerSaveBlockerId = null;
let shuttingDown = false;
let mainWindowCloseAllowed = false;
const appIcon = path.join(__dirname, "../assets/icon.png");
const OSD_WIDTH = 460;
const OSD_HEIGHT = 180;
const OSD_MARGIN = 28;

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
    mainWindow.on("close", async (event) => {
        if (mainWindowCloseAllowed) {
            return;
        }

        event.preventDefault();
        await shutdownAppServices();
        mainWindowCloseAllowed = true;
        mainWindow.close();
    });

    mainWindow.on("closed", () => {
        mainWindow = null;
        if (process.platform !== "darwin") {
            app.quit();
        }
    });
}

function closeOsdWindow() {
    clearTimeout(osdHideTimer);
    osdHideTimer = null;

    if (osdWindow && !osdWindow.isDestroyed()) {
        osdWindow.close();
    }

    osdWindow = null;
    osdReadyPromise = null;
}

async function shutdownAppServices() {
    if (shuttingDown) {
        return;
    }

    shuttingDown = true;

    if (powerSaveBlockerId && powerSaveBlocker.isStarted(powerSaveBlockerId)) {
        powerSaveBlocker.stop(powerSaveBlockerId);
    }
    powerSaveBlockerId = null;

    closeOsdWindow();
    vlc.closeVlc();
    await server.stopServer();
}

function createOsdWindow() {
    if (osdWindow) {
        return osdWindow;
    }

    osdWindow = new BrowserWindow({
        width: OSD_WIDTH,
        height: OSD_HEIGHT,
        show: false,
        frame: false,
        transparent: true,
        resizable: false,
        movable: false,
        minimizable: false,
        maximizable: false,
        fullscreenable: false,
        skipTaskbar: true,
        focusable: false,
        hasShadow: false,
        alwaysOnTop: true,
        backgroundColor: "#00000000",
        webPreferences: {
            preload: path.join(__dirname, "../overlay/preload.js"),
            contextIsolation: true,
            nodeIntegration: false,
            backgroundThrottling: false,
        }
    });

    osdWindow.setIgnoreMouseEvents(true, { forward: true });
    osdWindow.setAlwaysOnTop(true, "screen-saver");
    osdReadyPromise = osdWindow.loadFile(path.join(__dirname, "../overlay/index.html"))
        .catch(error => console.error("Could not load VLC OSD overlay:", error));
    osdWindow.on("closed", () => {
        osdWindow = null;
        osdReadyPromise = null;
    });

    return osdWindow;
}

function getFallbackOsdBounds() {
    const reference = mainWindow?.getBounds();
    const point = reference
        ? { x: reference.x + Math.floor(reference.width / 2), y: reference.y + Math.floor(reference.height / 2) }
        : screen.getCursorScreenPoint();
    const display = screen.getDisplayNearestPoint(point);
    const area = display.workArea;

    return {
        x: area.x + area.width - OSD_WIDTH - OSD_MARGIN,
        y: area.y + OSD_MARGIN,
        width: OSD_WIDTH,
        height: OSD_HEIGHT,
    };
}

function getVlcWindowBounds() {
    if (process.platform !== "win32") {
        return Promise.resolve(null);
    }

    const script = `
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WeWatchWin32 {
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
}
"@
$windows = Get-Process -Name vlc -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | ForEach-Object {
    $rect = New-Object WeWatchWin32+RECT
    if ([WeWatchWin32]::GetWindowRect($_.MainWindowHandle, [ref]$rect)) {
        $width = $rect.Right - $rect.Left
        $height = $rect.Bottom - $rect.Top
        if ($width -gt 120 -and $height -gt 80) {
            [PSCustomObject]@{ x = $rect.Left; y = $rect.Top; width = $width; height = $height; area = $width * $height }
        }
    }
}
$windows | Sort-Object area -Descending | Select-Object -First 1 -Property x,y,width,height | ConvertTo-Json -Compress
`;

    return new Promise(resolve => {
        execFile("powershell.exe", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script], {
            windowsHide: true,
            timeout: 1400,
        }, (error, stdout) => {
            if (error || !stdout.trim()) {
                resolve(null);
                return;
            }

            try {
                const bounds = JSON.parse(stdout.trim());
                if (!Number.isFinite(bounds.x) || !Number.isFinite(bounds.y) || !Number.isFinite(bounds.width) || !Number.isFinite(bounds.height)) {
                    resolve(null);
                    return;
                }
                resolve(bounds);
            } catch {
                resolve(null);
            }
        });
    });
}

async function positionOsdWindow(window) {
    const vlcBounds = await getVlcWindowBounds();
    if (!vlcBounds) {
        window.setBounds(getFallbackOsdBounds(), false);
        return;
    }

    const width = Math.min(OSD_WIDTH, Math.max(280, vlcBounds.width - (OSD_MARGIN * 2)));
    const height = OSD_HEIGHT;
    const x = Math.round(vlcBounds.x + vlcBounds.width - width - OSD_MARGIN);
    const y = Math.round(vlcBounds.y + OSD_MARGIN);

    window.setBounds({ x, y, width, height }, false);
}

async function showOsdMessage(payload = {}) {
    if (shuttingDown) {
        return;
    }

    const message = String(payload.message || "").trim();
    if (!message) {
        return;
    }

    const window = createOsdWindow();
    await osdReadyPromise;
    await positionOsdWindow(window);

    const duration = Math.max(1600, Math.min(8000, Number(payload.duration) || 3600));
    window.showInactive();
    window.webContents.send("osd:message", {
        message,
        kind: payload.kind || "event",
        duration,
        at: payload.at || Date.now(),
    });

    clearTimeout(osdHideTimer);
    osdHideTimer = setTimeout(() => {
        if (osdWindow && !osdWindow.isDestroyed()) {
            osdWindow.hide();
        }
    }, duration + 700);
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

ipcMain.on('osd:show', (_event, payload) => {
    showOsdMessage(payload);
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
    shutdownAppServices();
});
