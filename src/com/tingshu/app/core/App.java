package com.tingshu.app.core;

import android.app.Application;
import android.content.Context;

import com.tingshu.app.data.Settings;
import com.tingshu.app.player.PlayState;
import com.tingshu.app.source.SourceRegistry;

/**
 * 全局 Application。持有全局 Context，启动时初始化各单例。
 * 必须在 AndroidManifest 里声明 android:name=".core.App"，否则 onCreate 不会执行。
 */
public class App extends Application {

    private static Context CTX;

    @Override
    public void onCreate() {
        super.onCreate();
        CTX = this;
        // ★ v3.9.5：崩溃处理器必须**最先**装 —— 原来它在 App.onCreate 里、
        //   但排在它后面的任何一步（Settings.init / Net.loadCookies /
        //   SourceRegistry.registerAll / PythonHost…）一旦抛异常，
        //   进程当场死，用户看到的就是「点图标闪一下就没」、且**没有任何日志**。
        //   现在把它提到最前，并额外写一份到外部可访问目录（见 Crash.installEarly），
        //   这样即使 App.onCreate 本身炸了也能留下堆栈。
        Crash.installEarly(this);
        // ★ v3.9.5：下面每一步初始化都单独 try-catch。
        //   这些初始化任何一步抛异常，进程都会当场死 —— 用户看到的就是
        //   「点图标闪一下就没」。隔离后，单点失败只影响对应功能，
        //   首页照常能开（能在里面看到"某源不可用"总比完全打不开强）。
        //   注意：这里必须 catch Throwable（含 NoClassDefFoundError 等 Error），
        //   不能只 catch Exception。
        try { Settings.init(this); } catch (Throwable t) { logInit("Settings", t); }
        try { Net.loadCookies(Settings.sp()); } catch (Throwable t) { logInit("Net", t); }
        try { PlayState.init(this); } catch (Throwable t) { logInit("PlayState", t); }
        // ★ v3.7.9：先把「非 Python 源」同步登记 —— JSON 配置源、jar 插件源、
        //   JS 插件源都很快（毫秒级），必须保证首屏"打开就有源"。
        //   Python（.py）源要等 Chaquopy 解压 13MB assets，绝不能在这一步等，
        //   见下面的异步补齐。
        try { SourceRegistry.registerAll(); } catch (Throwable t) { logInit("SourceRegistry", t); }
        // ★ v3.7.9：Python 运行时的启动同样不能在 onCreate（主线程）里做 ——
        //   原实现是 available() → ensureStarted() 当场解压 assets，
        //   冷启动主线程被钉住数秒，用户看到"第一次打开没有书源，等很久才出来"。
        //   现在只挂一个就绪回调：解释器在后台线程慢慢起，起好了喊我们一声，
        //   那时再 reload 一次源表，把 .py 源补登记，并推给前端。
        try {
            com.tingshu.app.python.PythonHost.setReadyListener(
                    new com.tingshu.app.python.PythonHost.ReadyListener() {
                        public void onReady(final boolean pythonOk) {
                            if (!pythonOk) return;   // 启动失败：保持现状，Python 源不可用
                            if (!com.tingshu.app.source.SourceRegistry.pythonPending()) return;
                            // reload 会做磁盘 + 引擎调用，丢后台线程，别占主线程
                            com.tingshu.app.source.Aggregator.singlePool().execute(new Runnable() {
                                public void run() {
                                    try {
                                        com.tingshu.app.source.SourceRegistry.reload();
                                    } catch (Throwable ignored) {}
                                }
                            });
                        }
                    });
            // 主动催一次后台启动（本调用立即返回，不阻塞）
            com.tingshu.app.python.PythonHost.available();
        } catch (Throwable t) { logInit("PythonHost", t); }
        try { CacheManager.init(this); } catch (Throwable t) { logInit("CacheManager", t); }
        try { DownloadManager.init(this); } catch (Throwable t) { logInit("DownloadManager", t); }
        try { com.tingshu.app.data.Db.init(this); } catch (Throwable t) { logInit("Db", t); }
        // ★ v3.9.0：恢复上次设置的定时关闭（进程被杀后倒计时不能丢）
        try {
            com.tingshu.app.player.SleepTimer.restore();
        } catch (Throwable ignored) {}
        // 清理上次异常退出留下的半成品缓存
        try {
            com.tingshu.app.data.CacheRepo.purgeUnfinished(this);
        } catch (Throwable ignored) {}
        // ★ v2.9：下载的半成品也要清（只清 status != DONE 的，
        //   已完成的下载永不动 —— 那是用户主动要留下的）
        try {
            com.tingshu.app.data.DownloadRepo.purgeUnfinished(this);
        } catch (Throwable ignored) {}

        // ★ v3.2：启动自动验证已移除。
        //   原因：冷启动时同时跑十几个源的真实网络请求，会把首屏拖到十几秒，
        //   用户观感就是「一打开就卡」。改为完全手动的「批量验证」——
        //   需要时在书源管理页点一下，进度走顶部非阻塞条，不影响别的操作。
    }

    public static Context ctx() {
        return CTX;
    }

    /**
     * ★ v3.9.5：初始化步骤的非致命异常记录。
     *
     * <p>某个 init 抛异常时不再让进程直接死，而是把堆栈写进崩溃日志
     * （私有目录 + 外部目录各一份），用户能在「我的 → 崩溃日志」或者
     * 文件管理里看到，我们据此定位到底是哪一步炸了。
     */
    private static void logInit(String step, Throwable t) {
        try {
            Crash.record(CTX, step, t);
        } catch (Throwable ignored) {}
    }
}
