package com.fengxi.fnplayer.core;

import com.fengxi.fnplayer.data.Config;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 「跟网页走」的地址自愈。
 *
 * <p>飞牛官方 DDNS / 反向代理 / FN Connect 中继常把 Web 端挂在 443，
 * 而直连端口 5666 / 5667 在公网 IPv6 上未必放行。裸 TCP 探测到 5667 被拒，
 * 只说明「这一条路没服务」，并不代表网页打不开——浏览器走的是另一条路。
 *
 * <p>所以这里不再用裸 socket 一票否决：对同一主机的多种 scheme/port 组合
 * 发真实 HTTP 请求，谁返回 HTTP 响应就用谁，并把结果写回 Config，
 * 让后续 API / WebView / 播放链路全部对齐浏览器实际可用的地址。
 */
public final class Reach {

    private Reach() {}

    public static final class Hit {
        public String base = "";
        public int code = -1;
        public boolean fnos = false;
        public String err = "";
        public long ms;

        public boolean ok() { return code > 0; }
    }

    public static final class Heal {
        public boolean ok = false;
        public String base = "";
        public String detail = "";
        public int tried = 0;
        public long ms;
    }

    private static volatile String cached = "";
    private static volatile long cachedAt = 0L;
    private static final long TTL = 30L * 60L * 1000L;

    public static void remember(String base) {
        cached = base == null ? "" : base;
        cachedAt = System.currentTimeMillis();
    }

    public static String cached() {
        return System.currentTimeMillis() - cachedAt < TTL ? cached : "";
    }

    public static void forget() { cached = ""; cachedAt = 0L; }

    /** 候选：原地址 → 443 → 80 → 常见直连端口。顺序即优先级。 */
    public static List<String> candidates(String raw) {
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        try {
            String norm = Utils.normalizeBase(raw);
            set.add(norm);
            String host = Utils.hostOf(norm);   // 域名，或已带方括号的 IPv6 字面量
            set.add("https://" + host);          // 443：官方 DDNS / 反代 / 中继最常见
            set.add("http://" + host);           // 80
            set.add("http://" + host + ":8005"); // 飞牛影视独立端口（社区实测）
            set.add("https://" + host + ":5667");
            set.add("http://" + host + ":5666");
            set.add("http://" + host + ":8000");
            set.add("http://" + host + ":8001");
            set.add("https://" + host + ":5666");
        } catch (Throwable ignored) {}
        return new ArrayList<String>(set);
    }

    /**
     * 单个候选：GET /v/。任何 HTTP 响应码都算「有 Web 服务在」，
     * 401/403/404/302 同样说明端口后面活着，不该判成不通。
     * 探测阶段一律放宽证书，避免自签证书被误判成地址不可达。
     */
    public static Hit probe(final String base, int timeoutMs) {
        Hit h = new Hit();
        h.base = base;
        long t0 = System.currentTimeMillis();
        Map<String, String> hd = new LinkedHashMap<String, String>();
        hd.put("Accept", "text/html,application/xhtml+xml,*/*");
        Net.Resp r = Net.get(base + "/v/", hd, true, timeoutMs);
        h.ms = System.currentTimeMillis() - t0;
        h.code = r.code;
        h.err = r.error;
        if (h.code > 0) {
            String b = (r.body == null ? "" : r.body).toLowerCase(Locale.ROOT);
            h.fnos = b.contains("fnos") || b.contains("飞牛") || b.contains("trim-mc")
                    || b.contains("fnos-web") || b.contains("<title");
        }
        return h;
    }

    /** 并行探测所有候选，按候选优先级挑第一个可达（飞牛页面优先）。 */
    public static Heal heal(String preferred) {
        Heal out = new Heal();
        long t0 = System.currentTimeMillis();
        final List<String> cands = candidates(preferred);
        if (cands.isEmpty()) {
            out.detail = "  地址格式无法解析，请在设置里重新填写服务器地址\n";
            out.ms = System.currentTimeMillis() - t0;
            return out;
        }
        final int timeout = 2200;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, cands.size()));
        List<Future<Hit>> fs = new ArrayList<Future<Hit>>();
        for (int i = 0; i < cands.size(); i++) {
            final String b = cands.get(i);
            fs.add(pool.submit(new Callable<Hit>() {
                @Override public Hit call() { return probe(b, timeout); }
            }));
        }
        Map<String, Hit> hits = new HashMap<String, Hit>();
        for (int i = 0; i < fs.size(); i++) {
            try {
                Hit h = fs.get(i).get(3500, TimeUnit.MILLISECONDS);
                hits.put(cands.get(i), h);
            } catch (Throwable t) {
                Hit h = new Hit();
                h.base = cands.get(i);
                h.err = "timeout";
                hits.put(cands.get(i), h);
            }
        }
        pool.shutdownNow();

        StringBuilder sb = new StringBuilder();
        String first = "", fnosHit = "";
        for (String b : cands) {
            Hit h = hits.get(b);
            out.tried++;
            if (h == null) continue;
            if (h.ok()) {
                sb.append("  ✓ ").append(b).append(" → HTTP ").append(h.code)
                        .append(h.fnos ? "（飞牛页面）" : "").append("  ")
                        .append(h.ms).append("ms\n");
                if (first.length() == 0) first = b;
                if (h.fnos && fnosHit.length() == 0) fnosHit = b;
            } else {
                sb.append("  ✗ ").append(b).append(" → ").append(shortErr(h.err))
                        .append("  ").append(h.ms).append("ms\n");
            }
        }
        out.detail = sb.toString();
        out.base = fnosHit.length() > 0 ? fnosHit : first;
        out.ok = out.base.length() > 0;
        out.ms = System.currentTimeMillis() - t0;
        if (out.ok) {
            remember(out.base);
            Config.setBase(out.base);
            // 飞牛 https 基本都是自签；一旦走通 https 就自动放行证书，
            // 否则内嵌 WebView 会白屏、播放链路也会握手失败。
            if (out.base.startsWith("https")) Config.setTrustSsl(true);
        }
        return out;
    }

    private static String shortErr(String e) {
        if (e == null || e.length() == 0) return "无响应";
        if (e.contains("refused") || e.contains("ConnectException")) return "连接被拒绝";
        if (e.contains("Timeout") || e.contains("timeout") || e.contains("timed out")) return "连接超时";
        if (e.contains("NoRouteToHost")) return "网络不可达";
        if (e.contains("Unable to resolve") || e.contains("UnknownHost")) return "DNS 失败";
        if (e.contains("SSL") || e.contains("certificate")) return "证书错误";
        return e.length() > 46 ? e.substring(0, 46) : e;
    }

    /** 从任意 URL 提取 scheme://host:port，用于把 WebView 实际生效的地址回填。 */
    public static String originOf(String u) {
        try {
            URI x = new URI(u);
            String scheme = x.getScheme();
            String host = x.getHost();
            if (scheme == null || host == null) return "";
            if (!host.startsWith("[") && host.indexOf(':') >= 0) host = "[" + host + "]";
            int p = x.getPort();
            if (p <= 0) p = "https".equalsIgnoreCase(scheme) ? 443 : 80;
            return scheme.toLowerCase(Locale.ROOT) + "://" + host + ":" + p;
        } catch (Throwable t) { return ""; }
    }
}
