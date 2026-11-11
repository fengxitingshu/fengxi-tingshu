package com.tingshu.app.data;

import android.content.ContentValues;
import android.database.Cursor;

import com.tingshu.app.core.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件书源的持久化（表 plugin_jar）。
 * 只记录「装了哪些插件」，源实例由 JarLoader / JsPluginLoader 启动时重建。
 *
 * <p>★ v3.0：表里加了 {@code kind} 列，区分两种插件格式：
 * <ul>
 *   <li>{@code "jar"} —— 原「我的听书」生态的 jar 插件，走 DexClassLoader</li>
 *   <li>{@code "js"}  —— JS 脚本插件包（.zip），走 Rhino 引擎</li>
 * </ul>
 * 老数据在 v6→v7 升级时统一补成 {@code 'jar'}，语义不变。
 */
public final class PluginRepo {

    private PluginRepo() {}

    /** 插件格式：jar 插件 */
    public static final String KIND_JAR = "jar";
    /** 插件格式：JS 脚本插件包 */
    public static final String KIND_JS = "js";

    /** 一条插件记录 */
    public static class Plugin {
        public String filePath;   // 私有目录里的绝对路径
        public String fileName;   // 原始文件名（展示用）
        public String sourceIds;  // 逗号分隔的源 id（加载成功后回填）
        public boolean enabled;
        public String kind = KIND_JAR;
        public long addedAt;

        public boolean isJs() { return KIND_JS.equals(kind); }
    }

