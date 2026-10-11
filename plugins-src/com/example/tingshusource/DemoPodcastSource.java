package com.example.tingshusource;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.Source;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 示例插件源：接入 iTunes 播客搜索（Apple 公开搜索 API，无需鉴权）。
 *
 * 用途：演示「我的听书」jar 插件源的完整写法 —— 只要实现 Source 接口、
 * 在 MANIFEST.MF 里写好 Source-Entry 入口类名，打包成 jar 就能被 App 加载。
 *
 * 编译：
 *   1. javac -source 8 -target 8 -bootclasspath android.jar -cp android.jar ...
 *   2. d8 --min-api 21 --output out/ *.class
 *   3. jar cfm demo.jar MANIFEST.MF -C out .
 *
 * 注意：插件运行在宿主 App 进程里，能访问网络和 App 私有目录，
 * 请只安装你信任来源的插件。
 */
public class DemoPodcastSource implements Source {

    private static final String UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    @Override public String id()   { return "demo_podcast"; }
    @Override public String name() { return "播客示例源"; }
    @Override public String desc() { return "插件演示：搜索 iTunes 公开播客库，可直接试听。仅供学习参考。"; }
    @Override public boolean canSearch() { return true; }

    // ---------------- 搜索 ----------------

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        List<Book> out = new ArrayList<Book>();
        if (kw == null || kw.trim().length() == 0) return out;

        int limit = 25;
        int offset = Math.max(0, (page - 1) * limit);
        String u = "https://itunes.apple.com/search?media=podcast&entity=podcast"
                 + "&limit=" + limit + "&offset=" + offset
                 + "&term=" + URLEncoder.encode(kw.trim(), "UTF-8");

        String body = httpGet(u, null);
        if (body == null) return null;

