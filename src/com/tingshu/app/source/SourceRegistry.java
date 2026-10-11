package com.tingshu.app.source;

import com.tingshu.app.data.SourceConfig;
import com.tingshu.app.data.SourceStore;
import com.tingshu.app.source.impl.BokanVoiceSource;
import com.tingshu.app.source.impl.Pingshu5Source;
import com.tingshu.app.source.impl.Ting15Source;
import com.tingshu.app.source.impl.VoiceBusSource;
import com.tingshu.app.source.impl.YuntuVoiceSource;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 源注册表。
 *
 * <p>★ v2.4：本 App 已改为「纯空壳工具」—— 不再内置任何源。
 * 首次安装后源列表为空，首页会提示用户去导入书源（JSON 配置源 / jar 插件源）。
 * 内置源实现类仍保留在 {@code source/impl/} 里，仅用于编译期占位与
 * 「导入源 → 一键恢复官方推荐源」的扩展位，默认不注册。
 */
public final class SourceRegistry {

    /**
     * 内置源 id 白名单。
     *
     * <p>v2.4 起清空 —— 这是「去内置源」的唯一开关点：
     * 保持空数组，所有源都走「用户导入」这条路进来，
     * 想恢复某个内置源只需把它的 id 加回来并在 {@link #reload()} 里注册。
     */
    private static final String[] BUILTIN_IDS = {};

    /** 全部源（自定义 + 插件），LinkedHashMap 保序 */
    private static final LinkedHashMap<String, Source> ALL =
            new LinkedHashMap<String, Source>();

    private SourceRegistry() {}

    /** 启动时调用一次。可重复调用（导入源后热重载）。 */
    public static synchronized void registerAll() {
        reload();
    }

    /**
     * 重建注册表：库里所有自定义源 + jar 插件源。
     *
     * <p>★ v2.4：不再注册任何内置源。用 clear 而不是 early-return，
     * 否则导入新源后注册不进去。
     */
    public static synchronized void reload() {
        ALL.clear();
        loadCustom();
        loadPlugins();
        loadJsPlugins();
        loadPythonSources();
        // ★ v3.7.7：源集合有变动（导入/删除/启停都走 reload），
        //   分类缓存必须一起失效 —— 否则新导入的源在首页"看不见分类"，
        //   或已删除的源还留在缓存里。
        try {
            com.tingshu.app.Bridge.invalidateCatCache();
        } catch (Throwable ignored) {}
    }

