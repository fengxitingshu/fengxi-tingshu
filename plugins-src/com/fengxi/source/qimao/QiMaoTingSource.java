package com.fengxi.source.qimao;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.BrowsableSource;
import com.tingshu.app.source.Category;
import com.tingshu.app.source.Source;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 七猫听书源（Fx Plugin API v1，wtzw.com 客户端接口，无需登录）。
 *
 *   搜索   https://api-bc.wtzw.com/search/v1/words?wd=<kw>&gender=3&tab=1&...
 *   章节   https://api-ks.wtzw.com/api/v1/album/chapter-list?album_id=<id>
 *          无真人章节时走 AI：/api/v1/chapter/chapter-list → content_md5 → /api/v1/listen/preload-chapter-list
 *   取链   https://api-ks.wtzw.com/api/v1/album/preload-audio-list?album_id=<id>&chapter_ids=<id>
 *
 * 签名：md5(按 key 排序的 k=v 串 + SALT)；请求头里另有独立 sign。
 */
public class QiMaoTingSource implements BrowsableSource {

    private static final String API_BC = "https://api-bc.wtzw.com";
    private static final String API_KS = "https://api-ks.wtzw.com";
    private static final String APPLICATION_ID = "com.kmxs.reader";
    private static final String APP_VERSION = "73500";
    private static final String SALT = "d3dGiJc651gSQ8w1";
    private static final String UA = "webviewversion/0";
    private static final String AI_VOICE_ID = "4";

    @Override public String id()   { return "fengxi_qimao"; }
    @Override public String name() { return "七猫听书"; }
    @Override public String desc() { return "七猫听书专辑（官方接口，含 AI 朗读兜底）。仅供学习参考。"; }
    @Override public boolean canSearch() { return true; }

    // ---------------- 分类浏览（榜单接口，固定热门排序，单页无翻页） ----------------

    private static final String[][] CATS = {
        {"0",   "全部"}, {"758", "言情"}, {"717", "推理"}, {"669", "历史"},
        {"27",  "恐怖"}, {"773", "悬疑"}, {"772", "灵异"}, {"317", "穿越"},
        {"979", "都市"}, {"980", "成长"}, {"795", "科幻"}, {"925", "玄幻奇幻"},
    };

    @Override public boolean canBrowse() { return true; }

