import { createSocket } from "./socket.js";
import { setSync, getExpectedPosition } from "./sync.js";

const logEl = document.getElementById("eventLog");

const sessionSummary = document.getElementById("sessionSummary");
const roleBadge = document.getElementById("rolePill");
const roleValue = document.getElementById("roleValue");
const hostValue = document.getElementById("hostValue");
const driftEl = document.getElementById("driftValue");

const serverStatus = document.getElementById("serverBadge");
const socketStatus = document.getElementById("socketBadge");
const vlcStatus = document.getElementById("vlcBadge");
const versionBadge = document.getElementById("versionBadge");

const disconnectBtn = document.getElementById("disconnectButton");
const hostButton = document.getElementById("hostMode");
const joinButton = document.getElementById("friendMode");
const copyServerUrlButton = document.getElementById("copyServerUrlButton");
const openFileButton = document.getElementById("openFileButton");
const launchVlcButton = document.getElementById("launchVlcButton");
const checkVlcButton = document.getElementById("checkVlcButton");
const playButton = document.getElementById("playButton");
const pauseButton = document.getElementById("pauseButton");
const seekButton = document.getElementById("seekButton");
const syncButton = document.getElementById("syncButton");
const autoFollow = document.getElementById("autoFollow");
const themeToggle = document.getElementById("themeToggle");
const checkUpdateButton = document.getElementById("checkUpdateButton");
const installUpdateButton = document.getElementById("installUpdateButton");

const fileNameEl = document.getElementById("fileName");
const mediaMetaEl = document.getElementById("mediaMeta");
const seekSlider = document.getElementById("seekSlider");
const currentTimeEl = document.getElementById("currentTime");
const durationTimeEl = document.getElementById("durationTime");
const peopleListEl = document.getElementById("peopleList");

let socket = null;
let role = "offline";
let latency = null;
let currentStatus = null;
let lastClients = [];
let lastClientsReceivedAt = 0;
let lastHostStatus = null;
let lastHostStatusReceivedAt = 0;
let lastHostStatusStamp = null;
let lastAutoSyncAt = 0;
let lastAutoSeekAt = 0;
let driftStreak = 0;
let connectionErrorLogged = false;
let suppressLocalBroadcastUntil = 0;
let suppressStatusEventsUntil = 0;

const SEEK_DETECTION_THRESHOLD = 2.5;
const AUTO_SYNC_DRIFT_THRESHOLD = 4;
const AUTO_SYNC_DRIFT_STREAK = 2;
const AUTO_SYNC_SEEK_COOLDOWN = 12000;
const AUTO_SYNC_STATE_COOLDOWN = 3000;
const MAX_LATENCY_COMPENSATION = 6;

const statusLabels = {
    serverBadge: ["Server on", "Server off"],
    socketBadge: ["Socket on", "Socket off"],
    vlcBadge: ["VLC on", "VLC off"],
};

const THEME_STORAGE_KEY = "wewatch-theme";
const updateMessages = new Set();
let lastLoggedDownloadPercent = -1;

const roleLabels = {
    offline: "Offline",
    host: "Host",
    member: "Member",
};

function applyTheme(theme) {
    const nextTheme = theme === "light" ? "light" : "dark";
    document.documentElement.dataset.theme = nextTheme;
    themeToggle.classList.toggle("is-light", nextTheme === "light");
    themeToggle.setAttribute("aria-label", `Switch to ${nextTheme === "light" ? "dark" : "light"} theme`);
    localStorage.setItem(THEME_STORAGE_KEY, nextTheme);
}

