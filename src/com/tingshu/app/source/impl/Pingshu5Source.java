package com.tingshu.app.source.impl;

import com.tingshu.app.core.Utils;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.AbsSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单田芳评书网 www.pingshu5.net
 *
 * 特性：
 * 1) 站点强制桌面 UA。
 * 2) 不支持搜索（canSearch=false）。
 * 3) 分类页：/pbook/（单田芳）/ykc/（袁阔成）/llf/（刘兰芳）/llr/（连丽如）/和其它分类。
 * 4) 列表：.pop-books2 > ul > li，封面 img，标题 .caption > a，简介 .pop-intro。
 * 5) 详情：.book-list > ul > li > a（400 集同页全出）。
 * 6) 取链：单集页 <audio><source src="...喜马拉雅CDN...">。音频直链本身无需 Referer。
 *    封面需本站 Referer。
 */
public class Pingshu5Source extends AbsSource implements com.tingshu.app.source.BrowsableSource {

    public static final String ID = "pingshu5";
    private static final String BASE = "http://www.pingshu5.net";
    private static final String REF = BASE + "/";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "评书网";
    }

    @Override
    public String desc() {
        return "单田芳/袁阔成/刘兰芳等名家评书，不支持搜索。";
    }

    @Override
    public boolean canSearch() {
        return false;
    }

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        return new ArrayList<Book>();
    }

    /** 分类列表页（发现页用） */
    public List<Book> list(String path, int page) throws Exception {
        List<Book> out = new ArrayList<Book>();
        String p = (path == null || path.length() == 0) ? "pbook" : path;
        String url = BASE + "/" + p + "/";
        if (page > 1) url = url + "index_" + page + ".html";
        String html = get(url, REF, UA_DESKTOP);
        if (html == null) return out;

        List<String> items = grabBlocks(html, "<li[^>]*class=\"col-[^\"]*\"[^>]*>(.*?)</li>");
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
        out.add(new com.tingshu.app.source.Category("pbook", "单田芳"));
        out.add(new com.tingshu.app.source.Category("ykc", "袁阔成"));
        out.add(new com.tingshu.app.source.Category("llf", "刘兰芳"));
        out.add(new com.tingshu.app.source.Category("llr", "连丽如"));
        return out;
    }

    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        return list(catId, page);
    }

    private Book parseItem(String item, String base) {
        String href = grab(item, "<a[^>]*href=\"([^\"]+)\"[^>]*title=\"[^\"]*\"");
        if (href.length() == 0) return null;
        String title = grab(item, "<h5[^>]*class=\"pop-tit\"[^>]*>(.*?)</h5>");
        title = text(title);
        if (title.length() == 0) title = text(grab(item, "<a[^>]*title=\"([^\"]*)\""));
        if (title.length() == 0) return null;

        String cover = grab(item, "<img[^>]*src=\"([^\"]+)\"");
        String intro = text(grab(item, "<span[^>]*class=\"pop-intro\"[^>]*>(.*?)</span>"));
        intro = intro.replaceAll("^\\s*介绍\\s*[：:]\\s*", "");

        String aid = idFromHref(href);
        Book b = book(aid, title, Utils.abs(BASE + "/", href));
        b.cover = cover;
        b.intro = intro;
        return b;
    }

    private String idFromHref(String href) {
        // /pbook/bldx320h/ 或 /pbook/4850.html
        String s = href;
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int i = s.lastIndexOf('/');
        if (i >= 0) s = s.substring(i + 1);
        if (s.endsWith(".html")) s = s.substring(0, s.length() - 5);
        return s;
    }

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        BookDetail d = new BookDetail();
        d.chapters = new ArrayList<Chapter>();
        String url = normalize(bookUrl);
        String html = get(url, REF, UA_DESKTOP);
        if (html == null) return d;

        String title = text(grab(html, "<h1[^>]*>(.*?)</h1>"));
        if (title.length() == 0) {
            title = text(grab(html, "<title>(.*?)</title>")).replaceAll("[-_|].*$", "").trim();
        }
        String cover = grab(html, "property=\"og:image\"\\s+content=\"([^\"]*)\"");
        if (cover.length() == 0) cover = grab(html, "<div[^>]*class=\"[^\"]*thumb[^\"]*\"[^>]*>\\s*<img[^>]*src=\"([^\"]+)\"");

        Book b = book(idFromHref(url), title, url);
        b.cover = cover;
        d.book = b;

        // <div class="book-list clearfix"> <ul> <li><a href="/pbook/4850.html">第1回</a></li> ...
        String block = grab(html, "<div[^>]*class=\"book-list[^\"]*\"[^>]*>(.*?)</ul>");
        if (block.length() == 0) block = html;
        List<String> lis = grabBlocks(block, "<li>(.*?)</li>");
        TreeMap<Integer, Chapter> sorted = new TreeMap<Integer, Chapter>();
        int seq = 0;
        for (String li : lis) {
            String href = grab(li, "<a[^>]*href=\"([^\"]+)\"");
            if (href.length() == 0) continue;
            String ct = text(grab(li, "<a[^>]*>(.*?)</a>"));
            if (ct.length() == 0) ct = "第" + (seq + 1) + "回";
            seq++;
            Chapter c = new Chapter();
            c.title = ct;
            // 章节链接是站点绝对路径（/pbook/4850.html），必须以站点根为基准
            c.url = Utils.abs(BASE + "/", href);
            int num = extractNum(ct);
            c.index = num > 0 ? num : seq;
            if (sorted.containsKey(c.index)) c.index = seq;
            sorted.put(c.index, c);
        }
        d.chapters.addAll(sorted.values());
        d.total = d.chapters.size();
        return d;
    }

    /** 从「白眉大侠第123回」里抽 123；失败返回 -1 */
    private static int extractNum(String s) {
        if (s == null) return -1;
        Matcher m = Pattern.compile("第\\s*(\\d+)\\s*[回集话]").matcher(s);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Throwable t) { return -1; }
        }
        return -1;
    }

    @Override
    public String audioUrl(Chapter ch, Book bk) throws Exception {
        if (ch == null || ch.url == null) return null;
        String cacheKey = "src:" + ch.url;
        String cached = cacheGet(cacheKey);
        if (cached != null) return cached;

        String html = get(ch.url, REF, UA_DESKTOP);
        if (html == null) return null;
        // <audio ...><source src="...">
        String src = grab(html, "<source[^>]*src=\"([^\"]+)\"");
        if (src.length() == 0) src = grab(html, "<audio[^>]*src=\"([^\"]+)\"");
        if (src.length() == 0) return null;
        String real = unescape(src);
        if (real.startsWith("//")) real = "http:" + real;
        cachePut(cacheKey, real, 30 * 60 * 1000L);
        return real;
    }

    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        // 喜马拉雅 CDN 直链无需 Referer
        return null;
    }

    @Override
    public Map<String, String> coverHeaders(String coverUrl) {
        if (coverUrl != null && coverUrl.contains("pingshu5.net")) {
            return headers("Referer", REF);
        }
        return null;
    }

    private String normalize(String u) {
        if (u == null) return BASE + "/";
        if (u.startsWith("http")) return u;
        return BASE + (u.startsWith("/") ? u : "/" + u);
    }
}
