package com.tingshu.app.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;

/** 通用工具：MD5、时间格式化、HTML 转义还原、URL 编码。全部零依赖。 */
public final class Utils {

    private Utils() {}

    /** MD5 十六进制小写（书音FM 取链签名用） */
    public static String md5(String s) {
        if (s == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < d.length; i++) {
                int v = d[i] & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** URL 编码（UTF-8） */
    public static String enc(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /** 秒 -> mm:ss 或 h:mm:ss */
    public static String fmtTime(int sec) {
        if (sec < 0) sec = 0;
        int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) {
            sb.append(h).append(':');
            if (m < 10) sb.append('0');
        }
        sb.append(m).append(':');
        if (s < 10) sb.append('0');
        sb.append(s);
        return sb.toString();
    }

    /** 时间戳 -> yyyy-MM-dd HH:mm */
    public static String fmtDate(long ms) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(new Date(ms));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 时间戳 -> 相对描述（刚刚 / N分钟前 / N小时前 / N天前 / 日期） */
    public static String fmtRelative(long ms) {
        long d = System.currentTimeMillis() - ms;
        if (d < 60 * 1000L) return "刚刚";
        if (d < 3600 * 1000L) return (d / 60000) + "分钟前";
        if (d < 24 * 3600 * 1000L) return (d / 3600000) + "小时前";
        if (d < 30 * 24 * 3600 * 1000L) return (d / 86400000L) + "天前";
        return fmtDate(ms);
    }

    /** HTML 实体与转义还原：amp/lt/gt/quot/39/nbsp、反斜杠斜杠、unicode 转义序列 */
    public static String unescape(String s) {
        if (s == null) return "";
        s = s.replace("\\/", "/");
        s = unicodeUnescape(s);
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
             .replace("&apos;", "'").replace("&nbsp;", " ")
             .replace("&ldquo;", "\"").replace("&rdquo;", "\"")
             .replace("&hellip;", "…").replace("&mdash;", "—");
        return s;
    }

    /** 还原 unicode 转义序列（JSON 里中文路径常被转义，不还原会导致 URL 404） */
    public static String unicodeUnescape(String s) {
        if (s == null || s.indexOf("\\u") < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 5 < s.length() && s.charAt(i + 1) == 'u') {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    i += 5;
                    continue;
                } catch (Throwable ignored) {}
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 相对路径补全：//x -> https://x；/x -> base+x；x -> base/x */
    public static String abs(String base, String url) {
        if (url == null) return "";
        url = url.trim();
        if (url.length() == 0) return "";
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("//")) return "https:" + url;
        if (base == null || base.length() == 0) return url;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (url.startsWith("/")) return base + url;
        return base + "/" + url;
    }

    /** 取 URL 的 host（Cookie 隔离用） */
    public static String host(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 去掉 HTML 标签与多余空白 */
    public static String stripTags(String s) {
        if (s == null) return "";
        s = s.replaceAll("<[^>]+>", "");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** 文件名安全化：bookId 可能含 / : ? 等字符 */
    public static String safe(String s) {
        if (s == null) return "x";
        String r = s.replaceAll("[^0-9A-Za-z_.-]", "_");
        if (r.length() > 64) r = r.substring(0, 64);
        return r.length() == 0 ? "x" : r;
    }

    /** 是否为空串 */
    public static boolean empty(String s) {
        return s == null || s.trim().length() == 0;
    }
}