function formatTime(value) {
    const total = Math.max(0, Math.floor(Number(value) || 0));
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const seconds = total % 60;

    if (hours > 0) {
        return `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
    }

    return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

function formatDrift(value) {
    const drift = Math.abs(value) < 0.005 ? 0 : value;
    if (drift !== 0 && Math.abs(drift) < 0.1) {
        return drift < 0 ? "-<0.10s" : "<0.10s";
    }

    return `${drift.toFixed(2)}s`;
}

function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
}

function log(msg) {
    const time = new Date().toLocaleTimeString();
    const p = document.createElement("p");
    const timeEl = document.createElement("time");
    const messageEl = document.createElement("span");

    timeEl.textContent = time;
    messageEl.textContent = msg;

    p.append(timeEl, messageEl);
    logEl.prepend(p);
}

function showVlcOsd(message, kind = "event") {
    if (!message || !window.myAPI?.showOsdMessage) {
        return;
    }

    window.myAPI.showOsdMessage({
        message,
        kind,
        at: Date.now(),
    });
}

function logSessionEvent(message, kind = "event") {
    log(message);
    showVlcOsd(message, kind);
}

function getLocalActionMessage(successMessage, action = {}) {
    if (action.kind === "play") {
        return `Playing at ${formatTime(action.time)}`;
    }

    if (action.kind === "pause") {
        return `Paused at ${formatTime(action.time)}`;
    }

    return successMessage;
}

function getUserName() {
    return document.getElementById("userName").value.trim() || "User";
}

function getVlcConfig() {
    return {
        host: document.getElementById("vlcHost").value.trim() || "127.0.0.1",
        port: document.getElementById("vlcPort").value || "8080",
        password: document.getElementById("vlcPassword").value,
    };
}

function getServerPort() {
    return document.getElementById("serverPort").value || "3000";
}

function parseHostInput() {
    const raw = document.getElementById("serverUrl").value.trim().replace(/^wss?:\/\//i, "");
    const withoutPath = raw.split("/")[0];
    const [host] = withoutPath.split(":");
    const port = getServerPort();

    return {
        host: host || "",
        port,
        url: host ? `ws://${host}:${port}` : "",
    };
}

function getLocalServerUrl(port = getServerPort()) {
    return `ws://localhost:${port}`;
}

function getProbeUrl(url) {
    return `${url}${url.includes("?") ? "&" : "?"}probe=1`;
}

function getFileNameFromPath(filePath) {
    return String(filePath || "").split(/[\\/]/).pop() || "a file";
}

function setRole(r) {
    role = r;
    const label = roleLabels[r] || r;

    roleValue.innerText = label;
    roleBadge.className = "soft-pill " + r;
    roleBadge.innerText = label;
    sessionSummary.innerText = r === "offline" ? "Offline" : r === "host" ? "Hosting locally" : "Connected to host";

    disconnectBtn.disabled = r === "offline";

    if (r === "offline") {
        hostValue.innerText = "-";
    }
}

function setStatus(el, state, labelOverride) {
    if (!el) return;

    const labels = statusLabels[el.id] || ["On", "Off"];
    el.className = "badge " + (state ? "ok" : "bad");
    el.innerText = labelOverride || (state ? labels[0] : labels[1]);
}

function setWarning(el, label) {
    if (!el) return;

    el.className = "badge warn";
    el.innerText = label;
}

function updateInstallVisibility(visible) {
    installUpdateButton.hidden = !visible;
    installUpdateButton.disabled = !visible;
    installUpdateButton.setAttribute("aria-hidden", String(!visible));
}

function renderUpdateStatus(status = {}) {
    const state = status.state || "idle";
    const message = status.message || "Check update";

    checkUpdateButton.disabled = state === "checking" || state === "downloading";

    if (state === "checking") {
        lastLoggedDownloadPercent = -1;
        checkUpdateButton.innerText = "Checking...";
        updateInstallVisibility(false);
        return;
    }

    if (state === "available") {
        lastLoggedDownloadPercent = -1;
        checkUpdateButton.innerText = message;
        updateInstallVisibility(false);
        return;
    }

    if (state === "downloading") {
        const percent = Number(status.progress) || 0;
        checkUpdateButton.innerText = message;
        updateInstallVisibility(false);

        if (percent === 100 || lastLoggedDownloadPercent < 0 || percent - lastLoggedDownloadPercent >= 5) {
            lastLoggedDownloadPercent = percent;
            log(message);
        }
        return;
    }

    if (state === "downloaded") {
        lastLoggedDownloadPercent = -1;
        checkUpdateButton.innerText = "Update ready";
        installUpdateButton.innerText = "Restart to update";
        updateInstallVisibility(true);
        if (!updateMessages.has(message)) {
            updateMessages.add(message);
            log(message);
        }
        return;
    }

    if (state === "current") {
        checkUpdateButton.innerText = message || "No new versions found";
        updateInstallVisibility(false);
        return;
    }

    checkUpdateButton.innerText = state === "disabled" ? "Installed builds update" : "Check update";
    updateInstallVisibility(false);

    if (state === "error" && message && !updateMessages.has(message)) {
        updateMessages.add(message);
        log(`Update check failed: ${message}`);
        if (status.detail) {
            log(status.detail);
        }
    }
}

