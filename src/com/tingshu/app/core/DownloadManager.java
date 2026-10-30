package com.tingshu.app.core;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.tingshu.app.data.DownloadRepo;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.data.Settings;
import com.tingshu.app.data.WebDavClient;
import com.tingshu.app.source.Aggregator;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ★ v2.9 下载调度器（与 {@link CacheManager} 彻底分家）。
 *
 * <p><b>跟缓存的区别</b>：
 * <ul>
 *   <li>缓存：播放时自动预读几集，只为播放顺畅；用户不直接触发；受容量上限淘汰。</li>
 *   <li>下载：用户点 ⬇ / 勾选 / 全集下载主动要的；<b>永不自动删除</b>；可另存到外部文件夹。</li>
 * </ul>
 *
 * <p><b>下载流程（先下后移）</b>：
 * <pre>
 *   ① 取链（走网络，跳过本地命中）
 *   ② 下到私有目录 dl_audio/&lt;key&gt;/c00001.m4a   ← 这段有断点续传、.part 原子落盘
 *   ③ 若配了外部文件夹 → 移动过去：
 *        复制 → 校验字节数 → 删源
 *      校验不过就删掉目标、保留源，只提示不失败
 *   ④ 标记 download 表
 * </pre>
 *
 * <p><b>为什么是「复制→校验→删源」而不是系统 move</b>：
 * {@code DocumentsContract.moveDocument} 只在同一 provider 内有效，
 * 跨 provider（私有目录 → 外部存储）多数 ROM 会抛异常。这样实现对磁盘占用的
 * 最终效果与真移动一致（复制完立即删源），且中途失败源文件还在，更安全。
 */
public final class DownloadManager {

    public interface Listener {
        /** 单集下载结束（成功或失败） */
        void onChapterDone(Book book, int idx, boolean ok);
        /** 批量下载进度 */
        void onProgress(Book book, int done, int total, int curIdx);
        /** 批量下载结束 */
        void onDone(Book book, int done, int total);
        /** 出错 */
        void onError(Book book, int idx, String msg);
        /** 移动到外部目录失败（不影响「已下载」状态，仅提示） */
        void onExportFail(Book book, int idx, String msg);
    }

