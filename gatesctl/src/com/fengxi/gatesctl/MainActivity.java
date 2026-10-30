package com.fengxi.gatesctl;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 枫兮锁控 v1.0 —— 功能锁遥控器（仅群主使用）。
 *
 * <h3>做什么</h3>
 * 拨开关 → 本机算 SHA-256 签名（与听书 App 的 FeatureGate 验签同一算法、同一盐）
 * → 直接 PUT 写到 WebDAV 上的 index.html（飞牛 nginx 托管的那份）。
 * App 侧强制停止重开立即生效，不杀进程 12 小时内自动生效。
 *
 * <h3>与网页版的差别</h3>
 * Java 无浏览器 CORS 限制，真·一键直传；凭据存在本机 SharedPreferences。
 *
 * <h3>安全</h3>
 * APK 内含签名盐与你的 WebDAV 凭据 —— <b>绝不外传</b>。盐改了就要同步改
 * 听书 App 的 SIG_SALT_A/B（需发新版）。
 */
public class MainActivity extends Activity {

    /** 开关 key 顺序固定（生成的 JSON 与签名都依赖这个顺序的一致性） */
    private static final String[] KEYS = {
            "dav_export", "webdav_lib", "dav_backup", "sync_progress"
    };
    private static final String[] LABELS = {
            "转存到 NAS", "个人音频库", "网络备份", "多设备续听"
    };
    private static final String[] DESC = {
            "下载的音频自动转存到 WebDAV",
            "浏览播放自己 NAS 里的音频",
            "备份/恢复数据到 WebDAV",
            "进度经 NAS 中转跨设备同步"
    };

    private static final int BG = 0xFF0F1220;
    private static final int CARD = 0xFF171B2E;
    private static final int LINE = 0xFF2A3350;
    private static final int TXT = 0xFFE8EAF0;
    private static final int SUB = 0xFF9AA3B2;
    private static final int ACC = 0xFF7CC4FF;
    private static final int OKC = 0xFF37C978;
    private static final int BADC = 0xFFFF5D73;