async function initAppInfo() {
    try {
        const info = await window.myAPI.getAppInfo();
        versionBadge.innerText = `v${info.version}`;
        versionBadge.title = info.packaged
            ? `Updates from ${info.repository}`
            : "Updates work after installing a packaged build";
    } catch {
        versionBadge.innerText = "v?";
    }

    try {
        renderUpdateStatus(await window.myAPI.getUpdateStatus());
    } catch {
        renderUpdateStatus();
    }
}

async function refreshServerBadge() {
    try {
        const status = await window.myAPI.getServerStatus();
        setStatus(serverStatus, status.running, status.running ? `Server on :${status.port}` : undefined);
    } catch {
        setStatus(serverStatus, false);
    }
}

function extractFilename(data) {
    return data.information?.category?.meta?.filename
        || data.information?.category?.meta?.title
        || "No media";
}

function getVlcTime(data) {
    const length = Number(data.length) || 0;
    const position = Number(data.position);
    const time = Number(data.time) || 0;

    if (length > 0 && Number.isFinite(position) && position >= 0) {
        return position * length;
    }

    return time;
}

function statusFromVlc(data) {
    return {
        state: data.state || "idle",
        time: getVlcTime(data),
        length: Number(data.length) || 0,
        position: Number(data.position) || 0,
        filename: extractFilename(data),
        latency,
        updatedAt: Date.now(),
    };
}

function offlineStatus() {
    return {
        state: "offline",
        time: 0,
        length: 0,
        position: 0,
        filename: "No media",
        latency,
        updatedAt: Date.now(),
    };
}

function updateMediaUi(status) {
    if (fileNameEl.innerText !== status.filename) {
        const message = `File changed: ${status.filename}`;
        if (status.filename !== "No media" && !socketIsOpen()) {
            logSessionEvent(message, "file");
        } else {
            log(message);
        }
    }

    fileNameEl.innerText = status.filename;
    fileNameEl.title = status.filename;
    mediaMetaEl.innerText = `${formatTime(status.time)} / ${formatTime(status.length)}`;
    currentTimeEl.innerText = formatTime(status.time);
    durationTimeEl.innerText = formatTime(status.length);
    seekSlider.max = Math.max(1, Math.floor(status.length));
    seekSlider.value = Math.min(Math.floor(status.time), Number(seekSlider.max));
}

function detectLocalVlcChange(previous, next) {
    if (!socketIsOpen() || Date.now() < suppressLocalBroadcastUntil) {
        return;
    }

    if (!previous || previous.state === "offline" || next.state === "offline") {
        return;
    }

    const hasMedia = next.filename && next.filename !== "No media";

    if (hasMedia && previous.filename !== next.filename) {
        sendAction("file", {
            filename: next.filename,
            time: next.time,
            source: "vlc",
        });
        return;
    }

    if (!hasMedia || previous.filename !== next.filename) {
        return;
    }

    if (previous.state !== next.state) {
        if (next.state === "playing") {
            sendAction("play", {
                time: next.time,
                source: "vlc",
            });
        } else if (next.state === "paused" || next.state === "stopped") {
            sendAction("pause", {
                time: next.time,
                source: "vlc",
            });
        }
        return;
    }

    const elapsed = Math.max(0, (next.updatedAt - previous.updatedAt) / 1000);
    const expectedDelta = previous.state === "playing" ? elapsed : 0;
    const actualDelta = next.time - previous.time;
    const jump = actualDelta - expectedDelta;

    if (Math.abs(jump) > SEEK_DETECTION_THRESHOLD) {
        sendAction("seek", {
            time: next.time,
            state: next.state,
            source: "vlc",
        });
    }
}

