package com.fengxi.source.kuwo;

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

/**
 * 酷我听书源（Fx Plugin API v1）。
 *
 * 接口均为酷我对客户端开放的 HTTP 接口，无需登录、无需签名：
 *   搜索   http://search.kuwo.cn/r.s?client=kt&all=<kw>&ft=album&...      JSON albumlist
 *   兜底   http://tingshu.kuwo.cn/tingshu/api/search/Search?wd=<kw>&...    JSON data.data
 *   章节   http://search.kuwo.cn/r.s?stype=albuminfo&albumid=<id>&rn=600   JSON musiclist + songnum + info
 *   取链   https://mobi.kuwo.cn/mobi.s?...&type=convert_url_with_sign&rid=<trackId>&br=320kmp3
 *
 * 注意（来自宿主 plugin-spec 的酷我事件教训）：
 *   - 酷我 CDN 带 Referer 会 403 → audioHeaders 返回 null；
 *   - 失败返回 null / 抛带人话的 Exception，绝不静默吞掉。
 */
public class KuwoTingSource implements BrowsableSource {

    private static final String UA_DESKTOP =
        "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/94.0.4606.71 Mobile Safari/537.36";

    private static final String SEARCH_BASE =
        "http://search.kuwo.cn/r.s?client=kt&ft=album&newsearch=1&itemset=web_2013&cluster=0"
        + "&rformat=json&encoding=utf8&show_copyright_off=1&vipver=MUSIC_8.0.3.0_BCS75"
        + "&show_series_listen=1&version=9.1.8.1";

    private static final String ALBUM_BASE =
        "http://search.kuwo.cn/r.s?stype=albuminfo&loginUid=0&loginSid=null"
        + "&prod=kwplayer_ar_9.1.7.0&bkprod=kwbook_ar_9.1.7.0"
        + "&source=kwplayer_ar_9.1.7.0_t18.apk&bksource=kwbook_ar_9.1.7.0_t18.apk"
        + "&corp=kuwo&show_copyright_off=1&vipver=MUSIC_8.2.0.0_BCS17&mobi=1&sortby=3&iskwbook=1";

    private static final String PLAY_BASE =
        "https://mobi.kuwo.cn/mobi.s?f=web&user=0"
        + "&source=kwplayercar_ar_6.0.0.9_B_jiakong_vh.apk&type=convert_url_with_sign";

    @Override public String id()   { return "fengxi_kuwo"; }
    @Override public String name() { return "酷我听书"; }
    @Override public String desc() { return "酷我听书专辑（官方接口，无需登录）。仅供学习参考。"; }
    @Override public boolean canSearch() { return true; }

    // ---------------- 分类浏览 ----------------

    private static final String[][] CATS = {
        {"2",  "有声小说"}, {"37", "音乐"}, {"5", "相声评书"},
        {"9",  "历史"},     {"62", "影视原声"},
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
        // http://tingshu.kuwo.cn/v2/api/search/filter/albums?categoryId=<top>&rn=20&pn=<0起>
        String u = "http://tingshu.kuwo.cn/v2/api/search/filter/albums"
                 + "?notrace=0&source=kwplayer_ar_9.1.8.1_tvivo.apk&platform=1"
                 + "&kweexVersion=1.1.5&uid=2511482006&sortType=playCnt"
                 + "&loginUid=540339516&bksource=kwbook_ar_9.1.8.1_tvivo.apk"
                 + "&rn=20&categoryId=" + URLEncoder.encode(catId, "UTF-8")
                 + "&pn=" + (page - 1);
        String body = httpGet(u, null);
        if (body == null) throw new Exception("酷我分类接口无响应");
        JSONObject o = new JSONObject(body);
        if (o.optInt("code", 0) != 200) {
            throw new Exception("酷我分类接口报错: " + o.optString("msg", body));
        }
        JSONObject data = o.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("data");
        List<Book> out = new ArrayList<Book>();
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            if (v == null) continue;
            Book b = new Book();
            b.source = id();
            b.bookId = v.optString("albumId", "");
            b.title = v.optString("albumName", "");
            b.intro = v.optString("title", "");
            String artist = v.optString("artistName", "").replace("主播", "");
            b.artist = artist.length() > 8 ? artist.substring(0, 8) : artist;
            b.cover = v.optString("coverImg", "");
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
        List<Book> out = searchMain(kw.trim(), page);
        if (out == null) out = searchFallback(kw.trim(), page);
        return out;
    }

