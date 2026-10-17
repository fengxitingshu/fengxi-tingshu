package com.tingshu.app.data;

import android.content.ContentValues;
import android.database.Cursor;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.History;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放历史 + 每集进度。
 *
 * 设计要点：
 * - history 表一本书一条（key = source:bookId），记录"最后听到哪一集、集内偏移"。
 * - progress 表按 (book_key, chapter_idx) 记录每一集的偏移，支持任意集续播。
 * - 写库频率由调用方控制（每 5 秒 / 暂停 / 切集），不每秒写。
 */
public final class HistoryRepo {

    private HistoryRepo() {}

    // ==================== 历史 ====================

    /**
     * 更新/插入历史。切集或定时上报时调用。
     */
    public static void record(Book b, int chapterIdx, long posMs,
                              String chapUrl, String chapTitle, int chapterCount) {
        if (b == null || b.key().length() < 2) return;
        ContentValues v = new ContentValues();
        v.put("key", b.key());
        v.put("source", nz(b.source));
        v.put("book_id", nz(b.bookId));
        v.put("title", nz(b.title));
        v.put("author", nz(b.author));
        v.put("artist", nz(b.artist));
        v.put("cover", nz(b.cover));
        v.put("url", nz(b.url));
        v.put("intro", nz(b.intro));
        v.put("last_index", chapterIdx);
        v.put("last_pos_ms", posMs);
        v.put("last_chap_url", nz(chapUrl));
        v.put("last_chap_title", nz(chapTitle));
        v.put("chapter_count", chapterCount);
        v.put("updated_at", System.currentTimeMillis());
        Db.get().insert("history", v);

        // 同步写单集进度
        saveProgress(b.key(), chapterIdx, posMs);
    }

    /** 只更新进度，不动 other 字段（每 5 秒调用的高频路径） */
    public static void touch(String bookKey, int chapterIdx, long posMs) {
        if (bookKey == null || bookKey.length() < 2) return;
        ContentValues v = new ContentValues();
        v.put("last_index", chapterIdx);
        v.put("last_pos_ms", posMs);
        v.put("updated_at", System.currentTimeMillis());
        int n = Db.get().update("history", v, "key=?", new String[]{bookKey});
        if (n == 0) return;       // 历史里还没有这本书，等 record() 来建
        saveProgress(bookKey, chapterIdx, posMs);
    }

    public static List<History> list(int limit) {
        List<History> out = new ArrayList<History>();
        Cursor c = null;
        try {
            c = Db.get().rawQuery(
                    "SELECT * FROM history ORDER BY updated_at DESC LIMIT ?",
                    new String[]{String.valueOf(limit)});
            while (c != null && c.moveToNext()) out.add(read(c));
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return out;
    }

    public static History get(String bookKey) {
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT * FROM history WHERE key=?",
                    new String[]{bookKey});
            if (c != null && c.moveToFirst()) return read(c);
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return null;
    }

    /** 最近在听的一本（首页"继续收听"用） */
    public static History latest() {
        List<History> l = list(1);
        return l.isEmpty() ? null : l.get(0);
    }

    public static void remove(String bookKey) {
        Db.get().delete("history", "key=?", new String[]{bookKey});
    }

    public static void clear() {
        Db.get().exec("DELETE FROM history");
        Db.get().exec("DELETE FROM progress");
    }

    public static int count() {
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT COUNT(*) FROM history", null);
            if (c != null && c.moveToFirst()) return c.getInt(0);
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return 0;
    }

    // ==================== 单集进度 ====================

    /** 某本书全局记住的进度（最后一集） */
    public static long posOf(String bookKey, int chapterIdx) {
        Cursor c = null;
        try {
            c = Db.get().rawQuery(
                    "SELECT pos_ms FROM progress WHERE book_key=? AND chapter_idx=?",
                    new String[]{bookKey, String.valueOf(chapterIdx)});
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return 0;
    }

    private static void saveProgress(String bookKey, int chapterIdx, long posMs) {
        ContentValues v = new ContentValues();
        v.put("book_key", bookKey);
        v.put("chapter_idx", chapterIdx);
        v.put("pos_ms", posMs);
        Db.get().insert("progress", v);
    }

    public static void clearProgress(String bookKey) {
        Db.get().delete("progress", "book_key=?", new String[]{bookKey});
    }

    /**
     * ★ v3.9.0：某本书已收听过（有进度记录）的全部集号，1 基升序。
     *
     * <p>progress 表按 (book_key, chapter_idx) 记录每一集的偏移 —— 只要
     * 听过（哪怕只听了几秒）就会落一行。选集页/详情页据此画「已收听」
     * 绿色小对勾。上限 5000 集防呆。
     */
    public static java.util.List<Integer> listenedIdx(String bookKey) {
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        if (bookKey == null || bookKey.length() < 2) return out;
        Cursor c = null;
        try {
            c = Db.get().rawQuery(
                    "SELECT chapter_idx FROM progress WHERE book_key=? ORDER BY chapter_idx",
                    new String[]{bookKey});
            while (c != null && c.moveToNext() && out.size() < 5000) {
                out.add(c.getInt(0));
            }
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return out;
    }

    // ==================== 工具 ====================

    private static History read(Cursor c) {
        History h = new History();
        Book b = new Book();
        b.source = Db.str(c, "source");
        b.bookId = Db.str(c, "book_id");
        b.title = Db.str(c, "title");
        b.author = Db.str(c, "author");
        b.artist = Db.str(c, "artist");
        b.cover = Db.str(c, "cover");
        b.url = Db.str(c, "url");
        b.intro = Db.str(c, "intro");
        b.sources.add(b.source);
        b.variants.add(b);
        h.book = b;
        h.lastIndex = Db.i(c, "last_index", 1);
        h.lastPosMs = Db.l(c, "last_pos_ms", 0);
        h.lastChapUrl = Db.str(c, "last_chap_url");
        h.lastChapTitle = Db.str(c, "last_chap_title");
        h.chapterCount = Db.i(c, "chapter_count", 0);
        h.updatedAt = Db.l(c, "updated_at", 0);
        return h;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
