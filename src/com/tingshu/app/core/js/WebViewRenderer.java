package com.tingshu.app.core.js;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.tingshu.app.core.App;
import com.tingshu.app.core.Net;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ★ v3.0：后台 WebView 渲染器 —— 对应契约里的 {@code host.render} 与 {@code host.sniff}。
 *
 * <h3>为什么需要它</h3>
 * 有一批站点（念音网/有听网、悦听吧、爱听书……）的音频地址**不在 HTML 里**，
 * 而是页面上的 JS 跑完之后、或播放在 iframe 里加载媒体时才出现。纯 HTTP + 正则
 * 拿不到，必须真的用一个浏览器把页面跑起来。
 *
 * <h3>线程模型（这是最容易踩坑的地方）</h3>
 * <pre>
 *   worker 线程（脚本执行中）          主线程（UI）
 *   ─────────────────────────         ─────────────────────
 *   render(...)                        
 *     ├─ 建 WebView（必须在主线程）──→  new WebView + 设置 + loadUrl
 *     ├─ latch.await(timeout) 阻塞      onPageFinished
 *     │                                   └─ evaluateJavascript(script)
 *     │                                        └─ 拿到结果 → latch.countDown
 *     └─ 读结果返回给 JS
 * </pre>
 * 调用方在 worker 线程，所以这里可以放心 {@code latch.await()} 阻塞，不会卡 UI。
 *
 * <h3>WebView 生命周期</h3>
 * 每次调用**新建** WebView，用完 {@code destroy()} 掉。
 * 复用一个实例会踩「上一个页面在后台继续跑 JS、干扰下一个」的坑，
 * 而且多源并发渲染时复用完全没法用。
 */
public final class WebViewRenderer {

    private static final String TAG = "JsRender";

    /** 默认渲染超时 */
    private static final int DEFAULT_TIMEOUT = 30000;
    /** 渲染类源的并发上限（WebView 很重，不能像 HTTP 那样放 10 个） */
    private static final java.util.concurrent.Semaphore PERMITS =
            new java.util.concurrent.Semaphore(2);

    private WebViewRenderer() {}

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /**
     * ★ WebView 是**native 资源**，GC 管不了 —— 必须显式 {@code destroy()}。
     *
     * <p>这个类每次调用都 new 一个 WebView（避免上一页的 JS 干扰下一页），
     * 一次搜索里可能连续跑十几次渲染。不销毁的话，几轮下来就 native OOM。
     *
     * <p>做法：给每个 WebView 配一个「一次性销毁」的 Runnable，
     * 在结果拿到 / 超时 / 加载异常这三条路径上都会走到。
     */
    private static void destroyOnMain(final WebView wv, final AtomicBoolean destroyed) {
        UI.post(new Runnable() {
            public void run() {
                if (!destroyed.compareAndSet(false, true)) return;
                try {
                    wv.stopLoading();
                    wv.loadUrl("about:blank");
                    wv.removeAllViews();
                    wv.destroy();
                } catch (Throwable ignored) {}
            }
        });
    }

    // ==================== render ====================

