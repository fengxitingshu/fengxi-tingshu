package com.tingshu.app.core;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;

/**
 * ★ v4.19.0：下载转码通道 —— 设置里选了「转为 MP3」且下载到的源不是 MP3 时，
 * 用 mobile-ffmpeg（复用 v4.10.1 引入的完整版 FFmpeg，含 libmp3lame）统一转
 * 128k MP3。
 *
 * <p>与 {@link com.tingshu.app.player.WmaTrans} 一样走<b>反射调用</b>：
 * 标准版（不含 FFmpeg so）里 {@link #available()} 为 false，下载保持原始格式、
 * 不报错 —— 转码是锦上添花，不能因为标准版就下载失败。
 *
 * <p>不做缓存/二次转码判断：下载文件是一次性产物，转完即用，
 * 失败就保留原文件继续后续流程（移动 / 转存），对用户透明。
 */
public final class DlTrans {

    private static final boolean LIB;
    private static Method FF_EXECUTE;          // FFmpeg.execute(String[])
    /** ★ v4.19.1：收集 FFmpeg 日志尾部 —— 转码静默失败时给出可诊断的原因 */
    private static final StringBuilder LASTLOG = new StringBuilder();

    static {
        boolean ok = false;
        try {
            Class<?> ff = Class.forName("com.arthenica.mobileffmpeg.FFmpeg");
            FF_EXECUTE = ff.getMethod("execute", String[].class);
            Class<?> cfg = Class.forName("com.arthenica.mobileffmpeg.Config");
            cfg.getMethod("enableRedirection").invoke(null);
            Class<?> level = Class.forName("com.arthenica.mobileffmpeg.Level");
            cfg.getMethod("setLogLevel", level)
               .invoke(null, level.getField("AV_LOG_ERROR").get(null));
            // 日志回调：只存尾部 800 字符，供 lastError() 诊断
            try {
                final Class<?> lcb = Class.forName("com.arthenica.mobileffmpeg.LogCallback");
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                        lcb.getClassLoader(), new Class[]{lcb},
                        new java.lang.reflect.InvocationHandler() {
                            public Object invoke(Object pr, java.lang.reflect.Method m, Object[] a) {
                                if ("apply".equals(m.getName()) && a != null && a.length > 0) {
                                    try {
                                        String text = (String) a[0].getClass()
                                                .getMethod("getText").invoke(a[0]);
                                        synchronized (LASTLOG) {
                                            if (LASTLOG.length() > 800) LASTLOG.setLength(0);
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

    private DlTrans() {}

    /** 完整版（带 FFmpeg so）才有转码能力 */
    public static boolean available() { return LIB; }

    /** ★ v4.19.1：最近一次转码的 FFmpeg 错误日志尾部（诊断用） */
    public static String lastError() {
        synchronized (LASTLOG) {
            return LASTLOG.length() == 0 ? "无 FFmpeg 日志（库不可用或未执行）" : LASTLOG.toString();
        }
    }

    /**
     * 文件头魔数判断是不是 MP3。
     * ★ v4.19.1 修复：旧版只看头 4 字节，带 ID3v2 标签的 m4a/aac 会被误判成
     * MP3 → 跳过转码 → 用户看到「转 MP3 无效」。现在先排除常见容器魔数
     * （MP4/M4A、Ogg、FLAC、WAV、ASF/WMA），ID3 头则剥掉标签后继续找
     * MPEG 帧同步字；判断不了的一律按「不是 MP3」处理 —— 宁可多转一次，
     * 也不漏转（转码失败也有保留原格式的兜底，无害）。
     */
    public static boolean isMp3(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] b = new byte[16];
            int n = raf.read(b);
            if (n < 12) return false;
            if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return false; // MP4/M4A
            if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return false; // Ogg
            if (b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') return false; // FLAC
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') return false; // WAV
            if ((b[0] & 0xFF) == 0x30 && (b[1] & 0xFF) == 0x26
                    && (b[2] & 0xFF) == 0xB2 && (b[3] & 0xFF) == 0x75) return false;    // ASF/WMA
            if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
                // ID3v2：6-9 字节 syncsafe 标签长度，跳过后再找 MPEG 帧
                long sz = ((b[6] & 0x7F) << 21) | ((b[7] & 0x7F) << 14)
                        | ((b[8] & 0x7F) << 7) | (b[9] & 0x7F);
                raf.seek(10 + sz);
                byte[] h = new byte[4];
                if (raf.read(h) < 4) return false;
                return mpegFrame(h);
            }
            return mpegFrame(b);
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
        }
    }

    /** MPEG 音频帧同步字，刻意收紧到 Layer III，避免把 AAC ADTS 误判成 MP3 */
    private static boolean mpegFrame(byte[] b) {
        if ((b[0] & 0xFF) != 0xFF || (b[1] & 0xE0) != 0xE0) return false;
        int layer = (b[1] >> 1) & 0x03;   // 01 = Layer III
        int ver = (b[1] >> 3) & 0x03;     // 11 = MPEG1, 10 = MPEG2/2.5
        return (ver == 0x03 || ver == 0x02) && layer == 0x01;
    }

    /** 同名 .mp3 输出路径（c00001.m4a → c00001.mp3） */
    public static File mp3Of(File src) {
        String name = src.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return new File(src.getParentFile(), name + ".mp3");
    }

    /**
     * 转码为 128k MP3（语音/有声书足够清晰）。
     * 成功：删除源文件，返回 MP3 文件；失败/无库：删除半成品，返回 null
     * （调用方继续用原文件，不影响下载完成状态）。
     */
    public static File toMp3(File src) {
        if (!LIB || src == null || !src.isFile() || src.length() < 1024) return null;
        File out = mp3Of(src);
        try {
            // -vn：去掉内嵌封面流，防 m4a 封面干扰 mp3 封装
            String[] cmd = {"-y", "-i", src.getAbsolutePath(), "-vn",
                    "-c:a", "libmp3lame", "-b:a", "128k",
                    out.getAbsolutePath()};
            Integer rc = (Integer) FF_EXECUTE.invoke(null, (Object) cmd);
            boolean ok = (rc != null && rc.intValue() == 0)
                    && out.isFile() && out.length() > 1024 && isMp3(out);
            if (ok && src.delete()) return out;
            out.delete();      // 转坏 / 源删不掉：撤掉产物，保留源
            return null;
        } catch (Throwable t) {
            try { out.delete(); } catch (Throwable ignored) {}
            return null;
        }
    }
}