    @Override
    public List<Category> categories() {
        List<Category> out = new ArrayList<Category>();
        for (int i = 0; i < CATS.length; i++) {
            out.add(new Category(CATS[i][0], CATS[i][1]));
        }
        return out;
    }

    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        if (page > 1) return new ArrayList<Book>();   // 榜单接口无翻页
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("book_privacy", "1");
        p.put("category_id", catId);
        p.put("category_type", "0".equals(catId) ? "1" : "7");
        p.put("rank_type", "hot");
        String body = apiGet(API_BC, "/api/v1/album/leader-board", p);
        JSONObject o = new JSONObject(body);
        JSONObject data = o.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("books");
        List<Book> out = new ArrayList<Book>();
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            if (v == null) continue;
            Book b = new Book();
            b.source = id();
            b.bookId = v.optString("album_id", "");
            b.title = v.optString("title", "");
            String author = v.optString("author", "");
            b.artist = author.length() > 12 ? author.substring(0, 12) : author;
            b.cover = v.optString("image_link", "");
            b.intro = stripHtml(v.optString("intro", ""));
            b.url = b.bookId;
            if (b.bookId.length() == 0 || b.title.length() == 0) continue;
            out.add(b);
        }
        return out;
    }

    // ---------------- 搜索 ----------------

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        if (kw == null || kw.trim().length() == 0) return new ArrayList<Book>();
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("gender", "3");
        p.put("imei_ip", "2937357107");
        p.put("page", String.valueOf(page));
        p.put("tab", "1");
        p.put("wd", kw.trim());
        String body = apiGet(API_BC, "/search/v1/words", p);
        JSONObject o = new JSONObject(body);
        JSONObject data = o.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("books");
        if (list == null) return new ArrayList<Book>();
        List<Book> out = new ArrayList<Book>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            if (v == null || !"1".equals(v.optString("is_audio"))) continue;
            Book b = new Book();
            b.source = id();
            b.bookId = v.optString("album_id", "");
            b.title = v.optString("original_title", "");
            String author = v.optString("author", "");
            if (author.contains("主播")) {
                String[] parts = author.split("主播：");
                b.artist = (parts.length > 1 ? parts[1].replace("）", "").replace(")", "") : "主播")
                         + "(作者:" + parts[0] + ")";
            } else {
                b.artist = "AI朗读(作者:" + author + ")";
            }
            b.cover = v.optString("image_link", "");
            b.intro = stripHtml(v.optString("intro", ""));
            b.url = b.bookId;
            if (b.bookId.length() == 0 || b.title.length() == 0) continue;
            out.add(b);
        }
        return out;
    }

    // ---------------- 详情 + 章节 ----------------

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        if (bookUrl == null || bookUrl.length() == 0) throw new Exception("bookUrl 为空");
        String bookId = bookUrl;

        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("album_id", bookId);
        String body = apiGet(API_KS, "/api/v1/album/chapter-list", p);
        JSONObject data = new JSONObject(body).optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("chapter_list");

        List<Chapter> chapters = new ArrayList<Chapter>();
        boolean ai = false;
        if (list != null && list.length() > 0) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject v = list.optJSONObject(i);
                if (v == null) continue;
                String cid = v.optString("id", "");
                if (cid.length() == 0) continue;
                int idx = v.optInt("index", chapters.size() + 1);
                Chapter c = new Chapter(idx <= 0 ? chapters.size() + 1 : idx,
                                        v.optString("title", ""), "qmtrack:" + cid);
                chapters.add(c);
            }
        } else {
            ai = true;
            JSONArray aiList = aiChapterList(bookId);
            if (aiList == null) throw new Exception("该专辑没有章节数据，请在搜索页换个资源");
            for (int i = 0; i < aiList.length(); i++) {
                JSONObject v = aiList.optJSONObject(i);
                if (v == null) continue;
                String cid = v.optString("id", "");
                if (cid.length() == 0) continue;
                Chapter c = new Chapter(chapters.size() + 1, v.optString("title", ""), "qmtrack:" + cid);
                chapters.add(c);
            }
        }
        if (chapters.isEmpty()) throw new Exception("该专辑没有章节数据，请在搜索页换个资源");

        BookDetail d = new BookDetail();
        Book b = new Book();
        b.source = id();
        b.bookId = bookId;
        b.url = bookId;
        d.book = b;
        d.chapters = chapters;
        d.total = chapters.size();
        return d;
    }

    // ---------------- 取播放直链（真人音频优先，AI 朗读兜底） ----------------

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        if (ch == null || ch.url == null || !ch.url.startsWith("qmtrack:")) return null;
        String cid = ch.url.substring("qmtrack:".length());
        String bookId = (book == null || book.bookId == null) ? "" : book.bookId;

        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("album_id", bookId);
        p.put("chapter_ids", cid);
        String body = apiGet(API_KS, "/api/v1/album/preload-audio-list", p);
        JSONObject data = new JSONObject(body).optJSONObject("data");
        JSONArray voices = data == null ? null : data.optJSONArray("voice_list");
        if (voices != null) {
            for (int i = 0; i < voices.length(); i++) {
                JSONObject v = voices.optJSONObject(i);
                if (v != null && cid.equals(v.optString("chapter_id"))) {
                    String url = v.optString("voice_url", "");
                    if (url.length() > 0) return url;
                }
            }
        }
        // AI 朗读兜底
        String md5 = aiContentMd5(bookId, cid);
        if (md5 == null || md5.length() == 0) {
            throw new Exception("该音频需要七猫会员或付费，请换个资源");
        }
        Map<String, String> ai = new LinkedHashMap<String, String>();
        ai.put("book_id", bookId);
        ai.put("chapter_id", cid);
        ai.put("content_md5", md5);
        ai.put("voice_id", AI_VOICE_ID);
        String ttsBody = apiGet(API_KS, "/api/v1/listen/preload-chapter-list", ai);
        JSONObject ttsData = new JSONObject(ttsBody).optJSONObject("data");
        JSONArray cl = ttsData == null ? null : ttsData.optJSONArray("chapter_list");
        if (cl != null) {
            for (int i = 0; i < cl.length(); i++) {
                JSONObject v = cl.optJSONObject(i);
                if (v != null && cid.equals(v.optString("chapter_id"))) {
                    String url = v.optString("voice_url", "");
                    if (url.length() > 0) return url;
                }
            }
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

    // ==================== 七猫 AI 章节辅助 ====================

    private JSONArray aiChapterList(String bookId) throws Exception {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("id", bookId);
        String body = apiGet(API_KS, "/api/v1/chapter/chapter-list", p);
        JSONObject data = new JSONObject(body).optJSONObject("data");
        return data == null ? null : data.optJSONArray("chapter_lists");
    }

    private String aiContentMd5(String bookId, String cid) throws Exception {
        JSONArray list = aiChapterList(bookId);
        if (list == null) return null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            if (v != null && cid.equals(v.optString("id"))) {
                return v.optString("content_md5", "");
            }
        }
        return null;
    }

    // ==================== 请求与签名 ====================

    private String apiGet(String baseUrl, String path, Map<String, String> params) throws Exception {
        StringBuilder u = new StringBuilder(baseUrl).append(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) u.append('&');
            first = false;
            u.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        u.append("&sign=").append(sign(params));
        return httpGet(u.toString(), headerMap());
    }

    private Map<String, String> headerMap() {
        // 注意：header sign 不含 sign 自身与 User-Agent（与客户端实现一致）
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("AUTHORIZATION", "");
        h.put("app-version", APP_VERSION);
        h.put("application-id", APPLICATION_ID);
        h.put("channel", "unknown");
        h.put("net-env", "1");
        h.put("platform", "android");
        h.put("qm-params", "");
        h.put("reg", "0");
        h.put("sign", sign(h));
        h.put("User-Agent", UA);
        return h;
    }

    /** md5(按 key 排序的 k=v + SALT) */
    private String sign(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<String, String>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append(SALT);
        return md5(sb.toString());
    }

    // ==================== 工具 ====================

    private static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 失败", e);
        }
    }

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
}
