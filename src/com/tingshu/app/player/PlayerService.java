package com.tingshu.app.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

import com.tingshu.app.MainActivity;
import com.tingshu.app.R;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.Chapter;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

// ★ v4.21.32 方案 B：服务自持播放核心（无界面也可独立起播）
import com.tingshu.app.core.PlayerLog;
import com.tingshu.app.data.HistoryRepo;
import com.tingshu.app.data.Settings;
import com.tingshu.app.source.Aggregator;
import com.tingshu.app.widget.WidgetState;

/**
 * 前台播放服务：锁屏 / 通知栏控制 + 防杀。
 *
 * 关键决策：**不用 AndroidX/support-v4**，全部使用平台 API
 * （android.media.session.MediaSession + Notification.MediaStyle，都是 API 21+）。
 * 原因是本工程走手工构建链，没有 Gradle 依赖管理，引入 androidx.media 会连带
 * androidx.core 一整条依赖链，还要 Jetifier，得不偿失。
 *
 * 职责边界：本服务只负责「通知 + 媒体会话」，不持有 MediaPlayer。
 * 真正的播放由 MainActivity 持有的 PlayerCore 执行，服务通过静态 Ctl 回调它。
 */
public class PlayerService extends Service {

    public static final String CH_ID = "ts_play";
    public static final int NOTI_ID = 1001;

    public static final String ACT_TOGGLE = "com.tingshu.app.TOGGLE";
    public static final String ACT_NEXT = "com.tingshu.app.NEXT";
    public static final String ACT_PREV = "com.tingshu.app.PREV";
    public static final String ACT_STOP = "com.tingshu.app.STOP";
    /** ★ v3.7.15：桌面小组件进度条点击 → 精确跳转（extra "sec" = 目标秒） */
    public static final String ACT_SEEK = "com.tingshu.app.SEEK";

    /** 由 MainActivity 注入/撤销 */
    public interface Ctl {
        void svcToggle();
        void svcNext();
        void svcPrev();
        void svcStop();
        /** ★ v2.0：系统控件拖动进度（秒） */
        void svcSeek(int sec);
        /** ★ v2.0：系统控件设置倍速 */
        void svcSpeed(float rate);
        /** ★ v2.0：系统控件按集数下标直选（原子播放器列表点击） */
        void svcPlayIndex(int index);
    }

    private static PlayerService INSTANCE;
    private static Ctl CTL;

    private MediaSession session;
    private NotificationManager nm;
    private volatile String lastCover;
    /** 供锁屏/系统媒体控件复用的专辑图缓存（避免每次 update 重新解码） */
    private volatile Bitmap coverBmp;
    /** 播放速度倍率，由 Core 设置；写进 PlaybackState 供原子播放器显示 */
    private volatile float speed = 1.0f;
    /** 最近一次的书籍信息，用于原子播放器扩展字段 */
    private volatile Book lastBook;
    private volatile String lastChap;
    /** 最近一次章节标题（进度条 tick 重建通知时复用，避免副标题丢失） */
    private volatile String lastChapName;
    /** 最近一次时长（毫秒，-1 未知）。进度条 tick 用它重建通知 */
    private volatile long lastDurMs = -1;
    /**
     * 总时长兜底：少数源拿不到真实时长（MediaPlayer.getDuration() 返回 -1/0）。
     * 此时退化成「估算时长」——取当前已知最大进度 + 一个余量，
     * 保证通知栏进度条至少能显示并且会向前走，而不是整条消失。
     */
    private volatile long lastKnownDur = 0;

    public static void bindCtl(Ctl c) { CTL = c; }
    public static void unbindCtl() { CTL = null; }
    public static PlayerService get() { return INSTANCE; }
    public static boolean alive() { return INSTANCE != null; }

    /**
     * ★ v3.7.14：封面位图下载完成通知（★ v3.7.18 起服务于通知栏封面）。
     *
     * <p>根因：封面是**异步**下载的（{@link #loadCover} 起独立线程），而通知栏
     * 与播放页的封面同步往往是同步做的 —— 那一刻封面还没下完，取到 null
     * 就只能显示占位音符。所以这里加一个回调：封面到位后主动喊一声，
     * 让 MainActivity 立刻重绘一次通知栏封面。
     *
     * <p>（原话是「小组件同步」，小组件已在 v3.7.18 整体移除，
     *   这条回调改为服务于通知栏封面刷新。）
     */
    public interface CoverListener { void onCoverReady(Bitmap bmp); }

    private volatile CoverListener coverListener;

    public void setCoverListener(CoverListener l) { coverListener = l; }

    // ==================== 供 PlayerBrowserService 读取当前状态 ====================
    // ★ v2.2：系统「音源选择器」通过 MediaBrowserService 才知道本 App 在放什么，
    // 这几个 getter 就是给它取数据的。全部做了 null 兜底，取不到就返回 null。

    /** 当前正在播放/暂停的书；未播放时返回 null */
    public Book currentBook() { return lastBook; }

    /**
     * ★ v3.7.11：给桌面小组件复用的封面位图。
     *
     * <p>通知栏为了大图图标已经在解码并缓存封面，小组件直接拿这张现成的，
     * 避免为同一本书重复下两次图。
     */
    public Bitmap coverBitmap() { return coverBmp; }

    /** 当前集；未播放时返回 null */
    public Chapter currentChapter() { return lastChapter; }

    /** 当前集下标；未知返回 -1 */
    public int currentIndex() { return lastIndex; }

