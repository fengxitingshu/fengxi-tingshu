package com.fengxi.fnplayer.source;

import android.content.SharedPreferences;

import com.fengxi.fnplayer.core.Net;
import com.fengxi.fnplayer.data.Config;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「prelude / 阅读」式 JS 源脚本引擎（Rhino 精简版）。
 * 脚本协议（与红果短剧源一致）：
 * <ul>
 *   <li>脚本里调用 {@code registerSource({...}).host} 拿到宿主对象 host；</li>
 *   <li>host 提供 getString/getJson/getBytes（网络）、readCache/writeCache/cacheAge（缓存）；</li>
 *   <li>脚本可用全局工具函数 items/int/str/float（源脚本自带引用但未定义，由本引擎补齐）；</li>
 *   <li>源对象约定方法：categoryMenus / categoryPage / search / bookDetail / video。</li>
 * </ul>
 * 所有调用在单线程池里执行（Rhino Context 按线程绑定）。
 */
public final class JsSource {

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    /** 已加载脚本实例：key = 源id + 脚本指纹（脚本变更自动重载） */
    private static final Map<String, Instance> LOADED =
            new LinkedHashMap<String, Instance>();

    private JsSource() {}

    // ==================== 已加载实例 ====================

    public static final class Instance {
        public final String id;
        final Scriptable scope;
        final Scriptable src;

        Instance(String id, Scriptable scope, Scriptable src) {
            this.id = id;
            this.scope = scope;
            this.src = src;
        }
    }

    /** 拿到（必要时加载）一个源实例；脚本加载失败抛异常，消息给前端。 */
    public static Instance get(final String id, final String script) throws Exception {
        final String key = id + ":" + String.valueOf(script == null ? "" : script.hashCode());
        Instance in = LOADED.get(key);
        if (in != null) return in;
        // 清掉同 id 旧版本
        for (String k : LOADED.keySet().toArray(new String[0])) {
            if (k.startsWith(id + ":")) LOADED.remove(k);
        }
        if (script == null || script.trim().length() == 0)
            throw new Exception("源脚本为空");
        in = POOL.submit(new java.util.concurrent.Callable<Instance>() {
            @Override public Instance call() throws Exception {
                return load(id, script);
            }
        }).get();
        LOADED.put(key, in);
        return in;
    }

    private static Instance load(String id, String script) throws Exception {
        Context cx = Context.enter();
        cx.setOptimizationLevel(-1);          // Android 上只能解释执行
        cx.setLanguageVersion(Context.VERSION_1_7);
        try {
            final Scriptable scope = cx.initStandardObjects();
            final Scriptable[] reg = new Scriptable[1];

            // registerSource(srcObj) → { host: hostObj }，并记录源对象
            scope.put("registerSource", scope, new BaseFunction() {
                @Override public Object call(Context c, Scriptable s,
                                             Scriptable thisObj, Object[] a) {
                    if (a.length > 0 && a[0] instanceof Scriptable) reg[0] = (Scriptable) a[0];
                    Scriptable out = c.newObject(s);
                    out.put("host", out, buildHost(c, s));
                    return out;
                }
            });

            // 全局工具函数（源脚本引用但未自带）
            scope.put("items", scope, num1("items"));
            scope.put("int", scope, num1("int"));
            scope.put("str", scope, num1("str"));
            scope.put("float", scope, num1("float"));

            cx.evaluateString(scope, script, "source-" + id + ".js", 1, null);
            if (reg[0] == null)
                throw new Exception("脚本没有调用 registerSource（可能不是本 App 支持的源格式）");
            return new Instance(id, scope, reg[0]);
        } finally {
            Context.exit();
        }
    }

    /** items/int/str/float 的宽容实现：对象取键，数组安全下标。 */
    private static BaseFunction num1(final String name) {
        return new BaseFunction() {
            @Override public Object call(Context c, Scriptable s,
                                         Scriptable thisObj, Object[] a) {
                try {
                    if ("items".equals(name)) {
                        Object v = a.length > 0 ? a[0] : null;
                        String key = a.length > 1 ? String.valueOf(a[1]) : "";
                        Object r = keyOf(v, key);
                        return r == null ? c.newArray(s, 0) : r;
                    }
                    Object v = a.length > 0 ? a[0] : null;
                    String key = a.length > 1 ? String.valueOf(a[1]) : "";
                    double def = a.length > 2 && a[2] instanceof Number
                            ? ((Number) a[2]).doubleValue() : 0;
                    Object r = keyOf(v, key);
                    if (r instanceof Number) return r;
                    if (r != null) {
                        try { return Double.parseDouble(String.valueOf(r)); }
                        catch (Exception ignored) {}
                    }
                    return def;
                } catch (Throwable t) {
                    return "items".equals(name) ? c.newArray(s, 0) : 0;
                }
            }
        };
    }

