package com.tingshu.app.core;

import com.tingshu.app.source.Source;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import dalvik.system.DexClassLoader;

/**
 * jar 插件书源加载器。
 *
 * <p>支持两种插件格式：
 *
 * <h3>A. 宿主原生格式（优先）</h3>
 * 入口类实现 {@link Source} 接口。可通过 MANIFEST 的 {@code Source-Entry} 声明，
 * 也可由加载器枚举 dex 类名自动发现。
 *
 * <h3>B. 原版「我的听书」格式（兼容）</h3>
 * 包里含 {@code com.github.eprendre.sources_by_xxx.SourceEntry}，提供
 * {@code @JvmStatic getSources(): List<TingShu>}。加载器反射调用它拿到 TingShu
 * 列表，再用 {@link com.tingshu.app.plugin.TingShuAdapter} 包装成宿主 Source。
 * 这是为了让用户已有的 eprendre 生态书源 jar 能直接导入使用。
 *
 * <p>安全边界：
 * <ul>
 *   <li>只从应用私有目录加载</li>
 *   <li>全程 try/catch，坏 jar 不会让 App 崩溃，只返回错误信息</li>
 * </ul>
 */
public final class JarLoader {

    private JarLoader() {}

    /**
     * ★ v4.17.0 宿主插件 API 版本（#26 插件标准化）。
     *
     * <p>版本语义见 docs/plugin-spec.md。规则很简单：
     * <ul>
     *   <li>插件 MANIFEST 可声明 {@code Fx-Api-Version: N}（缺省视为 1）；</li>
     *   <li>N &le; {@link #SUPPORTED_API} → 正常加载（向后兼容）；</li>
     *   <li>N &gt; {@link #SUPPORTED_API} → <b>明确拒绝</b>，提示升级 App。
     *       这是酷我事件教过的事：插件按更新的宿主约定写的，硬加载的结果
     *       是运行期各种 IncompatibleClassChangeError 被插件自己 catch 吞掉，
     *       表现成"源是空的"这种最难查的假象。宁可入口就拦。</li>
     * </ul>
     */
    public static final int SUPPORTED_API = 1;

    /** 加载结果 */
    public static class JarResult {
        public boolean ok;
        public String error = "";
        /** 加载到的源（可能一个 jar 提供多个源） */
        public List<Source> sources = new ArrayList<Source>();
        /** 源 id 列表（与 sources 对应，方便存库） */
        public List<String> ids = new ArrayList<String>();
        /** 识别出的插件格式："native" / "eprendre" / "" */
        public String format = "";
        /**
         * ★ v3.3：第一条失败原因（诊断用）。
         *
         * <p>以前 tryAddEprendre / tryAdd 里的 catch 全是静默 return，
         * 用户导入一个「缺宿主类」的插件时只会看到「没有找到可用的书源」，
         * 完全无从判断是格式不对、缺类、还是源本身失效。现在把第一条
         * 异常信息留下来，导入失败时回显给用户。
         */
        public String firstError = "";
        /** ★ v3.3：缺失的宿主类名（去重），用于精准提示 */
        public final List<String> missingClasses = new ArrayList<String>();
        /** ★ v4.17.0 插件声明的 API 版本（未声明 = 1） */
        public int apiVersion = 1;
    }

