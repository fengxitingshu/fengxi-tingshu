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
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.Chapter;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

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
            a |= (1L << 16);   // PlaybackState.ACTION_SET_RATE = 1 << 16
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
            @Override public void onPlay() { if (CTL != null) CTL.svcToggle(); }
            @Override public void onPause() { if (CTL != null) CTL.svcToggle(); }
            @Override public void onSkipToNext() { if (CTL != null) CTL.svcNext(); }
            @Override public void onSkipToPrevious() { if (CTL != null) CTL.svcPrev(); }
            @Override public void onStop() { if (CTL != null) CTL.svcStop(); }
            /** ★ v2.0：原子播放器/锁屏拖动进度条会回调这里 */
            @Override public void onSeekTo(long pos) {
                if (CTL != null) CTL.svcSeek((int) (pos / 1000));
            }
            /** ★ v2.0：部分系统控件支持倍速切换 */
            @Override public void onSetPlaybackSpeed(float s) {
                if (CTL != null) CTL.svcSpeed(s);
            }
            @Override public void onPlayFromMediaId(String mediaId, android.os.Bundle extras) {
                // mediaId 约定为集数下标字符串，原子播放器可据此直接选集
                try {
                    int idx = Integer.parseInt(mediaId);
                    if (CTL != null) CTL.svcPlayIndex(idx);
                } catch (Throwable ignored) {}
            }
        });
        session.setActive(true);

        // 关键：startForegroundService() 后必须在 ~5s 内调用 startForeground()，
        // 否则 Android 8+ 会抛 ForegroundServiceDidNotStartInTimeException 直接闪退。
        // 这里先挂一个「空闲」通知占位，真正播放时 update() 会替换它。
        postIdle();
    }

    /** 空闲态的最小通知（服务刚起来、还没开始播放时用） */
    private void postIdle() {
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent content = PendingIntent.getActivity(this, 0, open, piFlags());

            Notification.Builder b = newBuilder()
                    .setContentTitle("枫兮听书")
                    .setContentText("准备就绪")
                    .setSmallIcon(android.R.drawable.ic_media_play)
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
                .setSmallIcon(android.R.drawable.ic_media_play);

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
        b.addAction(new Notification.Action(
                android.R.drawable.ic_media_previous, "上一集", pi(ACT_PREV, 1)));
        b.addAction(new Notification.Action(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "暂停" : "播放", pi(ACT_TOGGLE, 3)));
        b.addAction(new Notification.Action(
                android.R.drawable.ic_media_next, "下一集", pi(ACT_NEXT, 2)));
        b.addAction(new Notification.Action(
                android.R.drawable.ic_menu_close_clear_cancel, "停止", pi(ACT_STOP, 4)));
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
                .setSmallIcon(android.R.drawable.ic_media_play);

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
            Ctl c = CTL;
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
            } else if (ACT_TOGGLE.equals(a) || ACT_NEXT.equals(a)
                    || ACT_PREV.equals(a) || ACT_SEEK.equals(a)) {
                // ★ v3.7.14：进程被系统回收后，桌面小组件的点击会冷启动本服务，
                //   此时 CTL 还是 null（PlayerCore 在 App 进程里，没有播放器可控制）。
                //   原来这种命令被静默丢掉 —— 用户表现就是"点了没反应"。
                //   这里退化成"把 App 拉起来"：用户看到界面就知道需要重新播放，
                //   比什么都不发生要好。带 openFull 让前端直接进播放页。
                try {
                    Intent ui = new Intent(this, com.tingshu.app.MainActivity.class);
                    ui.setAction(Intent.ACTION_MAIN);
                    ui.addCategory(Intent.CATEGORY_LAUNCHER);
                    ui.putExtra("openFull", true);
                    ui.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(ui);
                } catch (Throwable ignored) {}
            }
        }
        return START_STICKY;
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