function socketIsOpen() {
    return socket && socket.readyState === WebSocket.OPEN;
}

function sendSocket(payload) {
    if (!socketIsOpen()) {
        return false;
    }

    socket.send(JSON.stringify(payload));
    return true;
}

function sendAction(kind, payload = {}) {
    sendSocket({
        type: "action",
        action: {
            kind,
            ...payload,
        },
    });
}

function suppressObservedChanges(duration = 2500) {
    const until = Date.now() + duration;
    suppressLocalBroadcastUntil = Math.max(suppressLocalBroadcastUntil, until);
    suppressStatusEventsUntil = Math.max(suppressStatusEventsUntil, until);
}

function probeServer(url) {
    return new Promise(resolve => {
        let settled = false;
        let probeSocket = null;
        const timer = setTimeout(() => finish(false), 2500);

        function finish(isOnline) {
            if (settled) return;

            settled = true;
            clearTimeout(timer);

            if (probeSocket && probeSocket.readyState === WebSocket.OPEN) {
                probeSocket.close();
            }

            resolve(isOnline);
        }

        try {
            probeSocket = new WebSocket(getProbeUrl(url));
        } catch {
            finish(false);
            return;
        }

        probeSocket.addEventListener("open", () => finish(true));
        probeSocket.addEventListener("error", () => finish(false));
    });
}

function publishStatus() {
    if (!currentStatus) return;

    sendSocket({
        type: "status",
        status: currentStatus,
        suppressEvents: Date.now() < suppressStatusEventsUntil,
    });
}

function broadcastLocalSync(reason) {
    if (!currentStatus) {
        log("No VLC status is available to sync yet");
        return false;
    }

    const sent = sendSocket({
        type: "sync",
        reason,
        status: currentStatus,
    });

    if (!sent) {
        log("Connect to a host before syncing");
    }

    return sent;
}

function getEstimatedTransitSeconds(status = lastHostStatus) {
    const hostLatency = Number(status?.latency) || 0;
    const localLatency = Number(latency) || 0;
    const roundTripMs = hostLatency + localLatency;

    return clamp(roundTripMs / 2000, 0, MAX_LATENCY_COMPENSATION);
}

function projectStatusTime(status, observedAt = status?.updatedAt) {
    if (!status) return null;

    const baseTime = Number(status.time) || 0;
    if (status.state !== "playing") {
        return baseTime;
    }

    const age = observedAt ? Math.max(0, (Date.now() - observedAt) / 1000) : 0;
    return baseTime + age;
}

function getProjectedLocalTime() {
    return projectStatusTime(currentStatus, currentStatus?.updatedAt);
}

function projectRemoteStatusTime(status, receivedAt = lastClientsReceivedAt) {
    if (!status) return null;

    if (status.state !== "playing") {
        return Number(status.time) || 0;
    }

    const age = receivedAt ? Math.max(0, (Date.now() - receivedAt) / 1000) : 0;
    return (Number(status.time) || 0) + age;
}

function getProjectedHostTime(status = lastHostStatus, receivedAt = lastHostStatusReceivedAt) {
    if (!status) return null;

    if (status.state !== "playing") {
        return Number(status.time) || 0;
    }

    const receiveAge = receivedAt ? Math.max(0, (Date.now() - receivedAt) / 1000) : 0;

    return (Number(status.time) || 0) + getEstimatedTransitSeconds(status) + receiveAge;
}

function getHostPeerDrift() {
    const localTime = getProjectedLocalTime();
    if (localTime === null || !currentStatus || currentStatus.state === "offline") {
        return null;
    }

    const memberDrifts = lastClients
        .filter(client => client.role !== "host" && client.status && client.status.state !== "offline")
        .map(client => {
            const memberTime = projectRemoteStatusTime(client.status, lastClientsReceivedAt);
            return memberTime === null ? null : memberTime - localTime;
        })
        .filter(value => value !== null && Number.isFinite(value));

    if (!memberDrifts.length) {
        return 0;
    }

    return memberDrifts.reduce((largest, value) => (
        Math.abs(value) > Math.abs(largest) ? value : largest
    ), memberDrifts[0]);
}

