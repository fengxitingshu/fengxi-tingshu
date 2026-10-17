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
}
