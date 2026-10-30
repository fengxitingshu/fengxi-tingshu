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

    /**
     * ★ v3.9.5：最早期安装 + 双写。
     *
     * <p>背景：用户反馈「装好后点图标闪一下就没」，且私有目录里没有任何
     * crash 文件。原因是原实现把 {@link #install} 排在 {@code App.onCreate}
     * 的中段，而崩溃发生在它**之前或同期**的初始化步骤里 —— 处理器还没挂上，
     * 进程已经死了。
     *
     * <p>本方法做两件事：
     * <ol>
     *   <li>在 {@code Application.onCreate} 的第一行就挂处理器（越早越好）；</li>
     *   <li>除私有目录 {@code filesDir/crash.log} 外，**再写一份到外部目录**
     *       {@code /sdcard/Android/data/&lt;包名&gt;/files/crash.log}（不必申请
     *       任何权限，现代 Android 对该沙箱目录免权限可读写），
     *       用户用「文件管理」就能直接找到并把它发出来。</li>
     * </ol>
     * 仍然「只记录、不吞异常」，记完照常交给系统默认流程。
     */
    public static void installEarly(final Context ctx) {
        install(ctx);
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try { write(app, t, e); } catch (Throwable ignored) {}
                try { writeExternal(app, t, e); } catch (Throwable ignored) {}
                if (def != null) def.uncaughtException(t, e);
            }
        });
    }

    /** 外部存储（应用专属沙箱，免权限）里的崩溃日志文件；拿不到外部目录时返回 null */
    public static File externalLogFile(Context ctx) {
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext == null) return null;
            return new File(ext, FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeExternal(Context ctx, Thread t, Throwable e) {
        File f = externalLogFile(ctx);
        if (f == null) return;
        FileWriter w = null;
        try {
            w = new FileWriter(f, true);
            w.write(header(t));
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            pw.flush();
            w.write(sw.toString());
            w.write("\n\n");
        } catch (Throwable ignored) {
        } finally {
            try { if (w != null) w.close(); } catch (Throwable ignored) {}
        }
    }

    private static String header(Thread t) {
        return "时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date()) + "\n"
                + "线程: " + t.getName() + "\n"
                + "设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " / Android " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")\n"
                + "----------------------------------------\n";
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

    /**
     * ★ v3.9.5：记录「非致命」的初始化异常（不结束进程）。
     *
     * <p>与 {@link #write} 的区别：那个用于未捕获异常（记完即崩），
     * 这个用于被 {@code App.onCreate} 隔离捕获的初始化失败 —— 记一笔堆栈
     * 方便定位，但进程继续跑。
     */
    public static void record(Context ctx, String step, Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date()));
        pw.println("类型: 初始化失败（已隔离，未闪退）");
        pw.println("步骤: " + step);
        pw.println("----------------------------------------");
        t.printStackTrace(pw);
        pw.flush();
        String text = sw.toString();

        try {
            FileWriter w = new FileWriter(new File(ctx.getFilesDir(), FILE), true);
            w.write(text);
            w.write("\n\n");
            w.close();
        } catch (Throwable ignored) {}

        File ext = externalLogFile(ctx);
        if (ext != null) {
            try {
                FileWriter w = new FileWriter(ext, true);
                w.write(text);
                w.write("\n\n");
                w.close();
            } catch (Throwable ignored) {}
        }
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
