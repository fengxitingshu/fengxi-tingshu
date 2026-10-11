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
 * 云图有声（yuntuys.com）
 *
 * 已知坑：
 * 1) **http 站点**（不是 https），Manifest 已开 usesCleartextTraffic。
 * 2) 接口路径里带一个固定的 WECHAT token，必须原样带上。
 * 3) 章节接口的分页参数是 pageSize / pageNum，一次给 200 条基本能拿全。
 * 4) 直链在 data.pageQuery.list[].audioUrl。
 */
public class YuntuVoiceSource extends AbsSource
        implements com.tingshu.app.source.BrowsableSource {

    public static final String ID = "yuntu";
    private static final String BASE = "http://open-service.yuntuys.com";
    private static final String REF = "http://www.yuntuys.com/";

    /**
     * 该源只开放了"搜索"接口（列表/分类接口均已探测 404）。
     * 因此分类用「题材关键词」实现：browse(catId) == search(关键词)。
     */
    private static final String[][] CATS = {
            {"小说", "有声小说"},
            {"文学", "文学名著"},
            {"历史", "历史人文"},
            {"国学", "国学经典"},
            {"儿童", "少儿读物"},
            {"健康", "健康养生"},
            {"科普", "科普百科"},
            {"诗歌", "诗词歌赋"},
            {"评书", "经典评书"},
    };

    /** 站点自己的固定 token */
    private static final String WECHAT = "wechat:07955551-706c-4259-9aa0-db4627dfca57";

    private static final String SEARCH =
            BASE + "/api/w_ys/book/search/%s/%s?pageSize=20&pageNum=%d";
    private static final String CHAPTERS =
            BASE + "/api/w_ys/book/getChapters/%s/%s/true/asc?pageSize=200&pageNum=%d";

    /** 章节接口一页给多少条（CHAPTERS 里 pageSize=200）；不满一页即到底 */
    private static final int PAGE_SIZE = 200;
    /** 一次并发预取多少页；绝大多数书一批就够 */
    private static final int PREFETCH = 4;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "云图有声";
    }

    @Override
    public String desc() {
        return "云图有声（yuntuys.com）。图书馆有声资源，藏书量大，接口返回直链。";
    }

    // ==================== 搜索 ====================

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        List<Book> out = new ArrayList<Book>();
        String url = String.format(SEARCH, WECHAT, Utils.enc(kw), page);
        String body = get(url, REF);
        if (body == null) return out;
        body = Json.clean(body);

        JSONObject root = Json.obj(body);
        if (root == null) return out;

        JSONArray arr = Json.arrOf(root, "data", "list");
        if (arr == null) arr = Json.arrOf(root, "data", "rows");
        if (arr == null) arr = root.optJSONArray("data");
        if (arr == null) return out;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Book b = new Book();
            b.source = ID;
            b.bookId = Json.pick(o, "bookId", "id", "book_id");
            b.title = Json.pick(o, "bookName", "name", "title");
            b.author = Json.pick(o, "author", "writer");
            b.artist = Json.pick(o, "reader", "narrator", "anchor", "speaker");
            b.cover = Json.pick(o, "cover", "coverUrl", "image", "pic", "logo");
            b.intro = Json.pick(o, "summary", "intro", "description", "bookSummary");
            b.category = Json.pick(o, "categoryName", "classifyName", "typeName");
            b.url = b.bookId;
            if (b.title.length() > 0 && b.bookId.length() > 0) out.add(b);
        }
        return out;
    }

    // ==================== 详情 ====================

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        BookDetail d = new BookDetail();
        Book b = new Book();
        b.source = ID;
        b.bookId = bookUrl;
        b.url = bookUrl;

        // ★ v1.9：并发预取章节分页（原为逐页串行）。
        //   该站一页最多 200 集，长篇书也有多页；并发后总耗时 ≈ 最慢的一页。
        List<Chapter> chapters = new ArrayList<Chapter>();
        boolean gotMeta = false;

        int pg = 1;
        boolean more = true;
        while (more && pg <= MAX_PAGES) {
            int batch = Math.min(PREFETCH, MAX_PAGES - pg + 1);
            java.util.List<String> urls = new java.util.ArrayList<String>(batch);
            java.util.List<String> refs = new java.util.ArrayList<String>(batch);
            for (int i = 0; i < batch; i++) {
                urls.add(String.format(CHAPTERS, WECHAT, Utils.enc(bookUrl), pg + i));
                refs.add(REF);
            }
            String[] pages = getParallel(urls, refs, null, pagePool(), batch);

            for (int bi = 0; bi < pages.length; bi++) {
                String body = pages[bi];
                if (body == null) {
                    if (bi == 0) more = false;
                    continue;
                }
                body = Json.clean(body);

                JSONObject root = Json.obj(body);
                if (root == null) {
                    if (bi == 0) more = false;
                    continue;
                }

                // 书籍元信息在同一份响应里
                if (!gotMeta) {
                    JSONObject bk = Json.child(root, "data.book");
                    if (bk == null) bk = Json.obj(Json.s(root, "data"));
                    if (bk != null) {
                        String t = Json.pick(bk, "bookName", "name", "title");
                        if (t.length() > 0) {
                            b.title = t;
                            b.author = Json.pick(bk, "author", "writer");
                            b.artist = Json.pick(bk, "reader", "narrator", "anchor");
                            b.cover = Json.pick(bk, "cover", "coverUrl", "image", "pic");
                            b.intro = Json.pick(bk, "summary", "intro", "description");
                            gotMeta = true;
                        }
                    }
                }

                // ★ 章节在 data.pageQuery.list
                JSONArray arr = Json.arrOf(root, "data", "pageQuery", "list");
                if (arr == null) arr = Json.arrOf(root, "data", "list");
                if (arr == null || arr.length() == 0) {
                    if (bi == 0) more = false;
                    continue;
                }

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Chapter c = new Chapter();
                    c.index = Json.i(o, "sortNum", chapters.size() + 1);
                    if (c.index <= 0) c.index = chapters.size() + 1;
                    c.title = Json.pick(o, "chapterName", "name", "title");
                    if (c.title.length() == 0) c.title = "第" + c.index + "集";
                    // ★ 直链
                    c.directUrl = Json.pick(o, "audioUrl", "file", "url", "playUrl");
                    c.url = c.directUrl;
                    chapters.add(c);
                }
                if (arr.length() < PAGE_SIZE) { more = false; break; }
            }
            pg += batch;
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
        if (ch != null && ch.directUrl != null && ch.directUrl.length() > 0) return ch.directUrl;
        if (ch != null && ch.url != null && ch.url.startsWith("http")) return ch.url;
        return null;
    }

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

    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        String kw = catId == null ? "" : catId;
        for (String[] c : CATS) {
            if (c[1].equals(catId)) {
                kw = c[0];
                break;
            }
        }
        return search(kw, page);
    }
}
