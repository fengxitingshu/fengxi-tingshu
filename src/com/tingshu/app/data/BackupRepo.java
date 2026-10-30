package com.tingshu.app.data;

import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ★ v3.8.0 备份数据面。
 *
 * <p>职责：把「用户配置类数据」收集成一个 {@code .fxbak} 包；以及为恢复流程
 * 生成「恢复前自动备份」。<b>本类不负责恢复写入</b>，那是
 * {@link BackupRestore} 的事。
 *
 * <p><b>备份范围</b>（用户已确认：仅配置数据，不含音频）：
 * <ul>
 *   <li>8 张用户表：fav / fav_group / history / progress /
 *       source_conf / custom_source / plugin_jar / search_hist</li>
 *   <li>{@code settings} SharedPreferences 的白名单键</li>
 *   <li>JS 插件目录 / Python 源 / jar 插件</li>
 *   <li>前端 localStorage 的 4 个 {@code fx_} 键（由调用方异步取好传进来）</li>
 * </ul>
 *
 * <p><b>★ 绝不包含</b>：{@code cache_audio}（音频预读缓存）、
 * {@code dl_audio}（已下载音频）、{@code chaquopy}（Python 运行时，
 * 可重新解压）、{@code crash}、{@code plugin_cache}。这些都是可重建的，
 * 装进去只会让包从几百 KB 涨到几个 GB。
 *
 * <p><b>为什么 DB 用逐表 JSON 而不是直接拷 tingshu.db 文件</b>：
 * {@code Db} 是无 {@code close()} 的单例（见 {@link Db}），且开了 WAL。
 * 直接拷文件要处理 {@code -wal}/{@code -shm} 一致性、跨版本 schema 不匹配、
 * 换机 user_version 对不上三个问题。逐表 JSON 全部绕开，
 * 且向前兼容天然更好（缺的表跳过即可）。
 */
public final class BackupRepo {

    private BackupRepo() {}

    /** 需要备份的用户表（顺序即打包顺序，固定便于校验和复现） */
    public static final List<String> USER_TABLES = Arrays.asList(
            "fav", "fav_group", "history", "progress",
            "source_conf", "custom_source", "plugin_jar", "search_hist");

    /**
     * 可备份的设置键白名单。
     *
     * <p><b>★ 刻意排除的键</b>（这些属于「绑设备」或「运行时状态」，
     * 备份了反而会在换机后造成困惑）：
     * <ul>
     *   <li>{@code float_ball_x} / {@code float_ball_y} —— 悬浮球坐标，
     *       换机后屏幕尺寸不同，恢复旧坐标会让球跑到屏幕外</li>
     *   <li>{@code download_tree_uri} / {@code download_tree_name} ——
     *       SAF 授权 URI，<b>换机必然失效</b>，恢复它等于恢复一个死链接</li>
     *   <li>{@code ignore_version} —— 「忽略这个版本」的自更新偏好，
     *       跟着设备走更合理</li>
     *   <li>{@code last_verify_summary} / {@code last_source} —— 运行时状态</li>
     * </ul>
     */
    public static final String[] SETTINGS_KEYS = {
            "speed_global", "ff_seconds", "rewind_on_play_ms", "play_mode", "gain_pct",
            "audio_focus_mode", "wifi_play_only", "play_on_full", "float_ball",
            "sleep_minutes", "exit_on_timer", "pause_after_chapters",
            "auto_cache_enabled", "auto_cache_count", "wifi_cache_only", "cache_limit_mb",
            "theme", "theme_color", "search_sources",
        "skip_intro_sec", "skip_outro_sec", "float_ball_side", "float_ball_yr"
    };

    /** 包内含中文名的插件目录序号（条目名用 ASCII，中文名放 JSON） */
    private static final String DIR_JS = "sources/js/";
    private static final String DIR_PY = "sources/py/";
    private static final String DIR_JAR = "sources/jar/";
    private static final String ENT_JSON_SOURCES = "sources/json_sources.json";
    private static final String ENT_LOCALSTORAGE = "localstorage.json";