    /** ★ v4.14.0：WebDAV 转存专用单线程池 —— 上传慢（NAS/坚果云），
     *  绝不能占下载线程池，否则一集没传完、后面的集都下不了。 */
    private static final ExecutorService EXPORT_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private static final ExecutorService POOL =
            Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "ts-download");
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final Set<String> QUEUED = Collections.synchronizedSet(new HashSet<String>());
    private static volatile Context CTX;
    private static volatile Listener LST;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private DownloadManager() {}

    public static void init(Context c) {
        CTX = c.getApplicationContext();
    }

    public static void setListener(Listener l) {
        LST = l;
    }

    private static String jobKey(String bookKey, int idx) {
        return bookKey + "#" + idx;
    }

    // ==================== 对外入口 ====================

    /** 下载单集 */
    public static void downloadChapter(final Book book, final int humanIdx) {
        if (book == null || CTX == null || humanIdx <= 0) return;
        final String jk = jobKey(book.key(), humanIdx);
        if (!QUEUED.add(jk)) return;
        POOL.execute(new Runnable() {
            public void run() {
                boolean ok = false;
                try {
                    if (DownloadRepo.isDownloaded(book.key(), humanIdx)) {
                        ok = true;   // 已下载过，视为成功
                    } else {
                        Chapter ch = new Chapter();
                        ch.index = humanIdx;
                        ch.title = "第" + humanIdx + "集";
                        ok = downloadOne(book, humanIdx, ch);
                    }
                } finally {
                    QUEUED.remove(jk);
                }
                chapterDone(book, humanIdx, ok);
            }
        });
    }

    /**
     * 批量下载指定集（勾选多集）。
     *
     * @param idxs 1 基集号数组
     */
    public static void downloadChapters(final Book book, final int[] idxs) {
        if (book == null || CTX == null || idxs == null || idxs.length == 0) return;
        // 先去重 + 过滤已下载的
        java.util.LinkedHashSet<Integer> want = new java.util.LinkedHashSet<Integer>();
        for (int i : idxs) {
            if (i > 0 && !DownloadRepo.isDownloaded(book.key(), i)) want.add(i);
        }
        if (want.isEmpty()) {
            progress(book, 0, 0, 0);
            done(book, 0, 0);
            return;
        }
        // ★ v4.18.0 任务簿注册：下载管理页可实时看到进度并 暂停/继续/取消
        Task task = new Task(book);
        task.total = want.size();
        for (int v : want) task.remaining.add(v);
        TASKS.put(task.key, task);
        POOL.execute(new Runnable() {
            public void run() {
                drainTask(book, task);
            }
        });
    }

    /** 下载整本（先拿章节表，再逐集下） */
    public static void downloadBook(final Book book) {
        if (book == null || CTX == null) return;
        POOL.execute(new Runnable() {
            public void run() {
                try {
                    BookDetail d = Aggregator.detailWithFallback(book);
                    if (d == null || d.chapters == null || d.chapters.isEmpty()) {
                        err(book, -1, "无法获取章节列表");
                        return;
                    }
                    List<Chapter> chs = d.chapters;
                    // ★ v4.18.0 任务簿注册（含"跳过已下载"的前置统计）
                    Task task = new Task(book);
                    task.total = chs.size();
                    int okCount = 0;
                    for (int i = 0; i < chs.size(); i++) {
                        Chapter ch = chs.get(i);
                        int humanIdx = ch.index > 0 ? ch.index : (i + 1);
                        if (DownloadRepo.isDownloaded(book.key(), humanIdx)) {
                            okCount++;
                        } else {
                            task.remaining.add(humanIdx);
                        }
                    }
                    task.done = okCount;
                    if (task.remaining.isEmpty()) {
                        done(book, okCount, task.total);
                        return;
                    }
                    TASKS.put(task.key, task);
                    drainTask(book, task);      // 已在下载线程里，直接跑
                } catch (Throwable t) {
                    err(book, -1, t.getClass().getSimpleName());
                }
            }
        });
    }

    // ==================== ★ v4.18.0 下载任务簿（可视化管理） ====================

    /**
     * 一个批量下载任务（勾选多集 / 整本），供下载管理页实时展示与控制。
     * 单集下载（{@link #downloadChapter}）是瞬时动作，不进任务簿。
     */
    public static final class Task {
        final Book book;
        public final String key;
        public volatile String title;
        /** 已成功集数（含开局就命中"已下载"跳过的） */
        public volatile int done;
        /** 总集数 */
        public volatile int total;
        /** 正在下载的集号（0 = 空闲） */
        public volatile int curIdx;
        public volatile boolean paused;
        public volatile boolean cancelled;
        /** 工作线程是否正在跑 drainTask（暂停退出后置 false，resume 据此决定是否重新入队） */
        volatile boolean running;
        /** 剩余待下的集号（1 基）；暂停/取消后保留，恢复时从断点继续 */
        final java.util.ArrayDeque<Integer> remaining = new java.util.ArrayDeque<Integer>();

        Task(Book b) {
            book = b;
            key = b.key();
            title = b.title == null ? "未知书名" : b.title;
        }
    }

    private static final java.util.Map<String, Task> TASKS =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Task>());

    /**
     * 批量任务执行体：循环消费 {@code task.remaining}，直到 下完 / 暂停 / 取消。
     * 暂停不占线程 —— 线程退出、任务留在簿里；resume 重新入队，从剩余集继续。
     */
    private static void drainTask(final Book book, final Task task) {
        task.running = true;
        while (!task.cancelled && !task.paused && !task.remaining.isEmpty()) {
            int idx = task.remaining.pollFirst();
            task.curIdx = idx;
            boolean ok = false;
            try {
                Chapter ch = new Chapter();
                ch.index = idx;
                ch.title = "第" + idx + "集";
                ok = downloadOne(book, idx, ch);
            } catch (Throwable ignored) {
            }
            if (ok) task.done++;
            chapterDone(book, idx, ok);
            progress(book, task.done, task.total, idx);
        }
        task.curIdx = 0;
        boolean requeue = false;
        synchronized (TASKS) {
            if (task.cancelled || task.remaining.isEmpty()) {
                TASKS.remove(task.key);          // 完成 / 取消 → 出簿
                task.running = false;
            } else if (task.paused) {
                task.running = false;            // 暂停 → 线程让路，任务挂起
            } else {
                requeue = true;                  // 挂起瞬间被 resume 过 → 补一次入队
            }
        }
        if (task.cancelled || task.remaining.isEmpty()) {
            done(book, task.done, task.total);
        }
        if (requeue) {
            POOL.execute(new Runnable() {
                public void run() {
                    drainTask(book, task);
                }
            });
        }
    }

    /** 暂停任务：当前这一集下完就停，剩余集保留（前端显示"已暂停"） */
    public static void pause(String bookKey) {
        Task t = TASKS.get(bookKey);
        if (t != null) t.paused = true;
    }

    /** 恢复任务：从剩余集继续（线程已退出则重新排队） */
    public static void resume(String bookKey) {
        Task t;
        synchronized (TASKS) { t = TASKS.get(bookKey); }
        if (t == null || t.cancelled || t.remaining.isEmpty()) return;
        t.paused = false;
        if (!t.running) {
            POOL.execute(new Runnable() {
                public void run() {
                    drainTask(t.book, t);
                }
            });
        }
        // running=true 时无需入队 —— 循环下一轮检查 paused=false 自然继续
    }

    /** 取消任务：剩余集清空（已下好的集不受影响），任务出簿 */
    public static void cancel(String bookKey) {
        Task t = TASKS.get(bookKey);
        if (t == null) return;
        t.cancelled = true;
        t.paused = false;
    }

    /** 任务清单 JSON（下载管理页轮询用）：[{key,title,done,total,curIdx,paused}] */
    public static String tasksJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        synchronized (TASKS) {
            for (Task t : TASKS.values()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"key\":\"").append(jsEsc(t.key)).append('"');
                sb.append(",\"title\":\"").append(jsEsc(t.title)).append('"');
                sb.append(",\"done\":").append(t.done);
                sb.append(",\"total\":").append(t.total);
                sb.append(",\"curIdx\":").append(t.curIdx);
                sb.append(",\"paused\":").append(t.paused);
                sb.append('}');
            }
        }
        return sb.append(']').toString();
    }

    /** JSON 字符串转义（够用即可：引号/反斜杠/控制符） */
    private static String jsEsc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    // ==================== 核心：下到私有 + 移动到外部 ====================

    private static boolean downloadOne(Book book, int humanIdx, Chapter stub) {
        try {
            DownloadRepo.markStatus(book.key(), humanIdx, DownloadRepo.ST_DOWNLOADING);

            String[] out = new String[2];
            // ★ 注意：这里【不能】走 audioWithFallback 的本地命中分支
            //   （那是给播放用的）。下载要的是网络直链，否则「下载」会变成
            //   把缓存文件原地搬一遍。用 forceNetwork=true 跳过本地检查。
            String audio = Aggregator.audioForDownload(book, humanIdx, out);
            String usedSrc = out[0];
            if (audio == null || audio.length() == 0) {
                DownloadRepo.markStatus(book.key(), humanIdx, DownloadRepo.ST_FAIL);
                err(book, humanIdx, "取链失败");
                return false;
            }

            com.tingshu.app.source.Source src =
                    com.tingshu.app.source.SourceRegistry.byId(usedSrc);
            java.util.Map<String, String> hdr = null;
            String referer = null;
            if (src != null) {
                hdr = src.audioHeaders(audio);
            }
            if (hdr != null && hdr.containsKey("Referer")) referer = hdr.get("Referer");

            File dest = DownloadRepo.chapterFile(CTX, book.key(), humanIdx);

            Net.DlResult r = Net.download(audio, referer, hdr, dest, null, null);
            if (!(r.ok && dest.exists() && dest.length() > 4096)) {
                DownloadRepo.markStatus(book.key(), humanIdx, DownloadRepo.ST_FAIL);
                err(book, humanIdx, r.error == null ? "下载失败" : r.error);
                return false;
            }

            // ★ v4.15.2：下载附封面 —— 该书私有目录里补一张 cover.jpg，
            //   配合外部目录/转存同步出去，文件夹缩略图观感更好。
            ensureCover(book);

            // ★ v4.14.0：转存开关开着 → 先把私有文件传 WebDAV（异步排队，
            //  不阻塞下载队列），传的是私有文件本身，与 SAF 移动互不影响。
            autoExportToWebDav(book, humanIdx, dest);

            // ★ v2.9：若配了外部文件夹 → 移动过去
            moveToSafIfNeeded(book, humanIdx, dest, audio);
            return true;
        } catch (Throwable t) {
            DownloadRepo.markStatus(book.key(), humanIdx, DownloadRepo.ST_FAIL);
            err(book, humanIdx, t.getClass().getSimpleName());
            return false;
        }
    }

    // ==================== ★ v4.15.2 封面随书下载 ====================

    /** 私有目录里的封面文件 */
    private static File coverFileOf(Book book) {
        try {
            return new File(DownloadRepo.bookDir(CTX, book.key()), "cover.jpg");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 保证该书私有目录里有一张 cover.jpg（已有 / 无封面地址 / 下载失败都静默）。
     * 本地下载（SAF 导出）与 WebDAV 转存都从这里取封面。
     */
    private static void ensureCover(Book book) {
        try {
            if (book == null) return;
            String cu = book.cover;
            if (cu == null || cu.length() == 0) return;
            File cf = coverFileOf(book);
            if (cf == null) return;
            if (cf.isFile() && cf.length() > 0) return;
            Net.download(cu, null, null, cf, null, null);
        } catch (Throwable ignored) {}
    }

    // ==================== ★ v4.14.0 WebDAV 转存（试用）★ v4.15.0 功能锁 + 去重 ====================

    private static volatile boolean davWarned = false;   // 同一次会话只弹一次失败提示

    /** 转存进度回调（MainActivity 注册 → callJs 推前端 toast） */
    public interface DavExportHook {
        void onProgress(Book b, int done, int total, int ok);
        void onDone(Book b, int ok, int total, int skip);
    }
    private static volatile DavExportHook davHook;
    public static void setDavExportHook(DavExportHook h) { davHook = h; }

    /** 下载完成后自动转存一集（功能锁关着/开关关着/没配服务器直接跳过） */
    private static void autoExportToWebDav(final Book book, final int humanIdx, final File f) {
        // ★ v4.15.0 功能锁：试用功能可随时远程回收权限
        if (!FeatureGate.allow(FeatureGate.DAV_EXPORT)) return;
        if (!Settings.webdavExportOn()) return;
        EXPORT_POOL.execute(new Runnable() {
            public void run() {
                String msg = uploadOneToWebDav(book, humanIdx, f, null);
                if (msg != null && !msg.equals(SKIP_EXISTING) && !davWarned) {
                    davWarned = true;
                    UI.post(new Runnable() { public void run() {
                        android.widget.Toast.makeText(CTX,
                                "转存失败（试用）：之后可在下载管理重试",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }});
                }
            }
        });
    }

    /** ★ v4.15.0 去重哨兵：远端已有同尺寸文件，跳过不算失败 */
    static final String SKIP_EXISTING = "skip-existing";

    /** ★ v4.15.2 封面已传过一次的转存目录（会话内记账，避免每集重复上传封面） */
    private static final java.util.Set<String> coverPushed =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 转存目录下的远端文件清单：文件名 → 字节数 */
    private static java.util.Map<String, Long> remoteSizes(String dirUrl,
            String user, String pass, String auth) {
        java.util.Map<String, Long> m = new java.util.HashMap<String, Long>();
        try {
            WebDavClient.Result r = WebDavClient.listWithErr(dirUrl, user, pass, "", auth);
            if (r == null || !r.ok) return m;
            for (int i = 0; i < r.files.size(); i++) {
                WebDavClient.DavFile df = r.files.get(i);
                if (df == null || df.dir || df.name == null) continue;
                m.put(df.name, df.size);
            }
        } catch (Throwable ignored) {}
        return m;
    }

    /**
     * 单集上传。成功返回 null；「远端已存在同尺寸文件，跳过」返回 SKIP_EXISTING；
     * 真失败返回原因。
     *
     * <p>★ v4.15.0 去重验证：上传前先 PROPFIND 转存目录拿远端文件清单，
     * 目标文件已存在<b>且字节数与本地一致</b>才跳过 —— 防止重复转存；
     * 尺寸不一致（半截上传/版本不同）则重新上传覆盖。existingSizes 非 null 时
     * 复用调用方已列好的清单（批量转存只发一次 PROPFIND）。
     */
    private static String uploadOneToWebDav(Book book, int humanIdx, File f,
            java.util.Map<String, Long> existingSizes) {
        try {
            if (f == null || !f.isFile()) return "本地文件不存在";
            String base = com.tingshu.app.data.BackupCred.url();
            if (base == null || base.length() == 0) return "未配置服务器";
            String user = com.tingshu.app.data.BackupCred.user();
            String pass = com.tingshu.app.data.BackupCred.pass();
            String auth = com.tingshu.app.data.BackupCred.authType();
            // 目录：<备份目录>/转存/<书名>/第N集.m4a（复用备份凭据，不另设一套）
            String sub = "转存/" + (book.title == null ? "未知书名" : book.title);
            String dirUrl = com.tingshu.app.data.WebDavClient.joinUrl(base,
                    com.tingshu.app.data.BackupCred.dir() + "/" + sub);
            String fileName = String.format("第%05d集.m4a", humanIdx);

            // ---- 去重验证：远端已有同尺寸文件 → 跳过 ----
            java.util.Map<String, Long> sizes = existingSizes;
            if (sizes == null) sizes = remoteSizes(dirUrl, user, pass, auth);
            Long have = sizes.get(fileName);
            if (have != null && have.longValue() == f.length()) return SKIP_EXISTING;

            com.tingshu.app.data.WebDavClient.ensureDir(base, user, pass,
                    com.tingshu.app.data.BackupCred.dir() + "/" + sub, auth);
            String target = com.tingshu.app.data.WebDavClient.joinUrl(dirUrl, fileName);

            // ★ v4.15.2：转存附封面 —— 远端目录里没有 cover.jpg 时补传一张
            //  （每本书只传一次，coverPushed 记账；列表缩略图直接好看）
            if (!sizes.containsKey("cover.jpg") && !coverPushed.contains(dirUrl)) {
                try {
                    ensureCover(book);
                    File cv = coverFileOf(book);
                    if (cv != null && cv.isFile() && cv.length() > 0) {
                        com.tingshu.app.data.WebDavClient.put(
                                com.tingshu.app.data.WebDavClient.joinUrl(dirUrl, "cover.jpg"),
                                user, pass, cv, auth);
                    }
                    coverPushed.add(dirUrl);
                } catch (Throwable ignored) {}
            }

            com.tingshu.app.data.WebDavClient.Result r =
                    com.tingshu.app.data.WebDavClient.put(target, user, pass, f, auth);
            return r.ok ? null : ("HTTP " + r.code + " " + r.error);
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    /**
     * 立即把某本书已下载（且有私有文件）的集全部转存一遍。
     * 由选集菜单「转存到 NAS」触发；回调走 onDavExportProgress → 前端 toast。
     */
    public static void exportBookToWebDav(final Book book) {
        EXPORT_POOL.execute(new Runnable() {
            public void run() {
                // ★ v4.15.0 功能锁：试用功能可随时远程回收权限
                if (!FeatureGate.allow(FeatureGate.DAV_EXPORT)) {
                    UI.post(new Runnable() { public void run() {
                        android.widget.Toast.makeText(CTX,
                                FeatureGate.denyMsg(FeatureGate.DAV_EXPORT),
                                android.widget.Toast.LENGTH_LONG).show();
                    }});
                    return;
                }
                java.util.List<int[]> idxs = DownloadRepo.listDoneIdx(book.key());
                int total = idxs.size(), okN = 0, skip = 0;
                DavExportHook hk = davHook;

                // ★ v4.15.0：整本书只发一次 PROPFIND，构建远端清单供全部集去重
                java.util.Map<String, Long> sizes = null;
                {
                    String base = com.tingshu.app.data.BackupCred.url();
                    if (base != null && base.length() > 0) {
                        String sub = "转存/" + (book.title == null ? "未知书名" : book.title);
                        String dirUrl = com.tingshu.app.data.WebDavClient.joinUrl(base,
                                com.tingshu.app.data.BackupCred.dir() + "/" + sub);
                        sizes = remoteSizes(dirUrl,
                                com.tingshu.app.data.BackupCred.user(),
                                com.tingshu.app.data.BackupCred.pass(),
                                com.tingshu.app.data.BackupCred.authType());
                    }
                }

                for (int i = 0; i < idxs.size(); i++) {
                    int idx = idxs.get(i)[0];
                    File f = DownloadRepo.chapterFile(CTX, book.key(), idx);
                    if (f == null || !f.isFile()) { skip++; continue; }   // 已移去 SAF 的跳过
                    String e = uploadOneToWebDav(book, idx, f, sizes);
                    if (e == null) okN++;
                    else if (SKIP_EXISTING.equals(e)) skip++;   // 远端已有 → 计入跳过
                    if (hk != null) {
                        final int done = i + 1;
                        final int okF = okN;
                        UI.post(new Runnable() { public void run() {
                            DavExportHook h2 = davHook;
                            if (h2 != null) h2.onProgress(book, done, total, okF);
                        }});
                    }
                }
                if (hk != null) {
                    final int fOk = okN, fSkip = skip, fTotal = total;
                    UI.post(new Runnable() { public void run() {
                        DavExportHook h2 = davHook;
                        if (h2 != null) h2.onDone(book, fOk, fTotal, fSkip);
                    }});
                }
            }
        });
    }

    /**
     * 把已下好的私有文件「移动」到用户选的外部目录。
     *
     * <p>三步：复制 → 校验字节数 → 删源。任何一步失败都保留私有文件，
     * 只提示不改变「已下载」状态（用户至少还能在 App 内听）。
     */
    private static void moveToSafIfNeeded(Book book, int humanIdx, File src, String srcUrl) {
        String treeStr = Settings.downloadTreeUri();
        if (treeStr == null || treeStr.length() == 0) {
            // 没配外部目录：留在私有目录即可
            DownloadRepo.markDone(book.key(), humanIdx, src.getAbsolutePath(),
                    src.length(), srcUrl);
            return;
        }

        try {
            Uri tree = Uri.parse(treeStr);
            // 子目录用「书名」（用户看得懂），文件名用「第N集」
            String bookName = SafOut.safeName(book.title == null ? "未知书名" : book.title);
            Uri bookDir = SafOut.ensureDir(CTX, tree, bookName);
            if (bookDir == null) {
                // 目录建不出来（权限失效等）→ 保留私有文件
                DownloadRepo.markDone(book.key(), humanIdx, src.getAbsolutePath(),
                        src.length(), srcUrl);
                exportFail(book, humanIdx, "无法访问外部文件夹，已保留在 App 内");
                return;
            }

            String fileName = String.format("第%05d集.m4a", humanIdx);
            Uri target = SafOut.createFile(CTX, bookDir, fileName, "audio/mp4");
            if (target == null) {
                DownloadRepo.markDone(book.key(), humanIdx, src.getAbsolutePath(),
                        src.length(), srcUrl);
                exportFail(book, humanIdx, "外部文件创建失败，已保留在 App 内");
                return;
            }

            long written = SafOut.copyInto(CTX, src, target);
            long srcLen = src.length();
            long dstLen = SafOut.sizeOf(CTX, target);

            // ★ 校验：写出去的字节数对得上，才敢删源
            boolean ok = (written == srcLen) && (dstLen < 0 || dstLen == srcLen);
            if (!ok) {
                SafOut.delete(CTX, target);      // 删掉残缺的目标
                DownloadRepo.markDone(book.key(), humanIdx, src.getAbsolutePath(),
                        srcLen, srcUrl);
                exportFail(book, humanIdx, "写入外部文件夹不完整，已保留在 App 内");
                return;
            }

            // 校验通过 → 删源，完成「移动」，省掉双份占用
            // ★ v4.15.2：封面跟书走 —— SAF 书目录里没有 cover.jpg 就补一张，
            //   文件管理器里书目录直接带缩略图。
            try {
                if (!SafOut.exists(CTX, bookDir, "cover.jpg")) {
                    File cv = coverFileOf(book);
                    if (cv != null && cv.isFile() && cv.length() > 0) {
                        Uri ct = SafOut.createFile(CTX, bookDir, "cover.jpg", "image/jpeg");
                        if (ct != null) SafOut.copyInto(CTX, cv, ct);
                    }
                }
            } catch (Throwable ignored) {}
            if (src.delete()) {
                DownloadRepo.markDone(book.key(), humanIdx, null, srcLen, srcUrl,
                        target.toString(), bookName + "/" + fileName);
            } else {
                // 删不掉（少见）→ 两份都留着，记录外部那份
                DownloadRepo.markDone(book.key(), humanIdx, src.getAbsolutePath(),
                        srcLen, srcUrl, target.toString(), bookName + "/" + fileName);
            }
        } catch (Throwable t) {
            // 兜底：任何意外都保证私有文件还在、状态仍是已完成
            try {
                DownloadRepo.markDone(book.key(), humanIdx, src.getAbsolutePath(),
                        src.length(), srcUrl);
            } catch (Throwable ignored) {}
            exportFail(book, humanIdx, "移动到外部文件夹失败，已保留在 App 内");
        }
    }

    // ==================== 回调（切主线程） ====================

    private static void chapterDone(final Book b, final int idx, final boolean ok) {
        UI.post(new Runnable() {
            public void run() {
                Listener l = LST;
                if (l != null) l.onChapterDone(b, idx, ok);
            }
        });
    }

    private static void progress(final Book b, final int d, final int t, final int idx) {
        UI.post(new Runnable() {
            public void run() {
                Listener l = LST;
                if (l != null) l.onProgress(b, d, t, idx);
            }
        });
    }

    private static void done(final Book b, final int d, final int t) {
        UI.post(new Runnable() {
            public void run() {
                Listener l = LST;
                if (l != null) l.onDone(b, d, t);
            }
        });
    }

    private static void err(final Book b, final int idx, final String msg) {
        UI.post(new Runnable() {
            public void run() {
                Listener l = LST;
                if (l != null) l.onError(b, idx, msg);
            }
        });
    }

    private static void exportFail(final Book b, final int idx, final String msg) {
        UI.post(new Runnable() {
            public void run() {
                Listener l = LST;
                if (l != null) l.onExportFail(b, idx, msg);
            }
        });
    }
}
