package com.tingshu.app.core;

import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * HTTP 客户端。零第三方依赖，只用 HttpURLConnection。
 *
 * 能力：GET/POST、自定义头、Cookie 保持（可持久化）、gzip、GBK/UTF-8 自动判编码、
 *       二进制、流式下载（进度回调 + 断点续传 + 可取消）、HEAD 读 Location、失败重试。
 */
public final class Net {

    public static final String UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36";

    public static final String UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36";

    /**
     * 超时分级：不同用途对"慢"的容忍度完全不同。
     *
     * <p>v1.9 起默认值再收一档。原因是「点一本书半天不出内容」里，
     * 真正难受的不是总时长，而是**首字节迟迟不来**：用户盯着转圈，
     * 8s 和 5s 的体感差别巨大。而页面本身（详情/列表/搜索）只要源是活的，
     * 99% 都在 2 秒内返回，把上限收到 5s/9s 几乎不影响成功率，
     * 却能让死源提前 3~7 秒让位给并发赛跑里已经跑赢的那个源。
     */
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 9000;

    /** 音频直链解析/HEAD 跳转：只要响应头，给更短的上限 */
    private static final int MEDIA_CONNECT_TIMEOUT = 4000;
    private static final int MEDIA_READ_TIMEOUT = 6000;

    /** Cookie 存储，按 host 隔离 */
    private static final Map<String, String> COOKIES = new HashMap<String, String>();

    private Net() {}

    // ==================== Cookie 持久化 ====================

    public static void loadCookies(SharedPreferences sp) {
        try {
            String v = sp.getString("cookie_store", "");
            if (v == null || v.length() == 0) return;
            String[] lines = v.split("\n");
            for (int i = 0; i < lines.length; i++) {
                int p = lines[i].indexOf('\u0001');
                if (p > 0) COOKIES.put(lines[i].substring(0, p), lines[i].substring(p + 1));
            }
        } catch (Throwable ignored) {}
    }

