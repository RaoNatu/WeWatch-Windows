export function createSocket(url, onMessage) {
    const socket = new WebSocket(url);

    socket.addEventListener("open", () => {
        console.log("Connected to server");
    });

    socket.addEventListener("message", (event) => {
        try {
            const data = JSON.parse(event.data);
            onMessage(data);
        } catch (err) {
            console.error("Invalid message", err);
        }
    });

    return socket;
}
