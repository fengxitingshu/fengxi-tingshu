package com.tingshu.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * ★ v4.15.0：源登录页（内置 WebView）。
 *
 * <h3>用途</h3>
 * 很多源需要登录（Cookie 会话）才能正常拉数据/播放。这个页面加载源的站点，
 * 用户在网页里自己完成登录（账号密码 / 扫码 / 短信，Whatever 站点支持什么），
 * 登录成功后点顶部「我已登录」，App 读取 WebView CookieManager 里该站点的
 * 全部 Cookie 写进 {@link com.tingshu.app.core.Net} 的 Cookie 库 ——
 * 之后源的所有请求（原生源走 Net、jar 插件走 Fuel 兼容层）都会自动带上。
 *
 * <h3>隐私边界</h3>
 * Cookie 只存在应用私有目录（SP 的 cookie_store 键），不上传任何服务器；
 * 用户可随时在「书源管理 → 长按源 → 登录 / Cookie」里清除。
 */
public class LoginActivity extends Activity {

    private WebView web;
    private String srcId = "";
    private String siteUrl = "";
    /** 最近一次自动入库的 Cookie 串（去重，避免同一会话反复提示） */
    private String lastSavedCk = "";
    private boolean announced = false;   // 本次会话只在"首次拿到登录态"时提示一声

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        srcId = getIntent().getStringExtra("srcId");
        siteUrl = getIntent().getStringExtra("siteUrl");
        if (siteUrl == null || siteUrl.length() == 0) {
            finish();
            return;
        }
        if (srcId == null) srcId = "";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0f1220"));

        // ---- 顶部操作条 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tip = new TextView(this);
        tip.setText("在网页中登录即可，Cookie 会自动保存（不用手动点）");
        tip.setTextColor(Color.parseColor("#c9cfdd"));
        tip.setTextSize(13);
        tip.setPadding(0, 0, dp(8), 0);
        android.widget.LinearLayout.LayoutParams tp =
                new android.widget.LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(tip, tp);

