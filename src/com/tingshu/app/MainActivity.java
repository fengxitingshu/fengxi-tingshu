package com.tingshu.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.tingshu.app.core.Net;
import com.tingshu.app.data.HistoryRepo;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.player.PlayerCore;
import com.tingshu.app.player.PlayerService;
import com.tingshu.app.source.Aggregator;
import com.tingshu.app.source.Source;
import com.tingshu.app.source.SourceRegistry;

import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * 应用主界面。
 *
 * 职责：承载 WebView + 挂 Bridge + 持有 PlayerCore。
 * 播放逻辑在 PlayerCore 里（第 3 批搬到 PlayerService 时只换持有者）。
 */
public class MainActivity extends Activity
        implements Bridge.PlayerControl, PlayerCore.Callback, PlayerService.Ctl {

    private WebView web;
    private PlayerCore core;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 续播用：本次播放请求的起始偏移（毫秒） */
    private long pendingStartMs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ★ v3.7.15：崩溃日志落盘。
        //
        //   用户反馈"概率闪退"，但拿不到 logcat —— 没有堆栈就只能盲猜。
        //   这里挂一个默认 UncaughtExceptionHandler：任何未捕获异常都把
        //   完整堆栈写到应用私有目录 crash/ 下（保留最近 5 份），
        //   下次闪退后让用户把文件发来即可精确定位。
        //   处理完仍走系统默认流程（该崩还是崩，不吞）。
        try {
            final Thread.UncaughtExceptionHandler prev =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(
                    new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        File dir = new File(getFilesDir(), "crash");
                        if (!dir.exists()) dir.mkdirs();
                        String ts = new java.text.SimpleDateFormat(
                                "yyyyMMdd-HHmmss", java.util.Locale.US)
                                .format(new java.util.Date());
                        File f = new File(dir, "crash-" + ts + ".log");
                        StringBuilder sb = new StringBuilder();
                        sb.append("时间: ").append(ts).append('\n')
                          .append("线程: ").append(t.getName()).append('\n')
                          .append("版本: ")
                          .append(cnversionName()).append('\n')
                          .append("机型: ").append(android.os.Build.MANUFACTURER)
                          .append(' ').append(android.os.Build.MODEL)
                          .append(" (Android ").append(android.os.Build.VERSION.RELEASE)
                          .append(")\n\n");
                        java.io.PrintWriter pw = new java.io.PrintWriter(f, "UTF-8");
                        pw.write(sb.toString());
                        e.printStackTrace(pw);
                        pw.close();
                        // 只保留最近 5 份，防膨胀
                        File[] all = dir.listFiles();
                        if (all != null && all.length > 5) {
                            java.util.Arrays.sort(all, new java.util.Comparator<File>() {
                                public int compare(File a, File b) {
                                    return Long.compare(b.lastModified(), a.lastModified());
                                }
                            });
                            for (int i = 5; i < all.length; i++) all[i].delete();
                        }
                    } catch (Throwable ignored) {}
                    if (prev != null) prev.uncaughtException(t, e);
                }
            });
        } catch (Throwable ignored) {}

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(Net.UA);
        // 注意：这里绝对不能用 FORCE_DARK_ON。
        // 那是 Android 10+ 的「强制反色」总开关，会无条件把页面（含我们的浅色
        // :root 变量）整体反色，导致「选了白天还是黑底」。深色改由 CSS 的
        // html.dark 自己控制，WebView 只负责别在页面边界漏出白/黑闪屏。
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                s.setForceDark(WebSettings.FORCE_DARK_OFF);
            }
        } catch (Throwable ignored) {}
        // 页面本身是白底时，WebView 底色也必须是白的，否则 OVER_SCROLL / 加载瞬间会漏黑
        web.setBackgroundColor(0xFFFFFFFF);

        // ★ v3.8.1：主题在页面加载完成后必须再刷一次。
        //   原来只在 onCreate 里 applyTheme() 一次，而那次在 web.loadUrl() 之前
        //   执行 —— evaluateJavascript 打向的还是空白页，赋值随着页面导航被丢弃，
        //   所以冷启动永远是白底（用户看到的就是「深色模式存不住」）。
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                applyTheme();
            }
        });

        // ★ v2.8 关键修复：必须设置 WebChromeClient，否则 JS 的
        //   alert / confirm / prompt 全部静默失效 —— confirm 恒返回 false、
        //   prompt 恒返回 null。后果是「点了缓存没反应」「分组建不了」
        //   这类"按钮点了像没点"的怪现象（页面里那个 confirm 直接短路了）。
        //   这里用默认实现即可，系统会弹标准对话框；
        //   页面上的高频/危险操作另有自绘弹窗（confirmSheet / inputSheet），
        //   不走系统框，保持 App 自身风格统一。
        web.setWebChromeClient(new android.webkit.WebChromeClient());

        web.addJavascriptInterface(new Bridge(web, this), "Android");

        core = new PlayerCore(this);
        core.setCallback(this);
        // ★ P0-1/P0-2：播放失败自动换源/重试时，给前端书源标签与轻提示
        core.setHealListener(new PlayerCore.HealListener() {
            @Override
            public void onHeal(int kind, String source, String sourceName) {
                if (kind == 1) {
                    // 换到新音源：刷新播放条/全屏页右上角「书源胶囊」+ 轻提示
                    try {
                        JSONObject o = new JSONObject();
                        o.put("source", source == null ? "" : source);
                        o.put("sourceName", sourceName == null ? "" : sourceName);
                        callJs("window.onSourceSwitched(" + o.toString() + ")");
                    } catch (Throwable ignored) {}
                } else {
                    // 网络波动：轻提示，不打断播放态
                    callJs("window.onPlayRetry()");
                }
            }
        });
        core.setSpeed(com.tingshu.app.data.Settings.speed());

        // ★ v3.9.0：定时关闭 —— 到点暂停钩子绑到本 Activity 持有的 PlayerCore；
        //   状态变化（设置/停表/到点）实时推给前端播放页与桌面小组件。
        com.tingshu.app.player.SleepTimer.bindTarget(
                new com.tingshu.app.player.SleepTimer.PauseTarget() {
                    public void onSleepPause() {
                        try { if (core != null) core.pause(); } catch (Throwable ignored) {}
                    }
                });
        com.tingshu.app.player.SleepTimer.setListener(
                new com.tingshu.app.player.SleepTimer.Listener() {
                    public void onSleepState(int min, int remain, int ws, int we, boolean holding) {
                        try {
                            callJs("window.onSleep("
                                    + com.tingshu.app.player.SleepTimer.stateJson().toString() + ")");
                        } catch (Throwable ignored) {}
                        // 小组件只更新定时角标（轻量：不重取封面、不缩放位图）
                        try {
                            com.tingshu.app.widget.WidgetState.sleepChip =
                                    com.tingshu.app.player.SleepTimer.chipText();
                            com.tingshu.app.widget.WidgetState.requestUpdate(MainActivity.this, true);
                        } catch (Throwable ignored) {}
                    }
                });

        // 前台播放服务：锁屏/通知栏控制 + 防杀
        PlayerService.bindCtl(this);
        startPlayerService();
        requestNotifPermission();
        wireCacheListener();

        // 主题：跟随系统深浅色
        applyTheme();

        web.loadUrl("file:///android_asset/www/index.html");
    }

    /**
     * 启动前台服务。
     * 注意：API 26+ 用 startForegroundService，若启动失败（部分 ROM 限制后台启动）
     * 不要让它把 App 带崩 —— 播放本身不依赖 Service，Service 只提供通知。
     */
    private void startPlayerService() {
        try {
            Intent si = new Intent(this, PlayerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si);
            else startService(si);
        } catch (Throwable t) {
            // 降级：普通 startService，通知可能不显示但播放不受影响
            try {
                startService(new Intent(this, PlayerService.class));
            } catch (Throwable ignored) {}
        }
    }

    /** 把缓存进度回调桥接到 JS */
    private void wireCacheListener() {
        com.tingshu.app.core.CacheManager.setListener(
                new com.tingshu.app.core.CacheManager.Listener() {
            public void onProgress(Book book, int done, int total, int curIdx) {
                JSONObject o = new JSONObject();
                try {
                    o.put("title", book == null ? "" : book.title);
                    o.put("done", done);
                    o.put("total", total);
                    o.put("index", curIdx);
                } catch (Throwable ignored) {}
                callJs("window.onCacheProgress(" + o.toString() + ")");
            }

            public void onDone(Book book, int done, int total) {
                JSONObject o = new JSONObject();
                try {
                    o.put("title", book == null ? "" : book.title);
                    o.put("done", done);
                    o.put("total", total);
                } catch (Throwable ignored) {}
                callJs("window.onCacheDone(" + o.toString() + ")");
            }

            public void onError(Book book, int idx, String msg) {
                // 单集失败不打扰用户，整体进度里能看出来
            }

            /** ★ v2.8：单集缓存结束 → 让章节行的下载按钮落定图标 */
            public void onChapterDone(Book book, int idx, boolean ok) {
                JSONObject o = new JSONObject();
                try {
                    o.put("key", book == null ? "" : book.key());
                    o.put("index", idx);
                    o.put("ok", ok);
                } catch (Throwable ignored) {}
                callJs("window.onChapterCacheDone(" + o.toString() + ")");
            }
        });

        // ★ v2.9：下载调度器的回调。事件名与缓存共用一套（onCache*），
        //   因为前端章节行的图标逻辑完全一样，没必要区分来源。
        com.tingshu.app.core.DownloadManager.setListener(
                new com.tingshu.app.core.DownloadManager.Listener() {
            public void onChapterDone(Book book, int idx, boolean ok) {
                JSONObject o = new JSONObject();
                try {
                    o.put("key", book == null ? "" : book.key());
                    o.put("index", idx);
                    o.put("ok", ok);
                } catch (Throwable ignored) {}
                callJs("window.onChapterCacheDone(" + o.toString() + ")");
            }

            public void onProgress(Book book, int done, int total, int curIdx) {
                JSONObject o = new JSONObject();
                try {
                    o.put("title", book == null ? "" : book.title);
                    o.put("done", done);
                    o.put("total", total);
                    o.put("index", curIdx);
                } catch (Throwable ignored) {}
                callJs("window.onCacheProgress(" + o.toString() + ")");
            }

            public void onDone(Book book, int done, int total) {
                JSONObject o = new JSONObject();
                try {
                    o.put("title", book == null ? "" : book.title);
                    o.put("done", done);
                    o.put("total", total);
                } catch (Throwable ignored) {}
                callJs("window.onCacheDone(" + o.toString() + ")");
            }

            public void onError(Book book, int idx, String msg) {
                // 单集失败不打扰用户，进度里能看出来
            }

            /** 移动到外部文件夹失败 —— 只在第一次提示，避免整本下载时刷屏 */
            public void onExportFail(Book book, int idx, String msg) {
                if (exportWarned) return;
                exportWarned = true;
                callJs("window.onToast(" + JSONObject.quote(msg) + ")");
            }
        });
    }

    /** 外部目录写入失败只提示一次（整本下载会连续失败，不能每集都弹） */
    private boolean exportWarned = false;

    /** 跟随系统夜间模式。用 Configuration.uiMode，API 21+ 均可读。 */
    private void applyTheme() {
        try {
            int mode = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            boolean dark;
            String pref = com.tingshu.app.data.Settings.theme();
            if ("dark".equals(pref)) dark = true;
            else if ("light".equals(pref)) dark = false;
            else dark = (mode == android.content.res.Configuration.UI_MODE_NIGHT_YES);
            applyThemeToWeb(dark);
        } catch (Throwable ignored) {}
    }

    private void applyThemeToWeb(final boolean dark) {
        // WebView 底色跟着切，避免深色下滚动/加载瞬间闪白，浅色下闪黑
        try {
            web.setBackgroundColor(dark ? 0xFF000000 : 0xFFFFFFFF);
        } catch (Throwable ignored) {}
        ui.post(new Runnable() {
            public void run() {
                try {
                    web.evaluateJavascript(
                            "(function(){document.documentElement.className='" +
                                    (dark ? "dark" : "") + "';})()", null);
                } catch (Throwable ignored) {}
            }
        });
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applyTheme();
    }

    /** Android 13+ 需要运行时申请通知权限，否则通知不显示 */
    private void requestNotifPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * ★ v3.9.0：申请存储读取权限（本地书源扫描本机音频用）。
     *
     * <p>API 33+ 申请 READ_MEDIA_AUDIO；以下申请 READ_EXTERNAL_STORAGE。
     * 结果通过 {@code window.onLocalPerm} 告诉前端，前端批了再扫一次。
     * 用户选了「拒绝」时回 ok:false，前端引导去系统设置（不反复弹窗惹人烦）。
     */
    public void requestStoragePermission() {
        try {
            String perm = Build.VERSION.SDK_INT >= 33
                    ? "android.permission.READ_MEDIA_AUDIO"
                    : "android.permission.READ_EXTERNAL_STORAGE";
            if (checkSelfPermission(perm)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{perm}, 1002);
            } else {
                callJs("window.onLocalPerm({ok:true})");
            }
        } catch (Throwable t) {
            callJs("window.onLocalPerm({ok:false,error:'fail'})");
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        // ★ v3.9.0：存储权限（本地书源扫描）
        if (req == 1002) {
            boolean ok = false;
            try {
                ok = res != null && res.length > 0
                        && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) {}
            callJs("window.onLocalPerm({ok:" + ok + "})");
            return;
        }
        super.onRequestPermissionsResult(req, perms, res);
    }

    // ==================== PlayerService.Ctl ====================

    @Override public void svcToggle() { core.toggle(); }
    @Override public void svcNext() { next(); }
    @Override public void svcPrev() { prev(); }
    @Override public void svcStop() { core.stop(); refreshNotification(); }

    /** ★ v3.7.15：崩溃日志用的本地版本号（拿不到就退化为清单里的版本名） */
    private String cnversionName() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** ★ v2.0：系统媒体控件（原子播放器/锁屏）拖动进度 */
    @Override public void svcSeek(int sec) {
        core.seek(Math.max(0, sec));
        refreshNotification();
    }

    /** ★ v2.0：系统媒体控件切换倍速，回传给前端保持 UI 一致 */
    @Override public void svcSpeed(float rate) {
        if (rate <= 0) return;
        core.setSpeed(rate);
        callJs("window.onSysSpeed(" + rate + ")");
    }

    /** ★ v2.0：系统媒体控件按集数下标直选（原子播放器的选集列表） */
    @Override public void svcPlayIndex(int index) {
        gotoChapter(index);
    }

    // ==================== Bridge.PlayerControl ====================

    @Override
    public void play(String source, String chapterUrl, String title, String directUrl,
                     java.util.Map<String, String> headers) {
        long start = pendingStartMs;
        pendingStartMs = 0;
        core.playUrl(source, chapterUrl, directUrl, start, headers);
    }

    @Override
    public void setQueue(Book b, java.util.List<com.tingshu.app.data.Models.Chapter> chs, int idx) {
        // 先把待播偏移定下来：新集没有历史记录时 posOf 返回 0，天然从头播
        try {
            if (b != null && chs != null && idx >= 0 && idx < chs.size()) {
                int humanIdx = chs.get(idx).index > 0 ? chs.get(idx).index : idx + 1;
                pendingStartMs = HistoryRepo.posOf(b.key(), humanIdx);
            }
        } catch (Throwable ignored) {}
        core.setQueue(b, chs, idx);
    }

    @Override
    public boolean isPlaying() {
        try { return core.isPlaying(); } catch (Throwable t) { return false; }
    }

    @Override
    public void pause() {
        core.pause();
    }

    @Override
    public void resume() {
        core.resume();
    }

    @Override
    public void toggle() {
        core.toggle();
    }

    @Override
    public boolean playIfPaused() {
        // ★ v2.6：把 PlayerCore 的真实结果透传给 Bridge ——
        //   返回 false 表示"没有可复用的播放器"（进程被杀后的冷启动），
        //   Bridge 会回调前端改走完整取链，避免"点了没反应"。
        return core.playIfPaused();
    }

    // ---- 供 Bridge.resumeSession 读取当前状态 ----

    @Override
    public Book currentBook() {
        try { return core.book(); } catch (Throwable t) { return null; }
    }

    @Override
    public int currentIndex() {
        try { return core.index() + 1; } catch (Throwable t) { return 0; }
    }

    @Override
    public int currentPosSec() {
        try { return core.curSec(); } catch (Throwable t) { return -1; }
    }

    @Override
    public String currentChapterTitle() {
        try {
            List<Chapter> chs = core.chapters();
            int i = core.index();
            if (chs != null && i >= 0 && i < chs.size()) {
                Chapter c = chs.get(i);
                return c == null || c.title == null ? "" : c.title;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    @Override
    public int currentChapterCount() {
        try {
            List<Chapter> chs = core.chapters();
            return chs == null ? 0 : chs.size();
        } catch (Throwable t) { return 0; }
    }

    @Override
    public void seek(int seconds) {
        core.seek(seconds);
    }

    @Override
    public void setSpeed(float speed) {
        core.setSpeed(speed);
        // ★ v2.0：同步到 MediaSession，让原子播放器/锁屏能看到当前倍速
        PlayerService svc = PlayerService.get();
        if (svc != null) svc.setSpeed(speed);
    }

    // ★ v3.1：音量增益（声音增幅/降幅）
    @Override
    public void setGain(int pct) {
        core.setGain(pct);
    }

    @Override
    public int gain() {
        return core.gain();
    }

    @Override
    public void stop() {
        core.stop();
        // ★ v2.2：停止播放时清掉预加载标记，避免下次播别的书被旧标记挡住
        try { Aggregator.resetPreload(); } catch (Throwable ignored) {}
    }

    @Override
    public void next() {
        gotoChapter(core.index() + 1);
    }

    @Override
    public void prev() {
        gotoChapter(core.index() - 1);
    }

    /**
     * 播放指定集（0 基）。带自动换源 + 续播偏移。
     * 这是所有"切集"的唯一入口。
     */
    private void gotoChapter(int idx) {
        final Book book = core.book();
        final List<Chapter> chs = core.chapters();
        if (book == null || chs == null || chs.isEmpty()) return;
        if (idx < 0 || idx >= chs.size()) {
            toast(idx < 0 ? "已经是第一集" : "已经是最后一集");
            return;
        }

        core.setQueue(book, chs, idx);
        final Chapter ch = chs.get(idx);
        final int humanIdx = (ch.index > 0 ? ch.index : idx + 1);

        // 续播：该集上次听到哪
        final long saved = HistoryRepo.posOf(book.key(), humanIdx);

        Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                String audio = null;
                String usedSrc = book.source;
                java.util.Map<String, String> hdr = null;
                try {
                    String[] out = new String[2];
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, String>[] outHdr = new java.util.Map[1];
                    audio = Aggregator.audioWithFallback(book, humanIdx, null, out, outHdr);
                    if (out[0] != null) usedSrc = out[0];
                    hdr = outHdr[0];
                } catch (Throwable ignored) {}

                final String url = audio;
                final String src = usedSrc;
                final java.util.Map<String, String> headers = hdr;
                ui.post(new Runnable() {
                    public void run() {
                        if (url == null) {
                            // ★ v3.7.13：这里**不再**自己 toast —— 前端 onPlayError
                            //   会弹一个带「关闭 / 换一集 / 换源」的提示面板。
                            //   以前这里 toast 一次、前端又 toast 一次，两句话叠在
                            //   屏幕上互相覆盖，用户只看到一句半截的提示。
                            callJs("window.onPlayError()");
                            return;
                        }
                        // 续播：集内偏移 < 正片时长 90% 才跳，否则从头（防"听完又倒回"）
                        pendingStartMs = saved;
                        core.playUrl(src, ch.url, url, saved, headers);
                        JSONObject o = new JSONObject();
                        try {
                            o.put("url", url);
                            o.put("title", ch.title);
                            o.put("index", humanIdx);
                            o.put("startMs", saved);
                            o.put("source", src);
                            o.put("sourceName", BridgeSource.sourceNameOf(src));
                        } catch (Throwable ignored) {}
                        callJs("window.onPlaying(" + o.toString() + ")");
                        // ★ v2.2：进播放队列后立刻预解析"下下集"的直链，
                        //   这样连播到下一集时 take 到的是缓存，切集几乎无感。
                        try {
                            int nextHuman = humanIdx + 1;
                            if (idx + 1 < chs.size()) {
                                Chapter nc = chs.get(idx + 1);
                                if (nc != null && nc.index > 0) nextHuman = nc.index;
                            }
                            Aggregator.preloadNext(book, nextHuman, null);
                        } catch (Throwable ignored) {}
                    }
                });
            }
        });
    }

    // ==================== PlayerCore.Callback ====================

    @Override
    public void onStarted(Book book, int chapterIdx) {
        // PlayerCore 已在 saveRecord 里写历史，这里只推 UI
        refreshNotification();
        // 自动缓存后续 N 集（受设置开关控制，内部还会判断 WiFi）
        try {
            com.tingshu.app.core.CacheManager.autoCacheAhead(
                    book, chapterIdx, com.tingshu.app.data.Settings.autoCacheCount());
        } catch (Throwable ignored) {}
        // ★ v2.2 播放提速：预解析"下一集"的直链。
        //   只解析 URL（几 KB 字符串）落进缓存 + 热连接，不下载音频文件本身。
        //   用户点「下一集」时 Aggregator.audioWithFallback 在缓存里秒回，
        //   省掉「详情页 → 播放页 → 解析」整串 HTTP 往返——这是切集卡顿的主因。
        try {
            java.util.List<com.tingshu.app.data.Models.Chapter> chs = core.chapters();
            int nextHuman = chapterIdx + 2;   // chapterIdx 是 0 基，下下集 = (idx+1)+1
            if (chs != null && chapterIdx + 1 < chs.size()) {
                com.tingshu.app.data.Models.Chapter nc = chs.get(chapterIdx + 1);
                if (nc != null && nc.index > 0) nextHuman = nc.index;
            }
            com.tingshu.app.source.Aggregator.preloadNext(book, nextHuman, null);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onStateChanged(final boolean playing) {
        callJs("window.onPlayState(" + playing + ")");
        lastNotiTick = 0;   // 状态变化立刻刷新通知（不受节流影响）
        refreshNotification();
        // ★ v3.8.2：同步桌面小组件（停止时 core.book() 已空 → 快照清空）
        syncWidget();
        // ★ v2.8：悬浮球跟随播放状态 —— 开播显示、停播收起
        syncFloatBall(playing);
    }

    /**
     * ★ v3.7.14：注册「封面已下载好」的回调（★ v3.7.18 起服务于通知栏封面）。
     *
     * <p>封面是异步下载的（PlayerService.loadCover 起线程），而通知栏通常在
     * 状态变化那一刻就刷了一次，那时封面往往还没回来 → 通知里只有占位图。
     * 这里挂上回调：封面到位后立刻重绘一次通知，封面马上就有图了。
     */
    private void bindCoverListener() {
        try {
            com.tingshu.app.player.PlayerService svc = PlayerService.get();
            if (svc == null) return;
            svc.setCoverListener(new com.tingshu.app.player.PlayerService.CoverListener() {
                @Override
                public void onCoverReady(android.graphics.Bitmap bmp) {
                    if (bmp == null) return;
                    // 封面到位 → 立刻重绘通知栏（封面存在 PlayerService 缓存里，
                    // refreshNotification 会自己取到新图）
                    MainActivity.this.runOnUiThread(new Runnable() {
                        public void run() { refreshNotification(); }
                    });
                    // ★ v3.8.2：桌面小组件同样要补这次封面
                    MainActivity.this.runOnUiThread(new Runnable() {
                        public void run() { syncWidget(); }
                    });
                }
            });
        } catch (Throwable ignored) {}
    }

    // ==================== ★ v3.8.2 桌面小组件同步 ====================

    /**
     * 把播放状态刷进 {@link com.tingshu.app.widget.WidgetState} 快照并请求重绘。
     *
     * <p>调用点：onStateChanged（开播/暂停/停止/切集换书）、onEnded、
     * 封面到位。进度 tick 走轻量的 {@link #syncWidgetProgress}。
     */
    private void syncWidget() {
        try {
            com.tingshu.app.data.Models.Book bk = core.book();
            if (bk == null) {
                com.tingshu.app.widget.WidgetState.clear();
                com.tingshu.app.widget.WidgetState.requestUpdate(this, true);
                return;
            }
            List<Chapter> chs = core.chapters();
            int idx = core.index();
            com.tingshu.app.widget.WidgetState.sync(bk, chs, idx,
                    core.isPlaying(), false, core.curSec(), core.durSec());
            // ★ v3.9.0：倍速 + 定时角标（小组件右上两个小胶囊）
            com.tingshu.app.widget.WidgetState.rate = core.speed();
            com.tingshu.app.widget.WidgetState.sleepChip =
                    com.tingshu.app.player.SleepTimer.chipText();
            // 封面：复用通知栏已下好的位图，缩到 128px 再进快照。
            // ★ 必须缩放：RemoteViews 走 Binder 传输，整张封面（常见
            //   500~1000px，解码后 1~4MB）直接塞有 TransactionTooLarge
            //   风险，小组件会整块更新失败。
            android.graphics.Bitmap full = lastCoverBmp();
            if (full != null) {
                com.tingshu.app.widget.WidgetState.cover = scaleCover(full);
            }
            com.tingshu.app.widget.WidgetState.requestUpdate(this, true);
        } catch (Throwable ignored) {}
    }

    /** 秒级进度同步（不重取封面，只更新进度字段后请求重绘） */
    private void syncWidgetProgress(int cur, int dur) {
        try {
            if (!com.tingshu.app.widget.WidgetState.hasContent) return;
            com.tingshu.app.widget.WidgetState.syncProgress(cur, dur);
            com.tingshu.app.widget.WidgetState.requestUpdate(this);
        } catch (Throwable ignored) {}
    }

    /** 复用 PlayerService 缓存里的封面位图（拿不到就返回 null，小组件显示占位） */
    private android.graphics.Bitmap lastCoverBmp() {
        try {
            com.tingshu.app.player.PlayerService svc = PlayerService.get();
            if (svc != null) return svc.coverBitmap();
        } catch (Throwable ignored) {}
        return null;
    }

    /** 封面缩放到 128px 见方（RemoteViews Binder 传输安全尺寸） */
    private android.graphics.Bitmap scaleCover(android.graphics.Bitmap src) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            int side = Math.min(w, h);
            if (side <= 0) return src;
            if (side <= 128) return src;
            float scale = 128f / side;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(scale, scale);
            return android.graphics.Bitmap.createBitmap(src, 0, 0, w, h, m, true);
        } catch (Throwable t) {
            return src;
        }
    }

    /**
     * ★ v2.8：按播放状态启停自绘悬浮球。
     *
     * <p>只有用户开了开关、且系统已授予悬浮窗权限时才会真的显示；
     * 缺权限时 {@link com.tingshu.app.player.FloatBallService#show} 返回 false，
     * 这里把开关回写为「关」并提示一次，避免开关显示为开、实际没球。
     */
    private void syncFloatBall(boolean playing) {
        try {
            if (!com.tingshu.app.data.Settings.floatBallEnabled()) {
                com.tingshu.app.player.FloatBallService.hide(this);
                return;
            }
            if (!playing) {
                com.tingshu.app.player.FloatBallService.hide(this);
                return;
            }
            if (!com.tingshu.app.player.FloatBallService.show(this)) {
                // 权限没了 —— 回写开关并告知（只提示一次，避免每集都弹）
                if (!floatPermWarned) {
                    floatPermWarned = true;
                    com.tingshu.app.data.Settings.setFloatBallEnabled(false);
                    callJs("window.onFloatBallDenied && window.onFloatBallDenied()");
                }
            } else {
                floatPermWarned = false;
            }
        } catch (Throwable ignored) {}
    }

    private boolean floatPermWarned = false;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // ★ v2.8：点悬浮球回来时带 openFull，让前端直接进全屏播放页
        if (intent != null && intent.getBooleanExtra("openFull", false)) {
            callJs("window.onOpenFull && window.onOpenFull()");
        }
    }

    /** 通知进度条节流。tick 是 1 秒一次，这里放开到每秒都刷（轻量路径）。 */
    private long lastNotiTick = 0;

    @Override
    public void onProgress(final int cur, final int dur) {
        callJs("window.onProgress(" + cur + "," + dur + ")");
        // ★ v2.4 修复「通知栏没有进度条」：
        //   原实现走 refreshNotification() → svc.update()，那条路会重建整个通知
        //   与 metadata（封面、动作、MediaStyle），每秒一次太重，于是被节流到 1 秒
        //   一跳一跳；更要命的是 update() 只在开播瞬间调用过一次，那时
        //   getDuration() 还没准备好 —— 进度条整个生命周期都没被设置过。
        //   现在改走 updateProgress()：只改进度段 + PlaybackState，开销极小，
        //   每秒都刷，时长一到位进度条立刻出现并平滑前进。
        com.tingshu.app.player.PlayerService svc = PlayerService.get();
        if (svc != null && core.book() != null) {
            svc.updateProgress(cur * 1000L, dur * 1000L);
        }
        // ★ v3.8.2：桌面小组件进度同步（秒级，与通知栏同频）
        syncWidgetProgress(cur, dur);
    }

    @Override
    public void onEnded() {
        // ★ v3.9.7 按集停止：定时选了「播完 N 集」且已到数 → 暂停且不再连播。
        //   必须在自动连播判断之前，否则永远轮不到它。
        if (com.tingshu.app.player.SleepTimer.onEpisodeEnd()) {
            core.pause();   // 复用暂停：落盘进度 + 推状态给前端/小组件
            return;
        }
        // ★ 自动连播：只要还有下一集就接着放，**不**向 JS 发 onEnded，
        //   否则前端会把图标切成"播放"并在那里停住。
        final java.util.List<com.tingshu.app.data.Models.Chapter> chs = core.chapters();
        final int cur = core.index();
        if (chs != null && cur + 1 < chs.size()) {
            // gotoChapter 内部会：setQueue → 取链（带自动换源）→ playUrl → 推 onPlaying
            gotoChapter(cur + 1);
            return;
        }
        // 已经是最后一集（或压根没有队列）：真正的播放结束
        // ★ v3.8.2：小组件同步到「播完态」（playing=false、进度保留），
        //   随后前端多半会调 stop()，那时快照会被清空
        syncWidget();
        callJs("window.onEnded()");
    }

    @Override
    public void onError(final String msg) {
        // ★ v2.2：播放失败先把该集的取链缓存剔掉 ——
        //   直链带时效签名，过期/失效后如果还从缓存里拿，会一直撞同一个死链。
        //   剔掉后下次切到这一集会重新解析，自然走自动换源。
        try {
            Book b = core.book();
            if (b != null) {
                java.util.List<Chapter> chs = core.chapters();
                int i = core.index();
                if (chs != null && i >= 0 && i < chs.size()) {
                    Chapter c = chs.get(i);
                    int human = (c != null && c.index > 0) ? c.index : i + 1;
                    com.tingshu.app.source.Aggregator.invalidateAudio(b, human);
                }
            }
        } catch (Throwable ignored) {}
        // ★ v3.7.13：把原因交给前端（v3.8.2 起前端不再弹面板，
        //   改为自动停止播放，见 app.js stopForPlayFail）。
        // ★ v3.8.2：先把小组件同步到「已停止」态，不等前端 stop 回来。
        syncWidget();
        try {
            JSONObject _o = new JSONObject();
            _o.put("reason", msg == null ? "" : msg);
            _o.put("kind", "play");
            callJs("window.onPlayError(" + _o.toString() + ")");
        } catch (Throwable t) {
            callJs("window.onPlayError()");
        }
    }

    // ==================== 通知栏同步 ====================

    /** 把当前播放状态同步到前台服务通知 */
    private void refreshNotification() {
        final PlayerService svc = PlayerService.get();
        if (svc == null) return;
        final Book b = core.book();
        if (b == null) {
            svc.update(null, null, false, -1, -1);
            return;
        }
        Chapter ch = null;
        List<Chapter> chs = core.chapters();
        int idx = core.index();
        if (chs != null && idx >= 0 && idx < chs.size()) ch = chs.get(idx);
        String title = ch == null ? null : ch.title;
        boolean playing = core.isPlaying();
        int posSec = core.curSec();
        int durSec = core.durSec();
        // ★ v2.2：把「集数下标 + 章节对象」一并传给服务，
        //   供 iQOO 音源选择器通过 MediaBrowserService 读取当前播放内容
        svc.update(b, title, playing, posSec * 1000L, durSec * 1000L, idx, ch);
    }

    // ==================== 内部 ====================

    private void callJs(final String code) {
        ui.post(new Runnable() {
            public void run() {
                try {
                    web.evaluateJavascript(code, null);
                } catch (Throwable ignored) {}
            }
        });
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    // ==================== 源文件选择 ====================

    /** 文件选择请求码 */
    private static final int REQ_PICK_SOURCE = 2001;
    private static final int REQ_PICK_DOWNLOAD_DIR = 2002;
    /** ★ v3.8.0：本地备份存放目录（SAF 目录树） */
    private static final int REQ_PICK_BACKUP_DIR = 2003;
    /** ★ v3.8.0：选一个 .fxbak 备份包来恢复 */
    private static final int REQ_PICK_BACKUP_FILE = 2004;
    /** ★ v3.9.1：本地书源自定义目录（SAF 目录树） */
    private static final int REQ_PICK_LOCAL_DIR = 2005;

    /**
     * ★ v2.9：打开系统文件夹选择器，挑一个「下载存放目录」。
     *
     * <p>用 ACTION_OPEN_DOCUMENT_TREE（SAF），不需要任何存储权限。
     * 拿到目录树 URI 后必须 takePersistableUriPermission，否则重启 App 后
     * 这个授权就失效了 —— 表现为「昨天下的能播、今天播不了」。
     */
    public void pickDownloadDir() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(it, REQ_PICK_DOWNLOAD_DIR);
        } catch (Throwable t) {
            callJs("window.onDownloadDir({ok:false,cancel:false,error:'no-picker'})");
        }
    }

    /**
     * 打开系统文件选择器挑一个源文件。
     *
     * <p>支持三类（按扩展名分流，见 {@link #onActivityResult}）：
     * <ul>
     *   <li>{@code .jar} / {@code .dex} —— jar 插件源（DexClassLoader）</li>
     *   <li>{@code .zip} —— ★ v3.0 JS 脚本插件包（Rhino）；若探测后不是 JS 包，
     *       自动按 jar 插件处理</li>
     *   <li>{@code .json} / 其它 —— 源配置文本（含原版听书订阅清单）</li>
     * </ul>
     * 用 ACTION_OPEN_DOCUMENT（SAF），不需要存储权限，返回可读的 content:// URI。
     */
    public void pickSourceFile() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            // zip 优先（JS 插件包）；jar/dex 次之；json 兜底。
            // 部分文件管理器 MIME 记录不规范，octet-stream 兜底
            it.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip", "application/x-zip-compressed",
                    "application/java-archive", "application/x-java-archive",
                    "application/json", "text/json", "text/plain",
                    "application/octet-stream", "*/*"});
            startActivityForResult(it, REQ_PICK_SOURCE);
        } catch (Throwable t) {
            // 极少数 ROM 没有文件选择器，降级用文本导入
            callJs("window.onToast(" + JSONObject.quote("无法打开文件选择器，请改用粘贴导入") + ")");
            callJs("window.onSourceFilePicked({ok:false,cancel:false,error:'no-picker'})");
        }
    }

    /**
     * ★ v3.8.0：打开系统文件夹选择器，挑一个「本地备份存放目录」。
     *
     * <p>与 {@link #pickDownloadDir()} 完全同一套 SAF 范式：用
     * {@code ACTION_OPEN_DOCUMENT_TREE}，不需要任何存储权限；
     * 拿到目录树 URI 后必须 {@code takePersistableUriPermission}，
     * 否则重启 App 后授权失效。
     *
     * <p>选中的目录存在前端内存里（不落盘到设置），因为备份是低频操作，
     * 每次重新选一次反而更明确 —— 也避免了「昨天选的目录今天失效」
     * 这种困惑（授权其实还在，但用户已经不记得选过）。
     */
    public void pickBackupDir() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(it, REQ_PICK_BACKUP_DIR);
        } catch (Throwable t) {
            callJs("window.onBackupDir({ok:false,cancel:false,error:'no-picker'})");
        }
    }

    /**
     * ★ v3.9.1：打开系统目录选择器，给本地书源自选一个扫描根目录。
     *
     * <p>与备份目录同一套范式（SAF 目录树 + 持久授权），差别是这里需要的是
     * <b>真实文件路径</b>而不是 content URI —— 本地书源走 MediaStore 按路径前缀
     * 过滤，拿不到路径就没法筛。好在绝大多数 ROM 的 tree documentId 形如
     * {@code primary:Music/有声书}，可以还原成 /storage/emulated/0/Music/有声书；
     * 少数 ROM 用了私有 id，还原失败时前端会让用户直接粘贴路径。
     */
    public void pickLocalDir() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(it, REQ_PICK_LOCAL_DIR);
        } catch (Throwable t) {
            callJs("window.onLocalDir({ok:false,cancel:false,error:'no-picker'})");
        }
    }

    /** tree URI → 真实路径；拿不到返回 "" */
    private static String treeDocPath(android.net.Uri tree) {
        if (tree == null) return "";
        try {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(tree);
            if (docId == null) return "";
            if (docId.startsWith("primary:")) {
                String ext = android.os.Environment.getExternalStorageDirectory()
                        .getAbsolutePath();
                return ext + "/" + docId.substring("primary:".length());
            }
            // 外置 SD / U 盘：形如 "ABCD-1234:Music/有声书"
            int colon = docId.indexOf(':');
            if (colon > 0) {
                String vol = docId.substring(0, colon);
                String rest = docId.substring(colon + 1);
                return "/storage/" + vol + (rest.length() > 0 ? ("/" + rest) : "");
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * ★ v3.8.0：打开系统文件选择器，挑一个 {@code .fxbak} 备份包来恢复。
     *
     * <p>用 {@code ACTION_OPEN_DOCUMENT}，返回可读的 {@code content://} URI。
     * 真正的读取（流式，不限 2MB）在 {@code Bridge.restoreLocal} 里做 ——
     * 这里只负责把 URI 交给前端，由前端弹确认框后再触发恢复。
     */
    public void pickBackupFile() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            // 备份包用 octet-stream 更贴切；但很多文件管理器认不出自定义后缀，
            // 所以 */* 兜底，避免文件变灰不可选。
            it.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/octet-stream", "application/zip",
                    "application/x-zip-compressed", "*/*"});
            // ★ v3.9.1：要给 URI 申请**可持久**的读授权。
            //   用户选完文件后会先看确认框再点恢复，这中间 Activity 完全可能被
            //   系统回收重建（后台内存紧张、切出去再回来、横竖屏切换）。
            //   临时授权随 Activity 一起没了，于是 restoreLocal 里的
            //   openInputStream 直接抛 SecurityException —— 表现就是
            //   「点了恢复，提示失败」，而且用户怎么重试都一样。
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(it, REQ_PICK_BACKUP_FILE);
        } catch (Throwable t) {
            callJs("window.onBackupFilePicked({ok:false,cancel:false,error:'no-picker'})");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // ★ v3.8.0：本地备份目录选择（SAF 目录树）—— 与下载目录同一套范式
        if (requestCode == REQ_PICK_BACKUP_DIR) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                callJs("window.onBackupDir({ok:false,cancel:true})");
                return;
            }
            android.net.Uri tree = data.getData();
            try {
                // 持久化授权：不调这句，重启 App 后这个目录就写不了了
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable ignored) {
                // 个别 ROM 不支持持久授权，仍允许本次会话使用
            }
            String name = com.tingshu.app.core.SafOut.displayName(this, tree);
            JSONObject o = new JSONObject();
            try {
                o.put("ok", true);
                o.put("cancel", false);
                o.put("uri", tree.toString());
                o.put("name", name);
            } catch (Throwable ignored) {}
            callJs("window.onBackupDir(" + o.toString() + ")");
            return;
        }

        // ★ v3.9.1：本地书源自定义目录
        if (requestCode == REQ_PICK_LOCAL_DIR) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                callJs("window.onLocalDir({ok:false,cancel:true})");
                return;
            }
            android.net.Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
                // 不支持持久授权也不影响本次使用
            }
            String path = treeDocPath(tree);
            String name = com.tingshu.app.core.SafOut.displayName(this, tree);
            JSONObject o = new JSONObject();
            try {
                o.put("ok", true);
                o.put("cancel", false);
                o.put("path", path);
                o.put("uri", tree.toString());
                o.put("name", name == null ? "" : name);
            } catch (Throwable ignored) {}
            callJs("window.onLocalDir(" + o.toString() + ")");
            return;
        }

        // ★ v3.8.0：选一个备份包（.fxbak）来恢复
        if (requestCode == REQ_PICK_BACKUP_FILE) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                callJs("window.onBackupFilePicked({ok:false,cancel:true})");
                return;
            }
            android.net.Uri uri = data.getData();
            // ★ v3.9.1：持久化读取授权（与下载目录同一个道理）。
            //   不调这句，用户点确认框这段时间里 Activity 一旦被重建，
            //   content:// 就读不出来了 —— 恢复必然失败。
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
                // 个别 ROM 不支持持久授权：仍能在本会话内使用，不阻断
            }
            String name = fileNameOf(uri);
            JSONObject o = new JSONObject();
            try {
                o.put("ok", true);
                o.put("cancel", false);
                o.put("uri", uri.toString());
                o.put("name", name == null ? "" : name);
            } catch (Throwable ignored) {}
            callJs("window.onBackupFilePicked(" + o.toString() + ")");
            return;
        }

        // ★ v2.9：下载目录选择（SAF 目录树）
        if (requestCode == REQ_PICK_DOWNLOAD_DIR) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                callJs("window.onDownloadDir({ok:false,cancel:true})");
                return;
            }
            android.net.Uri tree = data.getData();
            try {
                // ★ 关键：持久化授权。不调这句，重启 App 后 content:// 就读不了了，
                //   已经"移动"到外部文件夹的音频会变成"下过却播不了"。
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable ignored) {
                // 个别 ROM 不支持持久授权，仍允许本次会话使用
            }
            String name = com.tingshu.app.core.SafOut.displayName(this, tree);
            com.tingshu.app.data.Settings.setDownloadTreeUri(tree.toString());
            com.tingshu.app.data.Settings.setDownloadTreeName(name);

            JSONObject o = new JSONObject();
            try {
                o.put("ok", true);
                o.put("cancel", false);
                o.put("uri", tree.toString());
                o.put("name", name);
            } catch (Throwable ignored) {}
            callJs("window.onDownloadDir(" + o.toString() + ")");
            return;
        }

        if (requestCode != REQ_PICK_SOURCE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            callJs("window.onSourceFilePicked({ok:false,cancel:true})");
            return;
        }
        android.net.Uri uri = data.getData();
        String name = fileNameOf(uri);
        String lower = name == null ? "" : name.toLowerCase();

        if (lower.endsWith(".py")) {
            // ★ v3.4：单个 Python 源文件，直接交给 Python 通道
            importPyFile(uri, name);
        } else if (lower.endsWith(".zip") || lower.endsWith(".jsplugin")) {
            // ★ v3.0：.zip 一律先按 JS 插件包探一次。只有真的含 manifest.json
            //   且声明了 scripts 的才走 JS 通道；否则退回 jar 通道
            //   （历史上有人把 jar 插件包改名成 .zip 分发，不能让那条路断掉）。
            // ★ v3.4：再退一步 —— 内含 .py 的 zip 走 Python 源码包通道
            //   （用户常把一堆源打包成 zip 一次性导入）。
            importJsFile(uri, name);
        } else if (lower.endsWith(".jar") || lower.endsWith(".dex")) {
            importJarFile(uri, name);
        } else {
            // .json / 其它：按文本读，交给前端统一导入逻辑
            String content = readTextFromUri(uri);
            JSONObject o = new JSONObject();
            try {
                if (content == null) {
                    o.put("ok", false);
                    o.put("cancel", false);
                    o.put("error", "读取文件失败");
                } else {
                    o.put("ok", true);
                    o.put("cancel", false);
                    o.put("file", name);
                    o.put("type", "json");
                    o.put("json", content);
                }
            } catch (Throwable ignored) {}
            callJs("window.onSourceFilePicked(" + o.toString() + ")");
        }
    }

    /**
     * ★ v3.0：JS 插件包导入。
     *
     * <p>先把 content:// 整个复制到私有目录（Rhino 侧要按普通文件路径读 zip），
     * 再原地探一次是不是合法 JS 插件包：
     * <ul>
     *   <li>是 → 回 {@code type:"js"}，前端调 importSourceJs</li>
     *   <li>不是 → 把临时文件当成 jar 继续（用户可能只是把 jar 改名成了 zip）</li>
     * </ul>
     * 这样对用户来说「选个 .zip 就行」，不用懂两种插件的区别。
     */
    private void importJsFile(android.net.Uri uri, String name) {
        JSONObject o = new JSONObject();
        java.io.File out = null;
        try {
            java.io.File dir = getDir("js_plugin_inbox", MODE_PRIVATE);
            String safe = com.tingshu.app.core.Utils.safe(
                    name == null ? "plugin" : name.replaceAll("\\.(zip|jsplugin)$", ""));
            out = new java.io.File(dir, safe + "-" + System.currentTimeMillis() + ".zip");

            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new IllegalStateException("无法打开文件");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                // JS 插件包按脚本体积算都不大（ShunSources 全包不到 300KB），
                // 给到 20MB 是留给将来可能内嵌音频索引的包。
                if (total > 20 * 1024 * 1024) {
                    in.close();
                    fo.close();
                    throw new IllegalStateException("插件包超过 20MB，拒绝导入");
                }
                fo.write(buf, 0, n);
            }
            in.close();
            fo.flush();
            fo.close();

            boolean isJs = com.tingshu.app.core.js.JsPluginLoader
                    .looksLikeJsPlugin(out.getAbsolutePath());
            if (isJs) {
                o.put("ok", true);
                o.put("cancel", false);
                o.put("file", name == null ? "" : name);
                o.put("type", "js");
                o.put("path", out.getAbsolutePath());
            } else if (looksLikePyZip(out)) {
                // ★ v3.4：包里含 .py —— 走 Python 源码通道
                o.put("ok", true);
                o.put("cancel", false);
                o.put("file", name == null ? "" : name);
                o.put("type", "py");
                o.put("path", out.getAbsolutePath());
            } else {
                // 不是 JS 包 —— 当作 jar 插件继续（改名分发的兼容路径）
                o.put("ok", true);
                o.put("cancel", false);
                o.put("file", name == null ? "" : name);
                o.put("type", "jar");
                o.put("path", out.getAbsolutePath());
            }
        } catch (Throwable t) {
            if (out != null) { try { out.delete(); } catch (Throwable ignored) {} }
            try {
                o.put("ok", false);
                o.put("cancel", false);
                o.put("error", "读取插件包失败：" + (t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage()));
            } catch (Throwable ignored) {}
        }
        callJs("window.onSourceFilePicked(" + o.toString() + ")");
    }

    /**
     * ★ v3.4：单个 .py 源文件导入。
     *
     * <p>Python 解释器需要真实文件路径（zipimport / importlib 都按路径走），
     * 所以先把 content:// 拷到私有目录，再把路径回给前端 → importPySources。
     */
    private void importPyFile(android.net.Uri uri, String name) {
        JSONObject o = new JSONObject();
        java.io.File out = null;
        try {
            java.io.File dir = getDir("py_source_inbox", MODE_PRIVATE);
            // ★ 落盘名要保证「有可用字符 + 有 .py 后缀 + 不与同批冲突」。
            //   Utils.safe 会把中文/方括号冲成下划线，纯中文名（如「[书]听友」）
            //   净化后只剩一串下划线，Python 侧模块名会很难看且易撞名，
            //   所以这里自己收口：剥后缀 → 净化 → 去首尾下划线 → 空则回退。
            String stem = name == null ? "source" : name.replaceAll("\\.(py|zip)$", "");
            String safe = com.tingshu.app.core.Utils.safe(stem).replaceAll("^_+|_+$", "");
            if (safe.length() == 0) safe = "src_" + Math.abs(stem.hashCode());
            if (Character.isDigit(safe.charAt(0))) safe = "s_" + safe;
            out = new java.io.File(dir, safe + "-" + System.currentTimeMillis() + ".py");

            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new IllegalStateException("无法打开文件");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                // 单个源文件正常几十 KB；给 4MB 上限防误选大文件
                if (total > 4 * 1024 * 1024) {
                    in.close();
                    fo.close();
                    throw new IllegalStateException("源文件超过 4MB，拒绝导入");
                }
                fo.write(buf, 0, n);
            }
            in.close();
            fo.flush();
            fo.close();

            o.put("ok", true);
            o.put("cancel", false);
            o.put("file", name == null ? "" : name);
            o.put("type", "py");
            o.put("path", out.getAbsolutePath());
        } catch (Throwable t) {
            if (out != null) { try { out.delete(); } catch (Throwable ignored) {} }
            try {
                o.put("ok", false);
                o.put("cancel", false);
                o.put("error", "读取 Python 源失败：" + (t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage()));
            } catch (Throwable ignored) {}
        }
        callJs("window.onSourceFilePicked(" + o.toString() + ")");
    }

    /**
     * ★ v3.4：只读 zip 中央目录，判断包里有没有 .py。
     * 不解压、不依赖 Python，几百 KB 的包毫秒级返回。
     */
    private boolean looksLikePyZip(java.io.File zip) {
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(zip);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (n.startsWith("__MACOSX/") || n.startsWith(".")) continue;
                if (n.toLowerCase().endsWith(".py")) return true;
            }
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) { try { zf.close(); } catch (Throwable ignored) {} }
        }
        return false;
    }

    /**
     * jar 插件导入：DexClassLoader 要求真实文件路径，
     * 所以先把 content:// 复制到私有 plugins 目录，再把路径回给前端。
     */
    private void importJarFile(android.net.Uri uri, String name) {
        JSONObject o = new JSONObject();
        try {
            java.io.File dir = getDir("plugins", MODE_PRIVATE);
            // 固定名字覆盖导入（同文件重复导入不堆积）；不同文件按时间戳区分
            String safe = com.tingshu.app.core.Utils.safe(
                    name == null ? "plugin" : name.replaceAll("\\.(jar|zip|dex)$", ""));
            java.io.File out = new java.io.File(dir, safe + "-" + System.currentTimeMillis() + ".jar");
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new IllegalStateException("无法打开文件");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 20 * 1024 * 1024) {
                    in.close();
                    fo.close();
                    out.delete();
                    throw new IllegalStateException("jar 超过 20MB，拒绝导入");
                }
                fo.write(buf, 0, n);
            }
            in.close();
            fo.flush();
            fo.close();

            o.put("ok", true);
            o.put("cancel", false);
            o.put("file", name == null ? "" : name);
            o.put("type", "jar");
            o.put("path", out.getAbsolutePath());
        } catch (Throwable t) {
            try {
                o.put("ok", false);
                o.put("cancel", false);
                o.put("error", "复制 jar 失败：" + (t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage()));
            } catch (Throwable ignored) {}
        }
        callJs("window.onSourceFilePicked(" + o.toString() + ")");
    }

    /** 从 content:// 读出文件名 */
    private String fileNameOf(android.net.Uri uri) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String n = c.getString(i);
                    if (n != null && n.length() > 0) return n;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (c != null) c.close();
            } catch (Throwable ignored) {}
        }
        String s = uri == null ? "" : uri.getLastPathSegment();
        return s == null ? "sources.json" : s;
    }

    /** 读取文本，上限 2MB（源配置不可能这么大，防误选大文件把内存撑爆） */
    private String readTextFromUri(android.net.Uri uri) {
        java.io.InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 2 * 1024 * 1024) return null;
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {}
        }
    }

    // ==================== 生命周期 ====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (web.canGoBack()) {
                web.goBack();
                return true;
            }
            web.evaluateJavascript(
                    "(function(){if(window.onBackPressed)return window.onBackPressed();return false})()",
                    null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 退到后台时把进度落库，避免被系统杀掉丢进度
        if (core != null) core.saveProgressNow();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 回到前台时补挂一次服务回调（服务可能被系统重启过）
        PlayerService.bindCtl(this);
        // ★ v3.7.14：封面到位回调也要跟着补挂（服务重启后 listener 会丢）
        bindCoverListener();
        // 回到前台补刷一次通知（后台期间标题/进度可能已陈旧）
        refreshNotification();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ★ v2.8：从系统「悬浮窗授权页」回来后复查一次权限，并回推给前端刷新开关。
        //   用户可能刚授权，也可能把权限关了 —— 两种都要反映到设置页。
        callJs("window.onFloatBallStateRefresh && window.onFloatBallStateRefresh(" +
                com.tingshu.app.player.FloatBallService.canDraw(this) + ")");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PlayerService.unbindCtl();
        if (core != null) {
            core.saveProgressNow();
            core.release();
        }
    }
}
