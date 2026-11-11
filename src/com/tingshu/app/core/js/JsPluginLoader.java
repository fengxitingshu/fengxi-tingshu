package com.tingshu.app.core.js;

import android.util.Log;

import com.tingshu.app.core.App;
import com.tingshu.app.core.Json;
import com.tingshu.app.core.Utils;
import com.tingshu.app.data.PluginRepo;
import com.tingshu.app.source.JsSourceAdapter;
import com.tingshu.app.source.Source;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ★ v3.0：JS 脚本插件包（.zip）的加载器。
 *
 * <p>对标 {@link com.tingshu.app.core.JarLoader}（jar 插件），这里是 JS 插件的入口。
 * 一个插件包 = 一个 zip，解包后含：
 * <pre>
 *   manifest.json   { name, version, author, description, scripts: ["common.js", ...] }
 *   common.js       各源共用的辅助函数
 *   sites.js / api.js / ...   各源定义（调用 registerSource 注册）
 * </pre>
 *
 * <h3>包结构校验</h3>
 * 只做**保守**校验，宁可放过不可误杀（假插件的危害只是加载失败）：
 * <ul>
 *   <li>必须有 manifest.json 且 scripts 非空</li>
 *   <li>脚本里**必须真的调用 registerSource** 注册出至少一个源</li>
 *   <li>单个脚本不超过 2MB（防 zip bomb）；包内文件数不超过 200</li>
 * </ul>
 *
 * <h3>加载流程（与 JarLoader 对齐）</h3>
 * <pre>
 *   1. 解包 zip 到 filesDir/js_plugins/{包名}/
 *   2. 读 manifest.scripts，按序取脚本内容
 *   3. JsEngine 建 scope，按序 eval
 *   4. 把收集到的每个源包成 JsSourceAdapter
 * </pre>
 */
public final class JsPluginLoader {

    private static final String TAG = "JsPlugin";
    /** 单个脚本上限（防 zip bomb） */
    private static final long MAX_SCRIPT_BYTES = 2 * 1024 * 1024L;
    /** 包内最多文件数 */
    private static final int MAX_ENTRIES = 200;

    private JsPluginLoader() {}

