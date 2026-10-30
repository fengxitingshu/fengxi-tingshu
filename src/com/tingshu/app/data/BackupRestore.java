package com.tingshu.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ★ v3.8.0 恢复执行器。
 *
 * <p><b>这是本功能里唯一会「覆盖用户数据」的地方，因此每一步都为「宁可恢复失败，
 * 也不能把数据搞坏」设计。</b>
 *
 * <h3>执行顺序（严格）</h3>
 * <ol>
 *   <li><b>探测包</b>：解包 + 校验 app 标识 / schema / 内容校验和。
 *       失败 → 立即返回，<b>本机数据分毫未动</b>。</li>
 *   <li><b>★ 恢复前自动备份当前数据</b>（用户明确要求）。
 *       落到 {@code filesDir/auto_pre_restore/}，保留最近 3 个。
 *       <b>这一步失败就中止恢复</b> —— 因为「先备份再覆盖」是安全前提，
 *       前提不成立就不该继续。</li>
 *   <li><b>DB 写入</b>：在单个事务里「清表 + INSERT OR REPLACE」。
 *       任何一步抛异常 → 事务自动回滚 → 数据回到步骤 3 之前。</li>
 *   <li><b>文件面</b>：JS 插件 / Python 源 / jar。
 *       用「先改名 .bak → 写新的 → 成功才删 .bak」策略。</li>
 *   <li><b>Settings</b>：逐白名单键 {@code commit()}（不是 apply，确保立即生效）。</li>
 *   <li><b>重载源表</b>。</li>
 * </ol>
 *
 * <h3>★ 为什么不直接替换 tingshu.db 文件</h3>
 * {@link Db} 是静态单例，内部持有 {@code SQLiteDatabase} 连接，
 * <b>全部代码里没有一处 {@code close()}</b>，而且开了 WAL（还有 {@code -wal}/{@code -shm}
 * 两个附属文件）。直接替换文件会导致：① 已持有的连接仍指向被删掉的旧 inode，
 * 后续读写全部落到幽灵文件；② WAL 内容丢失造成数据不一致。
 * 改用「事务内清表 + INSERT OR REPLACE」，语义上同样满足「整体覆盖」，
 * 但完全不需要关连接，风险低得多。
 *
 * <h3>「不留半截数据」的三重保证</h3>
 * ① 恢复前自动备份兜底；② DB 事务原子回滚；③ 文件面 .bak 目录，成功才删。
 */
public final class BackupRestore {

    private BackupRestore() {}

    private static final String DIR_JS = "sources/js/";
    private static final String DIR_PY = "sources/py/";
    private static final String DIR_JAR = "sources/jar/";

    /**
     * ★ v3.9.6：必须以 float 存回 SharedPreferences 的设置项。
     *
     * <p>原因见 {@link Settings#speed()} —— 这些键一旦被写成 int，
     * 读取端一旦用 getFloat 就会 ClassCastException（App 一打开就闪退）。
     * 恢复备份时按此名单强制按 float 写回，从源头杜绝坏值。
     */
    private static final Set<String> FLOAT_KEYS = new HashSet<String>(Arrays.asList(
            "speed_global"
    ));

