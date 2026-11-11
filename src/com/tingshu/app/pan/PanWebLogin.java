package com.tingshu.app.pan;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v4.4.0：网盘「网页登录」——App 内嵌 WebView 打开网盘官方登录页，
 * 用户在里面正常登录（密码 / 扫码 / 第三方都行），本类轮询 WebView 的
 * CookieManager，一旦出现登录成功的标志 Cookie 就自动抓整条 Cookie
 * 存进 PanResolver，然后关页回调。全程用户只做「登录」一件事。
 *
 * <p>之所以不用外部浏览器：外部浏览器有 Cookie 沙箱，App 读不到；
 * 内嵌 WebView 的 CookieManager 可以直接读，效果等同「登录后自动拿 Cookie」。
 */
public final class PanWebLogin {

    private PanWebLogin() {}

    /** 每平台配置：登录入口 URL / 轮询哪个域的 Cookie / 判定成功需要的 Cookie 键 */
    private static final class Cfg {
        final String url;      // WebView 首页（未登录会被网盘自己跳到登录页）
        final String checkUrl; // 读这个 URL 的 cookie（CookieManager 按域取）
        final String[] keys;   // 全部出现 = 登录成功

        Cfg(String u, String c, String[] k) { url = u; checkUrl = c; keys = k; }
    }

    private static Cfg cfgOf(String pid) {
        if (PanResolver.P115.equals(pid))
            // 115 官方登录页，扫码+密码都有；成功后 cookie 落 .115.com
            return new Cfg("https://passport.115.com/app/1.0.0/web/",
                    "https://webapi.115.com/",
                    new String[]{"UID=", "CID=", "SEID="});
        if (PanResolver.TIANYI.equals(pid))
            return new Cfg("https://cloud.189.cn/web/login.html",
                    "https://cloud.189.cn/",
                    new String[]{"SESSION="});
        if (PanResolver.QUARK.equals(pid))
            return new Cfg("https://pan.quark.cn/",
                    "https://pan.quark.cn/",
                    new String[]{"__puus"});
        if (PanResolver.UC.equals(pid))
            return new Cfg("https://drive.uc.cn/",
                    "https://drive.uc.cn/",
                    new String[]{"__puus"});
        if (PanResolver.BAIDU.equals(pid))
            return new Cfg("https://pan.baidu.com/",
                    "https://pan.baidu.com/",
                    new String[]{"BDUSS="});
        if (PanResolver.ALI.equals(pid))
            // 阿里云盘网页版登录后 cookie 里有 sfstoken（可直连其 API）
            return new Cfg("https://www.alipan.com/",
                    "https://www.alipan.com/",
                    new String[]{"sfstoken"});
        return null;
    }

    /** 桌面 Chrome UA：网盘网页版对 WebView UA 常弹「请用 App 打开」，桌面 UA 可绕开 */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    /** ★ v4.19.1：天翼登录页是桌面版宽布局，手机 WebView 里二维码显示不全 → 用移动 UA 走 H5 登录页 */
    private static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static WebView  sWeb;
    private static Dialog   sDialog;
    private static Handler  sHandler;
    private static Runnable sPoll;
    private static boolean  sDone;   // 已抓到，防重复保存

