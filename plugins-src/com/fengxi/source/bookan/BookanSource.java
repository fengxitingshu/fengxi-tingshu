package com.fengxi.source.bookan;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.Source;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 博看书苑源（Fx Plugin API v1）——正版图书馆有声资源平台，纯 JSON 接口。
 * 仅自写自用，不随公开包分发。
 *
 *   搜索   GET https://es.bookan.com.cn/api/v3/voice/book?instanceId=25304&keyword=<kw>&pageNum=1&limitNum=15
 *          列表 data.list[]：name/id/cover/extra.author/intro
 *   章节   GET https://api.bookan.com.cn/voice/album/units?album_id=<id>&page=<p>&num=200&order=1
 *          列表 data.list[]：id/title/duration/file
 *   播放   零请求 —— 章节列表里的 file 字段就是直链（m4a）
 *
 * 站点内容以出版书/名著/评书为主，网文类搜索不到属正常。
 */
public class BookanSource implements Source {

    private static final String UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @Override public String id()   { return "fengxi_bookan"; }
    @Override public String name() { return "博看书苑"; }
    @Override public String desc() { return "博看书苑·图书馆有声资源（出版书为主，仅供学习参考，自用）。"; }
    @Override public boolean canSearch() { return true; }

    // ---------------- 搜索 ----------------

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        if (page > 1) return new ArrayList<Book>();
        if (kw == null || kw.trim().length() == 0) return new ArrayList<Book>();

        String resp = httpGet("https://es.bookan.com.cn/api/v3/voice/book?instanceId=25304"
                + "&keyword=" + URLEncoder.encode(kw.trim(), "UTF-8")
                + "&pageNum=1&limitNum=15");
        if (resp == null) throw new Exception("博看搜索接口无响应");

        List<Book> out = new ArrayList<Book>();
        JSONObject o = new JSONObject(resp);
        if (!"0".equals(o.optString("code", ""))) return out;
        JSONObject data = o.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("list");
        if (list == null) return out;

        for (int i = 0; i < list.length() && out.size() < 15; i++) {
            JSONObject v = list.optJSONObject(i);
            if (v == null) continue;
            String name = v.optString("name", "");
            long bid = v.optLong("id", 0);
            if (name.length() == 0 || bid <= 0) continue;
            Book b = new Book();
            b.source = id();
            b.bookId = String.valueOf(bid);
            b.url = b.bookId;
            b.title = name;
            JSONObject extra = v.optJSONObject("extra");
            b.artist = extra == null ? "" : extra.optString("author", "");
            b.cover = v.optString("cover", "");
            String intro = v.optString("intro", "");
            if (intro.length() > 500) intro = intro.substring(0, 500);
            b.intro = intro;
            out.add(b);
        }
        return out;
    }

    // ---------------- 详情 + 章节 ----------------

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        String albumId = bookUrl == null ? "" : bookUrl.trim();
        try {
            albumId = String.valueOf(Long.parseLong(albumId));
        } catch (NumberFormatException e) {
            throw new Exception("bookUrl 非法：" + bookUrl);
        }

        List<Chapter> chapters = new ArrayList<Chapter>();
        // 分页拉全（num=200 大页，拿不到新章节即停）
        for (int p = 1; p <= 50; p++) {
            String resp = httpGet("https://api.bookan.com.cn/voice/album/units?album_id="
                    + albumId + "&page=" + p + "&num=200&order=1");
            if (resp == null) break;
            JSONObject o = new JSONObject(resp);
            JSONObject data = o.optJSONObject("data");
            JSONArray list = data == null ? null : data.optJSONArray("list");
            if (list == null || list.length() == 0) break;
            int before = chapters.size();
            for (int i = 0; i < list.length(); i++) {
                JSONObject v = list.optJSONObject(i);
                if (v == null) continue;
                String title = v.optString("title", "");
                String file = v.optString("file", "");
                if (title.length() == 0) title = "第" + (chapters.size() + 1) + "集";
                Chapter c = new Chapter(chapters.size() + 1, title, file);
                c.directUrl = file;
                chapters.add(c);
            }
            if (chapters.size() == before) break;
        }
        if (chapters.isEmpty()) throw new Exception("这本书没有章节数据，请换个资源");

        BookDetail d = new BookDetail();
        Book b = new Book();
        b.source = id();
        b.bookId = albumId;
        b.url = albumId;
        d.book = b;
        d.chapters = chapters;
        d.total = chapters.size();
        return d;
    }

    // ---------------- 取播放直链 ----------------

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        if (ch == null) return null;
        if (ch.directUrl != null && ch.directUrl.length() > 0) return ch.directUrl;
        return ch.url == null || ch.url.length() == 0 ? null : ch.url;
    }

    @Override public Map<String, String> audioHeaders(String audioUrl) { return null; }
    @Override public Map<String, String> coverHeaders(String coverUrl) { return null; }

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
            c.setRequestProperty("Accept", "application/json,*/*");
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
