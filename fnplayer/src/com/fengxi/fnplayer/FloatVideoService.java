package com.fengxi.fnplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.fengxi.fnplayer.data.Config;
import com.fengxi.fnplayer.player.IjkHolder;
import com.fengxi.fnplayer.player.PlaySession;

import java.util.Map;

/**
 * v2.22 悬浮小窗播放（仿"D+悬浮版"）：
 * <ul>
 *   <li>方案 A（主路径）：同进程接管 PlaySession.holder，Surface 就绪后
 *       setSurface 换绑，解码管线不重建 → 零黑屏；</li>
 *   <li>方案 B（兜底）：holder 不可用/8 秒无画面时，用记录的 playUrl
 *       重建播放器并 seekTo 移交进度（黑屏 1~2s 可接受）；</li>
 *   <li>手势：单击暂停/继续、双击回全屏（resumePos 续播）、拖动 + 左右边缘吸附；</li>
 *   <li>任何窗口/播放异常都 stopSelf 清理，绝不崩进程。</li>
 * </ul>
 */
public class FloatVideoService extends Service implements IjkHolder.Listener {

    public static final String ACTION_SHOW = "com.fengxi.fnplayer.FLOAT_SHOW";
    public static final String ACTION_STOP = "com.fengxi.fnplayer.FLOAT_STOP";
    private static final String TAG = "FxFloat";
    private static final String CH_ID = "fx_float";
    private static final int FG_ID = 2201;
    private static final long WATCHDOG_MS = 8000;

    private WindowManager wm;
    private FrameLayout floatView;
    private WindowManager.LayoutParams lp;
    private TextureView tex;
    private TextView pauseIcon;

    /** 方案 A 接管的播放器（Activity 移交，本 Service 负责释放）。 */
    private IjkHolder sessionHolder;
    /** 方案 B 自建播放器。 */
    private IjkHolder fallbackPlayer;
    private boolean pendingSeek = false;
    private boolean paused = false;
    private long lastPos = 0;
    private final Handler ui = new Handler();

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            IjkHolder h = sessionHolder != null ? sessionHolder : fallbackPlayer;
            if (h == null || paused) return;
            long pos = h.curMs();
            boolean stalled = pos == lastPos && !h.isPlaying();
            lastPos = pos;
            if (stalled) {
                Log.w(TAG, "watchdog: no picture for " + WATCHDOG_MS + "ms, failover");
                failover();
            } else {
                ui.postDelayed(this, WATCHDOG_MS);
            }
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        Config.init(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        showFloat();
        return START_NOT_STICKY;
    }

    // ==================== 窗口 ====================