    /**
     * 弹出登录窗口。
     *
     * @param activity   宿主
     * @param pid        网盘 id（PanResolver.*）
     * @param onSuccess  抓到 Cookie 并保存成功后的回调（已在主线程）
     */
    public static void show(final Activity activity, final String pid,
                            final Runnable onSuccess) {
        final Cfg cfg = cfgOf(pid);
        if (cfg == null || activity == null || activity.isFinishing()) return;

        close(); // 上一个没关干净的情况
        sDone = false;

        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#101418"));

        // ---- 顶部提示条 ----
        final LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 8), dp(activity, 10));
        bar.setBackgroundColor(Color.parseColor("#1A2027"));

        final TextView tip = new TextView(activity);
        tip.setText("在页面里登录 " + PanResolver.nameOf(pid)
                + "，登录成功后自动返回（无需手动抄 Cookie）");
        tip.setTextColor(Color.parseColor("#D8E2EC"));
        tip.setTextSize(13);
        tip.setPadding(0, 0, dp(activity, 8), 0);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(tip, tp);

        final TextView reloadBtn = new TextView(activity);
        reloadBtn.setText("↻ 刷新");
        reloadBtn.setTextColor(Color.parseColor("#7FB3FF"));
        reloadBtn.setTextSize(13);
        reloadBtn.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
        reloadBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v4.21.10：在夸克/百度 App 里授权完成后回到本页，点刷新重新走登录态检测
                if (sWeb != null) sWeb.loadUrl(cfg.url);
            }
        });
        bar.addView(reloadBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView closeBtn = new TextView(activity);
        closeBtn.setText("✕ 关闭");
        closeBtn.setTextColor(Color.parseColor("#7FB3FF"));
        closeBtn.setTextSize(13);
        closeBtn.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { close(); }
        });
        bar.addView(closeBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- WebView ----
        sWeb = new WebView(activity);
        final WebSettings st = sWeb.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setUserAgentString("pan_tianyi".equals(pid) ? UA_MOBILE : UA);
        st.setSupportZoom(true);
        st.setBuiltInZoomControls(true);
        st.setDisplayZoomControls(false);
        st.setLoadWithOverviewMode(true);
        st.setUseWideViewPort(true);
        try { st.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); } catch (Throwable ignored) {}

        final CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        try { cm.setAcceptThirdPartyCookies(sWeb, true); } catch (Throwable ignored) {}

        sWeb.setWebViewClient(new WebViewClient() {
            /** v4.21.10：非 http(s) 的 scheme（quark:// / baiduboxapp:// / intent:// 等）
             *  交给外部 App 处理——网盘登录页的「APP 授权」靠这个拉起自家 App，
             *  授权完成回跳后页面继续登录流程，Cookie 轮询自然抓到。 */
            private boolean launchExternal(WebView v, String url) {
                if (url == null) return false;
                final String low = url.toLowerCase();
                final boolean httpish = low.startsWith("http://") || low.startsWith("https://")
                        || low.startsWith("about:") || low.startsWith("javascript:")
                        || low.startsWith("file:");
                if (httpish) return false;
                try {
                    android.content.Intent it;
                    if (low.startsWith("intent://")) {
                        it = android.content.Intent.parseUri(url,
                                android.content.Intent.URI_INTENT_SCHEME);
                    } else {
                        it = android.content.Intent.parseUri(url, 0);
                    }
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { activity.startActivity(it); return true; } catch (Throwable t) {
                        // 没有 App 接这个协议：若有 fallback 落地页（intent://;...end;...）就用它
                        String fb = it.getStringExtra("browser_fallback_url");
                        if (fb != null && fb.length() > 0) { v.loadUrl(fb); return true; }
                        return true; // 吞掉，不留在 WebView 里渲染乱码
                    }
                } catch (Throwable t2) {
                    return true;
                }
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return launchExternal(v, url); // 兼容旧 API 的重载
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return launchExternal(v, r == null ? null : r.getUrl() == null ? null : r.getUrl().toString());
            }
            @Override public void onPageFinished(WebView v, String url) {
                check(activity, pid, cfg, onSuccess);
            }
        });
        sWeb.setWebChromeClient(new WebChromeClient());
        sWeb.loadUrl(cfg.url);

        root.addView(sWeb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        sDialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final Window w = sDialog.getWindow();
        if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        sDialog.setContentView(root);
        sDialog.setCancelable(true);
        sDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) { stopPoll(); }
        });
        sDialog.show();

        // ---- 轮询 Cookie（有些网盘登录是纯 SPA，不触发 onPageFinished）----
        sHandler = new Handler(Looper.getMainLooper());
        sPoll = new Runnable() {
            @Override public void run() {
                if (sDialog == null || !sDialog.isShowing()) return;
                check(activity, pid, cfg, onSuccess);
                sHandler.postDelayed(this, 1500);
            }
        };
        sHandler.postDelayed(sPoll, 2500);
    }

    /** 检查 Cookie 是否齐了：齐了 → 整条保存 → 关窗 → 回调 */
    private static void check(final Activity activity, final String pid, final Cfg cfg,
                              final Runnable onSuccess) {
        if (sDone || sWeb == null) return;
        try {
            final CookieManager cm = CookieManager.getInstance();
            final String ck = cm.getCookie(cfg.checkUrl);
            if (ck == null || ck.isEmpty()) return;
            for (final String key : cfg.keys) {
                if (!ck.contains(key)) return; // 还没登录成功，继续等
            }
            sDone = true;
            PanResolver.saveCookie(pid, ck);
            stopPoll();
            if (sDialog != null && sDialog.isShowing()) sDialog.dismiss();
            if (onSuccess != null) onSuccess.run();
        } catch (Throwable ignored) {}
    }

    private static void stopPoll() {
        if (sHandler != null && sPoll != null) sHandler.removeCallbacks(sPoll);
        sPoll = null;
    }

    /** 关掉登录窗口（保留 WebView 尽快回收内存） */
    public static void close() {
        stopPoll();
        if (sDialog != null) {
            try { sDialog.dismiss(); } catch (Throwable ignored) {}
            sDialog = null;
        }
        if (sWeb != null) {
            try {
                sWeb.loadUrl("about:blank");
                sWeb.removeAllViews();
                sWeb.destroy();
            } catch (Throwable ignored) {}
            sWeb = null;
        }
        sDone = false;
    }

    private static int dp(android.content.Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
