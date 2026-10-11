package com.tingshu.app.core.js;

import android.util.Base64;
import android.util.Log;

import com.tingshu.app.core.Json;
import com.tingshu.app.core.Net;
import com.tingshu.app.core.Utils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * ★ v3.0：JS 插件的 {@code this.host} 对象 —— 宿主能力适配层。
 *
 * <p>这是整个 JS 插件体系里**最核心的一块**：契约里所有 {@code host.*} 方法
 * 必须**同步阻塞返回**（脚本里没有任何 Promise/回调，全是直线代码），
 * 所以这里全部直接调用同步的 Net / jsoup / 本地文件，不做任何异步派发。
 *
 * <p>调用线程：{@code search/detail/audio} 跑在 {@code Aggregator} 的线程池上，
 * 因此在这里 {@code Thread.sleep} 或发长请求都不会卡 UI。
 *
 * <h3>方法清单（与插件契约一一对应）</h3>
 * <pre>
 *   getString / getHtml / getJson / getStrings     网络取数
 *   parseHtml                                       HTML → DOM 壳
 *   render / sniff                                  WebView 渲染 / 媒体嗅探
 *   progress / sleep / log / toast                  过程控制
 *   readCache / writeCache / cacheAge               持久缓存
 *   finalUrl / htmlDecode / base64Decode
 *   aesEcbDecrypt / uuid / base                     杂项
 * </pre>
 */
public final class JsHostApi {

    private static final String TAG = "JsHost";

    /** 该 host 属于哪个插件（缓存隔离用） */
    private final String pluginId;
    /** 源的基础地址（供 host.base 用） */
    private final String base;
    /** 进度/提示的回调（由 Bridge 注入，可为 null） */
    private final Callback cb;

    public interface Callback {
        /** 长时间任务的进度文案；null 表示清除 */
        void onProgress(String text);
        /** 用户可见提示 */
        void onToast(String msg);
    }

    public JsHostApi(String pluginId, String base, Callback cb) {
        this.pluginId = pluginId == null ? "unknown" : pluginId;
        this.base = base == null ? "" : base;
        this.cb = cb;
    }

    // ==================== 构建 JS 对象 ====================