    /**
     * 从 jar 文件加载书源。
     *
     * @param jarPath jar 的真实文件路径（必须在私有目录，DexClassLoader 要求）
     * @param optDir  dex 优化输出目录（用 Context.getDir("pluginopt", 0)）
     */
    public static JarResult load(String jarPath, File optDir) {
        JarResult r = new JarResult();
        if (jarPath == null || jarPath.length() == 0) {
            r.error = "jar 路径为空";
            return r;
        }
        File f = new File(jarPath);
        if (!f.exists() || f.length() == 0) {
            r.error = "jar 文件不存在或为空";
            return r;
        }
        if (optDir == null) {
            r.error = "优化目录为空";
            return r;
        }
        if (!optDir.exists()) optDir.mkdirs();

        // ★ v4.17.0 版本协商：插件声明的 API 版本高于宿主支持范围 → 入口即拒。
        String verRaw = readManifestAttr(f, "Fx-Api-Version");
        int apiVer = 1;
        if (verRaw != null) {
            try { apiVer = Integer.parseInt(verRaw.trim()); }
            catch (Throwable t) {
                r.error = "插件 MANIFEST 的 Fx-Api-Version 不是数字：\"" + verRaw + "\"";
                return r;
            }
        }
        r.apiVersion = apiVer;
        if (apiVer > SUPPORTED_API) {
            r.error = "该插件要求宿主 API v" + apiVer + "，当前 App 最高支持 v"
                    + SUPPORTED_API + "，请升级 App 后再导入。"
                    + "（强行加载会出现源为空/播放失败等不可预期的行为）";
            return r;
        }

        // ★ v3.9.9：用户可能把「外层 zip」直接导入（源作者常把 jar 再包一层 zip）。
        //   zip 里没有 classes*.dex 时尝试解出内层 jar；有 dex 则原样使用。
        String unwrapped = unwrapZipIfNested(f, optDir);
        if (unwrapped != null) {
            jarPath = unwrapped;
            f = new File(jarPath);
        }

        ClassLoader parent = JarLoader.class.getClassLoader();
        DexClassLoader cl;
        try {
            cl = new DexClassLoader(jarPath, optDir.getAbsolutePath(), null, parent);
        } catch (Throwable t) {
            r.error = "无法加载 jar：" + msg(t);
            return r;
        }

        // ---- 策略 1：MANIFEST.MF 的 Source-Entry（宿主原生格式）----
        List<String> entries = readManifestEntries(f);
        for (int i = 0; i < entries.size(); i++) {
            tryAdd(r, cl, entries.get(i));
        }
        if (!r.sources.isEmpty()) {
            r.ok = true;
            r.format = "native";
            return r;
        }

        // ---- 策略 2：原版 eprendre 格式（SourceEntry.getSources() -> List<TingShu>）----
        List<String> classNames = dexClassNames(cl);
        // ★ v3.9.9：Android 10+ 之后 BaseDexClassLoader 的 Element 不再持有 DexFile
        //   字段（改为 cookie），上面的反射枚举会静默返回空表 —— eprendre 插件
        //   全部找不到 SourceEntry，报「jar 里没有找到可用的书源」。
        //   兜底：直接解析 jar 里 classes*.dex 的字符串池提取类名（纯 Java，零系统依赖）。
        if (classNames.isEmpty()) {
            classNames = jarClassNames(jarPath);
        }

        // 2a. 首选：找 SourceEntry（原版约定入口，一个 jar 通常只有一个）
        //     ★ v3.3：不再要求包名前缀，只看类名后缀 —— 不同插件的包名结构
        //     可能是 com.github.eprendre.sources_by_xxx / com.github.eprendre.<pkg>，
        //     甚至自定义域名，用后缀判定覆盖面最广。
        for (int i = 0; i < classNames.size(); i++) {
            String n = classNames.get(i);
            if (n.startsWith("com.tingshu.app.")) continue;
            if (!n.endsWith("SourceEntry")) continue;
            tryAddEprendre(r, cl, n);
        }
        if (!r.sources.isEmpty()) {
            r.ok = true;
            r.format = "eprendre";
            return r;
        }

        // 2b. 兜底：没有 SourceEntry 时，直接枚举 TingShu 子类逐个实例化。
        //     ★ v3.3 新增。有些插件（或插件改动版）不带入口类，
        //     但每个源类本身都能 newInstance()。
        for (int i = 0; i < classNames.size(); i++) {
            String n = classNames.get(i);
            if (n.startsWith("com.tingshu.app.")) continue;
            if (n.indexOf('$') >= 0) continue;         // 跳过内部类/匿名类
            if (n.startsWith("com.github.eprendre.tingshu.")) continue; // SDK 自身
            tryAddEprendreSource(r, cl, n);
        }
        if (!r.sources.isEmpty()) {
            r.ok = true;
            r.format = "eprendre";
            return r;
        }

        // ---- 策略 3：枚举 dex 全部类名，找宿主 Source 实现 ----
        for (int i = 0; i < classNames.size(); i++) {
            // 接口/常见支撑类跳过，减少 Class.forName 的开销
            String n = classNames.get(i);
            if (n.startsWith("com.tingshu.app.")) continue;   // 插件不应复用宿主包名
            if (n.startsWith("com.github.eprendre.")) continue; // 原版 API 类，不是源本身
            tryAdd(r, cl, n);
        }

        if (r.sources.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("jar 里没有找到可用的书源。\n支持的格式：\n")
              .append("① 实现 com.tingshu.app.source.Source 接口，并在 MANIFEST.MF 声明 ")
              .append("Source-Entry: 完整类名\n")
              .append("② 原版「我的听书」插件（含 SourceEntry.getSources()）");
            // ★ v3.3：把真实失败原因带出来，而不是让用户猜
            if (!r.missingClasses.isEmpty()) {
                sb.append("\n\n检测到插件需要的宿主类缺失：");
                for (int i = 0; i < r.missingClasses.size() && i < 6; i++) {
                    sb.append("\n · ").append(r.missingClasses.get(i));
                }
                sb.append("\n（这是插件与宿主 SDK 版本不匹配，升级 App 后再试）");
            } else if (r.firstError.length() > 0) {
                sb.append("\n\n首次失败原因：").append(r.firstError);
            }
            r.error = sb.toString();
            return r;
        }
        r.ok = true;
        r.format = "native";
        return r;
    }

