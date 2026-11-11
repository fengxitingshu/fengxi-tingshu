package com.tingshu.app.source;

import com.tingshu.app.core.js.JsEngine;
import com.tingshu.app.core.js.LxRuntime;
import com.tingshu.app.data.Models;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ v4.20.4：洛雪（LX）音源适配器。
 *
 * <p>LX 源脚本只提供取链（musicUrl），没有搜索 —— 搜索由本适配器内置
 * 酷我公开曲库接口提供，取链走脚本本体（经 {@link com.tingshu.app.core.js.JsCompat}
 * 语法降级原样执行，不修改源文件）。
 *
 * <p>链路：酷我搜索（rid）→ {@code lx.request 处理器 musicUrl(kw, rid)} → 播放直链。
 */
public class LxSourceAdapter implements Source {

    private static final String TAG = "LxSource";
    private static final String KW_SEARCH =
            "http://search.kuwo.cn/r.s?all=%s&ft=music&itemset=web_2013&client=kt&pn=%d&rn=20&rformat=json&encoding=utf8";
    private static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    private final JsEngine.Scope scope;
    private final String id;
    private final String name;
    private final String desc;

    public LxSourceAdapter(JsEngine.Scope scope, String pkgName, String initedJson) {
        this.scope = scope;
        // 稳定 id：包名派生（重导入覆盖）
        this.id = "lx-" + Integer.toHexString(
                (pkgName == null ? "lx" : pkgName).hashCode());
        this.name = pkgName == null || pkgName.length() == 0 ? "LX 音乐源" : pkgName;
        this.desc = "LX 音乐源（脚本取链 + 酷我曲库搜索），全站免登录。";
    }

    // ==================== Source ====================

    @Override public String id() { return id; }
    @Override public String name() { return name; }
    @Override public String desc() { return desc; }
    @Override public boolean canSearch() { return true; }

    @Override
    public List<Models.Book> search(String kw, int page) throws Exception {
        int p = Math.max(1, page);
        String url = String.format(KW_SEARCH,
                java.net.URLEncoder.encode(kw, "UTF-8"), (p - 1) * 20);
        try {
            String body = com.tingshu.app.core.Net.get(url, "http://www.kuwo.cn/", UA_MOBILE);
            List<Models.Book> out = parseKuwo(body, id);
            com.tingshu.app.core.PlayerLog.log("[LxSource:" + name + "] 搜索「" + kw + "」p" + p
                    + " → " + out.size() + " 条");
            return out;
        } catch (Throwable t) {
            com.tingshu.app.core.PlayerLog.log("[LxSource:" + name + "] 搜索失败「" + kw + "」："
                    + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            throw t;
        }
    }

    @Override
    public Models.BookDetail detail(String bookUrl) throws Exception {
        // 单曲：详情即一首"一章"的书
        Models.Book b = new Models.Book();
        b.source = id;
        b.bookId = bookUrl;
        b.title = "";
        b.url = bookUrl;
        Models.BookDetail d = new Models.BookDetail(b);
        d.chapters.add(new Models.Chapter(1, "播放", bookUrl));
        return d;
    }

    @Override
    public String audioUrl(Models.Chapter ch, Models.Book book) throws Exception {
        if (ch == null || ch.url == null) return null;
        // bookUrl 形如 "lx:kw:RID"
        String[] seg = ch.url.split(":");
        String src = seg.length >= 2 ? seg[1] : "kw";
        String rid = seg[seg.length - 1];
        if (!rid.matches("\\d+")) throw new Exception("歌曲 ID 无效：" + rid);
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            String u = resolveNative(rid);
            com.tingshu.app.core.PlayerLog.log("[LxSource:" + name + "] 取链成功 " + src + ":" + rid
                    + " → " + (u == null ? "null" : u.substring(0, Math.min(u.length(), 120)))
                    + "（" + (android.os.SystemClock.elapsedRealtime() - t0) + "ms）");
            return u;
        } catch (Throwable t) {
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            com.tingshu.app.core.PlayerLog.log("[LxSource:" + name + "] 取链失败 " + src + ":" + rid
                    + "（" + (android.os.SystemClock.elapsedRealtime() - t0) + "ms）：" + msg);
            throw new Exception("「" + name + "」音源取链失败：" + msg,
                    t instanceof Exception ? (Exception) t : null);
        }
    }

