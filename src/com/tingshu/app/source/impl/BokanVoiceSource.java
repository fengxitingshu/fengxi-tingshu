package com.tingshu.app.source.impl;

import com.tingshu.app.core.Json;
import com.tingshu.app.core.Utils;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.AbsSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 博看有声（bookan.com.cn）
 *
 * 已知坑：
 * 1) 搜索接口区分 book / album 两类，取并集命中率更高。
 * 2) 章节接口的 file 字段就是直链，不需要二次取链 → 详情页就把 directUrl 填好，
 *    audioUrl() 直接返回它，省一次请求。
 * 3) 音频 CDN 不需要 Referer。
 */
public class BokanVoiceSource extends AbsSource
        implements com.tingshu.app.source.BrowsableSource {

    public static final String ID = "bokan";
    private static final String REF = "https://www.bookan.com.cn/";

    /**
     * 站点的搜索接口是唯一的"发现"入口（无公开的列表/分类接口，已探测 404）。
     * 所以分类用「题材关键词」实现：browse(catId) 等价于 search(关键词)。
     * 这样发现页的每个分类都是真实可用的搜索结果，不会出现空列表。
     */
    private static final String[][] CATS = {
            {"玄幻", "玄幻奇幻"},
            {"武侠", "武侠江湖"},
            {"都市", "都市生活"},
            {"历史", "历史军事"},
            {"悬疑", "悬疑推理"},
            {"恐怖", "恐怖惊悚"},
            {"科幻", "科幻未来"},
            {"名著", "文学名著"},
            {"评书", "经典评书"},
            {"相声", "相声小品"},
            {"儿童", "儿童童话"},
            {"诗歌", "诗词散文"},
            {"职场", "职场商战"},
            {"国学", "国学经典"},
    };

    /** 搜索接口（GET，纯 JSON） */
    private static final String SEARCH_BOOK =
            "https://es.bookan.com.cn/api/v3/voice/book" +
            "?instanceId=25304&keyword=%s&pageNum=%d&limitNum=20";
    private static final String SEARCH_ALBUM =
            "https://es.bookan.com.cn/api/v3/voice/album" +
            "?instanceId=25304&keyword=%s&pageNum=%d&limitNum=20";

    /** 章节列表（GET，纯 JSON） */
    private static final String UNITS =
            "https://api.bookan.com.cn/voice/album/units" +
            "?album_id=%s&page=%d&num=20&order=1";

    /** 章节接口一页给多少条（UNITS 里 num=20）；不满一页即到底 */
    private static final int PAGE_SIZE = 20;
    /** 一次并发预取多少页；绝大多数书一批就够 */
    private static final int PREFETCH = 6;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "博看有声";
    }

    @Override
    public String desc() {
        return "博看有声（bookan.com.cn）。有声书、期刊、电台，接口稳定，直链可直接播放。";
    }

    // ==================== 搜索 ====================

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        List<Book> out = new ArrayList<Book>();
        String k = Utils.enc(kw);
        // 两类都搜，合并结果
        parseSearch(jsonGet(String.format(SEARCH_BOOK, k, page)), "book", out);
        parseSearch(jsonGet(String.format(SEARCH_ALBUM, k, page)), "album", out);
        return out;
    }

    private String jsonGet(String url) {
        String body = get(url, REF);
        return body == null ? null : Json.clean(body);
    }

    private void parseSearch(String body, String type, List<Book> out) {
        if (body == null) return;
        JSONObject root = Json.obj(body);
        if (root == null) return;
        // 结果可能在 data.list / data.rows / data.data
        JSONArray arr = Json.arrOf(root, "data", "list");
        if (arr == null) arr = Json.arrOf(root, "data", "rows");
        if (arr == null) arr = Json.arrOf(root, "data", "data");
        if (arr == null) {
            JSONArray d = root.optJSONArray("data");
            arr = d;
        }
        if (arr == null) return;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Book b = new Book();
            b.source = ID;
            b.title = Json.pick(o, "name", "title", "albumName", "bookName");
            b.author = Json.pick(o, "author", "writer", "authorName");
            b.artist = Json.pick(o, "narrator", "reader", "anchor", "speakerName");
            b.cover = Json.pick(o, "cover", "coverUrl", "logo", "image", "pic");
            b.intro = Json.pick(o, "summary", "intro", "description", "content");
            b.category = Json.pick(o, "categoryName", "category", "typeName");
            // bookId 用 type + id 组合，详情时能区分走哪个接口
            String rid = Json.pick(o, "id", "albumId", "bookId", "resourceId");
            b.bookId = type + "_" + rid;
            b.url = b.bookId;
            if (b.title.length() > 0) out.add(b);
        }
    }

    // ==================== 详情 ====================

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        BookDetail d = new BookDetail();
        String id = bookUrl;
        int us = id.indexOf('_');
        String type = "album";
        if (us > 0) {
            type = id.substring(0, us);
            id = id.substring(us + 1);
        }

        Book b = new Book();
        b.source = ID;
        b.bookId = type + "_" + id;
        b.url = bookUrl;

        // 章节列表（分页拉全）
        // ★ v1.9：先并发预取一批页，再按顺序消费，遇到不足一页即止。
        //   原来是一页一页串行等（每页一次 HTTP），一本几百集的书要等好几个来回。
        //   这里先并发拿 PREFETCH 页，绝大多数书一批就够，总耗时 ≈ 最慢的一页。
        JSONObject first = null;
        List<Chapter> chapters = new ArrayList<Chapter>();

        int pg = 1;
        boolean more = true;
        while (more && pg <= MAX_PAGES) {
            int batch = Math.min(PREFETCH, MAX_PAGES - pg + 1);
            java.util.List<String> urls = new java.util.ArrayList<String>(batch);
            java.util.List<String> refs = new java.util.ArrayList<String>(batch);
            for (int i = 0; i < batch; i++) {
                urls.add(String.format(UNITS, Utils.enc(id), pg + i));
                refs.add(REF);
            }
            String[] bodies = getParallel(urls, refs, null, pagePool(), batch);
            for (int i = 0; i < bodies.length; i++) {
                if (bodies[i] != null) bodies[i] = Json.clean(bodies[i]);
            }

            for (int bi = 0; bi < bodies.length; bi++) {
                String body = bodies[bi];
                if (body == null) {
                    // 这一页没拿到：如果是批内第一页，说明再往后也没意义了
                    if (bi == 0) { more = false; }
                    continue;
                }
                JSONObject root = Json.obj(body);
                if (root == null) {
                    if (bi == 0) more = false;
                    continue;
                }
                if (first == null) first = root;

                JSONArray arr = Json.arrOf(root, "data", "list");
                if (arr == null) arr = root.optJSONArray("data");
                if (arr == null || arr.length() == 0) {
                    if (bi == 0) more = false;
                    continue;
                }

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Chapter c = new Chapter();
                    c.index = Json.i(o, "sortNum", Json.i(o, "sort", chapters.size() + 1));
                    if (c.index <= 0) c.index = chapters.size() + 1;
                    c.title = Json.pick(o, "name", "title", "unitName");
                    if (c.title.length() == 0) c.title = "第" + c.index + "集";
                    // ★ 关键：file 就是直链
                    c.directUrl = Json.pick(o, "file", "url", "audioUrl", "playUrl", "src");
                    c.url = c.directUrl;
                    chapters.add(c);
                }
                // 不足一页说明到底了
                if (arr.length() < PAGE_SIZE) { more = false; break; }
            }
            pg += batch;
        }

        // 从第一页响应里补书的信息
        if (first != null) {
            JSONObject ab = Json.child(first, "data.album");
            if (ab == null) ab = Json.child(first, "data.book");
            if (ab == null) ab = Json.obj(Json.s(first, "data"));
            if (ab != null) {
                b.title = Json.pick(ab, "name", "title", "albumName");
                b.author = Json.pick(ab, "author", "writer");
                b.artist = Json.pick(ab, "narrator", "reader", "anchor");
                b.cover = Json.pick(ab, "cover", "coverUrl", "logo", "image");
                b.intro = Json.pick(ab, "summary", "intro", "description");
            }
        }

        d.book = b;
        d.chapters = chapters;
        d.total = chapters.size();
        d.intro = b.intro;
        return d;
    }

    // ==================== 取链 ====================

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        // 详情页已经给了直链，直接返回
        if (ch != null && ch.directUrl != null && ch.directUrl.length() > 0) {
            return ch.directUrl;
        }
        if (ch != null && ch.url != null && ch.url.startsWith("http")) return ch.url;
        return null;
    }

    // 音频 CDN 不需要 Referer
    @Override
    public java.util.Map<String, String> audioHeaders(String audioUrl) {
        return null;
    }

    // ==================== 分类浏览（关键词模拟） ====================

    @Override
    public boolean canBrowse() {
        return true;
    }

    @Override
    public List<com.tingshu.app.source.Category> categories() {
        List<com.tingshu.app.source.Category> out =
                new ArrayList<com.tingshu.app.source.Category>();
        for (String[] c : CATS) {
            out.add(new com.tingshu.app.source.Category(c[0], c[1]));
        }
        return out;
    }

    /** catId 就是题材关键词，直接复用搜索接口 */
    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        String kw = catId == null ? "" : catId;
        // 允许 catId 直接用显示名：先尝试在 CATS 里反查
        for (String[] c : CATS) {
            if (c[1].equals(catId)) {
                kw = c[0];
                break;
            }
        }
        return search(kw, page);
    }
}
