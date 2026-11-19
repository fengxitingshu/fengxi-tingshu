package com.fengxi.source.ximalaya;

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
 * 喜马拉雅免费源（Fx Plugin API v1）——主站公开接口，只取免费内容。
 * 仅自写自用，不随公开包分发。
 *
 *   搜索   GET https://www.ximalaya.com/revision/search?core=album&kw=<kw>&page=<p>&rows=20&spellchecker=true
 *          （不带 main 的 /revision/search 无需 xm-sign，2025-11 实测可用）
 *          列表 data.result.response.docs[]：title/id/cover_path/nickname/intro/is_paid
 *          is_paid=true 的付费专辑直接跳过（免费曲库只听免费的）。
 *   章节   GET https://mobile.ximalaya.com/mobile/others/ca/album/track/<albumId>/true/<page>/30
 *          列表 tracks.list[]：trackId/title/duration/playUrl64
 *   播放   优先章节自带的 playUrl64，没有再按集号回查一次接口。
 */
public class XimalayaSource implements Source {

    private static final int PAGE_SIZE = 30;
    private static final String UA_PC =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String UA_M =
        "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/94.0.4606.71 Mobile Safari/537.36";

    @Override public String id()   { return "fengxi_ximalaya"; }
    @Override public String name() { return "喜马拉雅免费"; }
    @Override public String desc() { return "喜马拉雅免费专辑（主站公开接口，付费内容自动跳过，自用）。"; }
    @Override public boolean canSearch() { return true; }

    // ---------------- 搜索 ----------------

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        if (kw == null || kw.trim().length() == 0) return new ArrayList<Book>();
        int p = page < 1 ? 1 : page;

        String resp = httpGet("https://www.ximalaya.com/revision/search?core=album"
                + "&kw=" + URLEncoder.encode(kw.trim(), "UTF-8")
                + "&page=" + p + "&rows=20&spellchecker=true",
                UA_PC, "https://www.ximalaya.com/search/");
        if (resp == null) throw new Exception("喜马拉雅搜索接口无响应");
        if (resp.contains("\"needLogin\":true") || resp.contains("\"ret\":303")) {
            throw new Exception("喜马拉雅搜索触发风控，请稍后再试");
        }

        List<Book> out = new ArrayList<Book>();
        JSONObject o = new JSONObject(resp);
        JSONObject data = o.optJSONObject("data");
        JSONObject result = data == null ? null : data.optJSONObject("result");
        JSONObject respObj = result == null ? null : result.optJSONObject("response");
        JSONArray docs = respObj == null ? null : respObj.optJSONArray("docs");
        if (docs == null) return out;

        for (int i = 0; i < docs.length() && out.size() < 20; i++) {
            JSONObject v = docs.optJSONObject(i);
            if (v == null) continue;
            if (v.optBoolean("is_paid", false)) continue;   // 付费专辑跳过
            String title = v.optString("title", "");
            long aid = v.optLong("id", 0);
            if (title.length() == 0 || aid <= 0) continue;
            Book b = new Book();
            b.source = id();
            b.bookId = String.valueOf(aid);
            b.url = b.bookId;
            b.title = title;
            b.artist = v.optString("nickname", "");
            String cover = v.optString("cover_path", "");
            if (cover.startsWith("//")) cover = "https:" + cover;
            b.cover = cover;
            String intro = v.optString("intro", "").replaceAll("<[^>]+>", "");
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
        int total = Integer.MAX_VALUE;
        for (int p = 1; p <= 100 && chapters.size() < total; p++) {
            String resp = httpGet("https://mobile.ximalaya.com/mobile/others/ca/album/track/"
                    + albumId + "/true/" + p + "/" + PAGE_SIZE, UA_M, null);
            if (resp == null) break;
            JSONObject o = new JSONObject(resp);
            JSONObject tracks = o.optJSONObject("tracks");
            if (tracks == null) break;
            if (total == Integer.MAX_VALUE) total = tracks.optInt("totalCount", 0);
            JSONArray list = tracks.optJSONArray("list");
            if (list == null || list.length() == 0) break;
            for (int i = 0; i < list.length(); i++) {
                JSONObject v = list.optJSONObject(i);
                if (v == null) continue;
                long tid = v.optLong("trackId", 0);
                String title = v.optString("title", "");
                if (tid <= 0) continue;
                if (title.length() == 0) title = "第" + (chapters.size() + 1) + "集";
                Chapter c = new Chapter(chapters.size() + 1, title, String.valueOf(tid));
                String play = v.optString("playUrl64", "");
                if (play.length() == 0) play = v.optString("playUrl32", "");
                c.directUrl = play;
                chapters.add(c);
            }
        }
        if (chapters.isEmpty()) throw new Exception("没有拿到章节数据（专辑可能整本付费）");

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
        if (book == null || book.bookId == null || ch.index <= 0) return ch.url;

        // 章节缺直链（罕见）：按集号回查所在页
        int page = ((ch.index - 1) / PAGE_SIZE) + 1;
        String resp = httpGet("https://mobile.ximalaya.com/mobile/others/ca/album/track/"
                + book.bookId + "/true/" + page + "/" + PAGE_SIZE, UA_M, null);
        if (resp == null) throw new Exception("喜马拉雅取播放地址无响应");
        JSONObject o = new JSONObject(resp);
        JSONObject tracks = o.optJSONObject("tracks");
        JSONArray list = tracks == null ? null : tracks.optJSONArray("list");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject v = list.optJSONObject(i);
                if (v == null) continue;
                if (v.optLong("trackId", 0) == parseLongSafe(ch.url)) {
                    String play = v.optString("playUrl64", "");
                    if (play.length() > 0) return play;
                }
            }
        }
        throw new Exception("这一集没有免费音频（可能属于付费内容）");
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s == null ? "" : s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    @Override public Map<String, String> audioHeaders(String audioUrl) { return null; }
    @Override public Map<String, String> coverHeaders(String coverUrl) { return null; }

    // ==================== HTTP ====================

    private static String httpGet(String url, String ua, String referer) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", ua == null ? UA_M : ua);
            c.setRequestProperty("Accept", "application/json,*/*");
            if (referer != null) c.setRequestProperty("Referer", referer);
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