    /**
     * ★ v4.21.8：App 原生多后端取链（酷我 rid → 播放直链）。
     *
     * <p>背景：LX 脚本的取链是 async/Promise 链，经 JsCompat 降级成生成器 shim 后，
     * 在 Rhino 1.8.1 上微任务跨 Context 滞留、链条 45s 不落定（实测连「纯拼 URL、
     * 零 HTTP」的后端1 都卡死）；且脚本内嵌的 103.79.184.97 / 海堂 API 已停服。
     * 而搜索本来就是本适配器直调酷我曲库接口拿 rid —— 取链同样由 App 原生完成即可，
     * 与「不修改源文件、App 端加接口解析能力」的约定一致：**源文件原样加载不动**，
     * 播放链路完全不依赖脚本的异步实现。
     *
     * <p>按脚本 KW_BACKENDS 的可用后端逐个尝试（全部短超时，整体秒级）：
     * <ol>
     *   <li>c.wwwweb.top/music/url（POST JSON，实测 0.17s 返回酷我 CDN 直链）</li>
     *   <li>antiserver.kuwo.cn/anti.s（酷我官方老接口，返回 https 直链）</li>
     *   <li>175.27.166.236:8928/kwstream（纯拼 URL，本身即音频流地址，最后兜底）</li>
     * </ol>
     */
    private String resolveNative(String rid) throws Exception {
        StringBuilder err = new StringBuilder();
        // ---- 后端3：Hello World KW 备用 API（脚本里的后端3，当前唯一在服的 JSON 接口）----
        try {
            java.util.Map<String, String> h = new java.util.HashMap<String, String>();
            h.put("Content-Type", "application/json");
            h.put("User-Agent", "lx-music-desktop/2.10.1");
            String body = "{\"source\":\"kw\",\"musicId\":\"" + rid
                    + "\",\"quality\":\"320k\"}";
            com.tingshu.app.core.Net.Resp r = com.tingshu.app.core.Net.request(
                    "https://c.wwwweb.top/music/url", body, null, h, false, true);
            if (r != null && r.body != null) {
                org.json.JSONObject o = new org.json.JSONObject(r.body);
                String u = o.optString("url", "");
                if (u.startsWith("http")) {
                    com.tingshu.app.core.PlayerLog.log("[LX] 原生取链 后端3 wwwweb ✓");
                    return u;
                }
                err.append("wwwweb:").append(o.optString("message", "无 url")).append(" ");
            } else err.append("wwwweb:空响应 ");
        } catch (Throwable t) {
            err.append("wwwweb:").append(brief(t)).append(" ");
        }
        // ---- 酷我官方 antiserver（返回纯文本 https 直链）----
        try {
            String s = com.tingshu.app.core.Net.get(
                    "http://antiserver.kuwo.cn/anti.s?rid=" + rid
                            + "&format=mp3&response=url&type=convert_url",
                    "https://www.kuwo.cn/");
            if (s != null && s.trim().startsWith("http")) {
                com.tingshu.app.core.PlayerLog.log("[LX] 原生取链 酷我官方 anti.s ✓");
                return s.trim();
            }
            err.append("anti.s:无效响应 ");
        } catch (Throwable t) {
            err.append("anti.s:").append(brief(t)).append(" ");
        }
        // ---- 后端1：kwstream 纯拼流媒体（对部分 rid 会 400，仅作最后兜底）----
        // 探测时带 Range 只读 1KB，避免把整首歌读进内存；URL 本身即播放器要的地址
        String stream = "http://175.27.166.236:8928/kwstream?id=" + rid
                + "&level=master&stream=1";
        try {
            java.util.Map<String, String> h = new java.util.HashMap<String, String>();
            h.put("Range", "bytes=0-1024");
            com.tingshu.app.core.Net.Resp r = com.tingshu.app.core.Net.request(
                    stream, null, null, h, false, true);
            if (r != null && r.code >= 200 && r.code < 400) {
                com.tingshu.app.core.PlayerLog.log("[LX] 原生取链 后端1 kwstream ✓");
                return stream;
            }
            err.append("kwstream:HTTP ").append(r == null ? "?" : r.code).append(" ");
        } catch (Throwable t) {
            err.append("kwstream:").append(brief(t)).append(" ");
        }
        throw new Exception("全部取链后端不可用（" + err.toString().trim() + "）");
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName()
                : m.substring(0, Math.min(m.length(), 60));
    }

    @Override
    public Map<String, String> audioHeaders(String audioUrl) {
        return null;   // 酷我流媒体直链不带 Referer 才能播
    }

    @Override
    public Map<String, String> coverHeaders(String coverUrl) {
        return null;
    }

    // ==================== 酷我搜索解析 ====================

    /**
     * 酷我 r.s 返回「单引号伪 JSON」，且 MINFO 字段内嵌 JSON 花括号 ——
     * 用引号感知的花括号深度扫描切出顶层记录，再逐字段抽取。
     */
    private List<Models.Book> parseKuwo(String body, String srcId) {
        List<Models.Book> out = new ArrayList<Models.Book>();
        if (body == null) return out;
        int i = body.indexOf("'abslist':[");
        if (i < 0) return out;
        String seg = body.substring(i + 11);
        int end = seg.lastIndexOf(']');
        if (end >= 0) seg = seg.substring(0, end);

        // 引号感知的花括号深度扫描
        List<String> recs = new ArrayList<String>();
        int depth = 0, start = -1;
        boolean inStr = false;
        for (int k = 0; k < seg.length(); k++) {
            char ch = seg.charAt(k);
            if (inStr) {
                if (ch == '\\') { k++; continue; }
                if (ch == '\'') inStr = false;
                continue;
            }
            if (ch == '\'') { inStr = true; continue; }
            if (ch == '{') { if (depth == 0) start = k; depth++; }
            else if (ch == '}') {
                depth--;
                if (depth == 0 && start >= 0) { recs.add(seg.substring(start, k + 1)); start = -1; }
            }
        }

        for (String rec : recs) {
            String rid = field(rec, "MUSICRID");
            if (rid.startsWith("MUSIC_")) rid = rid.substring(6);
            String title = field(rec, "SONGNAME");
            if (title.isEmpty()) title = field(rec, "NAME");
            if (rid.isEmpty() || title.isEmpty()) continue;
            Models.Book b = new Models.Book();
            b.source = srcId;
            b.bookId = "lx:kw:" + rid;
            b.title = unesc(title);
            b.author = unesc(field(rec, "ARTIST"));
            b.cover = unesc(field(rec, "PICPATH"));
            b.intro = unesc(field(rec, "ALBUM"));
            b.url = b.bookId;
            out.add(b);
        }
        return out;
    }

    private static String field(String rec, String key) {
        Matcher m = Pattern.compile("'" + key + "'\\s*:\\s*'((?:\\\\'|[^'])*)'").matcher(rec);
        return m.find() ? m.group(1) : "";
    }

    private static String unesc(String s) {
        return s.replace("\\'", "'").replace("\\\\", "\\")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
    }
}
