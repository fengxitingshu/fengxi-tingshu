package com.tingshu.app.python;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ★ v3.4：CPython 运行时宿主。
 *
 * <p>把 Chaquopy 的 CPython 3.8 解释器引导起来，并暴露 {@link #call} 给
 * {@link PySourceAdapter} 使用。所有与 Python 的交互都收敛在这里，
 * 上层只看到「输入方法名+参数，得到 JSON 字符串」。
 *
 * <h3>启动成本</h3>
 * 首次 {@code Python.start()} 会解压 assets（stdlib/requirements/bootstrap，
 * 合计约 6MB）到 {@code filesDir/chaquopy}，之后靠 SharedPreferences 里的
 * 版本指纹跳过，所以只有首次启动慢（百毫秒级），后续几乎无感。
 *
 * <h3>失败隔离</h3>
 * 任何一步失败都只让 Python 源不可用，不影响 jar / JSON / JS 源 ——
 * {@link #available()} 返回 false，调用方直接跳过。
 */
public final class PythonHost {

    private static final String TAG = "FxtsPy";

    private static final AtomicBoolean TRIED = new AtomicBoolean(false);
    private static volatile boolean ok = false;
    private static volatile String lastError = "";
    private static volatile PyObject module;

    /**
     * ★ v3.7.9：解释器当前处于「正在启动」状态。
     *
     * <p>启动是**一次性的**（首次解压 assets + self_test），一旦开始就必须跑完，
     * 中途不能被并发线程重复进入 —— 而它会持续几百毫秒到两三秒。
     * 这段时间里所有问 {@link #available()} 的地方都会拿到 false，
     * 但原因不是"不支持"而是"还没好"。调用方（尤其是前端提示文案）
     * 需要区分这两种情况，所以单独开一个可读的标记。
     */
    private static volatile boolean starting = false;

    /** ★ v3.9.1：启动失败后的重试次数上限（避免一次偶发失败就把 Python 永久判死） */
    private static int startRetries = 0;

    private PythonHost() {}

    /**
     * Python 是否可用（幂等，可在任意线程调）。
     *
     * <p>★ v3.5 修复：之前实现是 {@code ensureStarted(null)} —— 传 null 会
     * 直接返回 false，导致全项目没有任何一处真正启动过解释器，
     * 真机上永远报「Python 运行时不可用」（原因还是空字符串）。
     * 现在自己取 Application 上下文尝试启动，调用方不再需要关心传 Context。
     *
     * <p>★★ v3.7.9 关键修复：**这个方法不再阻塞调用线程**。
     *
     * <p><b>问题</b>：原实现里，首次调用会当场执行 {@code Python.start()} ——
     * Chaquopy 要把 {@code assets/chaquopy} 下 13MB 资源（bootstrap-native 5.6M /
     * requirements 3.2M / stdlib 2.8M）解压到 filesDir，还要跑一次 self_test。
     * 而调用链是：
     * <pre>
     *   App.onCreate()            ← 主线程
     *     └ SourceRegistry.registerAll()
     *         └ reload() → loadPythonSources() → available() → ensureStarted()
     * </pre>
     * 于是**每次冷启动主线程都被卡住数秒**，WebView 首屏一直拿不到源列表
     * （前端 listSources 走 singlePool，可主线程正被占着，Activity 都没起来），
     * 用户看到的就是"第一次打开没有书源，等很久才出来"。
     *
     * <p><b>现在的策略</b>：本方法只做「查状态 + 必要时点火」，绝不等待。
     * <ul>
     *   <li>已就绪 → 直接 true；</li>
     *   <li>正在启动 → 立刻 false（调用方本轮先跳过 Python 源）；</li>
     *   <li>没启动过 → **丢到后台线程**去 ensureStarted，立刻返回 false；</li>
     *   <li>启动完成 → 回调 {@link #onReady} 让上层热重载源表。</li>
     * </ul>
     * 这样主线程永远是毫秒级返回，源列表（JSON/jar/JS）先出来，Python 源稍后补上。
     */
    public static boolean available() {
        if (ok) return true;
        if (starting) return false;             // 正在解压，本次先跳过
        if (TRIED.get()) return ok;             // 启动过了但失败 → 永久 false
        kickStart();
        return false;                            // 首次调用：本轮不等，后台去启动
    }

    /**
     * ★ v3.7.9：判断「Python 运行时是否还在准备中」（区别于"不可用"）。
     * 前端用它来决定提示文案：准备中说"正在初始化"，失败才说"不可用"。
     */
    public static boolean starting() {
        return starting && !ok;
    }

    /** 运行时就绪回调（只保留一个，见 {@link #setReadyListener}） */
    public interface ReadyListener {
        void onReady(boolean ok);
    }

    private static volatile ReadyListener readyListener;

    /**
     * 注册「Python 运行时就绪」回调。
     *
     * <p>由 {@code App} 在 onCreate 里挂上：启动完成（无论成败）时喊一声，
     * App 据此在后台线程把源表重新 reload 一次，把刚才跳过没登记的 .py 源补进去，
     * 并主动推一次 onSourceList 给前端。回调在**后台线程**触发，实现方自己切主线程。
     */
    public static void setReadyListener(ReadyListener l) {
        readyListener = l;
        // 注册时若**已经启动结束**（成功或失败都算），立刻补一次通知，
        // 避免"注册晚了一步，回调永远不来"。
        // ★ v3.9.1：条件从 TRIED 收紧为「已启动结束」——
        //   TRIED 在 kickStart 一开始就被置上，而那时解释器还在解压；
        //   拿这个当判据会拿着 ok=false 空跑一次 onReady，
        //   上层的补登记逻辑因此被"以为已经通知过"而错过真正的就绪回调。
        if (l != null && TRIED.get() && !starting) {
            try { l.onReady(ok); } catch (Throwable ignored) {}
        }
    }

    /** 后台点火：把 ensureStarted 丢给单线程池，绝不占用调用线程 */
    private static void kickStart() {
        if (!TRIED.compareAndSet(false, true)) return;   // 别人抢到了就不重复点
        starting = true;
        android.content.Context app = null;
        try {
            app = com.tingshu.app.core.App.ctx();
        } catch (Throwable ignored) {}
        if (app == null) {
            // App 还没初始化（极早阶段），撤回标记，下次再试
            TRIED.set(false);
            starting = false;
            return;
        }
        final android.content.Context ctx = app.getApplicationContext();
        try {
            com.tingshu.app.source.Aggregator.singlePool().execute(new Runnable() {
                public void run() {
                    startNow(ctx);
                    synchronized (PythonHost.class) {
                        starting = false;
                        // ★ v3.9.1：唤醒可能在等这次启动的 ensureStarted
                        PythonHost.class.notifyAll();
                    }
                    ReadyListener l = readyListener;
                    if (l != null) {
                        try { l.onReady(ok); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable t) {
            // 线程池都不可用（极端情况）：退回同步启动，总比整个不可用强
            startNow(ctx);
            synchronized (PythonHost.class) {
                starting = false;
                PythonHost.class.notifyAll();
            }
        }
    }

    /**
     * 确保运行时已启动（**阻塞式**，只允许在后台线程调）。
     *
     * <p>★ v3.7.9：本方法保留给「导入 .py 源」这条路 —— 用户明确点了导入，
     * 等一会是合理的，而且必须等解释器真的可用才能装载脚本。
     * 冷启动路径**不要**用这个方法，走 {@link #available()}（非阻塞点火）。
     *
     * @param ctx Application 上下文；为 null 时若未启动过则立即返回 false
     */
    public static synchronized boolean ensureStarted(Context ctx) {
        if (ok) return true;
        if (ctx == null) return false;

        // ★ v3.9.1：后台正在启动 —— 必须等它结束，**绝不能**再进一次 startNow。
        //   Chaquopy 的 Python.start() 不是幂等的：两个线程同时进会抛异常或把
        //   解释器状态搅乱，而导入流程看到的就是"Python 运行时不可用"
        //   （真正原因被吞在异常里）。这里用监视器等待，启动完由 kickStart 唤醒。
        if (starting) {
            long deadline = System.currentTimeMillis() + 120000L;   // 最多等 2 分钟
            while (starting && !ok && System.currentTimeMillis() < deadline) {
                try {
                    PythonHost.class.wait(300);
                } catch (InterruptedException e) {
                    return ok;
                }
            }
            return ok;
        }

        // 启动过但失败了：允许有限次重试（原来一次失败就永久 false，
        // 整个进程内再也不会有 Python 源，连重启 App 之前都是死的）
        if (TRIED.get()) {
            if (startRetries >= 3) return false;
            startRetries++;
        }
        return startNow(ctx);
    }

    /** 真正的启动逻辑：解压 assets + self_test。耗时，禁止在主线程调。 */
    private static synchronized boolean startNow(Context ctx) {
        if (ctx == null) return false;
        if (ok) return true;
        TRIED.set(true);
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(ctx));
            }
            Python py = Python.getInstance();
            module = py.getModule("fxts_runtime");
            // 跑一次自检，把 Python 侧的模块可用性提前暴露出来
            String st = module.callAttr("self_test").toString();
            JSONObject o = new JSONObject(st);
            ok = o.optBoolean("ok", false);
            Log.i(TAG, "Python 运行时就绪: " + st);
        } catch (Throwable t) {
            ok = false;
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "Python 运行时启动失败", t);
        }
        // ★ v3.9.1：唤醒正在等这次启动结果的线程（见 ensureStarted）
        PythonHost.class.notifyAll();
        return ok;
    }

    /**
     * ★ v3.9.1：跑一次 Python 侧自检，把「为什么源用不了」讲清楚。
     *
     * <p>以前 .py 源不工作时，前端只能看到一句"Python 运行时不可用"，
     * 到底是解释器没起来、还是缺 lxml / Crypto 这类三方模块，完全看不出来。
     * 这里把 self_test 的结果原样回传（Python 版本、sys.path、每个关键模块的
     * 导入结果），诊断页一看就知道卡在哪。
     */
    public static JSONObject selfTest(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("available", available());
            o.put("starting", starting());
            o.put("lastError", lastError == null ? "" : lastError);
            if (!ensureStarted(ctx)) {
                o.put("error", "Python 运行时不可用"
                        + (lastError == null || lastError.isEmpty()
                            ? "" : ("：" + lastError)));
                return o;
            }
            try {
                String st = module.callAttr("self_test").toString();
                JSONObject inner = new JSONObject(st);
                o.put("ok", true);
                o.put("detail", inner.opt("data"));
            } catch (Throwable t) {
                o.put("error", "自检调用失败：" + (t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage()));
            }
        } catch (Throwable t) {
            try {
                o.put("error", String.valueOf(t.getMessage()));
            } catch (Throwable ignored) {}
        }
        return o;
    }

    /** 最近一次失败原因（诊断用） */
    public static String lastError() {
        return lastError;
    }

    /**
     * 加载一个 .py 源文件。
     *
     * @return 成功返回源的显示名，失败返回 null（原因见 {@link #lastError()}）
     */
    public static String loadSource(String key, String path) {
        if (!available()) return null;
        try {
            PyObject r = module.callAttr("load_source", key, path);
            JSONObject o = new JSONObject(r.toString());
            if (o.optBoolean("ok", false)) {
                return o.optString("name", "");
            }
            lastError = o.optString("error", "未知错误");
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "加载源失败: " + path, t);
        }
        return null;
    }

    /** 卸载一个源 */
    public static void unloadSource(String key) {
        if (!available()) return;
        try {
            module.callAttr("unload_source", key);
        } catch (Throwable t) {
            Log.w(TAG, "卸载源失败: " + key, t);
        }
    }

    /**
     * ★ v3.4：已装载的源清单（key → 显示名）。
     *
     * <p>导入时用来做跨次去重：用户可能先传一个 .py、后来又把整包 .zip 传一次，
     * 同一个书源会以不同文件名出现两次（id 是 py_&lt;文件名&gt;，天然不同），
     * 不处理的话源管理里就会出现两个同名条目。
     *
     * @return key → name 映射；失败返回空表（调用方按「没有已装源」处理即可）
     */
    public static java.util.Map<String, String> loadedNames() {
        java.util.Map<String, String> map = new java.util.HashMap<String, String>();
        if (!available()) return map;
        try {
            PyObject r = module.callAttr("list_sources");
            JSONObject o = new JSONObject(r.toString());
            org.json.JSONArray arr = o.optJSONArray("list");
            if (arr == null) return map;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                String k = it.optString("key", "");
                String n = it.optString("name", "");
                if (!k.isEmpty()) map.put(k, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "列举已装源失败", t);
        }
        return map;
    }

    /** 重复导入同一书源时，把旧的那份卸掉，避免同名条目并存 */
    public static void unloadByName(String name, String keepKey) {
        if (name == null || name.trim().isEmpty()) return;
        String want = name.trim().toLowerCase();
        java.util.Map<String, String> cur = loadedNames();
        for (java.util.Map.Entry<String, String> e : cur.entrySet()) {
            if (e.getKey() == null || e.getKey().equals(keepKey)) continue;
            String n = e.getValue() == null ? "" : e.getValue().trim().toLowerCase();
            if (want.equals(n)) unloadSource(e.getKey());
        }
    }

    /**
     * 统一调用入口。
     *
     * @param method Python 侧的函数名（home_content / category_content / ...）
     * @param args   位置参数
     * @return Python 返回的 JSON 字符串；失败返回 {"ok":false,...}
     */
    public static JSONObject call(String method, Object... args) {
        if (!available()) {
            return errJson("Python 运行时不可用: " + lastError);
        }
        try {
            PyObject r = module.callAttr(method, args);
            return new JSONObject(r.toString());
        } catch (Throwable t) {
            Log.e(TAG, "调用失败 " + method, t);
            return errJson(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static JSONObject errJson(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", msg == null ? "" : msg);
        } catch (Throwable ignored) {}
        return o;
    }

    /** 源工作目录：<filesDir>/python_sources */
    public static File sourcesDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "python_sources");
        if (!d.exists()) d.mkdirs();
        return d;
    }
}
