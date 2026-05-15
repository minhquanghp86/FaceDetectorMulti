package com.facedetectormulti.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.facedetectormulti.service.DetectionService;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    public static final String KEY_AUTO_START = "auto_start_on_boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        boolean isBoot =
                Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
                "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);

        if (!isBoot) return;

        Log.i(TAG, "Boot event received: " + action);

        boolean autoStart = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(KEY_AUTO_START, false);

        if (autoStart) {
            Log.i(TAG, "Auto-start enabled → starting DetectionService");
            try {
                DetectionService.start(context);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start service on boot", e);
            }
        } else {
            Log.i(TAG, "Auto-start disabled, skipping");
        }
    }
}