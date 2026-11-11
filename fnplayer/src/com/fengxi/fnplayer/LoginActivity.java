package com.fengxi.fnplayer;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.fengxi.fnplayer.core.Net;
import com.fengxi.fnplayer.core.Reach;
import com.fengxi.fnplayer.core.Utils;
import com.fengxi.fnplayer.data.Config;

/**
 * 内嵌飞牛影视网页登录，登录后自动抓凭据。
 *
 * <p>为什么不自己算 authx：签名密钥藏在飞牛前端 JS 里且升级会变。
 * 让飞牛自己的网页完成登录，我们只把 Cookie / Authorization 取出来，
 * 之后 App 用它调 API 和播放，完全绕开签名。
 */
public class LoginActivity extends Activity {

    private WebView web;
    private String site;
    private String lastSaved = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Config.init(this);

        String base = Config.base();
        if (base.length() == 0) {
            Toast.makeText(this, "请先在设置里填服务器地址", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        // 飞牛影视 Web 端挂在 /v/；真正的地址会在自愈完成后覆盖
        site = base + "/v/";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF10131A);

        TextView tip = new TextView(this);
        tip.setText("在下方网页里登录飞牛影视，登录成功后点右上角「保存并返回」");
        tip.setTextColor(0xFFB8C0D0);
        tip.setPadding(20, 16, 20, 16);
        tip.setTextSize(13);
        root.addView(tip);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button save = new Button(this);
        save.setText("保存并返回");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                capture(true, web == null ? null : web.getUrl());
            }
        });
        bar.addView(save);
        Button back = new Button(this);
        back.setText("取消");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);
        root.addView(bar);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(Net.UA);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        try { cm.setAcceptThirdPartyCookies(web, true); } catch (Throwable ignored) {}
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                // 网页能加载成功，就证明这条 origin 是通的（可能已被反代/中继改写）。
                // 把它回填成全局地址，后续 API / 播放全部跟网页走。
                String real = Reach.originOf(url);
                if (real.length() > 0 && !real.equals(Config.base())) {
                    Config.setBase(real);
                    Reach.remember(real);
                    site = real + "/v/";
                }
                capture(false, url);      // 登录态一出现就入库
                grabLocalStorage(v);
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler,
                                           android.net.http.SslError error) {
                if (Config.trustSsl()) handler.proceed();   // 自签证书
                else handler.cancel();
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        // 先自动选路：设置里填的直连端口可能没放行，而网页走的是 443 反代 / 中继。
        // 找到浏览器能打开的地址后再让 WebView 加载，避免登录页直接白屏。
        final TextView tipView = tip;
        tipView.setText("正在寻找网页能打开的地址…");
        new Thread(new Runnable() {
            @Override public void run() {
                final String from = Config.base();
                final Reach.Heal h = Reach.heal(from);
                final String target = h.ok ? h.base : from;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        site = target + "/v/";
                        tipView.setText(h.ok
                                ? "在下方网页里登录飞牛影视，成功后点「保存并返回」\n已自动选路：" + target
                                : "在下方网页里登录飞牛影视，成功后点「保存并返回」");
                        web.loadUrl(site);
                    }
                });
            }
        }).start();
    }

    /** 抓 Cookie（Trim-MC-token 等）+ 从页面 localStorage 里找 Authorization */
    private void capture(boolean finishAfter, String pageUrl) {
        try {
            CookieManager cm = CookieManager.getInstance();
            String key = (pageUrl != null && pageUrl.length() > 0) ? pageUrl : site;
            String ck = cm.getCookie(key);
            String token = "";
            if (ck != null) {
                for (String pair : ck.split(";")) {
                    String p = pair.trim();
                    if (p.startsWith("Trim-MC-token=")) {
                        token = p.substring("Trim-MC-token=".length());
                    }
                }
            }
            if (ck != null && ck.length() > 0) Config.setCookie(ck);
            if (token.length() > 0) Config.setToken(token);
            if (finishAfter) {
                if (ck == null || ck.length() == 0) {
                    Toast.makeText(this, "没读到登录 Cookie，请确认已在网页中登录",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "登录状态已保存 ✓", Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
        } catch (Throwable t) {
            if (finishAfter) Toast.makeText(this, "读取失败：" + Utils.brief(t),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 有些版本把 token 放 localStorage 而不是 Cookie，一并取。 */
    private void grabLocalStorage(WebView v) {
        try {
            v.evaluateJavascript(
                    "(function(){try{var o={};for(var i=0;i<localStorage.length;i++){"
                            + "var k=localStorage.key(i);o[k]=localStorage.getItem(k);}"
                            + "return JSON.stringify(o);}catch(e){return '{}';}})()",
                    new android.webkit.ValueCallback<String>() {
                        @Override public void onReceiveValue(String value) {
                            if (value == null || value.length() < 5) return;
                            try {
                                String raw = value;
                                if (raw.startsWith("\"")) {
                                    raw = new org.json.JSONTokener(raw).nextValue().toString();
                                }
                                org.json.JSONObject o = new org.json.JSONObject(raw);
                                java.util.Iterator<String> it = o.keys();
                                while (it.hasNext()) {
                                    String k = it.next();
                                    String val = o.optString(k, "");
                                    if (val.length() > 20 && val.length() < 512
                                            && (k.toLowerCase().contains("token")
                                                || k.toLowerCase().contains("auth"))) {
                                        Config.setToken(val.replace("Bearer ", "").trim());
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}
    }
}