function connect(url, isHost) {
    if (!url) {
        log("Enter the host IP address before joining");
        return;
    }

    if (socket) {
        socket.close();
        socket = null;
    }

    connectionErrorLogged = false;

    let nextSocket;
    try {
        nextSocket = createSocket(url, handleSocketMessage);
    } catch (err) {
        setStatus(socketStatus, false);
        log(`Invalid server URL: ${err.message}`);
        return;
    }

    socket = nextSocket;

    socket.addEventListener("open", () => {
        setStatus(socketStatus, true);
        setStatus(serverStatus, true, isHost ? `Server on :${getServerPort()}` : "Server on");
        setRole(isHost ? "host" : "member");
        hostValue.innerText = url;

        log(isHost ? "You are host" : "Joined as member");
        log(`Connected to ${url}`);

        sendSocket({
            type: "hello",
            name: getUserName(),
            role: isHost ? "host" : "member",
        });

        publishStatus();
    });

    socket.addEventListener("error", () => {
        setStatus(socketStatus, false);
        setStatus(serverStatus, false);
        connectionErrorLogged = true;
        log(`Could not connect to ${url}. The host server may not be running, or the IP/port may be wrong.`);
    });

    socket.addEventListener("close", () => {
        setStatus(socketStatus, false);
        setRole("offline");
        socket = null;
        refreshServerBadge();

        if (!connectionErrorLogged) {
            log("Disconnected");
        }
    });
}

function handleSocketMessage(data) {
    if (data.type === "pong") {
        latency = Date.now() - data.sentAt;
        if (currentStatus) {
            currentStatus.latency = latency;
            publishStatus();
        }
        renderPeople(lastClients);
        return;
    }

    if (data.type === "clients") {
        lastClients = data.clients;
        lastClientsReceivedAt = Date.now();
        updateHostStatus(data.clients);
        renderPeople(data.clients);
        maybeAutoSync();
        return;
    }

    if (data.type === "event") {
        logSessionEvent(data.message || data.event?.message || "Session event", data.event?.kind || "event");
        return;
    }

    if (data.type === "sync") {
        setSync({
            position: data.status?.time,
            state: data.status?.state,
        });

        if (role === "member") {
            applyHostSync(data.status, "Host sync", true);
        }
        return;
    }

    if (data.type === "control") {
        applyRemoteControl(data.action, data.name || "Someone");
        return;
    }

    if (data.type === "play") {
        setSync(data);
    }
}

function updateHostStatus(clients) {
    const host = clients.find(client => client.role === "host");
    const nextStatus = host?.status || null;
    const nextStamp = nextStatus?.serverReceivedAt
        || `${nextStatus?.state}:${nextStatus?.time}:${nextStatus?.filename}:${nextStatus?.updatedAt}`;

    if (nextStatus && nextStamp !== lastHostStatusStamp) {
        lastHostStatusReceivedAt = Date.now();
        lastHostStatusStamp = nextStamp;
    }

    lastHostStatus = nextStatus;
    updateDrift();
}

function updateDrift() {
    if (role === "host") {
        const hostDrift = getHostPeerDrift();
        driftEl.innerText = hostDrift === null ? "0.00s" : formatDrift(hostDrift);
        return;
    }

    if (role === "member" && lastHostStatus && currentStatus) {
        const expectedHostTime = getProjectedHostTime();
        const localTime = getProjectedLocalTime();
        if (expectedHostTime === null || localTime === null) {
            driftEl.innerText = "0.00s";
            return;
        }

        const drift = localTime - expectedHostTime;
        driftEl.innerText = formatDrift(drift);
        return;
    }

    const expected = getExpectedPosition();
    if (expected !== null && currentStatus) {
        const drift = currentStatus.time - expected;
        driftEl.innerText = formatDrift(drift);
        return;
    }

    driftEl.innerText = currentStatus ? "0.00s" : "-";
}

