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
            // ★ v3.7.11 修复「Python 源取到的资源和标题不符」：
            //
            //   根因有两个，叠在一起会让播放器拿到"和点的那一集毫无关系"的地址：
            //
            //   ① detail 阶段 parsePlayUrl 把**章节页 URL**（形如
            //      https://site/xxx/12.html）当成"直链"塞进了 ch.directUrl，
            //      只因它以 http 开头。而 TVBox 约定里 vod_play_url 里的地址
            //      绝大多数是"章节页"，真正的音频要 playerContent 去那页里抠。
            //   ② 上面这段又只看 directUrl.startsWith("http") 就直接 return，
            //      于是 playerContent **根本没被调用**，播放器拿到的是 HTML 页面。
            //
            //   现在：只有当 directUrl 看起来**确实是音频文件**时才信任它，
            //   否则一律走 playerContent 正路取链。
            if (isLikelyAudio(ch.directUrl)) {
                return ch.directUrl;
            }
            JSONObject r = PythonHost.call("player_content", key,
                    ch.flag == null ? "" : ch.flag, ch.url, false);
            if (!r.optBoolean("ok", false)) return null;
            JSONObject d = r.optJSONObject("data");
            if (d == null) return null;
            String url = d.optString("url", "");
            if (url == null || url.isEmpty()) return null;
            // ★ v3.7.11：源在取链失败时常会把"章节页地址"原样回填（见 [书]听书网.py
            //   的 `return {'parse':0,'url':str(id),...}`），并附一个 msg 说明原因。
            //   这种不是音频，直接当失败处理 —— 交给上层走"换源 / 下一候选"，
            //   而不是把 HTML 当音频塞给播放器（那就是用户看到的"内容与标题不符"）。
            if (!isLikelyAudio(url)) {
                return null;
            }
            return url;
        }
    }

    /**
     * ★ v3.7.11：判断一个 URL 是否"看起来就是音频文件"。
     *
     * <p>用于区分"真直链"和"章节页地址"。取链失败时源常把页面 URL 原样返回，
     * 若不加甄别地当音频用，播放器会把 HTML 当媒体流播放，
     * 表现就是"点第 3 集，播出来的却是别的东西 / 直接放不出"。
     *
     * <p>判定规则（宁可放行交给播放器 sniff，也不要误判成音频）：
     * <ul>
     *   <li>去掉 query / fragment 后，路径以常见音频扩展名结尾 → 是；</li>
     *   <li>明确是 .html / .htm / .php / .asp / .jsp / .shtml 页面 → 不是；</li>
     *   <li>其余（无扩展名、接口地址等）→ 交回播放器 sniff，按"可能"放行。</li>
     * </ul>
     */
    static boolean isLikelyAudio(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.isEmpty()) return false;
        if (!u.startsWith("http")) return false;
        // 去 query / fragment
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        String lower = u.toLowerCase(java.util.Locale.ROOT);
        // 明确的页面后缀 → 一定不是音频
        String[] pageExt = {".html", ".htm", ".php", ".asp", ".aspx", ".jsp",
                ".jspx", ".shtml", ".do", ".action", ".css", ".js", ".json"};
        for (String e : pageExt) if (lower.endsWith(e)) return false;
        // 明确的音频后缀 → 是
        String[] audioExt = {".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg",
                ".opus", ".m4b", ".m3u8", ".mp4", ".wma", ".ape", ".amr", ".ts", ".webm"};
        for (String e : audioExt) if (lower.endsWith(e)) return true;
        // 没有可辨识后缀：接口型地址，交给播放器 sniff（按"可能"处理）
        return true;
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
        // ★ v3.7.11：取第一条播放线名（vod_play_from 用 $$$ 分隔），回传给 playerContent。
        //   以前这个值被完全丢弃，多线路源取链时会拿错线路。
        String from = o.optString("vod_play_from", "");
        if (from == null || from.isEmpty()) from = o.optString("play_from", "");
        String firstFlag = firstSegment(from, "$$$");
        List<Chapter> chs = parsePlayUrl(play, firstFlag);
        d.chapters = chs;
        d.total = chs.size();
        if (d.total > 0) {
            // 章节总数优先用源给的
            int n = o.optInt("vod_total", 0);
            if (n > 0) d.total = n;
        }
        return d;
    }

    /** ★ v3.7.11：取按 sep 切分后的第一段（去空白） */
    private static String firstSegment(String s, String sep) {
        if (s == null) return "";
        int i = s.indexOf(sep);
        String v = (i >= 0 ? s.substring(0, i) : s);
        return v == null ? "" : v.trim();
    }

    /**
     * 解析 TVBox 的 vod_play_url 串。
     *
     * <p>★ v3.7.11 修复：原来只做 {@code play.split("#")}，一旦章节地址自身含
     * {@code #}（前端路由 / 锚点，如 {@code /a/1.html#/p1}）就会被切碎，
     * 切出来的碎片会被当成"新的章节"，于是章节数变多、标题与地址错位 ——
     * 用户看到的就是"标题对不上内容"。
     *
     * <p>现在改为按 {@code 名称$地址} 成对解析：以 {@code #} 为分隔时，
     * 若某段里**不含** {@code $}，说明它是上一段地址被切断的尾巴，
     * 把它接回上一项，而不是当成新章节。
     *
     * @param play 播放串；多播放线用 {@code $$$} 分隔，这里只取第一条
     * @param flag 该播放线的名字（vod_play_from），写入每章供 playerContent 用
     */
    static List<Chapter> parsePlayUrl(String play) {
        return parsePlayUrl(play, "");
    }

    static List<Chapter> parsePlayUrl(String play, String flag) {
        List<Chapter> out = new ArrayList<Chapter>();
        if (play == null || play.isEmpty()) return out;
        // 多播放源用 $$$ 分隔，取第一个
        int multi = play.indexOf("$$$");
        if (multi > 0) play = play.substring(0, multi);

        String[] items = play.split("#");
        int idx = 1;
        for (String it : items) {
            if (it == null || it.isEmpty()) continue;
            // ★ 判断"成对"依据是这一段里有没有 $：
            //   没有 $ 的段 = 上一段地址被 # 截断的续片 → 接回上一项
            int d = it.indexOf('$');
            if (d < 0) {
                if (!out.isEmpty()) {
                    Chapter prev = out.get(out.size() - 1);
                    prev.url = prev.url + "#" + it;
                }
                continue;
            }
            String title = it.substring(0, d);
            String url = it.substring(d + 1);
            if (url == null || url.isEmpty()) continue;
            String t = (title == null || title.isEmpty()) ? ("第" + idx + "集") : title;
            Chapter c = new Chapter(idx, t, url);
            c.flag = flag == null ? "" : flag;
            // ★ v3.7.11：只有当它**确实是音频文件**时才填 directUrl，
            //   章节页 URL 不再被误当成直链（见 audioUrl 里的说明）。
            if (isLikelyAudio(url)) {
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