    /** 插件根目录：filesDir/js_plugins */
    public static File root() {
        File d = new File(App.ctx().getFilesDir(), "js_plugins");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 加载结果（字段结构与 JarLoader.JarResult 对齐，方便上层统一处理） */
    public static class JsResult {
        public boolean ok;
        public String error = "";
        public List<Source> sources = new ArrayList<Source>();
        public List<String> ids = new ArrayList<String>();
        public String format = "js";
        /** 插件包名（来自 manifest） */
        public String pkgName = "";
        public String pkgVersion = "";
        public String pkgAuthor = "";
        /** 落地目录 */
        public String dir = "";

        /** 供源适配器汇报进度/提示的回调（由调用方注入，可为 null） */
        public JsHostApi.Callback callback;
    }

    /**
     * 从**已解包的插件目录**加载（App 启动时重建源用）。
     *
     * <p>与 {@link #load(String, File)} 的区别：那个负责「导入」（解包 + 加载），
     * 这个只做「加载」—— 目录是上次导入时落地的，manifest.json 已经在里面了。
     * 启动路径上不该重复解包（解包会删目录重建，白白 IO）。
     *
     * @param dirPath 插件目录的绝对路径（PluginRepo.filePath）
     */
    public static JsResult loadInstalled(String dirPath) {
        JsResult r = new JsResult();
        try {
            if (dirPath == null || dirPath.length() == 0) {
                r.error = "插件目录为空";
                return r;
            }
            File dir = new File(dirPath);
            if (!dir.isDirectory()) {
                r.error = "插件目录不存在：" + dirPath;
                return r;
            }
            r.dir = dir.getAbsolutePath();
            return loadFromDir(dir, r);
        } catch (Throwable t) {
            r.error = "加载异常：" + brief(t);
            return r;
        }
    }

    /**
     * 从 zip 文件加载 JS 插件。
     *
     * @param zipPath 原始 zip 的绝对路径（需可读）
     * @param optDir  解包目标父目录（传 null 用默认 {@link #root()}）
     */
    public static JsResult load(String zipPath, File optDir) {
        JsResult r = new JsResult();
        File dir = null;
        try {
            File zip = new File(zipPath);
            if (!zip.exists() || zip.length() <= 0) {
                r.error = "插件包不存在或为空";
                return r;
            }

            // ---- 0. ★ v4.20.4：单个 .js 源文件 —— 自动包装成插件目录（源文件不动） ----
            if (zip.getName().toLowerCase().endsWith(".js")) {
                String code = readText(zip);
                String base = Utils.safe(stripExt(zip.getName()));
                File parent = optDir != null ? optDir : root();
                dir = new File(parent, base);
                if (dir.exists()) deleteDir(dir);
                if (!dir.mkdirs()) {
                    r.error = "无法创建插件目录";
                    return r;
                }
                r.dir = dir.getAbsolutePath();
                writeText(new File(dir, "script.js"), code);
                JSONObject mf = new JSONObject();
                try {
                    // ★ v4.21.3：显示名优先取脚本头注解 @name / @version
                    //   （LX 脚本规范头，如「@name 墨澜聚合音源」）。
                    //   文件名经 Utils.safe 会把中文冲成下划线，只能当兜底。
                    String dn = headerField(code, "name");
                    String dv = headerField(code, "version");
                    mf.put("name", dn != null ? dn : base);
                    mf.put("version", dv != null ? dv : "1.0");
                    mf.put("author", headerField(code, "author") == null
                            ? "" : headerField(code, "author"));
                    mf.put("description", LxRuntime.looksLikeLx(code)
                            ? "LX 音源脚本（App 运行时解析）" : "JS 单文件源");
                    JSONArray sa = new JSONArray();
                    sa.put("script.js");
                    mf.put("scripts", sa);
                } catch (Throwable ignored) {}
                writeText(new File(dir, "manifest.json"), mf.toString());
                return loadFromDir(dir, r);
            }

            // ---- 1. 解包到独立目录（用 zip 文件名做目录名，同一包重复导入即覆盖） ----
            String base = Utils.safe(stripExt(zip.getName()));
            File parent = optDir != null ? optDir : root();
            dir = new File(parent, base);
            if (dir.exists()) deleteDir(dir);
            if (!dir.mkdirs()) {
                r.error = "无法创建插件目录";
                return r;
            }
            r.dir = dir.getAbsolutePath();

            String unpackErr = unpack(zip, dir);
            if (unpackErr != null) {
                r.error = unpackErr;
                return r;
            }

            return loadFromDir(dir, r);
        } catch (Throwable t) {
            r.error = "加载异常：" + brief(t);
            return r;
        }
    }

    /**
     * 已解包目录 → 执行脚本 → 包装源。{@link #load} 与 {@link #loadInstalled} 的公共后半段。
     *
     * @param r 调用方已填好 {@code dir}，本方法负责后续全部字段
     */
    private static JsResult loadFromDir(File dir, JsResult r) {
        // ---- 2. 读 manifest ----
        File mf = new File(dir, "manifest.json");
        if (!mf.exists()) {
            r.error = "不是有效的 JS 插件包：缺少 manifest.json";
            return r;
        }
        JSONObject manifest = Json.obj(readText(mf));
        if (manifest == null) {
            r.error = "manifest.json 格式错误";
            return r;
        }
        r.pkgName = Json.s(manifest, "name");
        r.pkgVersion = Json.s(manifest, "version");
        r.pkgAuthor = Json.s(manifest, "author");
        if (r.pkgName.length() == 0) r.pkgName = dir.getName();

        JSONArray scripts = manifest.optJSONArray("scripts");
        if (scripts == null || scripts.length() == 0) {
            r.error = "manifest.json 里没有声明 scripts";
            return r;
        }

        // ---- 3. 逐脚本加载（同一 scope 内按序执行） ----
        // ★ v4.20.4：作用域级 host 工厂 —— registerSource 时立即挂 host，
        //   prelude 系插件（如红果原版）在加载期就读 registerSource(...).host。
        final String pkgId = r.pkgName == null ? dir.getName() : r.pkgName;
        final JsHostApi.Callback cb = r.callback;
        JsEngine.Scope scope = JsEngine.newScope(new JsEngine.HostFactory() {
            @Override
            public Object build(Context cx, Scriptable global) {
                return new JsHostApi(pkgId, "", cb).build(cx, global);
            }
        });
        List<String> names = new ArrayList<String>();
        List<String> codes = new ArrayList<String>();
        boolean lxMode = false;
        for (int i = 0; i < scripts.length(); i++) {
            String fn = scripts.optString(i, "");
            if (fn.length() == 0) continue;
            File f = new File(dir, fn);
            if (!f.exists()) {
                r.error = "manifest 里声明的脚本不存在：" + fn;
                return r;
            }
            if (f.length() > MAX_SCRIPT_BYTES) {
                r.error = "脚本过大（>2MB）：" + fn;
                return r;
            }
            String code = readText(f);
            // ★ v4.20.4：LX 音源脚本（如墨澜）→ 先装 lx 运行时再跑脚本
            if (!lxMode && LxRuntime.looksLikeLx(code)) {
                lxMode = true;
                LxRuntime.install(scope, r.pkgName, code);
            }
            names.add(fn);
            codes.add(code);
        }

        for (int i = 0; i < codes.size(); i++) {
            try {
                scope.run(codes.get(i), names.get(i));
            } catch (Throwable t) {
                r.error = "脚本执行失败：" + names.get(i) + " — " + brief(t);
                return r;
            }
        }

        // ---- 4. 包装成 Source ----
        List<Scriptable> raw = scope.sources();
        if (raw.isEmpty()) {
            // ★ v4.20.4：LX 脚本不调 registerSource —— 注册出 LX 音源
            if (lxMode && LxRuntime.hasRequestHandler(scope)) {
                try {
                    r.sources.add(new com.tingshu.app.source.LxSourceAdapter(
                            scope, r.pkgName, LxRuntime.initedJson(scope)));
                    r.ok = true;
                    return r;
                } catch (Throwable t) {
                    r.error = "LX 源初始化失败：" + brief(t);
                    return r;
                }
            }
            r.error = "插件没有注册出任何书源（脚本里没有调用 registerSource）";
            return r;
        }
        for (Scriptable s : raw) {
            try {
                JsSourceAdapter a = new JsSourceAdapter(r.pkgName, scope, s, r.callback);
                if (a.id() == null || a.id().length() == 0) continue;
                r.sources.add(a);
                r.ids.add(a.id());
            } catch (Throwable t) {
                Log.w(TAG, "包装源失败: " + brief(t));
            }
        }
        if (r.sources.isEmpty()) {
            r.error = "插件里的源都无法初始化";
            return r;
        }

        r.ok = true;
        return r;
    }

    /** 只做「探针」式校验，不落地：判断一个 zip 是不是 JS 插件包（用于导入分流） */
    public static boolean looksLikeJsPlugin(String zipPath) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(zipPath);
            Enumeration<? extends ZipEntry> es = zf.entries();
            boolean hasManifest = false;
            int n = 0;
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                String nm = e.getName();
                if (nm.startsWith("__MACOSX/") || nm.endsWith("/")) continue;
                if (++n > MAX_ENTRIES) return false;
                // 允许包一层目录：xxx/manifest.json
                if (nm.equals("manifest.json") || nm.endsWith("/manifest.json")) {
                    hasManifest = true;
                    // 校验内容确实有 scripts 字段，避免把普通 zip 误判
                    InputStream in = null;
                    try {
                        in = zf.getInputStream(e);
                        String body = new String(readAll(in), Charset.forName("UTF-8"));
                        JSONObject o = Json.obj(body);
                        if (o != null) {
                            JSONArray sa = o.optJSONArray("scripts");
                            if (sa != null && sa.length() > 0) return true;
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (in != null) try { in.close(); } catch (Throwable ignored) {}
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) {}
        }
    }

    // ==================== 解包 ====================

    /** @return null 表示成功，否则是错误文案 */
    private static String unpack(File zip, File dir) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            Enumeration<? extends ZipEntry> es = zf.entries();
            int n = 0;
            // 若所有条目都在同一个顶层目录下（如 pkg/xxx），解包时把这个前缀剥掉，
            // 否则 manifest.json 会藏在子目录里找不到。
            String prefix = commonPrefix(zf);
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (e.isDirectory()) continue;
                String nm = e.getName();
                if (nm.startsWith("__MACOSX/")) continue;
                if (prefix != null && nm.startsWith(prefix)) nm = nm.substring(prefix.length());
                if (nm.length() == 0) continue;
                if (++n > MAX_ENTRIES) return "包内文件数过多（>" + MAX_ENTRIES + "）";
                if (e.getSize() > MAX_SCRIPT_BYTES) return "包内文件过大（>2MB）：" + nm;

                // 防目录穿越：只允许相对路径，且不能有 ..
                String safe = nm.replace('\\', '/');
                if (safe.contains("..") || safe.startsWith("/")) continue;

                File out = new File(dir, safe);
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) continue;

