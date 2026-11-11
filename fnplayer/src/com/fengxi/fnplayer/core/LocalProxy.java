package com.fengxi.fnplayer.core;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地 HTTP 代理（127.0.0.1）。飞牛影视播放链路的必经一层。
 *
 * <p>解决三件事：
 * <ol>
 *   <li>播放直链要带 Cookie（{@code Trim-MC-token} / {@code authorization}），
 *       而 ijkplayer 跟随 302 时会丢自定义头 → 代理每次都重新带全头；</li>
 *   <li>HLS 的 m3u8 与分片、密钥请求都必须带同样的头 → 代理把 playlist 里
 *       每个 URI 改写成自己的 {@code /seg?u=...} 地址；</li>
 *   <li>支持 Range 透传，拖动进度条能正常 seek；HTTPS 自签按单连接放行。</li>
 * </ol>
 */
public final class LocalProxy {

    private static final class Target {
        final String url;
        final Map<String, String> headers;
        final boolean trustSsl;
        Target(String u, Map<String, String> h, boolean t) { url = u; headers = h; trustSsl = t; }
    }

    private static final ConcurrentHashMap<String, Target> TARGETS =
            new ConcurrentHashMap<String, Target>();
    /**
     * 稳定目标表（海报等长生命周期资源）：按 URL 哈希复用同一个 id，
     * 不参与 TARGETS 的 256 条淘汰 —— 否则大媒体库滚动时旧海报地址会 404（封面空白的根因）。
     */
    private static final ConcurrentHashMap<String, Target> STABLE =
            new ConcurrentHashMap<String, Target>();
    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static ServerSocket server;
    private static int port = -1;

    private LocalProxy() {}

    public static synchronized String serve(String realUrl, Map<String, String> headers,
                                            boolean trustSsl) {
        if (realUrl == null || !realUrl.startsWith("http")) return null;
        try {
            ensureServer();
            String id = "p" + SEQ.getAndIncrement();
            TARGETS.put(id, new Target(realUrl, headers, trustSsl));
            if (TARGETS.size() > 256) {
                for (String k : TARGETS.keySet()) {
                    if (TARGETS.size() > 192 && !k.equals(id)) TARGETS.remove(k);
                }
            }
            return "http://127.0.0.1:" + port + "/" + id + "/" + fileNameOf(realUrl);
        } catch (Throwable t) { return null; }
    }

    /** 稳定代理地址：同一 URL 永远返回同一个本地地址（海报墙用）。 */
    public static synchronized String serveStable(String realUrl, Map<String, String> headers,
                                                  boolean trustSsl) {
        if (realUrl == null || !realUrl.startsWith("http")) return null;
        try {
            ensureServer();
            String id = "s" + md5(realUrl).substring(0, 12);
            STABLE.put(id, new Target(realUrl, headers, trustSsl));
            if (STABLE.size() > 2048) STABLE.clear();   // 极端情况整体重建，重新请求即可
            return "http://127.0.0.1:" + port + "/" + id + "/" + fileNameOf(realUrl);
        } catch (Throwable t) { return null; }
    }