    // ==================== 统计 ====================

    /** 进备份页展示：各部分条目数。失败字段一律给 0，不抛。 */
    public static JSONObject stats() {
        JSONObject o = new JSONObject();
        try {
            o.put("fav", count("fav"));
            o.put("favGroup", count("fav_group"));
            o.put("history", count("history"));
            o.put("progress", count("progress"));
            o.put("sourceConf", count("source_conf"));
            o.put("customSource", count("custom_source"));
            o.put("pluginJar", count("plugin_jar"));
            o.put("searchHist", count("search_hist"));
            o.put("jsPlugins", listJsPluginDirs().size());
            o.put("pySources", listPySources().size());
            o.put("jars", listJarFiles().size());
            // 自定义源数量（SourceStore 侧，与 custom_source 表一致但更权威）
            o.put("sources", SourceStore.count());
            o.put("total", count("fav") + count("fav_group") + count("history")
                    + count("custom_source"));
        } catch (Throwable ignored) {}
        return o;
    }

    private static int count(String table) {
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT COUNT(*) FROM " + table, null);
            if (c != null && c.moveToFirst()) return c.getInt(0);
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(c);
        }
        return 0;
    }

    // ==================== 打包 ====================

    /**
     * 生成一个完整备份包到私有目录，返回文件。调用方负责用后删除。
     *
     * @param includeCookie  是否包含 {@code cookie_store}（含网站登录态，默认 false）
     * @param localStorage   前端 localStorage 的键值（可为 null）
     * @param versionName    当前 App 版本名，写进 manifest
     * @param versionCode    当前 App 版本号，写进 manifest
     */
    public static File buildPackage(boolean includeCookie, JSONObject localStorage,
                                    String versionName, int versionCode) throws Exception {
        Context c = com.tingshu.app.core.App.ctx();
        if (c == null) throw new IllegalStateException("App 尚未创建");

        Map<String, byte[]> blobs = new LinkedHashMap<String, byte[]>();
        Map<String, File> files = new LinkedHashMap<String, File>();

        // ---- 1) 8 张用户表逐表导出 ----
        for (String t : USER_TABLES) {
            blobs.put("data/" + t + ".json", tableJson(t).toString().getBytes("UTF-8"));
        }

        // ---- 2) settings 白名单 ----
        blobs.put("data/settings.json",
                settingsJson(includeCookie).toString().getBytes("UTF-8"));

        // ---- 3) 自定义 JSON 源（复用现有导出） ----
        try {
            String src = com.tingshu.app.BridgeSource.exportJson(true, false);
            if (src != null) blobs.put(ENT_JSON_SOURCES, src.getBytes("UTF-8"));
        } catch (Throwable ignored) {}

        // ---- 4) localStorage（可选，由调用方异步取好传入） ----
        if (localStorage != null && localStorage.length() > 0) {
            blobs.put(ENT_LOCALSTORAGE, localStorage.toString().getBytes("UTF-8"));
        }

        // ---- 5) 文件面：JS 插件 / Python 源 / jar 插件 ----
        //     ★ 注意：这里只遍历 js_plugins / python_sources / plugin_jar 表，
        //       绝不遍历 cache_audio / dl_audio / chaquopy / crash。
        for (String rel : listJsPluginDirs()) {
            File dir = new File(c.getFilesDir(), "js_plugins/" + rel);
            collectDir(files, DIR_JS + rel + "/", dir, c);
        }
        for (String rel : listPySources()) {
            File f = new File(c.getFilesDir(), "python_sources/" + rel);
            files.put(DIR_PY + rel, f);
        }
        List<File> jars = listJarFiles();
        for (int i = 0; i < jars.size(); i++) {
            File f = jars.get(i);
            String nm = com.tingshu.app.core.SafOut.safeName(
                    f.getName() == null ? ("jar" + i) : f.getName());
            files.put(DIR_JAR + i + "_" + nm, f);
        }

        // ---- 6) manifest ----
        JSONObject manifest = buildManifest(versionName, versionCode, blobs, files);

        // ---- 7) 写包 ----
        File dir = tmpDir(c, "backup_tmp");
        File out = new File(dir, "backup-" + System.currentTimeMillis()
                + BackupCodec.EXT);
        FileOutputStream fo = new FileOutputStream(out);
        try {
            BackupCodec.writeZip(fo, manifest, blobs, files);
            fo.flush();
        } finally {
            try { fo.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /** 恢复前自动备份：生成到 {@code filesDir/auto_pre_restore/}，只保留最近 3 个 */
    public static File autoBackupBeforeRestore() throws Exception {
        Context c = com.tingshu.app.core.App.ctx();
        if (c == null) throw new IllegalStateException("App 尚未创建");

        File src = buildPackage(false, null, "auto", 0);
        File dir = tmpDir(c, "auto_pre_restore");
        File out = new File(dir, "pre-restore-" + System.currentTimeMillis()
                + BackupCodec.EXT);

        // 移动（同一分区，renameTo 足够；失败退化为复制）
        if (!src.renameTo(out)) {
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            FileOutputStream fo = new FileOutputStream(out);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                fo.flush();
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
                try { fo.close(); } catch (Throwable ignored) {}
            }
            try { src.delete(); } catch (Throwable ignored) {}
        }

        rotateAutoBackups(dir, 3);
        return out;
    }

    /** 只保留最新 keep 个自动备份，多余的删掉 */
    private static void rotateAutoBackups(File dir, int keep) {
        try {
            File[] all = dir.listFiles();
            if (all == null || all.length <= keep) return;
            Arrays.sort(all, new java.util.Comparator<File>() {
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());  // 新→旧
                }
            });
            for (int i = keep; i < all.length; i++) {
                try { all[i].delete(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /** 列出本机的自动备份（新→旧） */
    public static JSONArray autoBackupList() {
        JSONArray arr = new JSONArray();
        try {
            Context c = com.tingshu.app.core.App.ctx();
            if (c == null) return arr;
            File dir = new File(c.getFilesDir(), "auto_pre_restore");
            File[] all = dir.listFiles();
            if (all == null) return arr;
            Arrays.sort(all, new java.util.Comparator<File>() {
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            for (File f : all) {
                if (f == null || !f.isFile()) continue;
                JSONObject o = new JSONObject();
                o.put("name", f.getName());
                o.put("size", f.length());
                o.put("at", f.lastModified());
                o.put("atText", fmt.format(new Date(f.lastModified())));
                arr.put(o);
            }
        } catch (Throwable ignored) {}
        return arr;
    }

    /** 按名字取自动备份文件（防路径穿越：只取 basename） */
    public static File autoBackupFile(String name) {
        try {
            Context c = com.tingshu.app.core.App.ctx();
            if (c == null || name == null) return null;
            String safe = new File(name).getName();
            if (safe.length() == 0 || safe.contains("..")) return null;
            File f = new File(new File(c.getFilesDir(), "auto_pre_restore"), safe);
            return f.isFile() ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 各部分组装 ====================

    /**
     * 单表 → JSON。
     *
     * <p><b>★ 用 {@code cursor.getColumnNames()} 动态列名</b>而不是硬编码列：
     * 这样将来给表加列时，备份自动带上新列，不用改这里；
     * 恢复侧再用「本地表实际有的列」求交集，两头都稳。
     */
    private static JSONObject tableJson(String table) {
        JSONObject root = new JSONObject();
        JSONArray rows = new JSONArray();
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT * FROM " + table, null);
            if (c != null) {
                String[] cols = c.getColumnNames();
                while (c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    for (String col : cols) {
                        int idx = c.getColumnIndex(col);
                        if (idx < 0) continue;
                        switch (c.getType(idx)) {
                            case Cursor.FIELD_TYPE_INTEGER:
                                o.put(col, c.getLong(idx));
                                break;
                            case Cursor.FIELD_TYPE_FLOAT:
                                o.put(col, c.getDouble(idx));
                                break;
                            case Cursor.FIELD_TYPE_NULL:
                                o.put(col, JSONObject.NULL);
                                break;
                            default:
                                String s = c.getString(idx);
                                o.put(col, s == null ? JSONObject.NULL : s);
                                break;
                        }
                    }
                    rows.put(o);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(c);
        }
        try {
            root.put("table", table);
            root.put("count", rows.length());
            root.put("rows", rows);
        } catch (Throwable ignored) {}
        return root;
    }

    /** settings 白名单 → JSON。类型按 {@code sp.getAll()} 的实际类型原样保留。 */
    private static JSONObject settingsJson(boolean includeCookie) {
        JSONObject o = new JSONObject();
        try {
            Map<String, ?> all = Settings.sp().getAll();
            for (String k : SETTINGS_KEYS) {
                if (!all.containsKey(k)) continue;
                Object v = all.get(k);
                if (v == null) continue;
                o.put(k, v);
            }
            if (includeCookie) {
                String ck = Settings.sp().getString("cookie_store", "");
                if (ck != null && ck.length() > 0) o.put("cookie_store", ck);
            }
            // 标记：让恢复侧知道这个包是否带登录态
            o.put("_includeCookie", includeCookie);
        } catch (Throwable ignored) {}
        return o;
    }

    private static JSONObject buildManifest(String versionName, int versionCode,
                                           Map<String, byte[]> blobs,
                                           Map<String, File> files) {
        JSONObject m = new JSONObject();
        try {
            m.put("schema", BackupCodec.SCHEMA);
            m.put("app", BackupCodec.APP);
            m.put("appName", "枫兮听书");
            m.put("versionName", versionName == null ? "" : versionName);
            m.put("versionCode", versionCode);
            long now = System.currentTimeMillis();
            m.put("createdAt", now);
            m.put("createdText", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(now)));

            JSONObject dev = new JSONObject();
            dev.put("brand", Build.BRAND == null ? "" : Build.BRAND);
            dev.put("model", Build.MODEL == null ? "" : Build.MODEL);
            dev.put("sdk", Build.VERSION.SDK_INT);
            m.put("device", dev);

            JSONObject cnt = new JSONObject();
            cnt.put("fav", count("fav"));
            cnt.put("favGroup", count("fav_group"));
            cnt.put("history", count("history"));
            cnt.put("progress", count("progress"));
            cnt.put("sourceConf", count("source_conf"));
            cnt.put("customSource", count("custom_source"));
            cnt.put("pluginJar", count("plugin_jar"));
            cnt.put("searchHist", count("search_hist"));
            cnt.put("jsPlugins", listJsPluginDirs().size());
            cnt.put("pySources", listPySources().size());
            cnt.put("jars", listJarFiles().size());
            m.put("counts", cnt);

            JSONArray scope = new JSONArray();
            for (String t : USER_TABLES) scope.put(t);
            scope.put("settings");
            scope.put("sources");
            scope.put("localstorage");
            m.put("scope", scope);

            // ★ 显式声明排除了什么 —— 用户/排查时一眼能看出「音频没进包」
            JSONArray ex = new JSONArray();
            ex.put("cache_audio");
            ex.put("dl_audio");
            ex.put("chaquopy");
            ex.put("crash");
            ex.put("plugin_cache");
            m.put("excludes", ex);

            JSONObject ck = new JSONObject();
            ck.put("algo", "sha256");
            ck.put("value", BackupCodec.contentChecksum(blobs));
            m.put("checksum", ck);

            m.put("blobCount", blobs.size());
            m.put("fileCount", files.size());
        } catch (Throwable ignored) {}
        return m;
    }

    // ==================== 文件面收集（★ 范围严格限定） ====================

    /** JS 插件：{@code filesDir/js_plugins/} 下的每个子目录 */
    private static List<String> listJsPluginDirs() {
        List<String> out = new ArrayList<String>();
        try {
            Context c = com.tingshu.app.core.App.ctx();
            if (c == null) return out;
            File root = new File(c.getFilesDir(), "js_plugins");
            File[] all = root.listFiles();
            if (all == null) return out;
            for (File d : all) {
                if (d != null && d.isDirectory()) out.add(d.getName());
            }
            java.util.Collections.sort(out);
        } catch (Throwable ignored) {}
        return out;
    }

    /** Python 源：{@code filesDir/python_sources/} 下的每个 .py */
    private static List<String> listPySources() {
        List<String> out = new ArrayList<String>();
        try {
            Context c = com.tingshu.app.core.App.ctx();
            if (c == null) return out;
            File root = new File(c.getFilesDir(), "python_sources");
            File[] all = root.listFiles();
            if (all == null) return out;
            for (File f : all) {
                if (f != null && f.isFile() && f.getName().endsWith(".py")) {
                    out.add(f.getName());
                }
            }
            java.util.Collections.sort(out);
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * jar 插件文件。
     *
     * <p>来源是 {@code plugin_jar} 表里登记的 {@code file_path}（去重），
     * <b>不去扫目录</b> —— 扫目录会把历史遗留的孤儿 jar 也打包进去。
     */
    private static List<File> listJarFiles() {
        List<File> out = new ArrayList<File>();
        Cursor c = null;
        try {
            c = Db.get().rawQuery("SELECT file_path FROM plugin_jar", null);
            if (c != null) {
                while (c.moveToNext()) {
                    String p = c.getString(0);
                    if (p == null || p.length() == 0) continue;
                    File f = new File(p);
                    if (!f.isFile()) continue;
                    if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                    boolean dup = false;
                    for (File x : out) if (x.getAbsolutePath().equals(f.getAbsolutePath())) dup = true;
                    if (!dup) out.add(f);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    /** 递归把目录下的文件收进 files 映射（条目名 = prefix + 相对路径） */
    private static void collectDir(Map<String, File> files, String prefix,
                                   File dir, Context c) {
        collectDir0(files, prefix, dir, c, 0);
    }

    private static void collectDir0(Map<String, File> files, String prefix,
                                    File dir, Context c, int depth) {
        if (dir == null || depth > 6) return;          // 防符号链接环
        File[] all = dir.listFiles();
        if (all == null) return;
        for (File f : all) {
            if (f == null) continue;
            if (f.isDirectory()) {
                collectDir0(files, prefix + f.getName() + "/", f, c, depth + 1);
            } else if (f.isFile()) {
                files.put(prefix + f.getName(), f);
            }
        }
    }

    // ==================== 工具 ====================

    /** 校验包，不写任何数据。返回 {ok, schema, versionName, createdText, counts, error} */
    public static JSONObject inspect(File pkg) throws Exception {
        JSONObject r = new JSONObject();
        java.io.FileInputStream in = new java.io.FileInputStream(pkg);
        BackupCodec.Parsed p;
        try {
            File tmp = tmpDir(com.tingshu.app.core.App.ctx(), "inspect_work");
            p = BackupCodec.readZip(in, tmp);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
        String err = BackupCodec.validate(p);
        if (err != null) {
            r.put("ok", false);
            r.put("error", err);
            return r;
        }
        r.put("ok", true);
        r.put("schema", p.manifest.optInt("schema", 0));
        r.put("versionName", p.manifest.optString("versionName", ""));
        r.put("createdText", p.manifest.optString("createdText", ""));
        r.put("counts", p.manifest.optJSONObject("counts"));
        r.put("device", p.manifest.optJSONObject("device"));
        r.put("totalSize", p.totalSize);
        return r;
    }

    private static File tmpDir(Context c, String name) {
        File d = new File(c.getFilesDir(), name);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static void closeQuietly(Cursor c) {
        try { if (c != null) c.close(); } catch (Throwable ignored) {}
    }
}
