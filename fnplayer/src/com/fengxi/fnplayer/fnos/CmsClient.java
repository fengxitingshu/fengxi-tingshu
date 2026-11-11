package com.fengxi.fnplayer.fnos;

import com.fengxi.fnplayer.core.Net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 苹果 CMS V10 资源站 JSON API 客户端（v2.3 视频源导入支持）。
 *
 * <p>「资源站」是国内影视 API 最通用的格式（TVBox 系壳的默认源类型）：
 * <ul>
 *   <li>{@code GET {api}?ac=list}                      → 分类 {@code class[]: {type_id,type_name}}</li>
 *   <li>{@code GET {api}?ac=videolist&pg=&t=分类id}     → 列表 {@code list[]: {vod_id,vod_name,vod_pic,remarks,type_name}}</li>
 *   <li>{@code GET {api}?ac=videolist&ids=影片id}       → 详情（含 {@code vod_play_url}）</li>
 *   <li>{@code GET {api}?ac=videolist&wd=关键词}        → 搜索</li>
 * </ul>
 * {@code vod_play_url} 格式：{@code 播放源1$第01集$url1#第02集$url2$$$播放源2$...}，
 * 取第一组播放源，{@code #} 分集、{@code $} 分隔集名与直链。
 *
 * <p>输出复用 {@link FnClient.Result}（ok / list / data），Bridge 与前端零感知切换。
 */
public final class CmsClient {

    private final String api;          // 形如 https://api.example.com/api.php/provide/vod
    private final boolean trustSsl;

    public CmsClient(String api, boolean trustSsl) {
        this.api = api == null ? "" : api.trim();
        this.trustSsl = trustSsl;
    }

    public static boolean looksLikeCms(String api) {
        return api != null && (api.toLowerCase().contains("provide/vod")
                || api.contains("ac=")
                || api.toLowerCase().contains("vod"));
    }

    private String u(String query) {
        return api + (api.contains("?") ? "&" : "?") + query;
    }

    private FnClient.Result get(String query) {
        FnClient.Result r = new FnClient.Result();
        long t0 = System.currentTimeMillis();
        try {
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("User-Agent", Net.UA);
            h.put("Accept", "application/json, text/plain, */*");
            Net.Resp resp = Net.get(u(query), h, trustSsl, 12000);
            r.ms = System.currentTimeMillis() - t0;
            r.usedPath = u(query);
            r.raw = resp.body;
            if (resp.error.length() > 0) {
                r.error = resp.error;
                return r;
            }
            if (resp.code < 200 || resp.code >= 300) {
                r.error = "HTTP " + resp.code;
                return r;
            }
            // v2.15：很多站点（或相对 api 补全错了）会直接回一个网页，
            // 旧版抛「JSONException Value <!DOCTYPE ...」看不出问题在哪
            String body = resp.body == null ? "" : resp.body.trim();
            String low = body.toLowerCase();
            if (low.startsWith("<!doctype") || low.startsWith("<html")
                    || low.startsWith("<script") || low.contains("<title>")) {
                r.error = "接口返回的是网页不是 JSON（该地址可能不是 CMS 接口，或被站点拦截）\n" + u(query);
                return r;
            }
            JSONObject o = new JSONObject(resp.body);
            r.code = o.optInt("code", resp.code);
            r.data = o;
            r.list = o.optJSONArray("list");
            JSONArray cls = o.optJSONArray("class");
            if (cls != null) r.list = cls;      // 分类列表
            r.ok = (r.code == 1 || r.code == 200 || r.code == 0)
                    && (r.list != null || o.optJSONObject("data") != null);
            if (!r.ok) r.error = "code=" + r.code + "（接口拒绝）";
        } catch (Throwable t) {
            r.error = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage());
        }
        return r;
    }

    /** 分类列表（class[]）。 */
    public FnClient.Result categories() {
        return get("ac=list");
    }

    /** 影片列表：t 为分类 id（空=全部），page 从 1 开始。 */
    public FnClient.Result items(String typeId, int page) {
        String q = "ac=videolist&pg=" + (page <= 0 ? 1 : page);
        if (typeId != null && typeId.length() > 0) q += "&t=" + enc(typeId);
        return get(q);
    }

    /** 详情（含 vod_play_url）。 */
    public FnClient.Result detail(String vodId) {
        return get("ac=videolist&ids=" + enc(vodId));
    }

    public FnClient.Result search(String kw) {
        return get("ac=videolist&wd=" + enc(kw));
    }

    /**
     * 解析 vod_play_url → 集列表 [{guid:"direct:直链", title:集名}]。
     * 取第一组播放源（$$$ 分隔的第一段）。
     */
    public static JSONArray parseEpisodes(String playUrl) {
        JSONArray out = new JSONArray();
        if (playUrl == null || playUrl.length() == 0) return out;
        String group = playUrl.split("\\$\\$\\$")[0];
        for (String ep : group.split("#")) {
            String e = ep.trim();
            if (e.length() == 0) continue;
            int d = e.indexOf('$');
            String name = d > 0 ? e.substring(0, d).trim() : "播放";
            String url = d > 0 ? e.substring(d + 1).trim() : e;
            if (!url.startsWith("http")) continue;
            try {
                out.put(new JSONObject()
                        .put("guid", "direct:" + url)
                        .put("title", name));
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Throwable t) { return s == null ? "" : s; }
    }
}
