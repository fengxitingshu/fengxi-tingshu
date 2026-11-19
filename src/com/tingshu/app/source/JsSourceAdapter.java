package com.tingshu.app.source;

import com.tingshu.app.core.js.JsBridge;
import com.tingshu.app.core.js.JsEngine;
import com.tingshu.app.core.js.JsHostApi;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ★ v3.0：把一个 JS 源对象（插件里的 {@code registerSource({...})}）包成宿主 {@link Source}。
 *
 * <p>与 {@link com.tingshu.app.plugin.TingShuAdapter} 平级 —— 后者包装「我的听书」jar 源，
 * 本类包装 JS 脚本源。上层（Aggregator / UI）只认 {@code Source} 接口，完全无感。
 *
 * <h3>为什么实现 BrowsableSource 而不是只实现 Source</h3>
 * 这批 JS 源几乎全都带 {@code categoryMenus}（分类浏览），
 * 实现子接口后自动出现在「发现」页里，不用额外改动。
 *
 * <h3>线程安全</h3>
 * {@link JsEngine.Scope} 的全局作用域在并发进入时会互相干扰（Rhino 的 Scriptable
 * 不是线程安全的）。这里对每个源加一把锁，把「同一源的并发调用」串行化 ——
 * 实际场景下一个源很少被并发调用（搜索时每个源只调一次），代价可接受。
 */
public class JsSourceAdapter implements BrowsableSource {

    private final String id;
    private final String name;
    private final String desc;
    private final String group;
    private final boolean canSearch;
    /** 所属插件的包名（来自 manifest.json 的 name 字段） */
    private final String pkgName;
    /**
     * ★ v3.1：源的主站地址。
     *
     * <p>构造时本来就是从脚本里读出来喂给 {@link JsHostApi} 的，
     * 但之前读完就丢了。批量验证要去重（同一站点可能被 JSON 源和 JS 源同时覆盖），
     * 必须能拿到它做域名比对，所以在这里存下来。
     */
    private final String url;

    private final JsEngine.Scope scope;
    private final Scriptable src;
    private final Scriptable host;
    /** 串行化同一源的调用 */
    private final Object lock = new Object();

    /**
     * ★ v4.21.43 分类翻页链（补全 v4.21.42）。
     *
     * <p>插件契约：{@code categoryPage(url)} 只认 URL 尾部的 {@code #页码}，
     * 可选的返回值 {@code nextUrl} 给出下一页地址。
     *
     * <p>v4.21.42 只存了「每个 catId 最后一个 nextUrl」一个槽，且没有链时用
     * {@code replaceAll("#\d+$", ...)} 重写 —— 若插件给的分类地址尾部本来就没有
     * {@code #N}（番茄听书「直连」这类源就是纯地址），replaceAll 不命中，URL 原样
     * 回传，插件每页都返回第 1 页，前端连翻 5 页全是重复书，于是提示
     * 「后面几页都是重复内容」。
     *
     * <p>现在按 {@code (catId, page)} 记每一页该请求的地址：有 nextUrl 就顺链，
     * 没有就按契约把页号写成 {@code #N}（尾部已有 #N 则替换，没有则追加）。
     * 首屏 page==1 清链，避免刷新/换分类后旧链串页。
     */
    private final Map<String, String> pageUrlCache = new java.util.HashMap<String, String>();
    /** ★ v4.21.44：每页结果指纹 + 探测命中的分页模板 */
    private final Map<String, String> pageSigCache = new java.util.HashMap<String, String>();
    private final Map<String, String> pageTplCache = new java.util.HashMap<String, String>();

    // 分页 URL 规则统一交给 PagePaging（jar 插件源共用一份，避免两边各改各的）
    private static String pageKey(String catId, int page) { return PagePaging.key(catId, page); }
    private static String withPage(String catId, int page) { return PagePaging.withPage(catId, page); }
    private static String firstPageUrl(String catId) { return PagePaging.firstPageUrl(catId); }

