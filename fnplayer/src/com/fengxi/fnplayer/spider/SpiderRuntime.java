package com.fengxi.fnplayer.spider;

import android.content.Context;

import com.fengxi.fnplayer.core.Net;
import com.fengxi.fnplayer.core.Utils;
import com.fengxi.fnplayer.data.Config;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dalvik.system.DexClassLoader;

/**
 * v2.13 内置 TVBox spider 运行时。
 *
 * <ul>
 *   <li>按配置里的 jar 地址下载（缓存到 cacheDir/spider/），DexClassLoader 加载；</li>
 *   <li>源类名：api 形如 {@code csp_XXX} → 尝试 {@code com.github.catvod.spider.XXX}，
 *       失败再按原始名加载；</li>
 *   <li>常见初始化入口 {@code com.github.catvod.spider.Init.init(Context)} 反射调用一次；</li>
 *   <li>加载器与实例按 jar+类名 全局缓存，避免每次点分类都重新下载/加载。</li>
 * </ul>
 */
public final class SpiderRuntime {

    private static final ConcurrentHashMap<String, DexClassLoader> LOADERS =
            new ConcurrentHashMap<String, DexClassLoader>();
    private static final ConcurrentHashMap<String, Object> SPIDERS =
            new ConcurrentHashMap<String, Object>();

    private static final Pattern SNIFF = Pattern.compile(
            "https?://[^\"'<>\\\\\\s]+\\.(?:m3u8|mp4|flv)[^\"'<>\\\\\\s]*");

    private SpiderRuntime() {}

