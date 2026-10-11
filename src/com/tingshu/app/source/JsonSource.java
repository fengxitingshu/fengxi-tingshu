package com.tingshu.app.source;

import com.tingshu.app.core.Json;
import com.tingshu.app.core.Net;
import com.tingshu.app.core.Utils;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.data.SourceConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用抓站引擎：把一份 {@link SourceConfig} 解释成可用的 {@link Source}。
 *
 * 核心是「取值器」抽象——配置里每个字段（title / bookUrl / field ...）都是一段
 * 表达式，引擎按类型决定怎么解释它：
 * <ul>
 *   <li>type=html：字段值是正则，取 group(1)</li>
 *   <li>type=json：字段值是点路径，如 {@code data.list.0.name}</li>
 * </ul>
 * 另外字段值里支持少量占位符做拼接，覆盖大多数字站：
 * <pre>
 *   {base}   源 base
 *   {url}    当前上下文 URL（详情/取链页）
 *   {id}     从 URL 抽出的数字 id 或最后一段路径
 *   {kw}     关键词（URL 编码）
 *   {kwRaw}  关键词原文
 *   {page}   页码
 *   {src}    上一步取到的值（取链时常用，如把 id 拼进 api）
 * </pre>
 */
public final class JsonSource extends AbsSource implements BrowsableSource {

    private final SourceConfig cfg;

    public JsonSource(SourceConfig cfg) {
        this.cfg = cfg;
    }

    public SourceConfig config() {
        return cfg;
    }

    // ==================== 元信息 ====================

    @Override
    public String id() {
        return cfg.id;
    }

    @Override
    public String name() {
        return Utils.empty(cfg.name) ? cfg.id : cfg.name;
    }

    @Override
    public String desc() {
        return Utils.empty(cfg.desc) ? "自定义源" : cfg.desc;
    }

    @Override
    public boolean canSearch() {
        return cfg.search != null;
    }

    // ==================== 分类浏览（发现页） ====================

    @Override
    public boolean canBrowse() {
        return cfg.browse != null && cfg.browse.cats != null && cfg.browse.cats.length() > 0
                && !Utils.empty(cfg.browse.items);
    }

    @Override
    public List<Category> categories() {
        List<Category> out = new ArrayList<Category>();
        if (cfg.browse == null || cfg.browse.cats == null) return out;
        JSONArray cats = cfg.browse.cats;
        for (int i = 0; i < cats.length(); i++) {
            Object it = cats.opt(i);
            if (it instanceof JSONObject) {
                JSONObject o = (JSONObject) it;
                String k = Json.pick(o, "k", "key", "id", "url", "value");
                String n = Json.pick(o, "n", "name", "title", "label");
                if (Utils.empty(k)) continue;
                out.add(new Category(k, Utils.empty(n) ? k : n));
            } else if (it != null) {
                // 纯字符串数组的简写：k 与 n 都是它自己
                String v = String.valueOf(it).trim();
                if (!Utils.empty(v)) out.add(new Category(v, v));
            }
        }
        return out;
    }

    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        List<Book> out = new ArrayList<Book>();
        SourceConfig.Browse b = cfg.browse;
        if (b == null || Utils.empty(b.items)) return out;
        if (page <= 0) page = 1;

        // 分类值：以 http 开头当完整 URL，否则按 url 模板拼（模板缺省为 base/{cat}）
        String url = browseUrl(b, catId, page);
        if (Utils.empty(url)) return out;

        String html = fetch(url, "get", null, Utils.empty(catId) ? cfg.base : url);
        if (Utils.empty(html)) return out;

