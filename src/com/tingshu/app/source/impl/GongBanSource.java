package com.tingshu.app.source.impl;

import com.tingshu.app.core.RssParser;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.AbsSource;
import com.tingshu.app.source.BrowsableSource;
import com.tingshu.app.source.Category;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * ★ v4.11.0：公版书源 —— 版权已进入公有领域的经典著作朗读版。
 *
 * <h3>内容与合规</h3>
 * 内置专辑全部是**作者本人公开、免费分发**的原文朗读（白云出岫系列，
 * 在喜马拉雅的官方 RSS 订阅地址），音频走喜马拉雅公开 CDN。
 * 原著均为公版（四大名著、史记、汉书、古诗等），App 只做订阅与播放，
 * 不存储任何音频。想扩充书库：在「播客电台 → 管理订阅」里添加任意
 * 公版书专辑 / 播客 RSS 即可。
 *
 * <h3>专辑 RSS</h3>
 * 喜马拉雅为开放订阅的专辑提供 {@code https://www.ximalaya.com/album/<id>.xml}
 * 公开 RSS，条目带 enclosure 音频直链与 itunes:episode 集数 ——
 * 解析复用 {@link RssParser}，回目按 episode 升序排列（第 1 回在最前）。
 *
 * <p>清单为 2026-10 实测 RSS 可用（部分老专辑官方未开放 RSS，未收录）。
 */
public final class GongBanSource extends AbsSource implements BrowsableSource {

    public static final String ID = "gongban";

    /** 分类 id */
    public static final String CAT_CLASSIC = "classic";   // 四大名著与古典小说
    public static final String CAT_HISTORY = "history";   // 史书原文
    public static final String CAT_POEMS = "poems";       // 诗词

    /** {分类id, 专辑id} —— 名称以 RSS 实际标题为准，这里只做分组 */
    private static final String[][] ALBUMS = {
            {CAT_CLASSIC, "220565"},   // 红楼梦原文朗读（597 集）
            {CAT_CLASSIC, "268522"},   // 三国演义-原文朗读（248 集）
            {CAT_CLASSIC, "2798291"},  // 水浒传-原文朗读（214 集）
            {CAT_CLASSIC, "220566"},   // 西游记-原文朗读（196 集）
            {CAT_HISTORY, "220563"},   // 史记·原文朗读（142 集）
            {CAT_HISTORY, "220568"},   // 汉书·原文朗读（308 集）
            {CAT_POEMS, "224846"},     // 小学生必背古诗词（614 集）
    };

    @Override public String id() { return ID; }
    @Override public String name() { return "公版书"; }
    @Override public String desc() {
        return "公版经典原文朗读：四大名著、史记、汉书、古诗词。"
                + "内容为公版著作的公开免费朗读版（官方 RSS 订阅），持续扩充。";
    }

    // ==================== 分类 ====================

    @Override public boolean canBrowse() { return true; }

    @Override
    public List<Category> categories() {
        List<Category> l = new ArrayList<Category>();
        l.add(new Category(CAT_CLASSIC, "四大名著"));
        l.add(new Category(CAT_HISTORY, "史书原文"));
        l.add(new Category(CAT_POEMS, "诗词"));
        return l;
    }

    // ==================== 浏览 ====================

    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        if (page > 1) return new ArrayList<Book>();
        List<Book> out = new ArrayList<Book>();
        for (String[] a : ALBUMS) {
            if (catId == null || catId.equals(a[0])) {
                out.add(albumBook(a[1]));
            }
        }
        return out;
    }

    private Book albumBook(String albumId) {
        String url = "https://www.ximalaya.com/album/" + albumId + ".xml";
        Book b = new Book(ID, url, "专辑 " + albumId);   // 标题在 detail 时以 RSS 为准
        b.url = url;
        b.author = "白云出岫";
        b.category = "公版书";
        b.intro = "公版原著原文朗读 · 免费公开版本";
        return b;
    }

    // ==================== 搜索（本地目录匹配） ====================

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        List<Book> out = new ArrayList<Book>();
        if (kw == null || (kw = kw.trim()).length() == 0 || page > 1) return out;
        // 目录是固定的少量专辑，直接逐个拉 RSS 拿真名再匹配（7 个请求，可接受）
        for (String[] a : ALBUMS) {
            Book b = albumBook(a[1]);
            try {
                RssParser.Feed f = RssParser.fetch(b.url);
                if (f.title.length() > 0) b.title = f.title;
                if (f.cover.length() > 0) b.cover = f.cover;
            } catch (Throwable ignored) {}
            if (b.title.toLowerCase().contains(kw.toLowerCase())
                    || (b.intro != null && b.intro.contains(kw))) {
                out.add(b);
            }
        }
        return out;
    }

    // ==================== 详情 / 播放 ====================

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        RssParser.Feed f = RssParser.fetch(bookUrl);
        Book b = new Book(ID, bookUrl,
                f.title.length() > 0 ? f.title : "公版书专辑");
        b.url = bookUrl;
        b.cover = f.cover;
        b.author = "白云出岫";
        b.intro = f.desc.length() > 0 ? f.desc : "公版原著原文朗读 · 免费公开版本";
        b.category = "公版书";
        BookDetail d = new BookDetail(b);

        // 拷贝条目并按集数升序（第 1 回在前）
        List<RssParser.Item> items = new ArrayList<RssParser.Item>(f.items);
        boolean anyEp = false;
        for (RssParser.Item it : items) if (it.episode > 0) { anyEp = true; break; }
        if (anyEp) {
            Collections.sort(items, new Comparator<RssParser.Item>() {
                public int compare(RssParser.Item x, RssParser.Item y) {
                    int a = x.episode > 0 ? x.episode : Integer.MAX_VALUE;
                    int c = y.episode > 0 ? y.episode : Integer.MAX_VALUE;
                    return a - c;
                }
            });
        }

        int idx = 1;
        for (RssParser.Item it : items) {
            if (it.audio == null || it.audio.length() == 0) continue;
            Chapter c = new Chapter(idx++, chapterTitle(it), it.audio);
            c.directUrl = it.audio;
            d.chapters.add(c);
        }
        d.total = d.chapters.size();
        if (d.chapters.isEmpty()) {
            d.error = "该专辑暂未开放 RSS 订阅或没有音频";
        }
        return d;
    }

    private String chapterTitle(RssParser.Item it) {
        String t = it.title;
        if (t.length() == 0) t = "第" + it.episode + "集";
        if (it.duration != null && it.duration.length() > 0) t += " · " + it.duration;
        return t;
    }

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        return ch == null ? null : (ch.url == null || ch.url.length() == 0 ? ch.directUrl : ch.url);
    }
}