async function refreshVlcStatus(shouldLog = false) {
    try {
        const data = await window.myAPI.getVlcStatus(getVlcConfig());
        const status = statusFromVlc(data);
        const previous = currentStatus;

        currentStatus = status;
        setStatus(vlcStatus, true);
        updateMediaUi(status);
        detectLocalVlcChange(previous, status);
        publishStatus();
        updateDrift();

        if (shouldLog) {
            log(`VLC connected: ${status.filename}`);
        }

        maybeAutoSync();
        return status;
    } catch (err) {
        currentStatus = offlineStatus();
        setStatus(vlcStatus, false);
        publishStatus();
        updateDrift();

        if (shouldLog) {
            log(`VLC is not reachable at ${getVlcConfig().host}:${getVlcConfig().port}`);
        }

        return null;
    }
}

async function runVlcCommand(command, value, successMessage, action) {
    try {
        await window.myAPI.sendVlcCommand(command, value, getVlcConfig());
        setStatus(vlcStatus, true);
        if (!action || !socketIsOpen()) {
            if (action) {
                logSessionEvent(getLocalActionMessage(successMessage, action), action.kind);
            } else {
                log(successMessage);
            }
        }

        if (action) {
            suppressObservedChanges();
            sendAction(action.kind, action);
        }

        setTimeout(async () => {
            await refreshVlcStatus();
            if (role === "host" && !action) {
                broadcastLocalSync(successMessage);
            }
        }, 250);
    } catch (err) {
        setStatus(vlcStatus, false);
        log(`${successMessage} failed: ${err.message}`);
    }
}

async function applyRemoteControl(action = {}, actorName = "Someone") {
    try {
        suppressObservedChanges();

        const hasTime = action.time !== undefined && Number.isFinite(Number(action.time));
        if (hasTime && (action.kind === "play" || action.kind === "pause")) {
            await window.myAPI.sendVlcCommand("seek", Math.floor(Number(action.time)), getVlcConfig());
        }

        if (action.kind === "play") {
            await window.myAPI.sendVlcCommand("pl_forceresume", undefined, getVlcConfig());
        } else if (action.kind === "pause") {
            await window.myAPI.sendVlcCommand("pl_forcepause", undefined, getVlcConfig());
        } else if (action.kind === "seek") {
            await window.myAPI.sendVlcCommand("seek", Math.floor(Number(action.time) || 0), getVlcConfig());
            const stateMismatch = action.state && currentStatus?.state !== action.state;
            if (stateMismatch && action.state === "playing") {
                await window.myAPI.sendVlcCommand("pl_forceresume", undefined, getVlcConfig());
            } else if (stateMismatch && (action.state === "paused" || action.state === "stopped")) {
                await window.myAPI.sendVlcCommand("pl_forcepause", undefined, getVlcConfig());
            }
        } else {
            return;
        }

        setStatus(vlcStatus, true);
        await refreshVlcStatus();
    } catch (err) {
        setStatus(vlcStatus, false);
        log(`Could not apply ${actorName}'s ${action.kind || "control"}: ${err.message}`);
    }
}

async function applyHostSync(status, label = "Synced", force = false, options = {}) {
    if (!status || status.state === "offline") {
        log("No host VLC status is available yet");
        return;
    }

    if (!currentStatus || currentStatus.state === "offline") {
        await refreshVlcStatus();
    }

    const projectedTime = options.targetTime ?? getProjectedHostTime(status);
    const targetTime = Math.max(0, Math.floor(Number(projectedTime) || 0));
    const localTime = getProjectedLocalTime();
    const drift = localTime !== null ? localTime - targetTime : 0;
    const shouldSeek = options.seek !== false && (force || Math.abs(drift) > 1.25);
    const stateMismatch = currentStatus?.state !== status.state;

    try {
        suppressObservedChanges();

        if (shouldSeek) {
            await window.myAPI.sendVlcCommand("seek", targetTime, getVlcConfig());
        }

        if (stateMismatch && status.state === "playing") {
            await window.myAPI.sendVlcCommand("pl_forceresume", undefined, getVlcConfig());
        } else if (stateMismatch && (status.state === "paused" || status.state === "stopped")) {
            await window.myAPI.sendVlcCommand("pl_forcepause", undefined, getVlcConfig());
        }

        setSync({
            position: targetTime,
            state: status.state,
        });

        lastAutoSyncAt = Date.now();
        if (shouldSeek) {
            lastAutoSeekAt = Date.now();
        }
        log(`${label} at ${formatTime(targetTime)}`);
        await refreshVlcStatus();
    } catch (err) {
        log(`Sync failed: ${err.message}`);
    }
}