    /** 当前源是 spider 类型时返回源对象（含 jar / api），否则 null。 */
    public static JSONObject currentSpiderSource() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(Config.sources());
            String cur = Config.curSourceId();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && cur.equals(s.optString("id"))
                        && "spider".equals(s.optString("type"))) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 兼容旧调用（无 ext）。 */
    public static synchronized com.github.catvod.crawler.Spider get(
            Context ctx, String jarUrl, String cls) throws Exception {
        return get(ctx, jarUrl, cls, "");
    }

    /**
     * v2.17：取（必要时创建）spider 实例。ext 是站点配置里的 extend 参数
     * （site_urls / token / cookie / base64 等），必须原样传给 init(Context,String)，
     * 否则花卷、玩偶、网盘类爬虫会因为缺参数返回空列表。
     */
    public static synchronized com.github.catvod.crawler.Spider get(
            Context ctx, String jarUrl, String cls, String ext) throws Exception {
        String c = cls == null ? "" : cls.trim();
        if (c.length() == 0) throw new Exception("源缺少接口类名（api 字段为空）");
        String clean = cleanJarUrl(jarUrl);
        String md5 = md5Of(jarUrl);
        String e = ext == null ? "" : ext;
        String key = clean + "|" + md5 + "|" + c + "|" + e;
        Object cached = SPIDERS.get(key);
        if (cached != null) return (com.github.catvod.crawler.Spider) cached;
        Context app = ctx.getApplicationContext();
        DexClassLoader cl = loader(app, clean, md5);
        Class<?> k = null;
        Throwable last = null;
        if (c.startsWith("csp_")) {
            try { k = cl.loadClass("com.github.catvod.spider." + c.substring(4)); }
            catch (Throwable t) { last = t; }
        }
        if (k == null) {
            try { k = cl.loadClass(c); }
            catch (Throwable t) { last = t; }
        }
        if (k == null || !com.github.catvod.crawler.Spider.class.isAssignableFrom(k)) {
            // v2.19：壳源桥接失败的专属提示（NoClassDefFoundError: DexNative）
            String hint = "";
            if (last != null) {
                String m = String.valueOf(last.getMessage());
                if (m.contains("DexNative"))
                    hint = "；该源是 Guard 加密壳，解密桥接未成功（设备不支持或壳版本过新），请换其它源";
            }
            throw new Exception("jar 里找不到爬虫类 " + c
                    + (last == null ? "" : "（" + Utils.brief(last) + "）") + hint);
        }
        Object inst = k.newInstance();
        // 通用初始化：Init.init(Context)（多数 jar 的全局初始化入口）
        try {
            Class<?> ic = cl.loadClass("com.github.catvod.spider.Init");
            for (Method m : ic.getDeclaredMethods()) {
                if (m.getName().equals("init") && m.getParameterTypes().length >= 1
                        && m.getParameterTypes()[0] == Context.class) {
                    m.setAccessible(true);
                    // 两参签名存在时先试两参（部分分支第二参是 extend），
                    // 失败再退回单参，避免不同 TVBox 分支的 Init 签名差异导致初始化被吞掉。
                    if (m.getParameterTypes().length >= 2) {
                        try { m.invoke(null, new Object[]{app, e}); }
                        catch (Throwable t2) { try { m.invoke(null, new Object[]{app}); } catch (Throwable ignored) {} }
                    } else {
                        m.invoke(null, new Object[]{app});
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {}
        // 子类覆写了哪个 init 就调哪个（两套签名在不同 TVBox 分支都存在）
        try {
            Method m1 = k.getMethod("init", Context.class);
            if (m1.getDeclaringClass() != com.github.catvod.crawler.Spider.class)
                m1.invoke(inst, app);
        } catch (Throwable ignored) {}
        try {
            Method m2 = k.getMethod("init", Context.class, String.class);
            if (m2.getDeclaringClass() != com.github.catvod.crawler.Spider.class)
                m2.invoke(inst, app, e);   // ★ v2.17：传真实 ext，不再传空串
        } catch (Throwable ignored) {}
        SPIDERS.put(key, inst);
        return (com.github.catvod.crawler.Spider) inst;
    }

    /**
     * v2.17：剥离 TVBox  spider 地址的校验/防缓存后缀。
     * 常见形态：
     *   xxx/getFile?id=abc#.png;3130dc...   → # 后是 fragment，HTTP 不发送，但 Java 会带进字符串
     *   xxx/xxx.jpg;md5;53e039fb...         → 必须剥掉，否则服务器返回 404 HTML
     *   xxx/xxx.jar;md5=abc                 → 同上
     * 之前原样请求导致 404 的 HTML 被当成 jar 缓存 → ClassNotFoundException。
     */
    public static String cleanJarUrl(String u) {
        if (u == null) return "";
        String s = u.trim();
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        int p = s.toLowerCase().indexOf(";md5");
        if (p > 0) s = s.substring(0, p);
        return s.trim();
    }

    /** 下载（带缓存）并加载 jar。v2.17：校验 HTTP 状态与 dex 包格式，md5 尾参与缓存名。 */
    private static DexClassLoader loader(Context app, String jarUrl, String md5) throws Exception {
        if (jarUrl == null || jarUrl.length() == 0)
            throw new Exception("该爬虫源没有 jar 地址（配置缺少 spider/jar 字段）");
        File dir = new File(app.getCacheDir(), "spider");
        dir.mkdirs();
        File f;
        if (jarUrl.startsWith("img+")) {
            // EasyBox/FongMi 的 img+ 形态：jar 内容以 base64 直接内嵌在配置里
            try {
                byte[] bytes = android.util.Base64.decode(
                        jarUrl.substring(4), android.util.Base64.DEFAULT);
                if (bytes.length < 64)
                    throw new Exception("img+ jar 内容过短（" + bytes.length + " 字节）");
                f = new File(dir, "img_" + Integer.toHexString(jarUrl.hashCode()) + ".jar");
                writeBytes(f, bytes);
            } catch (java.lang.IllegalArgumentException ie) {
                throw new Exception("img+ jar 的 base64 解码失败");
            }
        } else {
            DexClassLoader hit = LOADERS.get(jarUrl + "|" + md5);
            if (hit != null) return hit;
            // md5 尾参与文件名：配置换了 jar（同 URL 新 md5）时缓存自动失效重新下载
            f = new File(dir, "sp_" + Integer.toHexString((jarUrl + "|" + md5).hashCode()) + ".jar");
            if (!f.exists() || f.length() < 64) {
                Map<String, String> h = new HashMap<String, String>();
                h.put("User-Agent", Net.UA);
                Net.Resp resp = Net.getBytes(jarUrl, h, true, 30000, 96 * 1024 * 1024);
                // ★ v2.17：以前只判断 bytes.length>=64，404 的 HTML（957 字节）会过关，
                // 被当作 jar 交给 DexClassLoader → 截图里的 ClassNotFoundException。
                String why = "";
                if (resp.error.length() > 0) why = resp.error;
                else if (resp.code >= 400 || resp.code == 0) why = "HTTP " + resp.code;
                else if (resp.bytes == null || resp.bytes.length < 64) why = "内容为空";
                if (why.length() > 0) {
                    f.delete();
                    throw new Exception("爬虫 jar 下载失败：" + why + "（" + jarUrl + "）");
                }
                writeBytes(f, resp.bytes);
            }
        }
        // ★ v2.17：缓存文件也校验，旧版留下的 HTML 假 jar 会被删掉重新下载
        try { assertDexJar(f); }
        catch (Exception ex) { f.delete(); throw ex; }
        File odex = new File(dir, "odex");
        odex.mkdirs();
        // ★ v2.17：不少 spider 包把 .so / .guard / 配置资源塞在 jar 的 assets 里
        // （饭太硬实测含 ftyguard_v7.so、ftyshinidie.guard）。TVBox 兼容层会
        // 把这些 so 解出来作为 native 库路径，并把 jar 挂进 AssetManager，
        // 否则 DexClassLoader 只拿到 classes.dex，爬虫运行时读 guard 文件必失败。
        boolean guard = extractNativeLibs(f, odex);
        addAssetPath(app, f);
        // ★ v2.19：Guard 加密壳（饭太硬/王二小/嗷呜线）。stub 靠宿主的
        // com.github.catvod.spider.DexNative 桥取解密后的 loader，桥的实现
        // 就是壳自带 so 通过 JNI_OnLoad RegisterNatives 绑定的 —— 必须按
        // 设备 ABI 预加载对应 so（v7=32位，v8=64位），且在 DexClassLoader
        // 创建之前，保证 stub 解析 DexNative 时类与绑定都已就绪。
        if (guard) preloadGuardSo(odex);
        DexClassLoader cl;
        try {
            cl = new DexClassLoader(f.getAbsolutePath(), odex.getAbsolutePath(),
                    odex.getAbsolutePath(), SpiderRuntime.class.getClassLoader());
        } catch (Throwable t) {
            throw new Exception("爬虫 jar 加载失败（可能不是 dex 格式）：" + Utils.brief(t));
        }
        LOADERS.put(jarUrl + "|" + md5, cl);
        return cl;
    }

    /** TVBox jar 地址尾 ;md5;<hash>：hash 变化 → 缓存失效重新下载。 */
    public static String md5Of(String u) {
        if (u == null) return "";
        int i = u.toLowerCase().indexOf(";md5;");
        return i >= 0 ? u.substring(i + 5).trim() : "";
    }

    private static void writeBytes(File f, byte[] bytes) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(bytes);
        fo.close();
    }

    private static void copyFile(File src, File dst) {
        try {
            java.io.FileInputStream fi = new java.io.FileInputStream(src);
            FileOutputStream fo = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int len;
            while ((len = fi.read(buf)) > 0) fo.write(buf, 0, len);
            fo.close();
            fi.close();
        } catch (Throwable ignored) {}
    }

    /**
     * 把 jar 里的 .so 平铺解到 odex 目录，供 DexClassLoader 作 native 库搜索路径。
     * v2.19：同时写 {@code lib} 前缀副本（System.loadLibrary 需要 libxxx.so 命名，
     * 壳 stub 可能用 loadLibrary("ftyguard_v8") / loadLibrary("DexNative") 任一形式）。
     *
     * @return jar 是否是 Guard 加密壳（assets 里有 *guard* / *shinidie* so 或 .guard 载荷）
     */
    private static boolean extractNativeLibs(File jar, File out) {
        boolean guard = false;
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(jar);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            java.util.List<String> soNames = new java.util.ArrayList<String>();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory()) {
                    if (!guard && (n.contains("shinidie") || n.toLowerCase().contains("guard"))) guard = true;
                    continue;
                }
                String low = n.toLowerCase();
                if (low.endsWith(".guard") || low.contains("shinidie")) guard = true;
                if (!n.endsWith(".so")) continue;
                soNames.add(n);
                guard = true;
            }
            for (String n : soNames) {
                String flat = n.substring(n.lastIndexOf('/') + 1);
                writeZipEntry(zf, n, new File(out, flat));
                if (!flat.startsWith("lib")) writeZipEntry(zf, n, new File(out, "lib" + flat));
            }
            // 部分壳 stub 直接 loadLibrary("DexNative")：单 so 壳再补一个别名
            if (soNames.size() == 1) {
                String only = soNames.get(0);
                String flat = only.substring(only.lastIndexOf('/') + 1);
                if (!flat.equals("DexNative.so")) {
                    File alias = new File(out, "libDexNative.so");
                    File src = new File(out, flat);
                    if (src.exists() && !alias.exists()) copyFile(src, alias);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) {}
        }
        return guard;
    }

    private static void writeZipEntry(java.util.zip.ZipFile zf, String name, File dst) {
        try {
            if (dst.exists() && dst.length() > 0) return;
            java.io.InputStream in = zf.getInputStream(zf.getEntry(name));
            if (in == null) return;
            java.io.FileOutputStream fo = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) fo.write(buf, 0, len);
            fo.close();
            in.close();
        } catch (Throwable ignored) {}
    }

    /** v2.19：按设备 ABI 预加载壳 so，让 JNI_OnLoad 把 natives 绑到宿主 DexNative 桥上。 */
    private static void preloadGuardSo(File dir) {
        boolean is64 = android.os.Build.SUPPORTED_64_BIT_ABIS != null
                && android.os.Build.SUPPORTED_64_BIT_ABIS.length > 0;
        File[] files = dir.listFiles();
        if (files == null) return;
        File best = null;
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".so") || n.equals("libDexNative.so")) continue;
            boolean v8 = n.contains("v8");
            if (is64 && v8) { best = f; break; }
            if (!is64 && !v8) { best = f; break; }
            if (best == null) best = f;   // 没有版本后缀的兜底
        }
        if (best == null) return;
        try { System.load(best.getAbsolutePath()); }
        catch (Throwable t) {
            android.util.Log.w("SpiderRuntime", "guard so 加载失败: " + best.getName()
                    + " " + t);
        }
    }

    /** TVBox 标准做法：把 jar 加入 AssetManager，爬虫才能 open("assets/xxx.guard")。 */
    private static void addAssetPath(Context app, File jar) {
        try {
            android.content.res.AssetManager am = app.getAssets();
            java.lang.reflect.Method m = am.getClass().getMethod("addAssetPath", String.class);
            m.setAccessible(true);
            m.invoke(am, jar.getAbsolutePath());
        } catch (Throwable ignored) {}
    }

    /** 必须是 zip 且内含 classes*.dex，否则不是 TVBox spider 包。 */
    private static void assertDexJar(File f) throws Exception {
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(f);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            boolean dex = false;
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.endsWith(".dex")) { dex = true; break; }
            }
            if (!dex) throw new Exception("下载到的不是爬虫包（zip 内没有 classes.dex，"
                    + "多半是配置站返回了网页/图片）");
        } catch (java.io.IOException ioe) {
            throw new Exception("下载到的不是爬虫包（不是 zip/dex 格式，"
                    + "多半是 404 网页）：" + Utils.brief(ioe));
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) {}
        }
    }

    /** playerContent 返回的 header JSON → 请求头 Map。 */
    public static Map<String, String> headerMap(JSONObject h) {
        Map<String, String> out = new HashMap<String, String>();
        if (h == null) return out;
        try {
            java.util.Iterator<String> it = h.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = h.optString(k, "");
                if (k.length() > 0 && v.length() > 0) out.put(k, v);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** parse=1 时请求解析页并嗅探 m3u8/mp4 直链。拿不到返回空串。 */
    public static String sniff(String pageUrl, Map<String, String> headers) {
        if (pageUrl == null || pageUrl.length() == 0) return "";
        try {
            Map<String, String> h = headers == null
                    ? new HashMap<String, String>() : headers;
            if (!h.containsKey("User-Agent")) h.put("User-Agent", Net.UA);
            Net.Resp resp = Net.get(pageUrl, h, true, 10000);
            if (resp.body.length() > 0) {
                Matcher m = SNIFF.matcher(resp.body);
                if (m.find()) return m.group().replace("\\/", "/");
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** 源播放类名（api）转 url 编码的工具，避免调用方重复写。 */
    public static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Throwable t) { return s == null ? "" : s; }
    }
}
