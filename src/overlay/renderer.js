const stack = document.getElementById("toastStack");
const MAX_TOASTS = 3;

function formatTime(value) {
    try {
        return new Date(value || Date.now()).toLocaleTimeString([], {
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
        });
    } catch {
        return "";
    }
}

function normalizeHexColor(value, fallback) {
    const raw = String(value || "").trim();
    return /^#[0-9a-f]{6}$/i.test(raw) ? raw.toLowerCase() : fallback;
}

function hexToRgbTriplet(value) {
    const hex = normalizeHexColor(value, "#6ee7d8").slice(1);
    const number = Number.parseInt(hex, 16);
    const red = (number >> 16) & 255;
    const green = (number >> 8) & 255;
    const blue = number & 255;

    return `${red}, ${green}, ${blue}`;
}

function applyColors(colors = {}) {
    const primary = normalizeHexColor(colors.primary, "#6ee7d8");
    const secondary = normalizeHexColor(colors.secondary, "#8f7dff");
    const tertiary = normalizeHexColor(colors.tertiary, "#ff8b6e");

    document.documentElement.style.setProperty("--primary", primary);
    document.documentElement.style.setProperty("--primary-rgb", hexToRgbTriplet(primary));
    document.documentElement.style.setProperty("--primary-2-rgb", hexToRgbTriplet(secondary));
    document.documentElement.style.setProperty("--primary-3-rgb", hexToRgbTriplet(tertiary));
}

function addToast(payload = {}) {
    const message = String(payload.message || "").trim();
    if (!message) {
        return;
    }

    applyColors(payload.colors);

    const toast = document.createElement("article");
    toast.className = `toast ${payload.kind || "event"}`;

    const time = document.createElement("time");
    time.textContent = formatTime(payload.at);

    const text = document.createElement("p");
    text.textContent = message;

    toast.append(time, text);
    stack.prepend(toast);

    while (stack.children.length > MAX_TOASTS) {
        stack.lastElementChild?.remove();
    }

    const duration = Math.max(1600, Math.min(8000, Number(payload.duration) || 3600));
    setTimeout(() => {
        toast.classList.add("is-hiding");
        setTimeout(() => toast.remove(), 260);
    }, duration);
}

window.wewatchOsd.onMessage(addToast);