        TextView done = new TextView(this);
        done.setText("完成");
        done.setTextColor(Color.WHITE);
        done.setTextSize(14);
        done.setPadding(dp(14), dp(7), dp(14), dp(7));
        done.setBackground(roundBg("#e8722a", dp(16)));
        done.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveAndFinish();
            }
        });
        bar.addView(done);

        TextView close = new TextView(this);
        close.setText(" ✕ ");
        close.setTextColor(Color.parseColor("#8b93a6"));
        close.setTextSize(16);
        close.setPadding(dp(10), 0, 0, 0);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(close);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- WebView ----
        web = new WebView(this);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setJavaScriptCanOpenWindowsAutomatically(true);
        st.setSupportZoom(true);
        st.setBuiltInZoomControls(true);
        st.setDisplayZoomControls(false);
        st.setUseWideViewPort(true);
        st.setLoadWithOverviewMode(true);
        // ★ 第三方 Cookie：很多站点登录走 OAuth/扫码跳转，不开的话会话存不住
        try {
            CookieManager.getInstance().setAcceptCookie(true);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
            }
        } catch (Throwable ignored) {}
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                autoCapture(url);
            }
        });
        // ★ v4.15.2 改成「和网盘一样」的自动登录：页面加载/跳转过程中持续监听，
        //   一旦 WebView 的 CookieManager 里出现该站点的会话（登录成功），
        //   立刻自动写进 Cookie 库并提示 —— 用户不再需要手动点「我已登录」。
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 50) {
                    autoCapture(view.getUrl());
                }
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        web.loadUrl(siteUrl);
    }

    /**
     * ★ v4.15.2 自动捕获 Cookie（登录态一出现就入库）。
     *
     * <p>读「当前页面 URL」与「站点根 URL」两处的 Cookie 合并 —— 很多站
     * 登录落在 passport.xxx 之类的子域，而源请求打的是 tingshu.xxx；
     * Net 注入本身有父域回退，但把两处都存一份最稳。
     * 内容没变化时静默跳过，首次拿到登录态时 Toast 提示一声。
     */
    private void autoCapture(String pageUrl) {
        try {
            CookieManager cm = CookieManager.getInstance();
            String ckPage = pageUrl == null ? null : cm.getCookie(pageUrl);
            String ckSite = cm.getCookie(siteUrl);
            String merged = mergeCookie(ckPage, ckSite);
            if (merged == null || merged.length() == 0) return;
            if (merged.equals(lastSavedCk)) return;
            lastSavedCk = merged;

            com.tingshu.app.core.Net.setCookie(siteUrl, merged);
            if (pageUrl != null && !sameHost(pageUrl, siteUrl)) {
                com.tingshu.app.core.Net.setCookie(pageUrl, merged);
            }
            com.tingshu.app.Bridge.persistCookies();
            // 记录 源 → 登录站点（重启后仍能判断登录态）
            try {
                android.content.SharedPreferences sp =
                        com.tingshu.app.data.Settings.sp();
                String key = "src_login_v1";
                org.json.JSONObject m = new org.json.JSONObject(
                        sp.getString(key, "{}"));
                m.put(srcId, siteUrl);
                sp.edit().putString(key, m.toString()).apply();
            } catch (Throwable ignored) {}
            if (!announced) {
                announced = true;
                Toast.makeText(this, "已自动获取登录状态 ✓", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {}
    }

    /** 合并两组 Cookie（同名取后者），返回合并串；两组都空时返回 null */
    private static String mergeCookie(String a, String b) {
        if (a == null || a.length() == 0) return (b == null || b.length() == 0) ? null : b;
        if (b == null || b.length() == 0) return a;
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        for (String pair : (a + "; " + b).split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = pair.substring(0, eq).trim();
            String v = pair.substring(eq + 1).trim();
            if (k.length() > 0) m.put(k, v);
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static boolean sameHost(String u1, String u2) {
        try {
            return java.net.URI.create(u1).getHost()
                    .equals(java.net.URI.create(u2).getHost());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 手动兜底：读取 WebView 的 Cookie 写进 Net Cookie 库 → 通知前端 → 关页 */
    private void saveAndFinish() {
        try {
            String ck = CookieManager.getInstance().getCookie(siteUrl);
            if (ck == null || ck.length() == 0) {
                Toast.makeText(this, "没有读到登录 Cookie，请确认已在网页中登录",
                        Toast.LENGTH_LONG).show();
                return;
            }
            com.tingshu.app.core.Net.setCookie(siteUrl, ck);
            com.tingshu.app.Bridge.persistCookies();
            // 记录 源 → 登录站点（重启后仍能判断登录态）
            try {
                android.content.SharedPreferences sp =
                        com.tingshu.app.data.Settings.sp();
                String key = "src_login_v1";
                org.json.JSONObject m = new org.json.JSONObject(
                        sp.getString(key, "{}"));
                m.put(srcId, siteUrl);
                sp.edit().putString(key, m.toString()).apply();
            } catch (Throwable ignored) {}
            Toast.makeText(this, "登录状态已保存", Toast.LENGTH_SHORT).show();
            notifyDone(true);
        } catch (Throwable t) {
            Toast.makeText(this, "保存失败：" + t.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
            notifyDone(false);
        }
        finish();
    }

    private void notifyDone(final boolean ok) {
        java.lang.Runnable r = com.tingshu.app.Bridge.webLoginCallback;
        com.tingshu.app.Bridge.webLoginCallback = null;
        if (r != null) {
            try { r.run(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            try {
                web.stopLoading();
                web.destroy();
            } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    // ---- 小工具 ----

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private android.graphics.drawable.Drawable roundBg(String color, int radius) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        try { g.setColor(Color.parseColor(color)); } catch (Throwable ignored) {}
        g.setCornerRadius(radius);
        return g;
    }
}
