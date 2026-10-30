package com.tingshu.app.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tingshu.app.core.Net;
import com.tingshu.app.core.PlayerLog;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * WMA → MP3 本地转码通道（★ v4.10.1 改为<b>反射调用</b> mobile-ffmpeg）。
 *
 * <p>为什么反射：完整 FFmpeg（mobile-ffmpeg，含 WMA 解码 + libmp3lame）会让
 * APK 膨胀约 24MB。v4.10.1 起构建分两档：
 * <ul>
 *   <li><b>标准版</b>：不打 FFmpeg so —— APK 恢复 ~24MB，WMA 尝试直连解码
 *       （部分机型系统/ijk 能解），解不了时明确提示装完整版；</li>
 *   <li><b>完整版</b>：打入 FFmpeg so —— WMA 首播自动转码，全机型可播。</li>
 * </ul>
 * 代码只有一份，通过 Class.forName 探测库是否存在，两种包都能编译运行。
 *
 * <p>流程：下载 WMA 到 cache → FFmpeg 转码 64k MP3 → 播本地文件；
 * 产物按 URL 哈希缓存，同一集第二次播放零等待。
 */
public final class WmaTrans {

    /** 转码完成/失败回调（主线程）；onStatus 用于展示阶段进度 */
    public interface Cb {
        void onReady(String mp3Path);
        void onFail(String why);
        void onStatus(String msg);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 完整 FFmpeg 库是否在包里（反射探测，标准版为 false） */
    private static final boolean LIB;
    private static Method FF_EXECUTE;          // FFmpeg.execute(String[])
    private static final StringBuilder LASTLOG = new StringBuilder();
    private static Method CFG_LOG_LEVEL;       // Config.setLogLevel(Level)
    private static Object LEVEL_ERROR;         // Level.AV_LOG_ERROR

    static {
        boolean ok = false;
        try {
            Class<?> ff = Class.forName("com.arthenica.mobileffmpeg.FFmpeg");
            Class<?> cfg = Class.forName("com.arthenica.mobileffmpeg.Config");
            Class<?> level = Class.forName("com.arthenica.mobileffmpeg.Level");
            FF_EXECUTE = ff.getMethod("execute", String[].class);
            CFG_LOG_LEVEL = cfg.getMethod("setLogLevel", level);
            LEVEL_ERROR = level.getField("AV_LOG_ERROR").get(null);
            cfg.getMethod("enableRedirection").invoke(null);
            CFG_LOG_LEVEL.invoke(null, LEVEL_ERROR);
            // ★ v4.10.9：收集错误日志尾部（转码失败时带给用户，便于定位）
            try {
                final Class<?> lcb = Class.forName("com.arthenica.mobileffmpeg.LogCallback");
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                        lcb.getClassLoader(), new Class[]{lcb},
                        new java.lang.reflect.InvocationHandler() {
                            public Object invoke(Object pr, Method m, Object[] a) {
                                if ("apply".equals(m.getName()) && a != null && a.length > 0) {
                                    try {
                                        String text = (String) a[0].getClass()
                                                .getMethod("getText").invoke(a[0]);
                                        synchronized (LASTLOG) {
                                            if (LASTLOG.length() > 1200) LASTLOG.setLength(0);
                                            LASTLOG.append(text).append('\n');
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                return null;
                            }
                        });
                cfg.getMethod("enableLogCallback", lcb).invoke(null, proxy);
            } catch (Throwable ignored) {}
            ok = true;
        } catch (Throwable ignored) {
            ok = false;
        }
        LIB = ok;
    }

    private WmaTrans() {}

    /** 完整版才有转码能力 */
    public static boolean libraryAvailable() { return LIB; }

    /**
     * 判断是否 WMA/ASF。网盘直链的真实扩展名常在 query 里
     * （…/download?…file_name=xxx.wma…），所以整串小写后找 ".wma"/".asf"。
     */
    public static boolean isWma(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains(".wma") || u.contains(".asf");
    }

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "wmaconv");
        if (!d.exists()) d.mkdirs();
        // ★ v4.10.6：版本标记 —— App 升级后清空旧转码缓存，
        //   防止历史版本转出的坏文件一直命中缓存（真机曾发生）
        File mark = new File(d, ".ver-4.10.6");
        if (!mark.exists()) {
            File[] old = d.listFiles();
            if (old != null) for (File f : old) f.delete();
            try { mark.createNewFile(); } catch (Throwable ignored) {}
        }
        return d;
    }