    /**
     * ★ v3.4：载入用户导入的 .py 源（TVBox / DSPlayer 风格）。
     *
     * <p>与 jar / JS 插件严格隔离：Python 运行时启动失败（设备不支持 / assets 损坏）
     * 只让这一类源不可用，其余源照常工作。
     *
     * <p>源文件放在 {@code <filesDir>/python_sources/}，文件名即源 id。
     * 每个文件独立 try-catch，单个坏源不影响其它源。
     *
     * <p>★★ v3.7.9：本方法**绝不再触发解释器启动**。
     * 它会被 {@code App.onCreate()}（主线程）经 {@code registerAll()} 调到，
     * 而解释器首次启动要解压 13MB assets —— 一旦在这里同步跑，
     * 冷启动主线程就被钉住数秒，用户看到"第一次打开没有书源，等很久才出来"。
     * 现在只做「已就绪才登记」：未就绪直接返回，等
     * {@link com.tingshu.app.python.PythonHost} 后台启动完成、
     * {@code App} 收到 ReadyListener 回调后重新 {@link #reload()} 补齐。
     */
    private static void loadPythonSources() {
        android.content.Context ctx;
        try {
            ctx = com.tingshu.app.core.App.ctx();
        } catch (Throwable t) {
            return;
        }
        if (ctx == null) return;

        File dir;
        try {
            dir = com.tingshu.app.python.PythonHost.sourcesDir(ctx);
        } catch (Throwable t) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;

        // ★ v3.7.9：只查状态，不点火（available 内部也不再阻塞，绝不会启动解释器）
        if (!com.tingshu.app.python.PythonHost.available()) {
            PY_PENDING.set(true);   // 标记"有 .py 源等着补登记"，供 App 决定是否要热重载
            return;
        }
        PY_PENDING.set(false);

        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });

        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f == null || !f.isFile()) continue;
            String fn = f.getName();
            if (!fn.endsWith(".py")) continue;
            String pid = "py_" + fn.substring(0, fn.length() - 3);
            try {
                String disp = com.tingshu.app.python.PythonHost.loadSource(pid, f.getAbsolutePath());
                if (disp == null) continue;
                com.tingshu.app.python.PySourceAdapter ad =
                        new com.tingshu.app.python.PySourceAdapter(pid, disp, "Python 源", true);
                // 空分类的源仍然登记（可能只支持搜索）
                ALL.put(pid, ad);
            } catch (Throwable ignored) {
            }
        }
    }

    /** ★ v3.7.9：还有已导入的 .py 源没登记上（等解释器后台启动完再补） */
    private static final java.util.concurrent.atomic.AtomicBoolean PY_PENDING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 是否存在「因 Python 运行时未就绪而暂缓登记」的源 */
    public static boolean pythonPending() {
        return PY_PENDING.get();
    }

    /** ★ v3.4：只取 Python 源 */
    public static synchronized List<Source> pythonSources() {
        List<Source> out = new ArrayList<Source>();
        for (Source s : ALL.values()) {
            if (s instanceof com.tingshu.app.python.PySourceAdapter) out.add(s);
        }
        return out;
    }

    /**
     * 注册官方推荐的 5 个内置源。
     *
     * <p>v2.4 起**不再自动调用**。保留这个方法是为了：
     * ① 用户想恢复推荐源时可以在源管理页一键调用；
     * ② 编译期不产生「import 未被使用」告警。
     */
    public static synchronized void registerRecommended() {
        register(new BokanVoiceSource());   // 博看有声（JSON，资源量大）
        register(new YuntuVoiceSource());   // 云图有声（JSON）
        register(new Ting15Source());       // 有听网（HTML+API）
        register(new Pingshu5Source());     // 评书网（HTML，名家评书）
        register(new VoiceBusSource());     // 声音巴士（HTML，无搜索）
    }

    /** 载入自定义源（解析失败或与内置冲突的跳过，不让坏配置拖垮整个 App） */
    private static void loadCustom() {
        List<SourceConfig> list;
        try {
            list = SourceStore.all();
        } catch (Throwable t) {
            return;
        }
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            SourceConfig sc = list.get(i);
            if (sc == null || sc.id == null || sc.id.length() == 0) continue;
            if (sc.conflictsWithBuiltin()) continue;
            try {
                if (sc.validate() != null) continue;
                ALL.put(sc.id, new JsonSource(sc));
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 载入 jar 插件源。加载失败（文件被清理/类损坏）只跳过该插件，
     * 绝不让单个坏插件拖垮整个注册表。
     */
    private static void loadPlugins() {
        List<com.tingshu.app.data.PluginRepo.Plugin> plugins;
        try {
            plugins = com.tingshu.app.data.PluginRepo.enabled();
        } catch (Throwable t) {
            return;
        }
        if (plugins == null) return;
        for (int i = 0; i < plugins.size(); i++) {
            com.tingshu.app.data.PluginRepo.Plugin p = plugins.get(i);
            try {
                com.tingshu.app.core.JarLoader.JarResult r =
                        com.tingshu.app.core.JarLoader.load(p.filePath, pluginOptDir());
                if (r.ok) {
                    for (Source s : r.sources) ALL.put(s.id(), s);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** dex 优化输出目录，懒初始化 */
    private static File pluginOptDir() {
        if (PLUGIN_OPT_DIR == null) {
            try {
                android.content.Context c = com.tingshu.app.core.App.ctx();
                if (c != null) PLUGIN_OPT_DIR = c.getDir("pluginopt", 0);
            } catch (Throwable ignored) {}
        }
        return PLUGIN_OPT_DIR;
    }

    private static File PLUGIN_OPT_DIR;

    /**
     * ★ v3.0：载入 JS 脚本书源插件。
     *
     * <p>与 {@link #loadPlugins()}（jar 插件）完全对称，区别只在 loader：
     * 这里走 {@link com.tingshu.app.core.js.JsPluginLoader}，把 zip 解包后
     * 用 Rhino 执行脚本，脚本里的 {@code registerSource({...})} 注册出源对象，
     * 每个源再包成 {@link com.tingshu.app.source.JsSourceAdapter}。
     *
     * <p>**性能考量**：一个 JS 插件包可能注册十几个源（ShunSources 就有 19 个），
     * 它们**共享同一个 Rhino scope**（脚本之间有闭包状态，必须同域）。
     * 所以这里是「一个插件包 → 一次 load → N 个源」，不是每个源各加载一次。
     *
     * <p>**容错**：单个插件包加载失败只跳过它（打日志），不影响 jar 插件和 JSON 源。
     */
    private static void loadJsPlugins() {
        List<com.tingshu.app.data.PluginRepo.Plugin> plugins;
        try {
            plugins = com.tingshu.app.data.PluginRepo.byKind(
                    com.tingshu.app.data.PluginRepo.KIND_JS);
        } catch (Throwable t) {
            return;
        }
        if (plugins == null || plugins.isEmpty()) return;

        for (int i = 0; i < plugins.size(); i++) {
            com.tingshu.app.data.PluginRepo.Plugin p = plugins.get(i);
            if (p == null || !p.enabled) continue;
            try {
                // filePath 对 JS 插件来说是「解包后的目录」，目录里已有 manifest.json，
                // 不需要再解一次 —— loader 提供了从目录直接加载的入口。
                com.tingshu.app.core.js.JsPluginLoader.JsResult r =
                        com.tingshu.app.core.js.JsPluginLoader.loadInstalled(p.filePath);
                if (r != null && r.ok) {
                    for (Source s : r.sources) {
                        if (s != null && s.id() != null) ALL.put(s.id(), s);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    /** 注册插件加载出来的源（导入 jar 时用；reload 也会走 loadPlugins） */
    public static synchronized int registerExternal(List<Source> sources) {
        if (sources == null) return 0;
        int n = 0;
        for (Source s : sources) {
            if (s == null || s.id() == null) continue;
            ALL.put(s.id(), s);
            n++;
        }
        return n;
    }

    public static synchronized void register(Source s) {
        if (s != null && s.id() != null) ALL.put(s.id(), s);
    }

    public static synchronized Source byId(String id) {
        return ALL.get(id);
    }

    /** 常用全部源（源管理页用，含已停用的） */
    public static synchronized List<Source> all() {
        return new ArrayList<Source>(ALL.values());
    }

    /** 内置源（源管理页里分两组展示用） */
    public static synchronized List<Source> builtins() {
        List<Source> out = new ArrayList<Source>();
        for (int i = 0; i < BUILTIN_IDS.length; i++) {
            Source s = ALL.get(BUILTIN_IDS[i]);
            if (s != null) out.add(s);
        }
        return out;
    }

    /** 配置化自定义源（JSON 声明的，不含 jar 插件） */
    public static synchronized List<Source> customs() {
        List<Source> out = new ArrayList<Source>();
        for (Source s : ALL.values()) {
            if (isBuiltin(s.id())) continue;
            if (s instanceof JsonSource) out.add(s);
        }
        return out;
    }

    /**
     * 插件源：jar 插件的源 + JS 插件的源（非内置、非 JSON 配置源的全部）。
     *
     * <p>v3.0 起 JS 源（{@link com.tingshu.app.source.JsSourceAdapter}）也在其中 ——
     * 它们与 jar 源在语义上都是「用户导入的插件」。
     */
    public static synchronized List<Source> plugins() {
        List<Source> out = new ArrayList<Source>();
        for (Source s : ALL.values()) {
            if (isBuiltin(s.id())) continue;
            if (s instanceof JsonSource) continue;
            out.add(s);
        }
        return out;
    }

    /** 只取 JS 插件的源 */
    public static synchronized List<Source> jsPlugins() {
        List<Source> out = new ArrayList<Source>();
        for (Source s : ALL.values()) {
            if (s instanceof com.tingshu.app.source.JsSourceAdapter) out.add(s);
        }
        return out;
    }

    public static boolean isBuiltin(String id) {
        if (id == null) return false;
        for (int i = 0; i < BUILTIN_IDS.length; i++) {
            if (BUILTIN_IDS[i].equals(id)) return true;
        }
        return false;
    }

    /** 是否来自 jar 插件（非内置、非 JSON 配置源） */
    public static synchronized boolean isFromPlugin(String id) {
        if (id == null || isBuiltin(id)) return false;
        Source s = ALL.get(id);
        if (s == null) return false;
        return !(s instanceof JsonSource);
    }

    // ==================== ★ v3.1 站点域名（去重用） ====================

    /**
     * 取一个源的主站域名（已归一化）。取不到返回空串。
     *
     * <p>用途：批量验证时判断「两个源是不是指向同一个站点」。同一站点被
     * JSON 源和 JS 源同时覆盖是很常见的情况（用户先导了订阅，后又装了插件包），
     * 结果就是同一本书搜出两条，还白跑两遍网络。
     *
     * <p>★ 为什么不直接比 URL 字符串：{@code https://www.a.com/} 与
     * {@code http://a.com} 是同一个站，字符串比会漏判。
     */
    public static String hostOf(Source s) {
        if (s == null) return "";
        String raw = rawUrlOf(s);
        return hostOfUrl(raw);
    }

    /** 从各种 Source 实现里抠出「主站地址」字符串 */
    private static String rawUrlOf(Source s) {
        try {
            if (s instanceof JsonSource) {
                SourceConfig cfg = ((JsonSource) s).config();
                return cfg == null ? "" : cfg.base;
            }
            if (s instanceof JsSourceAdapter) {
                return ((JsSourceAdapter) s).url();
            }
            // 其它插件源（jar）：约定是静态字段 BASE，取不到再试 url / BASE_URL
            String[] names = {"BASE", "BASE_URL", "url", "HOST"};
            for (int i = 0; i < names.length; i++) {
                try {
                    java.lang.reflect.Field f = s.getClass().getField(names[i]);
                    Object v = f.get(null);
                    if (v instanceof String && ((String) v).length() > 0) return (String) v;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * URL → 归一化域名。规则：
     * <pre>
     *   https://www.Example.com:8080/a/b?x=1  →  example.com
     *   http://m.example.com/                 →  m.example.com
     * </pre>
     * 只去掉协议、{@code www.} 前缀、端口、路径与查询串，并转小写。
     * <b>不去掉</b> {@code m.} 等子域 —— 手机站与主站内容常常不同，不该当成重复。
     */
    public static String hostOfUrl(String url) {
        if (url == null) return "";
        String u = url.trim().toLowerCase();
        if (u.length() == 0) return "";
        // 去协议
        int p = u.indexOf("://");
        if (p >= 0) u = u.substring(p + 3);
        // 去路径 / 查询 / 锚点
        int slash = u.indexOf('/');
        if (slash >= 0) u = u.substring(0, slash);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        // 去端口
        int colon = u.indexOf(':');
        if (colon >= 0) u = u.substring(0, colon);
        // 去 www.
        if (u.startsWith("www.")) u = u.substring(4);
        // 去用户信息（user:pass@host）
        int at = u.lastIndexOf('@');
        if (at >= 0) u = u.substring(at + 1);
        return u.trim();
    }

    /** 启用的源，按用户自定义顺序 */
    public static List<Source> enabled() {
        List<Source> list = all();
        List<Source> out = new ArrayList<Source>();
        for (Source s : list) {
            if (com.tingshu.app.data.SourceRepo.isEnabled(s.id())) out.add(s);
        }
        // 按用户排序
        final java.util.Map<String, Integer> sortMap = com.tingshu.app.data.SourceRepo.sortMap();
        java.util.Collections.sort(out, new java.util.Comparator<Source>() {
            public int compare(Source a, Source b) {
                Integer sa = sortMap.get(a.id());
                Integer sb = sortMap.get(b.id());
                int va = sa == null ? 1000 : sa.intValue();
                int vb = sb == null ? 1000 : sb.intValue();
                if (va != vb) return va - vb;
                return a.name().compareTo(b.name());
            }
        });
        return out;
    }

    /** 支持搜索的启用源 */
    public static List<Source> searchable() {
        List<Source> list = enabled();
        List<Source> out = new ArrayList<Source>();
        for (Source s : list) {
            if (s.canSearch()) out.add(s);
        }
        return out;
    }

    /** 支持分类浏览的启用源（发现页用） */
    public static List<Source> browsable() {
        List<Source> list = enabled();
        List<Source> out = new ArrayList<Source>();
        for (Source s : list) {
            if (!(s instanceof BrowsableSource)) continue;
            try {
                if (((BrowsableSource) s).canBrowse()) out.add(s);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    public static int count() {
        return ALL.size();
    }
}