        // 极简 JSON 解析：只取 results 数组里的几个字段，避免依赖第三方库
        List<String> items = splitTopObjects(body, "\"results\"");
        for (String it : items) {
            String title = str(it, "collectionName");
            if (title == null || title.length() == 0) continue;
            Book b = new Book();
            b.source = id();
            b.bookId = num(it, "collectionId");
            if (b.bookId == null || b.bookId.length() == 0) b.bookId = String.valueOf(title.hashCode());
            b.title = title;
            b.author = str(it, "artistName");
            b.cover = str(it, "artworkUrl600");
            if (b.cover == null) b.cover = str(it, "artworkUrl100");
            b.url = str(it, "feedUrl");       // 详情/取链定位串：RSS 地址
            b.category = str(it, "primaryGenreName");
            b.intro = str(it, "collectionName");
            out.add(b);
        }
        return out;
    }

    // ---------------- 详情 + 章节 ----------------

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        BookDetail d = new BookDetail();
        if (bookUrl == null || bookUrl.length() == 0) return d;

        String rss = bookUrl.trim();
        if (!rss.startsWith("http")) {
            // 兼容 url 字段被前端改写成 "id|feed" 的情况
            int p = rss.indexOf('|');
            if (p >= 0) rss = rss.substring(p + 1);
        }

        String xml = httpGet(rss, null);
        if (xml == null) return d;

        Book b = new Book();
        b.source = id();
        b.title = tag(xml, "title");
        b.author = tag(xml, "itunes:author");
        b.intro = stripHtml(tag(xml, "description"));
        b.cover = attr(xml, "itunes:image", "href");
        d.book = b;
        d.intro = b.intro;

        // 逐个 <item> 抽章节；音频地址优先 enclosure url
        List<String> items = blocks(xml, "<item", "</item>");
        int idx = 0;
        for (String it : items) {
            String t = tag(it, "title");
            String au = attr(it, "enclosure", "url");
            if (au == null || au.length() == 0) continue;
            idx++;
            Chapter c = new Chapter(idx, t == null || t.length() == 0 ? ("第" + idx + "集") : t, au);
            c.directUrl = au;   // 直链已知，省一次请求
            d.chapters.add(c);
            if (idx >= 300) break;  // 最多取 300 集，别把内存撑爆
        }
        d.total = d.chapters.size();
        return d;
    }

    // ---------------- 取音频直链 ----------------

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        if (ch == null) return null;
        if (ch.directUrl != null && ch.directUrl.length() > 0) return ch.directUrl;
        return ch.url;   // url 本身就是直链
    }

    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        return h;
    }

    @Override
    public Map<String, String> coverHeaders(String coverUrl) { return null; }

    // ==================== 工具 ====================

    private static String httpGet(String url, Map<String, String> extra) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            if (extra != null) {
                for (Map.Entry<String, String> e : extra.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** 取 "key": "value" 形式的字符串字段（简易） */
    private static String str(String json, String key) {
        if (json == null) return null;
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int c = json.indexOf(':', i + pat.length());
        if (c < 0) return null;
        int q = json.indexOf('"', c + 1);
        if (q < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int p = q + 1; p < json.length(); p++) {
            char ch = json.charAt(p);
            if (ch == '\\') {
                if (p + 1 < json.length()) { sb.append(json.charAt(p + 1)); p++; }
                continue;
            }
            if (ch == '"') break;
            sb.append(ch);
        }
        return sb.toString();
    }

    /** 取数字字段 */
    private static String num(String json, String key) {
        if (json == null) return null;
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int c = json.indexOf(':', i + pat.length());
        if (c < 0) return null;
        int p = c + 1;
        while (p < json.length() && (json.charAt(p) == ' ')) p++;
        int s = p;
        while (p < json.length()) {
            char ch = json.charAt(p);
            if ((ch >= '0' && ch <= '9') || ch == '.') p++; else break;
        }
        return p > s ? json.substring(s, p) : null;
    }

    /** 从 {"results":[{...},{...}]} 里切出顶层对象字符串 */
    private static List<String> splitTopObjects(String json, String key) {
        List<String> out = new ArrayList<String>();
        if (json == null) return out;
        int k = json.indexOf(key);
        if (k < 0) return out;
        int lb = json.indexOf('[', k);
        if (lb < 0) return out;
        int depth = 0, start = -1;
        boolean inStr = false;
        for (int p = lb + 1; p < json.length(); p++) {
            char ch = json.charAt(p);
            if (inStr) {
                if (ch == '\\') { p++; continue; }
                if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') { inStr = true; continue; }
            if (ch == '{') { if (depth == 0) start = p; depth++; }
            else if (ch == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(json.substring(start, p + 1));
                    start = -1;
                }
            } else if (ch == ']' && depth == 0) break;
        }
        return out;
    }

    /** 切出所有 <name ...>...</name> 块 */
    private static List<String> blocks(String xml, String open, String close) {
        List<String> out = new ArrayList<String>();
        if (xml == null) return out;
        int p = 0;
        while (true) {
            int a = xml.indexOf(open, p);
            if (a < 0) break;
            int b = xml.indexOf(close, a);
            if (b < 0) break;
            out.add(xml.substring(a, b + close.length()));
            p = b + close.length();
        }
        return out;
    }

    /** 取 <tag>text</tag> */
    private static String tag(String xml, String name) {
        if (xml == null) return null;
        int a = xml.indexOf("<" + name);
        if (a < 0) return null;
        int gt = xml.indexOf('>', a);
        if (gt < 0) return null;
        if (gt > a && xml.charAt(gt - 1) == '/') return "";
        int b = xml.indexOf("</" + name + ">", gt);
        if (b < 0) return null;
        String s = xml.substring(gt + 1, b);
        return s.indexOf("<![CDATA[") >= 0
            ? s.replace("<![CDATA[", "").replace("]]>", "").trim()
            : stripHtml(s);
    }

    /** 取 <tag ... attr="v"> */
    private static String attr(String xml, String tag, String at) {
        if (xml == null) return null;
        int p = 0;
        while (true) {
            int a = xml.indexOf("<" + tag, p);
            if (a < 0) return null;
            int gt = xml.indexOf('>', a);
            if (gt < 0) return null;
            String head = xml.substring(a, gt);
            int i = head.indexOf(at + "=\"");
            if (i >= 0) {
                int q1 = a + i + at.length() + 2;
                int q2 = xml.indexOf('"', q1);
                if (q2 > q1) return xml.substring(q1, q2);
            }
            p = gt + 1;
        }
    }

    private static String stripHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '<') { in = true; continue; }
            if (ch == '>') { in = false; continue; }
            if (!in) sb.append(ch);
        }
        return sb.toString().replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .trim();
    }
}
