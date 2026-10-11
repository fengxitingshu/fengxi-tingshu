package com.tingshu.app.source.impl;

import com.tingshu.app.core.Net;
import com.tingshu.app.core.Utils;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.AbsSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 有听网 m.ting15.com
 *
 * 已知坑（勿改）：
 * 1) 音频 CDN oss-links.guoguo.org.cn 与封面 img-book-oss.guoguo.org.cn 强制本站 Referer，
 *    否则 400/404 → audioHeaders/coverHeaders 必须返回本站 Referer。
 * 2) 取链接口返回体带 UTF-8 BOM 和 \u200B，必须先剥（Json.clean 或手动）。
 * 3) 返回 JSON 里中文路径被转成 unicode 转义序列，不还原会导致 URL 404。
 * 4) 接口的 page 字段是 1 基，而页面链接 /{slug}/{id}/0-{n}.html 里的 n 是 0 基（0-0 是"上次听到"）。
 * 5) 302 的最终腾讯 COS 地址带 Referer 才能播，且不能跟 Range 一起用 → 把首跳地址交给播放器逐跳。
 */
public class Ting15Source extends AbsSource implements com.tingshu.app.source.BrowsableSource {

    public static final String ID = "ting15";
    private static final String BASE = "https://m.ting15.com";
    private static final String REF = BASE + "/";