    public static List<Plugin> all() {
        List<Plugin> out = new ArrayList<Plugin>();
        Cursor c = null;
        try {
            c = Db.get().rawQuery(
                    "SELECT file_path, file_name, source_ids, enabled, kind, added_at"
                    + " FROM plugin_jar ORDER BY added_at ASC", null);
            while (c != null && c.moveToNext()) {
                Plugin p = new Plugin();
                p.filePath = Db.str(c, "file_path");
                p.fileName = Db.str(c, "file_name");
                p.sourceIds = Db.str(c, "source_ids");
                p.enabled = Db.i(c, "enabled", 1) != 0;
                p.kind = Db.str(c, "kind");
                if (p.kind == null || p.kind.length() == 0) p.kind = KIND_JAR;
                p.addedAt = Db.l(c, "added_at", 0);
                out.add(p);
            }
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return out;
    }

    /** 只要某种格式的插件 */
    public static List<Plugin> byKind(String kind) {
        List<Plugin> out = new ArrayList<Plugin>();
        List<Plugin> all = all();
        for (int i = 0; i < all.size(); i++) {
            if (kind == null || kind.equals(all.get(i).kind)) out.add(all.get(i));
        }
        return out;
    }

    /** 启用中的插件列表 */
    public static List<Plugin> enabled() {
        List<Plugin> out = new ArrayList<Plugin>();
        List<Plugin> all = all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).enabled) out.add(all.get(i));
        }
        return out;
    }

    public static boolean exists(String filePath) {
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT 1 FROM plugin_jar WHERE file_path=?",
                    new String[]{filePath});
            return c != null && c.moveToFirst();
        } catch (Throwable t) {
            return false;
        } finally {
            Db.close(c);
        }
    }

    /**
     * ★ v4.21.7：按「归一化文件名」清理重复的 JS 插件记录。
     *
     * <p>背景：v4.21.6 及之前，单 .js 源文件每次导入都落一个带时间戳的新路径，
     * PluginRepo.add 按 file_path 去重不命中 → 重导 N 次堆 N 条记录 →
     * 启动重建时同一个音源被加载 N 份，搜索/取链全部多路并发。
     * 这里按去掉时间戳后缀的文件名分组，同组只保留最新一条（其余仅删记录，
     * 不动插件目录，避免误伤）。
     */
    public static void dedupeJs() {
        Cursor c = null;
        try {
            c = Db.get().rawQuery(
                    "SELECT file_path, file_name, added_at FROM plugin_jar WHERE kind=?"
                            + " ORDER BY added_at DESC",
                    new String[]{KIND_JS});
            java.util.LinkedHashMap<String, String> keep = new java.util.LinkedHashMap<String, String>();
            java.util.List<String> kill = new java.util.ArrayList<String>();
            while (c != null && c.moveToNext()) {
                String path = Db.str(c, "file_path");
                String name = Db.str(c, "file_name");
                String key = normStem(name);
                if (key.length() == 0) key = normStem(path);
                if (key.length() == 0) continue;          // 归一不出 key 的不碰
                if (keep.containsKey(key)) kill.add(path); // 旧的（倒序遍历，先到的是最新）
                else keep.put(key, path);
            }
            Db.close(c);
            c = null;
            for (String path : kill) {
                try {
                    Db.get().delete("plugin_jar", "file_path=?", new String[]{path});
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
    }

    /** 「墨澜音乐源v2.3.5.js」/「…_v2_3_5-1793785487355.js」→ 去扩展名、去 -时间戳 */
    private static String normStem(String s) {
        if (s == null) return "";
        String t = s.replace('\\', '/');
        int slash = t.lastIndexOf('/');
        if (slash >= 0) t = t.substring(slash + 1);
        t = t.replaceAll("\\.(js|zip|jsplugin)$", "");
        t = t.replaceAll("-\\d{10,}$", "");
        return t.trim();
    }

    /** 新增（存在则忽略），返回是否已存在。kind 见 {@link #KIND_JAR} / {@link #KIND_JS} */
    public static boolean add(String filePath, String fileName, String kind) {
        if (Utils.empty(filePath)) return false;
        boolean had = exists(filePath);
        if (had) return true;
        ContentValues v = new ContentValues();
        v.put("file_path", filePath);
        v.put("file_name", fileName == null ? "" : fileName);
        v.put("enabled", 1);
        v.put("kind", kind == null || kind.length() == 0 ? KIND_JAR : kind);
        v.put("added_at", System.currentTimeMillis());
        Db.get().insert("plugin_jar", v);
        return false;
    }

    /** 兼容旧调用：默认按 jar 插件记 */
    public static boolean add(String filePath, String fileName) {
        return add(filePath, fileName, KIND_JAR);
    }

    /** 加载成功后回填源 id 清单 */
    public static void setSourceIds(String filePath, List<String> ids) {
        if (Utils.empty(filePath)) return;
        StringBuilder sb = new StringBuilder();
        if (ids != null) {
            for (int i = 0; i < ids.size(); i++) {
                if (sb.length() > 0) sb.append(',');
                sb.append(ids.get(i));
            }
        }
        ContentValues v = new ContentValues();
        v.put("source_ids", sb.toString());
        Db.get().update("plugin_jar", v, "file_path=?", new String[]{filePath});
    }

    public static void setEnabled(String filePath, boolean enabled) {
        ContentValues v = new ContentValues();
        v.put("enabled", enabled ? 1 : 0);
        Db.get().update("plugin_jar", v, "file_path=?", new String[]{filePath});
    }

    /** 删除记录并顺手删掉插件文件（jar 文件 / JS 插件目录）与它的缓存 */
    public static boolean remove(String filePath) {
        if (Utils.empty(filePath)) return false;
        // 先查一次 kind，决定怎么清理磁盘
        String kind = kindOf(filePath);
        int n = Db.get().delete("plugin_jar", "file_path=?", new String[]{filePath});
        try {
            java.io.File f = new java.io.File(filePath);
            if (f.isDirectory()) {
                deleteDir(f);
            } else {
                f.delete();
            }
        } catch (Throwable ignored) {}
        // JS 插件的持久缓存（相声随身听的索引可能好几 MB）一并清掉
        if (KIND_JS.equals(kind)) {
            try {
                com.tingshu.app.core.js.JsPluginKV.clear(Utils.safe(fileNameOf(filePath)));
            } catch (Throwable ignored) {}
        }
        return n > 0;
    }

    /** 查某个插件的格式 */
    public static String kindOf(String filePath) {
        if (Utils.empty(filePath)) return KIND_JAR;
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT kind FROM plugin_jar WHERE file_path=?",
                    new String[]{filePath});
            if (c != null && c.moveToFirst()) {
                String k = Db.str(c, "kind");
                return k == null || k.length() == 0 ? KIND_JAR : k;
            }
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        return KIND_JAR;
    }

    private static String fileNameOf(String filePath) {
        int i = filePath == null ? -1 : filePath.lastIndexOf('/');
        return i >= 0 ? filePath.substring(i + 1) : filePath;
    }

    private static void deleteDir(java.io.File d) {
        java.io.File[] fs = d.listFiles();
        if (fs != null) for (java.io.File f : fs) {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        }
        d.delete();
    }

    public static int count() {
        return all().size();
    }
}
