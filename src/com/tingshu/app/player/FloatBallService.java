package com.tingshu.app.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.tingshu.app.MainActivity;
// 注意：本文件里「Settings」一律写全限定名，因为 android.provider.Settings 与
// com.tingshu.app.data.Settings 同简单名，一旦 import 其中一个就会出现歧义编译错误。

/**
 * ★ v2.8 自绘悬浮球。
 *
 * <p><b>为什么不用系统自带的那个悬浮窗</b>：
 * 系统在后台播放时会自己弹一个媒体小圆标，位置由系统说了算、用户拖不动，
 * 而且不同 ROM 表现差异很大（有的会乱跳）。用户明确要一个「自己画、能拖、能吸附边缘」的。
 *
 * <p><b>窗口类型为什么选 OVERLAY / PHONE</b>：
 * <ul>
 *   <li>{@code TYPE_APPLICATION_PANEL} —— 排除。它必须依附宿主窗口，
 *       App 一退到后台窗口即销毁，悬浮球跟着消失，完全达不到目的。</li>
 *   <li>{@code TYPE_APPLICATION_OVERLAY}（API 26+）/ {@code TYPE_PHONE}（26 以下）
 *       —— 采用。这是「应用级悬浮窗」，可跨 App 显示，退到后台也活着。
 *       代价是需要 SYSTEM_ALERT_WINDOW 权限，得引导用户去系统设置里开。</li>
 * </ul>
 *
 * <p><b>为什么独立成一个服务、不塞进 PlayerService</b>：
 * PlayerService 是 {@code foregroundServiceType="mediaPlayback"}，职责是
 * 「通知 + MediaSession」，语义明确。把窗口管理混进去既污染职责，
 * 也让前台服务类型对不上。这里独立成服务，由 MainActivity 跟着播放状态启停。
 *
 * <p><b>位置持久化</b>：存的是绝对 px。跨设备/改分辨率后可能越界，
 * 所以每次显示前都 clamp 到屏幕范围内。刻意不存 dp —— 读取时屏幕密度可能已变，
 * 反而不准。
 */
public class FloatBallService extends Service {

    /** 悬浮窗权限被关闭时的兜底：什么也不做，静默退出（不能崩） */
    public static final String ACTION_SHOW = "com.tingshu.app.FLOAT_SHOW";
    public static final String ACTION_HIDE = "com.tingshu.app.FLOAT_HIDE";

    private static final int NOTI_ID = 1002;
    private static final String CH_ID = "ts_float";

    /** 球直径（dp） */
    private static final int BALL_DP = 52;
    /** 判定「拖动」的位移阈值（px），小于它算点击 */
    private static final int DRAG_SLOP = 12;

    private WindowManager wm;
    private View ball;
    private WindowManager.LayoutParams lp;
    private NotificationManager nm;

    private int screenW, screenH;
    private float downRawX, downRawY;
    private int downLpX, downLpY;
    private boolean dragged;

    // ==================== 生命周期 ====================

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // 前台服务必须在 5 秒内 startForeground，否则 8.0+ 直接 ANR/崩
        startForegroundSafely();

        // 没有悬浮窗权限就什么都不做（用户可能在设置里关掉了）
        if (!canDraw()) {
            stopSelf();
            return;
        }

