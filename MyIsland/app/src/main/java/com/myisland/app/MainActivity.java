package com.myisland.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    static final int BG = 0xFF0B0C10;
    static final int CARD = 0xFF16181F;
    static final int CARD2 = 0xFF22252E;
    static final int TEXT = 0xFFF2F3F7;
    static final int MUTED = 0xFF8A8F9C;
    static final int ACCENT = 0xFF8B7BFF;
    static final int GOOD = 0xFF3DDC97;
    static final int BAD = 0xFFFF6B6B;

    SharedPreferences sp;
    float density;
    LinearLayout box;
    TextView chipOverlay;
    TextView chipNotif;
    FrameLayout previewCard;
    View pvIdle;
    LinearLayout pvExp;
    SeekBar sbHue;
    SeekBar sbSat;
    SeekBar sbVal;

    int dp(float v) {
        return Math.round(v * density);
    }

    GradientDrawable rr(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    Drawable ripple(int color, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rr(color, radiusDp), null);
    }

    TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("island", MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(30), dp(16), dp(40));
        sv.addView(box);
        setContentView(sv);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateState();
        refreshPreview();
    }

    LinearLayout.LayoutParams matchWrap(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(10));
        c.setBackground(rr(CARD, 20));
        box.addView(c, matchWrap(14));
        TextView t = text(title.toUpperCase(), 12, MUTED, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(4));
        c.addView(t);
        return c;
    }

    TextView button(LinearLayout parent, String label, boolean filled, View.OnClickListener l) {
        TextView t = text(label, 15, filled ? Color.WHITE : TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        t.setBackground(ripple(filled ? ACCENT : CARD2, 14));
        t.setClickable(true);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        parent.addView(t, p);
        return t;
    }

    void changed() {
        Island i = Island.instance;
        if (i != null) {
            i.applySettings();
        }
        refreshPreview();
    }

    void toggle(LinearLayout c, String label, final String key, boolean def) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(label, 15, TEXT, false);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(sp.getBoolean(key, def));
        sw.setOnCheckedChangeListener((v, on) -> {
            sp.edit().putBoolean(key, on).apply();
            if (key.equals("enabled")) {
                NotifService.refresh();
            }
            changed();
        });
        r.addView(sw);
        r.setPadding(0, dp(8), 0, dp(8));
        c.addView(r);
    }

    SeekBar slider(LinearLayout c, final String label, final String key, final int min, int max, final String unit) {
        int val = Look.i(sp, key);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = text(label, 15, TEXT, false);
        final TextView v = text(val + unit, 15, ACCENT, true);
        head.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(v);
        head.setPadding(0, dp(10), 0, 0);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(val - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int value = p + min;
                v.setText(value + unit);
                if (!fromUser) {
                    return;
                }
                sp.edit().putInt(key, value).apply();
                changed();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                v.setText(v.getText());
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                Island i = Island.instance;
                if (i != null) {
                    i.preview();
                }
            }
        });
        c.addView(head);
        c.addView(sb);
        return sb;
    }

    void setColor(int hue, int sat, int val) {
        sp.edit().putInt("hue", hue).putInt("sat", sat).putInt("val", val).apply();
        sbHue.setProgress(hue);
        sbSat.setProgress(sat);
        sbVal.setProgress(val);
        changed();
        Island i = Island.instance;
        if (i != null) {
            i.preview();
        }
    }

    void swatches(LinearLayout c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(4));
        final int[][] presets = {
                {0, 0, 0}, {240, 10, 14}, {225, 85, 30}, {345, 80, 32},
                {140, 70, 24}, {265, 70, 40}, {185, 80, 34}, {30, 80, 40}
        };
        for (final int[] p : presets) {
            View sw = new View(this);
            sw.setBackground(circle(Color.HSVToColor(new float[]{p[0], p[1] / 100f, p[2] / 100f})));
            sw.setClickable(true);
            sw.setOnClickListener(v -> setColor(p[0], p[1], p[2]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            lp.rightMargin = dp(6);
            row.addView(sw, lp);
        }
        c.addView(row);
    }

    GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(1), 0x55FFFFFF);
        return d;
    }

    Drawable appIcon() {
        try {
            return getPackageManager().getApplicationIcon(getPackageName());
        } catch (Exception e) {
            return null;
        }
    }

    void build() {
        box.addView(text("My Island", 32, TEXT, true));
        TextView subtitle = text("Динамический остров для Android", 14, MUTED, false);
        subtitle.setPadding(0, dp(2), 0, dp(14));
        box.addView(subtitle);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipOverlay = text("", 13, TEXT, true);
        chipNotif = text("", 13, TEXT, true);
        for (TextView t : new TextView[]{chipOverlay, chipNotif}) {
            t.setPadding(dp(14), dp(9), dp(14), dp(9));
            t.setClickable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chips.addView(t, lp);
        }
        chipOverlay.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        chipNotif.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        box.addView(chips);

        previewCard = new FrameLayout(this);
        GradientDrawable wall = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF4A3AA8, 0xFF1D2D6B, 0xFF0E1226});
        wall.setCornerRadius(dp(22));
        previewCard.setBackground(wall);
        box.addView(previewCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(176)));
        ((LinearLayout.LayoutParams) previewCard.getLayoutParams()).topMargin = dp(16);

        pvIdle = new View(this);
        previewCard.addView(pvIdle, new FrameLayout.LayoutParams(dp(100), dp(30), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        pvExp = new LinearLayout(this);
        pvExp.setOrientation(LinearLayout.HORIZONTAL);
        pvExp.setGravity(Gravity.CENTER_VERTICAL);
        pvExp.setPadding(dp(14), 0, dp(14), 0);
        ImageView pi = new ImageView(this);
        pi.setImageDrawable(appIcon());
        pvExp.addView(pi, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout pt = new LinearLayout(this);
        pt.setOrientation(LinearLayout.VERTICAL);
        TextView pt1 = text("My Island", 14, Color.WHITE, true);
        TextView pt2 = text("Так выглядит уведомление", 12, 0xFFB8BBC6, false);
        pt1.setSingleLine(true);
        pt2.setSingleLine(true);
        pt.addView(pt1);
        pt.addView(pt2);
        LinearLayout.LayoutParams ptp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ptp.leftMargin = dp(10);
        pvExp.addView(pt, ptp);
        previewCard.addView(pvExp, new FrameLayout.LayoutParams(dp(300), dp(84), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        LinearLayout main = card("Остров");
        toggle(main, "Остров включён", "enabled", true);
        button(main, "Показать пример", true, v -> {
            Island i = Island.instance;
            if (i != null) {
                i.preview();
            } else {
                Toast.makeText(this, "Выдайте оба разрешения вверху и включите остров", Toast.LENGTH_LONG).show();
            }
        });

        LinearLayout fn = card("Функции");
        toggle(fn, "Уведомления", "f_notif", true);
        toggle(fn, "Музыка: обложка и кнопки", "f_music", true);
        toggle(fn, "Зарядка", "f_charge", true);
        toggle(fn, "Звонки", "f_call", true);
        toggle(fn, "Вибрация при раскрытии", "haptic", true);
        slider(fn, "Время показа уведомления", "dur", 2, 12, " с");

        LinearLayout look = card("Внешний вид");
        toggle(look, "Стекло (полупрозрачность)", "glass", false);
        slider(look, "Прозрачность стекла", "opacity", 20, 100, "%");
        toggle(look, "Светлый контур", "border", false);
        swatches(look);
        sbHue = slider(look, "Оттенок", "hue", 0, 360, "°");
        sbSat = slider(look, "Насыщенность", "sat", 0, 100, "%");
        sbVal = slider(look, "Яркость", "val", 0, 100, "%");
        slider(look, "Скругление раскрытого острова", "rad", 8, 40, " dp");

        LinearLayout anim = card("Анимация");
        slider(anim, "Скорость", "speed", 150, 800, "");
        slider(anim, "Упругость", "bounce", 0, 60, "%");

        LinearLayout geo = card("Положение и размер");
        slider(geo, "Сдвиг по горизонтали (под камеру)", "x", -250, 250, " dp");
        slider(geo, "Отступ сверху", "y", 0, 60, " dp");
        slider(geo, "Ширина капсулы", "w", 40, 220, " dp");
        slider(geo, "Высота капсулы", "h", 18, 60, " dp");
        slider(geo, "Ширина раскрытого", "ew", 220, 420, " dp");
        slider(geo, "Высота раскрытого", "eh", 56, 140, " dp");

        LinearLayout tm = card("Таймер");
        LinearLayout tr = new LinearLayout(this);
        tr.setOrientation(LinearLayout.HORIZONTAL);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText("5");
        et.setTextColor(TEXT);
        et.setHintTextColor(MUTED);
        et.setHint("мин");
        et.setGravity(Gravity.CENTER);
        et.setBackground(rr(CARD2, 12));
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        tr.addView(et, new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView min = text("минут", 15, MUTED, false);
        min.setPadding(dp(10), 0, 0, 0);
        tr.addView(min);
        tr.setPadding(0, dp(8), 0, 0);
        tm.addView(tr);
        button(tm, "Запустить таймер", true, v -> {
            int m = 0;
            try {
                m = Integer.parseInt(et.getText().toString().trim());
            } catch (Exception e) {
                m = 0;
            }
            Island i = Island.instance;
            if (i == null) {
                Toast.makeText(this, "Сначала включите остров", Toast.LENGTH_LONG).show();
            } else if (m > 0) {
                i.startTimer(m * 60);
            }
        });
        button(tm, "Сбросить таймер", false, v -> {
            Island i = Island.instance;
            if (i != null) {
                i.cancelTimer();
            }
        });

        LinearLayout help = card("Если остров пропадает");
        TextView hint = text("На Xiaomi и POCO откройте настройки приложения и включите Автозапуск, "
                + "в разделе Экономия заряда выберите Нет ограничений, а в Других разрешениях "
                + "разрешите Всплывающие окна в фоновом режиме.", 13, MUTED, false);
        hint.setPadding(0, dp(6), 0, dp(4));
        help.addView(hint);
        button(help, "Открыть настройки приложения", false, v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
    }

    void refreshPreview() {
        if (pvIdle == null) {
            return;
        }
        int sw = getResources().getDisplayMetrics().widthPixels;
        int avail = sw - dp(32);
        int iw = Math.min(dp(Look.i(sp, "w")), avail);
        int ih = dp(Look.i(sp, "h"));
        int ew = Math.min(dp(Look.i(sp, "ew")), avail - dp(8));
        int eh = dp(Look.i(sp, "eh"));
        pvIdle.setBackground(Look.background(sp, density));
        ((GradientDrawable) pvIdle.getBackground()).setCornerRadius(ih / 2f);
        FrameLayout.LayoutParams a = (FrameLayout.LayoutParams) pvIdle.getLayoutParams();
        a.width = iw;
        a.height = ih;
        a.topMargin = dp(14);
        pvIdle.setLayoutParams(a);
        pvExp.setBackground(Look.background(sp, density));
        ((GradientDrawable) pvExp.getBackground()).setCornerRadius(Math.min(eh / 2f, dp(Look.i(sp, "rad"))));
        FrameLayout.LayoutParams e = (FrameLayout.LayoutParams) pvExp.getLayoutParams();
        e.width = ew;
        e.height = eh;
        e.topMargin = dp(14) + ih + dp(16);
        pvExp.setLayoutParams(e);
    }

    boolean listenerOn() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    void chip(TextView t, String label, boolean ok) {
        t.setText((ok ? "● " : "○ ") + label);
        t.setTextColor(ok ? GOOD : BAD);
        t.setBackground(rr(ok ? 0x223DDC97 : 0x22FF6B6B, 20));
    }

    void updateState() {
        boolean ov = Settings.canDrawOverlays(this);
        boolean nl = listenerOn();
        chip(chipOverlay, "Поверх экрана", ov);
        chip(chipNotif, "Доступ к уведомлениям", nl);
        if (nl && NotifService.instance == null) {
            try {
                NotificationListenerService.requestRebind(new ComponentName(this, NotifService.class));
            } catch (Exception e) {
                nl = false;
            }
        }
        NotifService.refresh();
    }
}
