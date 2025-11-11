package com.github.catvod.spider;

import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

/**
 * 跟踪 Init 关键步骤状态并缓存到 SharedPreferences
 */
public final class InitStatusTracker {

    private static final String PREF_PREFIX = "init_status_";

    public static final String STEP_PERMISSION = "permission";
    public static final String STEP_DOWNLOAD = "download";
    public static final String STEP_CHAQUO = "chaquo";
    public static final String STEP_SECURE_HTTP = "secure_http";

    private static final String[] ALL_STEPS = {
        STEP_PERMISSION,
        STEP_DOWNLOAD,
        STEP_CHAQUO,
        STEP_SECURE_HTTP
    };

    private InitStatusTracker() {
    }

    public static void reset() {
        for (String step : ALL_STEPS) {
            markPending(step, "");
        }
    }

    public static void markPending(String step, String message) {
        store(step, "pending", message);
    }

    public static void markSuccess(String step, String message) {
        store(step, "success", message);
    }

    public static void markError(String step, String message) {
        store(step, "error", message);
    }

    public static void markSkipped(String step, String message) {
        store(step, "skipped", message);
    }

    public static JSONObject snapshot() {
        JSONObject snapshot = new JSONObject();
        for (String step : ALL_STEPS) {
            String raw = Prefers.getString(prefKey(step), "");
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            try {
                snapshot.put(step, new JSONObject(raw));
            } catch (Exception e) {
                try {
                    JSONObject fallback = new JSONObject();
                    fallback.put("status", "unknown");
                    fallback.put("message", raw);
                    fallback.put("timestamp", System.currentTimeMillis());
                    snapshot.put(step, fallback);
                } catch (Exception ignored) {
                    // ignore storing snapshot failure
                }
            }
        }
        return snapshot;
    }

    private static void store(String step, String status, String message) {
        if (step == null || step.isEmpty()) {
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("status", status);
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("message", message == null ? "" : message);
            Prefers.putString(prefKey(step), payload.toString());
        } catch (Exception ignored) {
            // ignore store failure
        }
    }

    private static String prefKey(String step) {
        return PREF_PREFIX + step;
    }
}
