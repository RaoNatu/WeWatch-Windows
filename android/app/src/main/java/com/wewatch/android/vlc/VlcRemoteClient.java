package com.wewatch.android.vlc;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class VlcRemoteClient {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 2500;
    private static final int LONG_POLL_TIMEOUT_MS = 1800;

    private String baseUrl = "http://127.0.0.1:8080";
    private String secret = "";
    private String cookie = "";
    private boolean androidRemote = false;
    private boolean legacyRemote = false;
    private Status lastStatus = null;

    public synchronized void configure(String nextBaseUrl, String nextSecret) {
        String normalized = normalizeBaseUrl(nextBaseUrl);
        if (!normalized.equals(baseUrl) || !safe(nextSecret).equals(secret)) {
            cookie = "";
            androidRemote = false;
            legacyRemote = false;
            lastStatus = null;
        }
        baseUrl = normalized;
        secret = safe(nextSecret);
    }

    public synchronized String getBaseUrl() {
        return baseUrl;
    }

    public synchronized String getModeLabel() {
        if (androidRemote) {
            return "VLC Android Remote";
        }
        if (legacyRemote) {
            return "VLC HTTP";
        }
        return "VLC Remote";
    }

    public synchronized Status connect() throws IOException, JSONException {
        IOException androidError = null;
        if (!secret.isEmpty()) {
            try {
                upgradeToSecureRemoteIfAvailable();
                authenticateAndroidRemote();
                androidRemote = true;
                legacyRemote = false;
                sendAndroidMessage("hello", null, null);
                return pollAndroidRemote();
            } catch (IOException error) {
                androidError = error;
                cookie = "";
                androidRemote = false;
            }
        }

        try {
            Status status = getLegacyStatus();
            legacyRemote = true;
            androidRemote = false;
            return status;
        } catch (IOException legacyError) {
            if (androidError != null) {
                throw androidError;
            }
            throw legacyError;
        }
    }

    public synchronized Status poll() throws IOException, JSONException {
        if (androidRemote) {
            return pollAndroidRemote();
        }
        if (legacyRemote) {
            return getLegacyStatus();
        }
        return connect();
    }

    public synchronized void play() throws IOException, JSONException {
        if (androidRemote) {
            sendAndroidMessage("play", null, null);
            return;
        }
        sendLegacyCommand("pl_forceresume", null);
    }

    public synchronized void pause() throws IOException, JSONException {
        if (androidRemote) {
            sendAndroidMessage("pause", null, null);
            return;
        }
        sendLegacyCommand("pl_forcepause", null);
    }

    public synchronized void seek(long targetMs) throws IOException, JSONException {
        long clamped = Math.max(0, targetMs);
        if (androidRemote) {
            sendAndroidMessage("set-progress", String.valueOf(clamped), null);
            return;
        }
        sendLegacyCommand("seek", String.valueOf(clamped / 1000L));
    }

    private void upgradeToSecureRemoteIfAvailable() {
        if (!baseUrl.toLowerCase(Locale.US).startsWith("http://")) {
            return;
        }

        try {
            HttpResult result = request("GET", endpoint("secure-url"), null, null, READ_TIMEOUT_MS, false);
            if (result.code >= 200 && result.code < 300) {
                String next = stripJsonString(result.body.trim());
                if (next.toLowerCase(Locale.US).startsWith("https://")) {
                    baseUrl = normalizeBaseUrl(next);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void authenticateAndroidRemote() throws IOException {
        cookie = "";
        HttpResult codeResult = request("POST", endpoint("code"), "challenge=", "application/x-www-form-urlencoded", READ_TIMEOUT_MS, false);
        if (codeResult.code < 200 || codeResult.code >= 300) {
            throw new IOException("VLC Remote did not return an OTP challenge");
        }

        String challenge = stripJsonString(codeResult.body.trim());
        if (challenge.isEmpty()) {
            throw new IOException("VLC Remote returned an empty OTP challenge");
        }

        String body = "code=" + urlEncode(sha256(secret + challenge));
        HttpResult verifyResult = request("POST", endpoint("verify-code"), body, "application/x-www-form-urlencoded", READ_TIMEOUT_MS, false);
        if (verifyResult.code < 200 || verifyResult.code >= 400) {
            throw new IOException("VLC OTP/password was rejected");
        }

        HttpResult ticket = request("GET", endpoint("wsticket"), null, null, READ_TIMEOUT_MS, true);
        if (ticket.code == HttpURLConnection.HTTP_UNAUTHORIZED || ticket.code == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new IOException("VLC OTP/password was rejected");
        }
        if (ticket.code < 200 || ticket.code >= 300) {
            throw new IOException("VLC Remote authentication failed");
        }
    }

    private Status pollAndroidRemote() throws IOException, JSONException {
        HttpResult result;
        try {
            result = request("GET", endpoint("longpolling"), null, null, LONG_POLL_TIMEOUT_MS, true);
        } catch (SocketTimeoutException timeout) {
            return lastStatus;
        }
        if (result.code == HttpURLConnection.HTTP_CLIENT_TIMEOUT) {
            return lastStatus;
        }
        if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED && !secret.isEmpty()) {
            authenticateAndroidRemote();
            try {
                result = request("GET", endpoint("longpolling"), null, null, LONG_POLL_TIMEOUT_MS, true);
            } catch (SocketTimeoutException timeout) {
                return lastStatus;
            }
        }
        if (result.code < 200 || result.code >= 300) {
            throw new IOException("VLC Remote polling failed (" + result.code + ")");
        }

        String body = result.body.trim();
        if (body.isEmpty()) {
            return lastStatus;
        }

        JSONArray events = new JSONArray(body);
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event != null) {
                applyAndroidEvent(event);
            }
        }
        return lastStatus;
    }

    private void applyAndroidEvent(JSONObject event) {
        String type = event.optString("type", "");
        if ("now-playing".equals(type)) {
            Status status = new Status();
            status.available = event.optBoolean("shouldShow", true)
                    || event.optLong("id", 0) != 0
                    || event.optLong("duration", 0) > 0
                    || event.optLong("progress", 0) > 0
                    || !event.optString("title", "").trim().isEmpty();
            status.playing = event.optBoolean("playing", false);
            status.state = status.playing ? "playing" : "paused";
            status.timeMs = Math.max(0, event.optLong("progress", 0));
            status.lengthMs = Math.max(0, event.optLong("duration", 0));
            status.title = firstNonEmpty(event.optString("title", ""), event.optString("filename", ""), event.optString("name", ""));
            status.filename = status.title;
            status.receivedAt = System.currentTimeMillis();
            lastStatus = status;
            return;
        }

        if ("player-status".equals(type)) {
            if (lastStatus == null) {
                lastStatus = new Status();
                lastStatus.receivedAt = System.currentTimeMillis();
            }
            lastStatus.available = event.optBoolean("shouldShow", lastStatus.available);
            lastStatus.playing = event.optBoolean("playing", lastStatus.playing);
            lastStatus.state = lastStatus.playing ? "playing" : "paused";
            lastStatus.receivedAt = System.currentTimeMillis();
        }
    }

    private Status getLegacyStatus() throws IOException, JSONException {
        HttpResult result = request("GET", endpoint("requests/status.json"), null, null, READ_TIMEOUT_MS, true);
        if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED || result.code == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new IOException("VLC HTTP password was rejected");
        }
        if (result.code < 200 || result.code >= 300) {
            throw new IOException("VLC HTTP status failed (" + result.code + ")");
        }

        JSONObject json = new JSONObject(result.body);
        Status status = new Status();
        String state = json.optString("state", "stopped");
        status.available = !"stopped".equals(state) || json.optLong("length", 0) > 0;
        status.playing = "playing".equals(state);
        status.state = "stopped".equals(state) ? "idle" : state;
        status.timeMs = Math.max(0, Math.round(json.optDouble("time", 0) * 1000.0));
        status.lengthMs = Math.max(0, Math.round(json.optDouble("length", 0) * 1000.0));
        String filename = findText(json.opt("information"), "filename");
        String title = findText(json.opt("information"), "title");
        status.title = firstNonEmpty(title, filename);
        status.filename = firstNonEmpty(filename, title);
        status.receivedAt = System.currentTimeMillis();
        lastStatus = status;
        return status;
    }

    private void sendAndroidMessage(String message, String id, String floatValue) throws IOException {
        StringBuilder builder = new StringBuilder(endpoint("playback-event"));
        builder.append("?message=").append(urlEncode(message));
        if (id != null) {
            builder.append("&id=").append(urlEncode(id));
        }
        if (floatValue != null) {
            builder.append("&floatValue=").append(urlEncode(floatValue));
        }
        HttpResult result = request("GET", builder.toString(), null, null, READ_TIMEOUT_MS, true);
        if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED && !secret.isEmpty()) {
            authenticateAndroidRemote();
            result = request("GET", builder.toString(), null, null, READ_TIMEOUT_MS, true);
        }
        if (result.code < 200 || result.code >= 300) {
            throw new IOException("VLC command failed (" + result.code + ")");
        }
    }

    private void sendLegacyCommand(String command, String value) throws IOException {
        StringBuilder builder = new StringBuilder(endpoint("requests/status.json"));
        builder.append("?command=").append(urlEncode(command));
        if (value != null) {
            builder.append("&val=").append(urlEncode(value));
        }

        HttpResult result = request("GET", builder.toString(), null, null, READ_TIMEOUT_MS, true);
        if (result.code < 200 || result.code >= 300) {
            throw new IOException("VLC command failed (" + result.code + ")");
        }
    }

    private HttpResult request(String method, String target, String body, String contentType, int readTimeout, boolean authenticated) throws IOException {
        URL url = new URL(target);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) connection;
            https.setSSLSocketFactory(trustAllSocketFactory());
            https.setHostnameVerifier(trustAllHostnameVerifier());
        }

        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(readTimeout);
            connection.setUseCaches(false);
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");

            if (!cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }
            if (authenticated && !androidRemote && !secret.isEmpty()) {
                String credentials = ":" + secret;
                connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(credentials.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            }

            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType != null ? contentType : "application/x-www-form-urlencoded");
                connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }

            int code = connection.getResponseCode();
            mergeCookies(connection.getHeaderFields());
            String response;
            try {
                response = readBody(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            } catch (IOException readError) {
                if (code >= 300 && code < 400) {
                    response = "";
                } else {
                    throw readError;
                }
            }
            return new HttpResult(code, response);
        } finally {
            connection.disconnect();
        }
    }

    private void mergeCookies(Map<String, List<String>> headers) {
        if (headers == null) {
            return;
        }

        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"Set-Cookie".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value == null || value.trim().isEmpty()) {
                    continue;
                }
                String cookiePair = value.split(";", 2)[0].trim();
                if (cookiePair.isEmpty()) {
                    continue;
                }
                cookie = cookie.isEmpty() ? cookiePair : cookie + "; " + cookiePair;
            }
        }
    }

    private String endpoint(String path) {
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        return baseUrl + "/" + cleanPath;
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = safe(value);
        if (normalized.isEmpty()) {
            normalized = "http://127.0.0.1:8080";
        }
        if (!normalized.contains("://")) {
            normalized = "http://" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String readBody(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static String stripJsonString(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String sha256(String value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : hash) {
                builder.append(String.format(Locale.US, "%02x", item));
            }
            return builder.toString();
        } catch (Exception error) {
            throw new IOException("Could not hash VLC OTP", error);
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String findText(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String direct = object.optString(key, "");
            if (!direct.isEmpty()) {
                return direct;
            }
            JSONArray names = object.names();
            if (names == null) {
                return "";
            }
            for (int i = 0; i < names.length(); i++) {
                String found = findText(object.opt(names.optString(i)), key);
                if (!found.isEmpty()) {
                    return found;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findText(array.opt(i), key);
                if (!found.isEmpty()) {
                    return found;
                }
            }
        }
        return "";
    }

    private static SSLSocketFactory trustAllSocketFactory() throws IOException {
        try {
            TrustManager[] trustManagers = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers, new SecureRandom());
            return context.getSocketFactory();
        } catch (Exception error) {
            throw new IOException("Could not prepare VLC HTTPS trust", error);
        }
    }

    private static HostnameVerifier trustAllHostnameVerifier() {
        return (hostname, session) -> true;
    }

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    public static final class Status {
        public boolean available = false;
        public boolean playing = false;
        public String state = "idle";
        public long timeMs = 0;
        public long lengthMs = 0;
        public String title = "";
        public String filename = "";
        public long receivedAt = 0;

        public long projectedTimeMs() {
            if (!playing || receivedAt <= 0) {
                return timeMs;
            }
            return Math.max(0, timeMs + (System.currentTimeMillis() - receivedAt));
        }
    }
}