    public static void persistCookies(SharedPreferences sp) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : COOKIES.entrySet()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(e.getKey()).append('\u0001').append(e.getValue());
            }
            sp.edit().putString("cookie_store", sb.toString()).apply();
        } catch (Throwable ignored) {}
    }

    /** 手动设置某个 host 的 Cookie（部分源需要会话预热） */
    public static void setCookie(String url, String cookie) {
        String h = Utils.host(url);
        if (h.length() > 0) COOKIES.put(h, cookie);
    }

    public static String getCookie(String url) {
        return COOKIES.get(Utils.host(url));
    }

    // ==================== IPv6 / 双栈 ====================

    /** IPv6 可用性缓存（网络变化时由调用方 invalidate） */
    private static volatile int IPV6_STATE = -1;   // -1 未探测 0 不可用 1 可用

    /**
     * 探测当前网络是否有可用的 IPv6 出口地址。
     * 规则：任一非回环网口上有「全球单播或唯一本地」v6 地址即算可用
     * （排除 fe80 链路本地，那个不能出公网）。
     * HttpURLConnection 本身走系统双栈解析（A + AAAA 都查，连不上自动试下一个地址），
     * 这里只做状态展示，不影响请求路径。
     */
    public static boolean ipv6Available() {
        int s = IPV6_STATE;
        if (s >= 0) return s == 1;
        boolean ok = false;
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements() && !ok) {
                java.net.NetworkInterface ni = en.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                    while (addrs != null && addrs.hasMoreElements()) {
                        java.net.InetAddress a = addrs.nextElement();
                        if (a instanceof java.net.Inet6Address
                                && !a.isLoopbackAddress()
                                && !a.isLinkLocalAddress()) {
                            ok = true;
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        IPV6_STATE = ok ? 1 : 0;
        return ok;
    }

    /** 网络切换后让下次探测重新跑 */
    public static void invalidateIpv6() {
        IPV6_STATE = -1;
    }

    // ==================== 响应对象 ====================

    public static class Resp {
        public int code;
        public String body;
        public byte[] bytes;
        public String contentType;
        public String encoding;
        public Map<String, String> headers = new HashMap<String, String>();
        public String location;   // 重定向目标（不自动跟随时用）
    }

    // ==================== 简化入口 ====================

    public static String get(String url, String referer) {
        Resp r = request(url, null, referer, null, false);
        return r == null ? null : r.body;
    }

    public static String get(String url, String referer, String ua) {
        Map<String, String> h = new HashMap<String, String>();
        if (ua != null) h.put("User-Agent", ua);
        Resp r = request(url, null, referer, h, false);
        return r == null ? null : r.body;
    }

    public static String post(String url, String form, String referer) {
        Resp r = request(url, form, referer, null, false);
        return r == null ? null : r.body;
    }

    public static String post(String url, String form, String referer, Map<String, String> extra) {
        Resp r = request(url, form, referer, extra, false);
        return r == null ? null : r.body;
    }

    public static byte[] getBytes(String url, String referer) {
        Resp r = request(url, null, referer, null, true);
        return r == null ? null : r.bytes;
    }

    // ==================== 核心请求 ====================

    /**
     * 发起请求，失败重试 1 次。
     * @param binary true 时把响应读进 bytes，否则存 body（文本，自动判编码）
     */
    public static Resp request(String url, String form, String referer,
                               Map<String, String> extra, boolean binary) {
        return request(url, form, referer, extra, binary, false);
    }

    /**
     * 同上，但可指定「短超时」模式（音频取链用）。
     */
    public static Resp request(String url, String form, String referer,
                               Map<String, String> extra, boolean binary, boolean shortTimeout) {
        final int ct = shortTimeout ? MEDIA_CONNECT_TIMEOUT : CONNECT_TIMEOUT;
        final int rt = shortTimeout ? MEDIA_READ_TIMEOUT : READ_TIMEOUT;

        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(ct);
                conn.setReadTimeout(rt);
                conn.setInstanceFollowRedirects(true);

                applyCommonHeaders(conn, referer, extra);

                if (form != null) {
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    if (extra == null || !extra.containsKey("Content-Type")) {
                        conn.setRequestProperty("Content-Type",
                                "application/x-www-form-urlencoded");
                    }
                    OutputStream os = conn.getOutputStream();
                    os.write(form.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                } else {
                    conn.setRequestMethod("GET");
                }

                int code = conn.getResponseCode();
                captureCookies(conn);

                InputStream is;
                try {
                    is = conn.getInputStream();
                } catch (Exception ex) {
                    is = conn.getErrorStream();
                }
                if (is == null) {
                    if (attempt == 0) continue;
                    return null;
                }

                String enc = conn.getContentEncoding();
                if (enc != null && enc.toLowerCase().contains("gzip")) {
                    is = new GZIPInputStream(is);
                }

                Resp r = new Resp();
                r.code = code;
                r.contentType = conn.getContentType();
                r.encoding = enc;
                r.location = conn.getHeaderField("Location");
                fillHeaders(conn, r);

                if (binary) {
                    r.bytes = readAll(is);
                } else {
                    byte[] raw = readAll(is);
                    r.body = new String(raw, guessCharset(conn));
                }
                conn.disconnect();
                return r;
            } catch (Throwable t) {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
                // ★ 超时类异常不重试。
                //   原来无脑重试一次，一个"接受 TCP 但不回数据"的死源
                //   实际耗时 = 2 × 超时（8s → 16s）。不重试直接交给
                //   并发赛跑里已经成功的其它源，用户就少等一整轮超时。
                if (attempt == 1 || isTimeout(t)) return null;
            }
        }
        return null;
    }

    /** 超时 / 连接失败：重试没有意义（源本身有问题，不是抖动） */
    private static boolean isTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.net.SocketTimeoutException
                    || c instanceof java.net.UnknownHostException
                    || c instanceof java.net.ConnectException
                    || c instanceof java.net.NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }

    /** 读重定向目标而不下载正文（声音巴士取链用） */
    public static String resolveLocation(String url, String referer, Map<String, String> extra) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(MEDIA_CONNECT_TIMEOUT);
            conn.setReadTimeout(MEDIA_READ_TIMEOUT);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("HEAD");
            applyCommonHeaders(conn, referer, extra);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                return loc == null ? null : Utils.abs(url, loc);
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    // ==================== 流式下载 ====================

    public interface Progress {
        /** @param total -1 表示服务端未给长度 */
        void onProgress(long done, long total);
    }

    public interface Cancel {
        boolean isCancelled();
    }

    public static class DlResult {
        public boolean ok;
        public long size;
        public String error;
        public boolean resumed;
    }

    /**
     * 流式下载到 dest，支持断点续传。
     * 边下边写盘（64KB buffer），音频 40MB 也不会 OOM。
     * 用 dest.part 作临时文件，完成后原子改名。
     */
    public static DlResult download(String url, String referer, Map<String, String> extra,
                                    File dest, Progress cb, Cancel cancel) {
        DlResult res = new DlResult();
        File dir = dest.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        File tmp = new File(dest.getAbsolutePath() + ".part");

        HttpURLConnection conn = null;
        RandomAccessFile raf = null;
        InputStream in = null;
        try {
            long exist = tmp.exists() ? tmp.length() : 0;

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            applyCommonHeaders(conn, referer, extra);
            if (exist > 0) {
                conn.setRequestProperty("Range", "bytes=" + exist + "-");
            } else {
                extra = null;
            }

            int code = conn.getResponseCode();
            boolean resuming = (code == 206) && exist > 0;
            if (code != 200 && code != 206) {
                res.ok = false;
                res.error = "HTTP " + code;
                return res;
            }

            long len = conn.getContentLength();
            long total = resuming ? exist + len : len;

            InputStream raw = conn.getInputStream();
            String enc = conn.getContentEncoding();
            // 音频一般不是 gzip；若 Content-Encoding 为 gzip 且带 Range，不能解压（会破坏偏移）
            if (!resuming && enc != null && enc.toLowerCase().contains("gzip")) {
                raw = new GZIPInputStream(raw);
            }
            in = raw;

            raf = new RandomAccessFile(tmp, "rw");
            if (resuming) {
                raf.seek(exist);
            } else {
                raf.setLength(0);
            }

            byte[] buf = new byte[64 * 1024];
            long done = resuming ? exist : 0;
            int n;
            long lastCb = 0;
            while ((n = in.read(buf)) > 0) {
                if (cancel != null && cancel.isCancelled()) {
                    res.ok = false;
                    res.error = "cancelled";
                    return res;
                }
                raf.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (cb != null && (now - lastCb > 400 || done == total)) {
                    lastCb = now;
                    cb.onProgress(done, total);
                }
            }
            raf.close();
            raf = null;
            in.close();
            in = null;

            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) {
                // rename 失败则退化为复制
                copyFile(tmp, dest);
                tmp.delete();
            }
            res.ok = true;
            res.size = done;
            res.resumed = resuming;
            return res;
        } catch (Throwable t) {
            res.ok = false;
            res.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return res;
        } finally {
            closeQuietly(raf);
            closeQuietly(in);
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    // ==================== 内部工具 ====================

    private static void applyCommonHeaders(HttpURLConnection conn, String referer,
                                           Map<String, String> extra) {
        boolean uaOverridden = false;
        if (extra != null && extra.containsKey("User-Agent")) uaOverridden = true;
        if (!uaOverridden) conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (referer != null && referer.length() > 0) {
            conn.setRequestProperty("Referer", referer);
        }
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        String h = Utils.host(conn.getURL().toString());
        String ck = COOKIES.get(h);
        if (ck != null && (extra == null || !extra.containsKey("Cookie"))) {
            conn.setRequestProperty("Cookie", ck);
        }
    }

    private static void captureCookies(HttpURLConnection conn) {
        try {
            Map<String, java.util.List<String>> hdrs = conn.getHeaderFields();
            if (hdrs == null) return;
            java.util.List<String> sc = hdrs.get("Set-Cookie");
            if (sc == null) sc = hdrs.get("set-cookie");
            if (sc == null || sc.isEmpty()) return;
            String host = Utils.host(conn.getURL().toString());
            StringBuilder sb = new StringBuilder();
            String old = COOKIES.get(host);
            if (old != null) sb.append(old);
            for (String s : sc) {
                int idx = s.indexOf(';');
                String kv = (idx > 0 ? s.substring(0, idx) : s).trim();
                if (kv.length() == 0) continue;
                if (sb.indexOf(kv) < 0) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(kv);
                }
            }
            COOKIES.put(host, sb.toString());
        } catch (Throwable ignored) {}
    }

    private static void fillHeaders(HttpURLConnection conn, Resp r) {
        try {
            Map<String, java.util.List<String>> hdrs = conn.getHeaderFields();
            if (hdrs == null) return;
            for (Map.Entry<String, java.util.List<String>> e : hdrs.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                r.headers.put(e.getKey(), e.getValue().get(0));
            }
        } catch (Throwable ignored) {}
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private static String guessCharset(HttpURLConnection conn) {
        String cs = conn.getContentType();
        if (cs != null) {
            String low = cs.toLowerCase();
            if (low.contains("gbk") || low.contains("gb2312") || low.contains("gb18030")) {
                return "GBK";
            }
            int i = low.indexOf("charset=");
            if (i >= 0) {
                String v = cs.substring(i + 8).trim();
                int s = v.indexOf(';');
                if (s > 0) v = v.substring(0, s);
                v = v.replace("\"", "").trim();
                if (v.length() > 0) {
                    try {
                        return v;
                    } catch (Throwable ignored) {}
                }
            }
        }
        return "UTF-8";
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = new java.io.FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Throwable ignored) {}
        }
    }
}