    /**
     * ★ v3.3：兜底路径 —— 直接把一个类当作 {@code TingShu} 子类实例化并包装。
     *
     * <p>用于插件没带 {@code SourceEntry} 入口的场景。判定方式是
     * 「父类链上出现 com.github.eprendre.tingshu.sources.TingShu」——
     * 只按类名猜会误伤，按继承关系判定最准。
     */
    private static void tryAddEprendreSource(JarResult r, ClassLoader cl, String className) {
        if (className == null || className.length() == 0) return;
        Class<?> c;
        try {
            c = Class.forName(className, false, cl);
        } catch (Throwable t) {
            noteError(r, t);
            return;
        }
        if (c.isInterface() || java.lang.reflect.Modifier.isAbstract(c.getModifiers())) return;
        // 必须是 TingShu 的子类
        boolean isTingShu = false;
        Class<?> k = c;
        while (k != null) {
            if ("com.github.eprendre.tingshu.sources.TingShu".equals(k.getName())) {
                isTingShu = true;
                break;
            }
            k = k.getSuperclass();
        }
        if (!isTingShu) return;
        // 触发链接（此时才会解析 super/iface，缺类在这里暴露）
        Object ts;
        try {
            ts = c.newInstance();
        } catch (Throwable t) {
            noteError(r, unwrap(t));
            return;
        }
        try {
            com.tingshu.app.plugin.TingShuAdapter ad =
                    new com.tingshu.app.plugin.TingShuAdapter(ts);
            String id = ad.id();
            if (id == null || id.length() == 0 || "ep_".equals(id)) return;
            for (int i = 0; i < r.ids.size(); i++) {
                if (r.ids.get(i).equals(id)) return;
            }
            r.sources.add(ad);
            r.ids.add(id);
        } catch (Throwable t) {
            noteError(r, unwrap(t));
        }
    }

    /**
     * 尝试用原版 eprendre 格式加载：反射调用 {@code SourceEntry.getSources()}，
     * 把返回的 {@code List<TingShu>} 逐个包装成宿主 Source。
     *
     * <p>用反射而不是直接 import，是因为原版类由插件自带/宿主 host 包提供，
     * 加载器不该在编译期依赖它们。
     */
    private static void tryAddEprendre(JarResult r, ClassLoader cl, String className) {
        if (className == null || className.length() == 0) return;
        Class<?> c;
        try {
            c = Class.forName(className, true, cl);
        } catch (Throwable t) {
            noteError(r, t);
            return;
        }
        // 找 getSources()（可能是 @JvmStatic 静态方法，也可能在 INSTANCE 上）
        Object result = null;
        try {
            java.lang.reflect.Method m = c.getMethod("getSources");
            java.lang.reflect.Modifier mod = null;
            int mm = m.getModifiers();
            boolean isStatic = java.lang.reflect.Modifier.isStatic(mm);
            if (isStatic) {
                result = m.invoke(null);
            } else {
                java.lang.reflect.Field inst = null;
                try {
                    inst = c.getField("INSTANCE");
                } catch (Throwable ignored) {}
                Object target = inst == null ? null : inst.get(null);
                if (target == null) {
                    try {
                        target = c.newInstance();
                    } catch (Throwable ignored) {}
                }
                if (target != null) result = m.invoke(target);
            }
        } catch (Throwable t) {
            // 反射包了一层 InvocationTargetException，真实原因在 cause 里
            noteError(r, unwrap(t));
            return;
        }
        if (!(result instanceof java.util.List)) return;

        java.util.List<?> list = (java.util.List<?>) result;
        for (int i = 0; i < list.size(); i++) {
            Object ts = list.get(i);
            if (ts == null) continue;
            try {
                com.tingshu.app.plugin.TingShuAdapter ad =
                        new com.tingshu.app.plugin.TingShuAdapter(ts);
                String id = ad.id();
                if (id == null || id.length() == 0 || "ep_".equals(id)) continue;
                boolean dup = false;
                for (String exist : r.ids) {
                    if (exist.equals(id)) { dup = true; break; }
                }
                if (dup) continue;
                r.sources.add(ad);
                r.ids.add(id);
            } catch (Throwable t) {
                // ★ v3.3：单个源构造失败也记录（以前整段静默）
                noteError(r, unwrap(t));
            }
        }
    }