    private static void ensureServer() throws Exception {
        if (server == null || server.isClosed()) {
            server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
            port = server.getLocalPort();
            Thread t = new Thread(new Runnable() {
                @Override public void run() { acceptLoop(); }
            }, "fnproxy");
            t.setDaemon(true);
            t.start();
        }
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("MD5");
            byte[] b = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable t) { return String.valueOf(s.hashCode()); }
    }

    public static void drop(String localUrl) {
        if (localUrl == null) return;
        try {
            String[] s = localUrl.split("/");
            if (s.length > 3) {
                TARGETS.remove(s[3]);
                STABLE.remove(s[3]);
            }
        } catch (Throwable ignored) {}
    }

    private static String fileNameOf(String u) {
        int q = u.indexOf('?');
        String p = q > 0 ? u.substring(0, q) : u;
        int s = p.lastIndexOf('/');
        String n = s >= 0 ? p.substring(s + 1) : "stream";
        return n.length() == 0 ? "stream" : n;
    }

    private static void acceptLoop() {
        while (server != null && !server.isClosed()) {
            try {
                final Socket sk = server.accept();
                sk.setSoTimeout(30000);
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { handle(sk); }
                }, "fnproxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable ignored) {}
        }
    }

    private static void handle(Socket sk) {
        InputStream is = null;
        OutputStream os = null;
        HttpURLConnection conn = null;
        try {
            is = sk.getInputStream();
            os = sk.getOutputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "ISO-8859-1"));
            String req = br.readLine();
            if (req == null) return;
            String[] parts = req.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            String range = null;
            String line;
            while ((line = br.readLine()) != null && line.length() > 0) {
                if (line.toLowerCase().startsWith("range:"))
                    range = line.substring(6).trim();
            }

            int s1 = path.indexOf('/');
            int s2 = s1 < 0 ? -1 : path.indexOf('/', s1 + 1);
            if (s1 < 0 || s2 < 0) { writeError(os, 404, "bad proxy path"); return; }
            String id = path.substring(s1 + 1, s2);
            String rest = path.substring(s2 + 1);
            Target t = TARGETS.get(id);
            if (t == null) t = STABLE.get(id);
            if (t == null) { writeError(os, 404, "unknown target"); return; }

            String targetUrl = t.url;
            if (rest.startsWith("seg?u=")) {
                String enc = rest.substring("seg?u=".length());
                int amp = enc.indexOf('&');
                if (amp >= 0) enc = enc.substring(0, amp);
                targetUrl = URLDecoder.decode(enc, "UTF-8");
            }
            boolean playlist = targetUrl.contains(".m3u8");

            conn = (HttpURLConnection) new URL(targetUrl).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", Net.UA);
            if (t.headers != null) {
                for (Map.Entry<String, String> e : t.headers.entrySet())
                    if (e.getKey() != null && e.getValue() != null)
                        conn.setRequestProperty(e.getKey(), e.getValue());
            }
            if (range != null) conn.setRequestProperty("Range", range);
            if (conn instanceof javax.net.ssl.HttpsURLConnection && t.trustSsl)
                Net.applyTrustAll((javax.net.ssl.HttpsURLConnection) conn);

            int code = conn.getResponseCode();
            // 302 逐跳带头
            for (int hop = 0; hop < 4 && (code == 301 || code == 302 || code == 303
                    || code == 307 || code == 308); hop++) {
                String loc = conn.getHeaderField("Location");
                if (loc == null) break;
                conn.disconnect();
                targetUrl = new URL(new URL(targetUrl), loc).toString();
                conn = (HttpURLConnection) new URL(targetUrl).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(20000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", Net.UA);
                if (t.headers != null) {
                    for (Map.Entry<String, String> e : t.headers.entrySet())
                        if (e.getKey() != null && e.getValue() != null)
                            conn.setRequestProperty(e.getKey(), e.getValue());
                }
                if (range != null) conn.setRequestProperty("Range", range);
                if (conn instanceof javax.net.ssl.HttpsURLConnection && t.trustSsl)
                    Net.applyTrustAll((javax.net.ssl.HttpsURLConnection) conn);
                code = conn.getResponseCode();
            }

            if (code >= 400) {
                writeError(os, code, "upstream HTTP " + code);
                return;
            }

            String ctype = conn.getContentType() == null ? "" : conn.getContentType().toLowerCase();
            String contentRange = conn.getHeaderField("Content-Range");
            String length = conn.getHeaderField("Content-Length");

            InputStream body = conn.getInputStream();
            // m3u8 判定：URL 后缀 + 响应类型（直播源 m3u8 常带 query 参数没有 .m3u8 后缀）
            if (playlist || ctype.contains("mpegurl") || ctype.contains("m3u")) {
                String text = readAllText(body);
                body.close();
                String rewritten = rewritePlaylist(text, targetUrl, id);
                byte[] out = rewritten.getBytes("UTF-8");
                String ctypeOut = ctype.length() > 0 ? ctype : "application/vnd.apple.mpegurl";
                StringBuilder hd = new StringBuilder();
                hd.append("HTTP/1.1 200 OK\r\n");
                hd.append("Content-Type: ").append(ctypeOut).append("\r\n");
                hd.append("Content-Length: ").append(out.length).append("\r\n");
                hd.append("Access-Control-Allow-Origin: *\r\n");
                hd.append("Connection: close\r\n\r\n");
                os.write(hd.toString().getBytes("ISO-8859-1"));
                os.write(out);
                os.flush();
                return;
            }

            StringBuilder hd = new StringBuilder();
            hd.append(code == 206 ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            hd.append("Content-Type: ").append(ctype.length() > 0 ? ctype : "application/octet-stream").append("\r\n");
            if (contentRange != null) hd.append("Content-Range: ").append(contentRange).append("\r\n");
            if (length != null) hd.append("Content-Length: ").append(length).append("\r\n");
            hd.append("Accept-Ranges: bytes\r\n");
            hd.append("Connection: close\r\n\r\n");
            os.write(hd.toString().getBytes("ISO-8859-1"));
            byte[] buf = new byte[16384];
            int n;
            while ((n = body.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } catch (Throwable t) {
            try { writeError(os, 502, Utils.brief(t)); } catch (Throwable ignored) {}
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
            if (is != null) try { is.close(); } catch (Throwable ignored) {}
            if (os != null) try { os.close(); } catch (Throwable ignored) {}
            if (sk != null) try { sk.close(); } catch (Throwable ignored) {}
        }
    }

    /** 把 m3u8 里的分片/密钥 URI 全部改写到本代理，保证每个请求都带 Cookie。 */
    private static String rewritePlaylist(String text, String baseUrl, String id) {
        StringBuilder out = new StringBuilder();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.length() == 0) { out.append('\n'); continue; }
            if (line.startsWith("#")) {
                // #EXT-X-KEY / #EXT-X-MAP 里的 URI="..."
                Matcher m = Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    String abs = toAbs(baseUrl, m.group(1));
                    m.appendReplacement(sb, Matcher.quoteReplacement(
                            "URI=\"" + segUrl(abs, id) + "\""));
                }
                m.appendTail(sb);
                out.append(sb).append('\n');
                continue;
            }
            out.append(segUrl(toAbs(baseUrl, line), id)).append('\n');
        }
        return out.toString();
    }

    private static String toAbs(String base, String p) {
        try { return new URL(new URL(base), p).toString(); }
        catch (Throwable t) { return p; }
    }

    private static String segUrl(String abs, String id) {
        try {
            return "http://127.0.0.1:" + port + "/" + id
                    + "/seg?u=" + URLEncoder.encode(abs, "UTF-8");
        } catch (Throwable t) { return abs; }
    }

    private static String readAllText(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static void writeError(OutputStream os, int code, String msg) throws Exception {
        byte[] b = ("proxy error: " + msg).getBytes("UTF-8");
        String hd = "HTTP/1.1 " + code + " ERROR\r\nContent-Type: text/plain\r\nContent-Length: "
                + b.length + "\r\nConnection: close\r\n\r\n";
        os.write(hd.getBytes("ISO-8859-1"));
        os.write(b);
        os.flush();
    }
}