    public JsSourceAdapter(String pluginId, JsEngine.Scope scope, Scriptable src,
                           JsHostApi.Callback cb) {
        this.scope = scope;
        this.src = src;
        this.pkgName = pluginId == null ? "" : pluginId;
        this.id = scope.str(src, "id");
        this.name = scope.str(src, "name");
        this.desc = scope.str(src, "description");
        this.group = scope.str(src, "group");
        this.canSearch = scope.hasFunction(src, "search");
        this.url = scope.str(src, "url");

        // host 必须在当前线程建（会 new Context），且要挂在源所属的 scope 上
        Context cx = JsEngine.enter();
        try {
            JsHostApi api = new JsHostApi(pluginId, this.url, cb);
            this.host = api.build(cx, scope.global());
        } finally {
            JsEngine.exit();
        }

        // 把 host 绑到源对象上（脚本里用 this.host）
        Context cx2 = JsEngine.enter();
        try {
            ScriptableObject.putProperty(src, "host", host);
        } finally {
            JsEngine.exit();
        }
    }

    /** 对外暴露分组名（源管理页按此分桶） */
    public String group() { return group; }

    /** 该源所属的插件包名（manifest.json 的 name），源管理页用来归类展示 */
    public String pkgName() { return pkgName; }

    /** ★ v3.1：源的主站地址（批量验证按域名去重时用） */
    public String url() { return url; }

    // ==================== Source ====================

