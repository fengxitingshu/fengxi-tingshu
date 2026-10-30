package com.tingshu.app.core;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ v4.11.0：通用 RSS / Atom 解析器（播客电台源与公版书源共用）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>不引入任何第三方 XML 库，用系统 {@link XmlPullParser}（android.util.Xml），
 *       崩溃面最小；解析全程 try-catch，坏块跳过不炸整份 feed。</li>
 *   <li>同时兼容 RSS 2.0（item/enclosure）与 Atom（entry/link rel=enclosure）；
 *       itunes: 命名空间字段（duration/episode/image）按**本地名**匹配，
 *       不关心前缀写法（itunes: / 空前缀都能命中）。</li>
 *   <li>集数提取：优先 itunes:episode，其次从标题里抓「第X回/集/卷/章」
 *       或开头数字 —— 公版书专辑靠它排回目顺序。</li>
 *   <li>上限保护：单份 feed 最多取 MAX_ITEMS 条，防止几千条的超大列表卡 UI。</li>
 * </ul>
 */
public final class RssParser {

    /** 单份 feed 最大条目数（lizhi 老播客有上千条，全要会卡列表） */
    public static final int MAX_ITEMS = 500;

    // ==================== 结果模型 ====================

    public static class Item {
        public String title = "";
        public String audio = "";     // enclosure 直链
        public String pubDate = "";
        public String duration = "";  // itunes:duration，如 "27:50"
        public int episode = -1;      // 集数（itunes:episode 或标题提取），无则 -1
    }

    public static class Feed {
        public String title = "";
        public String cover = "";
        public String desc = "";
        public final List<Item> items = new ArrayList<Item>();
    }

    private RssParser() {}

    // ==================== 对外入口 ====================

    /**
     * 抓取并解析一份 RSS（网络请求 + 解析）。
     * @param feedUrl RSS 地址
     * @throws Exception 网络/解析失败（message 面向用户可读）
     */
    public static Feed fetch(String feedUrl) throws Exception {
        if (feedUrl == null || feedUrl.trim().length() == 0) {
            throw new Exception("订阅地址为空");
        }
        String xml = Net.get(feedUrl.trim(), null);
        if (xml == null || xml.trim().length() == 0) {
            throw new Exception("订阅地址无响应（检查链接或网络）");
        }
        Feed f = parse(xml);
        if (f.items.isEmpty() && f.title.length() == 0) {
            throw new Exception("这不是有效的 RSS/播客地址");
        }
        return f;
    }

    // ==================== 解析 ====================

    /** 解析 XML 字符串。永不抛异常：解析失败返回空 Feed（title 为空即失败）。 */
    public static Feed parse(String xml) {
        Feed f = new Feed();
        if (xml == null || xml.length() == 0) return f;
        // 防超大 payload
        if (xml.length() > 8 * 1024 * 1024) xml = xml.substring(0, 8 * 1024 * 1024);
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new StringReader(xml));