    /**
     * 渲染页面并取结果。
     *
     * <p>两种返回形态（见插件契约）：
     * <ol>
     *   <li>给了 {@code opts.script}：在页面里执行它，**返回值即 render 的返回值**（通常是 HTML 片段）</li>
     *   <li>给了 {@code opts.parse}：页面加载完成后把 HTML 交给这个 JS 函数，取其返回值</li>
     * </ol>
     * 两者都给时优先 script（先把 script 结果拿到，再视情况交给 parse）。
     */
    public static Object render(final Context cx, final Scriptable scope,
                                final String url, final Scriptable opts) {
        final int timeout = intOpt(opts, "timeout", DEFAULT_TIMEOUT);
        final boolean desktop = boolOpt(opts, "desktop", false);
        final String script = strOpt(opts, "script");
        final Object parseFn = opts == null ? null
                : ScriptableObject.getProperty(opts, "parse");

        acquire();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> resultRef = new AtomicReference<String>(null);
        final AtomicReference<String> htmlRef = new AtomicReference<String>(null);
        // WebView 的销毁是一次性的，跨路径共享这个标记
        final AtomicBoolean destroyed = new AtomicBoolean(false);
        final AtomicReference<WebView> wvRef = new AtomicReference<WebView>(null);

        try {
            UI.post(new Runnable() {
                public void run() {
                    final WebView wv = newWebView(desktop);
                    wvRef.set(wv);
                    final AtomicBoolean done = new AtomicBoolean(false);

                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(final WebView view, String u) {
                            if (done.get()) return;
                            // 页面 DOM 就绪后：优先执行自定义 script
                            if (script != null && script.length() > 0) {
                                evalJs(view, script, new JsResult() {
                                    public void onResult(String v) {
                                        if (done.compareAndSet(false, true)) {
                                            resultRef.set(v);
                                            latch.countDown();
                                            destroyOnMain(view, destroyed);
                                        }
                                    }
                                });
                            } else {
                                // 没给 script 就退回整个页面 HTML
                                evalJs(view, "document.documentElement.outerHTML",
                                        new JsResult() {
                                    public void onResult(String v) {
                                        if (done.compareAndSet(false, true)) {
                                            htmlRef.set(v);
                                            latch.countDown();
                                            destroyOnMain(view, destroyed);
                                        }
                                    }
                                });
                            }
                        }
                    });

                    try {
                        wv.loadUrl(url);
                    } catch (Throwable t) {
                        done.set(true);
                        latch.countDown();
                        destroyOnMain(wv, destroyed);
                    }

                    // 超时兜底：到点强制收工，别让 worker 线程永久阻塞
                    UI.postDelayed(new Runnable() {
                        public void run() {
                            if (done.compareAndSet(false, true)) {
                                latch.countDown();
                                destroyOnMain(wv, destroyed);
                            }
                        }
                    }, timeout);
                }
            });