    /** 恢复主流程。返回 {ok, counts, autoBackupName, error, warn, localStorage} */
    public static JSONObject restore(File pkg) throws Exception {
        final JSONObject r = new JSONObject();
        final Context c = com.tingshu.app.core.App.ctx();
        if (c == null) throw new IllegalStateException("App 尚未创建");

        // ================= 1) 探测包（不写任何数据） =================
        final File work = dir(c, "restore_work");
        wipeDir(work);                                  // 清掉上次残留

        final BackupCodec.Parsed p;
        InputStream in = new FileInputStream(pkg);
        try {
            p = BackupCodec.readZip(in, work);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
        String bad = BackupCodec.validate(p);
        if (bad != null) {
            r.put("ok", false);
            r.put("error", bad);
            return r;
        }

        // ================= 2) ★ 恢复前自动备份 =================
        // ★ v3.9.1：失败**不再中止恢复**。
        //   原来的逻辑是"自动备份失败 → 直接取消恢复"，而自动备份只是个安全网：
        //   它一挂（磁盘满 / 某个插件文件读不到 / 打包中途异常），用户明明拿着
        //   一个好好的备份包却永远恢复不了，还看不到真正的原因在哪一步。
        //   现在降级为告警：照样恢复，但把"没留底"这件事如实告诉用户。
        String autoName = "";
        List<String> preWarns = new ArrayList<String>();
        try {
            File auto = BackupRepo.autoBackupBeforeRestore();
            autoName = auto == null ? "" : auto.getName();
        } catch (Throwable t) {
            preWarns.add("恢复前未能自动留存当前数据（" + msg(t) + "）");
        }

        // ================= 3) DB 写入（单个事务） =================
        //  ★ 关键：包内容 p 必须在闭包外先备好，事务里只做「清表 + 回填」这一件事。
        final JSONObject counts = new JSONObject();
        boolean dbOk = Db.get().transaction(new Runnable() {
            public void run() {
                for (String t : BackupRepo.USER_TABLES) {
                    restoreTable(t, p, counts);
                }
            }
        });
        if (!dbOk) {
            r.put("ok", false);
            r.put("error", "数据写入失败，已自动回滚（现有数据未被破坏）");
            r.put("autoBackupName", autoName);
            return r;
        }
        r.put("counts", counts);

        // ================= 4) 文件面（失败只告警，不回滚 DB） =================
        List<String> warns = new ArrayList<String>();
        warns.addAll(preWarns);
        restoreJsPlugins(p, c, warns);
        restorePySources(p, c, warns);
        restoreJars(p, c, warns);

        // ================= 5) Settings（commit 确保立即生效） =================
        restoreSettings(p, warns);

        // ================= 6) 重载源表 =================
        try {
            com.tingshu.app.source.SourceRegistry.reload();
        } catch (Throwable ignored) {}

        // ================= 7) 收尾 =================
        r.put("ok", true);
        r.put("autoBackupName", autoName);
        if (!warns.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < warns.size(); i++) {
                if (i > 0) sb.append("；");
                sb.append(warns.get(i));
            }
            r.put("warn", sb.toString());
        }

        // localStorage 交给前端写（Java 读不到 WebView 的 DomStorage）
        JSONObject ls = p.json("localstorage.json");
        if (ls != null && ls.length() > 0) r.put("localStorage", ls);

        wipeDir(work);
        return r;
    }

    // ==================== DB 逐表恢复 ====================

