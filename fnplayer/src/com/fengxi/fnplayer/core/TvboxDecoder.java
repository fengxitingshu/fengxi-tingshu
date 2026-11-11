package com.fengxi.fnplayer.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * TVBox 加密配置解码链（v2.17，按 FongMi/TV 系 Decoder.java 移植）。
 *
 * <p> EasyBox / FongMi 对配置地址支持的形态，本类逐一对齐：
 * <ul>
 *   <li>纯 JSON / 带 // 注释 → 原样返回（注释由 Bridge 先清洗）；</li>
 *   <li>{@code xxx;md5;key} 地址尾 → 第三段作为 AES/ECB 密钥；</li>
 *   <li>含 8 字符标记 + {@code **} 的 base64 密文 → 解出 JSON；</li>
 *   <li>{@code 2423}（即 "##"）开头的 hex 密文 → AES/CBC，key/iv 内嵌在密文里；</li>
 *   <li>JSON 内的 {@code "./xx.js?.."} {@code "../xx.js?.."} 相对脚本 → 重写为绝对地址。</li>
 * </ul>
 * 每一步失败都保持原文，交给上层（图片藏 base64 / 普通解析）继续兜底。
 */
public final class TvboxDecoder {

    private static final Pattern JS_URI =
            Pattern.compile("\"(\\.|\\.\\.)/(.?|.+?)\\.js\\?(.?|.+?)\"");

    private TvboxDecoder() {}

    /**
     * @param rawUrl 配置原始地址（可带 {@code ;md5;key} 尾）
     * @param key    地址尾第三段（AES/ECB 密钥，可空）
     * @param text   已下载的配置文本
     */
    public static String decode(String rawUrl, String key, String text) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        String data = text == null ? "" : text;
        if (data.length() == 0) return "";
        try {
            if (isJson(data)) return fix(url, data);
            if (data.contains("**")) data = base64(data);
            if (data.startsWith("2423")) data = cbc(data);
            if (key != null && key.length() > 0) data = ecb(data, key);
        } catch (Throwable ignored) {
            return data;   // 解不出就交回上层兜底
        }
        return fix(url, data);
    }

    // ---------- 相对路径重写（./ ../ __JS1__ __JS2__） ----------

    private static String fix(String url, String data) {
        if (url == null || url.length() == 0) return data;
        Matcher matcher = JS_URI.matcher(data);
        while (matcher.find()) data = replace(url, data, matcher.group());
        if (data.contains("../")) data = data.replace("../", resolve(url, "../"));
        if (data.contains("./")) data = data.replace("./", resolve(url, "./"));
        if (data.contains("__JS1__")) data = data.replace("__JS1__", "./");
        if (data.contains("__JS2__")) data = data.replace("__JS2__", "../");
        return data;
    }

    private static String replace(String url, String data, String ext) {
        String t = ext.replace("\"./", "\"" + resolve(url, "./"));
        t = t.replace("\"../", "\"" + resolve(url, "../"));
        t = t.replace("./", "__JS1__").replace("../", "__JS2__");
        return data.replace(ext, t);
    }

    /** 基于配置 URL 解析 "./"（当前目录）与 "../"（上级目录）——返回带尾斜杠的目录。 */
    private static String resolve(String url, String rel) {
        try {
            int q = url.indexOf('?');
            if (q > 0) url = url.substring(0, q);
            if ("./".equals(rel)) return url.substring(0, url.lastIndexOf('/') + 1);
            if ("../".equals(rel)) {
                String dir = url.substring(0, url.lastIndexOf('/') + 1);
                int p = dir.lastIndexOf('/', dir.length() - 2);
                return p > 0 ? dir.substring(0, p + 1) : dir;
            }
        } catch (Throwable ignored) {}
        return url;
    }

    // ---------- base64（8 字符标记 **） ----------

    private static String base64(String data) {
        String extract = extract(data);
        if (extract.isEmpty()) return data;
        try {
            return new String(android.util.Base64.decode(extract, android.util.Base64.DEFAULT),
                    StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return data;
        }
    }

    private static String extract(String data) {
        Matcher matcher = Pattern.compile("[A-Za-z0-9]{8}\\*\\*").matcher(data);
        return matcher.find() ? data.substring(data.indexOf(matcher.group()) + 10) : "";
    }

    // ---------- 2423（##）hex + AES/CBC ----------

    private static String cbc(String data) throws Exception {
        String decode = new String(hex2byte(data)).toLowerCase();
        if (decode.indexOf("$#") < 0 || decode.indexOf("#$") < 0) return data;
        String key = padEnd(decode.substring(decode.indexOf("$#") + 2, decode.indexOf("#$")));
        String iv = padEnd(decode.substring(decode.length() - 13));
        SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes());
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        data = data.substring(data.indexOf("2324") + 4, data.length() - 26);
        byte[] decryptData = cipher.doFinal(hex2byte(data));
        return new String(decryptData, StandardCharsets.UTF_8);
    }

    // ---------- AES/ECB（;md5;key 的 key） ----------

    private static String ecb(String data, String key) throws Exception {
        SecretKeySpec spec = new SecretKeySpec(padEnd(key).getBytes(), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, spec);
        return new String(cipher.doFinal(hex2byte(data)), StandardCharsets.UTF_8);
    }

    private static String padEnd(String key) {
        if (key.length() > 16) key = key.substring(0, 16);
        return key + "0000000000000000".substring(key.length());
    }

    private static byte[] hex2byte(String s) {
        String t = s.trim();
        int len = t.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++)
            out[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static boolean isJson(String s) {
        String t = s.trim();
        if (t.length() == 0) return false;
        char c = t.charAt(0);
        if (c != '{' && c != '[') return false;
        try { new JSONObject(t); return true; } catch (Throwable ignored) {}
        try { new JSONArray(t); return true; } catch (Throwable ignored) {}
        return false;
    }
}
