package com.tingshu.app.pan;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.AbsSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ★ v3.9.0：网盘接口的书源包装。
 *
 * <p>把 {@link PanResolver} 的解析能力包成标准 {@link com.tingshu.app.source.Source}，
 * 这样网盘文件可以**直接复用既有播放管线**：播放队列、自动下一集、
 * 播放历史、通知栏/小组件、P0 自愈换源，一样都不缺。
 *
 * <h3>关键约定</h3>
 * <ul>
 *   <li>book.url = {@code "share|" + 分享链接}；</li>
 *   <li>chapter.url = {@code pid + "|" + 分享链接 + "|" + fileId + "|" + token}
 *       —— 取链要用的东西全编码在 chapter.url 里，直链每次现取（不落库，
 *       直链有时效）；</li>
 *   <li>{@link #canSearch()} 恒 false —— 网盘不参与聚合搜索，只从
 *       「书源管理 → 自定义源」入口进来。</li>
 * </ul>
 */
public final class PanSource extends AbsSource {

    public static final String PREFIX = "share|";

    private final String pid;

    public PanSource(String pid) { this.pid = pid; }

    @Override public String id() { return pid; }

    @Override public String name() { return PanResolver.nameOf(pid); }

    @Override public String desc() {
        return "网盘接口：" + PanResolver.hostOf(pid) + "（在自定义源里粘贴 Cookie 后可用）";
    }

    @Override public boolean canSearch() { return false; }

    /** 不参与聚合搜索（canSearch=false，聚合层不会调到这里；实现只为满足接口） */
    @Override
    public List<Book> search(String kw, int page) throws Exception {
        return new ArrayList<Book>();
    }

    /** 从 Book.url 里取出分享链接 */
    public static String shareUrlOf(String bookUrl) {
        if (bookUrl == null) return "";
        return bookUrl.startsWith(PREFIX) ? bookUrl.substring(PREFIX.length()) : bookUrl;
    }

    /** 编一个 chapter.url（pid|share|fileId|token） */
    public static String encChapter(String pid, String shareUrl, String fileId, String token) {
        return pid + "|" + shareUrl + "|" + fileId + "|" + (token == null ? "" : token);
    }

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        BookDetail d = new BookDetail();
        d.total = 0;
        String share = shareUrlOf(bookUrl);
        List<PanResolver.PanFile> files = PanResolver.parseShare(pid, share);
        List<Chapter> chs = new ArrayList<Chapter>();
        int i = 1;
        for (PanResolver.PanFile f : files) {
            if (f.dir) continue;               // 只收文件，文件夹已在解析时展平
            Chapter ch = new Chapter();
            ch.title = f.name;
            ch.url = encChapter(pid, share, f.id, f.token);
            ch.index = i++;
            chs.add(ch);
        }
        d.chapters = chs;
        d.total = chs.size();
        return d;
    }

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        if (ch == null || ch.url == null) return null;
        String[] parts = ch.url.split("\\|", -1);
        if (parts.length < 4) throw new Exception("网盘章节信息不完整");
        String p = parts[0];
        String share = parts[1];
        String fileId = parts[2];
        String token = parts[3];
        PanResolver.PanFile f = new PanResolver.PanFile();
        f.id = fileId;
        f.token = token;
        f.dir = false;
        return PanResolver.downloadUrl(p, f, share);
    }

    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        return PanResolver.headers(pid, audioUrl);
    }
}
