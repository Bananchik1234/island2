package com.myisland.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    SharedPreferences sp;
    float density;
    LinearLayout box;
    Button bOverlay;
    Button bNotif;
    Button bToggle;

    int dp(float v) {
        return Math.round(v * density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("island", MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(32), dp(16), dp(40));
        sv.addView(box);
        setContentView(sv);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateState();
    }

    TextView heading(String text, float size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.BLACK);
        t.setPadding(0, dp(18), 0, dp(6));
        box.addView(t);
        return t;
    }

    Button button(String text, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setAllCaps(false);
        bt.setOnClickListener(l);
        box.addView(bt);
        return bt;
    }

    void toggle(String label, final String key) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setChecked(sp.getBoolean(key, true));
        sw.setPadding(0, dp(8), 0, dp(8));
        sw.setOnCheckedChangeListener((v, c) -> sp.edit().putBoolean(key, c).apply());
        box.addView(sw);
    }

    void slider(final String label, final String key, final int min, int max, int def) {
        final TextView tv = new TextView(this);
        int val = sp.getInt(key, def);
        tv.setText(label + ": " + val);
        tv.setPadding(0, dp(10), 0, 0);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(val - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p + min;
                tv.setText(label + ": " + v);
                if (!fromUser) {
                    return;
                }
                sp.edit().putInt(key, v).apply();
                IslandService svc = IslandService.instance;
                if (svc != null) {
                    svc.applySettings();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                tv.setText(tv.getText());
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                IslandService svc = IslandService.instance;
                if (svc != null) {
                    svc.preview();
                }
            }
        });
        box.addView(tv);
        box.addView(sb);
    }

    void build() {
        heading("Dynamic Island", 26);

        bOverlay = button("1. Разрешить показ поверх экрана", v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        bNotif = button("2. Доступ к уведомлениям", v -> startActivity(
                new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        bToggle = button("", v -> {
            boolean en = !sp.getBoolean("enabled", true);
            sp.edit().putBoolean("enabled", en).apply();
            updateState();
        });
        button("Показать тест", v -> {
            IslandService svc = IslandService.instance;
            if (svc != null) {
                svc.preview();
            } else {
                Toast.makeText(this, "Сначала выдайте разрешение 1 и включите остров", Toast.LENGTH_LONG).show();
            }
        });

        heading("Функции", 18);
        toggle("Уведомления", "f_notif");
        toggle("Музыка (обложка, кнопки)", "f_music");
        toggle("Зарядка", "f_charge");
        toggle("Звонки", "f_call");

        heading("Положение и размер (dp)", 18);
        slider("Сдвиг по горизонтали (под камеру)", "x", -250, 250, 0);
        slider("Отступ сверху", "y", 0, 60, 8);
        slider("Ширина капсулы", "w", 40, 220, 120);
        slider("Высота капсулы", "h", 18, 60, 34);
        slider("Ширина раскрытого острова", "ew", 220, 420, 330);
        slider("Высота раскрытого острова", "eh", 56, 140, 84);

        heading("Цвет", 18);
        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        int[] palette = {0xFF000000, 0xFF1C1C1E, 0xFF0A1A3A, 0xFF2A0A0A, 0xFF0A2A12, 0xFF2A1A3A};
        for (final int c : palette) {
            Button cb = new Button(this);
            cb.setBackgroundColor(c);
            cb.setText("");
            LinearLayout.LayoutParams lpb = new LinearLayout.LayoutParams(0, dp(44), 1f);
            lpb.rightMargin = dp(6);
            cb.setLayoutParams(lpb);
            cb.setOnClickListener(v -> {
                sp.edit().putInt("color", c).apply();
                IslandService svc = IslandService.instance;
                if (svc != null) {
                    svc.applySettings();
                    svc.preview();
                }
            });
            colors.addView(cb);
        }
        box.addView(colors);

        heading("Таймер", 18);
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("минуты");
        et.setText("5");
        box.addView(et);
        button("Запустить таймер", v -> {
            int min = 0;
            try {
                min = Integer.parseInt(et.getText().toString().trim());
            } catch (Exception e) {
                min = 0;
            }
            IslandService svc = IslandService.instance;
            if (svc == null) {
                Toast.makeText(this, "Сначала включите остров", Toast.LENGTH_LONG).show();
            } else if (min > 0) {
                svc.startTimer(min * 60);
            }
        });

        TextView hint = new TextView(this);
        hint.setText("На Xiaomi/POCO для стабильной работы: Настройки, Приложения, My Island, "
                + "включите Автозапуск, Экономия заряда: Нет ограничений, и в Других разрешениях "
                + "разрешите Всплывающие окна в фоновом режиме и Показ поверх других приложений.");
        hint.setTextSize(13);
        hint.setPadding(0, dp(20), 0, 0);
        box.addView(hint);
    }

    boolean listenerOn() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    void updateState() {
        boolean ov = Settings.canDrawOverlays(this);
        boolean nl = listenerOn();
        boolean en = sp.getBoolean("enabled", true);
        bOverlay.setText((ov ? "✅ " : "❌ ") + "1. Разрешить показ поверх экрана");
        bNotif.setText((nl ? "✅ " : "❌ ") + "2. Доступ к уведомлениям (музыка, звонки, сообщения)");
        bToggle.setText(en ? "Остров включён (нажмите, чтобы выключить)" : "Остров выключен (нажмите, чтобы включить)");
        Intent svc = new Intent(this, IslandService.class);
        if (en && ov) {
            startForegroundService(svc);
        } else if (!en) {
            stopService(svc);
        }
    }
}
