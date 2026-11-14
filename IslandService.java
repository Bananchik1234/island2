package com.myisland.app;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class IslandService extends Service {

    public static IslandService instance;

    static final String CHANNEL = "island";
    static final int M_IDLE = 0;
    static final int M_MED = 1;
    static final int M_EXP = 2;

    WindowManager wm;
    WindowManager.LayoutParams lp;
    FrameLayout root;
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
    ValueAnimator anim;
    SharedPreferences sp;
    float density;
    Handler h = new Handler(Looper.getMainLooper());

    int curW;
    int curH;
    int curX;
    float curR;
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
            Intent b = IslandService.this.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
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

    int dp(float v) {
        return Math.round(v * density);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        sp = getSharedPreferences("island", MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startAsForeground();
        icTimer = getDrawable(android.R.drawable.ic_lock_idle_alarm);
        icCharge = getDrawable(android.R.drawable.ic_lock_idle_charging);
        icPhone = getDrawable(android.R.drawable.sym_action_call);
        icMusic = getDrawable(android.R.drawable.ic_media_play);
        icInfo = getDrawable(android.R.drawable.ic_dialog_info);
        if (!buildViews()) {
            stopSelf();
            return;
        }
        registerReceiver(power, new IntentFilter(Intent.ACTION_POWER_CONNECTED));
        setupMedia();
        h.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(power);
        } catch (Exception e) {
            listenerSet = listenerSet;
        }
        for (MediaController c : watched) {
            try {
                c.unregisterCallback(mediaCb);
            } catch (Exception e) {
                listenerSet = listenerSet;
            }
        }
        if (msm != null) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Exception e) {
                listenerSet = listenerSet;
            }
        }
        if (anim != null) {
            anim.cancel();
        }
        if (root != null) {
            try {
                wm.removeView(root);
            } catch (Exception e) {
                root = null;
            }
        }
        root = null;
        instance = null;
        super.onDestroy();
    }

    void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Dynamic Island", NotificationManager.IMPORTANCE_MIN));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Dynamic Island работает")
                .setContentText("Нажмите, чтобы открыть настройки")
                .setContentIntent(pi)
                .build();
        startForeground(1, n);
    }

    TextView label(float sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
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
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(Color.WHITE);
        v.setPadding(dp(7), dp(7), dp(7), dp(7));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        return v;
    }

    boolean buildViews() {
        int iw = dp(sp.getInt("w", 120));
        int ih = dp(sp.getInt("h", 34));
        curW = iw;
        curH = ih;
        curR = ih / 2f;
        curX = dp(sp.getInt("x", 0));

        bg = new GradientDrawable();
        bg.setColor(sp.getInt("color", Color.BLACK));
        bg.setCornerRadius(curR);

        root = new FrameLayout(this);
        root.setBackground(bg);

        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setVisibility(View.INVISIBLE);

        icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });

        textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        title = label(14, Color.WHITE, true);
        sub = label(12, 0xFFB0B0B0, false);
        textCol.addView(title);
        textCol.addView(sub);

        side = label(13, Color.WHITE, true);
        side.setVisibility(View.GONE);

        controls = new LinearLayout(this);
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
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnClickListener(v -> onIslandTap());

        lp = new WindowManager.LayoutParams(
                curW,
                curH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.x = curX;
        lp.y = dp(sp.getInt("y", 8));
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
            return false;
        }
        return true;
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
            msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(this, NotifService.class);
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
            lastArt = new BitmapDrawable(getResources(), b);
        }
        return lastArt;
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
        showEvent(t, s, ic != null ? ic : icInfo, tap, 4500);
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

    public void preview() {
        showEvent("Dynamic Island", "Так выглядит уведомление", icInfo, null, 2500);
    }

    public void applySettings() {
        if (root == null) {
            return;
        }
        bg.setColor(sp.getInt("color", Color.BLACK));
        lastKey = "";
        render();
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
            applyContent(mode, t, s, ic, ctlOn, playing, !sideText.isEmpty());
        }
        if (!sideText.isEmpty()) {
            side.setText(sideText);
        }
    }

    void applyContent(int mode, String t, String s, Drawable ic, boolean ctlOn, boolean playing, boolean hasSide) {
        boolean fade = mode != curMode;
        curMode = mode;
        row.animate().cancel();
        icon.setImageDrawable(ic);
        icon.setVisibility(ic == null ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams ip = (LinearLayout.LayoutParams) icon.getLayoutParams();
        int isz = mode == M_EXP ? dp(44) : dp(22);
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
        int pad = mode == M_EXP ? dp(14) : dp(10);
        row.setPadding(pad, 0, pad, 0);
        row.setVisibility(mode == M_IDLE ? View.INVISIBLE : View.VISIBLE);
        updateSize();
        if (fade && mode != M_IDLE) {
            row.setAlpha(0f);
            row.animate().setStartDelay(150).setDuration(180).alpha(1f).start();
        } else {
            row.setAlpha(1f);
        }
    }

    void updateSize() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int iw = dp(sp.getInt("w", 120));
        int ih = dp(sp.getInt("h", 34));
        int ew = Math.min(dp(sp.getInt("ew", 330)), sw - dp(12));
        int eh = dp(sp.getInt("eh", 84));
        int xo = dp(sp.getInt("x", 0));
        int w;
        int hh;
        float r;
        if (curMode == M_EXP) {
            w = ew;
            hh = eh;
            r = Math.min(eh / 2f, dp(30));
        } else if (curMode == M_MED) {
            w = iw + dp(80);
            hh = ih;
            r = ih / 2f;
        } else {
            w = iw;
            hh = ih;
            r = ih / 2f;
        }
        int maxShift = Math.max(0, (sw - w) / 2);
        int x = Math.max(-maxShift, Math.min(maxShift, xo));
        animateTo(w, hh, r, x);
    }

    void animateTo(int w, int hh, float r, int x) {
        if (anim != null) {
            anim.cancel();
        }
        final int w0 = curW;
        final int h0 = curH;
        final int x0 = curX;
        final float r0 = curR;
        final int w1 = w;
        final int h1 = hh;
        final int x1 = x;
        final float r1 = r;
        if (w0 == w1 && h0 == h1 && x0 == x1 && r0 == r1) {
            applySize(w1, h1, r1, x1);
            return;
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(320);
        if (w1 * h1 >= w0 * h0) {
            anim.setInterpolator(new OvershootInterpolator(0.8f));
        } else {
            anim.setInterpolator(new DecelerateInterpolator());
        }
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            applySize(
                    Math.round(w0 + (w1 - w0) * f),
                    Math.round(h0 + (h1 - h0) * f),
                    r0 + (r1 - r0) * f,
                    Math.round(x0 + (x1 - x0) * f));
        });
        anim.start();
    }

    void applySize(int w, int hh, float r, int x) {
        if (root == null) {
            return;
        }
        curW = w;
        curH = hh;
        curR = r;
        curX = x;
        lp.width = Math.max(w, 1);
        lp.height = Math.max(hh, 1);
        lp.x = x;
        lp.y = dp(sp.getInt("y", 8));
        bg.setCornerRadius(Math.max(r, 0f));
        try {
            wm.updateViewLayout(root, lp);
        } catch (Exception e) {
            curW = w;
        }
    }
}