        try {
            buildBall();
        } catch (Throwable t) {
            // 任何窗口异常都不能崩，最多是没球
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // 服务被系统重启时 onCreate 已建好球，这里只保证前台状态
        if (ball == null && canDraw()) {
            try { buildBall(); } catch (Throwable ignored) {}
        }
        // 不要 sticky：播放停了就该消失，没必要被系统拉起来
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        removeBall();
        try { stopForeground(true); } catch (Throwable ignored) {}
        try { nm.cancel(NOTI_ID); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== 对外静态入口 ====================

    /**
     * 显示悬浮球（幂等）。权限没给就静默返回 —— 调用方不需要关心权限细节。
     *
     * @return true 表示这次调用让悬浮球可见了；false 表示没权限或失败
     */
    public static boolean show(Context ctx) {
        if (ctx == null) return false;
        if (!canDraw(ctx)) return false;
        try {
            Intent i = new Intent(ctx, FloatBallService.class);
            i.setAction(ACTION_SHOW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 隐藏悬浮球（幂等） */
    public static void hide(Context ctx) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(ctx, FloatBallService.class);
            i.setAction(ACTION_HIDE);
            ctx.startService(i);
        } catch (Throwable ignored) {}
    }

    /** 是否已授予「显示在其他应用上层」权限 */
    public static boolean canDraw() {
        return canDraw(null);
    }

    public static boolean canDraw(Context ctx) {
        // API 23 以下安装即授（部分 ROM 默认允许），不做判断
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            Context c = ctx != null ? ctx : com.tingshu.app.core.App.ctx();
            return android.provider.Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 悬浮球本体 ====================

    private void buildBall() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        int size = dp(BALL_DP);

        ball = new ImageView(this);
        // 圆底 + 播放三角，纯代码画，不依赖 drawable 资源
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFFE8503A);      // 与 App 主色一致
        bg.setStroke(dp(2), 0x33FFFFFF);
        // 用 App 图标作为球面图案，保持品牌一致性
        ((ImageView) ball).setImageResource(android.R.drawable.ic_media_play);
        ((ImageView) ball).setScaleType(ImageView.ScaleType.CENTER);
        ball.setBackground(bg);
        ball.setElevation(dp(6));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        // 还原上次位置；没摆过就默认贴右侧、纵向居中偏下
        int x = com.tingshu.app.data.Settings.floatBallX();
        int y = com.tingshu.app.data.Settings.floatBallY();
        if (x < 0) x = screenW - size - dp(12);
        if (y < 0) y = (int) (screenH * 0.62f);
        lp.x = clampX(x, size);
        lp.y = clampY(y, size);

        ball.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        downLpX = lp.x;
                        downLpY = lp.y;
                        dragged = false;
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downRawX);
                        int dy = (int) (e.getRawY() - downRawY);
                        if (Math.abs(dx) > DRAG_SLOP || Math.abs(dy) > DRAG_SLOP) {
                            dragged = true;
                        }
                        lp.x = clampX(downLpX + dx, lp.width);
                        lp.y = clampY(downLpY + dy, lp.height);
                        update();
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragged) {
                            snapToEdge();
                            savePos();
                        } else {
                            openPlayer();
                        }
                        return true;
                }
                return false;
            }
        });

        wm.addView(ball, lp);
    }

    private void removeBall() {
        if (ball != null && wm != null) {
            try { wm.removeView(ball); } catch (Throwable ignored) {}
        }
        ball = null;
    }

    /** 松手后吸附到最近的左右边缘 */
    private void snapToEdge() {
        if (lp == null || ball == null) return;
        int left = dp(4);
        int right = screenW - lp.width - dp(4);
        lp.x = (lp.x + lp.width / 2 < screenW / 2) ? left : right;
        lp.y = clampY(lp.y, lp.height);
        update();
    }

    private void savePos() {
        try {
            com.tingshu.app.data.Settings.setFloatBallX(lp.x);
            com.tingshu.app.data.Settings.setFloatBallY(lp.y);
        } catch (Throwable ignored) {}
    }

    private void update() {
        if (ball == null || wm == null) return;
        try { wm.updateViewLayout(ball, lp); } catch (Throwable ignored) {}
    }

    /** 点球（非拖动）→ 拉起 App 并直接进全屏播放页 */
    private void openPlayer() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra("openFull", true);
            startActivity(i);
        } catch (Throwable ignored) {}
    }

    // ==================== 工具 ====================

    private int clampX(int x, int w) {
        if (x < 0) return 0;
        if (x > screenW - w) return Math.max(0, screenW - w);
        return x;
    }

    private int clampY(int y, int h) {
        if (y < 0) return 0;
        if (y > screenH - h) return Math.max(0, screenH - h);
        return y;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void startForegroundSafely() {
        try {
            createChannel();
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int pflags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent content = PendingIntent.getActivity(this, 0, open, pflags);

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CH_ID)
                    : new Notification.Builder(this);
            b.setContentTitle("枫兮听书")
                    .setContentText("悬浮窗已开启")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(content)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setPriority(Notification.PRIORITY_MIN);
            startForeground(NOTI_ID, b.build());
        } catch (Throwable ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "悬浮窗", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
