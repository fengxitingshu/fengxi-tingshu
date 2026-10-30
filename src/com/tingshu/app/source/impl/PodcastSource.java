package com.tingshu.app.source.impl;

import com.tingshu.app.core.Net;
import com.tingshu.app.core.RssParser;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.data.Settings;
import com.tingshu.app.source.AbsSource;
import com.tingshu.app.source.BrowsableSource;
import com.tingshu.app.source.Category;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ v4.11.0：播客电台源 —— 通用 RSS 播客订阅。
 *
 * <h3>内容定位（合规）</h3>
 * 播客本身就是**公开发布、开放订阅**的音频（RSS 是播客行业的公开分发协议），
 * 本源只是通用订阅器：内置的精选节目全部来自制作方自己公布的 RSS 地址，
 * 用户也可以自行添加任意播客 / 音频 RSS（包括公版书专辑）。
 * 不抓取、不解析任何需要登录或付费的站点。
 *
 * <h3>结构</h3>
 * <ul>
 *   <li>分类「推荐节目」：内置精选（离线清单，点进详情才联网拉 RSS）；</li>
 *   <li>分类「我的订阅」：用户添加的 RSS，存 Settings（JSON）；</li>
 *   <li>分类「管理订阅」：browse 不产生内容，由前端拦截并打开订阅管理面板。</li>
 * </ul>
 *
 * <p>搜索：在「精选清单 + 我的订阅」的**节目名**里做本地匹配（不逐个拉 feed，
 * 保证搜索秒回）；单集搜索不提供 —— 播客按节目订阅收听是主流用法。
 */
public final class PodcastSource extends AbsSource implements BrowsableSource {

    public static final String ID = "podcast";

    /** 精选节目（全部为制作方公开的 RSS 订阅地址，2026-10 实测可用） */
    private static final String[][] RECOMMEND = {
            {"声东击西", "https://feeds.fireside.fm/shengdongjixi/rss",
             "聊科技、商业与世界的变迁 · 声动活泼出品"},
            {"声动早咖啡", "https://feeds.fireside.fm/sheng-espresso/rss",
             "工作日早上的科技商业新闻速递 · 声动活泼出品"},
            {"少数派播客", "https://sspai.typlog.io/feed/audio.xml",
             "聊聊数码、效率与数字生活 · 少数派出品"},
            {"字谈字畅", "https://www.thetype.com/typechat/feed/",
             "全球首家用华语制作的字体排印主题播客 · TypeTalks 出品"},
            {"无人知晓", "https://feed.xyzfm.space/ypn9dydpbxpc",
             "孟岩的投资与人生对谈"},
            {"啤酒事务局", "https://feed.xyzfm.space/hwjd66fryuu3",
             "中文精酿啤酒文化播客"},
            {"天方乐谈", "https://feed.xyzfm.space/p7kjjqrt9qag",
             "顾超的音乐漫谈 · 古典乐与歌剧"},
            {"周末变奏", "https://keychange.xyz/feed/audio.xml",
             "电影与流行文化闲谈"},
            {"大内密谈", "http://rss.lizhi.fm/rss/14275.xml",
             "老牌深夜文化谈话节目"},
    };

    private static final String SP_KEY = "podcast_subs_v1";

    // ==================== 源信息 ====================

    @Override public String id() { return ID; }
    @Override public String name() { return "播客电台"; }
    @Override public String desc() {
        return "播客 RSS 订阅：内置精选节目，支持添加任意播客/音频 RSS。"
                + "内容均为公开分发的节目，App 不提供、不存储任何音频。";
    }

    // ==================== 分类 ====================

    public static final String CAT_REC = "rec";
    public static final String CAT_SUBS = "subs";
    public static final String CAT_MANAGE = "manage";   // 前端拦截 → 打开管理面板

    @Override public boolean canBrowse() { return true; }

    @Override
    public List<Category> categories() {
        List<Category> l = new ArrayList<Category>();
        l.add(new Category(CAT_REC, "推荐节目"));
        l.add(new Category(CAT_SUBS, "我的订阅"));
        l.add(new Category(CAT_MANAGE, "管理订阅"));
        return l;
    }

