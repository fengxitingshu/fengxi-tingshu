package com.github.kittinunf.fuel.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request 的宿主实现。
 *
 * <p>parameters 元素可能是 kotlin.Pair 或 Map.Entry（原版 fuel 的 query 参数列表），
 * 这里用反射取值，避免编译期链接 kotlin.Pair（javac 有 kotlin-stdlib，无所谓，
 * 但反射更稳）。
 */
public final class RequestImpl implements Request {

    private final String url;
    private final Map<String, String> headers = new LinkedHashMap<String, String>();

    private RequestImpl(String url) {
        this.url = url == null ? "" : url;
    }

    public static RequestImpl create(String path, List parameters) {
        String u = path == null ? "" : path;
        if (parameters != null && !parameters.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parameters.size(); i++) {
                Object p = parameters.get(i);
                if (p == null) continue;
                Object k = pairComponent(p, true);
                Object v = pairComponent(p, false);
                if (k == null) continue;
                sb.append(sb.length() == 0 ? (u.contains("?") ? "&" : "?") : "&");
                sb.append(urlEncode(String.valueOf(k)));
                if (v != null) sb.append("=").append(urlEncode(String.valueOf(v)));
            }
            if (sb.length() > 0) u = u + sb;
        }
        return new RequestImpl(u);
    }

    private static Object pairComponent(Object pair, boolean first) {
        try {
            java.lang.reflect.Method m = pair.getClass().getMethod(first ? "getFirst" : "getSecond");
            return m.invoke(pair);
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = pair.getClass().getMethod(first ? "getKey" : "getValue");
            return m.invoke(pair);
        } catch (Throwable ignored) {}
        return null;
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    @Override
    public Request header(Map<String, String> hs) {
        if (hs != null) {
            for (Map.Entry<String, String> e : hs.entrySet()) {
                if (e.getKey() != null) headers.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        return this;
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public Map<String, String> headers() {
        return headers;
    }

    // ==================== ★ v4.15.0 真实执行请求 ====================

    /**
     * 执行 GET 请求，返回 {@code Triple<this, null, Result>}。
     *
     * <p>要点：
     * <ul>
     *   <li>请求头按插件给的原样带上（酷我源的 UA/Referer 全在这）；</li>
     *   <li>插件没给 User-Agent 时补宿主默认 UA（部分站点拒绝空 UA）；</li>
     *   <li>按 host 注入 Cookie 库里的会话（源登录入口写入的 Cookie 对
     *       jar 插件同样生效）；</li>
     *   <li>third 必须是 {@code com.github.kittinunf.result.Result}：
     *       2xx → Success(bytes)；失败 → Failure —— 与原版 Fuel 语义一致，
     *       插件 {@code Result.get()} 时成功拿字节、失败抛异常走自己的 catch。</li>
     * </ul>
     */
    @Override
    public kotlin.Triple<Request, Object, com.github.kittinunf.result.Result> response() {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            // 1) 插件给的头原样带上
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    if ("Cookie".equalsIgnoreCase(e.getKey())) continue;  // Cookie 统一走注入
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            // 2) 默认 UA
            if (!headers.containsKey("User-Agent")) {
                conn.setRequestProperty("User-Agent",
                        com.tingshu.app.core.Net.UA);
            }
            // 3) 会话 Cookie 注入（源登录入口 → Net.setCookie）
            String ck = com.tingshu.app.core.Net.getCookie(url);
            if (ck != null && ck.length() > 0) {
                conn.setRequestProperty("Cookie", ck);
            }
            conn.setRequestProperty("Accept", "*/*");

            int code = conn.getResponseCode();
            java.io.InputStream is;
            try {
                is = conn.getInputStream();
            } catch (Throwable ex) {
                is = conn.getErrorStream();
            }
            byte[] body = new byte[0];
            if (is != null) {
                try {
                    String enc = conn.getContentEncoding();
                    if (enc != null && enc.toLowerCase().contains("gzip")) {
                        is = new java.util.zip.GZIPInputStream(is);
                    }
                    java.io.ByteArrayOutputStream bo =
                            new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                    body = bo.toByteArray();
                } finally {
                    try { is.close(); } catch (Throwable ignored) {}
                }
            }
            // 非 2xx：按原版语义返回 Failure，插件 Result.get() 抛异常走它自己的 catch
            if (code < 200 || code >= 300) {
                try { com.tingshu.app.core.PlayerLog.log(
                        "Fuel.response: HTTP " + code + " " + url); } catch (Throwable ignored) {}
                return new kotlin.Triple<Request, Object, com.github.kittinunf.result.Result>(
                        this, null, new com.github.kittinunf.result.Result.Failure(
                                "HTTP " + code));
            }
            return new kotlin.Triple<Request, Object, com.github.kittinunf.result.Result>(
                    this, null, new com.github.kittinunf.result.Result.Success(body));
        } catch (Throwable t) {
            try {
                com.tingshu.app.core.PlayerLog.log("Fuel.response EXC: "
                        + t.getClass().getSimpleName() + " " + url);
            } catch (Throwable ignored) {}
            return new kotlin.Triple<Request, Object, com.github.kittinunf.result.Result>(
                    this, null, new com.github.kittinunf.result.Result.Failure(t));
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }
}