            int ev = p.next();
            // 顶层路由：rss(channel/item) 或 atom:feed(entry)
            boolean inChannel = false, inItem = false, inImage = false;
            Item cur = null;
            StringBuilder text = new StringBuilder();

            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = local(p.getName());
                    if ("channel".equals(name) || "feed".equals(name)) {
                        inChannel = true;
                    } else if ("item".equals(name) || "entry".equals(name)) {
                        inItem = true;
                        cur = new Item();
                        if (f.items.size() < MAX_ITEMS) f.items.add(cur);
                    } else if (inChannel && "image".equals(name) && cur == null) {
                        inImage = true;          // channel/image/url（旧式封面）
                    } else if (cur != null) {
                        if ("enclosure".equals(name)) {
                            String u = p.getAttributeValue(null, "url");
                            if (u == null || u.length() == 0)
                                u = p.getAttributeValue(null, "href");
                            if (u != null && u.length() > 0 &&
                                    (cur.audio.length() == 0 || looksAudio(u)))
                                cur.audio = u.trim();
                        } else if ("link".equals(name)) {
                            // Atom：link rel="enclosure" href=音频
                            String rel = nz(p.getAttributeValue(null, "rel"));
                            String href = nz(p.getAttributeValue(null, "href"));
                            if ("enclosure".equals(rel) && href.length() > 0) {
                                cur.audio = href.trim();
                            }
                        } else if ("image".equals(name)) {
                            // itunes:image href=封面（channel 级）
                            if (cur == null && !inItem) {
                                String h = nz(p.getAttributeValue(null, "href"));
                                if (h.length() > 0 && f.cover.length() == 0) f.cover = h.trim();
                            }
                        } else if ("duration".equals(name)) {
                            text.setLength(0);       // 让 END_TEXT 填充
                        }
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (text.length() < 4096) text.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = local(p.getName());
                    String t = text.toString().trim();
                    text.setLength(0);

                    if ("item".equals(name) || "entry".equals(name)) {
                        if (cur != null) postItem(cur);
                        cur = null;
                        inItem = false;
                    } else if ("channel".equals(name) || "feed".equals(name)) {
                        inChannel = false;
                    } else if ("image".equals(name)) {
                        inImage = false;
                    } else if (inChannel && cur == null) {
                        // channel 级字段
                        if ("title".equals(name)) {
                            if (f.title.length() == 0 && t.length() > 0) f.title = t;
                        } else if ("description".equals(name) || "subtitle".equals(name)) {
                            if (f.desc.length() == 0 && t.length() > 0) f.desc = stripHtml(t);
                        } else if ("url".equals(name) && inImage) {
                            if (f.cover.length() == 0 && t.length() > 0) f.cover = t;
                        }
                    } else if (cur != null) {
                        // item 级字段
                        if ("title".equals(name)) {
                            if (cur.title.length() == 0) cur.title = stripHtml(t);
                        } else if ("duration".equals(name)) {
                            if (cur.duration.length() == 0) cur.duration = t;
                        } else if ("pubdate".equals(name) || "published".equals(name)
                                || "updated".equals(name)) {
                            if (cur.pubDate.length() == 0) cur.pubDate = t;
                        } else if ("summary".equals(name) || "description".equals(name)) {
                            // 暂不存 item 简介，标题已够用
                        }
                    }
                }
                ev = p.next();
            }
        } catch (Throwable ignored) {}
        // 逐条兜底
        for (Item it : f.items) postItem(it);
        return f;
    }

    /** 收尾：清 title、提取集数 */
    private static void postItem(Item it) {
        if (it.title == null) it.title = "";
        it.title = it.title.trim();
        if (it.episode < 0) it.episode = episodeFrom(it.title);
    }

    /** 集数：标题里「第X回/集/卷/章/期/节」或开头数字 */
    static int episodeFrom(String title) {
        if (title == null) return -1;
        Matcher m = Pattern.compile(
                "第\\s*([0-9０-９一二两三四五六七八九十百千零〇]+)\\s*[回集卷章期节]").matcher(title);
        if (m.find()) {
            int v = cnNum(m.group(1));
            if (v > 0) return v;
        }
        // 开头数字：001 xxx / 12. xxx
        m = Pattern.compile("^\\s*(\\d{1,4})(?![0-9])").matcher(title);
        if (m.find()) return parseInt(m.group(1));
        return -1;
    }

    // ==================== 工具 ====================

    /** 中文名数字 → int（支持纯数字/全角/一二三…一百零八） */
    static int cnNum(String s) {
        if (s == null || s.length() == 0) return -1;
        // 全角→半角
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '０' && c <= '９') c = (char) (c - '０' + '0');
            b.append(c);
        }
        String t = b.toString();
        if (t.matches("\\d+")) return parseInt(t);

        final String D = "零一二两三四五六七八九";
        int total = 0, cur = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            int d = D.indexOf(c);
            if (d >= 0) { cur = d; continue; }
            if (c == '十') { total += (cur == 0 ? 1 : cur) * 10; cur = 0; }
            else if (c == '百') { total += (cur == 0 ? 1 : cur) * 100; cur = 0; }
            else if (c == '千') { total += (cur == 0 ? 1 : cur) * 1000; cur = 0; }
            else if (c == '万') { total = (total + cur) * 10000; cur = 0; }
            else if (c == '〇') { cur = 0; }
            else return -1;   // 不认识的字，放弃
        }
        return total + cur;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return -1; }
    }

    private static String local(String tag) {
        if (tag == null) return "";
        int k = tag.indexOf(':');
        return (k >= 0 ? tag.substring(k + 1) : tag).toLowerCase();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** 去掉简介里的 HTML 标签，压成一行 */
    static String stripHtml(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    /** 音频链接特征（多 enclosure 时挑最像音频的） */
    private static boolean looksAudio(String u) {
        String l = u.toLowerCase();
        int q = l.indexOf('?');
        if (q >= 0) l = l.substring(0, q);
        return l.endsWith(".mp3") || l.endsWith(".m4a") || l.endsWith(".m4b")
                || l.endsWith(".aac") || l.endsWith(".ogg") || l.endsWith(".opus")
                || l.endsWith(".wav") || l.endsWith(".flac") || l.endsWith(".wma");
    }
}