    private static Object keyOf(Object o, String key) {
        if (o instanceof Scriptable && key.length() > 0) {
            try {
                Object v = ((Scriptable) o).get(key, (Scriptable) o);
                return (v == Scriptable.NOT_FOUND) ? null : v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ==================== host 对象 ====================

    private static Scriptable buildHost(Context cx, final Scriptable scope) {
        Scriptable host = cx.newObject(scope);
        host.put("getString", host, hostFn("getString"));
        host.put("getHtml", host, hostFn("getString"));
        host.put("getJson", host, hostFn("getJson"));
        host.put("getBytes", host, hostFn("getBytes"));
        host.put("readCache", host, hostFn("readCache"));
        host.put("writeCache", host, hostFn("writeCache"));
        host.put("cacheAge", host, hostFn("cacheAge"));
        return host;
    }

    private static BaseFunction hostFn(final String name) {
        return new BaseFunction() {
            @Override public Object call(Context c, Scriptable s,
                                         Scriptable thisObj, Object[] a) {
                try {
                    return doHost(c, s, name, a);
                } catch (Throwable t) {
                    if ("readCache".equals(name)) return null;
                    if ("cacheAge".equals(name)) return -1;
                    if ("getBytes".equals(name)) return c.newArray(s, 0);
                    throw new RuntimeException(t.getMessage() == null
                            ? t.getClass().getSimpleName() : t.getMessage(), t);
                }
            }
        };
    }

    private static Object doHost(Context cx, Scriptable scope, String name,
                                 Object[] a) throws Exception {
        String url = a.length > 0 ? String.valueOf(a[0]) : "";
        Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
        headers.put("User-Agent", Net.UA);
        if (a.length > 1 && a[1] instanceof Scriptable) {
            Object hs = ((Scriptable) a[1]).get("headers", (Scriptable) a[1]);
            if (hs instanceof Scriptable) {
                for (Object pid : ScriptableObject.getPropertyIds((Scriptable) hs)) {
                    Object v = ((Scriptable) hs).get(String.valueOf(pid), (Scriptable) hs);
                    if (v != null && v != Scriptable.NOT_FOUND)
                        headers.put(String.valueOf(pid), String.valueOf(v));
                }
            }
        }
        if ("readCache".equals(name)) {
            SharedPreferences sp = Config.spCache();
            String raw = sp.getString("jsc_" + url, "");
            return raw.length() == 0 ? null : raw;
        }
        if ("cacheAge".equals(name)) {
            SharedPreferences sp = Config.spCache();
            long t = sp.getLong("jsc_t_" + url, -1);
            if (t < 0) return -1;
            return (System.currentTimeMillis() - t) / 60000L;   // 分钟
        }
        if ("writeCache".equals(name)) {
            String v = a.length > 1 && a[1] != null ? String.valueOf(a[1]) : "";
            SharedPreferences sp = Config.spCache();
            sp.edit().putString("jsc_" + url, v)
                    .putLong("jsc_t_" + url, System.currentTimeMillis()).apply();
            return null;
        }

        Net.Resp resp = Net.get(url, headers, true, 20000);
        if (resp.error.length() > 0 || resp.code >= 400)
            throw new Exception("HTTP " + resp.code + (resp.error.length() > 0
                    ? " " + resp.error : "") + " " + url);
        if ("getBytes".equals(name)) {
            byte[] b = resp.bytes != null ? resp.bytes : resp.body.getBytes("UTF-8");
            Object[] arr = new Object[b.length];
            for (int i = 0; i < b.length; i++) arr[i] = (int) b[i];
            return cx.newArray(scope, arr);
        }
        if ("getJson".equals(name)) {
            // 注意：Rhino 1.7.14 的 NativeJSON.parse 传 null reviver 会 NPE，
            // 改用 JS 表达式求值（JSON 本身就是合法 JS 字面量）
            Object v = cx.evaluateString(scope, "(" + resp.body + ")", "<json>", 1, null);
            return v == null ? cx.newObject(scope) : v;
        }
        return resp.body;   // getString / getHtml
    }

    // ==================== 调源方法 ====================

    private static Object call(Instance in, String fn, Object... args) throws Exception {
        Context cx = Context.enter();
        cx.setOptimizationLevel(-1);
        try {
            Object f = in.src.get(fn, in.src);
            if (!(f instanceof Function))
                throw new Exception("源没有实现 " + fn);
            Object r = ((Function) f).call(cx, in.scope, in.src, args);
            if (r == null || r == Scriptable.NOT_FOUND) return null;
            // Scriptable → JSON 字符串 → org.json（对前端透明）。
            // ★ 注意：JSONTokener 必须调 nextValue() 才真正解析，只 new 是不解析的！
            Object j = NativeJSON.stringify(cx, in.scope, r, null, null);
            if (j == null) return null;
            String s = String.valueOf(j);
            if ("undefined".equals(s) || s.length() == 0) return null;
            return new org.json.JSONTokener(s).nextValue();
        } finally {
            Context.exit();
        }
    }

    /** 发现页分组+榜单 tabs：[[{title,tabs:[{title,url}]}]] → 扁平化为 org.json。 */
    public static JSONArray categoryMenus(final String id, final String script) throws Exception {
        Object r = call(get(id, script), "categoryMenus");
        return r instanceof JSONArray ? (JSONArray) r : new JSONArray();
    }

    /** 分类页 → {books:[{bookUrl,title,intro,coverUrl,status}]}。 */
    public static JSONObject categoryPage(final String id, final String script,
                                          final String url) throws Exception {
        Object r = call(get(id, script), "categoryPage", url);
        return r instanceof JSONObject ? (JSONObject) r : new JSONObject();
    }

    public static JSONObject search(final String id, final String script,
                                    final String kw, final int page) throws Exception {
        Object r = call(get(id, script), "search", kw, page);
        return r instanceof JSONObject ? (JSONObject) r : new JSONObject();
    }

    /** 详情 → {title,intro,coverUrl,episodes:[{title,url}]}。 */
    public static JSONObject bookDetail(final String id, final String script,
                                        final String bookUrl) throws Exception {
        Object r = call(get(id, script), "bookDetail", bookUrl, Boolean.TRUE, Boolean.FALSE);
        return r instanceof JSONObject ? (JSONObject) r : new JSONObject();
    }

    /** 剧集地址 → 可播直链。 */
    public static String video(final String id, final String script,
                               final String url) throws Exception {
        Object r = call(get(id, script), "video", url);
        return r == null ? "" : (r instanceof String ? (String) r : String.valueOf(r));
    }
}
