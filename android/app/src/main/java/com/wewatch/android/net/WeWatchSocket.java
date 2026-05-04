package com.wewatch.android.net;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WeWatchSocket {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int OPCODE_TEXT = 0x1;
    private static final int OPCODE_CLOSE = 0x8;
    private static final int OPCODE_PING = 0x9;
    private static final int OPCODE_PONG = 0xA;

    private final URI uri;
    private final WeWatchSocketListener listener;
    private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();
    private final Object sendLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closing = new AtomicBoolean(false);

    private Socket socket;
    private InputStream input;
    private OutputStream output;

    public WeWatchSocket(URI uri, WeWatchSocketListener listener) {
        this.uri = uri;
        this.listener = listener;
    }

    public void connect() {
        readerExecutor.execute(() -> {
            try {
                openSocket();
                performHandshake();
                running.set(true);
                listener.onOpen();
                readFrames();
            } catch (Exception error) {
                if (!closing.get()) {
                    listener.onFailure(error);
                }
            } finally {
                running.set(false);
                closeQuietly();
                listener.onClosed();
                readerExecutor.shutdownNow();
                writerExecutor.shutdownNow();
            }
        });
    }

    public boolean isOpen() {
        return running.get() && socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void sendText(String text) {
        if (!isOpen()) {
            return;
        }

        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        try {
            writerExecutor.execute(() -> {
                try {
                    if (isOpen()) {
                        sendFrame(OPCODE_TEXT, payload);
                    }
                } catch (IOException error) {
                    listener.onFailure(error);
                    close();
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    public void close() {
        closing.set(true);
        running.set(false);
        try {
            writerExecutor.execute(() -> {
                try {
                    if (output != null) {
                        sendFrame(OPCODE_CLOSE, new byte[0]);
                    }
                } catch (IOException ignored) {
                } finally {
                    closeQuietly();
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
        closeQuietly();
    }

    private void openSocket() throws IOException {
        if (!"ws".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Only ws:// connections are supported");
        }

        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("Invalid WebSocket host");
        }

        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        input = socket.getInputStream();
        output = socket.getOutputStream();
    }

    private void performHandshake() throws Exception {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            path += "?" + uri.getRawQuery();
        }

        String host = uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 80);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";

        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();

        String response = readHttpHeader();
        String[] lines = response.split("\r\n");
        if (lines.length == 0 || !lines[0].contains(" 101 ")) {
            throw new IOException("Server did not accept the WebSocket upgrade");
        }

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(
                        lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                        lines[i].substring(colon + 1).trim()
                );
            }
        }

        String expectedAccept = expectedAcceptKey(key);
        String actualAccept = headers.get("sec-websocket-accept");
        if (!expectedAccept.equals(actualAccept)) {
            throw new IOException("Server returned an invalid WebSocket accept key");
        }
    }

    private String readHttpHeader() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        int[] marker = {'\r', '\n', '\r', '\n'};

        while (buffer.size() < 32768) {
            int value = input.read();
            if (value == -1) {
                throw new EOFException("Connection closed during WebSocket handshake");
            }
            buffer.write(value);
            if (value == marker[matched]) {
                matched++;
                if (matched == marker.length) {
                    return buffer.toString(StandardCharsets.US_ASCII.name());
                }
            } else {
                matched = value == marker[0] ? 1 : 0;
            }
        }

        throw new IOException("WebSocket handshake response was too large");
    }

    private String expectedAcceptKey(String key) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
        return Base64.encodeToString(hash, Base64.NO_WRAP);
    }

    private void readFrames() throws IOException {
        while (running.get()) {
            int first = input.read();
            if (first == -1) {
                break;
            }
            int second = readByte();
            int opcode = first & 0x0F;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;

            if (length == 126) {
                length = readUnsignedShort();
            } else if (length == 127) {
                length = readUnsignedLong();
            }

            if (length > Integer.MAX_VALUE) {
                throw new IOException("WebSocket frame is too large");
            }

            byte[] mask = null;
            if (masked) {
                mask = readFully(4);
            }

            byte[] payload = readFully((int) length);
            if (masked && mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }

            if (opcode == OPCODE_TEXT) {
                listener.onMessage(new String(payload, StandardCharsets.UTF_8));
            } else if (opcode == OPCODE_PING) {
                sendFrame(OPCODE_PONG, payload);
            } else if (opcode == OPCODE_CLOSE) {
                break;
            }
        }
    }

    private int readByte() throws IOException {
        int value = input.read();
        if (value == -1) {
            throw new EOFException("Connection closed");
        }
        return value;
    }

    private int readUnsignedShort() throws IOException {
        return (readByte() << 8) | readByte();
    }

    private long readUnsignedLong() throws IOException {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | readByte();
        }
        return value;
    }

    private byte[] readFully(int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(data, offset, length - offset);
            if (read == -1) {
                throw new EOFException("Connection closed while reading frame");
            }
            offset += read;
        }
        return data;
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        synchronized (sendLock) {
            if (output == null) {
                return;
            }

            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x80 | opcode);
            int length = payload.length;
            if (length <= 125) {
                frame.write(0x80 | length);
            } else if (length <= 65535) {
                frame.write(0x80 | 126);
                frame.write((length >>> 8) & 0xFF);
                frame.write(length & 0xFF);
            } else {
                frame.write(0x80 | 127);
                long longLength = length;
                for (int i = 7; i >= 0; i--) {
                    frame.write((int) ((longLength >>> (8 * i)) & 0xFF));
                }
            }

            byte[] mask = new byte[4];
            random.nextBytes(mask);
            frame.write(mask);
            for (int i = 0; i < payload.length; i++) {
                frame.write(payload[i] ^ mask[i % 4]);
            }

            output.write(frame.toByteArray());
            output.flush();
        }
    }

    private void closeQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
