package com.wewatch.android.sync;

import org.json.JSONException;
import org.json.JSONObject;

public final class PlaybackStatus {
    public String state = "idle";
    public double time = 0;
    public double length = 0;
    public double position = 0;
    public String filename = "No media";
    public long latency = -1;
    public long updatedAt = System.currentTimeMillis();
    public long serverReceivedAt = 0;

    public static PlaybackStatus idle(long latency) {
        PlaybackStatus status = new PlaybackStatus();
        status.state = "idle";
        status.filename = "No media";
        status.latency = latency;
        status.updatedAt = System.currentTimeMillis();
        return status;
    }

    public static PlaybackStatus fromJson(JSONObject json) {
        PlaybackStatus status = new PlaybackStatus();
        if (json == null) {
            return status;
        }

        status.state = json.optString("state", "idle");
        status.time = json.optDouble("time", 0);
        status.length = json.optDouble("length", 0);
        status.position = json.optDouble("position", 0);
        status.filename = json.optString("filename", "No media");
        status.latency = json.has("latency") && !json.isNull("latency") ? json.optLong("latency", -1) : -1;
        status.updatedAt = json.optLong("updatedAt", System.currentTimeMillis());
        status.serverReceivedAt = json.optLong("serverReceivedAt", 0);
        return status;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("state", state);
        json.put("time", time);
        json.put("length", length);
        json.put("position", position);
        json.put("filename", filename);
        if (latency >= 0) {
            json.put("latency", latency);
        } else {
            json.put("latency", JSONObject.NULL);
        }
        json.put("updatedAt", updatedAt);
        return json;
    }

    public String stamp() {
        if (serverReceivedAt > 0) {
            return String.valueOf(serverReceivedAt);
        }
        return state + ":" + time + ":" + filename + ":" + updatedAt;
    }
}
