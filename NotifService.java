package com.myisland.app;

import android.app.Notification;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotifService extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        IslandService s = IslandService.instance;
        if (s != null) {
            s.setupMedia();
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        IslandService s = IslandService.instance;
        if (s == null || sbn == null) {
            return;
        }
        Notification n = sbn.getNotification();
        if (n == null) {
            return;
        }
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName())) {
            return;
        }
        boolean isCall = Notification.CATEGORY_CALL.equals(n.category);
        if (!isCall) {
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                return;
            }
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                return;
            }
            if (Notification.CATEGORY_TRANSPORT.equals(n.category)) {
                return;
            }
            if (Notification.CATEGORY_PROGRESS.equals(n.category)) {
                return;
            }
        }
        Bundle ex = n.extras;
        CharSequence t = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence x = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TEXT);
        Drawable icon = null;
        String label = pkg;
        try {
            icon = getPackageManager().getApplicationIcon(pkg);
            label = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            icon = null;
        }
        String title = t == null || t.length() == 0 ? label : t.toString();
        String text = x == null ? "" : x.toString();
        if (isCall) {
            s.setCall(sbn.getKey(), title, text, icon, n.contentIntent);
        } else {
            s.onNotification(title, text, icon, n.contentIntent);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        IslandService s = IslandService.instance;
        if (s != null && sbn != null) {
            s.clearCall(sbn.getKey());
        }
    }
}
