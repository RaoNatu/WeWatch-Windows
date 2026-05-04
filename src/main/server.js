const { WebSocketServer } = require('ws');
const crypto = require("crypto");

const clients = new Map();

let wss;
let currentPort = null;

function startServer(port = 3000) {
    const targetPort = Number(port) || 3000;

    if (wss && currentPort === targetPort) {
        return Promise.resolve({ ok: true, port: currentPort, alreadyRunning: true });
    }

    return stopServer().then(() => new Promise((resolve, reject) => {
        try {
            wss = new WebSocketServer({ host: "0.0.0.0", port: targetPort });
        } catch (err) {
            wss = null;
            currentPort = null;
            reject(err);
            return;
        }

        wss.once("listening", () => {
            currentPort = targetPort;
            console.log(`Server started at port ${targetPort}`);
            resolve({ ok: true, port: targetPort });
        });

        wss.once("error", (err) => {
            wss = null;
            currentPort = null;
            reject(err);
        });

        wss.on('connection', handleConnection);
    }));
}

function handleConnection(ws, request) {
        const requestUrl = new URL(request.url || "/", "ws://localhost");

        if (requestUrl.searchParams.get("probe") === "1") {
            ws.send(JSON.stringify({
                type: "server-ready",
                port: currentPort,
            }));
            ws.close();
            return;
        }

        const id = crypto.randomUUID();

        const client = {
            id,
            name: "User",
            role: "member",
            socket: ws,
            status: null,
            joined: false,
            lastEventAt: new Map()
        };

        clients.set(id, client);
        console.log('Client connected!');

        ws.on('message', (message) => {
            let data;

            try {
                data = JSON.parse(message.toString());
            } catch {
                console.log("Invalid message");
                return;
            }

            if (data.type === "ping") {
                ws.send(JSON.stringify({
                    type: "pong",
                    sentAt: data.sentAt
                }));
                return;
            }

            if (data.type === "hello") {
                const client = clients.get(id);
                if (client) {
                    client.name = data.name || "User";
                    client.role = data.role || client.role;

                    if (!client.joined) {
                        client.joined = true;
                        emitClientEvent(client, "joined", `${client.name} joined`);
                    }
                }

                broadcastClients();
                return;
            }

            if (data.type === "status") {
                const client = clients.get(id);
                if (client) {
                    const previous = client.status;
                    client.status = {
                        ...data.status,
                        serverReceivedAt: Date.now()
                    };
                    if (!data.suppressEvents) {
                        broadcastStatusEvents(client, previous, client.status);
                    }
                }

                broadcastClients();
                return;
            }

            if (data.type === "action") {
                const client = clients.get(id);
                if (client) {
                    const message = getActionMessage(client, data.action);
                    if (message) {
                        emitClientEvent(client, data.action?.kind || "action", message);
                    }

                    broadcastToOthers(ws, {
                        type: "control",
                        senderId: id,
                        name: client.name,
                        action: data.action,
                        serverSentAt: Date.now(),
                    });
                }

                return;
            }

            if (data.type === "sync") {
                broadcast({
                    ...data,
                    senderId: id,
                    serverSentAt: Date.now()
                });
                return;
            }

            if (data.type === "play") {
                for (const c of clients.values()) {
                    if (c.socket.readyState === ws.OPEN) {
                        c.socket.send(JSON.stringify(data));
                    }
                }
            }
        });

        ws.on('close', () => {
            const client = clients.get(id);
            if (client) {
                emitClientEvent(client, "left", `${client.name} left`);
            }

            clients.delete(id);
            console.log("Client disconnected");

            broadcastClients();
        });

        broadcastClients();
}

function broadcastClients() {
    const list = Array.from(clients.values()).map(c => ({
        id: c.id,
        name: c.name,
        role: c.role,
        status: c.status
    }));

    const message = JSON.stringify({
        type: "clients",
        clients: list
    });

    for (const client of clients.values()) {
        if (client.socket.readyState === client.socket.OPEN) {
            client.socket.send(message);
        }
    }
}

function broadcast(payload) {
    const message = JSON.stringify(payload);

    for (const client of clients.values()) {
        if (client.socket.readyState === client.socket.OPEN) {
            client.socket.send(message);
        }
    }
}

function broadcastToOthers(sender, payload) {
    const message = JSON.stringify(payload);

    for (const client of clients.values()) {
        if (client.socket !== sender && client.socket.readyState === client.socket.OPEN) {
            client.socket.send(message);
        }
    }
}

function formatTime(value) {
    const total = Math.max(0, Math.floor(Number(value) || 0));
    const minutes = Math.floor(total / 60);
    const seconds = total % 60;

    return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

function getActionMessage(client, action = {}) {
    if (action.kind === "play") {
        return `${client.name} playing`;
    }

    if (action.kind === "pause") {
        return `${client.name} paused`;
    }

    if (action.kind === "seek") {
        return `${client.name} skipped to ${formatTime(action.time)}`;
    }

    if (action.kind === "file") {
        return `${client.name} selected ${action.filename || "a file"}`;
    }

    if (action.kind === "sync") {
        return `${client.name} synced to ${formatTime(action.time)}`;
    }

    return null;
}

function broadcastStatusEvents(client, previous, next) {
    if (!next) return;

    if (previous?.filename !== next.filename && next.filename && next.filename !== "No media") {
        emitClientEvent(client, "file", `${client.name} selected ${next.filename}`);
    }

    if (previous && previous.state !== next.state) {
        if (next.state === "playing") {
            emitClientEvent(client, "play", `${client.name} playing`);
        } else if (next.state === "paused") {
            emitClientEvent(client, "pause", `${client.name} paused`);
        }
    }

    if (previous?.filename === next.filename && Math.abs((next.time || 0) - (previous.time || 0)) > 5) {
        emitClientEvent(client, "seek", `${client.name} skipped to ${formatTime(next.time)}`);
    }
}

function emitClientEvent(client, kind, message) {
    const now = Date.now();
    const previous = client.lastEventAt.get(kind);

    if (previous && now - previous < 1500) {
        return;
    }

    client.lastEventAt.set(kind, now);

    broadcast({
        type: "event",
        event: {
            kind,
            name: client.name,
            message,
            at: now
        },
        message
    });
}

function stopServer() {
    if (!wss) {
        return Promise.resolve();
    }

    for (const client of clients.values()) {
        client.socket.close();
    }

    clients.clear();

    return new Promise(resolve => {
        wss.close(() => {
            wss = null;
            currentPort = null;
            resolve();
        });
    });
}

function getServerStatus() {
    return {
        running: Boolean(wss),
        port: currentPort,
        clients: clients.size,
    };
}

module.exports = { startServer, stopServer, getServerStatus };