    @Override public String id() { return id; }
    @Override public String name() { return name == null || name.length() == 0 ? id : name; }
    @Override public String desc() { return desc == null ? "" : desc; }
    @Override public boolean canSearch() { return canSearch; }

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        if (!canSearch) return new ArrayList<Book>();
        synchronized (lock) {
            Object r = scope.call(src, "search", new Object[]{kw, Integer.valueOf(page)});
            if (!(r instanceof Scriptable)) return new ArrayList<Book>();
            Object books = ScriptableObject.getProperty((Scriptable) r, "books");
            return JsBridge.toBooks(id, books);
        }
    }

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        synchronized (lock) {
            BookDetail d = new BookDetail();
            Book b = new Book();
            b.source = id;
            b.url = bookUrl;
            b.bookId = bookUrl;
            d.book = b;

            // 契约有两种签名：2 参 与 3 参（末位 loadFullPages）
            // 这里统一用 3 参调用（多传一个参数 JS 侧会忽略，不会报错）
            Object r;
            try {
                r = scope.call(src, "bookDetail",
                        new Object[]{bookUrl, Boolean.TRUE, Boolean.TRUE});
            } catch (Throwable t) {
                // 有的源 bookDetail 只声明 2 参且内部做了 arguments.length 校验，退回 2 参
                r = scope.call(src, "bookDetail", new Object[]{bookUrl, Boolean.TRUE});
            }
            if (r instanceof Scriptable) JsBridge.fillDetail(d, (Scriptable) r);
            return d;
        }
    }

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        if (ch == null) return null;
        // 有些源详情页就直接给了直链，省一次请求
        if (ch.directUrl != null && ch.directUrl.length() > 0) return ch.directUrl;
        synchronized (lock) {
            Object r = scope.call(src, "audio", new Object[]{ch.url == null ? "" : ch.url});
            if (r == null || r == Undefined.instance) return null;
            String s = Context.toString(r);
            return s == null || s.length() == 0 ? null : s;
        }
    }

    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        return callHeaderFn("audioHeaders", audioUrl);
    }

    @Override
    public Map<String, String> coverHeaders(String coverUrl) {
        return callHeaderFn("coverHeaders", coverUrl);
    }

    /** audioHeaders / coverHeaders 共用的调用逻辑（失败一律返回 null，不影响播放） */
    private Map<String, String> callHeaderFn(String fnName, String url) {
        if (!scope.hasFunction(src, fnName)) return null;
        synchronized (lock) {
            try {
                Object r = scope.call(src, fnName, new Object[]{url == null ? "" : url});
                return JsBridge.toHeaderMap(r);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    // ==================== BrowsableSource ====================

    @Override
    public boolean canBrowse() {
        return scope.hasFunction(src, "categoryMenus");
    }

    @Override
    public List<Category> categories() {
        List<Category> out = new ArrayList<Category>();
        if (!canBrowse()) return out;
        synchronized (lock) {
            try {
                Object r = scope.call(src, "categoryMenus", new Object[0]);
                int n = JsBridge.len(r);
                for (int i = 0; i < n; i++) {
                    Object g = JsBridge.at(r, i);
                    if (!(g instanceof Scriptable)) continue;
                    String gTitle = JsBridge.str((Scriptable) g, "title");
                    Object tabs = ScriptableObject.getProperty((Scriptable) g, "tabs");
                    int tn = JsBridge.len(tabs);
                    for (int j = 0; j < tn; j++) {
                        Object t = JsBridge.at(tabs, j);
                        if (!(t instanceof Scriptable)) continue;
                        String tTitle = JsBridge.str((Scriptable) t, "title");
                        String url = JsBridge.str((Scriptable) t, "url");
                        if (url.length() == 0) continue;
                        // id 里塞「分组名\u0001URL」，browse() 时拆回来
                        // 这样分类页 UI 能显示层级感，又不破坏 Category(id,name) 的两字段结构
                        String label = gTitle.length() > 0 && !gTitle.equals(tTitle)
                                ? gTitle + " · " + tTitle : tTitle;
                        out.add(new Category(url, label));
                    }
                }
            } catch (Throwable ignored) {}
        }
        return out;
    }

    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        if (catId == null || catId.length() == 0) return new ArrayList<Book>();
        if (page < 1) page = 1;
        synchronized (lock) {
            String url;
            String mode;
            String tpl = pageTplCache.get(catId);
            if (page == 1) {
                // 首屏 / 刷新 / 换分类：从原始地址重新起链
                clearChain(catId);
                url = firstPageUrl(catId);
                mode = "start";
            } else if (tpl != null && tpl.length() > 0) {
                url = PagePaging.applyTpl(tpl, page);
                mode = "tpl";
            } else {
                String chained = pageUrlCache.get(pageKey(catId, page));
                if (chained != null && chained.length() > 0) {
                    url = chained;
                    mode = "chain";
                } else {
                    // 插件没给 nextUrl（或跳页）→ 按契约把页号写进 #N
                    url = withPage(catId, page);
                    mode = "hash";
                }
            }
            // 别原地打转：算出来的地址跟上一页一样，就强制换成带页号的地址
            String prev = pageUrlCache.get(pageKey(catId, page - 1));
            if (url.equals(prev)) {
                url = withPage(catId, page);
                mode = "hash-force";
            }
            pageUrlCache.put(pageKey(catId, page), url);

            Object r = callCategoryPage(url);
            if (!(r instanceof Scriptable)) return new ArrayList<Book>();
            List<Book> books = JsBridge.toBooks(
                    id, ScriptableObject.getProperty((Scriptable) r, "books"));
            String sig = PagePaging.sig(books);
            String prevSig = page > 1 ? pageSigCache.get(pageKey(catId, page - 1)) : null;

            // 本页与上一页完全相同 ⇒ 猜的分页写法没生效，换写法探测
            if (page > 1 && sig.length() > 0 && sig.equals(prevSig)) {
                int tried = 0;
                for (String[] cand : PagePaging.candidates(catId, page)) {
                    if (tried >= 6 || cand[0].equals(url)) continue;
                    tried++;
                    Object r2 = callCategoryPageQuiet(cand[0]);
                    if (!(r2 instanceof Scriptable)) continue;
                    List<Book> b2 = JsBridge.toBooks(
                            id, ScriptableObject.getProperty((Scriptable) r2, "books"));
                    String s2 = PagePaging.sig(b2);
                    if (s2.length() == 0 || s2.equals(prevSig)) continue;
                    url = cand[0];
                    r = r2;
                    books = b2;
                    sig = s2;
                    pageTplCache.put(catId, cand[1]);
                    mode = "probe";
                    break;
                }
            }
            pageUrlCache.put(pageKey(catId, page), url);
            pageSigCache.put(pageKey(catId, page), sig);

            String next = JsBridge.str((Scriptable) r, "nextUrl");
            // nextUrl 与当前地址相同 = 插件没往前走，忽略它
            boolean hasNext = next.length() > 0 && !next.equals(url);
            if (hasNext) pageUrlCache.put(pageKey(catId, page + 1), next);
            else pageUrlCache.remove(pageKey(catId, page + 1));

            // 脱敏诊断：只打页号 / 模式 / 条数，绝不回显任何 URL 或域名
            try {
                android.util.Log.i("FxBrows", "page=" + page + " mode=" + mode
                        + " next=" + (hasNext ? 1 : 0) + " n=" + books.size());
            } catch (Throwable ignored) {}
            return books;
        }
    }

    /** 首屏清链：顺带清掉结果指纹与探测模板 */
    private void clearChain(String catId) {
        String prefix = catId + PagePaging.SEP;
        java.util.Iterator<Map.Entry<String, String>> it =
                pageUrlCache.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey().startsWith(prefix)) it.remove();
        }
        it = pageSigCache.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey().startsWith(prefix)) it.remove();
        }
        pageTplCache.remove(catId);
    }

    private Object callCategoryPage(String url) {
        return scope.call(src, "categoryPage", new Object[]{url});
    }

    /** 探测用的静默调用：插件抛错不能影响主流程 */
    private Object callCategoryPageQuiet(String url) {
        try {
            return callCategoryPage(url);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 分类页的下一页地址（供上层按需取用）。
     *
     * <p>优先给插件实际返回的 nextUrl；插件没给就按契约补 {@code #2}。
     * 不再像旧实现那样用原始 catId 重新请求一次 —— 那只会拿到第 1 页的链。
     */
    public String categoryPageNext(String catId) {
        if (catId == null || catId.length() == 0) return null;
        synchronized (lock) {
            String nx = pageUrlCache.get(pageKey(catId, 2));
            if (nx != null && nx.length() > 0) return nx;
            return withPage(catId, 2);
        }
    }

    public String nextUrlOf(String catId) { return categoryPageNext(catId); }

    /**
     * 该源是否需要用户先登录（契约里的 loginUrl）。
     * 前端可据此提示；本期只做展示，不做登录闭环。
     */
    public String loginUrl() { return scope.str(src, "loginUrl"); }

    /** 是否声明了多页章节（影响详情页是否提示"加载全部"） */
    public boolean multipleEpisodePages() { return scope.bool(src, "multipleEpisodePages"); }

    // ==================== 人机验证相关（契约字段） ====================

    /**
     * 该源的「验证页」地址。
     *
     * <p>乐听吧 / 米听书 / 囧贼听书网 / 一夜幻听 这几个站对裸请求直接返空，
     * 脚本里给了 {@code searchVerificationUrl(keywords)}，让用户在内置浏览器里
     * 打开去输验证码；验证通过后站点会给浏览器发 Cookie，之后的请求就能通。
     *
     * <p>本期**只负责把 URL 交出去**，不做「验证完自动重搜」的闭环。
     *
     * @return 需要验证时返回 URL，否则返回 ""（不适用）
     */
    public String searchVerificationUrl(String keywords) {
        if (!scope.hasFunction(src, "searchVerificationUrl")) return "";
        synchronized (lock) {
            try {
                Object r = scope.call(src, "searchVerificationUrl",
                        new Object[]{keywords == null ? "" : keywords});
                if (r == null || r == Undefined.instance) return "";
                String s = Context.toString(r);
                return s == null ? "" : s;
            } catch (Throwable t) {
                return "";
            }
        }
    }

    /** 验证页是否要用桌面 UA 打开（契约里的 searchVerificationDesktop） */
    public boolean searchVerificationDesktop() {
        return scope.bool(src, "searchVerificationDesktop");
    }

    /**
     * 两次搜索之间的强制间隔（秒），契约里的 searchDelaySeconds。
     * 有些站限制频繁搜索，靠这个值节流；0 表示不限。
     */
    public int searchDelaySeconds() {
        return scope.num(src, "searchDelaySeconds", 0);
    }

    /** 该源是否声明了「搜索可能被拦截」的验证入口 */
    public boolean needsVerification() {
        return scope.hasFunction(src, "searchVerificationUrl");
    }
}
