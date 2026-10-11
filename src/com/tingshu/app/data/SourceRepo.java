package com.tingshu.app.data;

import android.database.Cursor;

import java.util.HashMap;
import java.util.Map;

/** 源配置的持久化：启用状态、排序。源本身仍由代码注册。 */
public final class SourceRepo {

    private SourceRepo() {}

    /** 该源是否启用（表里没记录 = 默认启用） */
    public static boolean isEnabled(String sourceId) {
        Cursor c = null;
        try {
            c = Db.get().rawQuery(
                    "SELECT enabled FROM source_conf WHERE source_id=?", new String[]{sourceId});
            if (c != null && c.moveToFirst()) return c.getInt(0) != 0;
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return true;
    }

    public static void setEnabled(String sourceId, boolean enabled) {
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("source_id", sourceId);
        v.put("enabled", enabled ? 1 : 0);
        Cursor c = null;
        try {
            c = Db.get().rawQuery(
                    "SELECT sort FROM source_conf WHERE source_id=?", new String[]{sourceId});
            if (c != null && c.moveToFirst()) v.put("sort", c.getInt(0));
            else v.put("sort", 1000);
        } catch (Throwable ignored) {
            v.put("sort", 1000);
        } finally {
            Db.close(c);
        }
        Db.get().insert("source_conf", v);
    }

    /** 源 id -> 排序值 */
    public static Map<String, Integer> sortMap() {
        Map<String, Integer> m = new HashMap<String, Integer>();
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT source_id, sort FROM source_conf", null);
            while (c != null && c.moveToNext()) {
                m.put(Db.str(c, "source_id"), Db.i(c, "sort", 1000));
            }
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return m;
    }

    /** 保存一批源的顺序（按传入顺序写 sort） */
    public static void saveOrder(java.util.List<String> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put("source_id", orderedIds.get(i));
            v.put("sort", i);
            Cursor c = null;
            try {
                c = Db.get().rawQuery("SELECT enabled FROM source_conf WHERE source_id=?",
                        new String[]{orderedIds.get(i)});
                if (c != null && c.moveToFirst()) v.put("enabled", c.getInt(0));
                else v.put("enabled", 1);
            } catch (Throwable ignored) {
                v.put("enabled", 1);
            } finally {
                Db.close(c);
            }
            Db.get().insert("source_conf", v);
        }
    }

    /**
     * 给一批源排到最后（保序追加），不动已有源的顺序。
     * 导入插件/自定义源时用：新源排在列表尾部，不打乱用户已有的排序。
     */
    public static void saveOrderKeep(java.util.List<String> ids) {
        int base = 0;
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT MAX(sort) FROM source_conf", null);
            if (c != null && c.moveToFirst()) base = Db.i(c, "MAX(sort)", 0);
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        for (int i = 0; i < ids.size(); i++) {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put("source_id", ids.get(i));
            v.put("sort", base + 1 + i);
            Cursor c2 = null;
            try {
                c2 = Db.get().rawQuery("SELECT enabled FROM source_conf WHERE source_id=?",
                        new String[]{ids.get(i)});
                if (c2 != null && c2.moveToFirst()) v.put("enabled", c2.getInt(0));
                else v.put("enabled", 1);
            } catch (Throwable ignored) {
                v.put("enabled", 1);
            } finally {
                Db.close(c2);
            }
            Db.get().insert("source_conf", v);
        }
    }

    public static String alias(String sourceId) {
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT alias FROM source_conf WHERE source_id=?",
                    new String[]{sourceId});
            if (c != null && c.moveToFirst()) {
                String a = Db.str(c, "alias");
                if (a.length() > 0) return a;
            }
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return null;
    }

    /** 写别名（改名）。alias 为空串表示清除别名，回落原名。 */
    public static void setAlias(String sourceId, String alias) {
        if (sourceId == null || sourceId.length() == 0) return;
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("source_id", sourceId);
        v.put("alias", alias == null ? "" : alias.trim());
        Cursor c = null;
        try {
            c = Db.get().rawQuery(
                    "SELECT enabled, sort FROM source_conf WHERE source_id=?",
                    new String[]{sourceId});
            if (c != null && c.moveToFirst()) {
                v.put("enabled", Db.i(c, "enabled", 1));
                v.put("sort", Db.i(c, "sort", 1000));
            } else {
                v.put("enabled", 1);
                v.put("sort", 1000);
            }
        } catch (Throwable ignored) {
            v.put("enabled", 1);
            v.put("sort", 1000);
        } finally {
            Db.close(c);
        }
        Db.get().insert("source_conf", v);
    }

    /** 彻底遗忘一个源的配置（删除自定义源时调用，避免残留孤儿记录） */
    public static void forget(String sourceId) {
        if (sourceId == null || sourceId.length() == 0) return;
        try {
            Db.get().delete("source_conf", "source_id=?", new String[]{sourceId});
        } catch (Throwable ignored) {}
    }

    /** 显示名：有别名用别名，否则用源自带名字 */
    public static String displayName(String sourceId, String fallback) {
        String a = alias(sourceId);
        if (a != null && a.length() > 0) return a;
        return fallback == null ? sourceId : fallback;
    }
}
