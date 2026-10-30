package com.tingshu.app.core;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ★ v4.9.3：极简本地 HTTP 直链代理（只给「网盘直链播放」兜底用）。
 *
 * <h3>为什么需要它</h3>
 * 网盘（夸克 / 115 / 天翼 / UC）的音频直链有两个脾气：
 * <ol>
 *   <li>要带一大堆校验头（Cookie / Referer / UA），少一个就 403；</li>
 *   <li>多数还是一次 302 跳到 CDN，而 Android 的 MediaPlayer / ijk 在
 *       <b>跟随重定向时会把自定义头丢掉</b>——于是「取链明明成功，播放必然失败」。</li>
 * </ol>
 * 这两件事在播放器里没法修，只能在中间加一层：App 自己起一个
 * {@code 127.0.0.1} 的 HTTP 服务，用 {@link HttpURLConnection} 把直链取回来
 * （头由我们带、302 由我们跟），再把字节流原样吐给播放器。
 * 播放器只看到一条普普通通的本地 URL，不带任何头也能播。
 *
 * <h3>边界</h3>
 * <ul>
 *   <li><b>只在播放失败后才启用</b>（PlayerCore 的兜底分支），正常播放零开销、
 *       完全不影响现有链路——代理本身出问题也不会把能播的搞挂。</li>
 *   <li>支持 Range 透传（206 + Content-Range），seek / 拖动进度条照常。</li>
 *   <li>URL 尾部保留原始文件名，播放内核按扩展名选（.wma → FFmpeg）仍有效。</li>
 *   <li>失败 3 次即自我熔断，不再拖慢后续播放。</li>
 * </ul>
 */
public final class LocalProxy {

    /** 目标直链 + 它要求的请求头 */
    private static final class Target {
        final String url;
        final Map<String, String> headers;

        Target(String u, Map<String, String> h) {
            url = u;
            headers = h;
        }
    }

    private static final Map<String, Target> TARGETS = new ConcurrentHashMap<String, Target>();
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private static ServerSocket server;
    private static int port = -1;
    private static int failures = 0;
    /**
     * ★ v4.13.0：持久源（WebDAV / SMB 个人音频库）的独立熔断额度。
     *
     * <p>为什么必须分开：网盘直链偶发失败会把 {@link #failures} 打满，
     * 那本是给「网盘兜底」用的保守额度；若 WebDAV 跟着作废，就会出现
     * 「明明能连上 NAS，却起播失败」这种看不懂的现象。两者互不影响。
     */
    private static int persistFailures = 0;

    private LocalProxy() {}

    /** 熔断后不再尝试（避免每次失败都多等一轮连接超时） */
    public static boolean usable() {
        return failures < 3;
    }

    /**
     * 把一条直链挂到本地代理上，返回播放器可直接使用的本地 URL。
     *
     * @return 代理 URL；不可用时返回 null（调用方应回退到原直链）
     */
    public static synchronized String serve(String realUrl, Map<String, String> headers) {
        return serveInternal(realUrl, headers, false);
    }

    /**
     * ★ v4.13.0：持久源（WebDAV / SMB 个人音频库）专用。
     *
     * <p>与 {@link #serve} 的唯一区别是走<b>独立的熔断额度</b>
     * （见 {@link #persistFailures}）。语义完全一致。
     */
    public static synchronized String servePersistent(String realUrl,
                                                      Map<String, String> headers) {
        return serveInternal(realUrl, headers, true);
    }

    private static String serveInternal(String realUrl, Map<String, String> headers,
                                        boolean persistent) {
        if (realUrl == null || !realUrl.startsWith("http")) return null;
        if (persistent ? persistFailures >= 3 : failures >= 3) return null;
        try {
            if (server == null || server.isClosed()) {
                server = new ServerSocket(0, 24, InetAddress.getByName("127.0.0.1"));
                port = server.getLocalPort();
                Thread t = new Thread(new Runnable() {
                    public void run() { acceptLoop(); }
                }, "local-proxy");
                t.setDaemon(true);
                t.start();
            }
            String id = "p" + SEQ.getAndIncrement();
            TARGETS.put(id, new Target(realUrl, headers));
            // 控制一下 map 大小（长时间使用不会无限增长）
            if (TARGETS.size() > 64) {
                java.util.Iterator<String> it = TARGETS.keySet().iterator();
                while (it.hasNext() && TARGETS.size() > 48) {
                    String k = it.next();
                    if (!k.equals(id)) it.remove();
                }
            }
            return "http://127.0.0.1:" + port + "/" + id + "/" + fileNameOf(realUrl);
        } catch (Throwable t) {
            if (persistent) persistFailures++;
            else failures++;
            return null;
        }
    }

