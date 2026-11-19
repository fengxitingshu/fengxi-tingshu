package com.fengxi.source.kugou;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.security.MessageDigest;

/**
 * 酷狗听书源（Fx Plugin API v1）。
 *
 * 接口均为酷狗客户端网关开放调用（gateway.kugou.com），无需登录：
 *   搜索   http://mobilecdn.kugou.com/api/v3/search/album?keyword=<kw>&category=2
 *   章节   gateway /longaudio/v2/album_audios（x-router=openapi.kugou.com，POST JSON）
 *   取链   gateway /v5/url（x-router=trackercdn.kugou.com，key=md5(hash+secret+mid+0)）
 *
 * 网关签名：md5(SECRET + 按 key 排序的 k=v 串 + body + SECRET)。
 */
public class KuGouTingSource implements BrowsableSource {

    private static final String APP_ID = "1005";
    private static final String KEY_SECRET = "57ae12eb6890223e355ccfcb74edf70d1005";
    private static final String SECRET = "OIlwieks28dk2k092lksi2UIkp";
    private static final String UA = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";
    private static String MID = md5(UUID.randomUUID().toString());

    @Override public String id()   { return "fengxi_kugou"; }
    @Override public String name() { return "酷狗听书"; }
    @Override public String desc() { return "酷狗长音频专辑（官方网关接口，无需登录）。仅供学习参考。"; }
    @Override public boolean canSearch() { return true; }

    // ---------------- 分类浏览（tag_id 清单，固定最热排序） ----------------