    /** MediaSession token，交给 MediaBrowserService.setSessionToken */
    public MediaSession.Token sessionToken() {
        return session == null ? null : session.getSessionToken();
    }

    private volatile Chapter lastChapter;
    private volatile int lastIndex = -1;

    /** ★ v2.0：把播放速度写进 MediaSession，让原子播放器等系统控件能读到 */
    public void setSpeed(float s) {
        if (s <= 0) return;
        this.speed = s;
        try {
            if (session != null) {
                PlaybackState.Builder p = new PlaybackState.Builder();
                p.setActions(actions(1.0f));
                p.setState(lastPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        lastPosMs, s);
                session.setPlaybackState(p.build());
            }
        } catch (Throwable ignored) {}
    }

    private volatile boolean lastPlaying = false;
    private volatile long lastPosMs = 0;

    /** 统一的动作位：含 seek / 倍速，原子播放器据此决定显示哪些控件 */
    private long actions(float rate) {
        // ACTION_SET_RATE 是 API 31+ 才有的常量，这里用其字面值避免编译期依赖
        long a = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackState.ACTION_PLAY_FROM_SEARCH;
        if (Build.VERSION.SDK_INT >= 31) {
            a |= (1L << 30);   // PlaybackState.ACTION_SET_RATE = 1 << 30（★ v3.8.4 修正：原来误写 1<<16）
        }
        return a;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();

        session = new MediaSession(this, "TingShu");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { Ctl c = ctl(); if (c != null) c.svcToggle(); }
            @Override public void onPause() { Ctl c = ctl(); if (c != null) c.svcToggle(); }
            @Override public void onSkipToNext() { Ctl c = ctl(); if (c != null) c.svcNext(); }
            @Override public void onSkipToPrevious() { Ctl c = ctl(); if (c != null) c.svcPrev(); }
            @Override public void onStop() { Ctl c = ctl(); if (c != null) c.svcStop(); }
            /** ★ v2.0：原子播放器/锁屏拖动进度条会回调这里 */
            @Override public void onSeekTo(long pos) {
                Ctl c = ctl(); if (c != null) c.svcSeek((int) (pos / 1000));
            }
            /** ★ v2.0：部分系统控件支持倍速切换 */
            @Override public void onSetPlaybackSpeed(float s) {
                Ctl c = ctl(); if (c != null) c.svcSpeed(s);
            }
            @Override public void onPlayFromMediaId(String mediaId, android.os.Bundle extras) {
                // mediaId 约定为集数下标字符串，原子播放器可据此直接选集
                try {
                    int idx = Integer.parseInt(mediaId);
                    Ctl c = ctl(); if (c != null) c.svcPlayIndex(idx);
                } catch (Throwable ignored) {}
            }
        });
        session.setActive(true);

        // 关键：startForegroundService() 后必须在 ~5s 内调用 startForeground()，
        // 否则 Android 8+ 会抛 ForegroundServiceDidNotStartInTimeException 直接闪退。
        // 这里先挂一个「空闲」通知占位，真正播放时 update() 会替换它。
        postIdle();

        // ★ v3.9.19：进程心跳（每 60s 一条诊断日志）。
        //   用途：小组件白卡时，看「alive」是否还在走——
        //   · alive 停了 = 进程被系统冻结/杀（省电限制），与画法无关；
        //   · alive 还在 = 进程活着而桌面白卡，问题在桌面渲染。
        hb = new android.os.Handler(android.os.Looper.getMainLooper());
        hbTick = new Runnable() {
            public void run() {
                try {
                    com.tingshu.app.core.DiagLog.add("[diag] svc alive");
                } catch (Throwable ignored) {}
                hb.postDelayed(this, 60000);
            }
        };
        hb.postDelayed(hbTick, 60000);

        // ★ v4.21.32 方案 B：服务独立重启（进程被杀）时，WidgetState 静态快照
        //   已经随进程清空，桌面小组件会显示「未在播放」空白。这里先把落盘会话
        //   预填进快照，让小组件开机就有上次的书名/集数，点播放即可续播。
        //   界面在正常启动时 CTL 已 bindCtl，不会走这段，避免覆盖真实播放态。
        if (CTL == null) {
            try {
                ResumeSession.Snapshot rs = ResumeSession.get(this);
                if (rs != null && rs.valid()) {
                    WidgetState.sync(rs.book, rs.chapters, rs.idx,
                            false, false, (int) (rs.posMs / 1000), 0);
                    WidgetState.requestUpdate(this, true);
                    PlayerLog.log("[headless] 服务冷启动，已恢复小组件快照");
                }
            } catch (Throwable ignored) {}
        }
    }

    /** 心跳 Handler（v3.9.19） */
    private android.os.Handler hb;
    private Runnable hbTick;

    /** 空闲态的最小通知（服务刚起来、还没开始播放时用） */
    private void postIdle() {
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent content = PendingIntent.getActivity(this, 0, open, piFlags());

            Notification.Builder b = newBuilder()
                    .setContentTitle("枫兮听书")
                    .setContentText("准备就绪")
                    // ★ v3.9.4 修复闪退：小图标必须是本 App 自己的资源。
                    //   用 android.R.drawable.*（框架资源，包 ID 0x01）时，
                    //   startForeground() 会因"小图标不属于本包"抛
                    //   RemoteServiceException/IllegalArgumentException，
                    //   表现为"一打开就闪退"。ic_notif = 品牌"翻开的书"单色剪影。
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentIntent(content)
                    .setOngoing(false)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false);
            startForeground(NOTI_ID, b.build());
        } catch (Throwable ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "播放控制", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
    }

    /**
     * 只更新进度（轻量级，专供每秒 tick 调用）。
     *
     * <p>为什么不直接复用 {@link #update}：update 会重建整个通知与 metadata，
     * 每秒一次太重。这里只改 Notification 的进度段与 MediaSession 的
     * PlaybackState，标题/封面/动作按钮全部沿用上一次的。
     *
     * <p>★ v2.4 修复「通知栏没有进度条」：原来的实现只在 update() 里
     * setProgress，而 update() 只在「开始播放 / 状态变化 / 手动 seek」时调用，
     * 此时 MediaPlayer 往往还没 prepare 完，getDuration() 返回 -1 →
     * {@code durMs > 0} 不成立 → 整个播放周期内进度条从未被设置过。
     * 现在由每秒 tick 驱动，时长一到位进度条立刻出现。
     *
     * @param posMs 当前进度毫秒（<0 视为未知）
     * @param durMs 总时长毫秒（<=0 视为未知，走估算兜底）
     */
    public void updateProgress(long posMs, long durMs) {
        if (lastBook == null) return;
        if (durMs > 0) lastDurMs = durMs;
        if (posMs > 0) lastPosMs = posMs;
        // 记住已知最大时长，供兜底估算
        if (lastDurMs > lastKnownDur) lastKnownDur = lastDurMs;

        Notification.Builder b = newBuilder()
                .setContentTitle(lastBook.title == null ? "听书" : lastBook.title)
                .setContentText(buildSub(lastBook, lastChapName))
                .setContentIntent(mainPending())
                .setOngoing(lastPlaying)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                // ★ v3.9.4：同 postIdle —— 小图标必须用本包资源，否则闪退
                .setSmallIcon(R.drawable.ic_notif);

        applyActions(b, lastPlaying);
        if (coverBmp != null) {
            try { b.setLargeIcon(coverBmp); } catch (Throwable ignored) {}
        }
        applyProgress(b);

        setPlayback(lastPlaying, lastPosMs);
        // 用 notify 而不是 startForeground —— 已在后台上百次的 startForeground
        // 会不断抢焦点；已经在前台了就只更新内容。
        try { nm.notify(NOTI_ID, b.build()); } catch (Throwable ignored) {}
    }

    /** 统一的进度条写入。durMs 未知时用估算值兜底，避免进度条整条不显示。 */
    private void applyProgress(Notification.Builder b) {
        try {
            long dur = lastDurMs;
            if (dur <= 0) {
                // 兜底：以「已知最大进度 + 余量」当总长，至少让进度条能出现并前进
                long est = lastKnownDur > 0 ? lastKnownDur : lastPosMs + 60000L;
                dur = Math.max(est, lastPosMs + 1000L);
            }
            if (dur <= 0) return;
            int max = 1000;
            long p = Math.max(0, lastPosMs);
            int cur = (int) Math.min(max, (p * max) / dur);
            b.setProgress(max, cur, false);
        } catch (Throwable ignored) {}
    }

    /**
     * 动作按钮（顺序 = 上一集 / 播放暂停 / 下一集 / 停止）。
     * 抽出来供 update 与 updateProgress 共用，避免两处顺序写歪。
     */
    private void applyActions(Notification.Builder b, boolean playing) {
        // ★ v3.9.1：控制按钮改用自绘矢量图标。
        //   原来是 android.R.drawable.ic_media_* —— 系统框架位图只有 mdpi 一张，
        //   在 xxhdpi/xxxhdpi 手机上被拉伸，边缘发糊带锯齿。矢量按屏幕密度
        //   实时光栅化，任何密度下都是锐利的实心轮廓。
        b.addAction(new Notification.Action(
                R.drawable.ic_notif_prev, "上一集", pi(ACT_PREV, 1)));
        b.addAction(new Notification.Action(
                playing ? R.drawable.ic_notif_pause : R.drawable.ic_notif_play,
                playing ? "暂停" : "播放", pi(ACT_TOGGLE, 3)));
        b.addAction(new Notification.Action(
                R.drawable.ic_notif_next, "下一集", pi(ACT_NEXT, 2)));
        b.addAction(new Notification.Action(
                R.drawable.ic_notif_stop, "停止", pi(ACT_STOP, 4)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Notification.MediaStyle style = new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2);
            b.setStyle(style);
        }
    }

    private PendingIntent mainPending() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, open, piFlags());
    }

    /**
     * 刷新通知与媒体会话。
     * @param book     当前书；null 表示停止（撤下通知）
     * @param chapName 当前集标题
     * @param playing  是否正在播放
     * @param posMs    当前进度毫秒（用于锁屏进度条；-1 表示未知）
     * @param durMs    总时长毫秒（-1 未知）
     */
    public void update(Book book, String chapName, boolean playing, long posMs, long durMs) {
        update(book, chapName, playing, posMs, durMs, -1, null);
    }

    /**
     * 完整版：额外带上「集数下标」与「章节对象」，供 iQOO 音源选择器等
     * 通过 MediaBrowserService 读取当前播放内容。
     */
    public void update(Book book, String chapName, boolean playing,
                       long posMs, long durMs, int chapIdx, Chapter chap) {
        if (book == null) {
            nm.cancel(NOTI_ID);
            lastBook = null;
            lastChapter = null;
            lastIndex = -1;
            lastChapName = null;
            lastDurMs = -1;
            lastPosMs = 0;
            lastKnownDur = 0;
            lastPlaying = false;
            return;
        }
        lastBook = book;
        lastIndex = chapIdx;
        lastChapter = chap;
        String sub = buildSub(book, chapName);

        Notification.Builder b = newBuilder();
        b.setContentTitle(book.title == null ? "听书" : book.title)
                .setContentText(sub)
                .setContentIntent(mainPending())
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                // ★ v3.9.4：同 postIdle —— 小图标必须用本包资源，否则闪退
                .setSmallIcon(R.drawable.ic_notif);

        // ★ v2.2：动作**按最终展示顺序**依次添加 —— 顺序就是下面的字面顺序，
        //   紧凑视图用下标 0/1/2 引用，两者必须严格对应，否则会出现"点了上一集却变下一集"。
        //   顺序约定：上一集 → 播放/暂停 → 下一集 → 停止
        //   （与 iQOO 原子播放器、多数音乐 App 的胶囊顺序一致）
        applyActions(b, playing);

        // ★ 大图标（通知右上角的封面缩略图）：原子播放器与锁屏会读它
        if (coverBmp != null) {
            try { b.setLargeIcon(coverBmp); } catch (Throwable ignored) {}
        }

        // ★ v2.4 进度条：原来看起来"没进度条"的根因是这里 ——
        //   进度条只在 update() 里设置，而 update() 的调用时机是
        //   「开播 / 状态变化 / seek」，那时 MediaPlayer 常常还没 prepare 完，
        //   getDuration() 返回 -1 → durMs > 0 不成立 → 整个播放周期都没设过。
        //   现在把状态记下来交给每秒的 updateProgress() 去设（见该方法注释）。
        setMetadata(book, chapName, durMs);
        lastChapName = chapName;
        lastPlaying = playing;
        lastDurMs = durMs;
        if (posMs > 0) lastPosMs = posMs;
        applyProgress(b);
        setPlayback(playing, posMs);

        startForeground(NOTI_ID, b.build());
    }

    private String buildSub(Book book, String chapName) {
        StringBuilder sb = new StringBuilder();
        if (book.artist != null && book.artist.length() > 0) sb.append(book.artist);
        if (book.source != null && book.source.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(sourceName(book.source));
        }
        if (chapName != null && chapName.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(chapName);
        }
        return sb.length() == 0 ? "正在播放" : sb.toString();
    }

    private String sourceName(String id) {
        try {
            com.tingshu.app.source.Source s = com.tingshu.app.source.SourceRegistry.byId(id);
            if (s != null) return s.name();
        } catch (Throwable ignored) {}
        return id;
    }

    /** 最近一次的基础 metadata（不含封面位图），封面下载完要用它重建，不能丢字段 */
    private MediaMetadata baseMeta;

    private void setMetadata(Book book, String chapName, long durMs) {
        lastBook = book;
        lastChap = chapName;
        if (book == null) return;
        try {
            MediaMetadata.Builder m = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE,
                            book.title == null ? "听书" : book.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST,
                            book.artist == null ? "" : book.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM,
                            chapName == null ? "" : chapName)
                    // ★ v2.0 iQOO 原子播放器适配：
                    // 系统媒体控件优先读 DISPLAY_* 系列做标题/副标题展示，
                    // 不补的话部分 ROM 会显示空白或回退到 ALBUM。
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                            book.title == null ? "听书" : book.title)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                            chapName == null ? "" : chapName)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
                            book.artist == null ? "" : book.artist)
                    // 集数信息放进 DISPLAY_DESCRIPTION 之外的入口：
                    // 原子播放器常把 ALBUM_ARTIST 当作第二行副标题
                    .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                            book.artist == null ? "" : book.artist);
            // ★ 封面 URL 也写进 metadata：原子播放器/车机可自行拉取，
            //   不依赖我们下载好位图（比传 Bitmap 更省内存、更快出图）。
            if (book.cover != null && book.cover.startsWith("http")) {
                m.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, book.cover);
                m.putString(MediaMetadata.METADATA_KEY_ART_URI, book.cover);
                m.putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, book.cover);
            }
            if (durMs > 0) m.putLong(MediaMetadata.METADATA_KEY_DURATION, durMs);
            // 已经下载过封面位图就直接带上，省一次解码
            if (coverBmp != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, coverBmp);
            baseMeta = m.build();
            session.setMetadata(baseMeta);
            if (book.cover != null && book.cover.startsWith("http")) loadCover(book.cover);
        } catch (Throwable ignored) {}
    }

    private void loadCover(final String url) {
        if (url == null || !url.startsWith("http")) return;
        if (url.equals(lastCover)) return;
        lastCover = url;
        new Thread(new Runnable() {
            public void run() {
                try {
                    // ★ v2.2 封面缓存：先内存、再磁盘，最后才联网。
                    //   之前每次切集都重新下封面（同一张图下 N 次），
                    //   既是流量浪费，也让通知右上角迟迟不出图。
                    Bitmap bmp = coverCacheGet(url);
                    if (bmp == null) {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(8000);
                        c.setRequestProperty("User-Agent", com.tingshu.app.core.Net.UA);
                        InputStream in = c.getInputStream();
                        bmp = BitmapFactory.decodeStream(in);
                        in.close();
                        if (bmp != null) coverCachePut(url, bmp);
                    }
                    if (bmp != null && session != null) {
                        coverBmp = bmp;   // 缓存，后续 update 直接复用
                        // ★ v3.7.14：封面到位 → 通知 MainActivity 重绘一次通知栏封面。
                        //   （原文是"重推小组件快照"，小组件已于 v3.7.18 移除）
                        try {
                            CoverListener cl = coverListener;
                            if (cl != null) cl.onCoverReady(bmp);
                        } catch (Throwable ignored) {}
                        // ★ 在基础 metadata 上补专辑图，不能新建空 Builder，
                        //   否则 title/duration 被冲掉 → 通知标题消失、锁屏进度条失效。
                        //   v2.0：直接以 baseMeta 为蓝本重建，避免逐字段搬运遗漏
                        //   （原来只搬了 4 个字段，DISPLAY_*/URI 系列会丢）。
                        MediaMetadata base = baseMeta;
                        if (base == null) return;
                        MediaMetadata.Builder m = new MediaMetadata.Builder(base);
                        m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp);
                        session.setMetadata(m.build());
                    }
                } catch (Throwable ignored) {}
            }
        }, "ts-cover").start();
    }

    // ==================== 封面两级缓存（v2.2） ====================

    /** 内存 LRU：切集/切书来回切时不重复解码 */
    private static final int COVER_MEM_MAX = 8;
    private static final java.util.LinkedHashMap<String, Bitmap> COVER_MEM =
            new java.util.LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Bitmap> e) {
                    return size() > COVER_MEM_MAX;
                }
            };

    /** 磁盘缓存 TTL：封面基本不变，给长一点 */
    private static final long COVER_DISK_TTL_MS = 14L * 24 * 3600 * 1000;

    private static java.io.File coverDiskFile(String url) {
        try {
            java.io.File d = new java.io.File(
                    com.tingshu.app.core.App.ctx().getCacheDir(), "cover");
            if (!d.exists() && !d.mkdirs()) return null;
            return new java.io.File(d,
                    com.tingshu.app.core.Utils.md5(url) + ".img");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap coverCacheGet(String url) {
        synchronized (COVER_MEM) {
            Bitmap m = COVER_MEM.get(url);
            if (m != null) return m;
        }
        try {
            java.io.File f = coverDiskFile(url);
            if (f == null || !f.exists() || f.length() == 0) return null;
            if (System.currentTimeMillis() - f.lastModified() > COVER_DISK_TTL_MS) {
                f.delete();
                return null;
            }
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) {
                synchronized (COVER_MEM) {
                    COVER_MEM.put(url, b);
                }
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void coverCachePut(final String url, final Bitmap bmp) {
        synchronized (COVER_MEM) {
            COVER_MEM.put(url, bmp);
        }
        // 落盘放到独立线程，别占着当前下载线程
        new Thread(new Runnable() {
            public void run() {
                java.io.FileOutputStream fos = null;
                try {
                    java.io.File f = coverDiskFile(url);
                    if (f == null) return;
                    fos = new java.io.FileOutputStream(f);
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos);
                    fos.flush();
                } catch (Throwable ignored) {
                } finally {
                    if (fos != null) try { fos.close(); } catch (Throwable ignored) {}
                }
            }
        }, "ts-cover-w").start();
    }

    private void setPlayback(boolean playing, long posMs) {
        lastPlaying = playing;
        if (posMs > 0) lastPosMs = posMs;
        try {
            PlaybackState.Builder p = new PlaybackState.Builder()
                    .setActions(actions(speed));
            p.setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                    lastPosMs, speed);
            // ★ v2.0：位置随每次 update 一起进 PlaybackState，
            //   系统媒体控件（锁屏 / 原子播放器 / 通知栏进度条）都读它。
            session.setPlaybackState(p.build());
        } catch (Throwable ignored) {}
    }

    private Notification.Builder newBuilder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CH_ID);
        }
        return new Notification.Builder(this);
    }

    private int piFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    private PendingIntent pi(String act, int req) {
        Intent i = new Intent(this, PlayerService.class);
        i.setAction(act);
        return PendingIntent.getService(this, req, i, piFlags());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = (intent == null) ? null : intent.getAction();
        if (a != null) {
            // ★ v4.21.32 方案 B：ctl() 在界面不在时自动落到暗核心控制器，
            //   小组件/通知栏/锁屏点击不再退化成「把界面拉起来」，而是直接起播。
            Ctl c = ctl();
            if (c != null) {
                if (ACT_TOGGLE.equals(a)) c.svcToggle();
                else if (ACT_NEXT.equals(a)) c.svcNext();
                else if (ACT_PREV.equals(a)) c.svcPrev();
                else if (ACT_SEEK.equals(a)) {
                    // ★ v3.7.15：小组件进度条跳转。sec 由 Provider 按 5% 粒度算好。
                    int sec = intent.getIntExtra("sec", -1);
                    if (sec >= 0) c.svcSeek(sec);
                }
                else if (ACT_STOP.equals(a)) {
                    c.svcStop();
                    stopFg(true);
                }
            }
        }
        return START_STICKY;
    }

    // ==================== ★ v4.21.32 方案 B：无界面播放（暗核心） ====================
    //
    // 背景：以前 PlayerCore 只由 MainActivity 持有，进程被杀后服务里没有播放器，
    //   小组件/锁屏点击只能退化成「把界面拉起来」，用户得再点一次才能出声。
    //
    // 方案 B：让 PlayerService 自己也能持有一个 PlayerCore（下称"暗核心"）。
    //   · 界面在（CTL != null）：一切走原路径，暗核心不启用 —— 保护既有播放链路；
    //   · 界面不在（CTL == null，进程被杀 / 服务独立重启）：控制命令走 HEADLESS_CTL，
    //     暗核心从 ResumeSession 快照恢复会话，用纯 Java 重新取链（不需要 WebView），
    //     直接起播；小组件/通知栏/锁屏由暗核心回调维护，全程不弹界面；
    //   · 用户之后打开 App：MainActivity 调 takeHeadless(this) 接管正在播的核心
    //     （只换绑回调，不 stop / 不重取链 / 不双声）；
    //   · 用户退出界面但仍在播：onDestroy 调 giveBack(core) 把核心交回服务。
    //
    // 可行性前提（已逐条核实）：
    //   · App.onCreate() 在进程冷启动时先跑完 Settings / Net cookies /
    //     SourceRegistry.registerAll / Db.init —— 网盘 Cookie 与书源都就绪；
    //   · Aggregator.audioWithFallback + PanResolver 都是纯 Java，不依赖 WebView；
    //   · PlayerCore 构造只吃 Context + Settings.gain()，服务自带 Context。

    /** 服务自持的播放核心；界面接管期间为 null */
    private PlayerCore headless;
    /** 暗核心当前是否归界面所有（防止接管窗口内重复创建导致双声） */
    private volatile boolean headlessOwnedByUi = false;
    /** ★ v4.21.33：取链代际。界面接管**不算**作废，只有又发起新一集才作废旧结果 */
    private volatile int fetchGen = 0;
    /** ★ v4.21.33：冷恢复取链是否正在途中（防用户连点重复发起取链） */
    private volatile boolean coldResuming = false;

    /**
     * 统一的控制命令接收者：界面在 → MainActivity（原路径）；
     * 界面不在 → 暗核心控制器。锁屏/通知栏/蓝牙/小组件因此全部照常可用。
     */
    private static Ctl ctl() {
        return CTL != null ? CTL : HEADLESS_CTL;
    }

    /** 无界面时的控制器：按需创建暗核心并从快照恢复 */
    private static final Ctl HEADLESS_CTL = new Ctl() {
        public void svcToggle() {
            PlayerService s = INSTANCE;
            if (s == null) return;
            PlayerCore c = s.ensureHeadless();
            if (c == null) return;
            if (!c.hasContent()) { s.resumeFromSnapshot(); return; }
            if (c.isPlaying()) { c.pause(); return; }
            // 暂停 → 恢复（幂等，不会把正在播的误停）
            if (c.playIfPaused()) return;
            // ★ v4.21.33：player 还没建好 = 冷恢复取链在途 / 上次取链失败。
            //   这次点击不能被吞掉，否则用户会以为「要点两次才播」。
            //   resumeFromSnapshot 内部有 coldResuming 防重，不会重复取链。
            s.resumeFromSnapshot();
        }
        public void svcNext() {
            PlayerService s = INSTANCE;
            if (s == null) return;
            PlayerCore c = s.ensureHeadless();
            if (c == null) return;
            if (!c.hasContent()) { s.resumeFromSnapshot(); return; }
            s.headlessPlayIndex(c.index() + 1);
        }
        public void svcPrev() {
            PlayerService s = INSTANCE;
            if (s == null) return;
            PlayerCore c = s.ensureHeadless();
            if (c == null) return;
            if (!c.hasContent()) { s.resumeFromSnapshot(); return; }
            s.headlessPlayIndex(c.index() - 1);
        }
        public void svcStop() {
            PlayerService s = INSTANCE;
            if (s != null) s.stopHeadless();
        }
        public void svcSeek(int sec) {
            PlayerService s = INSTANCE;
            if (s == null) return;
            PlayerCore c = s.ensureHeadless();
            if (c == null) return;
            // 冷启动时用户直接点了进度条：先恢复当前集，再按目标秒数跳
            if (!c.hasContent()) { s.resumeFromSnapshot(); return; }
            c.seek(Math.max(0, sec));
        }
        public void svcSpeed(float rate) {
            PlayerService s = INSTANCE;
            if (s == null) return;
            if (s.headless != null) s.headless.setSpeed(rate);
            s.setSpeed(rate);
        }
        public void svcPlayIndex(int index) {
            PlayerService s = INSTANCE;
            if (s == null) return;
            PlayerCore c = s.ensureHeadless();
            if (c == null) return;
            if (!c.hasContent()) { s.resumeFromSnapshot(); return; }
            s.headlessPlayIndex(index);
        }
    };

    /** 创建（或复用）服务自持的播放核心 */
    private PlayerCore ensureHeadless() {
        if (headless != null) return headless;
        if (headlessOwnedByUi) return null;   // 界面正在接管，服务不碰，避免双声
        try {
            headless = new PlayerCore(this);
            headless.setCallback(new HeadlessCallback());
            headless.setSpeed(Settings.speed());
            PlayerLog.log("[headless] 服务自持播放核心已就绪");
        } catch (Throwable t) {
            PlayerLog.log("[headless] 创建失败：" + t);
            headless = null;
        }
        return headless;
    }

    /** 从落盘会话恢复并起播（纯 Java 取链，不需要界面） */
    private void resumeFromSnapshot() {
        // ★ v4.21.33：已有一次冷恢复取链在途时不重复发起。网盘取链要几秒，
        //   用户等不及连点第二下，重复取链既浪费又可能让两次 playUrl 抢播放器。
        if (coldResuming) {
            PlayerLog.log("[headless] 冷恢复取链已在途，忽略重复触发");
            return;
        }
        final ResumeSession.Snapshot snap = ResumeSession.get(this);
        if (snap == null || !snap.valid()) {
            // 从没播放过 / 用户主动停止后清空了会话：保留原有兜底 —— 打开 App 选书
            PlayerLog.log("[headless] 没有可恢复的会话，拉起界面选书");
            try {
                Intent ui = new Intent(this, MainActivity.class);
                ui.setAction(Intent.ACTION_MAIN);
                ui.addCategory(Intent.CATEGORY_LAUNCHER);
                ui.putExtra("openFull", true);
                ui.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(ui);
            } catch (Throwable ignored) {}
            return;
        }
        final PlayerCore c = ensureHeadless();
        if (c == null) return;
        c.setQueue(snap.book, snap.chapters, snap.idx);
        PlayerLog.log("[headless] 冷恢复《" + (snap.book.title == null ? "" : snap.book.title)
                + "》第 " + (snap.idx + 1) + " 集，续播 " + (snap.posMs / 1000) + "s");
        headlessPlayIndex(snap.idx, snap.posMs);
    }

    /** 播放指定集（暗核心版 MainActivity.gotoChapter，取链全部走纯 Java） */
    private void headlessPlayIndex(final int idx) { headlessPlayIndex(idx, 0); }

    private void headlessPlayIndex(final int idx, final long startMs) {
        // ★ v4.21.33：捕获核心**对象本身**。takeHeadless() 只是把 s.headless
        //   置空并换绑回调，这个对象仍然是界面正在用的同一个核心。旧代码
        //   在回调里 `if (headless == null) return;` 会把在途取链结果整个丢掉，
        //   表现为「小组件点了播放、进 App 还得再点一次」。
        final PlayerCore target = headless;
        if (target == null) return;
        final Book book = target.book();
        final List<Chapter> chs = target.chapters();
        if (book == null || chs == null || chs.isEmpty()) return;
        if (idx < 0 || idx >= chs.size()) {
            PlayerLog.log("[headless] 集下标越界 " + idx);
            return;
        }
        final Chapter ch = chs.get(idx);
        final int humanIdx = (ch != null && ch.index > 0) ? ch.index : idx + 1;
        final long saved = startMs > 0 ? startMs : HistoryRepo.posOf(book.key(), humanIdx);
        target.setQueue(book, chs, idx);
        WidgetState.sync(book, chs, idx, false, true, (int) (saved / 1000), 0);
        WidgetState.requestUpdate(this, true);
        final int gen = ++fetchGen;
        coldResuming = true;
        PlayerLog.log("[headless] 重新取链《" + book.title + "》第 " + humanIdx + " 集");
        Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                final String[] outUsed = new String[2];
                @SuppressWarnings("unchecked")
                final Map<String, String>[] outHdr = new Map[1];
                String audio = null;
                try {
                    audio = Aggregator.audioWithFallback(book, humanIdx, null, outUsed, outHdr);
                } catch (Throwable t) {
                    PlayerLog.log("[headless] 取链异常：" + t);
                }
                final String url = audio;
                final String src = outUsed[0] != null ? outUsed[0] : book.source;
                final Map<String, String> hdr = outHdr[0];
                final String chapUrl = (ch == null || ch.url == null) ? "" : ch.url;
                main().post(new Runnable() {
                    public void run() {
                        // 只有「又发起了新一集 / 被停止」才作废旧结果；
                        // 界面接管不作废 —— 核心是同一个，回调已换绑给界面。
                        if (gen != fetchGen) return;
                        coldResuming = false;
                        if (url == null || url.length() == 0) {
                            PlayerLog.log("[headless] 取链失败：没有可用直链");
                            WidgetState.buffering = false;
                            WidgetState.requestUpdate(PlayerService.this, true);
                            return;
                        }
                        target.playUrl(src, chapUrl, url, saved, hdr);
                        saveSnapshot(target, src, chapUrl);
                        PlayerLog.log("[headless] 已起播（"
                                + (headless == null ? "界面已接管" : "无界面") + "）");
                    }
                });
            }
        });
    }

    /** 主线程 Handler（复用心跳的，未创建时临时建一个） */
    private android.os.Handler main() {
        if (hb != null) return hb;
        return new android.os.Handler(android.os.Looper.getMainLooper());
    }

    /** 把暗核心当前状态写回落盘会话 */
    private void saveSnapshot(String source, String chapUrl) {
        saveSnapshot(headless, source, chapUrl);
    }

    /** ★ v4.21.33：可指定核心 —— 界面接管后 s.headless 为 null，会话仍要落盘 */
    private void saveSnapshot(PlayerCore c, String source, String chapUrl) {
        if (c == null) return;
        Book b = c.book();
        if (b == null) return;
        ResumeSession.save(this, b, c.chapters(), c.index(),
                (long) c.curSec() * 1000, source, chapUrl);
    }

    /** 停止暗核心并清除会话（用户主动停止，不该再被小组件续上） */
    private void stopHeadless() {
        // ★ v4.21.33：作废旧代际，防止「停止」之后在途取链结果又把播放拉起来
        fetchGen++;
        coldResuming = false;
        if (headless != null) {
            try { headless.stop(); } catch (Throwable ignored) {}
            try { headless.release(); } catch (Throwable ignored) {}
            headless = null;
        }
        ResumeSession.clear(this);
        WidgetState.clear();
        WidgetState.requestUpdate(this, true);
        try { update(null, null, false, -1, -1); } catch (Throwable ignored) {}
        PlayerLog.log("[headless] 已停止并清除会话");
    }

    /** 把暗核心状态同步到通知栏 + 桌面小组件（无界面时唯一的 UI 出口） */
    private void syncHeadlessUi() {
        PlayerCore c = headless;
        if (c == null) return;
        Book b = c.book();
        if (b == null) {
            try { update(null, null, false, -1, -1); } catch (Throwable ignored) {}
            WidgetState.clear();
            WidgetState.requestUpdate(this, true);
            return;
        }
        List<Chapter> chs = c.chapters();
        int idx = c.index();
        Chapter ch = (chs != null && idx >= 0 && idx < chs.size()) ? chs.get(idx) : null;
        try {
            update(b, ch == null ? null : ch.title, c.isPlaying(),
                    c.curSec() * 1000L, c.durSec() * 1000L, idx, ch);
        } catch (Throwable ignored) {}
        WidgetState.sync(b, chs, idx, c.isPlaying(), false, c.curSec(), c.durSec());
        WidgetState.rate = c.speed();
        WidgetState.requestUpdate(this, true);
    }

    /** 暗核心的无界面回调：只更新通知 / 小组件 / 会话，绝不碰 WebView */
    private class HeadlessCallback implements PlayerCore.Callback {
        public void onStarted(Book book, int chapterIdx) {
            syncHeadlessUi();
            PlayerCore c = headless;
            if (c != null) saveSnapshot(c.usedSource(), c.chapterUrl());
        }
        public void onStateChanged(boolean playing) { syncHeadlessUi(); }
        public void onProgress(int curSec, int durSec) {
            try { updateProgress(curSec * 1000L, durSec * 1000L); } catch (Throwable ignored) {}
            WidgetState.syncProgress(curSec, durSec);
            WidgetState.requestUpdate(PlayerService.this);
            // 每 5 秒把进度写回会话（touch 只改两个字段，开销极小）
            if (curSec % 5 == 0) {
                PlayerCore c = headless;
                if (c != null) ResumeSession.touch(PlayerService.this,
                        (long) curSec * 1000, c.index());
            }
        }
        public void onEnded() {
            PlayerCore c = headless;
            if (c == null) return;
            List<Chapter> chs = c.chapters();
            int cur = c.index();
            if (chs == null || chs.isEmpty() || cur < 0) { syncHeadlessUi(); return; }
            int mode = Settings.playMode();
            if (mode == 1) { headlessPlayIndex(cur); return; }        // 单曲循环
            if (cur + 1 < chs.size()) {
                if (mode == 3) {                                        // 随机
                    int r = cur;
                    if (chs.size() > 1) { while (r == cur) r = (int) (Math.random() * chs.size()); }
                    headlessPlayIndex(r);
                    return;
                }
                if (mode == 2 || Settings.autoNext()) {                 // 列表循环 / 顺序连播
                    headlessPlayIndex(cur + 1);
                    return;
                }
            } else if (mode == 2) {                                    // 列表循环回第一集
                headlessPlayIndex(0);
                return;
            }
            syncHeadlessUi();
        }
        public void onError(String msg) {
            PlayerLog.log("[headless] 播放失败：" + msg);
            WidgetState.playing = false;
            WidgetState.buffering = false;
            WidgetState.requestUpdate(PlayerService.this, true);
        }
    }

    /**
     * MainActivity.onCreate：界面接管服务里正在播的暗核心。
     * 只换绑回调，不 stop、不重取链、不产生第二个播放器。
     */
    public static PlayerCore takeHeadless(Ctl uiCtl) {
        PlayerService s = INSTANCE;
        if (s == null || s.headless == null) return null;
        PlayerCore c = s.headless;
        s.headless = null;
        s.headlessOwnedByUi = false;
        CTL = uiCtl;    // 提前绑定，避免接管窗口内 ctl() 又落回暗核心
        PlayerLog.log("[headless] 界面接管播放核心（不中断、不重取链）");
        return c;
    }

    /** MainActivity.onDestroy：界面退出但仍在播 → 核心交回服务继续后台播放 */
    public static void giveBack(PlayerCore c) {
        if (c == null) return;
        PlayerService s = INSTANCE;
        if (s == null) { c.release(); return; }
        s.headless = c;
        s.headlessOwnedByUi = false;
        try { c.setCallback(s.new HeadlessCallback()); } catch (Throwable ignored) {}
        try { c.setHealListener(null); } catch (Throwable ignored) {}
        // 定时关闭的暂停钩子改绑服务侧，不持有已销毁的 Activity
        try {
            com.tingshu.app.player.SleepTimer.bindTarget(
                    new com.tingshu.app.player.SleepTimer.PauseTarget() {
                        public void onSleepPause() {
                            PlayerService sv = INSTANCE;
                            if (sv != null && sv.headless != null) sv.headless.pause();
                        }
                    });
        } catch (Throwable ignored) {}
        s.saveSnapshot(c.usedSource(), c.chapterUrl());
        PlayerLog.log("[headless] 界面退出，核心交回服务继续后台播放");
    }

    private void stopFg(boolean remove) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(remove ? Service.STOP_FOREGROUND_REMOVE : Service.STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(remove);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        // ★ v4.21.32：服务真的被销毁时释放暗核心（若已被界面接管则为 null）
        try {
            if (headless != null) { headless.release(); headless = null; }
        } catch (Throwable ignored) {}
        try {
            if (hb != null && hbTick != null) hb.removeCallbacks(hbTick);
        } catch (Throwable ignored) {}
        try {
            if (session != null) {
                session.setActive(false);
                session.release();
                session = null;
            }
        } catch (Throwable ignored) {}
        nm.cancel(NOTI_ID);
        INSTANCE = null;
        super.onDestroy();
    }
}
