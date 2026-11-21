package com.deepseek.chat.local;

import android.app.Activity;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

/** Keeps the process-wide Gecko runtime alive across login screen reopenings. */
final class GeckoRuntimeProvider {
    private static GeckoRuntime runtime;

    private GeckoRuntimeProvider() {}

    static synchronized GeckoRuntime get(Activity activity) {
        if (runtime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .consoleOutput(BuildConfig.DEBUG)
                .remoteDebuggingEnabled(BuildConfig.DEBUG)
                .build();
            runtime = GeckoRuntime.create(activity.getApplicationContext(), settings);
        }
        return runtime;
    }
}
