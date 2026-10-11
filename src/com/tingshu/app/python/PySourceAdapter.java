package com.tingshu.app.python;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.BrowsableSource;
import com.tingshu.app.source.Category;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ★ v3.4：把一个 Python 源（TVBox / DSPlayer 风格的 .py）包成宿主 {@link BrowsableSource}。
 *
 * <p>与 {@link com.tingshu.app.source.JsSourceAdapter}、{@link com.tingshu.app.plugin.TingShuAdapter}
 * 平级 —— 三者分别包装 JS 源、jar 插件源、Python 源，上层只认 {@code Source} 接口。
 *
 * <h3>接口映射</h3>
 * <pre>
 *   Python 侧                            宿主侧
 *   homeContent(filter)         ->      categories()
 *   categoryContent(tid,pg,..)  ->      browse(catId, page)
 *   searchContent(key,quick,pg) ->      search(kw, page)
 *   detailContent(ids)          ->      detail(bookUrl)
 *   playerContent(flag,id,vip)  ->      audioUrl(ch, book)
 * </pre>
 *
 * <h3>数据形状</h3>
 * TVBox 约定：{@code {"list":[{"vod_id","vod_name","vod_pic","vod_remarks",...}]}}，
 * 详情用 {@code vod_play_url} 的 {@code 名称$地址#名称$地址} 串承载章节。
 * 这些都在这里翻译成宿主模型。
 *
 * <h3>线程安全</h3>
 * CPython 的 GIL 保证单条调用原子，但同一个源实例内部可能有可变状态
 * （{@code init()} 里建的自定义字段）。这里按源加锁，把同一源的并发调用串行化。
 */
public class PySourceAdapter implements BrowsableSource {

    private final String id;
    private final String name;
    private final String desc;
    private final boolean canSearch;
    /** 源在 Python 侧的注册键 */
    private final String key;

    private final Object lock = new Object();
    private volatile List<Category> cachedCategories;

    public PySourceAdapter(String key, String name, String desc, boolean canSearch) {
        this.key = key;
        this.id = key;
        this.name = name == null || name.isEmpty() ? key : name;
        this.desc = desc == null ? "Python 源" : desc;
        this.canSearch = canSearch;
    }

    /** Python 侧的注册键 */
    public String pyKey() {
        return key;
    }

    // ==================== Source ====================

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String desc() {
        return desc;
    }

    @Override
    public boolean canSearch() {
        return canSearch;
    }

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        synchronized (lock) {
            JSONObject r = PythonHost.call("search_content", key, kw, false, String.valueOf(page));
            if (!r.optBoolean("ok", false)) return null;
            return parseList(r.optJSONObject("data"), page);
        }
    }

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        synchronized (lock) {
            JSONObject r = PythonHost.call("detail_content", key, bookUrl);
            if (!r.optBoolean("ok", false)) return null;
            return parseDetail(r.optJSONObject("data"), bookUrl);
        }
    }

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        if (ch == null) return null;
        synchronized (lock) {
            // 章节里已经有直链就直接用，省一次往返
            if (ch.directUrl != null && !ch.directUrl.isEmpty()
                    && (ch.directUrl.startsWith("http"))) {
                return ch.directUrl;
            }
            JSONObject r = PythonHost.call("player_content", key, "", ch.url, false);
            if (!r.optBoolean("ok", false)) return null;
            JSONObject d = r.optJSONObject("data");
            if (d == null) return null;
            String url = d.optString("url", "");
            // TVBox 约定：parse=0 表示 url 就是直链
            int parse = d.optInt("parse", 0);
            if (parse == 1 && url.startsWith("http")) {
                // 需要二次解析的场景，交回宿主播放器 sniff
                return url;
            }
            return url.isEmpty() ? null : url;
        }
    }

    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        return null;
    }

    @Override
    public Map<String, String> coverHeaders(String coverUrl) {
        return null;
    }

    // ==================== BrowsableSource ====================

    @Override
    public boolean canBrowse() {
        return !categories().isEmpty();
    }

    @Override
    public List<Category> categories() {
        List<Category> c = cachedCategories;
        if (c != null) return c;
        synchronized (lock) {
            if (cachedCategories != null) return cachedCategories;
            List<Category> out = new ArrayList<Category>();
            try {
                JSONObject r = PythonHost.call("home_content", key);
                if (r.optBoolean("ok", false)) {
                    JSONObject d = r.optJSONObject("data");
                    if (d != null) out.addAll(parseCategories(d.optJSONArray("class")));
                }
            } catch (Throwable ignored) {
            }
            cachedCategories = out;
            c = out;
        }
        return c;
    }

    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        synchronized (lock) {
            JSONObject r = PythonHost.call("category_content", key, catId,
                    String.valueOf(page), "{}");
            if (!r.optBoolean("ok", false)) return null;
            return parseList(r.optJSONObject("data"), page);
        }
    }

    // ==================== 解析 ====================

    private static List<Category> parseCategories(JSONArray arr) {
        List<Category> out = new ArrayList<Category>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String cid = firstNonEmpty(o, "type_id", "tid", "id");
            String cname = firstNonEmpty(o, "type_name", "name", "title");
            if (cid != null) out.add(new Category(cid, cname == null ? cid : cname));
        }
        return out;
    }

    private List<Book> parseList(JSONObject data, int page) {
        List<Book> out = new ArrayList<Book>();
        if (data == null) return out;
        JSONArray arr = data.optJSONArray("list");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Book b = new Book();
            b.source = id;
            b.bookId = firstNonEmpty(o, "vod_id", "id", "book_id");
            b.title = firstNonEmpty(o, "vod_name", "name", "title");
            b.author = firstNonEmpty(o, "vod_actor", "author", "vod_director");
            b.artist = firstNonEmpty(o, "vod_director", "artist", "vod_actor");
            b.cover = firstNonEmpty(o, "vod_pic", "pic", "cover");
            b.intro = firstNonEmpty(o, "vod_content", "intro", "desc", "vod_blurb");
            b.category = firstNonEmpty(o, "type_name", "category");
            String remarks = firstNonEmpty(o, "vod_remarks", "remarks", "note");
            if (remarks != null && !remarks.isEmpty() && (b.intro == null || b.intro.isEmpty())) {
                b.intro = remarks;
            }
            // url：优先源给的完整 id，其次 bookId
            b.url = b.bookId;
            if (b.bookId != null && !b.bookId.isEmpty()) out.add(b);
        }
        return out;
    }

    private BookDetail parseDetail(JSONObject data, String bookUrl) {
        if (data == null) return null;
        JSONArray arr = data.optJSONArray("list");
        JSONObject o = (arr != null && arr.length() > 0) ? arr.optJSONObject(0) : null;
        if (o == null) return null;

        Book b = new Book();
        b.source = id;
        b.bookId = firstNonEmpty(o, "vod_id", "id");
        if (b.bookId == null) b.bookId = bookUrl;
        b.title = firstNonEmpty(o, "vod_name", "name", "title");
        b.author = firstNonEmpty(o, "vod_actor", "author");
        b.artist = firstNonEmpty(o, "vod_director", "artist");
        b.cover = firstNonEmpty(o, "vod_pic", "pic", "cover");
        b.intro = firstNonEmpty(o, "vod_content", "intro", "desc");
        b.category = firstNonEmpty(o, "type_name", "category");
        b.url = bookUrl;

        BookDetail d = new BookDetail(b);
        d.intro = b.intro;

        // vod_play_url: "名称$地址#名称$地址#..."
        String play = o.optString("vod_play_url", "");
        if (play == null || play.isEmpty()) play = o.optString("play_url", "");
        List<Chapter> chs = parsePlayUrl(play);
        d.chapters = chs;
        d.total = chs.size();
        if (d.total > 0) {
            // 章节总数优先用源给的
            int n = o.optInt("vod_total", 0);
            if (n > 0) d.total = n;
        }
        return d;
    }

    /** 解析 TVBox 的 vod_play_url 串 */
    static List<Chapter> parsePlayUrl(String play) {
        List<Chapter> out = new ArrayList<Chapter>();
        if (play == null || play.isEmpty()) return out;
        // 多播放源用 $$$ 分隔，取第一个
        int multi = play.indexOf("$$$");
        if (multi > 0) play = play.substring(0, multi);
        String[] items = play.split("#");
        int idx = 1;
        for (String it : items) {
            if (it == null || it.isEmpty()) continue;
            String title;
            String url;
            int d = it.indexOf('$');
            if (d >= 0) {
                title = it.substring(0, d);
                url = it.substring(d + 1);
            } else {
                title = "第" + idx + "集";
                url = it;
            }
            if (url == null || url.isEmpty()) continue;
            Chapter c = new Chapter(idx, title == null || title.isEmpty()
                    ? ("第" + idx + "集") : title, url);
            if (url.startsWith("http")) {
                c.directUrl = url;
            }
            out.add(c);
            idx++;
        }
        return out;
    }

    /** 依次取第一个非空字段 */
    private static String firstNonEmpty(JSONObject o, String... keys) {
        if (o == null) return null;
        for (String k : keys) {
            if (!o.has(k)) continue;
            String v = o.optString(k, "");
            if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
        }
        return null;
    }
}