    /**
     * 把 host 对象挂到 JS 作用域上。
     * 每个源各一份（因为 base 不同），但共用一个 JsHostApi 实例也没问题。
     */
    public Scriptable build(Context cx, Scriptable scope) {
        Scriptable host = cx.newObject(scope);

        put(host, "getString", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                return getString(c, s, a);
            }
        });
        put(host, "getHtml", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                return getHtml(c, s, a);
            }
        });
        put(host, "getJson", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                return getJson(c, s, a);
            }
        });
        put(host, "getStrings", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                return getStrings(c, s, a);
            }
        });
        put(host, "parseHtml", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                if (a.length < 1) return JsElement.none();
                String html = Context.toString(a[0]);
                String b = a.length > 1 ? Context.toString(a[1]) : base;
                try {
                    Document doc = Jsoup.parse(html, b);
                    return JsElement.of(doc);
                } catch (Throwable e) {
                    return JsElement.none();
                }
            }
        });
        put(host, "render", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                return render(c, s, a);
            }
        });
        put(host, "sniff", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                return sniff(c, s, a);
            }
        });
        put(host, "progress", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                String txt = (a.length < 1 || a[0] == null || a[0] == Undefined.instance)
                        ? null : Context.toString(a[0]);
                if (cb != null) try { cb.onProgress(txt); } catch (Throwable ignored) {}
                return Undefined.instance;
            }
        });
        put(host, "sleep", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                long ms = a.length > 0 ? (long) Context.toNumber(a[0]) : 0;
                sleepInterruptibly(ms);
                return Undefined.instance;
            }
        });
        put(host, "log", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                if (a.length > 0) Log.d(TAG, Context.toString(a[0]));
                return Undefined.instance;
            }
        });
        put(host, "toast", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                if (a.length > 0 && cb != null) {
                    try { cb.onToast(Context.toString(a[0])); } catch (Throwable ignored) {}
                }
                return Undefined.instance;
            }
        });
        put(host, "readCache", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                if (a.length < 1) return null;
                String v = JsPluginKV.read(pluginId, Context.toString(a[0]));
                return v == null ? null : v;
            }
        });
        put(host, "writeCache", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                if (a.length >= 2) {
                    JsPluginKV.write(pluginId, Context.toString(a[0]), Context.toString(a[1]));
                }
                return Undefined.instance;
            }
        });
        put(host, "cacheAge", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                if (a.length < 1) return -1d;
                return (double) JsPluginKV.age(pluginId, Context.toString(a[0]));
            }
        });
        put(host, "finalUrl", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                if (a.length < 1) return "";
                return finalUrl(Context.toString(a[0]), a.length > 1 ? a[1] : null);
            }
        });
        put(host, "htmlDecode", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                if (a.length < 1) return "";
                return Utils.unescape(Context.toString(a[0]));
            }
        });
        put(host, "base64Decode", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                if (a.length < 1) return "";
                return base64Decode(Context.toString(a[0]));
            }
        });
        put(host, "aesEcbDecrypt", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                if (a.length < 2) return "";
                return aesEcbDecrypt(Context.toString(a[0]), Context.toString(a[1]));
            }
        });
        put(host, "uuid", new BaseFunction() {
            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                return UUID.randomUUID().toString();
            }
        });
        // 预留给插件读取自身 base（当前脚本未用到）
        host.put("base", host, base);
        return host;
    }

    private static void put(Scriptable host, String name, Object fn) {
        ScriptableObject.putProperty(host, name, fn);
    }

    // ==================== HTTP ====================

    /** 把 JS 的 opts 对象翻译成 Net 需要的 (referer, extraHeaders, form, encoding) */
    private static class Opts {
        String referer;
        Map<String, String> headers = new HashMap<String, String>();
        String form;          // 非空 = POST
        String encoding;      // 响应重解码用
        boolean desktop;

        static Opts parse(Object o) {
            Opts p = new Opts();
            if (!(o instanceof Scriptable)) return p;
            Scriptable s = (Scriptable) o;
            p.desktop = boolOf(s, "desktop");
            p.form = strOf(s, "body");
            p.encoding = strOf(s, "encoding");
            String method = strOf(s, "method");
            if (p.form == null && "POST".equalsIgnoreCase(method)) p.form = "";

            Object h = ScriptableObject.getProperty(s, "headers");
            if (h instanceof Scriptable) {
                Scriptable hs = (Scriptable) h;
                for (Object k : hs.getIds()) {
                    if (!(k instanceof String)) continue;
                    Object v = ScriptableObject.getProperty(hs, (String) k);
                    if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) continue;
                    String val = Context.toString(v);
                    if ("Referer".equalsIgnoreCase((String) k)) {
                        p.referer = val;
                    } else {
                        p.headers.put((String) k, val);
                    }
                }
            }
            return p;
        }

        private static boolean boolOf(Scriptable s, String k) {
            Object v = ScriptableObject.getProperty(s, k);
            if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return false;
            return Context.toBoolean(v);
        }

        private static String strOf(Scriptable s, String k) {
            Object v = ScriptableObject.getProperty(s, k);
            if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return null;
            String r = Context.toString(v);
            return r.length() == 0 ? null : r;
        }
    }

    /** 统一发请求。desktop=true 时换桌面 UA，覆盖默认移动 UA */
    private Net.Resp request(String url, Opts opts) {
        if (opts == null) opts = new Opts();
        Map<String, String> h = new HashMap<String, String>(opts.headers);
        if (opts.desktop) h.put("User-Agent", Net.UA_DESKTOP);
        boolean binary = opts.encoding != null;
        return Net.request(url, opts.form, opts.referer, h, binary);
    }

    private Object getString(Context cx, Scriptable scope, Object[] a) {
        if (a.length < 1) return "";
        String url = Context.toString(a[0]);
        Opts opts = Opts.parse(a.length > 1 ? a[1] : null);
        Net.Resp r = request(url, opts);
        if (r == null) throw jsError(0, "请求失败：" + url, false);
        if (r.code >= 400) throw jsError(r.code, "HTTP " + r.code + "：" + url, r.code == 429);
        if (opts.encoding != null && r.bytes != null) {
            // 契约支持 opts.encoding：用指定编码重新解码（Net 内部只按 Content-Type 猜）
            try {
                return new String(r.bytes, Charset.forName(opts.encoding));
            } catch (Throwable ignored) {}
        }
        return r.body == null ? "" : r.body;
    }

    private Object getHtml(Context cx, Scriptable scope, Object[] a) {
        if (a.length < 1) return JsElement.none();
        String url = Context.toString(a[0]);
        Opts opts = Opts.parse(a.length > 1 ? a[1] : null);
        Net.Resp r = request(url, opts);
        if (r == null) throw jsError(0, "请求失败：" + url, false);
        if (r.code >= 400) throw jsError(r.code, "HTTP " + r.code + "：" + url, r.code == 429);
        try {
            return JsElement.of(Jsoup.parse(r.body == null ? "" : r.body, url));
        } catch (Throwable e) {
            return JsElement.none();
        }
    }

    private Object getJson(Context cx, Scriptable scope, Object[] a) {
        if (a.length < 1) return cx.newObject(scope);
        String url = Context.toString(a[0]);
        Opts opts = Opts.parse(a.length > 1 ? a[1] : null);
        Net.Resp r = request(url, opts);
        if (r == null) throw jsError(0, "请求失败：" + url, false);
        if (r.code >= 400) throw jsError(r.code, "HTTP " + r.code + "：" + url, r.code == 429);
        String body = r.body == null ? "" : r.body;
        // ★ 关键：直接让 Rhino 解析成**原生 JS 对象**，
        //   脚本里才能用 str(obj,'a.b.0.c') / obj.data.list[0].title 这类写法。
        //   若用 org.json 的 JSONObject（javaToJS 包成 NativeJavaObject），
        //   属性访问语义会不一致。
        try {
            Object parsed = NativeJSON.parse(cx, scope, Json.clean(body),
                    new org.mozilla.javascript.Callable() {
                        /**
                         * ★ 注意 Rhino 的 reviver 契约：args = [key, value]，
                         *   **args[0] 是 key（String），args[1] 才是 value**。
                         *   返回 args[0] 会把整个 JSON 结构改坏（String 当 Scriptable 用，
                         *   抛 ClassCastException，被外层 catch 成"不是合法 JSON"）。
                         *   返回 undefined 会**删除**该键。这里必须原样返回 args[1]。
                         */
                        public Object call(Context c, Scriptable s, Scriptable t, Object[] args) {
                            return args.length > 1 ? args[1] : args.length > 0 ? args[0] : Undefined.instance;
                        }
                    });
            return parsed == null ? Undefined.instance : parsed;
        } catch (Throwable e) {
            throw jsError(r.code, "返回的不是合法 JSON：" + url, false);
        }
    }

    /** 并发批量取字符串（相声随身听建索引用，12 页并发） */
    private Object getStrings(Context cx, Scriptable scope, Object[] a) {
        // ★ 一律用 cx.newArray 建数组（带 prototype，脚本里能 .join/.indexOf）
        if (a.length < 1 || !(a[0] instanceof Scriptable)) return cx.newArray(scope, 0);
        Scriptable arr = (Scriptable) a[0];
        final Opts opts = Opts.parse(a.length > 1 ? a[1] : null);
        int conc = a.length > 2 ? (int) Context.toNumber(a[2]) : 4;
        if (conc <= 0) conc = 1;

        List<String> urls = new ArrayList<String>();
        int n = (int) Context.toNumber(ScriptableObject.getProperty(arr, "length"));
        for (int i = 0; i < n; i++) {
            Object v = arr.get(i, arr);
            if (v == null || v == Undefined.instance) continue;
            urls.add(Context.toString(v));
        }

        final String[] out = new String[urls.size()];
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(conc, Math.max(1, urls.size())));
        try {
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (int i = 0; i < urls.size(); i++) {
                final int idx = i;
                final String u = urls.get(i);
                futures.add(pool.submit(new Callable<Void>() {
                    public Void call() {
                        for (int attempt = 0; attempt < 2; attempt++) {
                            try {
                                Net.Resp r = request(u, opts);
                                if (r != null && r.code < 400) {
                                    out[idx] = r.body;
                                    return null;
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;   // 失败项保持 null（契约要求）
                    }
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(60, TimeUnit.SECONDS); } catch (Throwable ignored) {}
            }
        } finally {
            pool.shutdownNow();
        }

        Scriptable result = cx.newArray(scope, out.length);
        for (int i = 0; i < out.length; i++) {
            result.put(i, result, out[i] == null ? null : out[i]);
        }
        return result;
    }

    private Object render(Context cx, Scriptable scope, Object[] a) {
        if (a.length < 1) return "";
        String url = Context.toString(a[0]);
        Scriptable opts = (a.length > 1 && a[1] instanceof Scriptable) ? (Scriptable) a[1] : null;
        return WebViewRenderer.render(cx, scope, url, opts);
    }

    private Object sniff(Context cx, Scriptable scope, Object[] a) {
        if (a.length < 1) return "";
        String url = Context.toString(a[0]);
        Scriptable opts = (a.length > 1 && a[1] instanceof Scriptable) ? (Scriptable) a[1] : null;
        return WebViewRenderer.sniff(cx, scope, url, opts);
    }

    // ==================== 工具 ====================

    /** 跟随重定向取最终地址。Net.resolveLocation 内部用 HEAD，不下载正文。 */
    private String finalUrl(String url, Object extra) {
        try {
            Map<String, String> h = new HashMap<String, String>();
            if (extra instanceof Scriptable) {
                Scriptable s = (Scriptable) extra;
                for (Object k : s.getIds()) {
                    if (!(k instanceof String)) continue;
                    Object v = ScriptableObject.getProperty(s, (String) k);
                    if (v != null && v != Undefined.instance) {
                        h.put((String) k, Context.toString(v));
                    }
                }
            }
            String r = Net.resolveLocation(url, null, h);
            return r == null || r.length() == 0 ? url : r;
        } catch (Throwable t) {
            return url;
        }
    }

    /** Base64 解码（爱听书用它解反爬令牌） */
    private String base64Decode(String s) {
        try {
            byte[] raw = Base64.decode(s, Base64.DEFAULT);
            return new String(raw, Charset.forName("UTF-8"));
        } catch (Throwable t) {
            // 退一步：有些串是 URL-safe 或补了换行的
            try {
                byte[] raw = Base64.decode(s.replace('-', '+').replace('_', '/'),
                        Base64.NO_WRAP);
                return new String(raw, Charset.forName("UTF-8"));
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    /** AES-ECB/PKCS5 解密（喜马拉雅的播放地址） */
    private String aesEcbDecrypt(String cipherText, String key) {
        try {
            byte[] kb = key.getBytes(Charset.forName("UTF-8"));
            SecretKeySpec spec = new SecretKeySpec(kb, "AES");
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, spec);
            byte[] in;
            try {
                in = Base64.decode(cipherText, Base64.DEFAULT);
            } catch (Throwable t) {
                in = cipherText.getBytes(Charset.forName("UTF-8"));
            }
            byte[] out = c.doFinal(in);
            return new String(out, Charset.forName("UTF-8"));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 可中断的休眠（分片检查，避免 sleep 期间用户取消没反应） */
    private static void sleepInterruptibly(long ms) {
        long left = ms;
        while (left > 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new JsEngine.JsInterrupted("脚本被取消");
            }
            long step = Math.min(left, 200L);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JsEngine.JsInterrupted("脚本被取消");
            }
            left -= step;
        }
    }

    /**
     * 造一个 JS 侧的 Error 并抛出。
     * 契约要求错误对象带上 {@code status}（HTTP 码）、{@code rateLimited}（限流）、
     * {@code cancelled} 属性，脚本里会 {@code try/catch} 后判断。
     */
    private static RuntimeException jsError(int status, String msg, boolean rateLimited) {
        return new HostError(status, msg, rateLimited);
    }

    /** 带业务属性的宿主错误 */
    public static class HostError extends RuntimeException {
        public final int status;
        public final boolean rateLimited;

        public HostError(int status, String msg, boolean rateLimited) {
            super(msg);
            this.status = status;
            this.rateLimited = rateLimited;
        }
    }
}
