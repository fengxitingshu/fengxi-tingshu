package com.tingshu.app.core;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;

/**
 * ★ v4.9.3：播放直链自检。
 *
 * <p>「取链成功但播放失败」是网盘书最难查的一类问题：界面上只有一句
 * 「播放失败」，而真实原因可能是 403（Cookie 失效 / 直链过期）、
 * 200 但返回的是 JSON 错误页、或者干脆域名连不上。
 * 这些信息 {@code MediaPlayer.onError} 永远给不出来（它只会说 -1004）。
 *
 * <p>所以在起播前对直链做一次极轻的探测（{@code GET Range: bytes=0-1}，
 * 只取两个字节），把状态码 / Content-Type / 最终 URL 记下来：
 * <ul>
 *   <li>明显失效（401/403/404/410）→ 调用方可以就地重新取一次链；</li>
 *   <li>播放仍然失败 → 把探测结果附在失败原因里，用户截图即可定位。</li>
 * </ul>
 *
 * <p><b>不阻断播放</b>：探测失败只是"提供线索"，绝不据此拒绝起播——
 * 有些 CDN 对 Range 请求的处理很怪，但正常 GET 是好的。
 */
public final class AudioProbe {

    public static final class Result {
        /** HTTP 状态码；0 表示连不上 */
        public int code;
        public String contentType = "";
        /** 文件总长（Content-Range 的总数或 Content-Length）；-1 未知 */
        public long length = -1;
        public String finalUrl = "";
        public String error = "";

        public boolean httpOk() {
            return code >= 200 && code < 400;
        }

        /** 返回的是不是音频（Content-Type 缺失时按"是"处理，不误杀） */
        public boolean looksAudio() {
            if (contentType == null || contentType.length() == 0) return true;
            String ct = contentType.toLowerCase(Locale.US);
            if (ct.startsWith("audio/")) return true;
            if (ct.contains("video/")) return true;              // mp4/m4a 容器
            if (ct.contains("octet-stream")) return true;        // 大多数 CDN 的默认值
            if (ct.contains("binary")) return true;
            if (ct.contains("mpegurl")) return true;             // m3u8
            return false;
        }

        public boolean healthy() {
            return httpOk() && looksAudio();
        }

        /** 一行诊断文本，直接拼进失败原因给用户看 */
        public String brief() {
            if (code == 0) {
                return "直链连不上" + (error.length() > 0 ? "（" + error + "）" : "");
            }
            StringBuilder sb = new StringBuilder("直链 HTTP ").append(code);
            if (contentType != null && contentType.length() > 0) {
                sb.append(' ').append(contentType);
            }
            if (!looksAudio()) sb.append("（返回的不是音频）");
            if (length > 0) sb.append("，约 ").append(length / 1024 / 1024).append("MB");
            return sb.toString();
        }
    }

    private AudioProbe() {}

    /** 最近一次探测结论（直链 → 一行摘要），供播放失败时拼进原因 */
    private static final java.util.Map<String, String> NOTES =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, String>() {
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<String, String> e) {
                            return size() > 16;
                        }
                    });

    /** 记下某条直链的自检结论 */
    public static void note(String url, String brief) {
        if (url == null || brief == null || brief.length() == 0) return;
        try { NOTES.put(url, brief); } catch (Throwable ignored) {}
    }

    /** 取某条直链的自检结论（没有则返回 null） */
    public static String noteOf(String url) {
        if (url == null) return null;
        try { return NOTES.get(url); } catch (Throwable t) { return null; }
    }

    /**
     * 探测直链。调用方必须在后台线程调用（会阻塞几百毫秒）。
     *
     * @param url     音频直链
     * @param headers 播放时要带的请求头（Cookie / Referer / UA）
     */
    public static Result check(String url, Map<String, String> headers) {
        Result r = new Result();
        r.finalUrl = url == null ? "" : url;
        if (url == null || !url.startsWith("http")) {
            r.error = "不是 http 直链";
            return r;
        }
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            int hop = 0;
            while (true) {
                conn = (HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Range", "bytes=0-1");
                conn.setRequestProperty("Accept", "*/*");
                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null) continue;
                        try { conn.setRequestProperty(e.getKey(), e.getValue()); } catch (Throwable ignored) {}
                    }
                }
                if (conn.getRequestProperty("User-Agent") == null) {
                    conn.setRequestProperty("User-Agent", Net.UA);
                }
                int code = conn.getResponseCode();
                if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308)
                        && hop < 3) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null && loc.length() > 0) {
                        u = new URL(u, loc);
                        r.finalUrl = u.toString();
                        conn.disconnect();
                        hop++;
                        continue;
                    }
                }
                r.code = code;
                String ct = conn.getContentType();
                r.contentType = ct == null ? "" : ct;
                String cr = conn.getHeaderField("Content-Range");
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash > 0) {
                        try { r.length = Long.parseLong(cr.substring(slash + 1).trim()); }
                        catch (Throwable ignored) {}
                    }
                }
                if (r.length < 0) {
                    long cl = conn.getContentLength();
                    if (cl >= 0) r.length = cl;
                }
                // 只读两个字节就断开，绝不把整首歌拉下来
                try {
                    java.io.InputStream in = conn.getInputStream();
                    byte[] b = new byte[2];
                    in.read(b);
                    in.close();
                } catch (Throwable ignored) {}
                return r;
            }
        } catch (Throwable t) {
            r.code = 0;
            r.error = shortMsg(t);
            return r;
        } finally {
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String shortMsg(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        if (m.length() > 60) m = m.substring(0, 60);
        return m;
    }
}
