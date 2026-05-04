const { spawn } = require("child_process");
const fs = require('fs');
const { pathToFileURL } = require("node:url");

let vlcProcess = null;

function getConfig(options = {}) {
    return {
        host: options.host || "127.0.0.1",
        port: String(options.port || "8080"),
        password: options.password ?? "1234",
    };
}

function getAuthHeader(config) {
    return "Basic " + Buffer.from(`:${config.password}`).toString("base64");
}

async function readVlcResponse(response) {
    const text = await response.text();

    if (!response.ok) {
        const detail = text.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim();
        throw new Error(detail ? `VLC returned ${response.status}: ${detail.slice(0, 140)}` : `VLC returned ${response.status}`);
    }

    if (!text.trim()) {
        return {};
    }

    try {
        return JSON.parse(text);
    } catch {
        return { ok: true, raw: text };
    }
}

async function sendCommand(command, value, options = {}, valueParam = "val") {
    const config = getConfig(options);
    const params = new URLSearchParams({ command });

    if (value !== undefined) {
        params.set(valueParam, String(value));
    }

    const url = `http://${config.host}:${config.port}/requests/status.json?${params.toString()}`;
    const response = await fetch(url, {
        headers: {
            Authorization: getAuthHeader(config),
        }
    });

    return readVlcResponse(response);
}

async function getStatus(options = {}) {
    const config = getConfig(options);
    const response = await fetch(`http://${config.host}:${config.port}/requests/status.json`, {
        headers: {
            Authorization: getAuthHeader(config),
        }
    });

    return readVlcResponse(response);
}

function launchVlc(options = {}) {
    try {
        const config = getConfig(options);
        const vlcPath = findVlc();

        if (!vlcPath) {
            return { ok: false, message: "VLC not found" };
        }

        vlcProcess = spawn(
            vlcPath,
            [
                "--extraintf", "http",
                "--http-host", config.host,
                "--http-port", config.port,
                "--http-password", config.password
            ],
            {
                detached: false,
                stdio: "ignore"
            }
        );

        vlcProcess.unref();

        return { ok: true };
    } catch (err) {
        return { ok: false, message: err.message };
    }
}

function findVlc() {
    const paths = [
        "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe",
        "C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe"
    ];

    return paths.find(p => fs.existsSync(p)) || null;
}

async function openFile(filePath, options = {}) {
    if (!filePath) {
        return { ok: false, message: "No file selected" };
    }

    try {
        const status = await sendCommand("in_play", pathToFileURL(filePath).href, options, "input");
        return { ok: true, status };
    } catch (err) {
        return { ok: false, message: err.message };
    }
}

function closeVlc() {
    if (vlcProcess) {
        try {
            vlcProcess.kill();
            vlcProcess = null;
            console.log("VLC closed");
        } catch (err) {
            console.error("Failed to close VLC:", err);
        }
    }
}

module.exports = { sendCommand, getStatus, launchVlc, openFile, closeVlc };
