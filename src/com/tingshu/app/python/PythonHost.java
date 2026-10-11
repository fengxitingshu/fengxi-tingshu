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

    private PythonHost() {}

    /**
     * Python 是否可用（幂等，可在任意线程调）。
     *
     * <p>★ v3.5 修复：之前实现是 {@code ensureStarted(null)} —— 传 null 会
     * 直接返回 false，导致全项目没有任何一处真正启动过解释器，
     * 真机上永远报「Python 运行时不可用」（原因还是空字符串）。
     * 现在自己取 Application 上下文尝试启动，调用方不再需要关心传 Context。
     */
    public static boolean available() {
        if (ok) return true;
        if (!TRIED.get()) {
            android.content.Context app = null;
            try {
                app = com.tingshu.app.core.App.ctx();
            } catch (Throwable ignored) {}
            if (app == null) {
                // App 还没初始化（极早阶段），不标记 TRIED，下次再试
                return false;
            }
            return ensureStarted(app);
        }
        return ok;
    }

    /** 最近一次失败原因（诊断用） */
    public static String lastError() {
        return lastError;
    }

    /**
     * 确保运行时已启动。首次调用会解压 assets，可能耗时，建议在子线程调。
     *
     * @param ctx Application 上下文；为 null 时若未启动过则立即返回 false
     */
    public static synchronized boolean ensureStarted(Context ctx) {
        if (TRIED.get()) return ok;
        if (ctx == null) return false;
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
        return ok;
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