    /** 分类：slug -> 显示名（与首页导航一致） */
    private static final String[][] CATS = {
            {"wuxiaxuanhuan", "武侠玄幻"},
            {"kongbulingyi", "恐怖灵异"},
            {"tuilixuanyi", "推理悬疑"},
            {"dushiyanqing", "都市言情"},
            {"jiatinglunli", "家庭伦理"},
            {"wenxuemingzhu", "官场职场"},
            {"jingdianpingshu", "经典评书"},
            {"quyixiqu", "曲艺戏曲"},
            {"xiangshengxiaopin", "相声小品"},
            {"yinyue", "助眠音频"},
    };

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "有听网";
    }

    @Override
    public String desc() {
        return "有听网（m.ting15.com）。小说、评书、相声资源，需站内 Referer 播放。";
    }

    // ==================== 搜索 ====================

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        String url = BASE + "/?s=ting-search-wd-" + Utils.enc(kw) + "-p-" + page + ".html";
        String html = Net.get(url, REF);
        return parseList(html);
    }

    /**
     * 解析「书列表」HTML。搜索结果页与分类列表页结构一致
     * （都是 {@code <a href="/slug/id.html"><dl>…</dl></a>}），所以共用。
     */
    private List<Book> parseList(String html) {
        List<Book> out = new ArrayList<Book>();
        if (html == null) return out;
        Pattern p = Pattern.compile(
                "<a\\s+href=\"(/[a-z]+/\\d+\\.html)\"[^>]*>\\s*(<dl>.*?</dl>)", Pattern.DOTALL);
        Matcher m = p.matcher(html);
        while (m.find()) {
            String href = m.group(1);
            String block = m.group(2);
            Book b = new Book();
            b.source = ID;
            b.url = BASE + href;
            b.bookId = href.replaceAll(".*/(\\d+)\\.html", "$1");
            b.title = Utils.stripTags(grab(block, "<h3>(.*?)</h3>"));
            b.cover = abs(BASE, grab(block, "<img\\s+src=\"([^\"]+)\""));
            b.author = stripLabel(text(grab(block, "作者[：:]\\s*([^<]*)</p>")));
            b.artist = stripLabel(text(grab(block, "播音[：:]\\s*([^<]*)</p>")));
            b.category = stripLabel(text(grab(block, "类别[：:]\\s*([^<]*)</p>")));
            if (b.title.length() > 0) out.add(b);
        }
        return out;
    }

    // ==================== 分类浏览（发现页） ====================

    @Override
    public boolean canBrowse() {
        return true;
    }

    @Override
    public List<com.tingshu.app.source.Category> categories() {
        List<com.tingshu.app.source.Category> out = new ArrayList<com.tingshu.app.source.Category>();
        for (int i = 0; i < CATS.length; i++) {
            out.add(new com.tingshu.app.source.Category(CATS[i][0], CATS[i][1]));
        }
        return out;
    }

    /**
     * 分类列表页。
     * 首页是 {@code /{slug}/}，第 2 页起是 {@code /{slug}/index{n}.html}。
     */
    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        if (Utils.empty(catId)) return new ArrayList<Book>();
        if (page <= 0) page = 1;
        // 只允许本站已知分类 slug，避免被当成任意路径代理
        boolean known = false;
        for (int i = 0; i < CATS.length; i++) {
            if (CATS[i][0].equals(catId)) { known = true; break; }
        }
        if (!known) return new ArrayList<Book>();

        String url = page <= 1
                ? BASE + "/" + catId + "/"
                : BASE + "/" + catId + "/index" + page + ".html";
        String html = Net.get(url, REF);
        return parseList(html);
    }

    // ==================== 详情 + 章节 ====================

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        BookDetail d = new BookDetail();
        String html = Net.get(bookUrl, REF);
        if (html == null) return d;

        Book b = new Book();
        b.source = ID;
        b.url = bookUrl;
        b.bookId = bookUrl.replaceAll(".*/(\\d+)\\.html.*", "$1");
        b.title = Utils.stripTags(grab(html, "<h1[^>]*>(.*?)</h1>"));
        if (b.title.length() == 0) {
            String t = Utils.stripTags(grab(html, "<title>(.*?)</title>"));
            int u = t.indexOf('_');
            b.title = u > 0 ? t.substring(0, u) : t;
        }
        b.author = stripLabel(text(grab(html, "作者[：:]\\s*([^<]{1,30})<")));
        b.artist = stripLabel(text(grab(html, "播音[：:]\\s*([^<]{1,30})<")));
        b.cover = abs(BASE, grab(html, "<img[^>]*src=\"([^\"]*guoguo[^\"]*)\""));
        if (b.cover.length() == 0) {
            b.cover = abs(BASE, grab(html, "<img[^>]*src=\"([^\"]+)\"[^>]*class=\"[^\"]*cover"));
        }
        String intro = meta(html, "description");
        b.intro = intro;
        d.book = b;
        d.intro = intro;

        // 章节：/{slug}/{id}/0-{n}.html，n 从 1 开始计集数（0-0 是"上次听到"，跳过）
        String basePath = bookUrl.replace(BASE, "").replace(".html", "");
        if (basePath.indexOf('?') > 0) basePath = basePath.substring(0, basePath.indexOf('?'));
        Pattern cp = Pattern.compile("href=\"(" + Pattern.quote(basePath) + "/(\\d+)-(\\d+)\\.html)\"");
        TreeMap<Integer, Chapter> map = new TreeMap<Integer, Chapter>();
        collect(cp, html, map);

        // ★ 分页补全改为**并发**：一本 800 集的书提示"共 8 页"，
        //   串行就是 8 个网络来回，用户干等好几秒；这些页互相独立，
        //   同时发出去后总耗时 ≈ 最慢的一页。
        int tp = totalPages(html);
        int last = Math.min(tp, MAX_PAGES);
        if (last > 1) {
            java.util.List<String> urls = new java.util.ArrayList<String>(last - 1);
            java.util.List<String> refs = new java.util.ArrayList<String>(last - 1);
            for (int pg = 2; pg <= last; pg++) {
                urls.add(bookUrl + "?page=" + pg);
                refs.add(bookUrl);
            }
            String[] pages = getParallel(urls, refs, null, pagePool(), 6);
            for (int i = 0; i < pages.length; i++) {
                if (pages[i] != null) collect(cp, pages[i], map);
            }
        }

        for (Map.Entry<Integer, Chapter> e : map.entrySet()) {
            d.chapters.add(e.getValue());
        }
        d.total = d.chapters.size();
        return d;
    }

    private void collect(Pattern cp, String html, TreeMap<Integer, Chapter> map) {
        Matcher cm = cp.matcher(html);
        while (cm.find()) {
            int no = 0;
            try {
                no = Integer.parseInt(cm.group(3));
            } catch (Throwable ignored) {}
            if (no <= 0) continue;   // 0-0 是占位项
            if (map.containsKey(no)) continue;
            Chapter c = new Chapter();
            c.index = no;
            c.url = BASE + cm.group(1);
            c.title = "第" + no + "集";
            map.put(no, c);
        }
    }

    // ==================== 取链 ====================

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        String html = Net.get(ch.url, REF);
        if (html == null) return null;

        String b = meta(html, "_b");
        String cp = meta(html, "_cp");
        String p = meta(html, "_p");
        String c = meta(html, "_c");
        String l = meta(html, "_l");
        if (Utils.empty(l)) l = "1";
        if (Utils.empty(b) || Utils.empty(cp)) return null;

        Map<String, String> h = new HashMap<String, String>();
        h.put("X-Requested-With", "XMLHttpRequest");
        if (!Utils.empty(c)) h.put("xt", c);
        h.put("l", l);

        String form = "bookId=" + b + "&isPay=" + (Utils.empty(p) ? "0" : p) + "&page=" + cp;
        String json = Net.post(BASE + "/?s=api-getneoplay", form, ch.url, h);
        if (json == null) return null;

        // 剥 BOM / 零宽字符
        json = stripBom(json).trim();

        String url = grab(json, "\"url\"\\s*:\\s*\"([^\"]+)\"");
        String status = grab(json, "\"status\"\\s*:\\s*(\\d+)");
        if (!"1".equals(status) || url.length() == 0) return null;
        return Utils.unescape(url);
    }

    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        if (audioUrl == null) return null;
        // 站内直链与其 302 目标都要求本站 Referer
        String host = Utils.host(audioUrl);
        if (host.contains("guoguo") || host.contains("ting15") || host.contains("myqcloud")
                || host.contains("tencent") || host.contains("cos.")) {
            return headers("Referer", REF);
        }
        return null;
    }

    @Override
    public Map<String, String> coverHeaders(String coverUrl) {
        if (coverUrl != null && coverUrl.contains("guoguo")) {
            return headers("Referer", REF);
        }
        return null;
    }

    private static String stripBom(String s) {
        if (s == null) return "";
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\uFEFF' || ch == '\u200B' || ch == '\u200C' || ch == '\u200D') i++;
            else break;
        }
        return i == 0 ? s : s.substring(i);
    }
}