function maybeAutoSync() {
    if (role !== "member" || !autoFollow.checked || !lastHostStatus || !currentStatus) {
        driftStreak = 0;
        return;
    }

    const now = Date.now();
    if (now - lastAutoSyncAt < AUTO_SYNC_STATE_COOLDOWN) {
        return;
    }

    const expectedHostTime = getProjectedHostTime();
    const localTime = getProjectedLocalTime();
    if (expectedHostTime === null || localTime === null) {
        return;
    }

    const drift = localTime - expectedHostTime;
    const absDrift = Math.abs(drift);
    const stateMismatch = currentStatus.state !== lastHostStatus.state
        && lastHostStatus.state !== "idle"
        && lastHostStatus.state !== "offline";

    if (stateMismatch) {
        driftStreak = 0;
        applyHostSync(lastHostStatus, "Auto state synced", false, {
            targetTime: expectedHostTime,
            seek: absDrift > AUTO_SYNC_DRIFT_THRESHOLD
        });
        return;
    }

    if (absDrift < AUTO_SYNC_DRIFT_THRESHOLD) {
        driftStreak = 0;
        return;
    }

    driftStreak += 1;
    if (driftStreak < AUTO_SYNC_DRIFT_STREAK || now - lastAutoSeekAt < AUTO_SYNC_SEEK_COOLDOWN) {
        return;
    }

    driftStreak = 0;
    applyHostSync(lastHostStatus, "Auto timeline synced", false, {
        targetTime: expectedHostTime,
        seek: true
    });
}

function renderPeople(clients) {
    peopleListEl.innerHTML = "";

    if (!clients.length) {
        const empty = document.createElement("div");
        empty.className = "empty-state";
        empty.innerText = "No people connected";
        peopleListEl.appendChild(empty);
        return;
    }

    clients.forEach(c => {
        const div = document.createElement("div");
        div.className = "person";

        const status = c.status;
        const state = status?.state || "waiting";
        const time = status ? formatTime(status.time) : "--:--";
        const duration = status ? formatTime(status.length) : "--:--";
        const file = status?.filename || "No VLC status yet";
        const personLatency = status?.latency;
        const personRole = roleLabels[c.role] || "Member";

        const details = document.createElement("div");
        const nameEl = document.createElement("strong");
        const metaEl = document.createElement("span");
        const fileEl = document.createElement("span");
        const latencyEl = document.createElement("small");

        nameEl.innerText = `${c.name || "User"} - ${personRole}`;
        metaEl.innerText = `${state} - ${time} / ${duration}`;
        fileEl.className = "person-file";
        fileEl.innerText = file;
        fileEl.title = file;
        latencyEl.innerText = personLatency !== null && personLatency !== undefined ? `${personLatency}ms` : "--";

        details.append(nameEl, metaEl, fileEl);
        div.append(details, latencyEl);

        peopleListEl.appendChild(div);
    });
}

hostButton.onclick = async () => {
    const port = getServerPort();
    const result = await window.myAPI.startServer(port);

    if (!result.ok) {
        setWarning(serverStatus, "Server issue");
        log(`Server is not running on port ${port}: ${result.message}`);
        return;
    }

    setStatus(serverStatus, true);
    log(result.alreadyRunning ? `Server already running on port ${result.port}` : `Server listening on port ${result.port}`);
    connect(getLocalServerUrl(result.port), true);
};

