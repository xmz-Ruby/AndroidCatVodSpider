package com.github.catvod.utils;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import com.github.catvod.spider.Init;

public class Notify {

    private Toast mToast;
    private Handler mHandler;

    private static class Loader {
        static volatile Notify INSTANCE = new Notify();
    }

    private static Notify get() {
        return Loader.INSTANCE;
    }

    public static void show(String text) {
        Init.run(() -> get().makeText(text));
    }

    private void makeText(String message) {
        if (TextUtils.isEmpty(message)) return;
        if (mToast != null) mToast.cancel();
        if (mHandler == null) mHandler = new Handler(Looper.getMainLooper());
        mToast = Toast.makeText(Init.context(), message, Toast.LENGTH_LONG);
        mToast.show();
        mHandler.postDelayed(() -> {
            if (mToast != null) mToast.cancel();
        }, 2000);
    }
}
