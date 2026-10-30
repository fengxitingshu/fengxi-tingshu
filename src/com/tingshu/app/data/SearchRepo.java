package com.tingshu.app.data;

import android.content.ContentValues;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 搜索历史。
 *
 * 设计要点：
 * - kw 做主键，同一个词重复搜只刷新时间与次数，不产生重复条目。
 * - 只保留最近 {@link #MAX} 条，超出按时间淘汰（在 add 后顺手清理）。
 * - 不做大小写/空格以外的归一化：用户搜什么就存什么，点击重搜要能命中。
 */
public final class SearchRepo {

    private SearchRepo() {}

    /** 最多保留多少条历史 */
    private static final int MAX = 30;

    /** 记一次搜索。kw 为空直接忽略。 */
    public static void add(String kw) {
        if (kw == null) return;
        final String k = kw.trim();
        if (k.length() == 0) return;

        // 先取旧的 hits，加一后写回（REPLACE 会覆盖整行）
        int hits = 1;
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT hits FROM search_hist WHERE kw=?", new String[]{k});
            if (c.moveToFirst()) hits = c.getInt(0) + 1;
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }

        ContentValues v = new ContentValues();
        v.put("kw", k);
        v.put("hits", hits);
        v.put("used_at", System.currentTimeMillis());
        try {
            Db.get().insert("search_hist", v);
        } catch (Throwable ignored) {}
        trim();
    }

    /** 超量后清掉最旧的 */
    private static void trim() {
        try {
            Db.get().exec("DELETE FROM search_hist WHERE kw NOT IN ("
                    + "SELECT kw FROM search_hist ORDER BY used_at DESC LIMIT " + MAX + ")");
        } catch (Throwable ignored) {}
    }

    /** 最近的历史，新的在前 */
    public static JSONArray list() {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = Db.get().rawQuery(
                    "SELECT kw, hits, used_at FROM search_hist "
                            + "ORDER BY used_at DESC LIMIT " + MAX, null);
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("kw", Db.str(c, "kw"));
                o.put("hits", Db.i(c, "hits", 0));
                o.put("t", Db.l(c, "used_at", 0));
                arr.put(o);
            }
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return arr;
    }

    /** 删一条 */
    public static void remove(String kw) {
        if (kw == null) return;
        try {
            Db.get().delete("search_hist", "kw=?", new String[]{kw.trim()});
        } catch (Throwable ignored) {}
    }

    /** 清空 */
    public static void clear() {
        try {
            Db.get().exec("DELETE FROM search_hist");
        } catch (Throwable ignored) {}
    }

    public static int count() {
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT COUNT(*) FROM search_hist", null);
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return 0;
    }
}
