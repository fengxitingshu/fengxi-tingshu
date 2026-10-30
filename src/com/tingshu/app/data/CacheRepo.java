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
 * 离线缓存索引。
 * 表 cache(key, chapter_idx, path, size, status, source_url, updated_at)。
 * 音频文件本身存在 filesDir/cache_audio/{source}/{bookId}/ 下。
 */
public final class CacheRepo {

    public static final int ST_PENDING = 0;
    public static final int ST_DOWNLOADING = 1;
    public static final int ST_DONE = 2;
    public static final int ST_FAIL = 3;

    private CacheRepo() {}

    /** 音频文件根目录 */
    public static File audioRoot(Context c) {
        File d = new File(c.getFilesDir(), "cache_audio");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 某本书的缓存目录 */
    public static File bookDir(Context c, String key) {
        String safe = key == null ? "unknown" : key.replace(':', '_').replace('/', '_');
        File d = new File(audioRoot(c), safe);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 某集的落盘路径 */
    public static File chapterFile(Context c, String key, int idx) {
        String name = String.format("c%05d.m4a", idx);
        return new File(bookDir(c, key), name);
    }

    // ==================== 读写 ====================

    public static void upsert(CacheItem it) {
        SQLiteDatabase db = Db.get().getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("key", it.key);
        v.put("chapter_idx", it.chapterIdx);
        v.put("path", it.path);
        v.put("size", it.size);
        v.put("status", it.status);
        v.put("source_url", it.sourceUrl);
        v.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict("cache", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public static void markDone(String key, int idx, String path, long size, String srcUrl) {
        CacheItem it = new CacheItem();
        it.key = key;
        it.chapterIdx = idx;
        it.path = path;
        it.size = size;
        it.status = ST_DONE;
        it.sourceUrl = srcUrl;
        upsert(it);
    }

    public static void markStatus(String key, int idx, int status) {
        SQLiteDatabase db = Db.get().getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("status", status);
        v.put("updated_at", System.currentTimeMillis());
        db.update("cache", v, "key=? AND chapter_idx=?", new String[]{key, String.valueOf(idx)});
    }

    /** 该集是否已缓存完成 */
    public static boolean isCached(String key, int idx) {
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT status FROM cache WHERE key=? AND chapter_idx=?",
                    new String[]{key, String.valueOf(idx)});
            return c.moveToFirst() && c.getInt(0) == ST_DONE;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 该集本地文件路径（不存在返回 null） */
    public static String cachedPath(String key, int idx) {
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT path, status FROM cache WHERE key=? AND chapter_idx=?",
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
                    "SELECT COUNT(*) FROM cache WHERE key=? AND status=" + ST_DONE,
                    new String[]{key});
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * ★ v2.8：一次查出某本书「所有集」的状态，返回 集号 → status。
     *
     * <p>章节列表要给每一行画缓存图标（未下载/下载中/已下载/失败），
     * 若逐集调 {@link #isCached} 就是 N 次 SQLite 查询（几百集 = 几百次），
     * 列表渲染会被拖慢。这里一次查完，前端拿到 map 后本地查表即可。
     *
     * <p>不在表里的集号 = 从未缓存过（视作「未下载」，前端按 0 处理）。
     */
    public static java.util.Map<Integer, Integer> statusMap(String key) {
        java.util.Map<Integer, Integer> m = new java.util.HashMap<Integer, Integer>();
        if (key == null || key.length() == 0) return m;
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT chapter_idx, status FROM cache WHERE key=?",
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

    /** 缓存占用总字节 */
    public static long totalSize() {
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT SUM(size) FROM cache WHERE status=" + ST_DONE, null);
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } catch (Throwable t) {
            return 0L;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 按书聚合的缓存清单（缓存管理页用） */
    public static List<CacheItem> books() {
        List<CacheItem> out = new ArrayList<CacheItem>();
        Cursor c = null;
        try {
            c = Db.get().getReadableDatabase().rawQuery(
                    "SELECT key, COUNT(*) AS n, SUM(size) AS sz, MAX(updated_at) AS t"
                            + " FROM cache WHERE status=" + ST_DONE
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

    /** 删除某本书的全部缓存（含文件） */
    public static void deleteBook(Context c, String key) {
        SQLiteDatabase db = Db.get().getWritableDatabase();
        db.delete("cache", "key=?", new String[]{key});
        deleteDir(bookDir(c, key));
    }

    /** 清空全部缓存 */
    public static void clearAll(Context c) {
        SQLiteDatabase db = Db.get().getWritableDatabase();
        db.delete("cache", null, null);
        deleteDir(audioRoot(c));
    }

    /** 未完成 / 失败的记录一律清理（启动时调用，避免残留 part 文件） */
    public static void purgeUnfinished(Context c) {
        try {
            SQLiteDatabase db = Db.get().getWritableDatabase();
            Cursor cur = db.rawQuery("SELECT key, chapter_idx FROM cache WHERE status<>" + ST_DONE, null);
            List<String[]> del = new ArrayList<String[]>();
            while (cur.moveToNext()) {
                del.add(new String[]{cur.getString(0), String.valueOf(cur.getInt(1))});
            }
            cur.close();
            for (String[] d : del) {
                db.delete("cache", "key=? AND chapter_idx=?", d);
            }
            db.delete("cache", "status<>" + ST_DONE, null);
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

    /** 按容量上限清理最旧的整本缓存 */
    public static void enforceLimit(Context c, long limitBytes) {
        if (limitBytes <= 0) return;
        long total = totalSize();
        if (total <= limitBytes) return;
        List<CacheItem> list = books();   // 已按 updated_at DESC
        for (int i = list.size() - 1; i >= 0 && total > limitBytes; i--) {
            CacheItem it = list.get(i);
            total -= it.size;
            deleteBook(c, it.key);
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
}
