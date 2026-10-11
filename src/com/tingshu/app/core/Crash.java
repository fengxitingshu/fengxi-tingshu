package com.tingshu.app.core;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局未捕获异常处理。
 *
 * 目的：手工构建的包没有崩溃上报，出问题只能靠 logcat。
 * 这里把堆栈写到 filesDir/crash.log，方便在「我的 → 崩溃日志」里直接看。
 * 注意：只记录，不吞异常 —— 记完仍然交给系统默认处理（否则状态不一致更糟）。
 */
public final class Crash {

    private static final String FILE = "crash.log";
    private static volatile boolean installed;

    private Crash() {}

    public static void install(final Context ctx) {
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    write(ctx, t, e);
                } catch (Throwable ignored) {}
                if (def != null) def.uncaughtException(t, e);
            }
        });
    }

    private static void write(Context ctx, Thread t, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date()));
        pw.println("线程: " + t.getName());
        pw.println("设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " / Android " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        pw.println("----------------------------------------");
        e.printStackTrace(pw);
        pw.flush();

        File f = logFile(ctx);
        // 只保留最近 3 次崩溃，防止文件无限膨胀
        try {
            if (f.exists() && f.length() > 64 * 1024) f.delete();
        } catch (Throwable ignored) {}

        FileWriter w = null;
        try {
            w = new FileWriter(f, true);
            w.write(sw.toString());
            w.write("\n\n");
        } catch (Throwable ignored) {
        } finally {
            try { if (w != null) w.close(); } catch (Throwable ignored) {}
        }
    }

    public static File logFile(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    /** 读崩溃日志给「我的」页显示；没有则返回空串 */
    public static String read(Context ctx) {
        try {
            File f = logFile(ctx);
            if (!f.exists()) return "";
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return "";
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    public static void clear(Context ctx) {
        try {
            File f = logFile(ctx);
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {}
    }
}
