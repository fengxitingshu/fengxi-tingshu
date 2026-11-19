package com.fengxi.source.yueting;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.BrowsableSource;
import com.tingshu.app.source.Category;
import com.tingshu.app.source.Source;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 悦听吧源（Fx Plugin API v1）——HTML + JSON 混合，自写自用，不随公开包分发。
 *
 *   搜索   GET  http://www.yuetingba.cn/Search?type=1&name=<kw>        解析 .section-box-list-item
 *   分类   GET  http://www.yuetingba.cn/book/<catId>/<page>            同上（9 个一级分类）
 *   章节   GET  http://www.yuetingba.cn/book/detail/<uuid>/<offset>    解析 a[href^=/book/ting/]（每页约 200，offset 翻页）
 *   播放   GET  http://www.yuetingba.cn/api/app/docs-listen/<uuid>/play-info
 *          → JSON audioUrl（带 token 与过期时间；message=="msg!" 表示限频）
 *
 * 详情页/章节 id 均为 UUID（2025 年站点改版后的格式）。
 */
public class YuetingSource implements BrowsableSource {

    private static final String HOST = "http://www.yuetingba.cn";
    private static final String UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 一级分类：id 来自站内导航 /book/<id>/1 */
    private static final String[][] CATS = {
        {"1", "玄幻"}, {"2", "历史"}, {"3", "武侠"}, {"4", "都市"}, {"5", "科幻"},
        {"6", "名著"}, {"7", "女频"}, {"8", "社科"}, {"a", "评书"},
    };

    @Override public String id()   { return "fengxi_yueting"; }
    @Override public String name() { return "悦听吧"; }
    @Override public String desc() { return "悦听吧免费听书站（网页解析，仅供学习参考，自用）。"; }
    @Override public boolean canSearch() { return true; }
    @Override public boolean canBrowse() { return true; }

    // ---------------- 分类 ----------------

    @Override
    public List<Category> categories() {
        List<Category> out = new ArrayList<Category>();
        for (String[] c : CATS) out.add(new Category(c[0], c[1]));
        return out;
    }

    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        if (catId == null || catId.length() == 0 || page < 1) return new ArrayList<Book>();
        String html = httpGet(HOST + "/book/" + catId + "/" + page);
        if (html == null) throw new Exception("悦听吧分类页无响应");
        return parseItemList(html);
    }

    // ---------------- 搜索 ----------------

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        if (page > 1) return new ArrayList<Book>();
        if (kw == null || kw.trim().length() == 0) return new ArrayList<Book>();
        String html = httpGet(HOST + "/Search?type=1&name="
                + URLEncoder.encode(kw.trim(), "UTF-8"));
        if (html == null) throw new Exception("悦听吧搜索接口无响应");
        return parseItemList(html);
    }

    /** 搜索页 / 分类页共用的列表解析（UUID 版式） */
    private List<Book> parseItemList(String html) {
        List<Book> out = new ArrayList<Book>();
        Document doc = Jsoup.parse(html);
        for (Element item : doc.select(".section-box-list-item")) {
            Element titleA = item.selectFirst(".box-list-item-text-title a");
            Element img = item.selectFirst("img");
            String path = null, title = null;
            if (titleA != null && titleA.attr("href").startsWith("/book/detail/")) {
                path = titleA.attr("href");
                title = titleA.text().trim();
            }
            if ((path == null || title == null || title.length() == 0) && img != null) {
                Element a = img.selectFirst("a[href^=/book/detail/]");
                if (a == null) {
                    Element pa = item.selectFirst("a[href^=/book/detail/]");
                    a = pa;
                }
                if (a != null) {
                    path = a.attr("href");
                    String alt = img.attr("alt");
                    title = (alt == null || alt.length() == 0)
                            ? (a.text() == null ? "" : a.text().trim()) : alt.trim();
                }
            }
            if (path == null || title == null || title.length() == 0) continue;

            Book b = new Book();
            b.source = id();
            b.bookId = uuidFromPath(path);
            b.url = path;
            b.title = title;
            if (img != null) {
                String src = img.hasAttr("data-original") ? img.attr("data-original") : img.attr("src");
                if (src != null && src.startsWith("/")) src = HOST + src;
                b.cover = src == null ? "" : src;
            }
            out.add(b);
            if (out.size() >= 20) break;
        }
        return out;
    }

    // ---------------- 详情 + 章节 ----------------

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        String uuid = uuidFromPath(bookUrl);
        if (uuid == null || uuid.length() < 8) throw new Exception("bookUrl 非法：" + bookUrl);

        List<Chapter> chapters = new ArrayList<Chapter>();
        String intro = "";
        HashSet<String> seen = new HashSet<String>();

        // 章节按 offset 翻页（每页约 200 条），拿不到新章节即停
        for (int offset = 0; offset < 20000; offset += 200) {
            String html = httpGet(HOST + "/book/detail/" + uuid + "/" + offset);
            if (html == null) break;
            Document doc = Jsoup.parse(html);
            if (offset == 0) {
                Element des = doc.selectFirst("meta[name=description]");
                if (des != null) intro = des.attr("content");
                if (intro == null) intro = "";
                if (intro.length() > 1000) intro = intro.substring(0, 1000);
            }
            int before = chapters.size();
            for (Element a : doc.select("a[href^=/book/ting/]")) {
                String href = a.attr("href");
                String title = a.text() == null ? "" : a.text().trim();
                String key = href + "|" + title;
                if (seen.contains(key)) continue;
                seen.add(key);
                if (title.length() == 0) title = "第" + (chapters.size() + 1) + "集";
                chapters.add(new Chapter(chapters.size() + 1, title, href));
            }
            if (chapters.size() == before) break;
        }
        if (chapters.isEmpty()) throw new Exception("这本书没有章节数据，请换个资源");

        BookDetail d = new BookDetail();
        Book b = new Book();
        b.source = id();
        b.bookId = uuid;
        b.url = "/book/detail/" + uuid + "/0";
        d.book = b;
        d.intro = intro;
        d.chapters = chapters;
        d.total = chapters.size();
        return d;
    }

    // ---------------- 取播放直链 ----------------

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        if (ch == null || ch.url == null) return null;
        String uuid = uuidFromPath(ch.url);
        if (uuid == null) return ch.directUrl == null ? null : ch.directUrl;

        String resp = httpGet(HOST + "/api/app/docs-listen/" + uuid + "/play-info");
        if (resp == null) throw new Exception("悦听吧播放接口无响应");
        JSONObject o = new JSONObject(resp);
        String url = o.optString("audioUrl", "");
        if (url.length() > 0) return url;
        // message=="msg!" 或其它错误多为站点限频
        throw new Exception("悦听吧取播放地址失败（站点可能限频），稍后再试");
    }

    @Override public Map<String, String> audioHeaders(String audioUrl) { return null; }
    @Override public Map<String, String> coverHeaders(String coverUrl) { return null; }

    // ==================== 工具 ====================

    /** /book/detail/<uuid>/0 或 /book/ting/<uuid> → <uuid> */
    private static String uuidFromPath(String path) {
        if (path == null) return null;
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].length() > 0) return parts[i];
        }
        return null;
    }

    // ==================== HTTP ====================

    private static String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/json,*/*");
            c.setRequestProperty("Referer", HOST + "/");
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }
}
