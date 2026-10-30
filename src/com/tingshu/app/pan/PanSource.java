package com.tingshu.app.pan;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.data.Settings;
import com.tingshu.app.source.AbsSource;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
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

    // ==================== 盘内书章节缓存（v4.7.0） ====================

    /**
     * 盘内书的章节无法从网盘「详情接口」重建（那是分享链接的解析逻辑），
     * 所以 panPlay 播放时把整份章节表写进缓存，detail 再读回。
     * 内存一层（快）+ SharedPreferences 落盘（重启后选集/续播仍可用）。
     */
    private static final java.util.Map<String, List<Chapter>> DISK_CH =
            new java.util.HashMap<String, List<Chapter>>();

    /**
     * 盘内书统一 id（v4.7.1）：与 Bridge.panPlay 的 bookId 用同一套规则——
     * "disk_" 前缀 + share 截到最后一个斜杠（同 Bridge 的正则）再消毒非法字符。
     * <p>★ v4.7.0 的 bug：写入端（Bridge）算出来是 {@code disk___disk___书名}，
     * 读取端（detail）只用书名消毒版 {@code 书名}，两个 key 对不上 →
     * 选集/详情永远拿到空列表（「不能选集」的根因之一）。
     */
    public static String diskBookId(String share) {
        String rawId = share == null ? "" : share.replaceAll(".*/", "");
        return "disk_" + rawId.replaceAll("[^0-9A-Za-z\\u4e00-\\u9fa5_-]", "_");
    }

    /** 从 "__disk__|<id>" 里取出消毒后的书 id（与 Bridge.panPlay 的 bookId 一致） */
    public static String diskIdOf(String share) {
        return diskBookId(share);
    }

    /** 播放时注册整份章节表（Bridge.panPlay 调用） */
    public static void putDiskChapters(String bookId, List<Chapter> chs) {
        if (bookId == null || bookId.length() == 0 || chs == null || chs.isEmpty()) return;
        synchronized (DISK_CH) { DISK_CH.put(bookId, chs); }
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (Chapter c : chs) {
                JSONObject o = new JSONObject();
                o.put("t", c.title == null ? "" : c.title);
                o.put("u", c.url == null ? "" : c.url);
                o.put("i", c.index);
                arr.put(o);
            }
            com.tingshu.app.data.Settings.sp().edit()
                    .putString("pan_disk_ch_" + bookId, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    /** detail 读回章节表：先内存，miss 后落盘 */
    public static List<Chapter> getDiskChapters(String bookId) {
        List<Chapter> mem;
        synchronized (DISK_CH) { mem = DISK_CH.get(bookId); }
        if (mem != null) return mem;
        List<Chapter> out = new ArrayList<Chapter>();
        try {
            String raw = com.tingshu.app.data.Settings.sp().getString("pan_disk_ch_" + bookId, "");
            if (raw.length() > 0) {
                org.json.JSONArray arr = new org.json.JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Chapter c = new Chapter();
                    c.title = o.optString("t", "");
                    c.url = o.optString("u", "");
                    c.index = o.optInt("i", i + 1);
                    out.add(c);
                }
            }
        } catch (Throwable ignored) {}
        if (!out.isEmpty()) {
            synchronized (DISK_CH) { DISK_CH.put(bookId, out); }
        }
        return out;
    }

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        BookDetail d = new BookDetail();
        d.total = 0;
        String share = shareUrlOf(bookUrl);
        // ★ v4.7.0：盘内书的章节由 panScanPlay 播放时写入缓存，这里读回——
        //   选集/历史续播/详情页才有章节数据（v4.6.0 之前返回空 → 不能选集）
        if (share.startsWith("__disk__")) {
            List<Chapter> chs = getDiskChapters(diskIdOf(share));
            d.chapters = chs;
            d.total = chs.size();
            if (chs.isEmpty()) d.error = "盘内书章节缓存已失效，请回首页网盘书架重新播放一次";
            return d;
        }
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
        // ★ v4.7.1 修复「播放失败」根因：盘内书 chapter.url =
        //   pid|__disk__|书名|fileId|token —— share 段本身含「|」，
        //   按固定下标取会把「书名」当 fileId、「fileId」当 token，
        //   整体错位一位 → 直链接口必然失败（报「第0集播放失败」）。
        //   改成从尾部取：最后一段 = token，倒数第二段 = fileId，
        //   中间全部拼回 share（兼容不含「|」的分享链接旧格式）。
        String token = parts[parts.length - 1];
        String fileId = parts[parts.length - 2];
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < parts.length - 2; i++) {
            if (sb.length() > 0) sb.append('|');
            sb.append(parts[i]);
        }
        String share = sb.toString();
        PanResolver.PanFile f = new PanResolver.PanFile();
        f.id = fileId;
        f.token = token;
        f.dir = false;
        // ★ v4.5.0：盘内文件（扫描网盘出来的）不走分享直链，走盘内直链
        if (share.startsWith("__disk__")) return PanResolver.diskDownloadUrl(p, f);
        return PanResolver.downloadUrl(p, f, share);
    }

    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        return PanResolver.headers(pid, audioUrl);
    }
}
