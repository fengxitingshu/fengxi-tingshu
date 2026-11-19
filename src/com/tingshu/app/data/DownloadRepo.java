package com.tingshu.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.tingshu.app.data.Models.CacheItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ★ v2.9 下载索引（与「缓存」彻底分家）。
 *
 * <p><b>为什么单独一份，不复用 {@link CacheRepo}</b>：
 * 两者语义完全不同 ——
 * <ul>
 *   <li><b>缓存</b>：播放时自动预读的临时副本。目的是让播放顺畅（命中即播、不必现取链），
 *       用户可以完全不关心它，容量超了自动淘汰最旧的。</li>
 *   <li><b>下载</b>：用户主动点 ⬇ / 勾选 / 全集下载要留下的东西。
 *       既然是用户明确要的，就<b>不能</b>被自动清理掉。</li>
 * </ul>
 * 混在一起会出现「我明明下载过，怎么没了」这种说不清的体验。
 * 分开之后：缓存受 {@code cacheLimitMb} 管、下载永不被自动删，
 * 各自的计数与清理入口也互不影响。
 *
 * <p>表 {@code download(key, chapter_idx, path, size, status, source_url, saved_uri, saved_path, updated_at)}。
 *
 * <p><b>path 与 saved_uri 的关系</b>：
 * 下载永远先落在私有目录（{@code filesDir/dl_audio/...}，这段有断点续传）。
 * 如果用户配了「外部文件夹」，文件随后会被<b>移动</b>过去（复制→校验→删源），
 * 这时 {@code path} 置空、{@code saved_uri} 记下 SAF 的 content:// URI。
 * 所以「本地文件在哪」的判断是：先看 path，path 没了再看 saved_uri。
 */
public final class DownloadRepo {

    /** 与 CacheRepo 对齐的状态值，前端一套图标逻辑通用 */
    public static final int ST_PENDING = 0;
    public static final int ST_DOWNLOADING = 1;
    public static final int ST_DONE = 2;
    public static final int ST_FAIL = 3;

    private DownloadRepo() {}

