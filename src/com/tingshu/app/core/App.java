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
        Crash.install(this);
        Settings.init(this);
        Net.loadCookies(Settings.sp());
        PlayState.init(this);
        // ★ v3.7.9：先把「非 Python 源」同步登记 —— JSON 配置源、jar 插件源、
        //   JS 插件源都很快（毫秒级），必须保证首屏"打开就有源"。
        //   Python（.py）源要等 Chaquopy 解压 13MB assets，绝不能在这一步等，
        //   见下面的异步补齐。
        SourceRegistry.registerAll();
        // ★ v3.7.9：Python 运行时的启动同样不能在 onCreate（主线程）里做 ——
        //   原实现是 available() → ensureStarted() 当场解压 assets，
        //   冷启动主线程被钉住数秒，用户看到"第一次打开没有书源，等很久才出来"。
        //   现在只挂一个就绪回调：解释器在后台线程慢慢起，起好了喊我们一声，
        //   那时再 reload 一次源表，把 .py 源补登记，并推给前端。
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
        CacheManager.init(this);
        DownloadManager.init(this);
        com.tingshu.app.data.Db.init(this);
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
}
