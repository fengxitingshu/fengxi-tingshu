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
        SourceRegistry.registerAll();
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
