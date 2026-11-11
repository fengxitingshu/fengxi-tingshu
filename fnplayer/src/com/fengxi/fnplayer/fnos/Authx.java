package com.fengxi.fnplayer.fnos;

import com.fengxi.fnplayer.core.Utils;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 飞牛影视 {@code authx} 签名（cse 签名机制）。
 *
 * <p>算法（与社区两个独立开源实现 fnv.py / fnos-tv 一致，均实测可用）：
 * <pre>
 * authx: nonce=&lt;6位随机数&gt;&amp;timestamp=&lt;毫秒&gt;&amp;sign=&lt;md5&gt;
 * body_hash:
 *   GET    → params 按 key 排序后 urlencode 再 md5（无参数即 md5("")）
 *   非 GET → 请求体 JSON 排序 + 紧凑序列化后 md5
 * 待签串: secret + "_" + path + "_" + nonce + "_" + ts + "_" + body_hash + "_" + apiKey
 * sign   = md5(待签串)      —— 六段全部用下划线连接
 * </pre>
 *
 * <p><b>secret / api_key 是飞牛影视前端 JS 里的公开常量</b>（随前端下发给每个浏览器，
 * 社区项目 fnos-tv 与 fnv.py 硬编码的值一致），因此这里直接内置默认值，
 * 用户手填的 secret/api_key 优先。飞牛大版本升级若导致签名失效，再换新值即可。
 */
public final class Authx {

    /** 飞牛影视前端内置 secret（公开常量，随 JS 分发）。 */
    public static final String DEFAULT_SECRET = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh";
    /** 飞牛影视前端内置 api_key（公开常量，UUID 形态）。 */
    public static final String DEFAULT_API_KEY = "16CCEB3D-AB42-077D-36A1-F355324E4237";

    private static final Random RND = new Random();

    private Authx() {}

    /** 用内置默认密钥签名（绝大多数场景）。 */
    public static String header(String method, String path, String query, String jsonBody) {
        return header(method, path, query, jsonBody, DEFAULT_SECRET, DEFAULT_API_KEY);
    }

    public static String header(String method, String path, String query,
                                String jsonBody, String secret, String apiKey) {
        String nonce = String.valueOf(100000 + RND.nextInt(900000));
        String ts = String.valueOf(System.currentTimeMillis());
        String bodyHash;
        if ("GET".equalsIgnoreCase(method)) {
            bodyHash = Utils.md5(sortedQuery(query));
        } else {
            bodyHash = Utils.md5(compactSortedJson(jsonBody == null ? "{}" : jsonBody));
        }
        // 六段全下划线连接（fnv.py / fnos-tv 一致）
        String raw = secret + "_" + path + "_" + nonce + "_" + ts + "_"
                + bodyHash + "_" + apiKey;
        return "nonce=" + nonce + "&timestamp=" + ts + "&sign=" + Utils.md5(raw);
    }

    /** GET 参数按 key 排序后 urlencode 成 a=1&b=2 形式 */
    static String sortedQuery(String query) {
        if (query == null || query.length() == 0) return "";
        java.util.Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        for (String pair : query.split("&")) {
            if (pair.length() == 0) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            m.put(k, v);
        }
        List<String> keys = new ArrayList<String>(m.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(k)).append('=').append(enc(m.get(k)));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Throwable t) { return s == null ? "" : s; }
    }

    /** 递归把 JSONObject 的 key 排序后紧凑序列化（Python sort_keys + separators=(',',':') 语义）。 */
    public static String compactSortedJson(String json) {
        try {
            String t = json.trim();
            if (t.startsWith("{")) return sortedObject(new JSONObject(t));
            return t;   // 非对象原样（数组/标量）
        } catch (Throwable e) { return json == null ? "{}" : json; }
    }

    private static String sortedObject(JSONObject o) throws Exception {
        List<String> keys = new ArrayList<String>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) keys.add(it.next());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String k : keys) {
            if (!first) sb.append(',');
            first = false;
            sb.append(new JSONObject().quote(k)).append(':');
            Object v = o.get(k);
            if (v instanceof JSONObject) sb.append(sortedObject((JSONObject) v));
            else if (v instanceof org.json.JSONArray) sb.append(v.toString());
            else if (v instanceof String) sb.append(new JSONObject().quote(String.valueOf(v)));
            else sb.append(String.valueOf(v));
        }
        return sb.append('}').toString();
    }
}
