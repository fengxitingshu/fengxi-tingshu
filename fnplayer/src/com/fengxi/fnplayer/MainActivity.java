package com.fengxi.fnplayer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.fengxi.fnplayer.core.Net;
import com.fengxi.fnplayer.data.Config;

/** 单 Activity + WebView 承载全部浏览 UI（照抄听书 App 的成熟范式）。 */
public class MainActivity extends Activity {

    private static final int REQ_IMPORT = 7001;

    /** v2.22：静态引用，供 FloatVideoService 把浮窗状态推给前端；销毁置 null 防泄漏。 */
    public static volatile MainActivity instance;

    private WebView web;
    private Bridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        Config.init(this);
        com.fengxi.fnplayer.core.CrashGuard.install(this);   // v2.19：后台线程崩溃不再闪退

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(Net.UA);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29)
                s.setForceDark(WebSettings.FORCE_DARK_OFF);
        } catch (Throwable ignored) {}
        // 主题（v2.11）：手动亮/暗或跟随系统，WebView 底色与前端主题同步
        applyWebBg();
        web.setWebChromeClient(new WebChromeClient());
        // 自签 HTTPS：NAS 几乎都是自签证书。海报 <img> 是跨源子资源请求，
        // 不放行 SSL 错误就会全部静默加载失败（「影视墙没出图片」的常见原因之一）。
        // 只对配置的 NAS 主机放行，其他站点照常拒绝。
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler,
                                           android.net.http.SslError error) {
                if (isNasHost(error.getUrl())) {
                    handler.proceed();
                } else {
                    handler.cancel();
                }
            }
        });
        bridge = new Bridge(web, this);
        web.addJavascriptInterface(bridge, "Android");
        web.loadUrl("file:///android_asset/www/index.html");
    }

    /** v2.11：按主题设置刷新 WebView 底色（setTheme 后由 Bridge 调用）。 */
    public void applyWebBg() {
        if (web == null) return;
        String t = com.fengxi.fnplayer.data.Config.theme();
        boolean night;
        if ("dark".equals(t)) night = true;
        else if ("light".equals(t)) night = false;
        else night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        web.setBackgroundColor(night ? 0xFF10131A : 0xFFF2F4F8);
    }

    /** 系统文件管理器选源文件（源管理 → 本地导入）。 */
    public void pickImportFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(Intent.createChooser(i, "选择源文件"), REQ_IMPORT);
            } catch (Throwable t2) {
                Toast.makeText(this, "无法打开文件管理器", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        try {
            Uri uri = data.getData();
            java.io.InputStream is = getContentResolver().openInputStream(uri);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            String name = queryFileName(uri);
            bridge.handleImportBytes(bo.toByteArray(), name);
        } catch (Throwable t) {
            callJs("window.onImportDone && window.onImportDone({ok:false,error:'读取文件失败："
                    + String.valueOf(t.getMessage()).replace("\"", "'") + "'})");
        }
    }

    private String queryFileName(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) return c.getString(idx);
                } finally { c.close(); }
            }
        } catch (Throwable ignored) {}
        String p = uri.getLastPathSegment();
        return p == null ? "" : p.substring(p.lastIndexOf('/') + 1);
    }

    /** 判断 url 的主机是否是用户配置的 NAS 主机（自签证书只对它放行）。 */
    private boolean isNasHost(String url) {
        String nas = hostOf(Config.base());
        String u = hostOf(url);
        return nas.length() > 0 && nas.equals(u);
    }

    private static String hostOf(String url) {
        if (url == null) return "";
        try {
            String h = new java.net.URI(url).getHost();
            return h == null ? "" : h.toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable t) { return ""; }
    }

    /** 原生 → 前端。务必 post 到主线程，evaluateJavascript 不能在别的线程调。 */
    public void callJs(final String js) {
        if (web == null) return;
        web.post(new Runnable() {
            @Override public void run() {
                try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从 WebView 登录页返回 / 重新前台时，刷新前端登录态
        callJs("window.onLoginChanged && window.onLoginChanged()");
    }

    @Override
    public void onBackPressed() {
        // 前端维护页面栈；无頁可退时前端调 Android.exitApp()
        callJs("window.onNativeBack && window.onNativeBack()");
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
