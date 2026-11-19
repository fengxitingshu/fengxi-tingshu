package com.tingshu.app.core;

import android.content.Context;
import android.net.Uri;

import com.tingshu.app.data.BackupCred;
import com.tingshu.app.data.DownloadRepo;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.WebDavClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ★ v4.21.48 手动转存 —— 把「已下载」的分集复制到用户挑的本地目录，或上传到 WebDAV。
 *
 * <p>与 v4.14.0 的两条旧链路的区别（别混）：
 * <ul>
 *   <li><b>下载后自动移到外部目录</b>（{@code DownloadManager.moveToSafIfNeeded}）：
 *       下载完成立刻移动，目标是设置里固定的那个目录，用户事后没法挑。</li>
 *   <li><b>选集菜单「转存到 NAS（试用）」</b>（{@code exportBookToWebDav}）：
 *       只能整本书全传，入口在选集弹层，下载管理页里没有。</li>
 *   <li><b>本类</b>：用户在下载管理页勾选任意几集 → 现场挑目标目录（本地），
 *       或直接手动传 NAS。复制而非移动，原下载一份不动；逐文件校验字节数。</li>
 * </ul>
 *
 * <p>源文件两种形态都要认：私有目录的 {@code path}，以及被移到外部目录后的
 * {@code saved_uri}（content://）。后者先落到 cache 里的临时文件，
 * 这样本地 SAF 写出与 WebDAV 上传都能复用同一套「只吃 File」的工具。
 */
public final class DlExport {

    public interface Hook {
        void onProgress(int done, int total, int ok, int skip, int fail);
        void onDone(int ok, int skip, int fail, int total, String target, String error);
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static volatile boolean running = false;

    private DlExport() {}

    public static boolean isRunning() { return running; }

    private static synchronized boolean begin() {
        if (running) return false;
        running = true;
        return true;
    }

    private static void busy(Hook h) {
        if (h != null) h.onDone(0, 0, 0, 0, "", "已有转存任务在进行，请等它结束");
    }

    // ==================== 本地目录（SAF） ====================

    /**
     * 复制选中集到用户现场挑的目录树：{@code <目录>/<书名>/第00001集.m4a}。
     * 目标已存在且字节数一致 → 跳过；复制后校验大小才算成功。
     */
    public static void exportLocal(final Context c, final Uri treeUri, final String key,
                                   final int[] idxs, final String title, final Hook hook) {
        if (!begin()) { busy(hook); return; }
        POOL.execute(new Runnable() {
            public void run() {
                final int total = idxs == null ? 0 : idxs.length;
                int ok = 0, skip = 0, fail = 0;
                String error = null;
                final String dirName = safeTitle(title);
                try {
                    if (total == 0) { error = "没有选中任何分集"; }
                    else {
                        List<DownloadRepo.Episode> all = DownloadRepo.episodes(key);
                        Uri dir = SafOut.ensureDir(c, treeUri, dirName);
                        if (dir == null) {
                            fail = total;
                            error = "无法在所选目录里创建「" + dirName + "」";
                        } else {
                            for (int i = 0; i < total; i++) {
                                DownloadRepo.Episode ep = find(all, idxs[i]);
                                File src = (ep != null && ep.status == DownloadRepo.ST_DONE)
                                        ? sourceFile(c, ep) : null;
                                if (src == null) {
                                    fail++;
                                    if (error == null) error = "第" + idxs[i] + "集：源文件不存在";
                                } else {
                                    String ext = DlTrans.isMp3(src) ? "mp3" : "m4a";
                                    String name = String.format("第%05d集.%s", idxs[i], ext);
                                    String mime = "mp3".equals(ext) ? "audio/mpeg" : "audio/mp4";
                                    Uri dst = SafOut.createFile(c, dir, name, mime);
                                    if (dst == null) {
                                        fail++;
                                        if (error == null) error = "第" + idxs[i] + "集：无法创建目标文件";
                                    } else if (SafOut.sizeOf(c, dst) == src.length()) {
                                        skip++;                    // 已转存过且尺寸一致
                                    } else {
                                        long wrote = SafOut.copyInto(c, src, dst);
                                        if (wrote == src.length()
                                                && SafOut.sizeOf(c, dst) == src.length()) {
                                            ok++;
                                        } else {
                                            fail++;
                                            if (error == null) error = "第" + idxs[i] + "集：复制后校验不一致";
                                        }
                                    }
                                    cleanupTmp(src);
                                }
                                final int d = i + 1, o = ok, s = skip, f = fail;
                                if (hook != null) hook.onProgress(d, total, o, s, f);
                            }
                        }
                    }
                } catch (Throwable t) {
                    error = t.getClass().getSimpleName();
                } finally {
                    running = false;
                    final int o = ok, s = skip, f = fail;
                    final String e = error;
                    if (hook != null) hook.onDone(o, s, f, total, dirName, e);
                }
            }
        });
    }

    // ==================== NAS（WebDAV） ====================