    private static final String[][] CATS = {
        {"906", "有声小说"}, {"1097", "玄幻异界"}, {"1023", "武侠仙侠"},
        {"908", "悬疑推理"}, {"909", "都市"},     {"937", "历史"},
        {"1099", "科幻未来"}, {"977", "架空穿越"}, {"907", "现代言情"},
        {"981", "古代言情"}, {"1098", "影视原著"}, {"2033", "广播剧"},
        {"3416", "评书"},    {"932", "相声曲艺"},  {"933", "人文"},
        {"928", "知识"},     {"931", "儿童"},      {"1092", "情感"},
        {"1070", "助眠解压"},
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
        // gateway /longaudio/v1/album/list（GET，无 x-router，page_size=20）
        Map<String, String> biz = new java.util.LinkedHashMap<String, String>();
        biz.put("api_ver", "2");
        biz.put("gender", "0");
        biz.put("sort", "1");
        biz.put("tag_id", catId);
        biz.put("free", "1");
        biz.put("status", "0");
        biz.put("page", String.valueOf(page));
        biz.put("page_size", "20");
        biz.put("clientver", "20789");
        String body = gatewayGet("/longaudio/v1/album/list", biz, null);
        if (body == null) throw new Exception("酷狗分类接口无响应");
        JSONObject o = new JSONObject(body);
        String err = o.optString("error_code", "0");
        if (!"0".equals(err)) {
            throw new Exception("酷狗分类接口报错: " + o.optString("message", body));
        }
        JSONObject data = o.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("data_list");
        List<Book> out = new ArrayList<Book>();
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            if (v == null) continue;
            String albumId = v.optString("album_id", "");
            if (albumId.length() == 0) continue;
            Book b = new Book();
            b.source = id();
            b.bookId = albumId;
            b.title = v.optString("album_name", "");
            b.artist = "";
            b.intro = v.optString("intro", "");
            b.cover = v.optString("sizable_cover", "").replace("{size}", "480");
            b.url = albumId;
            if (b.title.length() == 0) continue;
            out.add(b);
        }
        return out;
    }

    // ---------------- 搜索 ----------------

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        if (kw == null || kw.trim().length() == 0) return new ArrayList<Book>();
        String u = "http://mobilecdn.kugou.com/api/v3/search/album?format=json&keyword="
                 + URLEncoder.encode(kw.trim(), "UTF-8")
                 + "&page=" + page + "&pagesize=20&showtype=1&category=2";
        String body = httpGet(u, null);
        if (body == null) throw new Exception("酷狗搜索接口无响应");
        JSONObject o = new JSONObject(body);
        JSONObject data = o.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("info");
        if (list == null) return new ArrayList<Book>();
        List<Book> out = new ArrayList<Book>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            if (v == null) continue;
            if (!"2".equals(v.optString("category")) || v.optInt("privilege", 0) == 10) continue;
            Book b = new Book();
            b.source = id();
            b.bookId = v.optString("albumid", "");
            b.title = v.optString("albumname", "");
            String singer = v.optString("singername", "");
            b.artist = singer.length() > 8 ? singer.substring(0, 8) : singer;
            b.cover = v.optString("imgurl", "");
            b.intro = v.optString("intro", "");
            b.url = b.bookId;
            if (b.bookId.length() == 0 || b.title.length() == 0) continue;
            out.add(b);
        }
        return out;
    }

    // ---------------- 详情 + 章节（网关分页，每页 400，拉全） ----------------

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        if (bookUrl == null || bookUrl.length() == 0) throw new Exception("bookUrl 为空");
        String albumId = bookUrl;
        List<Chapter> chapters = new ArrayList<Chapter>();
        int total = -1;
        final int pageSize = 400;
        final int maxPages = 20;
        boolean retried = false;

        for (int pn = 1; pn <= maxPages; pn++) {
            String reqBody = "{\"album_id\":\"" + albumId + "\",\"area_code\":1,\"tagid\":0,\"page\":"
                           + pn + ",\"pagesize\":" + pageSize + "}";
            Map<String, String> extra = new HashMap<String, String>();
            extra.put("x-router", "openapi.kugou.com");
            extra.put("KG-TID", "78");
            String body = gatewayPost("/longaudio/v2/album_audios", reqBody, extra);
            JSONObject o = new JSONObject(body);
            if (!"0".equals(o.optString("error_code"))) {
                String msg = o.optString("message", "");
                if (msg.contains("需要验证") && !retried) {
                    retried = true;
                    MID = md5(UUID.randomUUID().toString());   // 换设备指纹重试一次
                    pn--;
                    continue;
                }
                throw new Exception("酷狗章节接口报错: " + msg);
            }
            JSONArray list = o.optJSONArray("data");
            if (list == null || list.length() == 0) break;
            if (total < 0) total = o.optInt("total", -1);
            for (int i = 0; i < list.length(); i++) {
                JSONObject v = list.optJSONObject(i);
                if (v == null) continue;
                String hash = v.optString("hash", "");
                String trackId = v.optString("album_audio_id", "");
                if (hash.length() == 0 || trackId.length() == 0) continue;
                String title = v.optString("audio_name", "");
                int idx = chapters.size() + 1;
                Chapter c = new Chapter(idx, title, "kgtrack:" + trackId + "@" + hash.toLowerCase() + "@" + v.optInt("timelength", 0));
                chapters.add(c);
            }
            if (total > 0 && chapters.size() >= total) break;
        }
        if (chapters.isEmpty()) throw new Exception("该专辑没有章节数据，请在搜索页换个资源");

        BookDetail d = new BookDetail();
        Book b = new Book();
        b.source = id();
        b.bookId = albumId;
        b.url = albumId;
        d.book = b;
        d.chapters = chapters;
        d.total = total > 0 ? total : chapters.size();
        return d;
    }

    // ---------------- 取播放直链 ----------------

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        if (ch == null || ch.url == null || !ch.url.startsWith("kgtrack:")) return null;
        String trackId = ch.url.substring("kgtrack:".length());
        String[] parts = trackId.split("@");
        if (parts.length < 2) return null;
        String albumAudioId = parts[0];
        String hash = parts[1];
        String albumId = (book == null || book.bookId == null) ? "" : book.bookId;

        Map<String, String> biz = new LinkedHashMap();
        biz.put("album_id", albumId);
        biz.put("area_code", "1");
        biz.put("hash", hash);
        biz.put("ssa_flag", "is_fromtrack");
        biz.put("version", "11430");
        biz.put("page_id", "151369488");
        biz.put("quality", "128");
        biz.put("album_audio_id", albumAudioId);
        biz.put("behavior", "play");
        biz.put("pid", "2");
        biz.put("cmd", "26");
        biz.put("pidversion", "3001");
        biz.put("IsFreePart", "0");
        biz.put("ppage_id", "463467626,350369493,788954147");
        biz.put("cdnBackup", "1");
        biz.put("module", "");
        biz.put("clientver", "11430");
        biz.put("key", md5(hash + KEY_SECRET + MID + "0"));

        Map<String, String> extra = new HashMap<String, String>();
        extra.put("x-router", "trackercdn.kugou.com");
        String body = gatewayGet("/v5/url", biz, extra);
        JSONObject o = new JSONObject(body);
        JSONArray urls = o.optJSONArray("url");
        if (urls == null || urls.length() == 0) {
            throw new Exception("该音频需要酷狗会员或付费，请换个资源");
        }
        return urls.getString(0);
    }

    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        return h;
    }

    @Override
    public Map<String, String> coverHeaders(String coverUrl) {
        return null;
    }

    // ==================== 酷狗网关 ====================

    private static class LinkedHashMap extends java.util.LinkedHashMap<String, String> {}

    private String gatewayGet(String path, Map<String, String> biz, Map<String, String> extra) throws Exception {
        String clienttime = String.valueOf(System.currentTimeMillis() / 1000);
        Map<String, String> params = baseParams(clienttime);
        if (biz != null) params.putAll(biz);
        StringBuilder u = new StringBuilder("https://gateway.kugou.com").append(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) u.append('&');
            first = false;
            u.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        u.append("&signature=").append(signature(params, ""));
        return httpGet(u.toString(), headers(clienttime, extra));
    }

    private String gatewayPost(String path, String jsonBody, Map<String, String> extra) throws Exception {
        String clienttime = String.valueOf(System.currentTimeMillis() / 1000);
        Map<String, String> params = baseParams(clienttime);
        StringBuilder u = new StringBuilder("https://gateway.kugou.com").append(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) u.append('&');
            first = false;
            u.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        u.append("&signature=").append(signature(params, jsonBody));
        Map<String, String> h = headers(clienttime, extra);
        return httpPostJson(u.toString(), jsonBody, h);
    }

    private Map<String, String> baseParams(String clienttime) {
        Map<String, String> p = new java.util.LinkedHashMap<String, String>();
        p.put("dfid", "-");
        p.put("mid", MID);
        p.put("uuid", "-");
        p.put("appid", APP_ID);
        p.put("clientver", "20489");
        p.put("clienttime", clienttime);
        return p;
    }

    private Map<String, String> headers(String clienttime, Map<String, String> extra) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("dfid", "-");
        h.put("mid", MID);
        h.put("clienttime", clienttime);
        h.put("kg-rc", "1");
        h.put("kg-thash", "5d816a0");
        h.put("kg-rec", "1");
        h.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
        if (extra != null) h.putAll(extra);
        return h;
    }

    /** md5(SECRET + 按 key 排序的 k=v + body + SECRET) */
    private String signature(Map<String, String> params, String body) {
        TreeMap<String, String> sorted = new TreeMap<String, String>(params);
        StringBuilder sb = new StringBuilder(SECRET);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        if (body == null) body = "";
        sb.append(body).append(SECRET);
        return md5(sb.toString());
    }

    // ==================== 工具 ====================

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
        return http(url, "GET", null, extra);
    }

    private static String httpPostJson(String url, String json, Map<String, String> extra) {
        return http(url, "POST", json, extra);
    }

    private static String http(String url, String method, String postBody, Map<String, String> extra) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            if (extra != null) {
                for (Map.Entry<String, String> e : extra.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (postBody != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                c.getOutputStream().write(postBody.getBytes("UTF-8"));
                c.getOutputStream().close();
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
