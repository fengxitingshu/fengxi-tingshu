package com.tingshu.app.source.impl;

import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore;

import com.tingshu.app.core.App;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.source.AbsSource;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ v3.9.0：本地书源 —— 播放手机里的音频文件。
 *
 * <h3>分组规则</h3>
 * 按音频文件所在<b>文件夹</b>分成一本书：文件夹名 = 书名，
 * 文件夹内的音频文件按文件名自然序（001 &lt; 002 &lt; 010）排成集。
 * 这是听书类内容在手机里最常见的存放方式（「书名/第001集.mp3」）。
 *
 * <h3>数据来源</h3>
 * MediaStore.Audio 系统媒体库（不需要用户手动选目录，扫一次全设备）。
 * 需要存储读取权限（API 33+ 是 READ_MEDIA_AUDIO，以下是 READ_EXTERNAL_STORAGE），
 * 由前端在「书源管理 → 自定义源 → 本地书源」发起扫描时申请；
 * 权限被拒时前端会收到 {@code needPermission} 并引导去系统设置。
 *
 * <h3>播放</h3>
 * 章节 url 直接是文件绝对路径，{@link #audioUrl} 包成 {@code file://} 返回，
 * 走播放器既有的本地文件分支（与「已下载」同一通路，无需网络）。
 */
public final class LocalSource extends AbsSource {

    public static final String ID = "local";

    /** 认识的音频扩展名（小写）★ v3.9.3 扩充：ape/mka/oga/m4r/m4p/amr/3gp */
    private static final String[] EXTS = {
            "mp3", "m4a", "m4b", "m4r", "m4p", "flac", "wav", "ogg", "oga",
            "opus", "aac", "wma", "ape", "mka", "amr", "3gp"
    };

    /**
     * ★ v3.9.3：书名前缀分组用的分隔符（bookUrl = 文件夹路径 + 分隔符 + 前缀）。
     * 用控制字符，正常文件路径不可能包含。
     */
    static final char GROUP_SEP = '\u0001';

    /** 文件系统兜底扫描的最大深度与文件数上限（防全盘遍历失控） */
    private static final int WALK_MAX_DEPTH = 8;
    private static final int WALK_MAX_FILES = 20000;
    /** 兜底扫描忽略小于 100KB 的音频（过滤音效/铃声碎片） */
    private static final long WALK_MIN_SIZE = 100L * 1024L;

    /** 扫描结果缓存（文件夹路径 → Book），60 秒 TTL */
    private static final Map<String, Book> CACHE = new LinkedHashMap<String, Book>();
    private static volatile long scanAt = 0L;
    private static final long TTL = 60_000L;
    private static final Object LOCK = new Object();

    /** ★ v4.2.0：扫描结果持久化文件（filesDir 下，重开 App 免重扫） */
    private static final String CACHE_FILE = "local_scan_cache.json";

    private static volatile String lastError = "";

    /**
     * ★ v3.9.1：自定义扫描根目录（用户自选的文件夹）。
     *
     * <p>为空 = 扫全机（v3.9.0 行为）；非空 = 只收这个目录（含子目录）下的音频。
     * 存在 Settings，随 App 进程常驻；改目录后需要 force 重扫一次。
     */
    private static volatile String rootDir = "";

    public LocalSource() {}

    /** 当前自定义目录（"" = 全部） */
    public static String rootDir() {
        String r = rootDir;
        if (r == null || r.length() == 0) {
            try {
                r = com.tingshu.app.data.Settings.sp().getString("local_root_dir", "");
            } catch (Throwable t) {
                r = "";
            }
            rootDir = r == null ? "" : r;
        }
        return rootDir;
    }

    /**
     * 设置自定义目录（"" / null = 全部）。
     *
     * @return 实际生效的值（已去掉尾部斜杠）
     */
    public static String setRootDir(String dir) {
        String v = (dir == null) ? "" : dir.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        rootDir = v;
        try {
            com.tingshu.app.data.Settings.sp().edit().putString("local_root_dir", v).apply();
        } catch (Throwable ignored) {}
        // 目录变了，缓存立即失效（否则刷新列表还是旧目录的书）
        synchronized (LOCK) {
            CACHE.clear();
            FILES.clear();
            scanAt = 0L;
        }
        return v;
    }

    @Override public String id() { return ID; }

    @Override public String name() { return "本地书源"; }

    @Override public String desc() { return "播放手机存储里的音频文件，按文件夹自动分书"; }

    @Override public boolean canSearch() { return true; }

    // ==================== 扫描 ====================

    /** 最近一次扫描的错误原因（权限缺失等），供前端提示 */
    public static String lastError() { return lastError; }

    /** 缓存里的书（未扫描过返回空表） */
    public static List<Book> cached() {
        synchronized (LOCK) {
            return new ArrayList<Book>(CACHE.values());
        }
    }

    /**
     * 扫描系统媒体库里的音频，按文件夹分书。
     *
     * @param force true = 跳过 TTL 强制重扫
     * @return 书列表（空表 + lastError 说明原因）
     */
    public static List<Book> scan(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - scanAt < TTL && !CACHE.isEmpty()) return cached();
        // ★ v4.2.0：进程重启后内存缓存是空的 —— 先尝试从磁盘恢复上一次
        //   扫描结果。恢复成功就直接用（不再强制全盘重扫，「重开 App 还得
        //   重新扫描」的老问题到此为止）；只有用户手动点「扫描本机音频」
        //   （force=true）或缓存文件不存在时才真正重扫。
        if (!force && CACHE.isEmpty()) {
            if (restoreFromDisk()) return cached();
        }
        Context ctx = App.ctx();
        if (ctx == null) { lastError = "no-ctx"; return cached(); }
        if (!hasReadPermission(ctx)) {
            lastError = "no-permission";
            return cached();
        }

        Map<String, List<String>> byFolder = new LinkedHashMap<String, List<String>>();
        Cursor c = null;
        try {
            String[] cols = {
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.TITLE
            };
            // ★ v4.1：不再按 IS_MUSIC / DURATION 过滤 —— 听书文件常被媒体库标成
            //   IS_MUSIC=0，且刚下载/冷门格式的时长可能还没提取（=0），之前的
            //   "IS_MUSIC!=0 OR DURATION>=20000" 会把它们整批漏掉。扩展名合法性
            //   由 isAudio() 把关（铃声/音效碎片由 WALK_MIN_SIZE 与分组规则兜住）。
            c = ctx.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                    null, null,
                    MediaStore.Audio.Media.DATA + " ASC");
            // ★ v3.9.1：自定义目录过滤在 Java 侧做，不在 SQL 里 LIKE ——
            //   路径里可能带 _ / %（LIKE 的通配符），拼 SQL 容易漏/错；
            //   游标本来就逐行遍历，顺手判一下开销可忽略。
            final String root = rootDir();
            final String rootSlash = (root == null || root.length() == 0) ? null : root + "/";
            while (c != null && c.moveToNext()) {
                String path = c.getString(0);
                if (path == null || path.length() == 0) continue;
                if (!isAudio(path)) continue;
                if (rootSlash != null && !path.startsWith(rootSlash)) continue;
                addFile(byFolder, path);
            }
        } catch (Throwable t) {
            lastError = String.valueOf(t.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }

        // ★ v4.1：文件系统兜底扫描改为**始终执行** —— 之前的触发条件是
        //   "设了自定义目录 或 媒体库一无所获"，结果只要媒体库扫到几首歌
        //   （found>0），兜底就不再跑，而听书文件夹（.nomedia / 未索引 /
        //   IS_MUSIC=0 且时长未提取）恰恰是媒体库最容易漏的部分。
        //   addFile 自带去重，MediaStore 与文件遍历的结果可以放心合并。
        {
            try {
                List<String> walked = new ArrayList<String>();
                File base = rootDir().length() > 0
                        ? new File(rootDir())
                        : android.os.Environment.getExternalStorageDirectory();
                if (base != null && base.exists()) walkDir(base, 0, walked);
                for (String p : walked) addFile(byFolder, p);
            } catch (Throwable ignored) {}
        }

        // ★ v3.9.3：文件夹 → 书。平铺在公共目录（Download 等）的书、或一个
        //   文件夹里混着多本书（"书名第1章.mp3"式命名）现在都能正确拆分。
        Map<String, Book> out = new LinkedHashMap<String, Book>();
        synchronized (LOCK) { FILES.clear(); }
        for (Map.Entry<String, List<String>> e : byFolder.entrySet()) {
            groupIntoBooks(e.getKey(), e.getValue(), out);
        }

        synchronized (LOCK) {
            CACHE.clear();
            CACHE.putAll(out);
            scanAt = System.currentTimeMillis();
            lastError = "";
            persistLocked();
        }
        return cached();
    }

    // ==================== ★ v4.2.0 扫描结果持久化 ====================

    private static java.io.File cacheFile() {
        try {
            Context ctx = App.ctx();
            if (ctx == null) return null;
            return new java.io.File(ctx.getFilesDir(), CACHE_FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 扫描成功后把结果写盘（同步写：文件很小，且调用点都在后台线程） */
    private static void persistLocked() {
        java.io.File f = cacheFile();
        if (f == null) return;
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("root", rootDir());
            root.put("scanAt", scanAt);
            org.json.JSONArray books = new org.json.JSONArray();
            for (Map.Entry<String, Book> e : CACHE.entrySet()) {
                org.json.JSONObject b = new org.json.JSONObject();
                b.put("bookId", e.getKey());
                b.put("title", e.getValue().title == null ? "" : e.getValue().title);
                b.put("url", e.getValue().url == null ? "" : e.getValue().url);
                books.put(b);
            }
            root.put("books", books);
            org.json.JSONObject files = new org.json.JSONObject();
            for (Map.Entry<String, List<String>> e : FILES.entrySet()) {
                files.put(e.getKey(), new org.json.JSONArray(e.getValue()));
            }
            root.put("files", files);
            java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(f), "UTF-8");
            w.write(root.toString());
            w.close();
        } catch (Throwable ignored) {}
    }

    /**
     * 从磁盘恢复上次扫描结果（进程重启后 CACHE/FILES 已空）。
     *
     * <p>恢复时校验「保存时的扫描目录」与当前一致 —— 用户中途改过扫描
     * 范围就作废（旧目录的书会误导），触发一次真正的重扫。
     *
     * @return true = 恢复成功且非空
     */
    private static boolean restoreFromDisk() {
        synchronized (LOCK) {
            if (!CACHE.isEmpty()) return true;
            java.io.File f = cacheFile();
            if (f == null || !f.exists()) return false;
            try {
                java.io.Reader r = new java.io.InputStreamReader(
                        new java.io.FileInputStream(f), "UTF-8");
                char[] buf = new char[1024];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                r.close();
                org.json.JSONObject root = new org.json.JSONObject(sb.toString());
                if (!rootDir().equals(root.optString("root", ""))) return false;
                org.json.JSONArray books = root.optJSONArray("books");
                org.json.JSONObject files = root.optJSONObject("files");
                if (books == null || books.length() == 0 || files == null) return false;
                CACHE.clear();
                FILES.clear();
                for (int i = 0; i < books.length(); i++) {
                    org.json.JSONObject b = books.optJSONObject(i);
                    if (b == null) continue;
                    String bookId = b.optString("bookId", "");
                    if (bookId.length() == 0) continue;
                    Book bk = new Book(ID, bookId, b.optString("title", "未命名"));
                    bk.url = b.optString("url", "");
                    bk.artist = "";
                    CACHE.put(bookId, bk);
                }
                java.util.Iterator<String> it = files.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    org.json.JSONArray arr = files.optJSONArray(k);
                    if (arr == null) continue;
                    List<String> fs = new ArrayList<String>();
                    for (int i = 0; i < arr.length(); i++) {
                        String p = arr.optString(i, "");
                        if (p.length() > 0) fs.add(p);
                    }
                    FILES.put(k, fs);
                }
                scanAt = root.optLong("scanAt", System.currentTimeMillis());
                return !CACHE.isEmpty();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /** 往「文件夹 → 文件列表」中间表加一条（保序去重） */
    private static void addFile(Map<String, List<String>> byFolder, String path) {
        File f = new File(path);
        File dir = f.getParentFile();
        if (dir == null) return;
        String dirPath = dir.getAbsolutePath();
        List<String> fl = byFolder.get(dirPath);
        if (fl == null) { fl = new ArrayList<String>(); byFolder.put(dirPath, fl); }
        if (!fl.contains(path)) fl.add(path);
    }

    /**
     * ★ v3.9.3：把一个文件夹里的文件拆成书。
     *
     * <p>规则：逐个文件解析「书名前缀」（"书名第X章/集/回.mp3" 里去掉集数
     * 部分的部分）。拆分仅在文件夹里出现 <b>≥2 个不同前缀</b> 且每个前缀
     * 至少 2 个文件时发生 —— 这时明显是一个文件夹混了多本书；其余情况维持
     * v3.9.0 行为（整个文件夹 = 一本书，文件夹名 = 书名），避免误拆。
     * 没匹配到前缀的零散文件归进以文件夹名命名的"杂项"书（若有）。
     */
    private static void groupIntoBooks(String dirPath, List<String> files, Map<String, Book> out) {
        // 前缀 → 文件列表（LinkedHashMap 保住文件夹名序）
        Map<String, List<String>> groups = new LinkedHashMap<String, List<String>>();
        int named = 0;
        for (String p : files) {
            String pre = bookPrefix(new File(p).getName());
            if (pre != null) named++;
            List<String> gl = groups.get(pre);
            if (gl == null) { gl = new ArrayList<String>(); groups.put(pre, gl); }
            gl.add(p);
        }
        // 统计"够格拆书"的前缀（≥2 个文件）
        int solid = 0;
        for (Map.Entry<String, List<String>> g : groups.entrySet()) {
            if (g.getKey() != null && g.getValue().size() >= 2) solid++;
        }
        boolean split = solid >= 2 && named >= 4;

        if (!split) {
            makeBook(dirPath, dirPath, folderName(new File(dirPath)), files, out);
            return;
        }
        for (Map.Entry<String, List<String>> g : groups.entrySet()) {
            String pre = g.getKey();
            List<String> gl = g.getValue();
            if (pre == null) {
                // 零散文件：前缀组都不收时归进文件夹名书
                makeBook(dirPath, dirPath, folderName(new File(dirPath)) + "（散集）", gl, out);
            } else {
                makeBook(dirPath, dirPath + GROUP_SEP + pre, pre, gl, out);
            }
        }
    }

    /** 组一本书并登记进 out / FILES（bookUrl 可能带前缀分隔符） */
    private static void makeBook(String dirPath, String bookUrl, String title,
                                 List<String> files, Map<String, Book> out) {
        if (files.isEmpty()) return;
        Book b = new Book(ID, bookUrl, title);
        b.url = dirPath;
        b.artist = "";
        out.put(bookUrl, b);
        synchronized (LOCK) {
            FILES.put(bookUrl, new ArrayList<String>(files));
        }
    }

    /**
     * ★ v3.9.3：从文件名解析书名前缀（去掉"第X章/集/回/话/节"及后面的部分）。
     *
     * <p>能认的集数写法：第001章 / 第12集 / 第三回 / Chapter 07 / EP03 /
     * ep_12 / Track 9 / 纯数字 001.mp3。返回 null = 没认出（零散文件）。
     * 中文数字也能解析（第三章 → 3），拆分只看前缀字符串本身。
     */
    private static final java.util.regex.Pattern PREFIX_PAT = java.util.regex.Pattern.compile(
            "^(.*?)[\\s._\\-–—\\[(（【]*"
                    + "(?:第\\s*([0-9０-９]+|[一二两三四五六七八九十百千万零〇]+)\\s*[章集回话节讲]"
                    + "|Chapter\\s*([0-9]+)|E[Pp]\\s*_?([0-9]+)|Track\\s*([0-9]+))"
                    + ".*$");

    private static String bookPrefix(String name) {
        if (name == null) return null;
        String base = baseName(name);
        java.util.regex.Matcher m = PREFIX_PAT.matcher(base);
        if (m.matches()) {
            String pre = m.group(1);
            // 去掉尾部粘连的分隔符
            while (pre.length() > 0 && "[\\s._\\-–—\\[(（【]".indexOf(pre.charAt(pre.length() - 1)) >= 0) {
                pre = pre.substring(0, pre.length() - 1);
            }
            if (pre.length() == 0) return null;   // "第1章.mp3" 没有书名前缀
            return pre;
        }
        // 纯数字开头：001.mp3 → null（前缀为空）；"书名 001.mp3" → 书名
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("^(.+?)[\\s._\\-]+[0-9]{1,4}$").matcher(base);
        if (m2.matches()) return m2.group(1);
        return null;
    }

    /** 文件系统兜底遍历（跳过隐藏目录 / Android 私有目录，带深度与数量上限） */
    private static void walkDir(File dir, int depth, List<String> out) {
        if (depth > WALK_MAX_DEPTH || out.size() >= WALK_MAX_FILES) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (out.size() >= WALK_MAX_FILES) return;
            String n = f.getName();
            if (f.isDirectory()) {
                if (n.startsWith(".") || n.equals("Android") || n.equals("data")
                        || n.equals("obb") || n.equals("teardown")) continue;
                walkDir(f, depth + 1, out);
            } else if (f.isFile() && isAudio(n) && f.length() >= WALK_MIN_SIZE) {
                out.add(f.getAbsolutePath());
            }
        }
    }

    /** 中间表：文件夹 → 文件路径列表（scan 到 detail 之间用） */
    private static final Map<String, List<String>> FILES = new LinkedHashMap<String, List<String>>();

    // ==================== Source 实现 ====================

    @Override
    public List<Book> search(String kw, int page) throws Exception {
        List<Book> all = scan(false);
        if (kw == null || kw.trim().length() == 0) return all;
        String k = kw.trim().toLowerCase();
        List<Book> out = new ArrayList<Book>();
        for (Book b : all) {
            if (b.title != null && b.title.toLowerCase().contains(k)) out.add(b);
        }
        return out;
    }

    @Override
    public BookDetail detail(String bookUrl) throws Exception {
        BookDetail d = new BookDetail();
        d.total = 0;
        // ★ v3.9.3：bookUrl 可能带前缀分组分隔符（文件夹\u0001书名前缀）
        String dirPath = bookUrl;
        String pre = null;
        int ix = bookUrl.indexOf(GROUP_SEP);
        if (ix >= 0) {
            dirPath = bookUrl.substring(0, ix);
            pre = bookUrl.substring(ix + 1);
        }
        List<String> files;
        synchronized (LOCK) {
            List<String> f = FILES.get(bookUrl);
            files = (f == null) ? new ArrayList<String>() : new ArrayList<String>(f);
        }
        if (files.isEmpty()) {
            // 缓存被清过（进程重启）—— 用文件夹路径直接列目录兜底
            File dir = new File(dirPath);
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (!f.isFile() || !isAudio(f.getName())) continue;
                    // 前缀分组书：只收属于这个前缀的文件
                    if (pre != null && !pre.equals(bookPrefix(f.getName()))) continue;
                    files.add(f.getAbsolutePath());
                }
            }
        }
        Collections.sort(files, EP_CMP);
        List<Chapter> chs = new ArrayList<Chapter>();
        int i = 1;
        for (String p : files) {
            Chapter ch = new Chapter();
            ch.title = baseName(new File(p).getName());
            ch.url = p;
            ch.index = i++;
            ch.directUrl = "file://" + p;
            chs.add(ch);
        }
        d.chapters = chs;
        d.total = chs.size();
        return d;
    }

    @Override
    public String audioUrl(Chapter ch, Book book) throws Exception {
        if (ch == null) return null;
        String u = ch.url;
        if (u == null || u.length() == 0) u = ch.directUrl;
        if (u == null || u.length() == 0) return null;
        return u.startsWith("file://") ? u : ("file://" + u);
    }

    // ==================== 工具 ====================

    private static boolean hasReadPermission(Context ctx) {
        try {
            String perm = android.os.Build.VERSION.SDK_INT >= 33
                    ? "android.permission.READ_MEDIA_AUDIO"
                    : "android.permission.READ_EXTERNAL_STORAGE";
            return ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isAudio(String name) {
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = n.substring(dot + 1);
        for (String e : EXTS) if (e.equals(ext)) return true;
        return false;
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String folderName(File dir) {
        String n = dir.getName();
        if (n == null || n.length() == 0 || n.equals("0") || n.equalsIgnoreCase("storage")) {
        // 扫到存储根目录：用上一级名字，再不行用绝对路径
            File p = dir.getParentFile();
            n = (p != null && p.getName() != null && p.getName().length() > 0)
                    ? p.getName() : dir.getAbsolutePath();
        }
        return n;
    }

    /** 文件名自然序：001 &lt; 002 &lt; 010（纯字符串比会把 010 排到 002 前面） */
    private static final Comparator<String> NATURAL = new Comparator<String>() {
        @Override public int compare(String a, String b) {
            int ia = 0, ib = 0;
            while (ia < a.length() && ib < b.length()) {
                char ca = a.charAt(ia), cb = b.charAt(ib);
                if (Character.isDigit(ca) && Character.isDigit(cb)) {
                    int sa = ia, sb = ib;
                    while (ia < a.length() && Character.isDigit(a.charAt(ia))) ia++;
                    while (ib < b.length() && Character.isDigit(b.charAt(ib))) ib++;
                    String na = a.substring(sa, ia).replaceFirst("^0+(?=\\d)", "");
                    String nb = b.substring(sb, ib).replaceFirst("^0+(?=\\d)", "");
                    if (na.length() != nb.length()) return na.length() - nb.length();
                    int cmp = na.compareTo(nb);
                    if (cmp != 0) return cmp;
                } else {
                    if (ca != cb) return ca - cb;
                    ia++; ib++;
                }
            }
            return (a.length() - ia) - (b.length() - ib);
        }
    };

    /**
     * ★ v3.9.3：集数感知排序。两边都解析得出集数（阿拉伯数字或中文数字，
     * "第三章"=3）就按数字比，否则退回自然序 —— 解决"第一章/第十章/第二章"
     * 被自然序排成 一/三/二 的乱序问题。
     */
    private static final Comparator<String> EP_CMP = new Comparator<String>() {
        @Override public int compare(String a, String b) {
            String na = new File(a).getName(), nb = new File(b).getName();
            int ea = epNo(na), eb = epNo(nb);
            if (ea >= 0 && eb >= 0 && ea != eb) return ea - eb;
            return NATURAL.compare(na, nb);
        }
    };

    /**
     * 从文件名解析集数（支持 第001章/第12集/第三回/Chapter 07/EP03/Track 9/
     * 纯数字 001）。解析不出返回 -1。
     */
    static int epNo(String fileName) {
        if (fileName == null) return -1;
        String base = baseName(fileName);
        java.util.regex.Matcher m = PREFIX_PAT.matcher(base);
        if (m.matches()) {
            for (int g = 2; g <= 5; g++) {
                String s = m.group(g);
                if (s == null || s.length() == 0) continue;
                if (Character.isDigit(s.charAt(0))) {
                    try { return Integer.parseInt(s); } catch (Throwable ignored) { return -1; }
                }
                return cnNum(s);
            }
        }
        // 纯数字文件名（001.mp3）
        if (base.matches("[0-9]{1,4}")) {
            try { return Integer.parseInt(base); } catch (Throwable ignored) {}
        }
        // ★ "书名 089.mp3" 式命名：尾巴是纯数字 → 当集数
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("^.+?[\\s._\\-]+([0-9]{1,4})$").matcher(base);
        if (m2.matches()) {
            try { return Integer.parseInt(m2.group(1)); } catch (Throwable ignored) {}
        }
        return -1;
    }

    /** 中文数字 → int（一二两三四五六七八九十百千万零〇，支持到万位） */
    static int cnNum(String s) {
        if (s == null || s.length() == 0) return -1;
        int result = 0, section = 0, digit = 0, any = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = -1, u = 0;
            char c = s.charAt(i);
            switch (c) {
                case '零': case '〇': v = 0; break;
                case '一': v = 1; break;
                case '二': case '两': v = 2; break;
                case '三': v = 3; break;
                case '四': v = 4; break;
                case '五': v = 5; break;
                case '六': v = 6; break;
                case '七': v = 7; break;
                case '八': v = 8; break;
                case '九': v = 9; break;
                case '十': u = 10; break;
                case '百': u = 100; break;
                case '千': u = 1000; break;
                case '万': u = 10000; break;
                default: return -1;   // 不认识的字：整体放弃
            }
            any = 1;
            if (u == 0) { digit = v; continue; }
            if (u == 10000) {
                result += (section + (digit == 0 ? 1 : digit)) * 10000;
                section = 0; digit = 0;
            } else {
                section += (digit == 0 ? 1 : digit) * u;
                digit = 0;
            }
        }
        return any == 1 ? result + section + digit : -1;
    }
}