joinButton.onclick = async () => {
    const endpoint = parseHostInput();

    if (!endpoint.host) {
        log("Enter the host IP address before joining");
        return;
    }

    setWarning(serverStatus, "Checking server");
    log(`Checking host ${endpoint.host}:${endpoint.port}`);

    const online = await probeServer(endpoint.url);
    if (!online) {
        setStatus(serverStatus, false);
        log(`Server is not reachable at ${endpoint.host}:${endpoint.port}`);
        return;
    }

    setStatus(serverStatus, true, "Server on");
    log(`Joining host ${endpoint.host}:${endpoint.port}`);
    connect(endpoint.url, false);
};

disconnectBtn.onclick = () => {
    if (!socket) return;

    const name = getUserName();
    socket.close();
    logSessionEvent(`${name} disconnected`, "left");
};

copyServerUrlButton.onclick = async () => {
    const endpoint = parseHostInput();
    const address = endpoint.host ? `${endpoint.host}:${endpoint.port}` : `localhost:${getServerPort()}`;

    try {
        await navigator.clipboard.writeText(address);
        log("Connection address copied");
    } catch {
        log("Could not copy connection address");
    }
};

openFileButton.onclick = async () => {
    const result = await window.myAPI.openMediaFile(getVlcConfig());

    if (result.canceled) {
        return;
    }

    if (!result.ok) {
        setStatus(vlcStatus, false);
        log(`Open file failed: ${result.message}`);
        return;
    }

    setStatus(vlcStatus, true);
    const filename = getFileNameFromPath(result.filePath);
    if (socketIsOpen()) {
        log(`Opened file: ${filename}`);
    } else {
        logSessionEvent(`Opened file: ${filename}`, "file");
    }
    suppressObservedChanges();
    sendAction("file", {
        filename,
    });
    setTimeout(refreshVlcStatus, 400);
};

launchVlcButton.onclick = async () => {
    const result = await window.myAPI.launchVlc(getVlcConfig());

    if (!result.ok) {
        setStatus(vlcStatus, false);
        log(result.message || "VLC could not be launched");
        return;
    }

    setStatus(vlcStatus, true);
    log("VLC launched");
};

checkVlcButton.onclick = () => {
    refreshVlcStatus(true);
};

playButton.onclick = () => {
    runVlcCommand("pl_forceresume", undefined, "Playing", {
        kind: "play",
        time: currentStatus?.time || Number(seekSlider.value) || 0,
    });
};

pauseButton.onclick = () => {
    runVlcCommand("pl_forcepause", undefined, "Paused", {
        kind: "pause",
        time: currentStatus?.time || Number(seekSlider.value) || 0,
    });
};

seekButton.onclick = () => {
    const time = Math.floor(Number(seekSlider.value) || 0);
    runVlcCommand("seek", time, `Seek to ${formatTime(time)}`, {
        kind: "seek",
        time,
        state: currentStatus?.state || "paused",
    });
};

syncButton.onclick = () => {
    if (role === "host") {
        if (broadcastLocalSync("Manual sync")) {
            sendAction("sync", {
                time: currentStatus?.time || 0,
            });
            log("Sync sent to connected members");
        }
        return;
    }

    applyHostSync(lastHostStatus, "Synced", true);
};

themeToggle.onclick = () => {
    applyTheme(document.documentElement.dataset.theme === "light" ? "dark" : "light");
};

checkUpdateButton.onclick = async () => {
    checkUpdateButton.disabled = true;
    checkUpdateButton.innerText = "Checking...";
    const result = await window.myAPI.checkForUpdates();
    renderUpdateStatus(result);
};

installUpdateButton.onclick = async () => {
    installUpdateButton.disabled = true;
    installUpdateButton.innerText = "Restarting...";
    const result = await window.myAPI.installUpdate();
    if (!result.ok) {
        log(result.message || "No update is ready yet");
        renderUpdateStatus(await window.myAPI.getUpdateStatus());
    }
};

window.myAPI.onUpdateStatus(renderUpdateStatus);

setInterval(refreshVlcStatus, 1000);

setInterval(() => {
    sendSocket({
        type: "ping",
        sentAt: Date.now(),
    });
}, 2000);

refreshServerBadge();
applyTheme(localStorage.getItem(THEME_STORAGE_KEY) || "dark");
initAppInfo();

setStatus(socketStatus, false);
setStatus(vlcStatus, false);
setRole(role);
renderPeople([]);
