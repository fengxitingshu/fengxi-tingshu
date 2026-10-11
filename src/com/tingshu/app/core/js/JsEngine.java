package com.tingshu.app.core.js;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ v3.0：Rhino JS 引擎封装。
 *
 * <p>让「JS 脚本插件」（如 ShunSources 那套）能在 App 里跑起来。设计要点：
 *
 * <h3>1. 一个插件包 = 一个 JS 全局作用域（scope）</h3>
 * 脚本之间有闭包状态（比如 zblog.js 的本地索引缓存），必须同域共享。
 * 同时不同插件包之间要隔离，所以每个包各自 {@code initStandardObjects()}。
 *
 * <h3>2. 每次调用新建 Context，不跨线程复用</h3>
 * 搜索/详情跑在 {@code Aggregator} 的线程池上（见 searchAll），
 * 池里多个线程会并发调用同一个源。Rhino 的 {@link Context} **不是线程安全**的，
 * 但 {@link Scriptable} 作用域在「同一时刻只有一个线程进入」时可以复用。
 * 因此这里每次进入都 {@code Context.enter()}，退出 {@code Context.exit()}。
 *
 * <h3>3. 解释模式 + 指令中断</h3>
 * Android 上不能再做字节码编译优化（{@code optimizationLevel = -1}），
 * 否则会有 dex 校验问题。同时设 {@code instructionObserverThreshold}，
 * 让死循环脚本能被超时/取消打断，不至于永久占住线程池。
 *
 * <h3>4. 注入的全局函数</h3>
 * JS 脚本里用到的 {@code registerSource / str / int / at / items / regexInt}
 * 都没有在脚本内定义，全是宿主注入的（详见插件契约文档）。
 */
public final class JsEngine {

    private JsEngine() {}

    /** 单次执行的超时（毫秒）。JS 源里最长见 sleep(60000)，所以给得比较宽 */
    public static volatile long callTimeoutMs = 120 * 1000L;