    /** 下载文件根目录（私有） */
    public static File audioRoot(Context c) {
        File d = new File(c.getFilesDir(), "dl_audio");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 某本书的下载目录 */
    public static File bookDir(Context c, String key) {
        File d = new File(audioRoot(c), safeKey(key));
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 某集的落盘路径 */
    public static File chapterFile(Context c, String key, int idx) {
        return new File(bookDir(c, key), String.format("c%05d.m4a", idx));
    }

    /** 目录名安全化：key 里可能带 : 和 / */
    public static String safeKey(String key) {
        return key == null ? "unknown" : key.replace(':', '_').replace('/', '_');
    }

    // ==================== 读写 ====================

    public static void upsert(CacheItem it) {
        upsert(it, null, null);
    }

    public static void upsert(CacheItem it, String savedUri, String savedPath) {
        SQLiteDatabase db = Db.get().getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("key", it.key);
        v.put("chapter_idx", it.chapterIdx);
        v.put("path", it.path);
        v.put("size", it.size);
        v.put("status", it.status);
        v.put("source_url", it.sourceUrl);
        v.put("saved_uri", savedUri);
        v.put("saved_path", savedPath);
        v.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict("download", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public static void markDone(String key, int idx, String path, long size, String srcUrl) {
        markDone(key, idx, path, size, srcUrl, null, null);
    }

    /**
     * 标记下载完成。
     *
     * @param path      私有目录里的绝对路径；文件已移动到外部时传 null
     * @param savedUri  移动到外部目录后的 SAF URI；没配外部目录则 null
     * @param savedPath 外部目录的可读路径（仅用于 UI 展示）
     */
    public static void markDone(String key, int idx, String path, long size, String srcUrl,
                                String savedUri, String savedPath) {
        CacheItem it = new CacheItem();
        it.key = key;
        it.chapterIdx = idx;
        it.path = path;
        it.size = size;
        it.status = ST_DONE;
        it.sourceUrl = srcUrl;
        upsert(it, savedUri, savedPath);
    }

    public static void markStatus(String key, int idx, int status) {
        SQLiteDatabase db = Db.get().getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("status", status);
        v.put("updated_at", System.currentTimeMillis());
        db.update("download", v, "key=? AND chapter_idx=?",
                new String[]{key, String.valueOf(idx)});
    }

    /** 该集是否已下载完成 */
    public static boolean isDownloaded(String key, int idx) {
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT status FROM download WHERE key=? AND chapter_idx=?",
                    new String[]{key, String.valueOf(idx)});
            return c.moveToFirst() && c.getInt(0) == ST_DONE;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * 该集在本地可播的位置。
     * 优先私有文件（file:// 直接用），否则回落到外部目录的 content:// URI。
     *
     * @return "file:///..." 或 "content://..."；都没有返回 null
     */
    public static String playUrl(String key, int idx) {
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT path, saved_uri, status FROM download WHERE key=? AND chapter_idx=?",
                    new String[]{key, String.valueOf(idx)});
            if (c.moveToFirst()) {
                if (c.getInt(2) != ST_DONE) return null;
                String p = c.getString(0);
                if (p != null && p.length() > 0 && new File(p).exists()) return "file://" + p;
                String u = c.getString(1);
                if (u != null && u.length() > 0) return u;   // content:// 原样返回
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** 该集私有文件的路径（不存在返回 null） */
    public static String localPath(String key, int idx) {
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT path, status FROM download WHERE key=? AND chapter_idx=?",
                    new String[]{key, String.valueOf(idx)});
            if (c.moveToFirst()) {
                String p = c.getString(0);
                int st = c.getInt(1);
                if (st == ST_DONE && p != null && new File(p).exists()) return p;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** 某本书已完成的集数 */
    public static int doneCount(String key) {
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM download WHERE key=? AND status=" + ST_DONE,
                    new String[]{key});
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 一次查出某本书「所有集」的状态，返回 集号 → status（避免 N+1 查询） */
    public static java.util.Map<Integer, Integer> statusMap(String key) {
        java.util.Map<Integer, Integer> m = new java.util.HashMap<Integer, Integer>();
        if (key == null || key.length() == 0) return m;
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT chapter_idx, status FROM download WHERE key=?",
                    new String[]{key});
            while (c.moveToNext()) {
                m.put(c.getInt(0), c.getInt(1));
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return m;
    }

    /** 下载占用总字节 */
    public static long totalSize() {
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT SUM(size) FROM download WHERE status=" + ST_DONE, null);
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } catch (Throwable t) {
            return 0L;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 已完成的总集数 */
    public static int totalCount() {
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM download WHERE status=" + ST_DONE, null);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 按书聚合的下载清单（下载管理页用） */
    public static List<CacheItem> books() {
        List<CacheItem> out = new ArrayList<CacheItem>();
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT key, COUNT(*) AS n, SUM(size) AS sz, MAX(updated_at) AS t"
                            + " FROM download WHERE status=" + ST_DONE
                            + " GROUP BY key ORDER BY t DESC", null);
            while (c.moveToNext()) {
                CacheItem it = new CacheItem();
                it.key = c.getString(0);
                it.chapterIdx = c.getInt(1);   // 这里复用为「集数」
                it.size = c.getLong(2);
                it.updatedAt = c.getLong(3);
                out.add(it);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    // ==================== ★ v4.21.48 分集管理 ====================

    /** 某一集的完整索引信息（分集管理页 + 手动转存用） */
    public static final class Episode {
        public int idx;
        public long size;
        public int status;
        public String path;         // 私有文件绝对路径（移到外部后为 null）
        public String savedUri;     // 外部目录 SAF content:// URI
        public String savedPath;    // 外部可读路径（仅展示）
        public long updatedAt;
    }

    /** 按集号升序列出某本书的全部下载记录（含进行中/失败，便于逐集管理） */
    public static List<Episode> episodes(String key) {
        List<Episode> out = new ArrayList<Episode>();
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT chapter_idx, size, status, path, saved_uri, saved_path, updated_at"
                            + " FROM download WHERE key=? ORDER BY chapter_idx",
                    new String[]{key});
            while (c.moveToNext()) {
                Episode e = new Episode();
                e.idx = c.getInt(0);
                e.size = c.getLong(1);
                e.status = c.getInt(2);
                e.path = c.getString(3);
                e.savedUri = c.getString(4);
                e.savedPath = c.getString(5);
                e.updatedAt = c.getLong(6);
                out.add(e);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    // ==================== 删除 ====================

    /** 删除某本书的全部下载（含私有文件；外部文件不动，属用户自己的文件） */
    public static void deleteBook(Context c, String key) {
        SQLiteDatabase db = Db.get().getWritableDatabase();
        db.delete("download", "key=?", new String[]{key});
        deleteDir(bookDir(c, key));
    }

    /** 清空全部下载 */
    public static void clearAll(Context c) {
        SQLiteDatabase db = Db.get().getWritableDatabase();
        db.delete("download", null, null);
        deleteDir(audioRoot(c));
    }

    /**
     * ★ v4.14.0：列出某本书所有「已完成」的下载（转存 WebDAV 用）。
     * 只回有私有文件的行 —— 被移动到 SAF 外部目录的（path 为空）没法直接
     * 读文件，跳过并在调用侧统计。
     */
    public static List<int[]> listDoneIdx(String key) {
        List<int[]> out = new ArrayList<int[]>();
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT chapter_idx FROM download WHERE key=? AND status=? ORDER BY chapter_idx",
                    new String[]{key, String.valueOf(ST_DONE)});
            while (c.moveToNext()) out.add(new int[]{c.getInt(0)});
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** ★ v4.14.0：删除整本书的下载（含私有目录），返回删除的集数 */
    public static int clearBook(Context c, String key) {
        SQLiteDatabase db = Db.get().getWritableDatabase();
        int n = 0;
        try {
            n = db.delete("download", "key=?", new String[]{key});
        } catch (Throwable ignored) {}
        File dir = bookDir(c, key);
        if (dir.exists()) deleteDir(dir);
        return n;
    }

    /** 删除单集（含私有文件） */
    public static void deleteOne(Context c, String key, int idx) {
        SQLiteDatabase db = Db.get().getWritableDatabase();
        db.delete("download", "key=? AND chapter_idx=?",
                new String[]{key, String.valueOf(idx)});
        File f = chapterFile(c, key, idx);
        if (f.exists()) f.delete();
    }

    /**
     * 未完成 / 失败的记录一律清理（启动时调用，避免残留 part 文件）。
     * ★ 只清 status != DONE 的，已完成的一律保留 —— 这是「下载」的区别所在。
     */
    public static void purgeUnfinished(Context c) {
        try {
            SQLiteDatabase db = Db.get().getWritableDatabase();
            Cursor cur = db.rawQuery(
                    "SELECT key, chapter_idx FROM download WHERE status<>" + ST_DONE, null);
            List<String[]> del = new ArrayList<String[]>();
            while (cur.moveToNext()) {
                del.add(new String[]{cur.getString(0), String.valueOf(cur.getInt(1))});
            }
            cur.close();
            for (String[] d : del) {
                db.delete("download", "key=? AND chapter_idx=?", d);
            }
            db.delete("download", "status<>" + ST_DONE, null);
        } catch (Throwable ignored) {
        }
        purgePartFiles(audioRoot(c));
    }

    private static void purgePartFiles(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) purgePartFiles(f);
            else if (f.getName().endsWith(".part")) f.delete();
        }
    }

    private static void deleteDir(File d) {
        if (d == null || !d.exists()) return;
        File[] fs = d.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        d.delete();
    }

    // ==================== 展示辅助 ====================

    /** 人类可读的大小，如 "58.3 MB" */
    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }
}
