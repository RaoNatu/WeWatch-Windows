let lastSync = null;

export function setSync(data) {
    lastSync = {
        position: Number(data.position) || 0,
        state: data.state || "playing",
        time: Date.now(),
    };
}

export function getExpectedPosition() {
    if (!lastSync) return null;
    if (lastSync.state !== "playing") return lastSync.position;

    const elapsed = (Date.now() - lastSync.time) / 1000;
    return lastSync.position + elapsed;
}
