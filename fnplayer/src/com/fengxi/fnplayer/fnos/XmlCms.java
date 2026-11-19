package com.fengxi.fnplayer.fnos;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.25：TVBox {@code type=0} XML 采集接口 → 苹果 CMS V10 等价 JSON 的适配器。
 *
 * <p>GitHub 上 TVBoxOS / FongMi-TV 的配置文档把 type 定义为：0=xml、1=json(CMS)、
 * 3=jar(spider)、4=remote。旧版只认 JSON，配置里大量 type=0 站点全部「解析不了」。
 * 这里做最小协议适配：把 XML 的分类 / 影片 / 选集转成 CmsClient 已经在消费的
 * {@code class[] / list[] / vod_id / vod_name / vod_pic / vod_play_url} 结构，
 * 前端、播放器、选集解析一律零改动。
 *
 * <p>只解析用户自己导入的源返回的文本，不内置、不聚合任何资源地址。
 */
public final class XmlCms {

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    private XmlCms() {}

    public static boolean looks(String body) {
        if (body == null) return false;
        String t = body.trim().toLowerCase();
        if (t.length() < 8 || t.charAt(0) != '<') return false;
        return t.startsWith("<?xml") || t.startsWith("<rss") || t.startsWith("<list")
                || t.startsWith("<video") || t.startsWith("<class") || t.startsWith("<xml")
                || t.startsWith("<root") || t.startsWith("<item")
                || t.startsWith("<items");
    }

    public static String toJson(String xml) throws Exception {
        JSONObject o = new JSONObject();
        o.put("code", 1);

        // 分类：<ty id="1">电影</ty>（部分站写成 <class><ty .../></class>）
        JSONArray cls = new JSONArray();
        Matcher m = Pattern.compile("<ty\\b([^>]*)>(.*?)</ty>", F).matcher(xml);
        while (m.find()) {
            String id = attr(m.group(1), "id");
            String name = text(m.group(2));
            if (id.length() == 0) id = String.valueOf(cls.length() + 1);
            if (name.length() > 0)
                cls.put(new JSONObject().put("type_id", id).put("type_name", name));
        }
        if (cls.length() > 0) o.put("class", cls);

        // 影片块：<video>…</video>，少数站用 <item>…</item>
        JSONArray list = new JSONArray();
        Matcher vm = Pattern.compile("<(video|item)\\b[^>]*>(.*?)</\\1>", F).matcher(xml);
        while (vm.find()) {
            String b = vm.group(2);
            if (b.indexOf("<id") < 0 && b.indexOf("<name") < 0) continue;   // 外层容器
            JSONObject v = new JSONObject();
            v.put("vod_id", tag(b, "id"));
            v.put("vod_name", tag(b, "name"));
            v.put("vod_pic", firstNonEmpty(tag(b, "pic"), tag(b, "img"), tag(b, "cover")));
            v.put("vod_remarks", firstNonEmpty(tag(b, "note"), tag(b, "state"), tag(b, "remarks")));
            v.put("vod_content", firstNonEmpty(tag(b, "des"), tag(b, "desc"), tag(b, "content")));
            v.put("type_name", firstNonEmpty(tag(b, "type"), tag(b, "typename")));

            // 播放组：<dd flag="qiyi"><![CDATA[第01集$url#第02集$url]]></dd>
            StringBuilder from = new StringBuilder();
            StringBuilder play = new StringBuilder();
            Matcher dd = Pattern.compile("<dd\\b([^>]*)>(.*?)</dd>", F).matcher(b);
            while (dd.find()) {
                String eps = text(dd.group(2));
                if (eps.length() == 0) continue;
                if (from.length() > 0) { from.append("$$$"); play.append("$$$"); }
                String flag = attr(dd.group(1), "flag");
                from.append(flag.length() > 0 ? flag : "默认");
                play.append(eps);
            }
            if (play.length() == 0) {
                String u = tag(b, "url");
                if (u.length() > 0) play.append("播放$").append(u);
            }
            v.put("vod_play_from", from.toString());
            v.put("vod_play_url", play.toString());
            list.put(v);
        }
        o.put("list", list);
        return o.toString();
    }

    private static String tag(String b, String name) {
        Matcher m = Pattern.compile("<" + name + "\\b[^>]*>(.*?)</" + name + ">", F).matcher(b);
        return m.find() ? text(m.group(1)) : "";
    }

    private static String attr(String s, String key) {
        Matcher m = Pattern.compile(key + "\\s*=\\s*[\"']([^\"']*)[\"']", F).matcher(s);
        return m.find() ? text(m.group(1)) : "";
    }

    /** 去 CDATA 壳、去子标签、解实体；保留 $ 与 #（选集格式靠它们）。 */
    private static String text(String s) {
        if (s == null) return "";
        String t = s.trim();
        int a = t.indexOf("<![CDATA[");
        if (a >= 0) {
            int e = t.indexOf("]]>", a + 9);
            t = e > a ? t.substring(a + 9, e) : t.substring(a + 9);
        }
        t = t.replaceAll("<[^>]*>", "").trim();
        return t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'");
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) if (s != null && s.length() > 0) return s;
        return "";
    }
}