    /**
     * 把选中集上传到 {@code <备份目录>/转存/<书名>/第00001集.m4a}。
     * 复用 {@link DownloadManager#uploadOneToWebDav} 的远端去重与封面补传，
     * 整批只发一次 PROPFIND。
     */
    public static void exportNas(final Context c, final String key, final int[] idxs,
                                 final String title, final Hook hook) {
        if (!begin()) { busy(hook); return; }
        POOL.execute(new Runnable() {
            public void run() {
                final int total = idxs == null ? 0 : idxs.length;
                int ok = 0, skip = 0, fail = 0;
                String error = null;
                final String dirName = safeTitle(title);
                final String target = "NAS/转存/" + dirName;
                try {
                    if (total == 0) { error = "没有选中任何分集"; }
                    else {
                        final Book b = bookOfKey(key, dirName);
                        Map<String, Long> sizes = null;
                        String base = BackupCred.url();
                        if (base != null && base.length() > 0) {
                            String sub = "转存/" + dirName;
                            String dirUrl = WebDavClient.joinUrl(base, BackupCred.dir() + "/" + sub);
                            sizes = DownloadManager.remoteSizes(dirUrl, BackupCred.user(),
                                    BackupCred.pass(), BackupCred.authType());
                        }
                        List<DownloadRepo.Episode> all = DownloadRepo.episodes(key);
                        for (int i = 0; i < total; i++) {
                            DownloadRepo.Episode ep = find(all, idxs[i]);
                            File src = (ep != null && ep.status == DownloadRepo.ST_DONE)
                                    ? sourceFile(c, ep) : null;
                            if (src == null) {
                                fail++;
                                if (error == null) error = "第" + idxs[i] + "集：源文件不存在";
                            } else {
                                String e = DownloadManager.uploadOneToWebDav(
                                        b, idxs[i], src, sizes);
                                if (e == null) ok++;
                                else if (DownloadManager.SKIP_EXISTING.equals(e)) skip++;
                                else {
                                    fail++;
                                    if (error == null) error = "第" + idxs[i] + "集：" + e;
                                }
                                cleanupTmp(src);
                            }
                            final int d = i + 1, o = ok, s = skip, f = fail;
                            if (hook != null) hook.onProgress(d, total, o, s, f);
                        }
                    }
                } catch (Throwable t) {
                    error = t.getClass().getSimpleName();
                } finally {
                    running = false;
                    final int o = ok, s = skip, f = fail;
                    final String e = error;
                    if (hook != null) hook.onDone(o, s, f, total, target, e);
                }
            }
        });
    }

    // ==================== 内部工具 ====================

    private static DownloadRepo.Episode find(List<DownloadRepo.Episode> all, int idx) {
        if (all == null) return null;
        for (int i = 0; i < all.size(); i++) {
            DownloadRepo.Episode e = all.get(i);
            if (e != null && e.idx == idx) return e;
        }
        return null;
    }

    /** 私有文件直接用；外部 SAF 文件先落临时副本（调用方用完 cleanupTmp） */
    private static File sourceFile(Context c, DownloadRepo.Episode e) {
        if (e.path != null && e.path.length() > 0) {
            File f = new File(e.path);
            if (f.isFile()) return f;
        }
        if (e.savedUri != null && e.savedUri.length() > 0) {
            File tmp = new File(c.getCacheDir(),
                    "dlex_" + Math.abs(e.savedUri.hashCode()) + "_" + e.idx + ".tmp");
            if (copyUriToFile(c, Uri.parse(e.savedUri), tmp) && tmp.length() > 0) return tmp;
            tmp.delete();
        }
        return null;
    }

    private static void cleanupTmp(File f) {
        if (f != null && f.getName().endsWith(".tmp")) {
            try { f.delete(); } catch (Throwable ignored) {}
        }
    }

    private static boolean copyUriToFile(Context c, Uri src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            if (dst.exists()) dst.delete();
            in = c.getContentResolver().openInputStream(src);
            if (in == null) return false;
            out = new FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            return total > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 目录名安全化：下载管理页显示的「书名」其实是 bookId，可能是整条 URL，
     * 直接拿去当目录名会在 NAS 上建出一串怪目录。与 SafOut.safeName 对齐，
     * 非法字符换下划线、截到 40 字符。
     */
    private static String safeTitle(String t) {
        if (t == null || t.trim().length() == 0) return "未知书名";
        String s = t.trim();
        final String bad = "\\/:*?\"<>|";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            sb.append(bad.indexOf(ch) >= 0 ? '_' : ch);
        }
        s = sb.toString().trim();
        if (s.length() > 40) s = s.substring(0, 40);
        return s.length() == 0 ? "未知书名" : s;
    }

    /** 由 key 反构一个 Book：保证 book.key() 与原 key 一致（封面路径要用） */
    private static Book bookOfKey(String key, String title) {
        String source = "";
        String bookId = key == null ? "" : key;
        if (key != null) {
            int i = key.indexOf(':');
            if (i >= 0) {
                source = key.substring(0, i);
                bookId = key.substring(i + 1);
            }
        }
        return new Book(source, bookId, title);
    }
}
