package com.fengxi.fnplayer.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 精简 HTTP 客户端（裁剪自主工程 Net.java 的 request 语义）。
 *
 * <p>针对飞牛 NAS 场景保留三件事：
 * <ol>
 *   <li>IPv6 字面量 URL（{@code http://[2408:…]:5666}）直接交给 HttpURLConnection；</li>
 *   <li>自签 HTTPS 只对<b>当前这一条连接</b>装 trust-all，不污染全局 SSLSocketFactory；</li>
 *   <li>手动逐跳跟随 302，每跳都重新带上 Cookie / Authorization，避免自定义头丢失。</li>
 * </ol>
 */
public final class Net {

    private Net() {}

    public static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    public static final class Resp {
        public int code = -1;
        public String body = "";
        public byte[] bytes;
        public String contentType = "";
        public String location = "";
        public String error = "";
        public long ms;
        public Map<String, String> headers = new LinkedHashMap<String, String>();
    }

    /** 短超时（API 调用）；播放直链由 LocalProxy 自己控制 */
    public static Resp get(String url, Map<String, String> headers,
                           boolean trustSsl, int timeoutMs) {
        return exec(url, "GET", null, headers, trustSsl, timeoutMs, false, 4 * 1024 * 1024);
    }

    public static Resp post(String url, String json, Map<String, String> headers,
                            boolean trustSsl, int timeoutMs) {
        return exec(url, "POST", json, headers, trustSsl, timeoutMs, false, 4 * 1024 * 1024);
    }

    /** 二进制读取（海报/小文件用），maxBytes 限制防止把整部电影读进内存 */
    public static Resp getBytes(String url, Map<String, String> headers,
                                boolean trustSsl, int timeoutMs, int maxBytes) {
        return exec(url, "GET", null, headers, trustSsl, timeoutMs, true, maxBytes);
    }

    private static Resp exec(String url, String method, String json,
                             Map<String, String> headers, boolean trustSsl,
                             int timeoutMs, boolean binary, int maxBytes) {
        long t0 = System.currentTimeMillis();
        Resp r = new Resp();
        String cur = url;
        // 手动跟 302，最多 5 跳，每跳都重新带全头
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(cur).openConnection();
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Accept", "*/*");
                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        if (e.getKey() != null && e.getValue() != null)
                            conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
                if (conn instanceof HttpsURLConnection && trustSsl) applyTrustAll((HttpsURLConnection) conn);

                if ("POST".equals(method)) {
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    byte[] data = (json == null ? "{}" : json).getBytes("UTF-8");
                    OutputStream os = conn.getOutputStream();
                    os.write(data);
                    os.flush();
                    os.close();
                } else {
                    conn.setRequestMethod("GET");
                }

                r.code = conn.getResponseCode();
                r.contentType = conn.getContentType() == null ? "" : conn.getContentType();
                for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                    if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty())
                        r.headers.put(e.getKey(), e.getValue().get(0));
                }
                r.location = conn.getHeaderField("Location") == null
                        ? "" : conn.getHeaderField("Location");

                if (r.code == 301 || r.code == 302 || r.code == 303
                        || r.code == 307 || r.code == 308) {
                    if (r.location.length() == 0) break;
                    cur = new URL(new URL(cur), r.location).toString();
                    conn.disconnect();
                    continue;
                }

                InputStream is = r.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (is == null) break;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = is.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) {
                        // 超出上限就截断，避免 OOM
                        bo.write(buf, 0, Math.max(0, n - (total - maxBytes)));
                        break;
                    }
                    bo.write(buf, 0, n);
                }
                is.close();
                byte[] out = bo.toByteArray();
                if (binary) r.bytes = out;
                r.body = new String(out, "UTF-8");
                break;
            } catch (Throwable t) {
                r.error = Utils.brief(t);
                break;
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
        r.ms = System.currentTimeMillis() - t0;
        return r;
    }

    /** 只对单条连接装信任所有证书的 TrustManager + 放行 hostname（自签 NAS 用）。 */
    public static void applyTrustAll(HttpsURLConnection h) {
        try {
            final X509TrustManager tm = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String auth) {}
                public void checkServerTrusted(X509Certificate[] chain, String auth) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{tm}, new SecureRandom());
            h.setSSLSocketFactory(sc.getSocketFactory());
            h.setHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) { return true; }
            });
        } catch (Throwable ignored) {}
    }
}
