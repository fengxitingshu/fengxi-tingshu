package com.tingshu.app.data;

import android.content.ContentValues;
import android.database.Cursor;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.FavGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏 + 分组。所有方法都是同步的，调用方负责放到后台线程。
 */
public final class FavRepo {

    private FavRepo() {}

    // ==================== 收藏 ====================

    public static boolean isFav(String key) {
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT 1 FROM fav WHERE key=?", new String[]{key});
            return c != null && c.moveToFirst();
        } catch (Throwable t) {
            return false;
        } finally {
            Db.close(c);
        }
    }

    /** 按 key 拿收藏的书（缓存页显示书名用）。找不到返回 null。 */
    public static Book findBook(String key) {
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT * FROM fav WHERE key=?", new String[]{key});
            if (c != null && c.moveToFirst()) return readBook(c);
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return null;
    }

    public static void add(Book b, long groupId) {
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
        v.put("group_id", groupId);
        v.put("added_at", System.currentTimeMillis());
        Db.get().insert("fav", v);
    }

    public static void remove(String key) {
        Db.get().delete("fav", "key=?", new String[]{key});
    }

    /** 返回 true 表示现在已收藏 */
    public static boolean toggle(Book b, long groupId) {
        if (b == null) return false;
        String k = b.key();
        if (isFav(k)) {
            remove(k);
            return false;
        }
        add(b, groupId);
        return true;
    }

    /** 收藏列表。groupId < 0 表示全部 */
    public static List<Book> list(long groupId) {
        List<Book> out = new ArrayList<Book>();
        Cursor c = null;
        try {
            if (groupId < 0) {
                c = Db.get().rawQuery(
                        "SELECT * FROM fav ORDER BY added_at DESC", null);
            } else {
                c = Db.get().rawQuery(
                        "SELECT * FROM fav WHERE group_id=? ORDER BY added_at DESC",
                        new String[]{String.valueOf(groupId)});
            }
            while (c != null && c.moveToNext()) out.add(readBook(c));
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return out;
    }

    /** 最近收藏，首页用 */
    public static List<Book> recent(int limit) {
        List<Book> out = new ArrayList<Book>();
        Cursor c = null;
        try {
            c = Db.get().rawQuery(
                    "SELECT * FROM fav ORDER BY added_at DESC LIMIT ?",
                    new String[]{String.valueOf(limit)});
            while (c != null && c.moveToNext()) out.add(readBook(c));
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return out;
    }

    public static int count() {
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT COUNT(*) FROM fav", null);
            if (c != null && c.moveToFirst()) return c.getInt(0);
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return 0;
    }

    /** 该收藏属于哪个分组 */
    public static long groupOf(String key) {
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT group_id FROM fav WHERE key=?", new String[]{key});
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return 0;
    }

    public static void moveToGroup(String key, long groupId) {
        ContentValues v = new ContentValues();
        v.put("group_id", groupId);
        Db.get().update("fav", v, "key=?", new String[]{key});
    }

    // ==================== 分组 ====================

    public static List<FavGroup> groups() {
        List<FavGroup> out = new ArrayList<FavGroup>();
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT * FROM fav_group ORDER BY sort, id", null);
            while (c != null && c.moveToNext()) {
                out.add(new FavGroup(
                        Db.l(c, "id", 0), Db.str(c, "name"), Db.i(c, "sort", 0)));
            }
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return out;
    }

    /** 返回新分组 id，失败返回 -1 */
    public static long addGroup(String name) {
        if (name == null || name.trim().length() == 0) return -1;
        ContentValues v = new ContentValues();
        v.put("name", name.trim());
        v.put("sort", groups().size());
        return Db.get().insert("fav_group", v);
    }

    public static void renameGroup(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        Db.get().update("fav_group", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** 删除分组，组内收藏回到默认分组（0） */
    public static void deleteGroup(long id) {
        if (id == 0) return;   // 默认分组不允许删
        ContentValues v = new ContentValues();
        v.put("group_id", 0L);
        Db.get().update("fav", v, "group_id=?", new String[]{String.valueOf(id)});
        Db.get().delete("fav_group", "id=?", new String[]{String.valueOf(id)});
    }

    public static int countInGroup(long groupId) {
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT COUNT(*) FROM fav WHERE group_id=?",
                    new String[]{String.valueOf(groupId)});
            if (c != null && c.moveToFirst()) return c.getInt(0);
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return 0;
    }

    // ==================== 工具 ====================

    private static Book readBook(Cursor c) {
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
        return b;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
