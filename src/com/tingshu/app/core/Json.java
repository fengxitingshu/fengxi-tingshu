package com.tingshu.app.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * org.json 的安全取值工具。对应 Kotlin 版 Http.kt 的 s/i/obj/arr 语义。
 * 所有方法容错：类型不对或字段缺失一律返回默认值，不抛异常。
 */
public final class Json {

    private Json() {}

    /**
     * 剥掉 BOM 和零宽字符。
     * 有听网 / 念音网 接口返回体带 UTF-8 BOM(\uFEFF) 和 \u200B，
     * 不剥会导致 JSONObject 解析失败（首字符不是 '{'）。
     */
    public static String clean(String raw) {
        if (raw == null) return null;
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\uFEFF' || c == '\u200B' || c == '\u200C' || c == '\u200D') i++;
            else break;
        }
        return i == 0 ? raw : raw.substring(i);
    }

    /** 解析字符串为 JSONObject，失败返回 null */
    public static JSONObject obj(String raw) {
        if (raw == null) return null;
        try {
            return new JSONObject(clean(raw).trim());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取 JSONObject 字段（安全） */
    public static JSONObject obj(JSONObject o, String key) {
        if (o == null) return null;
        JSONObject r = o.optJSONObject(key);
        return r;
    }

    /** 按点路径取对象，如 child(root, "data.pageQuery") */
    public static JSONObject child(JSONObject root, String path) {
        if (root == null || path == null) return null;
        String[] parts = path.split("\\.");
        JSONObject cur = root;
        for (int i = 0; i < parts.length && cur != null; i++) {
            cur = cur.optJSONObject(parts[i]);
        }
        return cur;
    }

    /** 取字符串，null 归一成空串 */
    public static String s(JSONObject o, String key) {
        if (o == null) return "";
        String v = o.optString(key, "");
        return v == null ? "" : v;
    }

    /** 带别名的取字符串：依次尝试多个 key，返回第一个非空的 */
    public static String pick(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && v.length() > 0) return v;
        }
        return "";
    }

    /** 取 int（安全） */
    public static int i(JSONObject o, String key, int def) {
        if (o == null) return def;
        return o.optInt(key, def);
    }

    /** 取 long（安全） */
    public static long l(JSONObject o, String key, long def) {
        if (o == null) return def;
        return o.optLong(key, def);
    }

    /** 取双层嵌套 int：arr[idx] 的 key 字段 */
    public static int iAt(JSONArray a, int idx, String key, int def) {
        if (a == null || idx < 0 || idx >= a.length()) return def;
        JSONObject o = a.optJSONObject(idx);
        return i(o, key, def);
    }

    /** 取数组，失败返回空数组（不返回 null，方便直接 for） */
    public static List<JSONObject> arr(JSONObject o, String key) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        if (o == null) return out;
        JSONArray a = o.optJSONArray(key);
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject it = a.optJSONObject(i);
            if (it != null) out.add(it);
        }
        return out;
    }

    /** 取数组（原始 JSONArray），失败返回 null */
    public static JSONArray arrOf(JSONObject o, String... path) {
        if (o == null || path == null || path.length == 0) return null;
        JSONObject cur = o;
        for (int i = 0; i < path.length - 1 && cur != null; i++) {
            cur = cur.optJSONObject(path[i]);
        }
        return cur == null ? null : cur.optJSONArray(path[path.length - 1]);
    }

    /** 按点路径取字符串，如 path(root, "data.url") */
    public static String path(String raw, String dotted) {
        JSONObject o = obj(raw);
        if (o == null || dotted == null) return "";
        String[] parts = dotted.split("\\.");
        Object cur = o;
        for (int i = 0; i < parts.length; i++) {
            if (cur instanceof JSONObject) {
                cur = ((JSONObject) cur).opt(parts[i]);
            } else if (cur instanceof JSONArray) {
                try {
                    cur = ((JSONArray) cur).opt(Integer.parseInt(parts[i]));
                } catch (Throwable t) {
                    return "";
                }
            } else {
                return "";
            }
        }
        return cur == null || cur == JSONObject.NULL ? "" : String.valueOf(cur);
    }
}
