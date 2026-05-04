package com.wewatch.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class UpdateChecker {
    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/RaoNatu/WeWatch/releases/latest";

    private UpdateChecker() {
    }

    static Release checkLatest(String currentVersion) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_URL).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "WeWatch-Android");

        int responseCode = connection.getResponseCode();
        InputStream stream = responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String body = readAll(stream);
        connection.disconnect();

        if (responseCode == 404) {
            return Release.noPublished(currentVersion);
        }

        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException("GitHub returned " + responseCode);
        }

        JSONObject releaseJson = new JSONObject(body);
        String tag = releaseJson.optString("tag_name", "");
        String latestVersion = normalizeVersion(tag);
        String apkName = "";
        String apkUrl = "";

        JSONArray assets = releaseJson.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }

                String name = asset.optString("name", "");
                String type = asset.optString("content_type", "");
                String lowerName = name.toLowerCase(Locale.US);
                if (lowerName.endsWith(".apk") || "application/vnd.android.package-archive".equals(type)) {
                    apkName = name;
                    apkUrl = asset.optString("browser_download_url", "");
                    break;
                }
            }
        }

        return new Release(
                latestVersion,
                tag,
                releaseJson.optString("html_url", ""),
                releaseJson.optString("body", ""),
                apkName,
                apkUrl,
                isNewerVersion(latestVersion, currentVersion)
        );
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    static boolean isNewerVersion(String latestVersion, String currentVersion) {
        int[] latest = parseVersion(latestVersion);
        int[] current = parseVersion(currentVersion);
        for (int i = 0; i < latest.length; i++) {
            if (latest[i] > current[i]) {
                return true;
            }
            if (latest[i] < current[i]) {
                return false;
            }
        }
        return false;
    }

    static String normalizeVersion(String rawVersion) {
        String version = rawVersion == null ? "" : rawVersion.trim();
        if (version.startsWith("v") || version.startsWith("V")) {
            version = version.substring(1);
        }

        int hyphen = version.indexOf('-');
        if (hyphen >= 0) {
            version = version.substring(0, hyphen);
        }

        int plus = version.indexOf('+');
        if (plus >= 0) {
            version = version.substring(0, plus);
        }

        return version.trim();
    }

    private static int[] parseVersion(String rawVersion) {
        String[] parts = normalizeVersion(rawVersion).split("\\.");
        int[] values = new int[]{0, 0, 0};
        for (int i = 0; i < values.length && i < parts.length; i++) {
            values[i] = leadingNumber(parts[i]);
        }
        return values;
    }

    private static int leadingNumber(String value) {
        if (value == null) {
            return 0;
        }

        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!Character.isDigit(character)) {
                break;
            }
            digits.append(character);
        }

        if (digits.length() == 0) {
            return 0;
        }

        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static final class Release {
        final String version;
        final String tag;
        final String releaseUrl;
        final String notes;
        final String apkName;
        final String apkUrl;
        final boolean newer;
        final boolean noPublishedRelease;

        Release(String version, String tag, String releaseUrl, String notes, String apkName, String apkUrl, boolean newer) {
            this(version, tag, releaseUrl, notes, apkName, apkUrl, newer, false);
        }

        Release(String version, String tag, String releaseUrl, String notes, String apkName, String apkUrl, boolean newer, boolean noPublishedRelease) {
            this.version = version;
            this.tag = tag;
            this.releaseUrl = releaseUrl;
            this.notes = notes;
            this.apkName = apkName;
            this.apkUrl = apkUrl;
            this.newer = newer;
            this.noPublishedRelease = noPublishedRelease;
        }

        static Release noPublished(String currentVersion) {
            return new Release(currentVersion, "", "", "", "", "", false, true);
        }

        boolean hasApk() {
            return apkUrl != null && !apkUrl.trim().isEmpty();
        }
    }
}