    /**
     * ★ v3.3：记录第一条失败原因，并从 {@code NoClassDefFoundError} 的 message
     * 里提取缺失的宿主类名（Dalvik 的 message 形如
     * {@code Lcom/github/eprendre/tingshu/sources/ILogin;}）。
     */
    private static void noteError(JarResult r, Throwable t) {
        if (t == null) return;
        String m = t.getMessage();
        if (r.firstError.length() == 0) {
            r.firstError = (m == null || m.length() == 0)
                    ? t.getClass().getName() : (t.getClass().getSimpleName() + ": " + m);
        }
        // 提取形如 Lcom/xxx/Yyy; 的缺失类名
        if (m != null) {
            java.util.regex.Matcher mt =
                    java.util.regex.Pattern.compile("L[\\w/$]+;").matcher(m);
            while (mt.find()) {
                String cls = mt.group().replace('/', '.');
                cls = cls.substring(1, cls.length() - 1);   // 去 L 和 ;
                if (cls.indexOf('.') <= 0) continue;
                if (!r.missingClasses.contains(cls)) r.missingClasses.add(cls);
            }
        }
    }

    /** 剥掉反射包装，拿到真实异常 */
    private static Throwable unwrap(Throwable t) {
        if (t instanceof java.lang.reflect.InvocationTargetException
                && ((java.lang.reflect.InvocationTargetException) t).getCause() != null) {
            return ((java.lang.reflect.InvocationTargetException) t).getCause();
        }
        if (t instanceof ExceptionInInitializerError
                && ((ExceptionInInitializerError) t).getException() != null) {
            return ((ExceptionInInitializerError) t).getException();
        }
        return t;
    }

    /** 尝试按类名实例化一个 Source 实现 */
    private static void tryAdd(JarResult r, ClassLoader cl, String className) {
        if (className == null || className.length() == 0) return;
        Class<?> c;
        try {
            c = Class.forName(className, false, cl);
        } catch (Throwable t) {
            noteError(r, unwrap(t));
            return;
        }
        if (c.isInterface() || java.lang.reflect.Modifier.isAbstract(c.getModifiers())) return;
        if (!Source.class.isAssignableFrom(c)) return;
        try {
            Object o = c.newInstance();
            Source s = (Source) o;
            if (s.id() == null || s.id().length() == 0) return;
            // 同一个 jar 里重复 id 去重
            for (String exist : r.ids) {
                if (exist.equals(s.id())) return;
            }
            r.sources.add(s);
            r.ids.add(s.id());
        } catch (Throwable t) {
            noteError(r, unwrap(t));
        }
    }

