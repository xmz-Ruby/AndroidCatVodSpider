package com.github.catvod.utils;

import static android.content.Context.MODE_PRIVATE;

import android.content.SharedPreferences;

import com.github.catvod.spider.Init;

public class Prefers {

    private static SharedPreferences getPrefers() {
        return Init.context().getSharedPreferences(Init.context().getPackageName() + "_preferences", MODE_PRIVATE);
    }

    public static String getString(String key, String defaultValue) {
        return getPrefers().getString(key, defaultValue);
    }

    public static String getString(String key) {
        return getString(key, "");
    }

    public static int getInt(String key, int defaultValue) {
        return getPrefers().getInt(key, defaultValue);
    }

    public static int getInt(String key) {
        return getInt(key, 0);
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        return getPrefers().getBoolean(key, defaultValue);
    }

    public static boolean getBoolean(String key) {
        return getPrefers().getBoolean(key, false);
    }

    public static void put(String key, Object obj) {
        if (obj == null) return;
        if (obj instanceof String) {
            getPrefers().edit().putString(key, (String) obj).apply();
        } else if (obj instanceof Boolean) {
            getPrefers().edit().putBoolean(key, (Boolean) obj).apply();
        } else if (obj instanceof Float) {
            getPrefers().edit().putFloat(key, (Float) obj).apply();
        } else if (obj instanceof Integer) {
            getPrefers().edit().putInt(key, (Integer) obj).apply();
        } else if (obj instanceof Long) {
            getPrefers().edit().putLong(key, (Long) obj).apply();
        }
    }

    public static long getLong(String key, long defaultValue) {
        return getPrefers().getLong(key, defaultValue);
    }

    public static long getLong(String key) {
        return getLong(key, 0L);
    }

    public static void putString(String key, String value) {
        getPrefers().edit().putString(key, value).apply();
    }

    public static void putLong(String key, long value) {
        getPrefers().edit().putLong(key, value).apply();
    }

    public static void remove(String key) {
        getPrefers().edit().remove(key).apply();
    }
}
