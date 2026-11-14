package com.myisland.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Island {

    public static Island instance;

    static final int M_IDLE = 0;
    static final int M_MED = 1;
    static final int M_EXP = 2;

    final Context ctx;
    final SharedPreferences sp;
    final float density;
    final WindowManager wm;
    final Handler h = new Handler(Looper.getMainLooper());

    WindowManager.LayoutParams lp;
    FrameLayout root;
    FrameLayout pill;
    LinearLayout row;
    LinearLayout textCol;
    LinearLayout controls;
    ImageView icon;
    ImageView prev;
    ImageView play;
    ImageView next;
    TextView title;
    TextView sub;
    TextView side;
    GradientDrawable bg;

    float[] pos = new float[4];
    float[] vel = new float[4];
    float[] tgt = new float[4];
    int winLeft;
    int topPad;
    boolean running;
    long lastNanos;
    int gen;

    int curMode = -1;
    String lastKey = "";
    PendingIntent curTap;

    String evTitle;
    String evText;
    Drawable evIcon;
    PendingIntent evTap;
    long evUntil;

    boolean callActive;
    String callKey;
    String callTitle;
    String callText;
    Drawable callIcon;
    PendingIntent callTap;
    long callUntil;

    long timerEnd;
    long expandUntil;

    MediaSessionManager msm;
    MediaController mc;
    List<MediaController> watched = new ArrayList<>();
    boolean listenerSet;
    boolean receiverSet;
    int tickCount;

    Drawable icTimer;
    Drawable icCharge;
    Drawable icPhone;
    Drawable icMusic;
    Drawable icInfo;
    Bitmap lastBmp;
    Drawable lastArt;

    final Runnable renderRun = new Runnable() {
        @Override
        public void run() {
            render();
        }
    };

    final Runnable tick = new Runnable() {
        @Override
        public void run() {
            tickCount++;
            if (!listenerSet && tickCount % 5 == 0) {
                setupMedia();
            }
            render();
            h.postDelayed(this, 1000);
        }
    };

    final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            if (!running || root == null) {
                running = false;
                return;
            }
            float dt = lastNanos == 0 ? 1f / 60f : Math.min((nanos - lastNanos) / 1e9f, 0.032f);
            lastNanos = nanos;
            boolean moving = step(dt);
            layoutPill();
            if (moving) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                running = false;
                lastNanos = 0;
                settle();
            }
        }
    };

    final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    watchControllers(controllers);
                }
            };

    final MediaController.Callback mediaCb = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            pickController();
            render();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            render();
        }
    };

    final BroadcastReceiver power = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!Intent.ACTION_POWER_CONNECTED.equals(i.getAction())) {
                return;
            }
            if (!sp.getBoolean("f_charge", true)) {
                return;
            }
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int pct = -1;
            if (b != null) {
                int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (lvl >= 0 && sc > 0) {
                    pct = lvl * 100 / sc;
                }
            }
            showEvent("Зарядка", pct >= 0 ? pct + "%" : "", icCharge, null, 3500);
        }
    };

    public Island(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("island", Context.MODE_PRIVATE);
        density = c.getResources().getDisplayMetrics().density;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    public boolean start() {
        icTimer = ctx.getDrawable(android.R.drawable.ic_lock_idle_alarm);
        icCharge = ctx.getDrawable(android.R.drawable.ic_lock_idle_charging);
        icPhone = ctx.getDrawable(android.R.drawable.sym_action_call);
        icMusic = ctx.getDrawable(android.R.drawable.ic_media_play);
        try {
            icInfo = ctx.getPackageManager().getApplicationIcon(ctx.getPackageName());
        } catch (Exception e) {
            icInfo = ctx.getDrawable(android.R.drawable.ic_dialog_info);
        }
        buildViews();
        computeTarget();
        for (int i = 0; i < 4; i++) {
            pos[i] = tgt[i];
            vel[i] = 0f;
        }
        topPad = 0;
        winLeft = Math.round(pos[2] - pos[0] / 2f);
        lp = new WindowManager.LayoutParams(
                Math.round(pos[0]),
                Math.round(pos[1]),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.x = winLeft + lp.width / 2;
        lp.y = dp(Look.i(sp, "y"));
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
            return false;
        }
        layoutPill();
        instance = this;
        ctx.registerReceiver(power, new IntentFilter(Intent.ACTION_POWER_CONNECTED));
        receiverSet = true;
        setupMedia();
        h.post(tick);
        return true;
    }

    public void stop() {
        h.removeCallbacksAndMessages(null);
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        if (receiverSet) {
            try {
                ctx.unregisterReceiver(power);
            } catch (Exception e) {
                receiverSet = false;
            }
            receiverSet = false;
        }
        for (MediaController c : watched) {
            try {
                c.unregisterCallback(mediaCb);
            } catch (Exception e) {
                listenerSet = false;
            }
        }
        watched.clear();
        if (msm != null && listenerSet) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Exception e) {
                listenerSet = false;
            }
        }
        listenerSet = false;
        if (root != null) {
            try {
                wm.removeView(root);
            } catch (Exception e) {
                root = null;
            }
        }
        root = null;
        if (instance == this) {
            instance = null;
        }
    }

    TextView label(float sizeSp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    ImageView ctl(int res) {
        ImageView v = new ImageView(ctx);
        v.setImageResource(res);
        v.setColorFilter(Color.WHITE);
        v.setPadding(dp(7), dp(7), dp(7), dp(7));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        return v;
    }

    void buildViews() {
        bg = Look.background(sp, density);
        root = new FrameLayout(ctx);
        pill = new FrameLayout(ctx);
        pill.setBackground(bg);
        pill.setClipToOutline(true);
        pill.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Math.max(0f, pos[3]));
            }
        });

        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setVisibility(View.INVISIBLE);

        icon = new ImageView(ctx);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getWidth() * 0.26f);
            }
        });

        textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        title = label(14, Color.WHITE, true);
        sub = label(12, 0xFFB8BBC6, false);
        textCol.addView(title);
        textCol.addView(sub);

        side = label(13, Color.WHITE, true);
        side.setVisibility(View.GONE);

        controls = new LinearLayout(ctx);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setVisibility(View.GONE);
        prev = ctl(android.R.drawable.ic_media_previous);
        play = ctl(android.R.drawable.ic_media_play);
        next = ctl(android.R.drawable.ic_media_next);
        controls.addView(prev);
        controls.addView(play);
        controls.addView(next);

        prev.setOnClickListener(v -> {
            if (mc != null) {
                mc.getTransportControls().skipToPrevious();
            }
            bump();
        });
        next.setOnClickListener(v -> {
            if (mc != null) {
                mc.getTransportControls().skipToNext();
            }
            bump();
        });
        play.setOnClickListener(v -> {
            if (mc != null) {
                if (musicPlaying()) {
                    mc.getTransportControls().pause();
                } else {
                    mc.getTransportControls().play();
                }
            }
            bump();
        });

        row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(10);
        tp.rightMargin = dp(6);
        row.addView(textCol, tp);
        row.addView(side, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pill.addView(row, new FrameLayout.LayoutParams(dp(100), dp(34), Gravity.CENTER));
        root.addView(pill, new FrameLayout.LayoutParams(dp(100), dp(34), Gravity.TOP | Gravity.LEFT));
        pill.setOnClickListener(v -> onIslandTap());
    }

    void computeTarget() {
        int sw = ctx.getResources().getDisplayMetrics().widthPixels;
        int iw = dp(Look.i(sp, "w"));
        int ih = dp(Look.i(sp, "h"));
        int ew = Math.min(dp(Look.i(sp, "ew")), sw - dp(12));
        int eh = dp(Look.i(sp, "eh"));
        float w;
        float hh;
        float r;
        if (curMode == M_EXP) {
            w = ew;
            hh = eh;
            r = Math.min(eh / 2f, dp(Look.i(sp, "rad")));
        } else if (curMode == M_MED) {
            w = iw + dp(80);
            hh = ih;
            r = ih / 2f;
        } else {
            w = iw;
            hh = ih;
            r = ih / 2f;
        }
        int maxShift = Math.max(0, (int) ((sw - w) / 2));
        float cx = Math.max(-maxShift, Math.min(maxShift, dp(Look.i(sp, "x"))));
        tgt[0] = w;
        tgt[1] = hh;
        tgt[2] = cx;
        tgt[3] = r;
    }

    void applyWindow(int left, int width, int height, int y) {
        if (root == null) {
            return;
        }
        lp.width = Math.max(1, width);
        lp.height = Math.max(1, height);
        lp.x = left + lp.width / 2;
        lp.y = y;
        try {
            wm.updateViewLayout(root, lp);
        } catch (Exception e) {
            running = false;
        }
    }

    void layoutPill() {
        if (root == null) {
            return;
        }
        FrameLayout.LayoutParams pp = (FrameLayout.LayoutParams) pill.getLayoutParams();
        pp.width = Math.max(dp(6), Math.round(pos[0]));
        pp.height = Math.max(dp(6), Math.round(pos[1]));
        pp.leftMargin = Math.round(pos[2] - pos[0] / 2f) - winLeft;
        pp.topMargin = topPad;
        pill.setLayoutParams(pp);
        bg.setCornerRadius(Math.max(0f, pos[3]));
        pill.invalidateOutline();
    }

    void retarget() {
        if (root == null) {
            return;
        }
        computeTarget();
        boolean still = true;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(pos[i] - tgt[i]) > 0.5f || Math.abs(vel[i]) > 8f) {
                still = false;
            }
        }
        if (still && !running) {
            for (int i = 0; i < 4; i++) {
                pos[i] = tgt[i];
                vel[i] = 0f;
            }
            settle();
            return;
        }
        int pad = dp(18);
        float l = Math.min(pos[2] - pos[0] / 2f, tgt[2] - tgt[0] / 2f) - pad;
        float r = Math.max(pos[2] + pos[0] / 2f, tgt[2] + tgt[0] / 2f) + pad;
        float hh = Math.max(pos[1], tgt[1]) + 2 * pad;
        topPad = pad;
        winLeft = Math.round(l);
        applyWindow(winLeft, Math.round(r) - winLeft, Math.round(hh), dp(Look.i(sp, "y")) - pad);
        layoutPill();
        if (!running) {
            running = true;
            lastNanos = 0;
            Choreographer.getInstance().postFrameCallback(frame);
        }
    }

    void settle() {
        if (root == null) {
            return;
        }
        topPad = 0;
        winLeft = Math.round(pos[2] - pos[0] / 2f);
        applyWindow(winLeft, Math.round(pos[0]), Math.round(pos[1]), dp(Look.i(sp, "y")));
        layoutPill();
    }

    boolean step(float dt) {
        float k = Look.i(sp, "speed");
        float zeta = 1f - Look.i(sp, "bounce") / 100f;
        float c = 2f * zeta * (float) Math.sqrt(k);
        int sub = 2;
        float d = dt / sub;
        for (int s = 0; s < sub; s++) {
            for (int i = 0; i < 4; i++) {
                float a = -k * (pos[i] - tgt[i]) - c * vel[i];
                vel[i] += a * d;
                pos[i] += vel[i] * d;
            }
        }
        boolean moving = false;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(pos[i] - tgt[i]) > 0.4f || Math.abs(vel[i]) > 10f) {
                moving = true;
            }
        }
        if (!moving) {
            for (int i = 0; i < 4; i++) {
                pos[i] = tgt[i];
                vel[i] = 0f;
            }
        }
        return moving;
    }

    void bump() {
        expandUntil = SystemClock.elapsedRealtime() + 7000;
        render();
    }

    void onIslandTap() {
        long now = SystemClock.elapsedRealtime();
        if (curMode == M_EXP) {
            if (curTap != null) {
                try {
                    curTap.send();
                } catch (Exception e) {
                    curTap = null;
                }
            }
            if (callActive) {
                return;
            }
            evUntil = 0;
            expandUntil = 0;
        } else if (hasMusic()) {
            expandUntil = now + 7000;
        }
        render();
    }

    public void setupMedia() {
        if (listenerSet) {
            return;
        }
        try {
            msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(ctx, NotifService.class);
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn);
            listenerSet = true;
            watchControllers(msm.getActiveSessions(cn));
        } catch (SecurityException e) {
            listenerSet = false;
        }
    }

    void watchControllers(List<MediaController> list) {
        for (MediaController c : watched) {
            try {
                c.unregisterCallback(mediaCb);
            } catch (Exception e) {
                listenerSet = listenerSet;
            }
        }
        watched.clear();
        if (list != null) {
            for (MediaController c : list) {
                try {
                    c.registerCallback(mediaCb);
                } catch (Exception e) {
                    listenerSet = listenerSet;
                }
                watched.add(c);
            }
        }
        pickController();
        render();
    }

    void pickController() {
        MediaController best = null;
        for (MediaController c : watched) {
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                best = c;
                break;
            }
            if (best == null && c.getMetadata() != null) {
                best = c;
            }
        }
        mc = best;
    }

    String musicTitle() {
        if (mc == null) {
            return null;
        }
        MediaMetadata md = mc.getMetadata();
        if (md == null) {
            return null;
        }
        String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(t)) {
            t = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        }
        return TextUtils.isEmpty(t) ? null : t;
    }

    String musicArtist() {
        if (mc == null) {
            return "";
        }
        MediaMetadata md = mc.getMetadata();
        if (md == null) {
            return "";
        }
        String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(a)) {
            a = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        }
        if (TextUtils.isEmpty(a)) {
            a = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        }
        return a == null ? "" : a;
    }

    boolean hasMusic() {
        return musicTitle() != null;
    }

    boolean musicPlaying() {
        if (mc == null) {
            return false;
        }
        PlaybackState st = mc.getPlaybackState();
        return st != null && st.getState() == PlaybackState.STATE_PLAYING;
    }

    Drawable musicArt() {
        MediaMetadata md = mc == null ? null : mc.getMetadata();
        if (md == null) {
            return icMusic;
        }
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) {
            b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }
        if (b == null) {
            b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        if (b == null) {
            return icMusic;
        }
        if (b != lastBmp) {
            lastBmp = b;
            lastArt = new BitmapDrawable(ctx.getResources(), b);
        }
        return lastArt;
    }

    long durMs() {
        return Look.i(sp, "dur") * 1000L;
    }

    public void showEvent(String t, String s, Drawable ic, PendingIntent tap, long ms) {
        evTitle = t;
        evText = s;
        evIcon = ic;
        evTap = tap;
        evUntil = SystemClock.elapsedRealtime() + ms;
        render();
        h.postDelayed(renderRun, ms + 80);
    }

    public void onNotification(String t, String s, Drawable ic, PendingIntent tap) {
        if (!sp.getBoolean("f_notif", true)) {
            return;
        }
        showEvent(t, s, ic != null ? ic : icInfo, tap, durMs());
    }

    public void setCall(String key, String t, String s, Drawable ic, PendingIntent tap) {
        if (!sp.getBoolean("f_call", true)) {
            return;
        }
        boolean isNew = !callActive || !key.equals(callKey);
        callActive = true;
        callKey = key;
        callTitle = t;
        callText = s;
        callIcon = ic != null ? ic : icPhone;
        callTap = tap;
        if (isNew) {
            callUntil = SystemClock.elapsedRealtime() + 30000;
        }
        render();
    }

    public void clearCall(String key) {
        if (callActive && key != null && key.equals(callKey)) {
            callActive = false;
            render();
        }
    }

    public void startTimer(int seconds) {
        timerEnd = SystemClock.elapsedRealtime() + seconds * 1000L;
        lastKey = "";
        render();
    }

    public void cancelTimer() {
        timerEnd = 0;
        lastKey = "";
        render();
    }

    public void preview() {
        showEvent("Dynamic Island", "Так выглядит уведомление", icInfo, null, Math.max(2500L, durMs() / 2));
    }

    public void applySettings() {
        if (root == null) {
            return;
        }
        bg = Look.background(sp, density);
        pill.setBackground(bg);
        lastKey = "";
        render();
        layoutPill();
    }

    void render() {
        if (root == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (timerEnd != 0 && now >= timerEnd) {
            timerEnd = 0;
            evTitle = "Таймер";
            evText = "Время вышло";
            evIcon = icTimer;
            evTap = null;
            evUntil = now + 6000;
        }
        if (callActive && now >= callUntil) {
            callActive = false;
        }
        int mode;
        String t = "";
        String s = "";
        String sideText = "";
        Drawable ic = null;
        boolean ctlOn = false;
        boolean playing = false;
        PendingIntent tap = null;
        boolean music = sp.getBoolean("f_music", true) && hasMusic();
        if (callActive) {
            mode = M_EXP;
            ic = callIcon;
            t = callTitle;
            s = callText;
            tap = callTap;
        } else if (now < evUntil) {
            mode = M_EXP;
            ic = evIcon;
            t = evTitle;
            s = evText;
            tap = evTap;
        } else if (music && now < expandUntil) {
            mode = M_EXP;
            ic = musicArt();
            t = musicTitle();
            s = musicArtist();
            ctlOn = true;
            playing = musicPlaying();
        } else if (timerEnd != 0) {
            mode = M_MED;
            ic = icTimer;
            long left = (timerEnd - now + 999) / 1000;
            sideText = String.format(Locale.US, "%d:%02d", left / 60, left % 60);
        } else if (music && musicPlaying()) {
            mode = M_MED;
            ic = musicArt();
        } else {
            mode = M_IDLE;
        }
        curTap = tap;
        String key = mode + "|" + t + "|" + s + "|" + ctlOn + "|" + playing + "|"
                + System.identityHashCode(ic) + "|" + (sideText.isEmpty() ? 0 : 1);
        if (!key.equals(lastKey)) {
            lastKey = key;
            transition(mode, t, s, ic, ctlOn, playing, !sideText.isEmpty());
        }
        if (!sideText.isEmpty()) {
            side.setText(sideText);
        }
    }

    void transition(final int mode, final String t, final String s, final Drawable ic,
                    final boolean ctlOn, final boolean playing, final boolean hasSide) {
        int prevMode = curMode;
        boolean modeChanged = mode != prevMode;
        curMode = mode;
        gen++;
        final int g = gen;
        retarget();
        if (mode == M_EXP && prevMode != M_EXP && sp.getBoolean("haptic", true)) {
            pill.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        if (!modeChanged) {
            row.animate().cancel();
            row.setAlpha(1f);
            row.setScaleX(1f);
            row.setScaleY(1f);
            applyContent(mode, t, s, ic, ctlOn, playing, hasSide);
            return;
        }
        if (prevMode == -1 || prevMode == M_IDLE) {
            applyContent(mode, t, s, ic, ctlOn, playing, hasSide);
            if (mode != M_IDLE) {
                fadeIn(110);
            }
            return;
        }
        row.animate().cancel();
        row.animate().alpha(0f).setDuration(90).withEndAction(() -> {
            if (g != gen) {
                return;
            }
            applyContent(mode, t, s, ic, ctlOn, playing, hasSide);
            if (mode != M_IDLE) {
                fadeIn(30);
            }
        }).start();
    }

    void fadeIn(long delay) {
        row.animate().cancel();
        row.setAlpha(0f);
        row.setScaleX(0.9f);
        row.setScaleY(0.9f);
        row.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(260)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    void applyContent(int mode, String t, String s, Drawable ic, boolean ctlOn, boolean playing, boolean hasSide) {
        FrameLayout.LayoutParams rp = (FrameLayout.LayoutParams) row.getLayoutParams();
        rp.width = Math.round(tgt[0]);
        rp.height = Math.round(tgt[1]);
        row.setLayoutParams(rp);
        icon.setImageDrawable(ic);
        icon.setVisibility(ic == null ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams ip = (LinearLayout.LayoutParams) icon.getLayoutParams();
        int isz = mode == M_EXP ? dp(46) : dp(22);
        ip.width = isz;
        ip.height = isz;
        icon.setLayoutParams(ip);
        title.setText(t);
        sub.setText(s);
        sub.setVisibility(TextUtils.isEmpty(s) ? View.GONE : View.VISIBLE);
        textCol.setVisibility(mode == M_EXP ? View.VISIBLE : View.INVISIBLE);
        side.setVisibility(hasSide ? View.VISIBLE : View.GONE);
        controls.setVisibility(ctlOn ? View.VISIBLE : View.GONE);
        play.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        int padding = mode == M_EXP ? dp(14) : dp(12);
        row.setPadding(padding, 0, padding, 0);
        row.setVisibility(mode == M_IDLE ? View.INVISIBLE : View.VISIBLE);
    }
}
