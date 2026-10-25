package com.tingshu.app.pan;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;

/**
 * ★ v4.2.0：网盘「登录」拿 Cookie —— 替代手动粘贴 Cookie。
 *
 * <h3>支持的登录方式</h3>
 * <ul>
 *   <li><b>115 扫码登录</b>（{@link #qr115Token} / {@link #qr115Status} /
 *       {@link #qr115Cookie}）：官方 qrcodeapi，扫一次自动拿
 *       UID/CID/SEID，全程无需手动复制。</li>
 *   <li><b>天翼云盘账号密码登录</b>（{@link #login189}）：官方
 *       open.e.189.cn 登录接口，密码走固定公钥 RSA/PKCS1 加密，
 *       成功后自动取 COOKIE_LOGIN_USER。</li>
 * </ul>
 *
 * <p>其余平台（百度/阿里/夸克/UC）无公开可用的登录接口（网页登录有加密
 * 与风控），仍走「粘贴 Cookie」。这里有意用裸 HttpURLConnection 而不是
 * 共用的 {@code Net}：登录流程需要读 Set-Cookie 原始头，共用工具不回传它。
 */
public final class PanLogin {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36";

    private PanLogin() {}

    // ==================== 115 扫码登录 ====================

    /**
     * 第 1 步：取扫码 token。
     *
     * @return data 节点：uid / time / sign / qrcode（qrcode 即二维码内容）
     */
    public static JSONObject qr115Token() throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("Referer", "https://115.com/");
        Resp r = http("GET", "https://qrcodeapi.115.com/api/1.0/web/1.0/token/", h, null);
        JSONObject o = new JSONObject(r.body);
        JSONObject data = o.optJSONObject("data");
        if (data == null || data.optString("uid", "").length() == 0) {
            throw new Exception("获取二维码失败：" + brief(r.body));
        }
        return data;
    }

    /**
     * 第 2 步：轮询扫码状态。
     *
     * @return state：-1=已过期/取消，0=等待扫码，1=已扫码待确认，2=已确认
     */
    public static int qr115Status(String uid, String time, String sign) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("Referer", "https://115.com/");
        String url = "https://qrcodeapi.115.com/get/status/?uid="
                + URLEncoder.encode(uid, "UTF-8")
                + "&time=" + URLEncoder.encode(time, "UTF-8")
                + "&sign=" + URLEncoder.encode(sign, "UTF-8");
        Resp r = http("GET", url, h, null);
        JSONObject o = new JSONObject(r.body);
        return o.optInt("state", -1);
    }

    /**
     * 第 3 步：扫码确认后取 Cookie（UID/CID/SEID）。
     *
     * @return 形如 "UID=..; CID=..; SEID=.." 的 Cookie 串
     */
    public static String qr115Cookie(String uid) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("Referer", "https://115.com/");
        h.put("Content-Type", "application/x-www-form-urlencoded");
        Resp r = http("POST", "https://passportapi.115.com/app/1.0/web/1.0/login/qrcode/",
                h, "account=" + URLEncoder.encode(uid, "UTF-8") + "&app=web");
        JSONObject o = new JSONObject(r.body);
        JSONObject cookie = o.optJSONObject("data") == null
                ? null : o.optJSONObject("data").optJSONObject("cookie");
        if (cookie == null) {
            throw new Exception("扫码登录未完成或被取消：" + brief(r.body));
        }
        StringBuilder sb = new StringBuilder();
        String[] keys = {"UID", "CID", "SEID", "KID"};
        for (String k : keys) {
            String v = cookie.optString(k, "");
            if (v.length() == 0) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(k).append("=").append(v);
        }
        if (sb.length() == 0) throw new Exception("登录响应里没有 Cookie：" + brief(r.body));
        return sb.toString();
    }

    // ==================== 天翼云盘密码登录 ====================

    /** open.e.189.cn 登录加密用的固定 RSA 公钥（X.509/PKIX，1024 位） */
    private static final String RSA189_PUB =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDY7mpaUysvgQkbp0iIn2ezoUyh" +
            "i1zPFn0HCXloLFWT7uoNkqtrphpQ/63LEcPz1VYzmDuDIf3iGxQKzeoHTiVMSmW6" +
            "FlhDeqVOG094hFJvZeK4OzA6HVwzwnEW5vIZ7d+u61RV1bsFxmB68+8JXs3ycGcE" +
            "4anY+YzZJcyOcEGKVQIDAQAB";

    /** 登录页四参数（captchaToken / lt / returnUrl / paramId） */
    private static final Pattern P_CAPTCHA_TOKEN = Pattern.compile("captchaToken' value='(.+?)'");
    private static final Pattern P_LT = Pattern.compile("lt = \"(.+?)\"");
    private static final Pattern P_RETURN_URL = Pattern.compile("returnUrl = '(.+?)'");
    private static final Pattern P_PARAM_ID = Pattern.compile("paramId = \"(.+?)\"");

    /**
     * 天翼云盘账号密码登录。
     *
     * @return "COOKIE_LOGIN_USER=.." Cookie 串（与手动粘贴等价）
     */
    public static String login189(String username, String password) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("Referer", "https://open.e.189.cn/");

        // 1. 登录页抓参数
        Resp pg = http("GET",
                "https://cloud.189.cn/udb/udb_login.jsp?pageId=1&redirectURL=/main.action",
                h, null);
        String html = pg.body == null ? "" : pg.body;
        String captchaToken = group1(P_CAPTCHA_TOKEN, html);
        String lt = group1(P_LT, html);
        String returnUrl = group1(P_RETURN_URL, html);
        String paramId = group1(P_PARAM_ID, html);
        if (captchaToken.length() == 0 || lt.length() == 0 || paramId.length() == 0) {
            throw new Exception("登录页解析失败（接口可能已改版），请改用粘贴 Cookie");
        }

        Map<String, String> h2 = new HashMap<String, String>(h);
        h2.put("Content-Type", "application/x-www-form-urlencoded");
        h2.put("lt", lt);

        // 2. 是否需要验证码（触发风控时提前给明确提示，不让用户猜）
        Resp nc = http("POST", "https://open.e.189.cn/api/logbox/oauth2/needcaptcha.do",
                h2, "accountType=01&userName=" + URLEncoder.encode(
                        "{RSA}" + b64ToHex(rsa189(username)), "UTF-8")
                + "&appKey=cloud");
        if (nc.body != null && nc.body.trim().length() > 0 && !"0".equals(nc.body.trim())) {
            throw new Exception("该账号触发了登录验证码，请改用「配置 Cookie」方式");
        }

        // 3. 提交登录（密码 RSA 加密，与网页版一致）
        StringBuilder form = new StringBuilder();
        form.append("appKey=cloud");
        form.append("&accountType=01");
        form.append("&userName=").append(URLEncoder.encode(
                "{RSA}" + b64ToHex(rsa189(username)), "UTF-8"));
        form.append("&password=").append(URLEncoder.encode(
                "{RSA}" + b64ToHex(rsa189(password)), "UTF-8"));
        form.append("&validateCode=");
        form.append("&captchaToken=").append(URLEncoder.encode(captchaToken, "UTF-8"));
        form.append("&returnUrl=").append(URLEncoder.encode(returnUrl, "UTF-8"));
        form.append("&mailSuffix=@189.cn");
        form.append("&paramId=").append(URLEncoder.encode(paramId, "UTF-8"));
        Resp lp = http("POST", "https://open.e.189.cn/api/logbox/oauth2/loginSubmit.do",
                h2, form.toString());
        JSONObject o;
        try {
            o = new JSONObject(lp.body == null ? "" : lp.body);
        } catch (Throwable t) {
            throw new Exception("登录响应解析失败：" + brief(lp.body));
        }
        String msg = o.optString("msg", "");
        String toUrl = o.optString("toUrl", "");
        if (msg.indexOf("成功") < 0 || toUrl.length() == 0) {
            throw new Exception(msg.length() > 0 ? msg : ("登录失败：" + brief(lp.body)));
        }

        // 4. 访问 toUrl 换正式 Cookie，抓 COOKIE_LOGIN_USER
        Resp fin = http("GET", toUrl, h2, null);
        String ck = headerCookie(fin, "COOKIE_LOGIN_USER");
        if (ck.length() == 0) {
            throw new Exception("登录成功但未取到 Cookie，请改用粘贴 Cookie");
        }
        return "COOKIE_LOGIN_USER=" + ck;
    }

    /** 固定公钥 RSA/PKCS1 加密 → Base64 → hex（与官方网页版一致） */
    private static String rsa189(String data) throws Exception {
        byte[] der = Base64.decode(RSA189_PUB, Base64.DEFAULT);
        PublicKey pub = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, pub);
        byte[] enc = c.doFinal(data.getBytes("UTF-8"));
        return b64ToHex(Base64.encodeToString(enc, Base64.NO_WRAP));
    }

    /** Base64 → 十六进制（逐字符复刻官方网页版 b64tohex，含填充位行为） */
    private static final String B64MAP =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private static String int2char(int i) { return "0123456789abcdef".substring(i, i + 1); }

    private static String b64ToHex(String b64str) {
        StringBuilder sb = new StringBuilder();
        int e = 0, c = 0;
        for (int i = 0; i < b64str.length(); i++) {
            char r = b64str.charAt(i);
            if (r == '=') continue;
            int v = B64MAP.indexOf(r);
            if (v < 0) continue;
            if (e == 0) { e = 1; sb.append(int2char(v >> 2)); c = 3 & v; }
            else if (e == 1) { e = 2; sb.append(int2char((c << 2) | (v >> 4))); c = 15 & v; }
            else if (e == 2) { e = 3; sb.append(int2char(c)); sb.append(int2char(v >> 2)); c = 3 & v; }
            else { e = 0; sb.append(int2char((c << 2) | (v >> 4))); sb.append(int2char(15 & v)); }
        }
        if (e == 1) sb.append(int2char(c << 2));
        return sb.toString();
    }

    // ==================== HTTP 底座 ====================

    /** 极简 HTTP 响应：状态码 + 正文 + 全部响应头（Set-Cookie 在登录流程里要用） */
    public static class Resp {
        public int code;
        public String body;
        public Map<String, List<String>> headers;
    }

    private static Resp http(String method, String url, Map<String, String> headers,
                             String body) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getValue() != null) conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            Resp r = new Resp();
            r.code = conn.getResponseCode();
            r.headers = conn.getHeaderFields();
            InputStream in = r.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            r.body = readAll(in);
            // 3xx 跟一跳（toUrl 那步是 302 换 Cookie，跟丢就拿不到 COOKIE_LOGIN_USER）
            if (r.code >= 300 && r.code < 400) {
                String loc = conn.getHeaderField("Location");
                if (loc != null && loc.length() > 0) {
                    if (loc.startsWith("/")) {
                        java.net.URI u = new java.net.URI(url);
                        loc = u.getScheme() + "://" + u.getHost() + loc;
                    }
                    return http(method, loc, headers, body);
                }
            }
            return r;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** 从响应头里抠某个 Cookie 的值 */
    private static String headerCookie(Resp r, String name) {
        if (r == null || r.headers == null) return "";
        for (Map.Entry<String, List<String>> e : r.headers.entrySet()) {
            if (e.getKey() == null || !"set-cookie".equalsIgnoreCase(e.getKey())) continue;
            for (String v : e.getValue()) {
                if (v == null) continue;
                Matcher m = Pattern.compile(name + "=([^;]+)").matcher(v);
                if (m.find()) return m.group(1);
            }
        }
        return "";
    }

    private static String group1(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static String brief(String s) {
        if (s == null) return "空响应";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
