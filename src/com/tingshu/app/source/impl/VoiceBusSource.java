package com.tingshu.app.source.impl;

import com.tingshu.app.core.Net;
import com.tingshu.app.core.Utils;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.AbsSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 声音巴士 vbus.cc
 *
 * 特性：
 * 1) 一个「节目」= 一集，没有多集概念 → detail() 返回单集。
 * 2) 不支持搜索（canSearch=false），只能从「发现」页进入。
 * 3) 取链：播放页 <audio src="/program/get-source/{id}">，该端点 302 跳到腾讯 CDN，
 *    必须用 resolveLocation() 拿最终地址（HEAD + 不自动跟随）。
 * 4) 站点强制桌面 UA（移动 UA 会被 301 到 www）。
 */
public class VoiceBusSource extends AbsSource implements com.tingshu.app.source.BrowsableSource {

    public static final String ID = "vbus";
    private static final String BASE = "https://vbus.cc";
    private static final String REF = BASE + "/";
    private static final long TTL_URL = 10 * 60 * 1000L;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "声音巴士";
    }

    @Override
    public String desc() {
        return "无障碍声音交流平台，节目为单集形式，不支持搜索。";
    }

    @Override
    public boolean canSearch() {
        return false;
    }

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        return new ArrayList<Book>();
    }

    /** 发现页：/recommend/1 /new/1 /hot/1 等；bookUrl 即节目详情页 */
    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        BookDetail d = new BookDetail();
        d.chapters = new ArrayList<Chapter>();
        String url = bookUrl;
        if (url != null && url.length() > 0 && !url.startsWith("http")) {
            url = BASE + (url.startsWith("/") ? url : "/" + url);
        }
        if (url == null || url.length() == 0) return d;

        String html = get(url, REF, UA_DESKTOP);
        if (html == null || html.length() == 0) return d;

        String title = grab(html, "<h1[^>]*>(.*?)</h1>");
        if (title.length() == 0) title = grab(html, "<title>(.*?)</title>");
        title = text(title);

        String cover = grab(html, "<audio[^>]*>\\s*</audio>");
        cover = grab(html, "property=\"og:image\"\\s+content=\"([^\"]*)\"");

        Book b = book(idFromUrl(url), title, url);
        b.cover = cover;
        b.artist = grabMetaText(html, "fa-user");
        d.book = b;
        d.intro = grabMetaText(html, "program-detail");

        Chapter c = new Chapter();
        c.title = title.length() > 0 ? title : "音频";
        c.index = 0;
        c.url = url;
        d.chapters.add(c);
        d.total = 1;
        return d;
    }

    /** 从 <span class="icon"><i class="fa fa-user">...</i></span><span><a href="/user/N">作者</a></span> 抠名字 */
    private String grabMetaText(String html, String faClass) {
        return text(grab(html, "fa-" + faClass + "\"[^>]*></i></span><span>(?:<a[^>]*>)?([^<]*)"));
    }

    private String idFromUrl(String url) {
        int i = url.lastIndexOf('/');
        String s = i >= 0 ? url.substring(i + 1) : url;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        return s;
    }

    @Override
    public String audioUrl(Chapter ch, Book bk) throws Exception {
        if (ch == null || ch.url == null) return null;
        String pageUrl = ch.url;
        String cacheKey = "src:" + pageUrl;
        String cached = cacheGet(cacheKey);
        if (cached != null) return cached;

        String html = get(pageUrl, REF, UA_DESKTOP);
        if (html == null) return null;
        // <audio ... src="/program/get-source/4457">
        String src = grab(html, "<audio[^>]*\\ssrc=\"([^\"]+)\"");
        if (src.length() == 0) return null;
        // src 是站点绝对路径（/program/get-source/4457），必须对站点根解析
        String abs = Utils.abs(BASE + "/", src);

        // 该端点 302 → 腾讯 CDN，用 HEAD 拿 Location
        String real = Net.resolveLocation(abs, pageUrl, null);
        if (real == null || real.length() == 0) real = abs;
        cachePut(cacheKey, real, TTL_URL);
        return real;
    }

    @Override
    public java.util.Map<String, String> audioHeaders(String audioUrl) {
        return null;
    }

    // ==================== 发现页（列表） ====================

    /** 供「发现」页使用：拉某一分类的一页 */
    public List<Book> list(String path, int page) throws Exception {
        List<Book> out = new ArrayList<Book>();
        if (path == null) path = "recommend";
        String url = BASE + "/" + path + "/" + page;
        String html = get(url, REF, UA_DESKTOP);
        if (html == null) return out;

        // <ul class="program-list"> <li class="media mb-4"> ... </li> </ul>
        List<String> items = grabBlocks(html, "<li\\s+class=\"media[^\"]*\"[^>]*>(.*?)</li>");
        for (String it : items) {
            Book b = parseItem(it, url);
            if (b != null) out.add(b);
        }
        return out;
    }

    // ==================== 发现页（BrowsableSource） ====================

    @Override
    public boolean canBrowse() { return true; }

    @Override
    public List<com.tingshu.app.source.Category> categories() {
        List<com.tingshu.app.source.Category> out =
                new ArrayList<com.tingshu.app.source.Category>();
        // 站点首页固定栏目
        out.add(new com.tingshu.app.source.Category("recommend", "推荐"));
        out.add(new com.tingshu.app.source.Category("new", "最新"));
        out.add(new com.tingshu.app.source.Category("hot", "最热"));
        out.add(new com.tingshu.app.source.Category("album", "专辑"));
        // 站点标签页（实测每个都有 20 条有效节目）
        out.add(new com.tingshu.app.source.Category("tag/4", "歌曲"));
        out.add(new com.tingshu.app.source.Category("tag/5", "翻唱"));
        out.add(new com.tingshu.app.source.Category("tag/7", "才艺"));
        out.add(new com.tingshu.app.source.Category("tag/37", "伴奏"));
        out.add(new com.tingshu.app.source.Category("tag/23", "评测"));
        out.add(new com.tingshu.app.source.Category("tag/162", "录音"));
        out.add(new com.tingshu.app.source.Category("tag/3", "讲座"));
        return out;
    }

    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        return list(catId, page);
    }

    private Book parseItem(String item, String base) {
        // <h3 aria-level="2"><a ... href="/v/4457">走马</a></h3>
        String href = grab(item, "<h3[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"");
        if (href.length() == 0) return null;
        String title = grab(item, "<h3[^>]*>\\s*<a[^>]*>(.*?)</a>");
        title = text(title);
        if (title.length() == 0) return null;

        String cover = grab(item, "<img[^>]*src=\"([^\"]+)\"");
        if (cover.startsWith("//")) cover = "https:" + cover;
        if (cover.contains("placeholder")) cover = "";

        // 作者 = fa-user 后的 <a> 文本
        String artist = text(grab(item, "fa-user\"[^>]*></i></span><span><a[^>]*>([^<]*)"));
        // 时间 = 最后一个 <span> 文本
        String date = "";
        List<String> spans = grabAll(item, "<span>(\\d{4}-\\d{2}-\\d{2}[^<]*)</span>");
        if (!spans.isEmpty()) date = spans.get(spans.size() - 1);

        Book b = book(idFromUrl(href), title, Utils.abs(BASE + "/", href));
        b.cover = cover;
        b.artist = artist;
        b.category = date;
        return b;
    }
}