                InputStream in = null;
                FileOutputStream fos = null;
                try {
                    in = zf.getInputStream(e);
                    fos = new FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int len;
                    long total = 0;
                    while ((len = in.read(buf)) > 0) {
                        total += len;
                        if (total > MAX_SCRIPT_BYTES) return "包内文件过大（>2MB）：" + nm;
                        fos.write(buf, 0, len);
                    }
                    fos.flush();
                } finally {
                    if (in != null) try { in.close(); } catch (Throwable ignored) {}
                    if (fos != null) try { fos.close(); } catch (Throwable ignored) {}
                }
            }
            return null;
        } catch (Throwable t) {
            return "解包失败：" + brief(t);
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 如果 zip 里所有文件都在同一个顶层目录下，返回那个前缀（含末尾 '/'）。
     * 这种包是「右键压缩文件夹」产生的，很常见，必须兼容。
     */
    private static String commonPrefix(ZipFile zf) {
        try {
            Enumeration<? extends ZipEntry> es = zf.entries();
            String p = null;
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (e.isDirectory()) continue;
                String nm = e.getName();
                if (nm.startsWith("__MACOSX/")) continue;
                int i = nm.indexOf('/');
                if (i < 0) return null;                 // 有顶层文件 → 没有公共前缀
                String top = nm.substring(0, i + 1);
                if (p == null) p = top;
                else if (!p.equals(top)) return null;   // 顶层目录不止一个
            }
            if (p == null) return null;
            // 再确认「除顶层目录外还有文件」——否则剥完就没了
            Enumeration<? extends ZipEntry> es2 = zf.entries();
            while (es2.hasMoreElements()) {
                ZipEntry e = es2.nextElement();
                if (e.isDirectory()) continue;
                String nm = e.getName();
                if (!nm.startsWith("__MACOSX/") && nm.length() > p.length()) return p;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 工具 ====================

    private static String readText(File f) {
        try {
            return new String(readAll(new java.io.FileInputStream(f)), Charset.forName("UTF-8"));
        } catch (Throwable t) {
            return "";
        }
    }

    /** ★ v4.20.4：写小文本（单 .js 包装用） */
    private static void writeText(File f, String s) {
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(s.getBytes(Charset.forName("UTF-8")));
            fo.flush();
            fo.close();
        } catch (Throwable ignored) {}
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        long total = 0;
        while ((len = in.read(buf)) > 0) {
            total += len;
            if (total > MAX_SCRIPT_BYTES) throw new IllegalStateException("文件过大");
            bos.write(buf, 0, len);
        }
        return bos.toByteArray();
    }

    /**
     * ★ v4.21.3：从脚本头部注释取 LX 规范注解（@name / @version / @author）。
     * 只扫前 4000 字符；取不到或值为 null 返回 null（调用方给兜底）。
     */
    private static String headerField(String code, String field) {
        if (code == null || field == null) return null;
        try {
            String head = code.substring(0, Math.min(code.length(), 4000));
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("@\\s*" + field + "\\s+([^\\r\\n*]+)")
                    .matcher(head);
            if (!m.find()) return null;
            String v = m.group(1).trim();
            return (v.length() == 0 || "null".equals(v)) ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stripExt(String name) {
        int i = name == null ? -1 : name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : (name == null ? "plugin" : name);
    }

    private static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        }
        d.delete();
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = "";
        if (m.length() > 160) m = m.substring(0, 160);
        return t.getClass().getSimpleName() + (m.length() > 0 ? ": " + m : "");
    }

    /** 清理半成品：上次导入失败留下的目录（只保留有 manifest.json 的） */
    public static void purgeBroken() {
        try {
            File[] ds = root().listFiles();
            if (ds == null) return;
            for (File d : ds) {
                if (!d.isDirectory()) continue;
                if (!new File(d, "manifest.json").exists()) deleteDir(d);
            }
        } catch (Throwable ignored) {}
    }

    /** 已装 JS 插件占用的磁盘字节数 */
    public static long totalSize() {
        return dirSize(root());
    }

    private static long dirSize(File d) {
        long n = 0;
        try {
            File[] fs = d.listFiles();
            if (fs == null) return 0;
            for (File f : fs) n += f.isDirectory() ? dirSize(f) : f.length();
        } catch (Throwable ignored) {}
        return n;
    }

    /** 供 PluginRepo 记录：JS 插件目录路径（与 jar 的「文件路径」语义一致） */
    public static String dirForZipName(String zipFileName) {
        return new File(root(), Utils.safe(stripExt(zipFileName))).getAbsolutePath();
    }
}
