package com.tingshu.app.core.js;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ★ v4.20.4：洛雪（LX Music）音源脚本运行时。
 *
 * <p>LX 源脚本（如墨澜聚合音源）依赖宿主提供的 {@code globalThis.lx} 环境：
 * <ul>
 *   <li>{@code lx.on(EVENT_NAMES.request, handler)} —— 注册取链处理器；</li>
 *   <li>{@code lx.send(EVENT_NAMES.inited, payload)} —— 上报音源清单；</li>
 *   <li>{@code lx.request(url, options, callback)} —— HTTP（同步实现）；</li>
 *   <li>全局 {@code setTimeout / clearTimeout / console}。</li>
 * </ul>
 *
 * <p>LX 源只有取链没有搜索 —— App 侧配套由 {@link
 * com.tingshu.app.source.LxSourceAdapter} 提供酷我曲库搜索，取链走脚本本体，
 * **不修改源文件**（配合 {@link JsCompat} 语法降级原样执行）。
 */
public final class LxRuntime {

    private LxRuntime() {}

    private static final String HANDLERS = "__lxHandlers";
    private static final String INITED = "__lxInited";

    /**
     * ★ v4.21.6：单 JS 线程（Actor 模型）。
     *
     * <p>根因：Rhino 的 Promise 微任务队列是「每个 Context 独立」的
     * （{@code Context.microtasks} 数组），而我们原先派发、定时器、HTTP 回调
     * 散落在不同线程的不同 Context 里，各队列互相看不见，泵也泵不动 ——
     * 墨澜酷我后端1（纯拼 URL、零 HTTP）都会 45s 不落定就是铁证。
     *
     * <p>修复：所有脚本执行收口到这一条线程（同一 Context → 同一条微任务队列），
     * HTTP 放线程池、回调投回本线程，定时器只负责到点投递。
     * JS 本来就是单线程语义，这里的串行化不影响正确性。
     */
    private static final java.util.concurrent.ExecutorService JS =
            Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "lx-js");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** LX 脚本的 HTTP 池（不占 JS 线程） */
    private static final java.util.concurrent.ExecutorService HTTP =
            Executors.newFixedThreadPool(4, new java.util.concurrent.ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger SEQ =
                        new java.util.concurrent.atomic.AtomicInteger();
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "lx-http-" + SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    /** setTimeout 定时器（LX 脚本的超时保护 / 限流 sleep 用）——只负责到点投递到 JS 线程 */
    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "lx-timer");
                    t.setDaemon(true);
                    return t;
                }
            });
    private static final AtomicInteger TIMER_SEQ = new AtomicInteger(1);
    private static final Map<Integer, ScheduledFuture<?>> TIMER_JOBS = new ConcurrentHashMap<>();

    /** 脚本是否为 LX 源（含 lx.on / EVENT_NAMES 特征） */
    public static boolean looksLikeLx(String code) {
        return code != null && (code.contains("globalThis.lx") || code.contains("lx.on("))
                && code.contains("EVENT_NAMES");
    }

    /** 脚本加载后是否注册了 request 处理器（可对外提供取链） */
    public static boolean hasRequestHandler(JsEngine.Scope scope) {
        try {
            Scriptable g = scope.global();
            Object h = ScriptableObject.getProperty(g, HANDLERS);
            if (!(h instanceof Scriptable)) return false;
            Object req = ScriptableObject.getProperty((Scriptable) h, "request");
            return req instanceof org.mozilla.javascript.Function;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读取脚本 send(EVENT_NAMES.inited) 上报的音源清单（JSON 字符串，无则 ""） */
    public static String initedJson(JsEngine.Scope scope) {
        try {
            Context cx = JsEngine.enter();
            try {
                Scriptable g = scope.global();
                Object v = ScriptableObject.getProperty(g, INITED);
                if (v == null || v == org.mozilla.javascript.Scriptable.NOT_FOUND
                        || v == org.mozilla.javascript.Undefined.instance) return "";
                Object s = org.mozilla.javascript.NativeJSON.stringify(
                        cx, g, v, org.mozilla.javascript.Undefined.instance,
                        org.mozilla.javascript.Undefined.instance);
                return s == null ? "" : Context.toString(s);
            } finally {
                JsEngine.exit();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 在作用域里安装 lx 运行时（须在脚本 {@code run} 之前调用）。
     *
     * @param scriptName 显示名（currentScriptInfo.name）
     * @param rawScript  脚本原文（currentScriptInfo.rawScript，部分脚本读头部注释）
     */
    public static void install(final JsEngine.Scope scope, String scriptName, String rawScript) {
        Context cx = JsEngine.enter();
        try {
            Scriptable g = scope.global();

            // ---- console（路由到诊断日志，不影响播放）----
            // ★ v4.21.2：原来这里有「getProperty(g,"console")==Undefined.instance」的
            //   防重复判断，但属性不存在时 getProperty 返回的是 NOT_FOUND 而非
            //   Undefined.instance，条件永远为假 → console 从未装上，
            //   墨澜脚本第 953 行第一个 console.log 就 ReferenceError。
            //   改成无条件安装：putProperty 覆盖写，幂等安全。
            {
                Scriptable console = cx.newObject(g);
                BaseFunction log = new BaseFunction() {
                    @Override
                    public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                        try {
                            StringBuilder sb = new StringBuilder("[LX] ");
                            for (int i = 0; i < a.length; i++) {
                                if (i > 0) sb.append(' ');
                                sb.append(Context.toString(a[i]));
                            }
                            com.tingshu.app.core.PlayerLog.log(sb.toString());
                        } catch (Throwable ignored) {}
                        return org.mozilla.javascript.Undefined.instance;
                    }
                };
                console.put("log", console, log);
                console.put("info", console, log);
                console.put("warn", console, log);
                console.put("error", console, log);
                ScriptableObject.putProperty(g, "console", console);
            }

            // ---- setTimeout / clearTimeout（真实定时器：限流 sleep 依赖） ----
            ScriptableObject.putProperty(g, "setTimeout", new BaseFunction() {
                @Override
                public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                    if (a.length < 1 || !(a[0] instanceof org.mozilla.javascript.Function))
                        return 0;
                    final org.mozilla.javascript.Function fn =
                            (org.mozilla.javascript.Function) a[0];
                    long ms = a.length > 1 ? (long) Context.toNumber(a[1]) : 0;
                    final int id = TIMER_SEQ.getAndIncrement();
                    final Scriptable scopeG = g;
                    try {
                        TIMER_JOBS.put(id, TIMER.schedule(new Runnable() {
                            @Override
                            public void run() {
                            TIMER_JOBS.remove(id);
                            // ★ v4.21.6：到点只投递，脚本一律在 JS 线程跑
                            JS.execute(new Runnable() {
                                @Override
                                public void run() {
                                Context tc = JsEngine.enter();
                                try {
                                    fn.call(tc, scopeG, fn, new Object[0]);
                                    tc.processMicrotasks();
                                } catch (Throwable t) {
                                    try {
                                        com.tingshu.app.core.PlayerLog.log(
                                                "[LX] 定时器回调异常：" + t);
                                    } catch (Throwable ignored) {}
                                } finally {
                                    JsEngine.exit();
                                }
                                }
                            });
                            }
                        }, Math.max(0, ms), TimeUnit.MILLISECONDS));
                    } catch (Throwable ignored) {}
                    return id;
                }
            });
            ScriptableObject.putProperty(g, "clearTimeout", new BaseFunction() {
                @Override
                public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                    try {
                        if (a.length > 0) {
                            ScheduledFuture<?> f = TIMER_JOBS.remove(
                                    (int) Context.toNumber(a[0]));
                            if (f != null) f.cancel(false);
                        }
                    } catch (Throwable ignored) {}
                    return org.mozilla.javascript.Undefined.instance;
                }
            });

            // ---- lx 对象本体 ----
            Scriptable lx = cx.newObject(g);
            Scriptable evn = cx.newObject(lx);
            evn.put("request", evn, "request");
            evn.put("inited", evn, "inited");
            evn.put("updateAlert", evn, "updateAlert");
            lx.put("EVENT_NAMES", lx, evn);
            lx.put("version", lx, "2.9.0");

            // on(name, handler) → 存进 __lxHandlers
            ScriptableObject.putProperty(g, HANDLERS, cx.newObject(g));
            lx.put("on", lx, new BaseFunction() {
                @Override
                public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                    if (a.length >= 2 && a[0] != null) {
                        try {
                            Scriptable hs = (Scriptable) ScriptableObject.getProperty(g, HANDLERS);
                            hs.put(Context.toString(a[0]), hs, a[1]);
                        } catch (Throwable ignored) {}
                    }
                    return org.mozilla.javascript.Undefined.instance;
                }
            });

            // send(name, data) → inited 载荷捕获（音源清单）
            lx.put("send", lx, new BaseFunction() {
                @Override
                public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                    if (a.length >= 2 && "inited".equals(Context.toString(a[0]))) {
                        ScriptableObject.putProperty(g, INITED, a[1]);
                    }
                    return org.mozilla.javascript.Undefined.instance;
                }
            });

            // request(url, options, callback) —— 同步 HTTP，回调立即触发
            lx.put("request", lx, new BaseFunction() {
                @Override
                public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                    String url = a.length > 0 ? Context.toString(a[0]) : "";
                    String method = "GET";
                    java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
                    String body = null;
                    if (a.length > 1 && a[1] instanceof Scriptable) {
                        Scriptable o = (Scriptable) a[1];
                        try {
                            Object m = ScriptableObject.getProperty(o, "method");
                            if (m != org.mozilla.javascript.Undefined.instance)
                                method = Context.toString(m);
                        } catch (Throwable ignored) {}
                        try {
                            Object b = ScriptableObject.getProperty(o, "body");
                            if (b != null && b != org.mozilla.javascript.Undefined.instance)
                                body = Context.toString(b);
                        } catch (Throwable ignored) {}
                        try {
                            Object h = ScriptableObject.getProperty(o, "headers");
                            if (h instanceof Scriptable) {
                                Scriptable hs = (Scriptable) h;
                                Object ids = hs.getIds();
                                if (ids instanceof Object[]) {
                                    for (Object k : (Object[]) ids) {
                                        Object v = ScriptableObject.getProperty(hs, Context.toString(k));
                                        if (v != org.mozilla.javascript.Undefined.instance)
                                            headers.put(Context.toString(k), Context.toString(v));
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    org.mozilla.javascript.Function cb = a.length > 2
                            && a[2] instanceof org.mozilla.javascript.Function
                            ? (org.mozilla.javascript.Function) a[2] : null;
                    if (cb == null) return org.mozilla.javascript.Undefined.instance;

                    // ★ v4.21.6：HTTP 移到线程池，回调投回 JS 线程 —— LX 的 request
                    //   本来就是异步回调语义，脚本侧的 JS 超时（setTimeout reject）
                    //   从此真正生效（原先同步阻塞把超时机制整个架空）。
                    final String fUrl = url;
                    final String fMethod = method;
                    final java.util.Map<String, String> fHeaders = headers;
                    final String fBody = body;
                    final org.mozilla.javascript.Function fCb = cb;
                    final Scriptable fG = g;
                    HTTP.execute(new Runnable() {
                        @Override
                        public void run() {
                            Object errArg = org.mozilla.javascript.Undefined.instance;
                            byte[] data = null;
                            String errMsg = null;
                            long t0 = android.os.SystemClock.elapsedRealtime();
                            try {
                                data = http(fUrl, fMethod, fHeaders, fBody);
                            } catch (Throwable e) {
                                errMsg = e.getMessage() == null
                                        ? e.getClass().getSimpleName() : e.getMessage();
                            }
                            final long dur = android.os.SystemClock.elapsedRealtime() - t0;
                            final byte[] fData = data;
                            final String fErrMsg = errMsg;
                            JS.execute(new Runnable() {
                                @Override
                                public void run() {
                                    Context c2 = JsEngine.enter();
                                    try {
                                        Object errArg = org.mozilla.javascript.Undefined.instance;
                                        Object respArg = org.mozilla.javascript.Undefined.instance;
                                        if (fData != null) {
                                            Scriptable resp = c2.newObject(fG);
                                            resp.put("statusCode", resp, 200);
                                            resp.put("body", resp, new String(fData, "UTF-8"));
                                            resp.put("headers", resp, c2.newObject(fG));
                                            respArg = resp;
                                            com.tingshu.app.core.PlayerLog.log("[LX] HTTP "
                                                    + fMethod + " "
                                                    + fUrl.substring(0, Math.min(fUrl.length(), 110))
                                                    + " → " + fData.length + "B（" + dur + "ms）");
                                        } else {
                                            Scriptable eo = c2.newObject(fG, "Error",
                                                    new Object[]{fErrMsg});
                                            errArg = eo;
                                            com.tingshu.app.core.PlayerLog.log("[LX] HTTP "
                                                    + fMethod + " "
                                                    + fUrl.substring(0, Math.min(fUrl.length(), 110))
                                                    + " ✗ " + fErrMsg + "（" + dur + "ms）");
                                        }
                                        fCb.call(c2, fG, fCb,
                                                new Object[]{errArg, respArg});
                                        c2.processMicrotasks();
                                    } catch (Throwable t) {
                                        try {
                                            com.tingshu.app.core.PlayerLog.log(
                                                    "[LX] request 回调异常：" + t);
                                        } catch (Throwable ignored) {}
                                    } finally {
                                        JsEngine.exit();
                                    }
                                }
                            });
                        }
                    });
                    return org.mozilla.javascript.Undefined.instance;
                }
            });

            // utils.buffer.bufToString —— 脚本用 ?. 探测，缺省也安全；给了更稳
            Scriptable utils = cx.newObject(lx);
            Scriptable buffer = cx.newObject(utils);
            buffer.put("bufToString", buffer, new BaseFunction() {
                @Override
                public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                    // request 同步实现里 body 恒为字符串，直接透传
                    return a.length > 0 ? Context.toString(a[0])
                            : org.mozilla.javascript.Undefined.instance;
                }
            });
            utils.put("buffer", utils, buffer);
            lx.put("utils", lx, utils);

            // currentScriptInfo
            Scriptable csi = cx.newObject(lx);
            csi.put("name", csi, scriptName == null ? "" : scriptName);
            csi.put("rawScript", csi, rawScript == null ? "" : rawScript);
            lx.put("currentScriptInfo", lx, csi);

            ScriptableObject.putProperty(g, "lx", lx);

            // ---- musicUrl 取链驱动（给 LxSourceAdapter 用） ----
            ScriptableObject.putProperty(g, "__lxDispatch", new BaseFunction() {
                @Override
                public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                    Scriptable hs = (Scriptable) ScriptableObject.getProperty(g, HANDLERS);
                    Object handler = hs == null ? null
                            : ScriptableObject.getProperty(hs, "request");
                    if (!(handler instanceof org.mozilla.javascript.Function))
                        throw Context.throwAsScriptRuntimeEx(
                                new IllegalStateException("源没有注册 request 处理器"));
                    Scriptable info = c.newObject(g);
                    Scriptable mi = c.newObject(g);
                    String mid = a.length > 2 ? Context.toString(a[2]) : "";
                    mi.put("songmid", mi, mid);
                    mi.put("id", mi, mid);
                    mi.put("songId", mi, mid);
                    mi.put("musicId", mi, mid);
                    mi.put("hash", mi, "");
                    info.put("musicInfo", info, mi);
                    info.put("type", info, a.length > 3 ? Context.toString(a[3]) : "master");
                    Scriptable req = c.newObject(g);
                    req.put("action", req, a.length > 0 ? a[0] : "musicUrl");
                    req.put("source", req, a.length > 1 ? a[1] : "kw");
                    req.put("info", req, info);
                    return ((org.mozilla.javascript.Function) handler)
                            .call(c, g, (Scriptable) handler, new Object[]{req});
                }
            });
        } finally {
            JsEngine.exit();
        }
    }

    /**
     * 调用脚本的 musicUrl 处理器。
     *
     * @return 播放直链
     * @throws Exception 脚本取链失败（含全部后端错误信息）
     */
    public static String resolveMusicUrl(JsEngine.Scope scope, String source,
                                         String musicId, String quality) throws Exception {
        final Object[] out = new Object[2];   // [url, err]
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final String mid = musicId == null ? "" : musicId.replaceAll("['\\\\]", "");
        final String q = quality == null ? "master" : quality.replaceAll("['\\\\]", "");
        final String src = source == null ? "kw" : source.replaceAll("['\\\\]", "");
        com.tingshu.app.core.PlayerLog.log("[LX] 取链派发 " + src + ":" + musicId
                + " 音质=" + quality);
        // ★ v4.21.6：整个派发过程收口到 JS 线程 —— 派发、回调、定时器全在同一条
        //   线程/同一个 Context 上，微任务队列唯一，每步执行完就地泵干净。
        final Scriptable g = scope.global();
        JS.execute(new Runnable() {
            @Override
            public void run() {
                Context cx = JsEngine.enter();
                try {
                    ScriptableObject.putProperty(g, "__lxJavaCb", new BaseFunction() {
                        @Override
                        public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                            try {
                                out[0] = a.length > 0
                                        && a[0] != org.mozilla.javascript.Undefined.instance
                                        ? Context.toString(a[0]) : null;
                                out[1] = a.length > 1
                                        && a[1] != org.mozilla.javascript.Undefined.instance
                                        ? Context.toString(a[1]) : null;
                            } catch (Throwable e) {
                                out[1] = String.valueOf(e);
                            }
                            try {
                                com.tingshu.app.core.PlayerLog.log("[LX] 取链回调 "
                                        + (out[0] != null
                                                ? "url=" + String.valueOf(out[0]).substring(0,
                                                        Math.min(String.valueOf(out[0]).length(), 100))
                                                : "err=" + out[1]));
                            } catch (Throwable ignored) {}
                            latch.countDown();
                            return org.mozilla.javascript.Undefined.instance;
                        }
                    });
                    cx.evaluateString(g,
                            "__lxDispatch('musicUrl', '" + src + "', '" + mid + "', '" + q + "')" +
                                    " .then(function(v){ __lxJavaCb.done(v, null) }," +
                                    "     function(e){ __lxJavaCb.done(null, e && e.message ? e.message : String(e)) });",
                            "lxResolve", 1, null);
                    cx.processMicrotasks();
                } catch (Throwable t) {
                    out[1] = String.valueOf(t);
                    latch.countDown();
                } finally {
                    JsEngine.exit();
                }
            }
        });
        // 调用线程只管等：HTTP 在池里跑、回调/定时器在 JS 线程泵，45s 兜底
        try {
            if (!latch.await(45, TimeUnit.SECONDS)) {
                com.tingshu.app.core.PlayerLog.log(
                        "[LX] 取链 45s 未落定：脚本链路卡死（详见上方 HTTP/回调日志）");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (out[1] != null) throw new Exception(String.valueOf(out[1]));
        if (out[0] == null || out[0].toString().length() == 0)
            throw new Exception("取链超时：音源脚本未返回结果（请重试或换源）");
        return out[0].toString();
    }

    /** 同步 HTTP（支持自定义头 / POST body） */
    private static byte[] http(String url, String method,
                               Map<String, String> headers, String body) throws Exception {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        // ★ v4.21.4：收紧超时（原来 10s/20s）—— LX 脚本普遍多后端串行轮询，
        //   单后端挂 30 秒会拖爆整条取链链路（脚本自身还有 15s JS 层超时兜底）
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod(method == null ? "GET" : method);
        boolean hasUa = false;
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null) continue;
                if ("User-Agent".equalsIgnoreCase(e.getKey())) hasUa = true;
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        if (!hasUa) conn.setRequestProperty("User-Agent", com.tingshu.app.core.Net.UA);
        if (body != null && !"GET".equalsIgnoreCase(method)) {
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes("UTF-8"));
        }
        int code = conn.getResponseCode();
        java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
        if (in != null) in.close();
        if (code >= 400) throw new Exception("HTTP " + code);
        return bos.toByteArray();
    }

    /** 释放定时器线程（仅测试用；App 生命周期内常驻） */
    static void shutdownForTest() {
        TIMER.shutdownNow();
    }

    /** NativeObject 引用占位（避免多余 import 警告） */
    @SuppressWarnings("unused")
    private static Class<?> keepImport() { return NativeObject.class; }
}