    /** 读 MANIFEST.MF 里的 Source-Entry（支持多行，逗号分隔多个类名） */
    private static List<String> readManifestEntries(File jar) {
        String v = readManifestAttr(jar, "Source-Entry");
        List<String> out = new ArrayList<String>();
        if (v == null || v.length() == 0) return out;
        String[] parts = v.split("[,\\s]+");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.length() > 0) out.add(p);
        }
        return out;
    }

    /**
     * ★ v4.17.0：读 MANIFEST.MF 的任意属性值（含标准折行续行，续行以空格开头）。
     * 没有该属性或 IO 异常返回 null（当无属性处理）。
     */
    private static String readManifestAttr(File jar, String attrName) {
        if (jar == null || attrName == null) return null;
        JarFile jf = null;
        try {
            jf = new JarFile(jar);
            JarEntry mf = jf.getJarEntry("META-INF/MANIFEST.MF");
            if (mf == null) return null;
            InputStream in = jf.getInputStream(mf);
            byte[] buf = readAll(in, 64 * 1024);
            if (in != null) in.close();
            if (buf == null) return null;
            String txt = new String(buf, "UTF-8");
            String key = attrName + ":";
            String[] lines = txt.replace("\r\n", "\n").split("\n");
            StringBuilder val = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                String L = lines[i];
                if (L.startsWith(key)) {
                    val.setLength(0);
                    val.append(L.substring(key.length()).trim());
                } else if (val.length() > 0 && L.length() > 1 && L.charAt(0) == ' ') {
                    val.append(L.substring(1).trim());
                } else if (val.length() > 0) {
                    break;   // 下一个属性了
                }
            }
            return val.length() == 0 ? null : val.toString();
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                if (jf != null) jf.close();
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 枚举 dex 里的全部类名。
     * 反射 BaseDexClassLoader.pathList -> dexElements[] -> dexFile.entries()。
     * 不同 API 版本字段可能缺失或被限制，失败返回空列表（还有 MANIFEST 兜底在上层）。
     */
    private static List<String> dexClassNames(ClassLoader cl) {
        List<String> out = new ArrayList<String>();
        try {
            Field f = findField(cl.getClass(), "pathList");
            if (f == null) return out;
            f.setAccessible(true);
            Object pathList = f.get(cl);
            if (pathList == null) return out;

            Field fe = findField(pathList.getClass(), "dexElements");
            if (fe == null) return out;
            fe.setAccessible(true);
            Object[] elements = (Object[]) fe.get(pathList);
            if (elements == null) return out;

            for (int i = 0; i < elements.length; i++) {
                Field fd = findField(elements[i].getClass(), "dexFile");
                if (fd == null) continue;
                fd.setAccessible(true);
                Object dex = fd.get(elements[i]);
                if (dex == null) continue;
                // DexFile.entries() 是 public 的，直接调
                if (dex instanceof dalvik.system.DexFile) {
                    Enumeration<String> names = ((dalvik.system.DexFile) dex).entries();
                    while (names != null && names.hasMoreElements()) {
                        out.add(names.nextElement());
                    }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * ★ v3.9.9：导入文件是「zip 套 jar」时解出内层 jar。
     *
     * <p>判定：zip 里有 classes*.dex → 本身就是插件包，原样返回 null（用原文件）；
     * 没有.dex 但存在 .jar 条目 → 解出第一个 .jar 到 optDir，返回新路径。
     * 任何异常都返回 null，不影响主流程。
     */
    private static String unwrapZipIfNested(File f, File optDir) {
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(f);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            String nested = null;
            while (en != null && en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String nm = e.getName();
                if (nm.startsWith("classes") && nm.endsWith(".dex")) {
                    return null;   // 本身就是 dex 插件包
                }
                if (nested == null && nm.endsWith(".jar") && !nm.contains("/")
                        && !nm.startsWith("META-INF/")) {
                    nested = nm;
                }
            }
            if (nested == null) return null;
            java.io.InputStream in = zf.getInputStream(zf.getEntry(nested));
            File out = new File(optDir, "unwrapped_" + System.currentTimeMillis() + ".jar");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            } finally {
                try { fo.close(); } catch (Throwable ignored) {}
                try { in.close(); } catch (Throwable ignored) {}
            }
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * ★ v3.9.9：不依赖系统内部 API 的类名枚举 —— 直接解析 jar 里
     * classes.dex / classes2.dex ... 的类型表。
     *
     * <p>背景见 load() 里的注释：Android 10+ 拿不到 DexPathList.Element.dexFile，
     * {@link #dexClassNames} 静默失效。这里读取 dex 自身结构：
     * header 的 string_ids 指向 uleb128 长度前缀 + MUTF-8 描述符，
     * type_ids 是 string 索引 —— 只取形如 {@code Lcom/x/Y;} 的类描述符。
     *
     * <p> dex 格式参考 AOSP 《Dalvik Executable format》；只读不写，
     * 解析失败返回已解析到的部分（或空表），绝不抛出。
     */
    private static List<String> jarClassNames(String jarPath) {
        List<String> out = new ArrayList<String>();
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(jarPath);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            List<byte[]> dexList = new ArrayList<byte[]>();
            while (en != null && en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String nm = e.getName();
                if (!nm.startsWith("classes") || !nm.endsWith(".dex")) continue;
                java.io.InputStream in = zf.getInputStream(e);
                try {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    dexList.add(bo.toByteArray());
                } finally {
                    try { in.close(); } catch (Throwable ignored) {}
                }
            }
            for (int d = 0; d < dexList.size(); d++) {
                parseDexClassNames(dexList.get(d), out);
            }
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /** 解析单个 dex 字节块，把类描述符（L 开头 ; 结尾）转成点分名加进 out */
    private static void parseDexClassNames(byte[] dex, List<String> out) {
        if (dex == null || dex.length < 0x48) return;
        try {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(dex)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int stringIdsSize = b.getInt(0x38);
            int stringIdsOff = b.getInt(0x3C);
            int typeIdsSize = b.getInt(0x40);
            int typeIdsOff = b.getInt(0x44);
            if (stringIdsSize <= 0 || stringIdsOff <= 0 || typeIdsSize <= 0 || typeIdsOff <= 0) return;
            if ((long) stringIdsOff + stringIdsSize * 4L > dex.length) return;
            if ((long) typeIdsOff + typeIdsSize * 4L > dex.length) return;

            // string_ids[i] = string data offset
            int[] strOff = new int[stringIdsSize];
            for (int i = 0; i < stringIdsSize; i++) {
                strOff[i] = b.getInt(stringIdsOff + i * 4);
            }
            // type_ids[i] = descriptor string index
            for (int i = 0; i < typeIdsSize; i++) {
                int sIdx = b.getInt(typeIdsOff + i * 4);
                if (sIdx < 0 || sIdx >= stringIdsSize) continue;
                String desc = readDexString(dex, strOff[sIdx]);
                if (desc == null || desc.length() < 3) continue;
                if (desc.charAt(0) != 'L' || desc.charAt(desc.length() - 1) != ';') continue;
                out.add(desc.substring(1, desc.length() - 1).replace('/', '.'));
            }
        } catch (Throwable ignored) {}
    }

    /** 读 dex 字符串：uleb128 长度 + MUTF-8 字节（只关心可打印 ASCII 描述符） */
    private static String readDexString(byte[] dex, int offset) {
        try {
            int p = offset;
            int len = 0;          // uleb128（实际字符数，这里只取低 28 位足够）
            int shift = 0;
            while (p < dex.length) {
                int one = dex[p++] & 0xFF;
                len |= (one & 0x7F) << shift;
                if ((one & 0x80) == 0) break;
                shift += 7;
                if (shift > 21) return null;
            }
            if (len <= 0 || p + len > dex.length) return null;
            // MUTF-8：对类描述符（ASCII）可直接按字节取，遇 0 结束
            StringBuilder sb = new StringBuilder(len);
            int consumed = 0;
            while (p < dex.length && consumed < len) {
                int c = dex[p++] & 0xFF;
                if (c == 0) break;
                if (c < 0x80) {
                    sb.append((char) c);
                    consumed++;
                } else if ((c & 0xE0) == 0xC0 && p < dex.length) {
                    sb.append((char) (((c & 0x1F) << 6) | (dex[p++] & 0x3F)));
                    consumed++;
                } else if ((c & 0xF0) == 0xE0 && p + 1 < dex.length) {
                    sb.append((char) (((c & 0x0F) << 12)
                            | ((dex[p++] & 0x3F) << 6) | (dex[p++] & 0x3F)));
                    consumed++;
                } else {
                    return null;  // 非法序列
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 沿类层级向上找字段声明 */
    private static Field findField(Class<?> c, String name) {
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream in, int limit) {
        if (in == null) return null;
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > limit) return null;
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.length() == 0) ? t.getClass().getSimpleName() : m;
    }
}