    // ==================== 浏览 ====================

    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        if (page > 1) return new ArrayList<Book>();
        List<Book> out = new ArrayList<Book>();
        if (CAT_REC.equals(catId)) {
            for (String[] r : RECOMMEND) out.add(feedBook(r[0], r[1], r[2]));
        } else if (CAT_SUBS.equals(catId)) {
            for (JSONObject s : loadSubs()) {
                out.add(feedBook(s.optString("t", "我的订阅"), s.optString("u", ""),
                        "我添加的播客订阅"));
            }
        }
        // CAT_MANAGE：空列表，前端拦截
        return out;
    }

    /** feed → Book（bookUrl = RSS 地址） */
    private Book feedBook(String title, String feedUrl, String intro) {
        Book b = new Book(ID, feedUrl, title);
        b.url = feedUrl;
        b.author = "播客";
        b.intro = intro == null ? "" : intro;
        b.category = "播客";
        return b;
    }

    // ==================== 搜索（本地节目名匹配） ====================

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        List<Book> out = new ArrayList<Book>();
        if (kw == null || (kw = kw.trim()).length() == 0 || page > 1) return out;
        String k = kw.toLowerCase();
        for (String[] r : RECOMMEND) {
            if (r[0].toLowerCase().contains(k) || r[2].toLowerCase().contains(k)) {
                out.add(feedBook(r[0], r[1], r[2]));
            }
        }
        for (JSONObject s : loadSubs()) {
            String t = s.optString("t", "");
            if (t.toLowerCase().contains(k)) out.add(feedBook(t, s.optString("u", ""), "我的订阅"));
        }
        return out;
    }

    // ==================== 详情 / 播放 ====================

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        RssParser.Feed f = RssParser.fetch(bookUrl);
        Book b = new Book(ID, bookUrl,
                f.title.length() > 0 ? f.title : "播客节目");
        b.url = bookUrl;
        b.cover = f.cover;
        b.author = "播客";
        b.intro = f.desc;
        BookDetail d = new BookDetail(b);
        int idx = 1;
        for (RssParser.Item it : f.items) {
            if (it.audio == null || it.audio.length() == 0) continue;
            Chapter c = new Chapter(idx++, chapterTitle(it), it.audio);
            c.directUrl = it.audio;
            d.chapters.add(c);
        }
        d.total = d.chapters.size();
        if (d.chapters.isEmpty()) {
            d.error = "这份订阅里没有找到音频（检查 RSS 地址是否为播客 feed）";
        }
        return d;
    }

    private String chapterTitle(RssParser.Item it) {
        String t = it.title;
        if (t.length() == 0) t = "未命名单集";
        if (it.duration != null && it.duration.length() > 0) t += " · " + it.duration;
        return t;
    }

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        // enclosure 本身就是直链
        return ch == null ? null : (ch.url == null || ch.url.length() == 0 ? ch.directUrl : ch.url);
    }

    // ==================== 订阅存储（Bridge 调用） ====================

    /** 读取全部订阅（JSON：[{u: url, t: title, c: cover}]） */
    public static List<JSONObject> loadSubs() {
        List<JSONObject> out = new ArrayList<JSONObject>();
        try {
            String raw = Settings.sp().getString(SP_KEY, "[]");
            JSONArray a = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && o.optString("u", "").length() > 0) out.add(o);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 添加订阅（url 去重）。成功返回 true；已存在返回 false。 */
    public static boolean addSub(String url, String title, String cover) {
        if (url == null || url.trim().length() == 0) return false;
        url = url.trim();
        // 已有 → 更新标题/封面即可
        for (JSONObject s : loadSubs()) {
            if (url.equals(s.optString("u", ""))) {
                try {
                    if (title != null && title.length() > 0) s.put("t", title);
                    if (cover != null && cover.length() > 0) s.put("c", cover);
                    saveSubs(loadSubs());
                } catch (Throwable ignored) {}
                return false;
            }
        }
        try {
            JSONObject o = new JSONObject();
            o.put("u", url);
            o.put("t", title == null ? "" : title);
            o.put("c", cover == null ? "" : cover);
            List<JSONObject> all = loadSubs();
            all.add(0, o);    // 新订阅排前面
            saveSubs(all);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 删除订阅。返回是否删了东西。 */
    public static boolean delSub(String url) {
        if (url == null || url.trim().length() == 0) return false;
        List<JSONObject> keep = new ArrayList<JSONObject>();
        boolean hit = false;
        for (JSONObject s : loadSubs()) {
            if (url.trim().equals(s.optString("u", ""))) hit = true;
            else keep.add(s);
        }
        if (hit) saveSubs(keep);
        return hit;
    }

    private static void saveSubs(List<JSONObject> subs) {
        try {
            JSONArray a = new JSONArray();
            for (JSONObject o : subs) a.put(o);
            Settings.sp().edit().putString(SP_KEY, a.toString()).apply();
        } catch (Throwable ignored) {}
    }
}