        if ("json".equals(b.type)) {
            JSONArray arr = jsonArrayAt(html, b.items);
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Book bk = browseJsonItem(o, b, url);
                if (bk != null && !Utils.empty(bk.title) && !Utils.empty(bk.url)) out.add(bk);
            }
        } else {
            List<String> blocks = grabBlocks(html, b.items);
            for (int i = 0; i < blocks.size(); i++) {
                Book bk = browseHtmlBlock(blocks.get(i), b, url);
                if (bk != null && !Utils.empty(bk.title) && !Utils.empty(bk.url)) out.add(bk);
            }
        }

        if (b.reverse && out.size() > 1) {
            List<Book> rev = new ArrayList<Book>(out.size());
            for (int i = out.size() - 1; i >= 0; i--) rev.add(out.get(i));
            out = rev;
        }
        return out;
    }

    /** 拼分类列表页 URL：{cat} 分类值、{page} 页码 */
    private String browseUrl(SourceConfig.Browse b, String catId, int page) {
        String cat = catId == null ? "" : catId;
        if (cat.startsWith("http://") || cat.startsWith("https://")) {
            // 分类本身就是完整地址，仍支持 {page} 替换
            if (!Utils.empty(b.url) && b.url.indexOf('{') >= 0) {
                return tpl(b.url, null, page, cat, null, null);
            }
            return cat;
        }
        String t = Utils.empty(b.url) ? "{base}/{cat}" : b.url;
        String s = t;
        s = s.replace("{cat}", cat);
        s = s.replace("{page}", String.valueOf(page));
        s = s.replace("{base}", cfg.base == null ? "" : cfg.base);
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        return Utils.abs(cfg.base + "/", s);
    }

    private Book browseHtmlBlock(String blk, SourceConfig.Browse b, String pageUrl) {
        PageCtx cx = new PageCtx(pageUrl);
        String title = text(pick(blk, b.title, "html", cx));
        String href = pick(blk, b.urlField, "html", cx);
        if (Utils.empty(title) || Utils.empty(href)) return null;
        href = Utils.abs(cfg.base + "/", unescape(href));
        Book bk = book(bookIdOf(href), title, href);
        if (!Utils.empty(b.author)) bk.author = text(stripLabel(pick(blk, b.author, "html", cx)));
        if (!Utils.empty(b.cover)) bk.cover = Utils.abs(cfg.base + "/", unescape(pick(blk, b.cover, "html", cx)));
        if (!Utils.empty(b.intro)) bk.intro = text(pick(blk, b.intro, "html", cx));
        return bk;
    }

    private Book browseJsonItem(JSONObject o, SourceConfig.Browse b, String pageUrl) {
        String title = jsonPick(o, b.title);
        String href = jsonPick(o, b.urlField);
        if (Utils.empty(title) || Utils.empty(href)) return null;
        String rawId = href;
        href = Utils.abs(cfg.base + "/", unescape(href));
        Book bk = book(bookIdOf(rawId), text(title), href);
        if (!Utils.empty(b.author)) bk.author = text(jsonPick(o, b.author));
        if (!Utils.empty(b.cover)) bk.cover = Utils.abs(cfg.base + "/", unescape(jsonPick(o, b.cover)));
        if (!Utils.empty(b.intro)) bk.intro = text(jsonPick(o, b.intro));
        return bk;
    }

    // ==================== 请求 ====================

    /** 附加配置里的全局请求头 */
    private Map<String, String> extraHeaders() {
        Map<String, String> m = new HashMap<String, String>();
        if (cfg.headers != null) {
            java.util.Iterator<String> it = cfg.headers.keys();
            while (it != null && it.hasNext()) {
                String k = it.next();
                String v = cfg.headers.optString(k, "");
                if (!Utils.empty(k) && !Utils.empty(v)) m.put(k, v);
            }
        }
        return m;
    }

    /** 按模板发起请求，返回原始响应体 */
    private String fetch(String url, String method, String body, String referer) {
        if (Utils.empty(url)) return "";
        Map<String, String> ex = extraHeaders();
        Map<String, String> hdr = ex.isEmpty() ? null : ex;
        String ref = Utils.empty(referer) ? cfg.base : referer;
        if ("post".equals(method)) {
            return Net.post(url, body == null ? "" : body, ref, hdr);
        }
        Net.Resp r = Net.request(url, null, ref, hdr, false);
        return r == null ? "" : r.body;
    }

    /** 模板占位符替换 */
    private String tpl(String t, String kw, int page, String url, String id, String src) {
        if (t == null) return "";
        String s = t;
        s = s.replace("{base}", cfg.base == null ? "" : cfg.base);
        s = s.replace("{url}", url == null ? "" : url);
        s = s.replace("{id}", id == null ? "" : id);
        s = s.replace("{kwRaw}", kw == null ? "" : kw);
        s = s.replace("{kw}", Utils.enc(kw == null ? "" : kw));
        s = s.replace("{page}", String.valueOf(page));
        s = s.replace("{src}", src == null ? "" : src);
        // 兼容 printf 风格：第一个 %s 给 kw，第二个 %s 给 page
        if (s.indexOf("%s") >= 0) {
            int i1 = s.indexOf("%s");
            s = s.substring(0, i1) + Utils.enc(kw == null ? "" : kw) + s.substring(i1 + 2);
            int i2 = s.indexOf("%s");
            if (i2 >= 0) s = s.substring(0, i2) + page + s.substring(i2 + 2);
        }
        // 已经是绝对地址就直接用，否则拼 base
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        return Utils.abs(cfg.base + "/", s);
    }

    // ==================== 取值器 ====================

    /**
     * 从「一段文本」里按配置取值。
     * type=html 走正则 group(1)；type=json 走点路径。
     */
    private String pick(String body, String expr, String type, PageCtx ctx) {
        if (Utils.empty(expr)) return "";
        expr = subst(expr, ctx, null);
        if ("json".equals(type)) {
            String v = Json.path(body, expr);
            return v == null ? "" : v;
        }
        return grab(body, expr);
    }

    /** 在字段表达式里替换上下文占位符（不含 kw/page） */
    private String subst(String expr, PageCtx ctx, String src) {
        if (expr == null) return "";
        if (ctx == null) return expr;
        String s = expr;
        s = s.replace("{base}", cfg.base == null ? "" : cfg.base);
        s = s.replace("{url}", ctx.url == null ? "" : ctx.url);
        s = s.replace("{id}", ctx.id == null ? "" : ctx.id);
        if (src != null) s = s.replace("{src}", src);
        return s;
    }

    /** 一页的上下文 */
    private static final class PageCtx {
        String url = "";
        String id = "";
        String pageUrl = "";   // 用于 Referer

        PageCtx(String url) {
            this.url = url == null ? "" : url;
            this.id = extractId(this.url);
            this.pageUrl = this.url;
        }
    }

    /** 从 URL 里提取一个可用的 id：优先纯数字，其次最后一段路径/参数 */
    static String extractId(String url) {
        if (Utils.empty(url)) return "";
        try {
            String u = url;
            // 优先找形如 /12345 或 =12345 的数字串
            Matcher m = Pattern.compile("(\\d{2,})").matcher(u);
            if (m.find()) return m.group(1);
            String path = u;
            int q = path.indexOf('?');
            if (q > 0) path = path.substring(0, q);
            int h = path.indexOf("://");
            if (h > 0) path = path.substring(h + 3);
            int s = path.indexOf('/');
            if (s > 0) path = path.substring(s + 1);
            path = path.replaceAll("\\.(html?|php|aspx?|jsp)$", "");
            int last = path.lastIndexOf('/');
            if (last >= 0) path = path.substring(last + 1);
            return path;
        } catch (Throwable t) {
            return "";
        }
    }

    // ==================== 搜索 ====================

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        List<Book> out = new ArrayList<Book>();
        if (cfg.search == null) return out;
        SourceConfig.Search s = cfg.search;

        String url = tpl(s.url, kw, page, null, null, null);
        String body = tpl(s.body, kw, page, null, null, null);
        String html = fetch(url, s.method, body, cfg.base);
        if (Utils.empty(html)) return out;

        if ("json".equals(s.type)) {
            JSONArray arr = jsonArrayAt(html, s.items);
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Book b = fromJsonItem(o, s, url);
                if (b != null && !Utils.empty(b.title) && !Utils.empty(b.url)) out.add(b);
            }
        } else {
            // HTML：items 是「包住整条」的正则，用 group(0) 切块，再在块内取值
            List<String> blocks = grabBlocks(html, s.items);
            for (int i = 0; i < blocks.size(); i++) {
                String blk = blocks.get(i);
                Book b = fromHtmlBlock(blk, s, url);
                if (b != null && !Utils.empty(b.title) && !Utils.empty(b.url)) out.add(b);
            }
        }

        // 分页：配置了 hasNext 就继续往后抓，直到没有或到上限
        if (!Utils.empty(s.hasNext) && page < s.maxPages) {
            // 只抓本页，翻页由上层 page 参数驱动（避免一次请求打太多）
            // 这里只在「首页」时给出下一页是否存在的信息，不做批量抓取。
        }
        return out;
    }

    /** 从 HTML 块里解析一条结果 */
    private Book fromHtmlBlock(String blk, SourceConfig.Search s, String pageUrl) {
        String title = pick(blk, s.title, "html", new PageCtx(pageUrl));
        String href = pick(blk, s.urlField, "html", new PageCtx(pageUrl));
        title = text(title);
        if (Utils.empty(title) || Utils.empty(href)) return null;
        href = Utils.abs(cfg.base + "/", unescape(href));
        Book b = book(bookIdOf(href), title, href);
        if (!Utils.empty(s.author)) b.author = text(stripLabel(pick(blk, s.author, "html", new PageCtx(pageUrl))));
        if (!Utils.empty(s.cover)) b.cover = Utils.abs(cfg.base + "/", unescape(pick(blk, s.cover, "html", new PageCtx(pageUrl))));
        if (!Utils.empty(s.intro)) b.intro = text(pick(blk, s.intro, "html", new PageCtx(pageUrl)));
        return b;
    }

    /** 从 JSON 条目里解析一条结果 */
    private Book fromJsonItem(JSONObject o, SourceConfig.Search s, String pageUrl) {
        String title = jsonPick(o, s.title);
        String href = jsonPick(o, s.urlField);
        if (Utils.empty(title) || Utils.empty(href)) return null;
        String rawId = href;
        href = Utils.abs(cfg.base + "/", unescape(href));
        // bookId 用「原始值」而不是补全后的 URL：JSON 源里常常就是个纯数字 id，
        // 详情页/取链模板里的 {id} 需要的正是它（extractId 对纯数字也能反推，
        // 但对含字母的 id 会失效，所以这里显式存下来最稳）。
        Book b = book(bookIdOf(rawId), text(title), href);
        if (!Utils.empty(s.author)) b.author = text(jsonPick(o, s.author));
        if (!Utils.empty(s.cover)) b.cover = Utils.abs(cfg.base + "/", unescape(jsonPick(o, s.cover)));
        if (!Utils.empty(s.intro)) b.intro = text(jsonPick(o, s.intro));
        return b;
    }

    /** JSON 条目字段取值：点路径支持带数组下标，如 list.0.name */
    private String jsonPick(JSONObject o, String expr) {
        if (o == null || Utils.empty(expr)) return "";
        if (expr.indexOf('.') < 0) return Json.pick(o, expr);
        // 有路径就整体走 path
        return Json.path(o.toString(), expr);
    }

    /** 结果数组定位：支持 data.list / result.songs 等 */
    private JSONArray jsonArrayAt(String body, String path) {
        JSONObject o = Json.obj(body);
        if (o == null) return null;
        if (Utils.empty(path)) {
            JSONArray a = o.optJSONArray("data");
            return a != null ? a : o.optJSONArray("list");
        }
        String[] parts = path.split("\\.");
        Object cur = o;
        for (int i = 0; i < parts.length; i++) {
            if (cur instanceof JSONObject) {
                cur = ((JSONObject) cur).opt(parts[i]);
            } else if (cur instanceof JSONArray) {
                try {
                    cur = ((JSONArray) cur).opt(Integer.parseInt(parts[i]));
                } catch (Throwable t) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return cur instanceof JSONArray ? (JSONArray) cur : null;
    }

    /** 用书链接当 bookId（稳定且唯一） */
    private String bookIdOf(String url) {
        String h = Utils.md5(url == null ? "" : url);
        return h.length() >= 16 ? h.substring(0, 16) : h;
    }

    // ==================== 详情 ====================

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        BookDetail d = new BookDetail();
        SourceConfig.Detail dc = cfg.detail;
        if (dc == null || Utils.empty(bookUrl)) return d;

        // 章节所在页：默认书本身那页
        String pageUrl = Utils.empty(dc.page)
                ? bookUrl
                : tpl(dc.page, null, 1, bookUrl, extractId(bookUrl), null);
        String method = Utils.empty(dc.method) ? "get" : dc.method;
        String body = fetch(pageUrl, method, null, bookUrl);
        if (Utils.empty(body)) return d;

        // ★ v1.9：配置了 pages 就拉多页（并发）。原来只读第 1 页，
        //   长书的章节会缺一大截，用户还得自己发现"少了后 200 集"。
        //   pages = 0（缺省）时保持旧行为：只拉 1 页，不发多余请求。
        List<String> bodies = new ArrayList<String>();
        bodies.add(body);
        if (dc.pages > 1) {
            List<String> urls = new ArrayList<String>();
            List<String> refs = new ArrayList<String>();
            for (int pg = 2; pg <= dc.pages; pg++) {
                urls.add(tpl(pageUrl, null, pg, bookUrl, extractId(bookUrl), null));
                refs.add(bookUrl);
            }
            String[] more = getParallel(urls, refs, null, pagePool(), 4);
            for (int i = 0; i < more.length; i++) {
                if (!Utils.empty(more[i])) bodies.add(more[i]);
            }
        }

        List<Chapter> list = new ArrayList<Chapter>();
        if ("json".equals(dc.type)) {
            for (int bi = 0; bi < bodies.size(); bi++) {
                JSONArray arr = jsonArrayAt(bodies.get(bi), dc.items);
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String t = jsonPick(o, dc.title);
                    String u = jsonPick(o, dc.urlField);
                    if (Utils.empty(u)) continue;
                    if (Utils.empty(t)) t = "第" + (list.size() + 1) + "集";
                    list.add(new Chapter(list.size() + 1, text(t),
                            Utils.abs(cfg.base + "/", unescape(u))));
                }
            }
        } else {
            for (int bi = 0; bi < bodies.size(); bi++) {
                String pgBody = bodies.get(bi);
                List<String> blocks = grabBlocks(pgBody, dc.items);
                for (int i = 0; i < blocks.size(); i++) {
                    String blk = blocks.get(i);
                    PageCtx cx = new PageCtx(pageUrl);
                    String t = pick(blk, dc.title, "html", cx);
                    String u = pick(blk, dc.urlField, "html", cx);
                    t = text(t);
                    if (Utils.empty(u)) continue;
                    if (Utils.empty(t)) t = "第" + (list.size() + 1) + "集";
                    list.add(new Chapter(list.size() + 1, t,
                            Utils.abs(cfg.base + "/", unescape(u))));
                }
            }
        }

        if (dc.reverse && list.size() > 1) {
            List<Chapter> rev = new ArrayList<Chapter>(list.size());
            for (int i = list.size() - 1; i >= 0; i--) rev.add(list.get(i));
            list = rev;
        }
        // 重排集号，保证 1 基连续
        for (int i = 0; i < list.size(); i++) list.get(i).index = i + 1;
        d.chapters = list;
        d.total = list.size();

        // 详情页覆盖书名/作者/简介
        Book b = new Book();
        b.source = id();
        b.bookId = bookIdOf(bookUrl);
        b.url = bookUrl;
        PageCtx cx = new PageCtx(pageUrl);
        String bt = Utils.empty(dc.bookTitle) ? "" : text(pick(body, dc.bookTitle, dc.type, cx));
        // 书名一般来自搜索结果，这里只在能取到时覆盖
        b.title = bt;
        if (!Utils.empty(dc.author)) b.author = text(stripLabel(pick(body, dc.author, dc.type, cx)));
        if (!Utils.empty(dc.intro)) b.intro = text(pick(body, dc.intro, dc.type, cx));
        if (!Utils.empty(dc.cover)) {
            String cv = pick(body, dc.cover, dc.type, cx);
            if (!Utils.empty(cv)) b.cover = Utils.abs(cfg.base + "/", unescape(cv));
        }
        d.book = b;
        d.intro = b.intro;
        return d;
    }

    // ==================== 取链 ====================

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        SourceConfig.Audio a = cfg.audio;
        if (a == null || ch == null) return "";

        if ("direct".equals(a.mode)) {
            // 章节链接本身就是直链；详情里已填则直接用
            String u = ch.directUrl;
            if (Utils.empty(u)) u = ch.url;
            return unescape(u);
        }

        String base = Utils.empty(ch.url) ? (book == null ? "" : book.url) : ch.url;
        PageCtx ctx = new PageCtx(base);
        String url = tpl(a.url, null, 1, base, ctx.id, null);
        if (Utils.empty(url)) url = base;

        // 取链结果做 TTL 缓存，避免同一集反复请求
        String ck = "au:" + url;
        String hit = cacheGet(ck);
        if (hit != null) return hit;

        String referer = Utils.empty(ch.url) ? cfg.base : ch.url;
        String body = fetch(url, a.method, null, referer);
        if (Utils.empty(body)) return "";

        String out;
        if ("json".equals(a.mode)) {
            out = Json.path(body, a.field);
        } else {
            out = grabGroup(body, a.field, a.group);
        }
        if (a.unescape) out = unescape(out);
        out = out.trim();
        // 相对路径补全成绝对地址
        if (!Utils.empty(out) && !out.startsWith("http")) {
            out = Utils.abs(cfg.base + "/", out);
        }
        if (!Utils.empty(out)) cachePut(ck, out, 10 * 60 * 1000L);
        return out;
    }

    /** 取第 n 个捕获组 */
    private static String grabGroup(String s, String regex, int group) {
        if (s == null || Utils.empty(regex)) return "";
        try {
            Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(s);
            if (!m.find()) return "";
            if (group <= 0) return m.group(0).trim();
            return group <= m.groupCount() ? m.group(group).trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        Map<String, String> m = extraHeaders();
        return m.isEmpty() ? null : m;
    }

    @Override
    public Map<String, String> coverHeaders(String coverUrl) {
        Map<String, String> m = extraHeaders();
        return m.isEmpty() ? null : m;
    }

    // ==================== 自检 ====================

    /**
     * 连通性测试：跑一次真实搜索（或抓一次首页），返回人类可读结果。
     * 给「源管理」里的测试按钮用。
     */
    public String selfTest() {
        try {
            if (cfg.search == null) {
                String page = fetch(cfg.base, "get", null, cfg.base);
                if (Utils.empty(page)) return "无法访问 " + cfg.base;
                return "站点可访问，但该源未配置搜索（仅支持浏览）";
            }
            List<Book> r = search("测试", 1);
            if (r.isEmpty()) {
                r = search("a", 1);
            }
            if (r.isEmpty()) return "接口可访问但未解析出结果，请检查 items/title 正则";
            Book b = r.get(0);
            return "搜索正常，命中 " + r.size() + " 条，示例：《" + b.title + "》";
        } catch (Throwable t) {
            String m = t.getMessage();
            return "测试失败：" + (m == null ? t.getClass().getSimpleName() : m);
        }
    }
}
