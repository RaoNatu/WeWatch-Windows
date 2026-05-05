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

function addToast(payload = {}) {
    const message = String(payload.message || "").trim();
    if (!message) {
        return;
    }

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
