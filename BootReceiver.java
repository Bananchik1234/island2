package com.myisland.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        SharedPreferences sp = c.getSharedPreferences("island", Context.MODE_PRIVATE);
        if (sp.getBoolean("enabled", true) && Settings.canDrawOverlays(c)) {
            c.startForegroundService(new Intent(c, IslandService.class));
        }
    }
}
