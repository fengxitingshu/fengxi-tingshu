package com.tingshu.app.source.impl;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.data.Settings;
import com.tingshu.app.data.WebDavClient;
import com.tingshu.app.source.AbsSource;
import com.tingshu.app.source.BrowsableSource;
import com.tingshu.app.source.Category;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ v4.13.0：个人音频库（WebDAV）。
 *
 * <h3>定位（合规）</h3>
 * 播的是<b>用户自己的音频</b> —— NAS / 家庭存储 / 自建服务（群晖 DSM、威联通 QTS、
 * 极空间、飞牛、Alist/OpenList、坚果云、Nextcloud…）。App 不提供、不存储任何内容，
 * 只是把用户本来就拥有的文件播出来。这是最干净、也最不可替代的一块工具价值。
 *
 * <h3>为什么不造轮子</h3>
 * 协议层直接用 {@link WebDavClient}（v3.8.0 起就在为「备份与恢复」工作）：
 * PROPFIND 列目录、Basic/Digest 认证、自签证书放行、IPv6 地址、中文路径编码，
 * 以及一堆真实踩过的坑（坚果云 410 双斜杠、各家服务端对 PROPFIND 的差异降级兼容）
 * 都已经处理好。这里只做「目录 → 书 → 章节」的映射。
 *
 * <h3>一个目录 = 一本书</h3>
 * <ul>
 *   <li>浏览某个路径 → 下面的<b>子目录各成一本书</b>（书名 = 目录名）；</li>
 *   <li>若这一层本身就有音频文件 → 额外给一本「本层音频」，点了就能整层连播。</li>
 * </ul>
 *
 * <h3>播放链路</h3>
 * {@link #audioHeaders} 返回 Basic 认证头，PlayerCore 检测到 webdav 源会让播放
 * 直接走 {@link com.tingshu.app.core.LocalProxy} —— 认证交给 Java 层，
 * 播放器只连本地 127.0.0.1，自签 HTTPS / IPv6 / 拖进度条（Range）全都照常。
 *
 * <p>已知限制：<b>Digest 认证暂不支持播放</b>（Digest 需要质询交互，静态请求头无效）。
 * 绝大多数 NAS 走 Basic，不受影响；真碰到会给明确提示而不是静默失败。
 */
public final class WebdavSource extends AbsSource implements BrowsableSource {

    public static final String ID = "webdav";

    /** 服务器清单持久化键（JSON 数组） */
    private static final String SP_KEY = "dav_libs_v1";

    /** 分类 id：browse 不产出内容，前端拦截后打开服务器管理面板 */
    public static final String CAT_MANAGE = "@manage";

    /** bookUrl / catId 前缀 */
    private static final String REF = "w|";

    /** 支持的音频扩展名（小写；不定 .mp4 —— 那多半是视频） */
    private static final String[] AUDIO_EXT = {
            ".mp3", ".m4a", ".m4b", ".aac", ".flac", ".wav", ".ogg", ".oga",
            ".opus", ".wma", ".ape", ".mka", ".aiff", ".aif", ".amr", ".3gp", ".m4r"
    };

    // ==================== 源信息 ====================

    @Override public String id() { return ID; }
    @Override public String name() { return "个人音频库"; }

    @Override public String desc() {
        return "播放你自己 NAS / 家庭存储里的音频（WebDAV）。"
                + "App 不提供任何内容，播的全是你自己的文件。";
    }

    // ==================== 分类 ====================

    @Override public boolean canBrowse() { return true; }

    @Override
    public List<Category> categories() {
        List<Category> l = new ArrayList<Category>();
        List<JSONObject> libs = loadLibs();
        for (int i = 0; i < libs.size(); i++) {
            String n = libs.get(i).optString("n", "");
            if (n.length() == 0) n = "NAS";
            // catId 形如 "0|"（竖线后是相对该服务器入口的子路径）
            l.add(new Category(i + "|", n));
        }
        l.add(new Category(CAT_MANAGE, "管理服务器"));
        return l;
    }

    // ==================== 搜索 ====================

    /**
     * 个人音频库不做关键词搜索，返回空列表。
     *
     * <p>要做就得对每台服务器的每个子目录发 PROPFIND 再递归下去，
     * 一次搜索几十上百个请求，代价远高于收益 —— 目录本身就是最好的导航。
     * 搜索框不会因为这里为空而报错，只是这个源不给结果。
     */
    @Override
    public List<Book> search(String kw, int page) throws Exception {
        return new ArrayList<Book>();
    }

    // ==================== 浏览 ====================

    @Override
    public List<Book> browse(String catId, int page) throws Exception {
        List<Book> out = new ArrayList<Book>();
        if (page > 1) return out;
        if (catId == null || CAT_MANAGE.equals(catId)) return out;
        int bar = catId.indexOf('|');
        if (bar < 0) return out;
        int idx = toInt(catId.substring(0, bar), -1);
        String path = catId.substring(bar + 1);
        JSONObject lib = libAt(idx);
        if (lib == null) return out;

        // ★ v4.15.0 功能锁：试用功能可随时远程回收权限
        if (!com.tingshu.app.core.FeatureGate.allow(
                com.tingshu.app.core.FeatureGate.WEBDAV_LIB)) {
            throw new Exception(com.tingshu.app.core.FeatureGate.denyMsg(
                    com.tingshu.app.core.FeatureGate.WEBDAV_LIB));
        }

        // ★ v4.15.0 深层目录导航：非根路径时给一条「返回上级」，
        //   前端识别标题前缀后按目录浏览处理（不开详情）。
        if (path.length() > 0) {
            String parent = parentPath(path);
            out.add(upBook(idx, parent));
        }

        WebDavClient.Result r = listDir(lib, path);
        if (!r.ok) throw new Exception(failMsg(r));

        List<Book> dirs = new ArrayList<Book>();
        int audioCount = 0;
        for (int i = 0; i < r.files.size(); i++) {
            WebDavClient.DavFile f = r.files.get(i);
            if (f == null || f.name == null || f.name.length() == 0) continue;
            if (isJunk(f.name)) continue;        // ★ v4.17.0 .DS_Store/@eaDir 等不当"书"
            if (f.dir) {
                dirs.add(book(idx, joinPath(path, f.name), f.name));
            } else if (isAudio(f.name)) {
                audioCount++;
            }
        }
        sortBooks(dirs);
        out.addAll(dirs);
        // 这一层本身就有散装音频 → 放最前（返回上级之后），点开即可整层连播
        if (audioCount > 0) {
            out.add(path.length() > 0 ? 1 : 0,
                    book(idx, path, "▶ 本层音频（" + audioCount + " 个文件）"));
        }
        return out;
    }

    // ==================== 详情 / 章节 ====================

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        // ★ v4.15.0 功能锁：试用功能可随时远程回收权限
        if (!com.tingshu.app.core.FeatureGate.allow(
                com.tingshu.app.core.FeatureGate.WEBDAV_LIB)) {
            Book blocked = new Book(ID, bookUrl, "个人音频库");
            BookDetail bd = new BookDetail(blocked);
            bd.error = com.tingshu.app.core.FeatureGate.denyMsg(
                    com.tingshu.app.core.FeatureGate.WEBDAV_LIB);
            return bd;
        }
        int idx = -1;
        String path = "";
        if (bookUrl != null && bookUrl.startsWith(REF)) {
            String rest = bookUrl.substring(REF.length());
            int bar = rest.indexOf('|');
            if (bar >= 0) {
                idx = toInt(rest.substring(0, bar), -1);
                path = rest.substring(bar + 1);
            } else {
                idx = toInt(rest, -1);
            }
        }
        JSONObject lib = libAt(idx);
        if (lib == null) {
            BookDetail d = new BookDetail(new Book(ID, bookUrl, "个人音频库"));
            d.error = "找不到这台服务器了（可能已被删除）";
            return d;
        }

        Book b = new Book(ID, bookUrl, bookTitle(path, lib));
        b.url = bookUrl;
        b.author = "个人音频库";
        b.category = lib.optString("n", "NAS");
        b.intro = "来自「" + lib.optString("n", "NAS") + "」的个人音频。\n"
                + "内容为服务器上的原始文件，App 不提供、不存储任何音频。";
        BookDetail d = new BookDetail(b);

        WebDavClient.Result r = listDir(lib, path);
        if (!r.ok) {
            d.error = failMsg(r);
            return d;
        }

        List<WebDavClient.DavFile> audios = new ArrayList<WebDavClient.DavFile>();
        int subDirs = 0;
        for (int i = 0; i < r.files.size(); i++) {
            WebDavClient.DavFile f = r.files.get(i);
            if (f == null || f.name == null || f.name.length() == 0) continue;
            if (isJunk(f.name)) continue;        // ★ v4.17.0 垃圾文件不进章节表
            if (f.dir) { subDirs++; continue; }
            if (isAudio(f.name)) audios.add(f);
        }
        sortFiles(audios);

        // 文件 URL = <base>/<dir>/<文件名>，整体交给 encodeDavUrl 按 RFC3986 逐段编码
        String dirUrl = WebDavClient.joinUrl(lib.optString("u", ""), realPath(lib, path));
        int no = 1;
        for (WebDavClient.DavFile f : audios) {
            String u = WebDavClient.encodeDavUrl(dirUrl + f.name);
            // ★ 章节名保留原始文件名（含扩展名）：播放内核要靠它判断格式，
            //   像 WMA 这种转存后 URL 可能不带扩展名的场景，章节名是第二道保险。
            Chapter c = new Chapter(no++, f.name, u);
            c.directUrl = u;
            d.chapters.add(c);
        }
        d.total = d.chapters.size();
        if (d.chapters.isEmpty()) {
            // ★ v4.17.0：区分「纯空目录」和「只有子目录」—— 后者是用户
            //   下钻到了中间层，提示要告诉他往哪走，而不是甩一句没有音频。
            d.error = subDirs > 0
                    ? "这个目录里没有音频文件，但有 " + subDirs + " 个子目录。"
                      + "请返回上一层，直接点进子目录收听"
                    : "这个目录里没有找到音频文件"
                      + "（支持 mp3 / m4a / flac / wav / wma 等常见格式）";
        }
        return d;
    }

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        if (ch == null) return null;
        return ch.url != null && ch.url.length() > 0 ? ch.url : ch.directUrl;
    }

    /**
     * ★ 播放认证：返回 Basic 请求头，交给 LocalProxy 带上去取流。
     *
     * <p>Digest 需要「401 质询 → 重算 → 重试」的交互，静态头无效，只能明确告知。
     */
    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        JSONObject lib = libForUrl(audioUrl);
        if (lib == null) return null;
        String user = lib.optString("a", "");
        if (user.length() == 0) return null;    // 匿名访问，无需认证头
        if (WebDavClient.AUTH_DIGEST.equals(lib.optString("t", WebDavClient.AUTH_AUTO))) {
            try {
                com.tingshu.app.core.PlayerLog.log(
                        "个人音频库：该服务器使用 Digest 认证，无法用静态请求头播放，"
                                + "请改用 Basic 认证（或用 http）");
            } catch (Throwable ignored) {}
            return null;
        }
        String h = basicAuth(user, lib.optString("w", ""));
        if (h == null) return null;
        Map<String, String> m = new HashMap<String, String>();
        m.put("Authorization", h);
        return m;
    }

    // ==================== 服务器清单持久化（Bridge 调用） ====================

    /**
     * 读取全部服务器。
     *
     * <p>JSON：{@code [{n:名称, u:地址, p:入口路径, a:账号, w:密码, t:认证方式}]}
     */
    public static List<JSONObject> loadLibs() {
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

    private static void saveLibs(List<JSONObject> libs) {
        try {
            JSONArray a = new JSONArray();
            for (JSONObject o : libs) a.put(o);
            Settings.sp().edit().putString(SP_KEY, a.toString()).apply();
        } catch (Throwable ignored) {}
    }

    /** 新增一台服务器（地址去重 → 覆盖更新）。返回是否新建。 */
    public static boolean addLib(JSONObject lib) {
        if (lib == null) return false;
        String u = lib.optString("u", "");
        if (u.length() == 0) return false;
        for (JSONObject o : loadLibs()) {
            if (u.equals(o.optString("u", ""))) {
                saveLibs(replaceLib(u, lib));
                return false;
            }
        }
        List<JSONObject> all = loadLibs();
        all.add(0, lib);
        saveLibs(all);
        return true;
    }

    /** 更新第 idx 台服务器。返回是否成功。 */
    public static boolean updateLib(int idx, JSONObject lib) {
        List<JSONObject> all = loadLibs();
        if (idx < 0 || idx >= all.size() || lib == null) return false;
        all.set(idx, lib);
        saveLibs(all);
        return true;
    }

    /** 删除第 idx 台服务器。返回是否删了东西。 */
    public static boolean delLib(int idx) {
        List<JSONObject> all = loadLibs();
        if (idx < 0 || idx >= all.size()) return false;
        all.remove(idx);
        saveLibs(all);
        return true;
    }

    private static List<JSONObject> replaceLib(String url, JSONObject lib) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (JSONObject o : loadLibs()) {
            out.add(url.equals(o.optString("u", "")) ? lib : o);
        }
        return out;
    }

    /** 测试连接：能否列出入口路径（入口路径为空时退化成探测服务器本身） */
    public static WebDavClient.Result testLib(String url, String user, String pass,
                                              String path, String auth) {
        WebDavClient.Result r = WebDavClient.listWithErr(
                url, user, pass, path, normAuth(auth));
        if (r.ok) return r;
        // 列不动不代表连不上（有些实现的根目录不可列）→ 再用 smartTest 兜一次
        WebDavClient.Result t = WebDavClient.smartTest(url, user, pass, path, normAuth(auth));
        return t.ok ? t : r;
    }

    public static JSONObject libAt(int idx) {
        List<JSONObject> all = loadLibs();
        return (idx >= 0 && idx < all.size()) ? all.get(idx) : null;
    }

    // ==================== 内部工具 ====================

    /** 服务器入口路径 lib.p 与 browse/detail 里的相对路径相拼 */
    private static String realPath(JSONObject lib, String path) {
        return joinPath(lib.optString("p", ""), path);
    }

    private static WebDavClient.Result listDir(JSONObject lib, String path) {
        return WebDavClient.listWithErr(
                lib.optString("u", ""),
                lib.optString("a", ""),
                lib.optString("w", ""),
                realPath(lib, path),
                normAuth(lib.optString("t", WebDavClient.AUTH_AUTO)));
    }

    private static Book book(int idx, String path, String title) {
        Book b = new Book(ID, REF + idx + "|" + path, title);
        b.url = REF + idx + "|" + path;
        b.author = "个人音频库";
        return b;
    }

    /** ★ v4.15.0：「⬆ 返回上级」条目（前端按目录浏览处理，不开详情） */
    private static Book upBook(int idx, String parentPath) {
        Book b = book(idx, parentPath, "⬆ 返回上级");
        b.author = "个人音频库";
        return b;
    }

    /** ★ v4.15.0：取父路径（根返回空串） */
    private static String parentPath(String path) {
        String s = path == null ? "" : path.replace("\\", "/");
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(0, i) : "";
    }

    private static String failMsg(WebDavClient.Result r) {
        if (r == null) return "连不上服务器";
        if (r.error != null && r.error.length() > 0) return r.error;
        return "连不上服务器（HTTP " + r.code + "）";
    }

    /** 与认证方式反查：哪个服务器拥有这条音频 URL */
    private static JSONObject libForUrl(String audioUrl) {
        if (audioUrl == null) return null;
        JSONObject hit = null;
        for (JSONObject lib : loadLibs()) {
            String base = lib.optString("u", "");
            if (base.length() > 0 && audioUrl.startsWith(base)
                    && (hit == null || base.length() > hit.optString("u", "").length())) {
                hit = lib;
            }
        }
        return hit;
    }

    private static String bookTitle(String path, JSONObject lib) {
        String last = lastSeg(path);
        if (last.length() > 0) return last;
        String p = lib.optString("p", "");
        String lp = lastSeg(p);
        return lp.length() > 0 ? lp : lib.optString("n", "个人音频库");
    }

    private static String lastSeg(String path) {
        String s = path == null ? "" : path.replace("\\", "/");
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** 逻辑路径拼接（不做 URL 编码 —— 编码在 encodeDavUrl 里统一做） */
    private static String joinPath(String a, String b) {
        String s = (a == null ? "" : a) + "/" + (b == null ? "" : b);
        s = s.replace("\\", "/");
        while (s.contains("//")) s = s.replace("//", "/");
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String normAuth(String t) {
        if (WebDavClient.AUTH_BASIC.equals(t)) return WebDavClient.AUTH_BASIC;
        if (WebDavClient.AUTH_DIGEST.equals(t)) return WebDavClient.AUTH_DIGEST;
        return WebDavClient.AUTH_AUTO;
    }

    static boolean isAudio(String name) {
        if (name == null) return false;
        if (isJunk(name)) return false;      // ★ v4.17.0 垃圾文件不当音频
        String n = name.toLowerCase();
        for (String ext : AUDIO_EXT) {
            if (n.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * ★ v4.17.0 边界补强：过滤 NAS 生态的「假文件」。
     *
     * <p>真实 NAS 目录里常见的垃圾，混进书架就是一本《.DS_Store》，
     * 混进章节列表就是一集 403 字节的"音频"（AppleDouble 副本，点了必失败）：
     * <ul>
     *   <li>{@code .DS_Store} / {@code ._xxx} —— macOS Finder 与 AppleDouble 副本，
     *       Windows 里拷过去的 Mac 文件夹几乎必带；</li>
     *   <li>{@code Thumbs.db} / {@code desktop.ini} —— Windows 缩略图/配置；</li>
     *   <li>{@code @eaDir} —— 群晖自动生成的缩略图目录（synology index）；</li>
     *   <li>{@code #recycle} / {@code recycle.bin} —— 回收站目录；</li>
     *   <li>{@code lost+found} —— Linux 磁盘根。</li>
     * </ul>
     * 这些名字有共同特征：都不该被当成"书"或"章节"，直接忽略即可。
     */
    static boolean isJunk(String name) {
        if (name == null || name.length() == 0) return true;
        if (name.charAt(0) == '.') return true;              // .DS_Store / ._xxx / .stfolder 等
        if (name.startsWith("._")) return true;              // 双保险（AppleDouble 一般以 . 开头）
        if (name.startsWith("@")) return true;               // @eaDir / @Recycle 等系统目录
        String l = name.toLowerCase();
        return l.equals("thumbs.db") || l.equals("desktop.ini")
                || l.equals("recycle.bin") || l.equals("#recycle")
                || l.equals("lost+found") || l.equals("system volume information");
    }

    private static void sortFiles(List<WebDavClient.DavFile> list) {
        try {
            Collections.sort(list, new Comparator<WebDavClient.DavFile>() {
                public int compare(WebDavClient.DavFile a, WebDavClient.DavFile b) {
                    return naturalCmp(String.valueOf(a.name), String.valueOf(b.name));
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void sortBooks(List<Book> list) {
        try {
            Collections.sort(list, new Comparator<Book>() {
                public int compare(Book a, Book b) {
                    return naturalCmp(String.valueOf(a.title), String.valueOf(b.title));
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 自然排序：把名字里的数字当数值比，这样「第 2 集」会排在「第 10 集」前面
     * —— 听书目录几乎全是这种命名，字典序会把顺序排成一团糟。
     */
    static int naturalCmp(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (ca >= '0' && ca <= '9' && cb >= '0' && cb <= '9') {
                long na = 0, nb = 0;
                while (i < a.length() && a.charAt(i) >= '0' && a.charAt(i) <= '9') {
                    na = na * 10 + (a.charAt(i) - '0');
                    if (na > 100000000L) { na = 100000000L; }
                    i++;
                }
                while (j < b.length() && b.charAt(j) >= '0' && b.charAt(j) <= '9') {
                    nb = nb * 10 + (b.charAt(j) - '0');
                    if (nb > 100000000L) { nb = 100000000L; }
                    j++;
                }
                if (na != nb) return na < nb ? -1 : 1;
            } else {
                if (ca != cb) return ca < cb ? -1 : 1;
                i++;
                j++;
            }
        }
        return a.length() - b.length();
    }

    private static int toInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); }
        catch (Throwable t) { return def; }
    }

    private static String basicAuth(String user, String pass) {
        if (user == null || user.length() == 0) return null;
        String token = user + ":" + (pass == null ? "" : pass);
        byte[] raw;
        try { raw = token.getBytes("UTF-8"); }
        catch (Throwable t) { raw = token.getBytes(); }
        try {
            return "Basic " + android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            try { return "Basic " + java.util.Base64.getEncoder().encodeToString(raw); }
            catch (Throwable t2) { return null; }
        }
    }
}
