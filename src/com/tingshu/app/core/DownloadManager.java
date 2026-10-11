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
        final int[] list = new int[want.size()];
        int p = 0;
        for (int v : want) list[p++] = v;

        POOL.execute(new Runnable() {
            public void run() {
                int total = list.length;
                int okCount = 0;
                for (int i = 0; i < total; i++) {
                    int idx = list[i];
                    boolean ok = false;
                    try {
                        Chapter ch = new Chapter();
                        ch.index = idx;
                        ch.title = "第" + idx + "集";
                        ok = downloadOne(book, idx, ch);
                    } catch (Throwable ignored) {
                    }
                    if (ok) okCount++;
                    chapterDone(book, idx, ok);
                    progress(book, okCount, total, idx);
                }
                done(book, okCount, total);
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
                    int total = chs.size();
                    int okCount = 0;
                    for (int i = 0; i < total; i++) {
                        Chapter ch = chs.get(i);
                        int humanIdx = ch.index > 0 ? ch.index : (i + 1);
                        if (DownloadRepo.isDownloaded(book.key(), humanIdx)) {
                            okCount++;
                            progress(book, okCount, total, humanIdx);
                            continue;
                        }
                        boolean ok = downloadOne(book, humanIdx, ch);
                        if (ok) okCount++;
                        chapterDone(book, humanIdx, ok);
                        progress(book, okCount, total, humanIdx);
                    }
                    done(book, okCount, total);
                } catch (Throwable t) {
                    err(book, -1, t.getClass().getSimpleName());
                }
            }
        });
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

            // ★ v2.9：若配了外部文件夹 → 移动过去
            moveToSafIfNeeded(book, humanIdx, dest, audio);
            return true;
        } catch (Throwable t) {
            DownloadRepo.markStatus(book.key(), humanIdx, DownloadRepo.ST_FAIL);
            err(book, humanIdx, t.getClass().getSimpleName());
            return false;
        }
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