    /** 主搜索：search.kuwo.cn/r.s albumlist */
    private List<Book> searchMain(String kw, int page) {
        try {
            String u = SEARCH_BASE + "&all=" + URLEncoder.encode(kw, "UTF-8")
                     + "&pn=" + (page - 1) + "&rn=15";
            String body = httpGet(u, null);
            if (body == null) return null;
            JSONObject o = new JSONObject(body);
            JSONArray list = o.optJSONArray("albumlist");
            if (list == null) return null;
            List<Book> out = new ArrayList<Book>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject v = list.optJSONObject(i);
                if (v == null) continue;
                int tracks = v.optInt("musiccnt", 0);
                if (tracks < 11) continue;                       // 太短的当噪音丢掉
                String artist = v.optString("artist", "");
                if (artist == null || artist.startsWith("Unknown")) continue;
                Book b = new Book();
                b.source = id();
                b.bookId = v.optString("id", "");
                b.title = v.optString("name", "");
                b.artist = artist.length() > 8 ? artist.substring(0, 8) : artist;
                b.cover = v.optString("img", "");
                b.url = b.bookId;
                if (b.bookId.length() == 0 || b.title.length() == 0) continue;
                out.add(b);
            }
            return out;
        } catch (Throwable t) {
            return null;   // 主接口挂了走兜底
        }
    }

    /** 兜底搜索：tingshu.kuwo.cn 客户端接口 */
    private List<Book> searchFallback(String kw, int page) throws Exception {
        String u = "http://tingshu.kuwo.cn/tingshu/api/search/Search?pn=" + page
                 + "&rn=15&type=album&version=8.5.6.1&kweexVersion=1.0.2&wd="
                 + URLEncoder.encode(kw, "UTF-8");
        String body = httpGet(u, null);
        if (body == null) return null;
        if (body.contains("</html>")) return new ArrayList<Book>();
        JSONObject o = new JSONObject(body);
        if (o.optInt("code", 0) != 200) return null;
        JSONObject data = o.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("data");
        if (list == null) return new ArrayList<Book>();
        List<Book> out = new ArrayList<Book>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            if (v == null) continue;
            if (v.optInt("songTotal", 0) <= 5) continue;
            Book b = new Book();
            b.source = id();
            b.bookId = v.optString("albumId", "");
            b.title = v.optString("albumName", "");
            String artist = v.optString("artistName", "");
            b.artist = artist.length() > 8 ? artist.substring(0, 8) : artist;
            b.cover = v.optString("coverImg", "");
            b.url = b.bookId;
            if (b.bookId.length() == 0 || b.title.length() == 0) continue;
            out.add(b);
        }
        return out;
    }

    // ---------------- 详情 + 章节（一次拿全，翻页直到 songnum） ----------------

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        if (bookUrl == null || bookUrl.length() == 0) throw new Exception("bookUrl 为空");
        final String albumId = bookUrl;
        Map<String, String> h = new HashMap<String, String>();
        h.put("Host", "search.kuwo.cn");

        List<Chapter> chapters = new ArrayList<Chapter>();
        String intro = "";
        int total = 0;
        final int pageSize = 600;
        final int maxPages = 30;

        for (int pn = 0; pn < maxPages; pn++) {
            String u = ALBUM_BASE + "&albumid=" + albumId + "&pn=" + pn + "&rn=" + pageSize;
            String body = httpGet(u, h);
            if (body == null) throw new Exception("酷我章节接口无响应（第 " + pn + " 页）");
            JSONObject o = new JSONObject(body);
            JSONArray list = o.optJSONArray("musiclist");
            if (list == null) {
                if (pn == 0) throw new Exception("酷我章节接口没有 musiclist，专辑可能已下架");
                break;
            }
            if (pn == 0) {
                total = o.optInt("songnum", 0);
                intro = o.optString("info", "");
            }
            for (int i = 0; i < list.length(); i++) {
                JSONObject v = list.optJSONObject(i);
                if (v == null) continue;
                String trackId = v.optString("musicrid", "");
                String title = v.optString("name", "");
                if (trackId.length() == 0) continue;
                int idx = chapters.size() + 1;
                int dur = v.optInt("duration", 0);
                if ((title == null || title.length() == 0) && dur > 0) {
                    title = "第" + idx + "集";
                }
                Chapter c = new Chapter(idx, title, "kwtrack:" + trackId);
                chapters.add(c);
            }
            if (total <= 0 || chapters.size() >= total) break;
            if (list.length() == 0) break;
            try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
        }
        if (chapters.isEmpty()) throw new Exception("该专辑没有章节数据，请在搜索页换个资源");

        BookDetail d = new BookDetail();
        Book b = new Book();
        b.source = id();
        b.bookId = albumId;
        b.url = albumId;
        d.book = b;
        d.intro = intro;
        d.chapters = chapters;
        d.total = total > 0 ? total : chapters.size();
        return d;
    }

    // ---------------- 取播放直链 ----------------

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        if (ch == null || ch.url == null || !ch.url.startsWith("kwtrack:")) return null;
        String trackId = ch.url.substring("kwtrack:".length());
        String br = "&br=320kmp3";
        for (int attempt = 0; attempt < 2; attempt++) {
            String u = PLAY_BASE + "&rid=" + trackId + br;
            String body = httpGet(u, null);
            if (body == null) return null;
            if (body.contains("the page you are looking")) return null;
            JSONObject o = new JSONObject(body);
            if (o.optInt("code", 0) != 200) {
                throw new Exception("酷我取链报错: " + o.optString("msg", body));
            }
            String url = o.optJSONObject("data") == null
                       ? null : o.optJSONObject("data").optString("url", "");
            if (url == null || url.length() == 0) return null;
            if (url.contains(".wma")) {
                if (attempt == 0) { br = "&br=128kmp3"; continue; }   // 降码率再试一次
                throw new Exception("安卓不支持 wma 格式，换个资源试试");
            }
            if (url.contains("2015636967.aac")) return null;          // 已知坏资源
            if (url.contains("3759149332")) throw new Exception("暂不支持 VIP 章节，换个资源试试");
            return url;
        }
        return null;
    }

    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        return null;   // 酷我 CDN 带 Referer 会 403，必须 null
    }

    @Override
    public Map<String, String> coverHeaders(String coverUrl) {
        return null;
    }

    // ==================== HTTP ====================

    private static String httpGet(String url, Map<String, String> extra) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA_DESKTOP);
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