    /** ★ v4.10.6：下载到的源文件必须是真音频（WMA/ASF 或 WAV 头），防把错误页当音频转 */
    private static boolean looksAudio(File f) {
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            byte[] b = new byte[16];
            int n = raf.read(b);
            if (n < 16) return false;
            // ASF/WMV 头 GUID: 30 26 B2 75 8E 66 CF 11 ...
            if ((b[0] & 0xFF) == 0x30 && (b[1] & 0xFF) == 0x26 && (b[2] & 0xFF) == 0xB2
                    && (b[3] & 0xFF) == 0x75) return true;
            // RIFF....WAVE
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') return true;
            // MP3（ID3 或帧同步）
            if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') return true;
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF & 0xE0) == 0xE0) return true;
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
        }
    }

    private static String keyOf(String url) {
        return "t" + Integer.toHexString(url.hashCode());
    }

    /** 已转好的 MP3（存在且有效就免转码，实现二次播放秒开） */
    public static File cachedMp3(Context c, String url) {
        File f = new File(dir(c), keyOf(url) + ".mp3");
        return f.length() > 1024 ? f : null;
    }

    /**
     * 下载 + 转码。完成后删源文件，cache 由系统按需回收。
     * onReady/onFail 保证在主线程回调。
     */
    public static void convert(final Context ctx, final String url,
                               final Map<String, String> headers, final Cb cb) {
        final Context app = ctx.getApplicationContext();
        PlayerLog.log("转码convert 开始 url=" + url.substring(0, Math.min(90, url.length()))
                + " LIB=" + LIB);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    File out = new File(dir(app), keyOf(url) + ".mp3");
                    if (out.length() > 1024) {
                        deliver(out.getAbsolutePath(), null, cb);
                        return;
                    }
                    if (!LIB) {
                        PlayerLog.log("转码失败：无 FFmpeg 库（标准版）");
                        deliver(null, "标准版不含 WMA 转码组件，本机也无法直解 WMA —— "
                                + "请到下载页安装「完整版」，或换 MP3 格式的书", cb);
                        return;
                    }
                    File in = new File(dir(app), keyOf(url) + ".wma");
                    if (in.length() < 1024) {
                        in.delete();
                        deliverStatus("下载中 0%", cb);
                        Net.DlResult r = Net.download(url, null, headers, in,
                                new Net.Progress() {
                                    int lastPct = -10;
                                    public void onProgress(long done, long total) {
                                        if (total <= 0) return;
                                        int pct = (int) (done * 100 / total);
                                        if (pct - lastPct >= 10) {
                                            lastPct = pct - pct % 10;
                                            deliverStatus("下载中 " + lastPct + "%", cb);
                                        }
                                    }
                                }, null);
                        if (!r.ok || in.length() < 1024) {
                            in.delete();
                            PlayerLog.log("转码下载失败：" + r.error);
                            deliver(null, "下载失败：" + (r.error == null ? "无响应" : r.error), cb);
                            return;
                        }
                        PlayerLog.log("转码下载完成 " + in.length() / 1024 + "KB");
                        if (!looksAudio(in)) {
                            long sz = in.length();
                            in.delete();
                            PlayerLog.log("源文件头校验失败（非音频）" + sz / 1024 + "KB");
                            deliver(null, "下载到的不是音频文件（" + sz / 1024 + "KB，直链可能已失效），请重试", cb);
                            return;
                        }
                        deliverStatus("转码中（约半分钟，请稍候）", cb);
                    }
                    // 语音 64k MP3 足够；-vn 防 WMA 自带封面图干扰 mp3 封装
                    String[] cmd = {"-y", "-i", in.getAbsolutePath(), "-vn",
                            "-c:a", "libmp3lame", "-b:a", "64k",
                            out.getAbsolutePath()};
                    Integer rc = (Integer) FF_EXECUTE.invoke(null, (Object) cmd);
                    PlayerLog.log("FFmpeg rc=" + rc);
                    in.delete();
                    // ★ v4.10.9：rc 成功且输出真的是 MP3（防坏输出进缓存）
                    boolean success = (rc != null && rc.intValue() == 0)
                            && out.length() > 1024 && looksAudio(out);
                    if (success) {
                        PlayerLog.log("转码成功 " + out.length() / 1024 + "KB");
                        deliver(out.getAbsolutePath(), null, cb);
                    } else {
                        out.delete();
                        String log;
                        synchronized (LASTLOG) {
                            log = LASTLOG.length() > 200
                                    ? LASTLOG.substring(LASTLOG.length() - 200) : LASTLOG.toString();
                        }
                        log = log.trim().replaceAll("\\s+", " ");
                        deliver(null, "转码失败（rc=" + rc + "）"
                                + (log.length() > 0 ? " " + log : "") + "，可重试或反馈", cb);
                    }
                } catch (Throwable e) {
                    deliver(null, "转码异常：" + e.getClass().getSimpleName()
                            + (e.getMessage() != null ? " " + e.getMessage() : ""), cb);
                }
            }
        }, "wma-trans");
        t.setDaemon(true);
        t.start();
    }

    private static void deliverStatus(final String msg, final Cb cb) {
        MAIN.post(new Runnable() {
            public void run() { if (cb != null) cb.onStatus(msg); }
        });
    }

    private static void deliver(final String path, final String err, final Cb cb) {
        MAIN.post(new Runnable() {
            public void run() {
                if (cb == null) return;
                if (path != null) cb.onReady(path);
                else cb.onFail(err == null ? "未知原因" : err);
            }
        });
    }
}
