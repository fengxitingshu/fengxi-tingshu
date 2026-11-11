package com.fengxi.fnplayer.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * v2.19：全局崩溃守卫。
 *
 * <p>点击海报后「闪退」的最常见来源不是主线程，而是后台线程任务里逃逸的
 * 异常（探测直链抛 MalformedURLException、爬虫 jar 抛 Error 等）——任何线程
 * 的未捕获异常默认都会杀掉整个进程。这里安装默认未捕获异常处理器：
 * <ul>
 *   <li>后台线程崩溃 → 记录日志 + 弹 toast，进程保活，界面照常可用；</li>
 *   <li>主线程崩溃 → 交回系统默认处理（吞掉会让主线程假死黑屏，比闪退更糟）。</li>
 * </ul>
 */
public final class CrashGuard {

    private static boolean installed;

    private CrashGuard() {}

    public static void install(Context ctx) {
        if (installed) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(final Thread t, final Throwable e) {
                try {
                    android.util.Log.e("FxCrashGuard", "线程 " + t.getName() + " 未捕获异常", e);
                    if (t == Looper.getMainLooper().getThread()) {
                        // 主线程：交回系统（吞掉会导致黑屏假死）
                        if (prev != null) prev.uncaughtException(t, e);
                        else System.exit(2);
                        return;
                    }
                    // 后台线程：进程保活，提示用户
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() {
                            try {
                                Toast.makeText(app, "操作出错了，请重试（"
                                        + com.fengxi.fnplayer.core.Utils.brief(e) + "）",
                                        Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable ignored) {
                    if (prev != null) prev.uncaughtException(t, e);
                }
            }
        });
    }
}
