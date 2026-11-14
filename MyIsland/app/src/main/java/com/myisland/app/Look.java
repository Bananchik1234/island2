package com.myisland.app;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

public final class Look {

    private Look() {
    }

    static int def(String k) {
        switch (k) {
            case "x":
                return 0;
            case "y":
                return 8;
            case "w":
                return 120;
            case "h":
                return 34;
            case "ew":
                return 330;
            case "eh":
                return 84;
            case "rad":
                return 28;
            case "hue":
                return 0;
            case "sat":
                return 0;
            case "val":
                return 0;
            case "opacity":
                return 70;
            case "speed":
                return 420;
            case "bounce":
                return 25;
            case "dur":
                return 4;
            default:
                return 0;
        }
    }

    static int i(SharedPreferences sp, String k) {
        return sp.getInt(k, def(k));
    }

    static int base(SharedPreferences sp) {
        float[] hsv = new float[]{i(sp, "hue"), i(sp, "sat") / 100f, i(sp, "val") / 100f};
        return Color.HSVToColor(hsv);
    }

    static int mix(int c, float t) {
        return Math.round(c + (255 - c) * t);
    }

    static GradientDrawable background(SharedPreferences sp, float density) {
        int base = base(sp);
        boolean glass = sp.getBoolean("glass", false);
        int alpha = glass ? Math.round(i(sp, "opacity") * 2.55f) : 255;
        int bottom = Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base));
        int top = glass
                ? Color.argb(alpha, mix(Color.red(base), 0.2f), mix(Color.green(base), 0.2f), mix(Color.blue(base), 0.2f))
                : bottom;
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        if (sp.getBoolean("border", false)) {
            d.setStroke(Math.max(1, Math.round(density)), Color.argb(glass ? 80 : 60, 255, 255, 255));
        }
        return d;
    }
}
