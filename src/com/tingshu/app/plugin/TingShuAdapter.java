package com.tingshu.app.plugin;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.BrowsableSource;
import com.tingshu.app.source.Category;
import com.tingshu.app.source.Source;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原版「我的听书」插件 → 本 App {@link Source} 的适配器。
 *
 * <p>把 {@link com.github.eprendre.tingshu.sources.TingShu}（原版 API）包装成
 * 宿主能直接用的源，并让它同时具备分类浏览能力（{@link BrowsableSource}）。
 *
 * <h3>反射而非直接调用</h3>
 * 插件是第三方 Kotlin 代码，编译目标/返回类型（如 {@code kotlin.Pair}）与宿主
 * 无关。直接强转会有类型链接风险，所以这里统一走反射取 {@code getFirst/getSecond}
 * 这类**方法名稳定**的访问器，最大程度兼容不同插件版本。
 *
 * <h3>取链关键点</h3>
 * 插件取链走 {@code AudioUrlCustomExtractor.setUp{...}} 把解析函数写进全局单例。
 * 并发场景下必须串行化，故调用
 * {@link com.github.eprendre.tingshu.sources.AudioUrlCustomExtractor#setupAndExtract}。
 */
public final class TingShuAdapter extends AbsSourceProxy {

    private final Object tingShu;          // com.github.eprendre.tingshu.sources.TingShu 实例
    private final String sourceId;
    private final String sourceName;
    private final String sourceDesc;
    private final String baseUrl;

    public TingShuAdapter(Object tingShu) {
        this.tingShu = tingShu;
        this.sourceId = "ep_" + nz(str(invoke(tingShu, "getSourceId", new Class[0], new Object[0])));
        this.sourceName = nz(str(invoke(tingShu, "getName", new Class[0], new Object[0])));
        this.sourceDesc = nz(str(invoke(tingShu, "getDesc", new Class[0], new Object[0])));
        this.baseUrl = nz(str(invoke(tingShu, "getUrl", new Class[0], new Object[0])));
    }

    /** 原始 TingShu 实例（调试/诊断用） */
    public Object raw() {
        return tingShu;
    }

    // ==================== 元信息 ====================

    @Override
    public String id() {
        return sourceId;
    }

    @Override
    public String name() {
        return sourceName.length() == 0 ? sourceId : sourceName;
    }

    @Override
    public String desc() {
        return sourceDesc.length() == 0 ? "插件源" : sourceDesc;
    }

    @Override
    public boolean canSearch() {
        try {
            Object r = invoke(tingShu, "isSearchable", new Class[0], new Object[0]);
            return r == null || Boolean.TRUE.equals(r) || "true".equals(String.valueOf(r));
        } catch (Throwable t) {
            return true;
        }
    }

    // ==================== 搜索 ====================

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        clearErr();
        Object pair = invoke(tingShu, "search",
                new Class[]{String.class, int.class},
                new Object[]{kw == null ? "" : kw, Integer.valueOf(page)});
        // ★ v4.21.38：插件内部抛错不再吞掉 —— 抛给聚合层显示真实原因
        if (pair == null) rethrowIfErr("插件源搜索失败");
        List<Book> out = new ArrayList<Book>();
        if (pair == null) return out;

        Object first = invoke(pair, "getFirst", new Class[0], new Object[0]);
        if (!(first instanceof List)) return out;
        List<?> list = (List<?>) first;
        for (int i = 0; i < list.size(); i++) {
            Book b = toBook(list.get(i));
            if (b != null && !empty(b.title) && !empty(b.url)) out.add(b);
        }
        return out;
    }

    /** 搜索返回的总页数（Pair.second）。解析失败给 1。 */
    public int searchTotalPages(String kw, int page) {
        try {
            Object pair = invoke(tingShu, "search",
                    new Class[]{String.class, int.class},
                    new Object[]{kw == null ? "" : kw, Integer.valueOf(page)});
            Object second = invoke(pair, "getSecond", new Class[0], new Object[0]);
            if (second instanceof Integer) return ((Integer) second).intValue();
            return parseInt(str(second), 1);
        } catch (Throwable t) {
            return 1;
        }
    }

    // ==================== 详情 ====================

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        BookDetail d = new BookDetail();
        d.book = new Book(sourceId, md5(bookUrl), "");
        d.book.url = bookUrl;

        Object bd = invoke(tingShu, "getBookDetailInfo",
                new Class[]{String.class, boolean.class, boolean.class},
                new Object[]{bookUrl, Boolean.TRUE, Boolean.TRUE});
        // ★ v4.21.38：详情加载失败不再吞成空详情
        if (bd == null) rethrowIfErr("插件源详情加载失败");
        if (bd == null) return d;

        String intro = nz(str(invoke(bd, "getIntro", new Class[0], new Object[0])));
        String author = nz(str(invoke(bd, "getAuthor", new Class[0], new Object[0])));
        String artist = nz(str(invoke(bd, "getArtist", new Class[0], new Object[0])));
        String cover = nz(str(invoke(bd, "getCoverUrl", new Class[0], new Object[0])));

        d.intro = intro;
        if (d.book != null) {
            d.book.intro = intro;
            d.book.author = author;
            d.book.artist = artist;
            if (cover.length() > 0) d.book.cover = cover;
        }

        Object pl = invoke(bd, "getPlayList", new Class[0], new Object[0]);
        List<Chapter> chs = new ArrayList<Chapter>();
        if (pl instanceof List) {
            List<?> list = (List<?>) pl;
            for (int i = 0; i < list.size(); i++) {
                Object ep = list.get(i);
                if (ep == null) continue;
                String t = nz(str(invoke(ep, "getTitle", new Class[0], new Object[0])));
                String u = nz(str(invoke(ep, "getUrl", new Class[0], new Object[0])));
                if (empty(u)) continue;
                if (empty(t)) t = "第" + (i + 1) + "集";
                chs.add(new Chapter(i + 1, t, u));
            }
        }
        d.chapters = chs;
        d.total = chs.size();
        return d;
    }

    // ==================== 取链 ====================

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        if (ch == null) return "";
        String target = !empty(ch.url) ? ch.url : ch.directUrl;
        if (empty(target)) return "";

        // 拿插件的取链器
        clearErr();
        Object extractor = invoke(tingShu, "getAudioUrlExtractor", new Class[0], new Object[0]);
        if (extractor == null) return target;

        // 若是 AudioUrlCustomExtractor（绝大多数插件都走它），走带锁的一次性取链
        if (isCustomExtractor(extractor)) {
            Object parse = peekParse(extractor);
            if (parse != null) {
                clearErr();
                String r = setupAndExtract(parse, target);
                if (empty(r)) {
                    // ★ v4.21.38：插件没解析出直链时抛错，而不是把章节页地址
                    //   当音频链接甩给播放器（那只会得到一句莫名其妙的播放失败）
                    rethrowIfErr("插件取链失败");
                    throw new Exception("插件未返回音频直链（站点可能已改版、被拦截或需要验证）");
                }
                return r;
            }
        }

        // 其它取链器：直接 extract()，但宿主无法回调拿到结果（原版是异步回调式），
        // 这里只能返回章节地址本身，让播放器尝试直连。
        try {
            invoke(extractor, "extract",
                    new Class[]{String.class, boolean.class, boolean.class, boolean.class},
                    new Object[]{target, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE});
        } catch (Throwable ignored) {}
        return target;
    }

    /**
     * 播放该音频需要的附加请求头。
     * 读插件实现的 {@code AudioUrlExtraHeaders}——这是让需要 Referer 的源能播的关键。
     */
    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        if (!implementsInterface(tingShu, "com.github.eprendre.tingshu.sources.AudioUrlExtraHeaders")) {
            return null;
        }
        try {
            Object m = invoke(tingShu, "headers",
                    new Class[]{String.class},
                    new Object[]{audioUrl == null ? "" : audioUrl});
            return toStringMap(m);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Map<String, String> coverHeaders(String coverUrl) {
        if (!implementsInterface(tingShu, "com.github.eprendre.tingshu.sources.CoverUrlExtraHeaders")) {
            return null;
        }
        try {
            // 插件会就地写入传入的 map，返回 true 才取用
            Map<String, String> bag = new HashMap<String, String>();
            Object r = invoke(tingShu, "coverHeaders",
                    new Class[]{String.class, Map.class},
                    new Object[]{coverUrl == null ? "" : coverUrl, bag});
            boolean ok = (r == null) || Boolean.TRUE.equals(r) || "true".equals(String.valueOf(r));
            if (!ok) return null;
            return bag.isEmpty() ? null : bag;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 分类浏览（BrowsableSource） ====================

    @Override
    public boolean canBrowse() {
        try {
            Object r = invoke(tingShu, "isDiscoverable", new Class[0], new Object[0]);
            boolean disc = (r == null) || Boolean.TRUE.equals(r) || "true".equals(String.valueOf(r));
            return disc && !categoryMenus().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public List<Category> categories() {
        List<Category> out = new ArrayList<Category>();
        List<Object> menus = categoryMenus();
        for (int i = 0; i < menus.size(); i++) {
            Object menu = menus.get(i);
            Object tabs = invoke(menu, "getTabs", new Class[0], new Object[0]);
            if (!(tabs instanceof List)) continue;
            List<?> tl = (List<?>) tabs;
            for (int j = 0; j < tl.size(); j++) {
                Object tab = tl.get(j);
                if (tab == null) continue;
                String title = nz(str(invoke(tab, "getTitle", new Class[0], new Object[0])));
                String url = nz(str(invoke(tab, "getUrl", new Class[0], new Object[0])));
                if (empty(url)) continue;
                out.add(new Category(url, empty(title) ? url : title));
            }
        }
        return out;
    }

    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        List<Book> out = new ArrayList<Book>();
        if (empty(catId)) return out;
        clearErr();
        Object cat = invoke(tingShu, "getCategoryList",
                new Class[]{String.class}, new Object[]{catId});
        if (cat == null) rethrowIfErr("插件源分类加载失败");
        if (cat == null) return out;
        Object list = invoke(cat, "getList", new Class[0], new Object[0]);
        if (!(list instanceof List)) return out;
        List<?> ll = (List<?>) list;
        for (int i = 0; i < ll.size(); i++) {
            Book b = toBook(ll.get(i));
            if (b != null && !empty(b.title) && !empty(b.url)) out.add(b);
        }
        return out;
    }

    // ==================== 内部工具 ====================

    @SuppressWarnings("unchecked")
    private List<Object> categoryMenus() {
        List<Object> out = new ArrayList<Object>();
        try {
            Object r = invoke(tingShu, "getCategoryMenus", new Class[0], new Object[0]);
            if (r instanceof List) {
                List<?> l = (List<?>) r;
                for (int i = 0; i < l.size(); i++) {
                    if (l.get(i) != null) out.add(l.get(i));
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 原版 Book → 宿主 Book */
    private Book toBook(Object o) {
        if (o == null) return null;
        try {
            String bookUrl = nz(str(invoke(o, "getBookUrl", new Class[0], new Object[0])));
            String title = nz(str(invoke(o, "getTitle", new Class[0], new Object[0])));
            String author = nz(str(invoke(o, "getAuthor", new Class[0], new Object[0])));
            String artist = nz(str(invoke(o, "getArtist", new Class[0], new Object[0])));
            String cover = nz(str(invoke(o, "getCoverUrl", new Class[0], new Object[0])));
            String intro = nz(str(invoke(o, "getIntro", new Class[0], new Object[0])));
            String status = nz(str(invoke(o, "getStatus", new Class[0], new Object[0])));

            Book b = new Book(sourceId, md5(bookUrl), title);
            b.url = bookUrl;
            b.author = author;
            b.artist = artist;
            b.cover = cover;
            b.intro = empty(intro) ? status : intro;
            if (!empty(status)) b.category = status;
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- 反射工具 ----

    /**
     * 最近一次反射调用失败的根因（线程局部，避免并发搜索时串源）。
     * ★ v4.21.38：关键链路（搜索/详情/浏览/取链）不再把插件异常吞成「无结果」，
     * 而是取这里记录的根因抛出去 —— 聚合层会把它标成「失败 + 原因」显示给用户，
     * 否则插件任何内部错误（站点 404 / WAF 拦截 / 解析异常）看起来都像「没搜到」。
     */
    private static final ThreadLocal<Throwable> LAST_ERR = new ThreadLocal<Throwable>();

    static void clearErr() {
        LAST_ERR.remove();
    }

    /** 有记录的错误则原样抛出（解包 InvocationTargetException） */
    static void rethrowIfErr(String what) throws Exception {
        Throwable t = LAST_ERR.get();
        if (t == null) return;
        LAST_ERR.remove();
        throw new Exception(what + "：" + rootMsg(t), t);
    }

    static String rootMsg(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String m = r.getMessage();
        String cls = r.getClass().getSimpleName();
        if (m == null || m.length() == 0) return cls;
        return cls + " " + m;
    }

    /** 按名+参数类型找方法并调用；找不到/出错返回 null（错误记入 LAST_ERR） */
    static Object invoke(Object target, String name, Class<?>[] types, Object[] args) {
        if (target == null) return null;
        try {
            Method m = findMethod(target.getClass(), name, types);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable t) {
            // 参数类型不完全匹配时退化：按名字挑第一个参数个数一样的
            try {
                Method m = findByNameArity(target.getClass(), name, args == null ? 0 : args.length);
                if (m == null) return null;
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (Throwable t2) {
                LAST_ERR.set(t2);
                return null;
            }
        }
    }

    private static Method findMethod(Class<?> c, String name, Class<?>[] types) {
        Class<?> k = c;
        while (k != null) {
            try {
                Method m = k.getDeclaredMethod(name, types);
                if (m != null) return m;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                return null;
            }
            k = k.getSuperclass();
        }
        // 接口默认实现
        return null;
    }

    private static Method findByNameArity(Class<?> c, String name, int arity) {
        Class<?> k = c;
        while (k != null) {
            Method[] ms = k.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                if (!ms[i].getName().equals(name)) continue;
                if (ms[i].getParameterTypes().length == arity) return ms[i];
            }
            k = k.getSuperclass();
        }
        return null;
    }

    private static boolean implementsInterface(Object o, String ifaceName) {
        Class<?> c = o.getClass();
        while (c != null) {
            Class<?>[] ifs = c.getInterfaces();
            for (int i = 0; i < ifs.length; i++) {
                if (ifaceName.equals(ifs[i].getName())) return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    private static boolean isCustomExtractor(Object extractor) {
        String n = extractor.getClass().getName();
        return n.startsWith("com.github.eprendre.tingshu.sources.AudioUrlCustomExtractor");
    }

    /** 从宿主侧单例读出当前 parse（插件刚 setUp 完的那个） */
    private static Object peekParse(Object extractor) {
        try {
            java.lang.reflect.Field f = extractor.getClass().getDeclaredField("parse");
            f.setAccessible(true);
            return f.get(extractor);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 走宿主自己的 AudioUrlCustomExtractor（Kotlin object）做加锁取链。
     *
     * <p>Java 侧无法直接把反射拿到的 {@code parse} 对象强转成 Kotlin 函数类型
     * （源码层面 Java 看不到 Kotlin 的箭头类型，只能看到 {@code Function1}），
     * 所以用一个 Java 实现的 {@link kotlin.jvm.functions.Function1} 包一层转发。
     */
    @SuppressWarnings("unchecked")
    private static String setupAndExtract(Object parse, String chapterUrl) {
        try {
            final kotlin.jvm.functions.Function1<Object, Object> f1 =
                    (kotlin.jvm.functions.Function1<Object, Object>) parse;
            kotlin.jvm.functions.Function1<String, String> bridge =
                    new kotlin.jvm.functions.Function1<String, String>() {
                        @Override
                        public String invoke(String s) {
                            try {
                                Object r = f1.invoke(s);
                                return r == null ? "" : String.valueOf(r);
                            } catch (Throwable t) {
                                LAST_ERR.set(t);
                                return "";
                            }
                        }
                    };
            return com.github.eprendre.tingshu.sources.AudioUrlCustomExtractor
                    .setupAndExtract(bridge, chapterUrl);
        } catch (Throwable t) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> toStringMap(Object o) {
        if (!(o instanceof Map)) return null;
        Map<?, ?> src = (Map<?, ?>) o;
        if (src.isEmpty()) return null;
        Map<String, String> out = new HashMap<String, String>();
        for (Map.Entry<?, ?> e : src.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return out.isEmpty() ? null : out;
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    static boolean empty(String s) {
        return s == null || s.length() == 0;
    }

    static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    static String md5(String s) {
        return com.tingshu.app.core.Utils.md5(s == null ? "" : s);
    }
}