    private void showFloat() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission missing, abort");
            stopSelf();
            return;
        }
        if (floatView != null) return;   // 已在显示
        startForegroundSafely();
        try {
            buildView();
            wm.addView(floatView, lp);
        } catch (Throwable t) {
            Log.e(TAG, "addView fail", t);
            cleanup();
            stopSelf();
            return;
        }
        // watchdog：移交后 8 秒无画面 → 兜底重建
        lastPos = 0;
        ui.postDelayed(watchdog, WATCHDOG_MS);
        notifyState(true);
    }

    private void buildView() {
        boolean portrait = PlaySession.portrait;
        int w = portrait ? dp(170) : dp(240);
        int h = portrait ? dp(304) : dp(135);

        floatView = new FrameLayout(this);
        floatView.setBackgroundColor(0xFF000000);

        tex = new TextureView(this);
        tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int vw, int vh) {
                attachPlayer(new Surface(st));
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int vw, int vh) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        floatView.addView(tex, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 中央暂停图标
        pauseIcon = new TextView(this);
        pauseIcon.setText("▶");
        pauseIcon.setTextColor(0xFFFFFFFF);
        pauseIcon.setTextSize(34);
        pauseIcon.setGravity(Gravity.CENTER);
        pauseIcon.setVisibility(View.GONE);
        floatView.addView(pauseIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // 右上 ✕
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(0xFFFFFFFF);
        close.setTextSize(15);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(10), dp(10), dp(10), dp(10));
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopSelf(); }
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        floatView.addView(close, clp);

        // 手势：单击暂停/继续，双击回全屏，拖动 + 吸附
        final GestureDetector gd = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                    @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                        togglePlay();
                        return true;
                    }
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        backFullscreen();
                        return true;
                    }
                });
        final int[] down = new int[2];
        final int[] origin = new int[2];
        tex.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                gd.onTouchEvent(e);
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        down[0] = (int) e.getRawX();
                        down[1] = (int) e.getRawY();
                        origin[0] = lp.x;
                        origin[1] = lp.y;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = origin[0] + ((int) e.getRawX() - down[0]);
                        lp.y = origin[1] + ((int) e.getRawY() - down[1]);
                        try { wm.updateViewLayout(floatView, lp); } catch (Throwable ignored) {}
                        break;
                    case MotionEvent.ACTION_UP:
                        snapToEdge();
                        break;
                    default:
                        break;
                }
                return true;
            }
        });

        lp = new WindowManager.LayoutParams(w, h,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int fx = Config.floatX(), fy = Config.floatY();
        lp.x = fx >= 0 ? fx : dp(24);
        lp.y = fy >= 0 ? fy : dp(120);
    }

    // ==================== 播放器移交 ====================

    private void attachPlayer(Surface surface) {
        try {
            if (PlaySession.active()) {
                // 方案 A：换绑 Surface，解码管线不重建 → 零黑屏
                sessionHolder = PlaySession.holder;
                sessionHolder.setListener(this);
                sessionHolder.keepScreenOn(false);
                sessionHolder.setSurface(surface);
                sessionHolder.play();
                Log.d(TAG, "transfer ok, pos=" + PlaySession.posMs);
                paused = false;
                pauseIcon.setVisibility(View.GONE);
                return;
            }
            failoverTo(surface);
        } catch (Throwable t) {
            Log.e(TAG, "attachPlayer fail", t);
            try { failoverTo(surface); } catch (Throwable ignored) {}
        }
    }

    /** 方案 B：用移交时记录的播放地址重建播放器并续播。 */
    private void failoverTo(Surface surface) {
        String url = PlaySession.playUrl;
        if (url == null || url.length() == 0) {
            Log.w(TAG, "failover: no playUrl, give up");
            stopSelf();
            return;
        }
        Map<String, String> hs = PlaySession.headers;
        if (fallbackPlayer != null) { fallbackPlayer.release(); fallbackPlayer = null; }
        fallbackPlayer = new IjkHolder();
        fallbackPlayer.init(this, surface);
        fallbackPlayer.setListener(this);
        fallbackPlayer.keepScreenOn(false);
        pendingSeek = PlaySession.posMs > 0;
        try {
            fallbackPlayer.setDataSource(url, hs == null
                    ? new java.util.HashMap<String, String>() : hs);
        } catch (Throwable t) {
            Log.e(TAG, "fallback datasource fail", t);
            stopSelf();
        }
        Log.d(TAG, "failover started, seekTo=" + PlaySession.posMs);
    }

    /** watchdog 判定移交失败 → 释放移交播放器，转方案 B。 */
    private void failover() {
        ui.removeCallbacks(watchdog);
        if (sessionHolder != null) {
            try { sessionHolder.release(); } catch (Throwable ignored) {}
            sessionHolder = null;
        }
        if (tex != null && tex.isAvailable()) {
            try { failoverTo(new Surface(tex.getSurfaceTexture())); } catch (Throwable t) {
                Log.e(TAG, "failover fail", t);
                stopSelf();
            }
        }
    }

    // ==================== 手势动作 ====================

    private void togglePlay() {
        IjkHolder h = sessionHolder != null ? sessionHolder : fallbackPlayer;
        if (h == null) return;
        paused = !paused;
        if (paused) h.pause(); else h.play();
        if (pauseIcon != null) pauseIcon.setVisibility(paused ? View.VISIBLE : View.GONE);
    }

    /** 双击回全屏：Activity 重启 + resumePos 续播（浮窗侧释放播放器）。 */
    private void backFullscreen() {
        Intent i;
        long pos = 0;
        try {
            IjkHolder h = sessionHolder != null ? sessionHolder : fallbackPlayer;
            if (h != null) pos = h.curMs();
        } catch (Throwable ignored) {}
        if ("short".equals(PlaySession.backActivity)) {
            i = new Intent(this, ShortActivity.class);
            i.putExtra("guid", PlaySession.guid);
            i.putExtra("title", PlaySession.title);
            i.putExtra("resumeEp", PlaySession.epIdx);
        } else {
            i = new Intent(this, VideoActivity.class);
            i.putExtra("guid", PlaySession.guid);
            i.putExtra("title", PlaySession.title);
            if (PlaySession.item.length() > 0) i.putExtra("item", PlaySession.item);
            if (PlaySession.live) i.putExtra("live", "1");
        }
        i.putExtra("resumePos", pos > 0 ? pos : PlaySession.posMs);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(i); } catch (Throwable t) {
            Log.e(TAG, "back fullscreen fail", t);
        }
        stopSelf();
    }

    /** ACTION_UP：吸附最近左右边缘并持久化位置。 */
    private void snapToEdge() {
        try {
            int sw = getResources().getDisplayMetrics().widthPixels;
            int cx = lp.x + floatView.getWidth() / 2;
            int margin = dp(12);
            lp.x = cx < sw / 2 ? margin : sw - floatView.getWidth() - margin;
            if (lp.y < 0) lp.y = 0;
            wm.updateViewLayout(floatView, lp);
            Config.setFloatPos(lp.x, lp.y);
        } catch (Throwable ignored) {}
    }

    // ==================== Listener（兜底播放器与移交播放器共用） ====================

    @Override public void onPrepared() {
        if (pendingSeek && fallbackPlayer != null) {
            fallbackPlayer.seekTo(PlaySession.posMs);
            pendingSeek = false;
        }
        pauseIcon.setVisibility(View.GONE);
        paused = false;
    }

    @Override public void onEnded() { stopSelf(); }

    @Override public void onError(int what, int extra) {
        Log.w(TAG, "player error what=" + what + " extra=" + extra);
        stopSelf();
    }

    @Override public void onInfo(int what, int extra) {}
    @Override public void onVideoSize(int w, int h) {}

    // ==================== 清理 ====================

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        ui.removeCallbacksAndMessages(null);
        if (sessionHolder != null) {
            try { sessionHolder.setListener(null); sessionHolder.release(); } catch (Throwable ignored) {}
            sessionHolder = null;
        }
        if (fallbackPlayer != null) {
            try { fallbackPlayer.setListener(null); fallbackPlayer.release(); } catch (Throwable ignored) {}
            fallbackPlayer = null;
        }
        PlaySession.clear();
        if (floatView != null && wm != null) {
            try { wm.removeView(floatView); } catch (Throwable ignored) {}
            floatView = null;
        }
        try { stopForeground(true); } catch (Throwable ignored) {}
        notifyState(false);
    }

    private void startForegroundSafely() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "悬浮播放",
                        NotificationManager.IMPORTANCE_MIN);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH_ID)
                    : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("悬浮播放")
                    .setContentText(PlaySession.title.length() > 0 ? PlaySession.title : "枫兮影视")
                    .setOngoing(true);
            startForeground(FG_ID, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "startForeground fail", t);
        }
    }

    /** 浮窗状态推给前端（MainActivity 若活着）。 */
    private void notifyState(boolean showing) {
        MainActivity m = MainActivity.instance;
        if (m != null) {
            m.callJs("window.onFloatState && window.onFloatState({showing:" + showing + "})");
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
