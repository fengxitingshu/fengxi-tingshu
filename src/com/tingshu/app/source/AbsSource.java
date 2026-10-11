package com.tingshu.app.source;

import com.tingshu.app.core.Net;
import com.tingshu.app.core.Utils;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 源的抽象基类。提供 HTML 解析工具（代替 Jsoup）、URL 处理、线程安全缓存。
 * 所有 impl 继承它，只写站点特有的逻辑。
 */
public abstract class AbsSource implements Source {

    /** 默认移动端 UA */
    protected static final String UA = Net.UA;
    /** 桌面 UA（部分站点强制要求） */
    protected static final String UA_DESKTOP = Net.UA_DESKTOP;

    // ==================== 默认实现 ====================

    @Override
    public boolean canSearch() {
        return true;
    }

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        BookDetail d = new BookDetail();
        d.total = 0;
        return d;
    }

    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        return null;
    }

    @Override
    public Map<String, String> coverHeaders(String coverUrl) {
        return null;
    }

    // ==================== HTML 解析工具（替代 Jsoup） ====================

    /** 取第一个匹配的 group(1)，无匹配返回空串。DOTALL 模式。 */
    public static String grab(String s, String regex) {
        return grab(s, regex, Pattern.DOTALL);
    }

    public static String grab(String s, String regex, int flags) {
        if (s == null) return "";
        try {
            Matcher m = Pattern.compile(regex, flags).matcher(s);
            return m.find() ? m.group(1).trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 取所有匹配的 group(1) */
    public static List<String> grabAll(String s, String regex) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        try {
            Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(s);
            while (m.find()) out.add(m.group(1).trim());
        } catch (Throwable ignored) {}
        return out;
    }

    /** 取所有匹配的完整片段（group(0)） */
    public static List<String> grabBlocks(String s, String regex) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        try {
            Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(s);
            while (m.find()) out.add(m.group(0));
        } catch (Throwable ignored) {}
        return out;
    }

    /** 取 meta 标签 content */
    public static String meta(String html, String name) {
        if (html == null) return "";
        String v = grab(html, "<meta\\s+name=\"" + Pattern.quote(name)
                + "\"\\s+content=\"([^\"]*)\"");
        if (v.length() == 0) {
            v = grab(html, "<meta\\s+name='" + Pattern.quote(name)
                    + "'\\s+content='([^']*)'");
        }
        if (v.length() == 0) {
            v = grab(html, "<meta\\s+content=\"([^\"]*)\"\\s+name=\"" + Pattern.quote(name) + "\"");
        }
        return v;
    }

    /** 去标签 + 还原实体 + 压空白 */
    public static String text(String s) {
        return Utils.stripTags(Utils.unescape(s));
    }

    /** 去掉"作者："/"播音："之类前缀 */
    public static String stripLabel(String s) {
        if (s == null) return "";
        return s.replaceAll("^\\s*(作者|播音|演播|主播|类别|状态|时间|来源)\\s*[：:]\\s*", "").trim();
    }

    /** 相对路径补全 */
    public static String abs(String base, String url) {
        return Utils.abs(base, url);
    }

    /** 还原转义 */
    public static String unescape(String s) {
        return Utils.unescape(s);
    }

    // ==================== 便捷 HTTP ====================

    protected String get(String url, String referer) {
        return Net.get(url, referer);
    }

    protected String get(String url, String referer, String ua) {
        return Net.get(url, referer, ua);
    }

    protected String post(String url, String form, String referer) {
        return Net.post(url, form, referer);
    }

    protected String post(String url, String form, String referer, Map<String, String> extra) {
        return Net.post(url, form, referer, extra);
    }

    protected static Map<String, String> headers(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] != null && kv[i + 1] != null) m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ==================== 分页结果 ====================

    /**
     * 部分源的搜索/章节接口一次给不完全，需要循环翻页。
     * 这个保护上限防止死循环（无限翻页会打爆站点也会卡死 App）。
     */
    protected static final int MAX_PAGES = 50;

    /** 判断章节 list 是否已经包含某集号 */
    protected static boolean hasIndex(List<Chapter> list, int index) {
        for (Chapter c : list) {
            if (c.index == index) return true;
        }
        return false;
    }

    /** 从 HTML 里数「共 N 页」 */
    protected static int totalPages(String html) {
        String v = grab(html, "共\\s*(\\d+)\\s*页");
        if (v.length() == 0) v = grab(html, "共(\\d+)页");
        try {
            return Integer.parseInt(v);
        } catch (Throwable t) {
            return 1;
        }
    }

    // ==================== 并发翻页 ====================

    /**
     * 并发拉一批 URL（章节分页专用），返回**按传入顺序**排列的响应体数组。
     *
     * <p>为什么需要它：有听网这类站，目录是「共 N 页」一页 100 集。
     * 一本 800 集的书就是 8 次串行 HTTP —— 源再好也要等 8 个来回。
     * 而这些页彼此独立，完全可以同时发。并发后总耗时 ≈ 最慢的一页，
     * 而不是所有页之和。
     *
     * <p>失败/超时的页返回 null（调用方自行跳过），单页失败不影响其它页。
     * 只有在「第 1 页拿不到」时才算整体失败（由调用方判断）。
     *
     * @param urls       要拉取的页 URL 列表（按顺序）
     * @param refs       每页对应的 Referer，可为 null（统一不带头）
     * @param ua         User-Agent，可为 null（用默认）
     * @param pool       线程池；传 null 用内置池
     * @param maxParallel 最大并发数
     */
    protected static String[] getParallel(java.util.List<String> urls,
                                          java.util.List<String> refs,
                                          final String ua,
                                          java.util.concurrent.ExecutorService pool,
                                          int maxParallel) {
        int n = urls == null ? 0 : urls.size();
        final String[] out = new String[n];
        if (n == 0) return out;
        if (maxParallel < 1) maxParallel = 1;
        if (maxParallel > n) maxParallel = n;

        java.util.concurrent.ExecutorService ex = pool != null ? pool
                : java.util.concurrent.Executors.newFixedThreadPool(
                        maxParallel, new java.util.concurrent.ThreadFactory() {
                            private int k = 0;
                            public Thread newThread(Runnable r) {
                                Thread t = new Thread(r, "ts-page-" + (++k));
                                t.setDaemon(true);
                                return t;
                            }
                        });
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final String u = urls.get(i);
            final String ref = (refs != null && i < refs.size()) ? refs.get(i) : null;
            ex.execute(new Runnable() {
                public void run() {
                    try {
                        out[idx] = ua == null ? Net.get(u, ref) : Net.get(u, ref, ua);
                    } catch (Throwable ignored) {
                        out[idx] = null;
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try {
            latch.await(PAGE_DEADLINE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {}
        return out;
    }

    /** 并发的章节分页池：页面请求都很小，给 6 个并发足够，也不至于把站点惹毛 */
    private static final java.util.concurrent.ExecutorService PAGE_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(6,
                    new java.util.concurrent.ThreadFactory() {
                        private int k = 0;
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "ts-chpage-" + (++k));
                            t.setDaemon(true);
                            return t;
                        }
                    });

    protected static java.util.concurrent.ExecutorService pagePool() {
        return PAGE_POOL;
    }

    /** 并发翻页的整体兜底：超过它就别等了，已有多少集用多少集 */
    protected static final long PAGE_DEADLINE_MS = 12000;

    // ==================== TTL 缓存（防限流 / 防签名过期） ====================

    private final Map<String, long[]> ttlExpire = new HashMap<String, long[]>();
    private final Map<String, String> ttlValue = new HashMap<String, String>();

    /** 写缓存，ttlMs 毫秒后过期 */
    protected synchronized void cachePut(String key, String value, long ttlMs) {
        ttlValue.put(key, value);
        ttlExpire.put(key, new long[]{System.currentTimeMillis() + ttlMs});
    }

    /** 读缓存，过期或不存在返回 null */
    protected synchronized String cacheGet(String key) {
        long[] exp = ttlExpire.get(key);
        if (exp == null) return null;
        if (System.currentTimeMillis() > exp[0]) {
            ttlValue.remove(key);
            ttlExpire.remove(key);
            return null;
        }
        return ttlValue.get(key);
    }

    /** 构造一个基础 Book（少写样板） */
    protected Book book(String bookId, String title, String url) {
        Book b = new Book();
        b.source = id();
        b.bookId = bookId;
        b.title = title;
        b.url = url;
        return b;
    }
}