    /**
     * Rhino 的 ContextFactory。
     *
     * <p>统一切到解释模式 + 装指令观察器（用于中断死循环）。
     * 用静态单例，因为 Rhino 要求同一线程的 Context 从同一个 Factory 出。
     */
    private static final ContextFactory FACTORY = new ContextFactory() {
        @Override
        protected boolean hasFeature(Context cx, int featureIndex) {
            // ES6 语法（模板串、let/const、箭头函数）—— 这套插件用到了一些
            if (featureIndex == Context.FEATURE_STRICT_MODE) return false;
            return super.hasFeature(cx, featureIndex);
        }

        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setLanguageVersion(Context.VERSION_ES6);
            // ★ Android 上必须解释执行：Rhino 的 class 编译器会生成 JVM 字节码，
            //   在 ART 上跑不了（且 d8 也无法正确 desugar）。
            cx.setOptimizationLevel(-1);
            // 每执行 N 条指令回调一次 observeInstructionCount，用来中断死循环
            cx.setInstructionObserverThreshold(20000);
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            // 中断检查：超时或线程被打断都直接抛错，让 Rhino 立刻中止脚本。
            // 这是防止「脚本死循环 / sleep 太久」把线程池占死的最后一道闸。
            long deadline = DEADLINE.get();
            if (deadline > 0 && System.currentTimeMillis() > deadline) {
                throw new JsInterrupted("脚本执行超时（" + callTimeoutMs + "ms）");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new JsInterrupted("脚本执行被取消");
            }
        }
    };

    /** 当前线程的脚本截止时刻（0 = 不限时）。用 ThreadLocal 避免多线程互相干扰 */
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<Long>() {
        @Override protected Long initialValue() { return 0L; }
    };

    /** 给当前线程设一个执行截止时间，返回旧的截止值（供还原） */
    public static long pushDeadline(long ms) {
        long old = DEADLINE.get();
        DEADLINE.set(ms <= 0 ? 0L : System.currentTimeMillis() + ms);
        return old;
    }

    public static void popDeadline(long old) {
        DEADLINE.set(old);
    }

    /** 中断信号。继承 Error 以便 JS 侧 try/catch 能捕获到 */
    public static class JsInterrupted extends RuntimeException {
        public JsInterrupted(String msg) { super(msg); }
    }

    // ==================== 作用域 ====================

    /**
     * 一个插件包的 JS 全局作用域。
     *
     * <p>创建时即注入全部全局函数；脚本通过 {@link #run} 顺序加载。
     * 注意：**同一时刻只允许一个线程进入**（调用方负责串行化，或靠 script 无共享写状态）。
     */
    public static final class Scope {
        private final Scriptable global;
        /** registerSource 收集到的源对象（保持声明顺序） */
        private final List<Scriptable> sources = new ArrayList<Scriptable>();
        /** 源 id -> 源对象，便于按 id 查 */
        private final java.util.Map<String, Scriptable> byId =
                new java.util.LinkedHashMap<String, Scriptable>();

        Scope() { this(null); }

        /**
         * @param outer 额外的注册观察者（可为 null）。Scope 自己始终会收集一份，
         *              outer 只用于「边加载边打印/边入库」这类旁路需求。
         */
        Scope(final SourceSink outer) {
            Context cx = FACTORY.enterContext();
            try {
                global = cx.initStandardObjects(null, false);
                injectGlobals(cx, global, new SourceSink() {
                    public void onSource(Scriptable src) {
                        // 同 id 重复注册时后一个覆盖前一个（脚本内部可能有条件注册）
                        String id = Context.toString(ScriptableObject.getProperty(src, "id"));
                        if (id == null || id.length() == 0 || "undefined".equals(id)) return;
                        if (byId.containsKey(id)) {
                            int at = -1;
                            for (int i = 0; i < sources.size(); i++) {
                                if (id.equals(Context.toString(
                                        ScriptableObject.getProperty(sources.get(i), "id")))) {
                                    at = i; break;
                                }
                            }
                            if (at >= 0) sources.set(at, src); else sources.add(src);
                        } else {
                            sources.add(src);
                        }
                        byId.put(id, src);
                        if (outer != null) {
                            try { outer.onSource(src); } catch (Throwable ignored) {}
                        }
                    }
                });
            } finally {
                Context.exit();
            }
        }

        public Scriptable global() { return global; }
        public List<Scriptable> sources() { return sources; }
        public int sourceCount() { return sources.size(); }
        public Scriptable sourceById(String id) { return byId.get(id); }

        /**
         * 把一个宿主对象挂到源对象上（目前只有 {@code host}）。
         *
         * <p>JS 源里全部写成 {@code var host = this.host}，靠的就是这个属性。
         * 必须给**每个源对象**单独挂 —— 因为它们各自可能被不同插件包加载，
         * 且 host 里带着各自的 pluginId（影响缓存目录）。
         */
        public void attachHost(Scriptable src, Object host) {
            Context cx = FACTORY.enterContext();
            try {
                ScriptableObject.putProperty(src, "host", host);
            } finally {
                Context.exit();
            }
        }

        /** 加载一段脚本（按 manifest.scripts 顺序调用） */
        public void run(String code, String name) {
            long old = pushDeadline(60 * 1000L);     // 加载期给 60s，正常脚本毫秒级
            Context cx = FACTORY.enterContext();
            try {
                cx.evaluateString(global, code, name, 1, null);
            } finally {
                Context.exit();
                popDeadline(old);
            }
        }

        /**
         * 在指定源对象上调用它的某个方法。
         *
         * @param src      源对象
         * @param fnName   方法名（search / bookDetail / audio / ...）
         * @param args     JS 参数
         * @return JS 返回值（可能是 Undefined）
         */
        public Object call(Scriptable src, String fnName, Object[] args) {
            long old = pushDeadline(callTimeoutMs);
            Context cx = FACTORY.enterContext();
            try {
                Object fn = ScriptableObject.getProperty(src, fnName);
                if (!(fn instanceof Function)) {
                    throw new JsError("源不支持 " + fnName + "()");
                }
                return ((Function) fn).call(cx, global, src, args);
            } finally {
                Context.exit();
                popDeadline(old);
            }
        }

        /** 调一个裸 JS 函数（不是源的方法），例如 render 的 parse 回调 */
        public Object callFunction(Function fn, Object[] args) {
            long old = pushDeadline(callTimeoutMs);
            Context cx = FACTORY.enterContext();
            try {
                return fn.call(cx, global, global, args);
            } finally {
                Context.exit();
                popDeadline(old);
            }
        }

        /** 源对象上是否有某个函数属性（用来判断 canSearch / 是否有分类） */
        public boolean hasFunction(Scriptable src, String fnName) {
            Context cx = FACTORY.enterContext();
            try {
                return ScriptableObject.getProperty(src, fnName) instanceof Function;
            } finally {
                Context.exit();
            }
        }

        /** 读源对象的字符串属性（id / name / description / group / url ...） */
        public String str(Scriptable src, String key) {
            Context cx = FACTORY.enterContext();
            try {
                Object v = ScriptableObject.getProperty(src, key);
                // 属性不存在时 Rhino 返回 UniqueTag.NOT_FOUND（不是 null），必须一并挡掉，
                // 否则会拼出 "org.mozilla.javascript.UniqueTag@xxx: NOT_FOUND" 这种鬼字符串
                if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return "";
                if (v instanceof Scriptable) return "";
                return Context.toString(v);
            } finally {
                Context.exit();
            }
        }

        /** 读源对象的布尔属性 */
        public boolean bool(Scriptable src, String key) {
            Context cx = FACTORY.enterContext();
            try {
                Object v = ScriptableObject.getProperty(src, key);
                if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return false;
                return Context.toBoolean(v);
            } finally {
                Context.exit();
            }
        }

        /** 读源对象的数字属性（不存在 / 非数字都返回 def） */
        public int num(Scriptable src, String key, int def) {
            Context cx = FACTORY.enterContext();
            try {
                Object v = ScriptableObject.getProperty(src, key);
                if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return def;
                double d = Context.toNumber(v);
                return Double.isNaN(d) ? def : (int) d;
            } finally {
                Context.exit();
            }
        }
    }

    /** 新建一个空的作用域（已注入全局函数） */
    public static Scope newScope() {
        return new Scope();
    }

    /**
     * 把一个 Java 对象转成 JS 侧可用的值。
     * 用于把 host 对象、以及各种 JSON 数据塞给脚本。
     */
    public static Object toJs(Scriptable scope, Object javaObj) {
        Context cx = FACTORY.enterContext();
        try {
            return Context.javaToJS(javaObj, scope);
        } finally {
            Context.exit();
        }
    }

    /** 取当前线程的 Rhino Context（供需要手动 evaluate 的辅助类用） */
    public static Context enter() {
        return FACTORY.enterContext();
    }

    public static void exit() {
        Context.exit();
    }

    // ==================== 全局函数注入 ====================

    /**
     * 给全局作用域注入那批「脚本里没定义、全靠宿主提供」的全局函数。
     *
     * @param sink registerSource 收到的源对象往哪放（每个 Scope 各自的收集器）
     */
    private static void injectGlobals(final Context cx, final Scriptable scope,
                                      final SourceSink sink) {
        // registerSource(source)
        put(scope, "registerSource", new BaseFunction() {
            @Override
            public Object call(Context c, Scriptable s, Scriptable thisObj, Object[] a) {
                if (a.length > 0 && a[0] instanceof Scriptable) {
                    sink.onSource((Scriptable) a[0]);
                }
                return Undefined.instance;
            }
        });
        // regexInt(str, regex, default?)
        put(scope, "regexInt", new BaseFunction() {
            @Override
            public Object call(Context c, Scriptable s, Scriptable thisObj, Object[] a) {
                if (a.length < 2) return 0;
                int def = a.length > 2 ? (int) Context.toNumber(a[2]) : 0;
                try {
                    String pattern = patternOf(a[1]);
                    if (pattern == null) return def;
                    Matcher m = Pattern.compile(pattern).matcher(Context.toString(a[0]));
                    if (m.find() && m.groupCount() >= 1) {
                        String g = m.group(1);
                        return g == null ? def : Integer.parseInt(g.trim());
                    }
                } catch (Throwable ignored) {}
                return def;
            }
        });
        // str(obj, path) -> String
        put(scope, "str", new BaseFunction() {
            @Override
            public Object call(Context c, Scriptable s, Scriptable thisObj, Object[] a) {
                Object v = pathGet(a.length > 0 ? a[0] : null,
                        a.length > 1 ? Context.toString(a[1]) : "");
                if (v == null || v == Undefined.instance) return "";
                return Context.toString(v);
            }
        });
        // int(obj, path, default?) -> Int
        put(scope, "int", new BaseFunction() {
            @Override
            public Object call(Context c, Scriptable s, Scriptable thisObj, Object[] a) {
                Object v = pathGet(a.length > 0 ? a[0] : null,
                        a.length > 1 ? Context.toString(a[1]) : "");
                if (v == null || v == Undefined.instance) return a.length > 2 ? a[2] : 0;
                double d = Context.toNumber(v);
                if (Double.isNaN(d)) return a.length > 2 ? a[2] : 0;
                return (int) d;
            }
        });
        // at(obj, path) -> 原始值（可为 undefined/null）
        put(scope, "at", new BaseFunction() {
            @Override
            public Object call(Context c, Scriptable s, Scriptable thisObj, Object[] a) {
                return pathGet(a.length > 0 ? a[0] : null,
                        a.length > 1 ? Context.toString(a[1]) : "");
            }
        });
        // items(obj, path) -> Array（保证返回数组）
        put(scope, "items", new BaseFunction() {
            @Override
            public Object call(Context c, Scriptable s, Scriptable thisObj, Object[] a) {
                Object v = pathGet(a.length > 0 ? a[0] : null,
                        a.length > 1 ? Context.toString(a[1]) : "");
                if (v instanceof NativeArray) return v;
                // Java List 经 javaToJS 会变成 NativeJavaArray，它有 length 但没有 JS 数组方法，
                // 这里统一转成真正的 NativeArray，脚本里才能 .map/.filter
                return toNativeArray(c, s, v);
            }
        });
    }

    /** registerSource 的接收方（每个 Scope 一份，天然隔离） */
    public interface SourceSink { void onSource(Scriptable src); }

    // ==================== 工具 ====================

    private static void put(Scriptable scope, String name, Object fn) {
        ScriptableObject.putProperty(scope, name, fn);
    }

    /**
     * 把 JS 传进来的正则还原成 Java 的 pattern 字符串。
     *
     * <p>插件里全是 {@code regexInt(url, /\/(\d+)$/)} 这种 **RegExp 字面量**写法。
     * 如果直接 {@code Context.toString(regexp)}，拿到的是 {@code "/\/(\d+)$/"}
     * —— **首尾带斜杠**，丢给 {@code Pattern.compile} 会编译失败，
     * 于是所有分页页码都会静默退化成默认值（表现为 currentPage 永远是 0）。
     *
     * <p>这里优先取 RegExp 对象的 {@code source} 属性；拿不到再按
     * {@code /pattern/flags} 字符串剥壳。
     */
    static String patternOf(Object v) {
        if (v == null || v == Undefined.instance) return null;
        if (v instanceof Scriptable) {
            Object src = ScriptableObject.getProperty((Scriptable) v, "source");
            if (src != null && src != Scriptable.NOT_FOUND && src != Undefined.instance) {
                String p = Context.toString(src);
                if (p != null && p.length() > 0) return p;
            }
        }
        String raw = Context.toString(v);
        if (raw == null || raw.length() == 0) return null;
        // "/abc/gi" → "abc"
        if (raw.length() > 2 && raw.charAt(0) == '/' && raw.lastIndexOf('/') > 0) {
            int end = raw.lastIndexOf('/');
            String body = raw.substring(1, end);
            String flags = raw.substring(end + 1);
            if (body.length() > 0) {
                if (flags.indexOf('i') >= 0) body = "(?i)" + body;
                return body;
            }
        }
        return raw;
    }

    /**
     * 点路径取值，支持数组下标：'data.list.0.name' / 'durl.0.url'。
     *
     * <p>★ 取不到时一律归一成 {@link Undefined#instance}。
     * Rhino 在属性不存在时返回的是 {@code UniqueTag.NOT_FOUND}（不是 null），
     * 一路透传出去再被 {@code Context.toString} 一拼，就会得到
     * {@code "org.mozilla.javascript.UniqueTag@xxx: NOT_FOUND"} 这种鬼字符串。
     */
    static Object pathGet(Object root, String path) {
        if (root == null || root == Undefined.instance) return Undefined.instance;
        if (path == null || path.length() == 0) return norm(root);
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (cur == null || cur == Undefined.instance) return Undefined.instance;
            if (cur instanceof Scriptable) {
                Scriptable sc = (Scriptable) cur;
                if (seg.matches("\\d+")) {
                    cur = norm(sc.get(Integer.parseInt(seg), sc));
                } else {
                    String k = seg;
                    cur = sc.has(k, sc) ? norm(ScriptableObject.getProperty(sc, k)) : Undefined.instance;
                }
            } else {
                return Undefined.instance;
            }
        }
        return cur;
    }

    /** NOT_FOUND / null → Undefined.instance，其余原样 */
    static Object norm(Object v) {
        if (v == null || v == Scriptable.NOT_FOUND) return Undefined.instance;
        return v;
    }

    /**
     * 把任意值转成 JS 原生数组（List / Java 数组 / 单个对象都兜住）。
     *
     * <p>★ 必须用 {@code cx.newArray(scope, n)} —— {@code new NativeArray(n)}
     * 造出来的数组**不带 prototype**，脚本里的 {@code .join() / .indexOf()}
     * 会全部报 "Cannot find default value for object"。
     */
    static Object toNativeArray(Context cx, Scriptable scope, Object v) {
        if (v == null || v == Undefined.instance) return cx.newArray(scope, 0);
        if (v instanceof NativeArray) return v;
        Object[] items = null;
        if (v instanceof List) {
            items = ((List) v).toArray();
        } else if (v instanceof Object[]) {
            items = (Object[]) v;
        } else if (v instanceof Scriptable) {
            // 已经是 JS 对象但不是数组 —— 保留原样，让脚本自己判断
            return v;
        }
        if (items == null) return cx.newArray(scope, 0);
        Scriptable out = cx.newArray(scope, items.length);
        for (int i = 0; i < items.length; i++) {
            out.put(i, out, items[i] instanceof Scriptable || items[i] == null
                    ? items[i] : Context.javaToJS(items[i], scope));
        }
        return out;
    }

    /** 引擎内部错误 */
    public static class JsError extends RuntimeException {
        public JsError(String msg) { super(msg); }
    }
}