    /** 从直链里取文件名（保留扩展名给播放内核判断格式） */
    private static String fileNameOf(String url) {
        try {
            String p = url;
            int q = p.indexOf('?');
            if (q > 0) p = p.substring(0, q);
            int slash = p.lastIndexOf('/');
            String name = (slash >= 0 && slash + 1 < p.length()) ? p.substring(slash + 1) : "a";
            // 只留安全字符，避免 URL 里出现奇怪的东西
            name = name.replaceAll("[^0-9A-Za-z._-]", "_");
            return name.length() == 0 ? "a" : name;
        } catch (Throwable t) {
            return "a";
        }
    }

    // ==================== 监听 / 分发 ====================

    private static void acceptLoop() {
        while (true) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(new Runnable() {
                    public void run() { handle(s); }
                }, "local-proxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                // server 被关掉时跳出（正常情况下 App 生命周期内不会）
                if (server == null || server.isClosed()) return;
            }
        }
    }

    private static void handle(Socket s) {
        HttpURLConnection conn = null;
        try {
            s.setSoTimeout(20000);
            StringBuilder head = new StringBuilder();
            InputStream cin = new BufferedInputStream(s.getInputStream());
            // 只读到空行为止（请求没有 body）
            int prev1 = -1, prev2 = -1, prev3 = -1, c;
            while ((c = cin.read()) != -1) {
                head.append((char) c);
                if (prev1 == '\r' && prev2 == '\n' && prev3 == '\r' && c == '\n') break;
                prev3 = prev2;
                prev2 = prev1;
                prev1 = c;
                if (head.length() > 8192) break;
            }
            String[] lines = head.toString().split("\r\n");
            if (lines.length == 0) return;

            // 请求行：GET /p12/xxx.mp3 HTTP/1.1
            String reqLine = lines[0];
            String[] seg = reqLine.split(" ");
            if (seg.length < 2) return;
            String path = seg[1];
            String id = null;
            if (path.startsWith("/")) {
                String rest = path.substring(1);
                int slash = rest.indexOf('/');
                id = slash > 0 ? rest.substring(0, slash) : rest;
            }
            Target target = id == null ? null : TARGETS.get(id);
            if (target == null) {
                writeSimple(s, 404, "not found");
                return;
            }

            String range = null;
            for (int i = 1; i < lines.length; i++) {
                String ln = lines[i];
                int colon = ln.indexOf(':');
                if (colon <= 0) continue;
                String k = ln.substring(0, colon).trim();
                if (k.equalsIgnoreCase("Range")) range = ln.substring(colon + 1).trim();
            }

            conn = openFollowing(target, range, 0);
            int code = conn.getResponseCode();

            OutputStream out = s.getOutputStream();
            StringBuilder resp = new StringBuilder();
            resp.append("HTTP/1.1 ").append(code).append(' ')
                    .append(code == 206 ? "Partial Content" : (code == 200 ? "OK" : httpMsg(code)))
                    .append("\r\n");
            String ct = conn.getContentType();
            resp.append("Content-Type: ").append(ct == null ? "application/octet-stream" : ct).append("\r\n");
            resp.append("Accept-Ranges: bytes\r\n");

            long len = -1;
            String cr = conn.getHeaderField("Content-Range");
            if (cr != null && cr.length() > 0) {
                resp.append("Content-Range: ").append(cr).append("\r\n");
                len = parseTotal(cr);
            }
            long cl = conn.getContentLength();
            if (cl >= 0 && code == 200) len = cl;
            if (code == 206 && cr != null) {
                // 206：长度就是这一段
                long[] r = parseRange(cr);
                if (r != null) cl = r[1] - r[0] + 1;
            }
            if (cl >= 0) resp.append("Content-Length: ").append(cl).append("\r\n");
            else if (len >= 0) resp.append("Content-Length: ").append(len).append("\r\n");
            resp.append("Connection: close\r\n\r\n");
            out.write(resp.toString().getBytes("UTF-8"));
            out.flush();

            InputStream in = conn.getInputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
            try { in.close(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            // 播放器提前关连接是常态（换集/seek），不当错误
        } finally {
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) {}
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    /** 打开上游连接，手动跟随 302/301（关键：每一跳都重新带上校验头） */
    private static HttpURLConnection openFollowing(Target target, String range, int depth)
            throws Exception {
        URL u = new URL(target.url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        applyNasTls(conn, target.url);   // ★ v4.13.0：家庭 NAS 的自签名证书
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (target.headers != null) {
            for (Map.Entry<String, String> e : target.headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                try { conn.setRequestProperty(e.getKey(), e.getValue()); } catch (Throwable ignored) {}
            }
        }
        if (conn.getRequestProperty("User-Agent") == null) {
            conn.setRequestProperty("User-Agent", Net.UA);
        }
        if (range != null) conn.setRequestProperty("Range", range);

        int code = conn.getResponseCode();
        if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308)
                && depth < 5) {
            String loc = conn.getHeaderField("Location");
            if (loc != null && loc.length() > 0) {
                URL next = new URL(u, loc);
                conn.disconnect();
                Target nt = new Target(next.toString(), target.headers);
                return openFollowing(nt, range, depth + 1);
            }
        }
        return conn;
    }

    /**
     * ★ v4.13.0：家庭 NAS（群晖 / 威联通 / 飞牛等）的 HTTPS 几乎都是自签名证书，
     * 严格校验必然握手失败 —— 表现为「浏览器能开 Nas 控制台，App 里却连不上」。
     *
     * <p>默认仍然严格校验；只有在用户明确开启「信任自签名证书」（备份页那个开关，
     * {@code Settings.davTrustAllSsl()}）后才放宽，且<b>只作用于这一条连接</b> ——
     * 不去动全局默认 SSLSocketFactory，那会让整个 App 失去 TLS 保护。
     */
    private static void applyNasTls(HttpURLConnection conn, String url) {
        if (url == null || !url.toLowerCase(Locale.US).startsWith("https")) return;
        if (!(conn instanceof javax.net.ssl.HttpsURLConnection)) return;
        boolean trust;
        try {
            trust = com.tingshu.app.data.Settings.davTrustAllSsl();
        } catch (Throwable t) {
            return;   // Settings 未初始化（极端情况）→ 走严格校验更安全
        }
        if (!trust) return;
        javax.net.ssl.HttpsURLConnection hc = (javax.net.ssl.HttpsURLConnection) conn;
        try {
            javax.net.ssl.TrustManager[] tm = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public void checkClientTrusted(
                                java.security.cert.X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(
                                java.security.cert.X509Certificate[] chain, String authType) {}
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, tm, new java.security.SecureRandom());
            hc.setSSLSocketFactory(sc.getSocketFactory());
            // 自签证书的 NAS 常用 IP / IPv6 直连，主机名校验必然不过
            hc.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                    return true;
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void writeSimple(Socket s, int code, String body) {
        try {
            byte[] b = body.getBytes("UTF-8");
            String h = "HTTP/1.1 " + code + " " + httpMsg(code) + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
            OutputStream o = s.getOutputStream();
            o.write(h.getBytes("UTF-8"));
            o.write(b);
            o.flush();
        } catch (Throwable ignored) {}
    }

    private static String httpMsg(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            default: return "OK";
        }
    }

    private static long parseTotal(String contentRange) {
        try {
            int slash = contentRange.lastIndexOf('/');
            if (slash < 0) return -1;
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long[] parseRange(String contentRange) {
        try {
            // bytes 0-1023/123456
            String s = contentRange.toLowerCase(Locale.US).replace("bytes", "").trim();
            int slash = s.indexOf('/');
            if (slash > 0) s = s.substring(0, slash);
            int dash = s.indexOf('-');
            if (dash < 0) return null;
            long a = Long.parseLong(s.substring(0, dash).trim());
            long b = Long.parseLong(s.substring(dash + 1).trim());
            return new long[]{a, b};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 诊断用：当前代理状态（排查时可从日志看） */
    public static String status() {
        return "port=" + port + " targets=" + TARGETS.size() + " failures=" + failures;
    }

    /** 清空历史目标（换书/停止时调用，避免长期占用） */
    public static synchronized void reset() {
        TARGETS.clear();
    }
}
