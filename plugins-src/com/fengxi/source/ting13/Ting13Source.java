package com.fengxi.source.ting13;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.Source;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 听13 源（Fx Plugin API v1）——HTML 爬虫型，仅自写自用，不随公开包分发。
 *
 *   搜索   POST https://www.ting13.cc/novelsearch/search/result.html  (searchword=kw)
 *          ★ 必须带 Referer 头，否则站点返回 200 的「友情提示」反爬拦截页（无结果）。
 *            该页还兼做限流页（访问过快时同样出现）。
 *   章节   GET  https://m.ting13.cc<bookPath>?p=<n>   .play-list a（每页 30，翻页拉全）
 *   播放   GET  https://m.ting13.cc<chapterPath> 取 meta _c/_b/_p/_d + 会话 cookie
 *          POST https://m.ting13.cc/api/mapi/play (nid/cid/sort + sc/sp 头)
 *
 * sp 混淆算法（62 字符表，每字符 → 随机+映射+随机 三字符组）：
 *   映射 = 表内位置 +3 取模。与原实现一致。
 */
public class Ting13Source implements Source {

    private static final String HOST = "m.ting13.cc";
    private static final String ALPHA =
        "PXhw7U1B0a9kQDKZsTjIASmOeNzxYG4CHo1JyRfg2b8FLpEvr3FtVnlqMidu6c";
    private static final String UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    @Override public String id()   { return "fengxi_ting13"; }
    @Override public String name() { return "听13"; }
    @Override public String desc() { return "听13 聚合站（网页解析，仅供学习参考，自用）。"; }
    @Override public boolean canSearch() { return true; }

    // ---------------- 搜索 ----------------

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        if (page > 1) return new ArrayList<Book>();
        if (kw == null || kw.trim().length() == 0) return new ArrayList<Book>();

        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("searchword", kw.trim());
        // ★ 站点反爬：搜索 POST 必须带 Referer，否则返回 200 的拦截页而不是结果
        Map<String, String> extra = new LinkedHashMap<String, String>();
        extra.put("Referer", "https://www.ting13.cc/");
        String html = httpPostForm("https://www.ting13.cc/novelsearch/search/result.html",
                                   form, extra, null);
        if (html == null) throw new Exception("听13 搜索接口无响应");
        if (html.contains("友情提示")) {
            throw new Exception("听13 搜索被站点拦截（访问过快或反爬），请稍后再试");
        }

