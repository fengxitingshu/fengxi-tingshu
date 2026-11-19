package com.fengxi.fnplayer.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v2.19 全局崩溃守卫；v2.25 增加崩溃日志落盘。
 *
 * <p>点击海报 / 导入配置后「闪退」的常见来源不是主线程，而是后台线程里逃逸的
 * 异常（探测直链抛 MalformedURLException、爬虫 jar 抛 Error 等）——任何线程
 * 的未捕获异常默认都会杀掉整个进程。这里安装默认未捕获异常处理器：
 * <ul>
 *   <li>后台线程崩溃 → 写日志 + 弹 toast，进程保活，界面照常可用；</li>
 *   <li>主线程崩溃 → 先写日志再交回系统默认处理（吞掉会让主线程假死黑屏，比闪退更糟）。</li>
 * </ul>
 *
 * <p>日志落在应用私有 files/crash.log（不入库、不上传、不含任何地址/凭据），
 * 用户可在「我的 → 复制崩溃日志」里取出来自查。
 */
public final class CrashGuard {

    private static boolean installed;
    private static final long MAX_LOG = 96 * 1024L;

    private CrashGuard() {}

    public static void install(Context ctx) {
        if (installed) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(final Thread t, final Throwable e) {
                try {
                    append(app, t, e);          // 主线程也要先落盘，否则崩完查不到原因
                    android.util.Log.e("FxCrashGuard", "线程 " + t.getName() + " 未捕获异常", e);
                    if (t == Looper.getMainLooper().getThread()) {
                        if (prev != null) prev.uncaughtException(t, e);
                        else System.exit(2);
                        return;
                    }
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

    private static File logFile(Context ctx) {
        return new File(ctx.getFilesDir(), "crash.log");
    }

    private static synchronized void append(Context ctx, Thread t, Throwable e) {
        FileOutputStream fo = null;
        try {
            File f = logFile(ctx);
            if (f.exists() && f.length() > MAX_LOG) f.delete();   // 只保留最近一段
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            pw.flush();
            String head = "==== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                    .format(new Date()) + " / " + (t == null ? "?" : t.getName()) + " ====\n";
            fo = new FileOutputStream(f, true);
            fo.write((head + sw.toString() + "\n").getBytes("UTF-8"));
            fo.flush();
        } catch (Throwable ignored) {
        } finally {
            if (fo != null) try { fo.close(); } catch (Throwable ignored) {}
        }
    }

    /** 供「复制崩溃日志」读取；没有记录时返回空串。 */
    public static String read(Context ctx) {
        java.io.FileInputStream fi = null;
        try {
            File f = logFile(ctx);
            if (!f.exists()) return "";
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            fi = new java.io.FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (fi != null) try { fi.close(); } catch (Throwable ignored) {}
        }
    }

    public static void clear(Context ctx) {
        try { logFile(ctx).delete(); } catch (Throwable ignored) {}
    }
}
