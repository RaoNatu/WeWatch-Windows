const { autoUpdater } = require("electron-updater");

const GITHUB_REPOSITORY = "RaoNatu/WeWatch";

function setupUpdates({ app, ipcMain, getWindow }) {
    let checking = false;
    let updateDownloaded = false;
    let lastStatus = {
        state: "idle",
        message: "Updates ready",
        currentVersion: app.getVersion(),
    };

    autoUpdater.autoDownload = true;
    autoUpdater.autoInstallOnAppQuit = true;
    autoUpdater.allowPrerelease = false;

    function publish(status) {
        lastStatus = {
            ...lastStatus,
            ...status,
            currentVersion: app.getVersion(),
        };

        const window = getWindow();
        if (window && !window.isDestroyed()) {
            window.webContents.send("update:status", lastStatus);
        }
    }

    function cleanError(error) {
        if (!error) {
            return "Update check failed";
        }

        return error.message || String(error);
    }

    function formatBytes(bytes) {
        const value = Number(bytes) || 0;
        if (value <= 0) {
            return "0 MB";
        }

        return `${(value / 1024 / 1024).toFixed(1)} MB`;
    }

    function formatDownloadProgress(progress) {
        const percent = Math.max(0, Math.min(100, Math.round(progress.percent || 0)));
        const transferred = formatBytes(progress.transferred);
        const total = formatBytes(progress.total);
        const speed = formatBytes(progress.bytesPerSecond);
        const version = lastStatus.latestVersion ? `v${lastStatus.latestVersion}` : "update";

        return {
            percent,
            transferred: Number(progress.transferred) || 0,
            total: Number(progress.total) || 0,
            bytesPerSecond: Number(progress.bytesPerSecond) || 0,
            message: `Downloading ${version}: ${percent}% (${transferred} / ${total}, ${speed}/s)`,
        };
    }

    function getFriendlyErrorStatus(error) {
        const message = cleanError(error);
        const lowerMessage = message.toLowerCase();

        if (lowerMessage.includes("no published versions on github")) {
            return {
                state: "current",
                message: "No published updates yet",
            };
        }

        if (lowerMessage.includes("latest.yml") && lowerMessage.includes("404")) {
            return {
                state: "error",
                message: "latest.yml is missing from the GitHub release",
                detail: message,
            };
        }

        if (lowerMessage.includes("404")) {
            return {
                state: "error",
                message: "Update asset was not found on GitHub",
                detail: "Upload the installer and blockmap with the exact filenames listed inside latest.yml.",
            };
        }

        return {
            state: "error",
            message,
        };
    }

    async function checkForUpdates() {
        if (!app.isPackaged) {
            const status = {
                state: "disabled",
                message: "Updates work after installing a packaged build",
            };
            publish(status);
            return { ok: false, ...status };
        }

        if (checking) {
            return { ok: false, ...lastStatus };
        }

        checking = true;
        try {
            const result = await autoUpdater.checkForUpdates();
            return {
                ok: true,
                ...lastStatus,
                latestVersion: result?.updateInfo?.version || lastStatus.latestVersion,
            };
        } catch (error) {
            const status = getFriendlyErrorStatus(error);
            publish(status);
            return { ok: false, ...status };
        } finally {
            checking = false;
        }
    }

    autoUpdater.on("checking-for-update", () => {
        checking = true;
        publish({
            state: "checking",
            message: "Checking for updates",
        });
    });

    autoUpdater.on("update-available", info => {
        updateDownloaded = false;
        publish({
            state: "available",
            latestVersion: info.version,
            message: `Downloading v${info.version}`,
        });
    });

    autoUpdater.on("update-not-available", info => {
        checking = false;
        updateDownloaded = false;
        publish({
            state: "current",
            latestVersion: info.version,
            message: "No new versions found",
        });
    });

    autoUpdater.on("download-progress", progress => {
        const download = formatDownloadProgress(progress);
        publish({
            state: "downloading",
            progress: download.percent,
            transferred: download.transferred,
            total: download.total,
            bytesPerSecond: download.bytesPerSecond,
            message: download.message,
        });
    });

    autoUpdater.on("update-downloaded", info => {
        checking = false;
        updateDownloaded = true;
        publish({
            state: "downloaded",
            latestVersion: info.version,
            message: `v${info.version} is ready to install`,
        });
    });

    autoUpdater.on("error", error => {
        checking = false;
        publish(getFriendlyErrorStatus(error));
    });

    ipcMain.handle("app:get-info", () => ({
        name: app.getName(),
        version: app.getVersion(),
        packaged: app.isPackaged,
        repository: GITHUB_REPOSITORY,
    }));

    ipcMain.handle("update:get-status", () => lastStatus);
    ipcMain.handle("update:check", () => checkForUpdates());
    ipcMain.handle("update:install", () => {
        if (!updateDownloaded) {
            return {
                ok: false,
                message: "No downloaded update is ready yet",
            };
        }

        autoUpdater.quitAndInstall(false, true);
        return { ok: true };
    });

    if (app.isPackaged) {
        setTimeout(() => {
            checkForUpdates().catch(() => {});
        }, 5000);
    }
}

module.exports = {
    setupUpdates,
};