        List<Book> out = new ArrayList<Book>();
        Document doc = Jsoup.parse(html);
        Element works = doc.selectFirst(".list-works");
        if (works == null) return out;                    // 无结果/被拦，返回空列表
        for (Element li : works.select("li")) {
            Element dt = li.selectFirst(".list-book-dt");
            if (dt == null) continue;
            Element a = dt.selectFirst("a");
            if (a == null) continue;
            String path = a.attr("href");                 // /youshengxiaoshuo/18763/
            String title = a.text().replace("-", "");
            if (path.length() == 0 || title.length() == 0) continue;
            Book b = new Book();
            b.source = id();
            b.bookId = bookIdFromPath(path);
            b.title = title;
            Element boy = li.selectFirst(".book-boyin a");
            b.artist = boy == null ? "" : boy.text();
            Element img = li.selectFirst("img");
            b.cover = img == null ? "" : img.attr("src");
            Element des = li.selectFirst(".list-book-des");
            String intro = des == null ? "" : des.text();
            if (intro.length() > 500) intro = intro.substring(0, 500);
            b.intro = intro;
            b.url = path;
            out.add(b);
            if (out.size() >= 12) break;
        }
        return out;
    }

    // ---------------- 详情 + 章节（翻页拉全） ----------------

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        if (bookUrl == null || bookUrl.indexOf("/youshengxiaoshuo/") != 0) {
            throw new Exception("bookUrl 非法：" + bookUrl);
        }
        String base = "https://" + HOST + bookUrl;
        List<Chapter> chapters = new ArrayList<Chapter>();
        String intro = "";

        for (int p = 1; p <= 100; p++) {
            String html = httpGet(base + "?p=" + p, null, null);
            if (html == null) break;
            if (html.contains("<title>系统发生错误")) {
                throw new Exception("听13 站点报错，请稍后再试");
            }
            Document doc = Jsoup.parse(html);
            Element list = doc.selectFirst(".play-list");
            if (list == null) break;
            if (p == 1) {
                Element des = doc.selectFirst(".book-des");
                if (des == null) des = doc.selectFirst(".intro");
                intro = des == null ? "" : des.text();
                if (intro.length() > 1000) intro = intro.substring(0, 1000);
            }
            int before = chapters.size();
            for (Element a : list.select("a")) {
                String path = a.attr("href");             // /play/28485_1_201874.html
                if (path.indexOf("/play/") != 0) continue;
                Element i = a.selectFirst("i");
                String title = i == null ? a.text() : i.text();
                if (title == null || title.length() == 0) title = "第" + (chapters.size() + 1) + "集";
                chapters.add(new Chapter(chapters.size() + 1, title, path));
            }
            if (chapters.size() == before) break;         // 该页无新章节 → 到头了
        }
        if (chapters.isEmpty()) throw new Exception("这本书没有章节数据，请在搜索页换个资源");

        BookDetail d = new BookDetail();
        Book b = new Book();
        b.source = id();
        b.bookId = bookIdFromPath(bookUrl);
        b.url = bookUrl;
        d.book = b;
        d.intro = intro;
        d.chapters = chapters;
        d.total = chapters.size();
        return d;
    }

    // ---------------- 取播放直链 ----------------

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        if (ch == null || ch.url == null || ch.url.indexOf("/play/") != 0) return null;
        String cp = "https://" + HOST + ch.url;

        for (int attempt = 0; attempt < 2; attempt++) {
            // 1. 章节页：拿 meta 四件套 + 会话 cookie
            String html = httpGet(cp, null, null);
            if (html == null) throw new Exception("听13 章节页无响应");
            if (html.contains("小说不存在或者被禁止查看")) {
                throw new Exception("这一集没有内容，换个资源试试");
            }
            Document doc = Jsoup.parse(html);
            String sc = meta(doc, "_c"), nid = meta(doc, "_b");
            String cid = meta(doc, "_p"), sort = meta(doc, "_d");
            if (nid == null || nid.length() == 0) {
                throw new Exception("听13 播放参数获取失败，请稍后重试");
            }
            String cookie = lastCookies();                // 章节页响应里抓的 Set-Cookie

            // 2. 播放接口
            Map<String, String> form = new LinkedHashMap<String, String>();
            form.put("nid", nid);
            form.put("cid", cid == null ? "" : cid);
            form.put("sort", sort == null ? "" : sort);
            Map<String, String> h = new LinkedHashMap<String, String>();
            if (cookie != null) h.put("cookie", cookie);
            h.put("sp", obfuscate(sc == null ? "" : sc));
            h.put("sc", sc == null ? "" : sc);
            h.put("X-Requested-With", "XMLHttpRequest");
            h.put("Referer", cp);
            h.put("Origin", "https://" + HOST);
            String resp = httpPostForm("https://" + HOST + "/api/mapi/play", form, h, cp);
            if (resp == null) throw new Exception("听13 播放接口无响应");

            JSONObject o = new JSONObject(resp);
            String status = o.optString("status", "");
            if ("200".equals(status)) {
                return o.optString("url", "");
            }
            String msg = o.optString("msg", "");
            if (msg.contains("访问过快")) {
                throw new Exception("访问太快，听13 有频次控制，请稍等一会再试");
            }
            // cookie 失效 → 刷新会话重试一次
            if (attempt == 0) {
                refreshSession();
                continue;
            }
            throw new Exception("听13 播放失败：" + (msg.length() > 0 ? msg : resp));
        }
        return null;
    }

    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        return null;
    }

    @Override
    public Map<String, String> coverHeaders(String coverUrl) {
        return null;
    }

    // ==================== 会话 cookie ====================

    private static volatile String lastCookie = null;

    /** 从最近一次章节页响应头抓 Set-Cookie（由 httpGet 填充） */
    private String lastCookies() { return lastCookie; }

    /** 刷新会话：重新访问主页拿新 cookie */
    private void refreshSession() {
        try { httpGet("https://" + HOST + "/", null, null); } catch (Throwable ignored) {}
    }

    // ==================== sp 混淆 ====================

    private static String obfuscate(String sc) {
        if (sc == null) sc = "";
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder(sc.length() * 3);
        for (int i = 0; i < sc.length(); i++) {
            char c = sc.charAt(i);
            int idx = ALPHA.indexOf(c);
            char mapped = idx < 0 ? c : ALPHA.charAt((idx + 3) % 62);
            sb.append(ALPHA.charAt(rnd.nextInt(62)))
              .append(mapped)
              .append(ALPHA.charAt(rnd.nextInt(62)));
        }
        return sb.toString();
    }

    private static String meta(Document doc, String name) {
        Element e = doc.selectFirst("meta[name=" + name + "]");
        return e == null ? null : e.attr("content");
    }

    private static String bookIdFromPath(String path) {
        // /youshengxiaoshuo/18763/ → 18763
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].length() > 0) return parts[i];
        }
        return path;
    }

    // ==================== HTTP ====================

    private static String httpGet(String url, Map<String, String> extra, String[] cookieOut) {
        return http(url, "GET", null, extra, true);
    }

    private static String httpPostForm(String url, Map<String, String> form,
                                       Map<String, String> extra, String referer) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        try {
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (!first) sb.append('&');
                first = false;
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                  .append('=')
                  .append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
        } catch (Exception e) {
            return null;
        }
        return http(url, "POST", sb.toString(), extra, false);
    }

    private static String http(String url, String method, String formBody,
                               Map<String, String> extra, boolean captureCookie) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            if (extra != null) {
                for (Map.Entry<String, String> e : extra.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (formBody != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream os = c.getOutputStream();
                os.write(formBody.getBytes("UTF-8"));
                os.close();
            }
            if (captureCookie) {
                StringBuilder ck = new StringBuilder();
                int n = 0;
                for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                    if (h.getKey() != null && "Set-Cookie".equalsIgnoreCase(h.getKey())
                            && h.getValue() != null) {
                        for (String v : h.getValue()) {
                            if (v != null && v.length() > 0) {
                                if (n++ > 0) ck.append("; ");
                                ck.append(v.split(";", 2)[0].trim());
                            }
                        }
                    }
                }
                if (ck.length() > 0) lastCookie = ck.toString();
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
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