            try {
                latch.await(timeout + 5000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            } finally {
                // 无论正常返回、超时、还是被中断，都要把 WebView 收掉。
                // 正常路径下 onPageFinished 里已经叫过 destroy，靠 destroyed 标记去重。
                WebView opened = wvRef.get();
                if (opened != null) destroyOnMain(opened, destroyed);
            }

            String scriptOut = resultRef.get();
            String html = htmlRef.get();

            // 形态 2：给了 parse 回调 —— 把 HTML 交给它
            if (parseFn instanceof Function) {
                String input = scriptOut != null ? scriptOut : (html == null ? "" : html);
                // ★ 一定要套 deadline：parse 是插件里的任意 JS，可能自己写死循环。
                //   不设限的话，worker 线程会被永久占住（而且绕过 JsEngine 的中断机制）。
                long old = JsEngine.pushDeadline(JsEngine.callTimeoutMs);
                try {
                    Object r = ((Function) parseFn).call(cx, scope, scope, new Object[]{input});
                    return r == null ? Undefined.instance : r;
                } catch (Throwable t) {
                    Log.w(TAG, "render.parse 失败: " + t);
                    return "";
                } finally {
                    JsEngine.popDeadline(old);
                }
            }

            // 形态 1：直接返回 script 的结果
            return scriptOut != null ? scriptOut : (html == null ? "" : html);
        } finally {
            release();
        }
    }

    /**
     * ★ v4.21.39：Java 侧薄封装 —— 渲染页面直接拿完整 HTML。
     *
     * <p>原 {@link #render} 是 Rhino 宿主签名（要 Context/Scriptable），
     * Java 代码（如 SourceProbe 网站分析器）没法直接调；这里复用同一套
     * acquire/newWebView/evalJs/latch 主体，只是不要 script/parse，
     * onPageFinished 后取 {@code document.documentElement.outerHTML}。
     */
    public static String renderUrl(final String url, final int timeout, final boolean desktop) {
        acquire();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> htmlRef = new AtomicReference<String>(null);
        final AtomicBoolean destroyed = new AtomicBoolean(false);
        final AtomicReference<WebView> wvRef = new AtomicReference<WebView>(null);
        try {
            UI.post(new Runnable() {
                public void run() {
                    final WebView wv = newWebView(desktop);
                    wvRef.set(wv);
                    final AtomicBoolean done = new AtomicBoolean(false);

                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(final WebView view, String u) {
                            if (done.get()) return;
                            evalJs(view, "document.documentElement.outerHTML",
                                    new JsResult() {
                                public void onResult(String v) {
                                    if (done.compareAndSet(false, true)) {
                                        htmlRef.set(v);
                                        latch.countDown();
                                        destroyOnMain(view, destroyed);
                                    }
                                }
                            });
                        }
                    });

                    try {
                        wv.loadUrl(url);
                    } catch (Throwable t) {
                        done.set(true);
                        latch.countDown();
                        destroyOnMain(wv, destroyed);
                    }

                    UI.postDelayed(new Runnable() {
                        public void run() {
                            if (done.compareAndSet(false, true)) {
                                latch.countDown();
                                destroyOnMain(wv, destroyed);
                            }
                        }
                    }, timeout);
                }
            });

            try {
                latch.await(timeout + 5000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            } finally {
                WebView opened = wvRef.get();
                if (opened != null) destroyOnMain(opened, destroyed);
            }
            return htmlRef.get() == null ? "" : htmlRef.get();
        } finally {
            release();
        }
    }

    // ==================== sniff ====================

    /**
     * 打开页面并**监听它发出的网络请求**，抓第一个通过 {@code opts.validate} 的音频地址。
     *
     * <p>用于「音频地址在播放器里、页面 HTML 里没有」的站点
     * （爱听书、懒人听书付费章节、听中国兜底）。
     *
     * @param opts {@code {desktop, validate(Function url->bool), timeout}}
     */
    public static Object sniff(final Context cx, final Scriptable scope,
                               final String url, final Scriptable opts) {
        final int timeout = intOpt(opts, "timeout", 20000);
        final boolean desktop = boolOpt(opts, "desktop", false);
        final Object validateFn = opts == null ? null
                : ScriptableObject.getProperty(opts, "validate");

        acquire();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> hitRef = new AtomicReference<String>(null);
        final AtomicBoolean destroyed = new AtomicBoolean(false);
        final AtomicReference<WebView> wvRef = new AtomicReference<WebView>(null);

        try {
            UI.post(new Runnable() {
                public void run() {
                    final WebView wv = newWebView(desktop);
                    wvRef.set(wv);
                    final AtomicBoolean done = new AtomicBoolean(false);

                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public WebResourceResponse shouldInterceptRequest(
                                WebView view, WebResourceRequest req) {
                            try {
                                handleCandidate(req.getUrl() == null ? null
                                        : req.getUrl().toString());
                            } catch (Throwable ignored) {}
                            return super.shouldInterceptRequest(view, req);
                        }

                        // API < 21 的旧签名（minSdk 21，为兼容起见保留）
                        @Override
                        public WebResourceResponse shouldInterceptRequest(WebView view, String u) {
                            try { handleCandidate(u); } catch (Throwable ignored) {}
                            return null;
                        }

                        /**
                         * ★ 不能在这里直接回调 JS 的 validate！
                         *
                         * <p>{@code shouldInterceptRequest} 由 WebView 在**它自己的线程**
                         * 上调过来（不保证是主线程）。而 Rhino 的 {@link Context} 是
                         * 线程绑定的 —— 跨线程用调用方那个 {@code cx} 会直接崩，
                         * 或者拿到挂掉的作用域。
                         *
                         * <p>所以这里只把 URL 丢回主线程，在主线程上跑 validate。
                         */
                        private void handleCandidate(final String u) {
                            if (u == null || done.get()) return;
                            UI.post(new Runnable() {
                                public void run() {
                                    if (done.get()) return;
                                    if (!accept(scope, validateFn, u)) return;
                                    if (hitRef.compareAndSet(null, u)) {
                                        done.set(true);
                                        latch.countDown();
                                        destroyOnMain(wv, destroyed);
                                    }
                                }
                            });
                        }
                    });

                    try {
                        wv.loadUrl(url);
                    } catch (Throwable t) {
                        done.set(true);
                        latch.countDown();
                        destroyOnMain(wv, destroyed);
                    }

                    UI.postDelayed(new Runnable() {
                        public void run() {
                            if (done.compareAndSet(false, true)) {
                                latch.countDown();
                                destroyOnMain(wv, destroyed);
                            }
                        }
                    }, timeout);
                }
            });

            try {
                latch.await(timeout + 3000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                WebView opened = wvRef.get();
                if (opened != null) destroyOnMain(opened, destroyed);
            }
            String hit = hitRef.get();
            return hit == null ? "" : hit;
        } finally {
            release();
        }
    }

    /**
     * validate 回调：没给 validate 就默认认为所有请求都算候选。
     *
     * <p>★ 这里**不能复用调用方传进来的 {@code Context}**。
     * Rhino 的 Context 是 per-thread 的：worker 线程那个 cx 拿到主线程上用，
     * 轻则行为诡异，重则直接抛 "Context has already been entered / wrong thread"。
     * 正确做法是用 {@link JsEngine#enter()} 在主线程上现开一个。
     */
    private static boolean accept(Scriptable scope, Object validateFn, String url) {
        if (!(validateFn instanceof Function)) return true;
        Context cx = JsEngine.enter();
        try {
            Object r = ((Function) validateFn).call(cx, scope, scope, new Object[]{url});
            return Context.toBoolean(r);
        } catch (Throwable t) {
            return false;
        } finally {
            JsEngine.exit();
        }
    }

    // ==================== WebView 构建 / 工具 ====================

    @SuppressLint("SetJavaScriptEnabled")
    private static WebView newWebView(boolean desktop) {
        WebView wv = new WebView(App.ctx());
        try {
            WebSettings s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setAllowFileAccess(true);
            // 很多站的播放器是自动播的，禁掉手势要求否则不触发加载
            s.setMediaPlaybackRequiresUserGesture(false);
            // 混合内容（https 页面里的 http 音频）必须放行
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            s.setUserAgentString(desktop ? Net.UA_DESKTOP : Net.UA);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            if (Build.VERSION.SDK_INT >= 29) {
                try { s.setForceDark(WebSettings.FORCE_DARK_OFF); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return wv;
    }

    private interface JsResult { void onResult(String v); }

    /** 在 WebView 里执行 JS 并取字符串结果 */
    private static void evalJs(final WebView wv, final String code, final JsResult cb) {
        try {
            wv.evaluateJavascript(code, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    cb.onResult(unquote(value));
                }
            });
        } catch (Throwable t) {
            cb.onResult("");
        }
    }

    /**
     * {@code evaluateJavascript} 回传的是 **JSON 字面量**：字符串会带引号并转义。
     *
     * <p>★ 但剥引号必须谨慎：脚本返回的可能是 HTML 片段（`<html>...`），
     * 如果它恰好以引号开头结尾，无脑剥就会破坏内容。
     * 所以这里先做一次「能不能当 JSON 字符串解析」的校验：
     * <pre>
     *   "\"abc\""     → JSON.parse 成功 → 取内部值  "abc"
     *   "\"<html>..\""→ JSON.parse 成功 → 取内部值
     *   "{a:1}"       → 解析失败（不是合法 JSON）→ 原样返回
     *   "null"        → 空串
     * </pre>
     *
     * <p>注意插件里（sites.js:453）自己还会再 {@code .replace(/^"|"$/g, '')} 一次，
     * 那是为 Windows 版宿主写的兼容残留；我们这边先剥了，它那次 replace 就是空操作，
     * 不会造成二次破坏。
     */
    private static String unquote(String v) {
        if (v == null) return "";
        if ("null".equals(v) || "undefined".equals(v)) return "";
        String s = v.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            // 只有当它真的能当 JSON 字符串解析时才剥引号
            try {
                Object parsed = new org.json.JSONTokener(s).nextValue();
                if (parsed instanceof String) return (String) parsed;
            } catch (Throwable ignored) {
                // 不是合法 JSON 字符串 —— 说明内容本身就是普通文本，原样返回
                return s;
            }
            // 解析成功但不是 String（极少数），退回手动剥壳
            String body = s.substring(1, s.length() - 1);
            return body.replace("\\/", "/").replace("\\\"", "\"")
                    .replace("\\n", "\n").replace("\\r", "").replace("\\\\", "\\");
        }
        return s;
    }

    private static void acquire() {
        try { PERMITS.acquire(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void release() {
        try { PERMITS.release(); } catch (Throwable ignored) {}
    }

    // ---- opts 读取 ----

    private static int intOpt(Scriptable s, String k, int def) {
        if (s == null) return def;
        Object v = ScriptableObject.getProperty(s, k);
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return def;
        double d = Context.toNumber(v);
        return Double.isNaN(d) || d <= 0 ? def : (int) d;
    }

    private static boolean boolOpt(Scriptable s, String k, boolean def) {
        if (s == null) return def;
        Object v = ScriptableObject.getProperty(s, k);
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return def;
        return Context.toBoolean(v);
    }

    private static String strOpt(Scriptable s, String k) {
        if (s == null) return null;
        Object v = ScriptableObject.getProperty(s, k);
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return null;
        String r = Context.toString(v);
        return r.length() == 0 ? null : r;
    }
}