    private EditText etUrl, etUser, etPass, etSalt;
    private CheckBox cbTrust;
    private final Switch[] switches = new Switch[KEYS.length];
    private TextView tvOut, tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadPrefs();
        regen();
    }

    // ==================== UI（纯代码构建，零资源依赖） ====================

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(CARD);
        int p = dp(14);
        c.setPadding(p, p, p, p);
        return c;
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(ACC);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private EditText input(boolean password) {
        EditText e = new EditText(this);
        e.setTextSize(14);
        e.setTextColor(TXT);
        e.setHintTextColor(SUB);
        e.setBackgroundColor(0xFF0D1020);
        int p = dp(10);
        e.setPadding(p, p, p, p);
        e.setSingleLine(true);
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(SUB);
        t.setTextSize(12);
        t.setPadding(0, dp(8), 0, dp(2));
        return t;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(24), pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        TextView h1 = new TextView(this);
        h1.setText("🔒 枫兮锁控");
        h1.setTextColor(TXT);
        h1.setTextSize(20);
        h1.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(h1);
        TextView sub = new TextView(this);
        sub.setText("功能锁遥控器 · 拨开关 → 签名 → 直写 NAS。仅群主使用，请勿分发本应用。");
        sub.setTextColor(SUB);
        sub.setTextSize(12);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        // ---- 开关卡片 ----
        LinearLayout cSw = card();
        cSw.addView(title("功能开关"));
        for (int i = 0; i < KEYS.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setWeightSum(1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            TextView t = new TextView(this);
            t.setText(LABELS[i]);
            t.setTextColor(TXT);
            t.setTextSize(15);
            col.addView(t);
            TextView d = new TextView(this);
            d.setText(DESC[i]);
            d.setTextColor(SUB);
            d.setTextSize(11);
            col.addView(d);
            row.addView(col, lp);
            Switch sw = new Switch(this);
            sw.setChecked(true);
            switches[i] = sw;
            row.addView(sw);
            final int idx = i;
            sw.setOnCheckedChangeListener((b, v) -> onToggle(idx, v));
            cSw.addView(row);
            if (i < KEYS.length - 1) {
                TextView line = new TextView(this);
                line.setBackgroundColor(LINE);
                line.setHeight(dp(1));
                cSw.addView(line);
            }
        }
        root.addView(cSw);

        // ---- 连接配置卡片 ----
        LinearLayout cCfg = card();
        cCfg.addView(title("WebDAV 连接（飞牛上 index.html 所在目录）"));
        cCfg.addView(label("目录地址（到 miyue 这一层）"));
        etUrl = input(false);
        etUrl.setHint("https://…/dav/miyue");
        cCfg.addView(etUrl);
        cCfg.addView(label("账号"));
        etUser = input(false);
        cCfg.addView(etUser);
        cCfg.addView(label("密码"));
        etPass = input(true);
        cCfg.addView(etPass);
        cbTrust = new CheckBox(this);
        cbTrust.setText("信任自签证书（自用 NAS 常见）");
        cbTrust.setTextColor(SUB);
        cbTrust.setTextSize(12);
        cbTrust.setChecked(true);
        cCfg.addView(cbTrust);
        cCfg.addView(label("签名盐（SALT，改了要同步改听书 App）"));
        etSalt = input(true);
        cCfg.addView(etSalt);
        root.addView(cCfg);

        // ---- 操作 ----
        LinearLayout cOp = card();
        cOp.addView(title("操作"));
        Button btnPush = new Button(this);
        btnPush.setText("⬆ 保存并上传");
        btnPush.setTextColor(Color.WHITE);
        btnPush.getBackground().setTint(0xFFFF4D6D);
        btnPush.setOnClickListener(v -> doPush());
        cOp.addView(btnPush);
        Button btnRead = new Button(this);
        btnRead.setText("⬇ 读取远端当前状态");
        btnRead.setTextColor(Color.WHITE);
        btnRead.getBackground().setTint(0xFF2B5876);
        btnRead.setOnClickListener(v -> doRead());
        cOp.addView(btnRead);
        tvStatus = new TextView(this);
        tvStatus.setTextColor(SUB);
        tvStatus.setTextSize(12);
        tvStatus.setPadding(0, dp(8), 0, 0);
        cOp.addView(tvStatus);
        root.addView(cOp);

        // ---- 生成预览 ----
        LinearLayout cOut = card();
        cOut.addView(title("将写入的两行内容"));
        tvOut = new TextView(this);
        tvOut.setTypeface(Typeface.MONOSPACE);
        tvOut.setTextSize(11);
        tvOut.setTextColor(0xFFC7F0D4);
        tvOut.setTextIsSelectable(true);
        cOut.addView(tvOut);
        root.addView(cOut);

        TextView note = new TextView(this);
        note.setText("生效说明：上传后「强制停止」听书 App 再打开立即生效；\n"
                + "不杀进程则 12 小时内下次拉取生效。关掉的开关在 App 里\n"
                + "会提示「试用已暂停」，本地记录与下载不受影响。");
        note.setTextColor(SUB);
        note.setTextSize(11);
        note.setPadding(dp(4), dp(12), dp(4), 0);
        root.addView(note);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ==================== 状态与生成 ====================

    private void onToggle(int idx, boolean on) {
        switches[idx].setTextColor(on ? TXT : BADC);
        regen();
    }

    /** 生成 payload JSON（key 顺序固定）并算签名，刷新预览 */
    private String payload() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < KEYS.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(KEYS[i]).append("\": ")
              .append(switches[i].isChecked() ? "true" : "false");
        }
        sb.append("}");
        return sb.toString();
    }

    private String salt() {
        return etSalt.getText().toString().trim();
    }

    private String sig(String payload, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((payload + salt).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void regen() {
        String p = payload();
        tvOut.setText(sig(p, salt()) + "\n" + p);
    }

    // ==================== 凭据持久化 ====================

    private void loadPrefs() {
        android.content.SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        etUrl.setText(sp.getString("url", ""));
        etUser.setText(sp.getString("user", ""));
        etPass.setText(sp.getString("pass", ""));
        etSalt.setText(sp.getString("salt", "fx@tingshu#gates-2026"));
        cbTrust.setChecked(sp.getBoolean("trust", true));
        String last = sp.getString("payload", "");
        if (last.length() > 0) {
            try {
                JSONObject o = new JSONObject(last);
                for (int i = 0; i < KEYS.length; i++) {
                    switches[i].setChecked(o.optBoolean(KEYS[i], true));
                }
            } catch (Throwable ignored) {}
        }
    }

    private void savePrefs() {
        android.content.SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        sp.edit()
          .putString("url", etUrl.getText().toString().trim())
          .putString("user", etUser.getText().toString().trim())
          .putString("pass", etPass.getText().toString())
          .putString("salt", salt())
          .putBoolean("trust", cbTrust.isChecked())
          .putString("payload", payload())
          .apply();
    }

    private String fileUrl() {
        String u = etUrl.getText().toString().trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u + "/index.html";
    }

    // ==================== 网络 ====================

    private void trustAll() {
        try {
            TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tm, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
        } catch (Throwable ignored) {}
    }

    private HttpURLConnection open(String url, String method) throws Exception {
        if (cbTrust.isChecked()) trustAll();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestMethod(method);
        String u = etUser.getText().toString().trim();
        String p = etPass.getText().toString();
        if (u.length() > 0) {
            String token = android.util.Base64.encodeToString(
                    (u + ":" + p).getBytes(StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP);
            c.setRequestProperty("Authorization", "Basic " + token);
        }
        return c;
    }

    private String readAll(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) break;
            bo.write(buf, 0, n);
        }
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private void status(final String s, final int color) {
        runOnUiThread(() -> {
            tvStatus.setText(s);
            tvStatus.setTextColor(color);
        });
    }

    private void toast(final String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private void busyCheck() {
        savePrefs();
        regen();
    }

    /** 读取远端 index.html → 验签（尽力）→ 把开关拨到远端状态 */
    private void doRead() {
        busyCheck();
        final String url = fileUrl();
        if (etUrl.getText().toString().trim().length() == 0) {
            status("先填 WebDAV 目录地址", BADC);
            return;
        }
        status("读取中…", SUB);
        new Thread(() -> {
            try {
                HttpURLConnection c = open(url, "GET");
                int code = c.getResponseCode();
                if (code != 200) {
                    status("读取失败：HTTP " + code, BADC);
                    return;
                }
                String body = readAll(c.getInputStream(), 1024 * 256);
                String b = body.replace("\r\n", "\n").trim();
                int nl = b.indexOf('\n');
                if (nl <= 0) {
                    status("远端内容不是两行（可能没部署过）", BADC);
                    return;
                }
                String remoteSig = b.substring(0, nl).trim().toLowerCase();
                String payload = b.substring(nl + 1).trim();
                String expect = sig(payload, salt());
                final boolean sigOk = remoteSig.equals(expect);
                JSONObject o = new JSONObject(payload);
                runOnUiThread(() -> {
                    for (int i = 0; i < KEYS.length; i++) {
                        switches[i].setChecked(o.optBoolean(KEYS[i], true));
                    }
                    regen();
                });
                status(sigOk ? "✓ 已读取远端状态（签名校验通过）"
                             : "已读取远端状态，⚠ 签名校验失败（盐不一致或内容被手改）",
                        sigOk ? OKC : BADC);
            } catch (Throwable t) {
                status("读取失败：" + t.getMessage(), BADC);
            }
        }, "fx-read").start();
    }

    /** 生成两行 → PUT 到 WebDAV */
    private void doPush() {
        busyCheck();
        final String url = fileUrl();
        if (etUrl.getText().toString().trim().length() == 0) {
            status("先填 WebDAV 目录地址", BADC);
            return;
        }
        final String body = sig(payload(), salt()) + "\n" + payload() + "\n";
        status("上传中…", SUB);
        new Thread(() -> {
            try {
                HttpURLConnection c = open(url, "PUT");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "text/plain");
                byte[] raw = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(raw.length);
                OutputStream os = c.getOutputStream();
                os.write(raw);
                os.close();
                int code = c.getResponseCode();
                if (code == 200 || code == 201 || code == 204) {
                    status("✅ 已上传（HTTP " + code + "）。强制停止听书 App 再打开即生效", OKC);
                    toast("✅ 上传成功");
                } else {
                    String err = "";
                    try { err = readAll(c.getErrorStream(), 2048); } catch (Throwable ignored) {}
                    status("上传失败：HTTP " + code + (err.length() > 0 ? " " + snippet(err) : ""), BADC);
                }
            } catch (Throwable t) {
                status("上传失败：" + t.getMessage(), BADC);
            }
        }, "fx-push").start();
    }

    private String snippet(String s) {
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