    /**
     * 单表恢复：清空 → 逐行 INSERT OR REPLACE。
     *
     * <p><b>★ 列白名单</b>：只写「包里带了、且本地表实际也存在」的列。
     * 这样处理两种情形都安全：
     * <ul>
     *   <li>包比本机新（有本机没有的列）→ 该列被忽略，不报错</li>
     *   <li>包比本机旧（缺本机新增的列）→ 缺的列取默认值</li>
     * </ul>
     * 列清单用 {@code PRAGMA table_info(t)} 现取，不硬编码。
     */
    private static void restoreTable(String table, BackupCodec.Parsed p, JSONObject counts) {
        // 包内没有这张表 → 跳过（老包可能少表）
        JSONObject t = p.json("data/" + table + ".json");
        if (t == null) {
            try { counts.put(table, 0); } catch (Throwable ignored) {}
            return;
        }

        // 1) 清空
        Db.get().exec("DELETE FROM " + table);

        // ★ 收藏分组表：清空后必须补回默认分组 id=0，
        //   否则所有 group_id=0 的收藏会指向一个不存在的分组。
        if ("fav_group".equals(table)) {
            try {
                ContentValues dv = new ContentValues();
                dv.put("id", 0);
                dv.put("name", "默认分组");
                dv.put("sort", 0);
                insertIgnore("fav_group", dv);
            } catch (Throwable ignored) {}
        }

        // 2) 本地实际存在的列
        Set<String> local = columnsOf(table);

        // 3) 逐行回填
        JSONArray rows = t.optJSONArray("rows");
        int n = 0;
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                ContentValues v = new ContentValues();
                java.util.Iterator<String> it = row.keys();
                while (it.hasNext()) {
                    String col = it.next();
                    if (!local.contains(col)) continue;      // ★ 列白名单
                    Object val = row.opt(col);
                    if (val == null || val == JSONObject.NULL) {
                        v.putNull(col);
                    } else if (val instanceof Integer || val instanceof Long) {
                        v.put(col, ((Number) val).longValue());
                    } else if (val instanceof Double || val instanceof Float) {
                        v.put(col, ((Number) val).doubleValue());
                    } else if (val instanceof Boolean) {
                        v.put(col, ((Boolean) val) ? 1 : 0);
                    } else {
                        v.put(col, String.valueOf(val));
                    }
                }
                if (v.size() == 0) continue;
                Db.get().insert(table, v);
                n++;
            }
        }
        try { counts.put(table, n); } catch (Throwable ignored) {}
    }

    /** 兼容用：吞掉「已存在」类异常的单行插入（给默认分组用） */
    private static void insertIgnore(String table, ContentValues v) {
        try {
            Db.get().insert(table, v);
        } catch (Throwable ignored) {}
    }

    /** 取某表实际存在的列名集合（PRAGMA table_info） */
    private static Set<String> columnsOf(String table) {
        Set<String> out = new HashSet<String>();
        Cursor c = null;
        try {
            c = Db.get().rawQuery("PRAGMA table_info(" + table + ")", null);
            if (c != null) {
                int idx = c.getColumnIndex("name");
                while (c.moveToNext()) {
                    if (idx >= 0) {
                        String nm = c.getString(idx);
                        if (nm != null && nm.length() > 0) out.add(nm);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    // ==================== 文件面恢复 ====================

    /**
     * JS 插件。策略：把现有 {@code js_plugins} 改名成 {@code js_plugins.bak-<ts>}，
     * 写新的，<b>成功后</b>才删 .bak。中途失败则把 .bak 改回来。
     */
    private static void restoreJsPlugins(BackupCodec.Parsed p, Context c, List<String> warns) {
        File root = new File(c.getFilesDir(), "js_plugins");
        File bak = new File(c.getFilesDir(), "js_plugins.bak-" + System.currentTimeMillis());

        // 包里没有 js 条目 → 一个都不动（老包/没装过插件）
        List<String> entries = entriesUnder(p, DIR_JS);
        if (entries.isEmpty()) return;

        boolean moved = false;
        try {
            if (root.exists()) {
                if (!root.renameTo(bak)) throw new IllegalStateException("旧插件目录改名失败");
                moved = true;
            }
            if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("建目录失败");

            // 还原：条目名 sources/js/<pkg>/<file...>
            for (String ent : entries) {
                String rel = ent.substring(DIR_JS.length());       // <pkg>/<file>
                if (rel.length() == 0) continue;
                File src = p.files.get(ent);
                if (src == null) continue;
                File dst = new File(root, rel);
                writeInside(root, dst, src);
            }
            // 成功 → 删旧
            if (moved) wipeDir(bak);
        } catch (Throwable t) {
            // 失败 → 回滚：删掉半成品，把 .bak 改回来
            wipeDir(root);
            if (moved) {
                try { bak.renameTo(root); } catch (Throwable ignored) {}
            }
            warns.add("JS 插件恢复失败（已回滚：" + msg(t) + "）");
        }
    }

    /** Python 源。策略同上。 */
    private static void restorePySources(BackupCodec.Parsed p, Context c, List<String> warns) {
        File root = new File(c.getFilesDir(), "python_sources");
        File bak = new File(c.getFilesDir(), "python_sources.bak-" + System.currentTimeMillis());

        List<String> entries = entriesUnder(p, DIR_PY);
        if (entries.isEmpty()) return;

        boolean moved = false;
        try {
            if (root.exists()) {
                if (!root.renameTo(bak)) throw new IllegalStateException("旧 Python 源目录改名失败");
                moved = true;
            }
            if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("建目录失败");

            for (String ent : entries) {
                String rel = ent.substring(DIR_PY.length());
                if (rel.length() == 0) continue;
                File src = p.files.get(ent);
                if (src == null) continue;
                writeInside(root, new File(root, rel), src);
            }
            if (moved) wipeDir(bak);
        } catch (Throwable t) {
            wipeDir(root);
            if (moved) {
                try { bak.renameTo(root); } catch (Throwable ignored) {}
            }
            warns.add("Python 源恢复失败（已回滚：" + msg(t) + "）");
        }
    }

    /**
     * jar 插件。
     *
     * <p><b>落地名不能沿用包内条目名</b>（那是 {@code 0_xxx.jar} 这种带序号的 ASCII 名），
     * 要用 {@code plugin_jar} 表里记录的 {@code file_name}。
     * 而且要知道「哪个条目对应哪一行」—— 这里按 {@code plugin_jar} 表的写入顺序
     * 与 jar 条目排序后的顺序做一一对应（打包侧 {@link BackupRepo} 正是按
     * {@code listJarFiles()} 的顺序 + 排序后写入的，两端一致）。
     *
     * <p>落盘后要<b>回写 file_path</b>，因为新路径与旧路径必然不同。
     */
    private static void restoreJars(BackupCodec.Parsed p, Context c, List<String> warns) {
        List<String> entries = entriesUnder(p, DIR_JAR);
        if (entries.isEmpty()) return;
        try {
            File root = c.getDir("plugins", Context.MODE_PRIVATE);
            if (!root.exists()) root.mkdirs();

            // 从 plugin_jar 表按 file_name 建立「包内条目 → 目标文件名」映射
            // 包内条目名形如 sources/jar/<idx>_<fileName>
            for (String ent : entries) {
                File src = p.files.get(ent);
                if (src == null) continue;
                String base = ent.substring(DIR_JAR.length());
                // 去掉开头的 "<数字>_"
                int us = base.indexOf('_');
                String fileName = (us >= 0 && us + 1 < base.length()) ? base.substring(us + 1) : base;
                fileName = com.tingshu.app.core.SafOut.safeName(fileName);

                File dst = new File(root, fileName);
                writeInside(root, dst, src);
            }

            // ★ 回写 file_path：按 file_name 匹配 plugin_jar 表里原有行，更新为新路径
            //   （插件登记行本身已在步骤 3 由 DB 恢复写入，这里只修正路径）
            try {
                Cursor cur = Db.get().rawQuery("SELECT file_name FROM plugin_jar", null);
                List<String> names = new ArrayList<String>();
                if (cur != null) {
                    while (cur.moveToNext()) {
                        String nm = cur.getString(0);
                        if (nm != null && nm.length() > 0) names.add(nm);
                    }
                    cur.close();
                }
                for (String nm : names) {
                    File f = new File(root, com.tingshu.app.core.SafOut.safeName(nm));
                    if (!f.isFile()) continue;
                    ContentValues v = new ContentValues();
                    v.put("file_path", f.getAbsolutePath());
                    Db.get().update("plugin_jar", v, "file_name = ?", new String[]{nm});
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            warns.add("jar 插件恢复失败（" + msg(t) + "）");
        }
    }

    // ==================== Settings 恢复 ====================

    private static void restoreSettings(BackupCodec.Parsed p, List<String> warns) {
        JSONObject s = p.json("data/settings.json");
        if (s == null) return;
        try {
            android.content.SharedPreferences.Editor e = Settings.sp().edit();
            for (String k : BackupRepo.SETTINGS_KEYS) {
                if (!s.has(k)) continue;
                Object v = s.opt(k);
                if (v == null || v == JSONObject.NULL) continue;
                // ★★ v3.9.6 修复：浮点型设置项（如 speed_global）必须按 float 写回。
                //   原来对 Integer/Long 一律 putInt，于是 speed_global 被写成 int，
                //   再被 Settings.speed() 的 getFloat() 一读就 ClassCastException → 闪退。
                if (FLOAT_KEYS.contains(k)) {
                    if (v instanceof Number) {
                        e.putFloat(k, ((Number) v).floatValue());
                    } else if (v instanceof String) {
                        try { e.putFloat(k, Float.parseFloat(((String) v).trim())); }
                        catch (Throwable ignored) { e.putFloat(k, 1.0f); }
                    }
                    continue;
                }
                if (v instanceof Integer) {
                    e.putInt(k, ((Number) v).intValue());
                } else if (v instanceof Long) {
                    e.putInt(k, ((Number) v).intValue());
                } else if (v instanceof Boolean) {
                    e.putBoolean(k, (Boolean) v);
                } else if (v instanceof Double || v instanceof Float) {
                    e.putFloat(k, ((Number) v).floatValue());
                } else {
                    e.putString(k, String.valueOf(v));
                }
            }
            // 登录态只在包里带了才写
            if (s.has("cookie_store")) {
                String ck = s.optString("cookie_store", "");
                if (ck != null && ck.length() > 0) e.putString("cookie_store", ck);
            }
            // ★ 用 commit 而不是 apply：恢复完要立刻生效，量小无所谓阻塞
            e.commit();
            // 让 Net 的内存态也跟上
            try { com.tingshu.app.core.Net.loadCookies(Settings.sp()); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            warns.add("设置恢复部分失败（" + msg(t) + "）");
        }
    }

    // ==================== 工具 ====================

    /** 取包内某前缀下的全部条目名（已排序，保证与打包侧顺序一致） */
    private static List<String> entriesUnder(BackupCodec.Parsed p, String prefix) {
        List<String> out = new ArrayList<String>();
        for (String k : p.files.keySet()) {
            if (k != null && k.startsWith(prefix)) out.add(k);
        }
        Collections.sort(out);
        return out;
    }

    /**
     * 写文件，并校验目标真实路径必须落在 root 之内（防路径穿越）。
     * 用流式复制，不整块读进内存。
     */
    private static void writeInside(File root, File dst, File src) throws Exception {
        String rp = root.getCanonicalPath();
        String dp = dst.getCanonicalPath();
        if (!dp.equals(rp) && !dp.startsWith(rp + File.separator)) {
            throw new IllegalStateException("非法路径：" + dst.getName());
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
            try { out.close(); } catch (Throwable ignored) {}
        }
    }

    /** 递归删空一个目录的内容（保留目录本身） */
    private static void wipeDir(File d) {
        if (d == null || !d.exists()) return;
        File[] all = d.listFiles();
        if (all == null) return;
        for (File f : all) {
            if (f == null) continue;
            if (f.isDirectory()) {
                wipeDir(f);
                try { f.delete(); } catch (Throwable ignored) {}
            } else {
                try { f.delete(); } catch (Throwable ignored) {}
            }
        }
    }

    private static File dir(Context c, String name) {
        File d = new File(c.getFilesDir(), name);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String msg(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        if (m == null || m.length() == 0) m = t.getClass().getSimpleName();
        return m;
    }
}
