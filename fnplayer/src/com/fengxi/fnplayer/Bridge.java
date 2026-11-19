package com.fengxi.fnplayer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import com.fengxi.fnplayer.core.LocalProxy;
import com.fengxi.fnplayer.core.Reach;
import com.fengxi.fnplayer.core.TvboxDecoder;
import com.fengxi.fnplayer.core.Utils;
import com.fengxi.fnplayer.data.Config;
import com.fengxi.fnplayer.fnos.CmsClient;
import com.fengxi.fnplayer.fnos.FnClient;
import com.fengxi.fnplayer.source.JsSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 前端 ↔ 原生桥。网络操作丢到 IO 池，结果回调 window.onXxx。 */
public final class Bridge {

    private final WebView web;
    private final MainActivity act;
    // v2.16：单线程池会被一个慢请求（豆瓣 API / jar 下载 / 自愈）堵死，
    // 后面所有 loadItems / 直播 / 切源全部排队 → 「卡住不动」。改 3 线程并发。
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    /** v2.25：探活独立线程池。旧版 spiderUsable 在 io 线程里再 submit 回 io 池，
     *  多个并发任务会把 3 条线程全占满并互相等待，界面看起来就是「卡死」。 */
    private static final ExecutorService PROBE =
            Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "fx-probe");
                    t.setDaemon(true);
                    return t;
                }
            });

    public Bridge(WebView web, MainActivity act) {
        this.web = web;
        this.act = act;
    }

    private void js(final String code) { act.callJs(code); }

    private static final java.util.concurrent.atomic.AtomicLong SRC_SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    // ==================== 配置 ====================

    @JavascriptInterface
    public String getConfig() {
        try {
            JSONObject o = new JSONObject();
            o.put("base", Config.base());
            o.put("token", Config.token());
            o.put("user", Config.user());
            o.put("pass", Config.pass());
            o.put("trustSsl", Config.trustSsl());
            o.put("secret", Config.secret());
            o.put("apiKey", Config.apiKey());
            o.put("webMode", Config.webMode());
            o.put("hasCredential", Config.hasCredential());
            // v2.17：以前硬编码 2.13，升级后设置页一直显示旧版本，
            // 导致「改了没装上」无法自查。改成读 APK 的真实版本信息。
            try {
                android.content.pm.PackageInfo pi = act.getPackageManager()
                        .getPackageInfo(act.getPackageName(), 0);
                o.put("version", pi.versionName);
                o.put("versionCode", pi.versionCode);
            } catch (Throwable ignored) {
                o.put("version", "2.25");
                o.put("versionCode", 36);
            }
            o.put("effectiveBase", Reach.cached().length() > 0 ? Reach.cached() : Config.base());
            // 设置中心
            o.put("homeColumns", Config.homeColumns());
            o.put("textureRender", Config.textureRender());
            o.put("preferSoft", Config.preferSoft());
            o.put("longPressSpeed", Config.longPressSpeed());
            o.put("defaultRatio", Config.defaultRatio());
            o.put("autoNext", Config.autoNext());
            o.put("backgroundPlay", Config.backgroundPlay());
            o.put("theme", Config.theme());
            try { o.put("history", new JSONArray(Config.history())); } catch (Throwable ignored) {}
            return o.toString();
        } catch (Throwable t) { return "{}"; }
    }

    /** 保存配置。base 会被 normalizeBase 归一（裸 IPv6 自动补方括号）。 */
    @JavascriptInterface
    public String saveConfig(String json) {
        JSONObject r = new JSONObject();
        try {
            JSONObject o = new JSONObject(json);
            String raw = o.optString("base", "");
            Reach.forget();     // 用户手动改了地址，清掉上一轮自动选路结果
            String norm = "";
            if (raw.length() > 0) {
                try {
                    norm = Utils.normalizeBase(raw);
                } catch (Throwable t) {
                    r.put("ok", false).put("error", t.getMessage());
                    return r.toString();
                }
                Config.setBase(norm);
            } else {
                norm = Config.base();
            }
            if (o.has("token")) Config.setToken(o.optString("token", ""));
            if (o.has("user")) Config.setUser(o.optString("user", ""));
            if (o.has("pass")) Config.setPass(o.optString("pass", ""));
            if (o.has("trustSsl")) Config.setTrustSsl(o.optBoolean("trustSsl", false));
            if (o.has("secret")) Config.setSecret(o.optString("secret", ""));
            if (o.has("apiKey")) Config.setApiKey(o.optString("apiKey", ""));
            // 设置中心
            if (o.has("homeColumns")) Config.setHomeColumns(o.optInt("homeColumns", 2));
            if (o.has("textureRender")) Config.setTextureRender(o.optBoolean("textureRender", false));
            if (o.has("preferSoft")) Config.setPreferSoft(o.optBoolean("preferSoft", false));
            if (o.has("longPressSpeed")) Config.setLongPressSpeed((float) o.optDouble("longPressSpeed", 3.0));
            if (o.has("defaultRatio")) Config.setDefaultRatio(o.optInt("defaultRatio", 0));
            if (o.has("autoNext")) Config.setAutoNext(o.optBoolean("autoNext", true));
            if (o.has("backgroundPlay")) Config.setBackgroundPlay(o.optBoolean("backgroundPlay", false));
            if (o.has("theme")) {
                Config.setTheme(o.optString("theme", "auto"));
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (act instanceof com.fengxi.fnplayer.MainActivity)
                            ((com.fengxi.fnplayer.MainActivity) act).applyWebBg();
                    }
                });
            }
            injectCookie();
            r.put("ok", true).put("base", norm).put("error", "");
        } catch (Throwable t) {
            try { r.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
        }
        return r.toString();
    }

    /** 把 token 注入 WebView Cookie，保证海报 <img> 与内嵌网页都能带上登录态 */
    private void injectCookie() {
        String base = Config.base();
        String token = Config.token();
        if (base.length() == 0 || token.length() == 0) return;
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setCookie(base, "authorization=" + token + "; Path=/");
            cm.setCookie(base, "Trim-MC-token=" + token + "; Path=/");
            cm.setCookie(base, "lastLoginUsername=" + Config.user() + "; Path=/");
            cm.flush();
        } catch (Throwable ignored) {}
    }

    @JavascriptInterface
    public void openLogin() {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                act.startActivity(new Intent(act, LoginActivity.class));
            }
        });
    }

    /** 原生账号密码登录（POST /v/api/v1/login + 内置 authx 签名），成功后保存 Token。 */
    @JavascriptInterface
    public void loginNative(final String user, final String pass) {
        io.execute(new Runnable() {
            @Override public void run() {
                String u = user == null ? "" : user.trim();
                String p = pass == null ? "" : pass;
                if (u.length() == 0 || p.length() == 0) {
                    js("window.onNativeLogin && window.onNativeLogin(false,"
                            + q("请先填写用户名和密码") + ")");
                    return;
                }
                FnClient.Result r = new FnClient().loginNative(u, p);
                String tk = FnClient.extractToken(r);
                if (tk.length() > 0) {
                    Config.setUser(u);
                    Config.setPass(p);
                    Config.setToken(tk);
                    FnClient.forgetShared();   // Token 变了，清客户端缓存
                    // 把登录态同步进 WebView Cookie：内嵌网页模式 / <img> 直连都靠它
                    try {
                        String b = Config.base();
                        if (b.length() > 0) {
                            CookieManager cm = CookieManager.getInstance();
                            cm.setCookie(b, "Trim-MC-token=" + tk + "; path=/");
                            cm.setCookie(b, "authorization=" + tk + "; path=/");
                            cm.setCookie(b, "lastLoginUsername=" + u + "; path=/");
                            cm.flush();
                        }
                    } catch (Throwable ignored) {}
                    js("window.onNativeLogin && window.onNativeLogin(true," + q("登录成功") + ")");
                } else {
                    String em = r.error.length() > 0 ? r.error
                            : (r.data == null ? "无响应" : "服务器拒绝（code=" + r.code + "）");
                    js("window.onNativeLogin && window.onNativeLogin(false," + q(em) + ")");
                }
            }
        });
    }

    @JavascriptInterface
    public void clearLogin() {
        Config.clearCredential();
        FnClient.forgetShared();
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookie();
            cm.flush();
        } catch (Throwable ignored) {}
        js("window.onLoginChanged && window.onLoginChanged()");
    }

    @JavascriptInterface
    public void setWebMode(final boolean v) { Config.setWebMode(v); }

    /** 清空观看历史，并通知首页刷新「继续观看」区。 */
    @JavascriptInterface
    public void clearHistory() {
        Config.clearHistory();
        js("window.onHistory && window.onHistory([])");
    }

    /** v2.19：观看历史列表（历史页用），回调 window.onHistory({ok,list})。 */
    @JavascriptInterface
    public void getHistory() {
        io.execute(new Runnable() {
            @Override public void run() {
                js("window.onHistory && window.onHistory({ok:true,list:"
                        + Config.history() + "})");
            }
        });
    }

    /**
     * v2.19：搜索（顶栏 🔍 入口）。当前源是资源站走 CMS ac=videolist&wd=，
     * 爬虫源走 searchContent（先 false 再 true 兼容两套签名）。
     * 回调 window.onSearch(resultJson) —— 复用首页卡片渲染。
     */
    @JavascriptInterface
    public void search(final String kw) {
        // v2.20.1：EasyBox 风格聚合搜索——所有可搜索源并行检索，
        // 每个源完成即推一次渐进结果（搜索(N/M)），不再串行逐个等。
        io.execute(new Runnable() {
            @Override public void run() {
                String k = kw == null ? "" : kw.trim();
                try {
                    if (k.length() == 0) {
                        js("window.onSearch && window.onSearch({ok:false,error:'请输入搜索关键词',list:[]})");
                        return;
                    }
                    java.util.List<JSONObject> cands = new java.util.ArrayList<JSONObject>();
                    JSONObject cur = currentSourceObj();
                    String curType = curSourceType();
                    if (("cms".equals(curType) || "spider".equals(curType)) && cur != null)
                        cands.add(cur);
                    try {
                        JSONArray arr = new JSONArray(Config.sources());
                        for (int i = 0; i < arr.length() && cands.size() < 12; i++) {
                            JSONObject s = arr.optJSONObject(i);
                            if (s == null) continue;
                            String ty = s.optString("type", "");
                            if (!"cms".equals(ty) && !"spider".equals(ty)) continue;
                            boolean dup = false;
                            for (JSONObject c : cands)
                                if (c.optString("id").equals(s.optString("id"))) { dup = true; break; }
                            if (!dup) cands.add(s);
                        }
                    } catch (Throwable ignored) {}
                    if (cands.isEmpty()) {
                        js("window.onSearch && window.onSearch({ok:false,error:'当前没有可搜索的资源站/爬虫源',list:[]})");
                        return;
                    }
                    final int total = cands.size();
                    final String name0 = cands.get(0).optString("name", "");
                    // 并行执行（io 池只有 3 线程，搜索临时开独立池）
                    java.util.concurrent.ExecutorService pool =
                            java.util.concurrent.Executors.newFixedThreadPool(
                                    Math.min(6, total));
                    final java.util.List<org.json.JSONObject> doneSrc =
                            java.util.Collections.synchronizedList(
                                    new java.util.ArrayList<org.json.JSONObject>());
                    final StringBuilder allCards = new StringBuilder("[");
                    final int[] done = {0};
                    for (final JSONObject src : cands) {
                        pool.execute(new Runnable() {
                            @Override public void run() {
                                String srcName = src.optString("name", "");
                                int count = 0;
                                String err = "";
                                FnClient.Result rr = searchIn(src, k);
                                if (rr.ok && rr.list != null && rr.list.length() > 0) {
                                    count = rr.list.length();
                                    // 给每条结果打上来源名
                                    for (int i = 0; i < rr.list.length(); i++) {
                                        JSONObject it = rr.list.optJSONObject(i);
                                        if (it != null) { try { it.put("__src", srcName); } catch (Throwable ignored) {} }
                                    }
                                    FnClient.Result tagged = rr;
                                    String cards = resultJson(tagged, false);
                                    // 提取 list 数组里的卡片 JSON 拼进聚合结果
                                    try {
                                        JSONArray la = new JSONObject(cards).optJSONArray("list");
                                        synchronized (allCards) {
                                            for (int i = 0; la != null && i < la.length(); i++) {
                                                if (allCards.length() > 1) allCards.append(",");
                                                allCards.append(la.opt(i).toString());
                                            }
                                        }
                                    } catch (Throwable ignored) {}
                                } else {
                                    err = rr.error;
                                }
                                org.json.JSONObject st = new org.json.JSONObject();
                                try {
                                    st.put("name", srcName).put("count", count);
                                    if (err.length() > 0) st.put("error",
                                            err.length() > 60 ? err.substring(0, 60) : err);
                                } catch (Throwable ignored) {}
                                doneSrc.add(st);
                                int d0;
                                synchronized (done) {
                                    done[0]++;
                                    d0 = done[0];
                                }
                                // 每个源完成都推一次渐进结果
                                String payload;
                                synchronized (allCards) {
                                    payload = "window.onSearch && window.onSearch({ok:true,progress:true,"
                                            + "done:" + d0 + ",total:" + total + ",kw:"
                                            + q(k) + ",sources:" + allCardsToString(doneSrc)
                                            + ",list:" + allCards + "]})";
                                }
                                js(payload);
                            }
                        });
                    }
                    pool.shutdown();
                } catch (Throwable t) {
                    js("window.onSearch && window.onSearch({ok:false,error:"
                            + q(Utils.brief(t)) + ",list:[]})");
                }
            }
        });
    }

    private String allCardsToString(java.util.List<org.json.JSONObject> l) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) b.append(",");
            b.append(l.get(i).toString());
        }
        return b.append("]").toString();
    }

    /** 在单个源内搜索（cms 走 ac=search，spider 走 searchContent 两次签名兼容）。 */
    private FnClient.Result searchIn(JSONObject src, String k) {
        FnClient.Result r = new FnClient.Result();
        String ty = src == null ? "" : src.optString("type", "");
        try {
            if ("cms".equals(ty)) {
                r = cms(src).search(k);
            } else if ("spider".equals(ty)) {
                try {
                    com.github.catvod.crawler.Spider sp =
                            com.fengxi.fnplayer.spider.SpiderRuntime.get(
                                    act, src.optString("jar", ""), src.optString("api", ""),
                                    src.optString("ext", ""));
                    JSONObject o;
                    try { o = new JSONObject(sp.searchContent(k, false)); }
                    catch (Throwable t0) { o = new JSONObject(sp.searchContent(k, true)); }
                    JSONArray l = o.optJSONArray("list");
                    if (l != null && l.length() > 0) { r.ok = true; r.list = l; }
                    else r.error = "没有搜到内容";
                } catch (Throwable t) {
                    r.error = spiderErrorHint(t);
                }
            } else {
                r.error = "该源不支持搜索";
            }
        } catch (Throwable t) {
            r.error = Utils.brief(t);
        }
        return r;
    }

    @JavascriptInterface
    public String getWebUrl() { return Config.base() + "/v/"; }

    /** 相对路径 → 绝对 URL（海报、网页资源用） */
    @JavascriptInterface
    public String absUrl(String p) {
        String base = Config.base();
        if (p == null || p.length() == 0) return "";
        if (p.startsWith("http://") || p.startsWith("https://")) return p;
        if (p.startsWith("//")) return (base.startsWith("https") ? "https:" : "http:") + p;
        if (p.startsWith("/")) return base + p;
        return base + "/v/" + p;
    }

    // ==================== 内容（v2.3：按当前源类型分发 fnos / cms） ====================

    /** 当前源类型：cms / nas（NAS 源，默认）。js 源只服务短剧栏，不进这里。 */
    private String curSourceType() {
        try {
            JSONArray arr = new JSONArray(Config.sources());
            String cur = Config.curSourceId();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && cur.equals(s.optString("id"))) {
                    String t = s.optString("type", "nas");
                    return "fnos".equals(t) ? "nas" : t;
                }
            }
        } catch (Throwable ignored) {}
        return "nas";
    }

    private CmsClient cms() {
        return new CmsClient(Config.base(), Config.trustSsl());
    }

    /** v2.20：按指定源建 CMS 客户端（跨源搜索用）。api 为空时退回当前 base。 */
    private CmsClient cms(JSONObject src) {
        String api = src == null ? "" : src.optString("api", "").trim();
        if (api.length() == 0) return cms();
        if (!api.startsWith("http")) api = idnUrl(api);
        return new CmsClient(api, Config.trustSsl());
    }

    /** 当前源对象（v2.13 spider 播放链路用）。找不到返回 null。 */
    private JSONObject curSourceObj() {
        try {
            JSONArray arr = new JSONArray(Config.sources());
            String cur = Config.curSourceId();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && cur.equals(s.optString("id"))) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 当前源对象（静态版，供 VideoActivity 取选集用）。 */
    public static JSONObject currentSourceObj() {
        try {
            JSONArray arr = new JSONArray(Config.sources());
            String cur = Config.curSourceId();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && cur.equals(s.optString("id"))) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static JSONObject findSourceById(String id) {
        try {
            JSONArray arr = new JSONArray(Config.sources());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && id.equals(s.optString("id"))) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * v2.15：按源类型取选集。播放器以前只认 direct:/spp:，其余 guid 一律
     * 丢给飞牛 FnClient.playUrl，CMS / 爬虫源点进去必报 play/info code=1、what=-10000。
     * nas 源返回空数组（仍交给 VideoActivity 的 FnClient 处理）。
     * guid 形如 spid|源id|影片id 时按指定源解析（短剧卡片可能来自非当前源）。
     */
    public static JSONArray episodesFor(Context ctx, String guid) {
        JSONArray out = new JSONArray();
        try {
            JSONObject src = currentSourceObj();
            String id = guid == null ? "" : guid;
            if (id.startsWith("spid|")) {
                String[] p = id.split("\\|", 3);
                JSONObject specified = findSourceById(p.length > 1 ? p[1] : "");
                if (specified != null) src = specified;
                id = p.length > 2 ? p[2] : "";
            }
            String type = src == null ? "nas" : src.optString("type", "nas");
            if ("cms".equals(type)) {
                CmsClient c = new CmsClient(src.optString("api", ""),
                        src.optBoolean("trustSsl", false));
                FnClient.Result d = c.detail(id);
                if (d.ok && d.list != null && d.list.length() > 0) {
                    JSONObject vod = d.list.optJSONObject(0);
                    if (vod != null) out = CmsClient.parseEpisodes(
                            vod.optString("vod_play_url", ""));
                }
            } else if ("spider".equals(type)) {
                com.github.catvod.crawler.Spider sp =
                        com.fengxi.fnplayer.spider.SpiderRuntime.get(
                                ctx.getApplicationContext(), src.optString("jar", ""),
                                src.optString("api", ""), src.optString("ext", ""));
                // v2.16：豆瓣推荐类爬虫把「搜索占位」当 vod_id（msearch:片名），
                // 直接 detailContent 拿不到播放列表。先 searchContent 换真实 id。
                String realId = spiderRealId(sp, id);
                try {
                    JSONObject o = new JSONObject(
                            sp.detailContent(java.util.Arrays.asList(realId)));
                    JSONArray l = o.optJSONArray("list");
                    if (l != null && l.length() > 0) out = parseSpiderEpisodes(l.optJSONObject(0));
                } catch (Throwable ignored) {}
                // 详情没给选集 → 直接问 playerContent 要播放地址（不少源靠这条出直链）
                if (out.length() == 0) out = spiderPlayerFallback(sp, realId);
                if (out.length() == 0 && !realId.equals(id)) out = spiderPlayerFallback(sp, id);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * v2.16：把「搜索占位 id」解析成真实 vod_id。
     * 豆瓣推荐类爬虫首页/分类返回的 vod_id 形如 msearch:片名，直接 detailContent
     * 拿不到播放列表；需先 searchContent(片名) 取第一条结果的真实 vod_id。
     * 非 msearch: 原样返回；搜索失败也原样返回，交给后续兜底。
     */
    private static String spiderRealId(com.github.catvod.crawler.Spider sp, String id) {
        if (id == null || !id.startsWith("msearch:")) return id;
        String kw = id.substring("msearch:".length()).trim();
        if (kw.length() == 0) return id;
        try {
            JSONObject sr = new JSONObject(sp.searchContent(kw, false));
            JSONArray sl = sr.optJSONArray("list");
            if (sl != null && sl.length() > 0) {
                JSONObject f = sl.optJSONObject(0);
                String vid = f == null ? "" : f.optString("vod_id", "");
                if (vid.length() > 0 && !vid.startsWith("msearch:")) return vid;
            }
        } catch (Throwable ignored) {}
        return id;
    }

    /** v2.16：详情无选集时，直接调 playerContent 探一次直链，成功则产出 direct: 集。 */
    private static JSONArray spiderPlayerFallback(
            com.github.catvod.crawler.Spider sp, String id) {
        JSONArray out = new JSONArray();
        try {
            JSONObject o = new JSONObject(sp.playerContent(
                    "", id, new java.util.ArrayList<String>()));
            String url = o.optString("url", "").trim();
            if (url.length() == 0) url = o.optString("playUrl", "").trim();
            if (url.length() == 0) url = o.optString("play_url", "").trim();
            if (url.startsWith("http")) {
                out.put(new JSONObject().put("guid", "direct:" + url).put("title", "播放"));
            } else if (url.length() > 0 || o.optInt("parse", 0) == 1) {
                // 交给播放器的 spp: 分支再解析一次（带 flag$id）
                out.put(new JSONObject()
                        .put("guid", "spp:" + com.fengxi.fnplayer.spider.SpiderRuntime.enc("$" + id))
                        .put("title", "播放"));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 当前 spider 源实例（v2.13 内置 TVBox spider 运行时）。失败抛出可读异常。 */
    private com.github.catvod.crawler.Spider curSpider() throws Exception {
        JSONObject src = curSourceObj();
        if (src == null || !"spider".equals(src.optString("type")))
            throw new Exception("当前源不是爬虫源");
        String jar = src.optString("jar", "");
        return com.fengxi.fnplayer.spider.SpiderRuntime.get(
                act, jar, src.optString("api", ""), resolveExt(src));
    }

    /** ext 文本的内存缓存：key = 配置文件绝对地址。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> EXT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    /**
     * v2.25：FongMi/TVBox 配置里站点 ext 常写成 {@code file://one.json}、
     * {@code ./one.json} 或远程 URL，运行时要下载后把「内容」当 extend 传给
     * Spider.init(Context,String)。旧版把文件名原样传给爬虫，于是这类站点
     * 全部返回空列表（用户看到的「很多源解析不了」）。改为按需下载并缓存。
     */
    private String resolveExt(JSONObject s) {
        String ext = s.optString("ext", "");
        String url = s.optString("extUrl", "");
        if (url.length() == 0 || !url.startsWith("http")) return ext;
        String hit = EXT_CACHE.get(url);
        if (hit != null) return hit;
        try {
            Map<String, String> h = new java.util.LinkedHashMap<String, String>();
            h.put("User-Agent", "okhttp/3.15");
            com.fengxi.fnplayer.core.Net.Resp resp =
                    com.fengxi.fnplayer.core.Net.get(url, h, true, 10000);
            if (resp.error.length() == 0 && resp.code >= 200 && resp.code < 300
                    && resp.body != null && resp.body.trim().length() > 0) {
                String body = resp.body.trim();
                EXT_CACHE.put(url, body);
                return body;
            }
        } catch (Throwable ignored) {}
        return ext;
    }

    @JavascriptInterface
    public void loadLibraries() {
        io.execute(new Runnable() {
            @Override public void run() {
                FnClient.Result r;
                try {
                    r = loadLibrariesOnce();
                    // v2.16：只对「网络层错误」自愈。接口返回网页 / homeContent 没分类
                    // 这类是源本身内容问题，切源也多半一样，傻等反而让首页一直转圈。
                    // v2.18：Guard 加密壳源（NoClassDefFoundError: DexNative）也触发自愈
                    // —— 这类源永远出不了内容，自动换到可用源，不再让首页整页报错。
                    if (!r.ok && (isNetError(r.error) || r.error.contains("加密壳")
                            || r.error.contains("不支持"))) {
                        String healed = tryHealSource();
                        if (healed.length() > 0) {
                            r = loadLibrariesOnce();
                            js("window.onSourceHealed && window.onSourceHealed(" + q(healed) + ")");
                        }
                    }
                } catch (Throwable t) {
                    // v2.25：任何逃逸异常都必须给前端一个回调，否则首页永远转圈
                    r = new FnClient.Result();
                    r.error = "读取媒体库异常：" + Utils.brief(t);
                }
                js("window.onLibraries && window.onLibraries(" + resultJson(r, true) + ")");
            }
        });
    }

    private FnClient.Result loadLibrariesOnce() {
        FnClient.Result r;
        if ("cms".equals(curSourceType())) {
            r = cms().categories();
        } else if ("spider".equals(curSourceType())) {
            r = new FnClient.Result();
            try {
                com.github.catvod.crawler.Spider sp = curSpider();
                JSONObject o = new JSONObject(sp.homeContent(false));
                JSONArray cls = o.optJSONArray("class");
                if (cls != null && cls.length() > 0) {
                    r.ok = true;
                    r.list = cls;
                } else {
                    r.error = "homeContent 没有返回分类";
                }
            } catch (Throwable t) {
                r.error = "爬虫源加载失败：" + spiderErrorHint(t);
            }
        } else {
            r = FnClient.shared().libraries();
            if (!r.ok && isNetError(r.error)) {
                Reach.Heal h = Reach.heal(Config.base());
                if (h.ok) {
                    r = FnClient.shared().libraries();
                    js("window.onBaseHealed && window.onBaseHealed(" + q(h.base) + ")");
                }
            }
        }
        return r;
    }

    /**
     * 当前源网络层连不上时，最多试 2 个其它 cms/spider 源，且有 8 秒总预算；
     * v2.16：synchronized 避免并发池里多个请求同时改 curSourceId 互相踩。
     */
    private synchronized String tryHealSource() {
        JSONObject orig = currentSourceObj();
        String cur = orig == null ? Config.curSourceId() : orig.optString("id");
        long deadline = System.currentTimeMillis() + 8000;
        try {
            JSONArray arr = new JSONArray(Config.sources());
            int tried = 0;
            for (int i = 0; i < arr.length() && tried < 4; i++) {
                if (System.currentTimeMillis() > deadline) break;   // 总预算，别拖死首屏
                JSONObject s = arr.optJSONObject(i);
                if (s == null || cur.equals(s.optString("id"))) continue;
                String type = s.optString("type", "");
                if (!"cms".equals(type) && !"spider".equals(type)) continue;
                tried++;
                Config.setCurSourceId(s.optString("id"));
                syncEffective(s);
                FnClient.Result r = loadLibrariesOnce();
                if (r.ok && r.list != null && r.list.length() > 0) {
                    Reach.forget();
                    FnClient.forgetShared();
                    return s.optString("name", "可用源");
                }
            }
        } catch (Throwable ignored) {}
        try {
            Config.setCurSourceId(cur);
            if (orig != null) syncEffective(orig);
        } catch (Throwable ignored) {}
        return "";
    }

    private static boolean isNetError(String e) {
        if (e == null || e.length() == 0) return true;
        return e.contains("连接") || e.contains("超时") || e.contains("不可达")
                || e.contains("解析失败") || e.contains("证书") || e.contains("SSL")
                || e.contains("HTTP -1") || e.contains("无响应");
    }

    @JavascriptInterface
    public void loadItems(final String guid, final int page) {
        io.execute(new Runnable() {
            @Override public void run() {
                FnClient.Result r;
                if ("cms".equals(curSourceType())) {
                    r = cms().items(guid, page);
                } else if ("spider".equals(curSourceType())) {
                    // v2.13：categoryContent(tid, pg, filter, extend)
                    r = new FnClient.Result();
                    try {
                        com.github.catvod.crawler.Spider sp = curSpider();
                        JSONObject o = new JSONObject(sp.categoryContent(
                                guid, String.valueOf(page <= 0 ? 1 : page), false,
                                new java.util.HashMap<String, String>()));
                        JSONArray l = o.optJSONArray("list");
                        if (l != null && l.length() > 0) {
                            r.ok = true;
                            r.list = l;
                        } else if (page <= 1) {
                            r.error = "categoryContent 没有返回列表";
                        } else {
                            r.ok = true;   // 翻页翻到底：空页不算错
                        }
                    } catch (Throwable t) {
                        r.error = "爬虫源列表失败：" + Utils.brief(t);
                    }
                } else {
                    r = FnClient.shared().items(guid, page);
                }
                js("window.onItems && window.onItems(" + resultJson(r, false) + ")");
            }
        });
    }

    @JavascriptInterface
    public void loadDetail(final String guid) {
        io.execute(new Runnable() {
            @Override public void run() {
                FnClient.Result r;
                if ("cms".equals(curSourceType())) {
                    r = cms().detail(guid);
                } else if ("spider".equals(curSourceType())) {
                    r = new FnClient.Result();
                    try {
                        com.github.catvod.crawler.Spider sp = curSpider();
                        String realId = spiderRealId(sp, guid);
                        JSONObject o = new JSONObject(
                                sp.detailContent(java.util.Arrays.asList(realId)));
                        JSONArray l = o.optJSONArray("list");
                        if (l != null && l.length() > 0) {
                            r.ok = true;
                            r.list = l;
                        } else {
                            r.error = "detailContent 没有返回详情";
                        }
                    } catch (Throwable t) {
                        r.error = "爬虫源详情失败：" + Utils.brief(t);
                    }
                } else {
                    r = FnClient.shared().detail(guid);
                }
                js("window.onDetail && window.onDetail(" + resultJson(r, false) + ")");
            }
        });
    }

    @JavascriptInterface
    public void loadEpisodes(final String guid) {
        io.execute(new Runnable() {
            @Override public void run() {
                FnClient.Result r;
                if ("cms".equals(curSourceType())) {
                    // cms：详情里的 vod_play_url → 集列表（direct: 前缀直链）
                    FnClient.Result d = cms().detail(guid);
                    r = new FnClient.Result();
                    r.ms = d.ms;
                    if (d.ok && d.list != null && d.list.length() > 0) {
                        JSONObject vod = d.list.optJSONObject(0);
                        if (vod != null) {
                            r.ok = true;
                            r.list = CmsClient.parseEpisodes(vod.optString("vod_play_url", ""));
                        } else r.error = "详情为空";
                    } else { r.ok = false; r.error = d.error; r.raw = d.raw; }
                } else if ("spider".equals(curSourceType())) {
                    // v2.13：spider 详情 → 多组播放源全部展开；
                    // 直链集 guid=direct:url，需解析的集 guid=spp:flag$id（VideoActivity 里取链）
                    r = new FnClient.Result();
                    try {
                        com.github.catvod.crawler.Spider sp = curSpider();
                        String realId = spiderRealId(sp, guid);
                        JSONObject o = new JSONObject(
                                sp.detailContent(java.util.Arrays.asList(realId)));
                        JSONArray l = o.optJSONArray("list");
                        if (l != null && l.length() > 0) {
                            r.ok = true;
                            r.list = parseSpiderEpisodes(l.optJSONObject(0));
                            if (r.list.length() == 0) r.list = spiderPlayerFallback(sp, realId);
                            if (r.list.length() == 0) r.error = "详情里没有选集（vod_play_url 为空）";
                        } else {
                            r.error = "detailContent 没有返回详情";
                        }
                    } catch (Throwable t) {
                        r.error = "爬虫源选集失败：" + Utils.brief(t);
                    }
                } else {
                    r = FnClient.shared().episodes(guid);
                }
                js("window.onEpisodes && window.onEpisodes(" + resultJson(r, false) + ")");
            }
        });
    }

    /**
     * spider 详情的 vod_play_url → 集列表。格式与 cms 相同（组间 $$$、集间 #、集内 名称$url），
     * 但直链不保证是 http（可能是播放 id），所以：
     * <ul>
     *   <li>url 以 http 开头 → guid = "direct:" + url（VideoActivity 直链模式）；</li>
     *   <li>其它 → guid = "spp:" + urlencode(flag$id)，播放时走 playerContent 取真实地址。</li>
     * </ul>
     * 多组播放源时集名前缀「播放源名·」，避免不同清晰度/线路的集名混淆。
     */
    private static JSONArray parseSpiderEpisodes(JSONObject vod) {
        JSONArray out = new JSONArray();
        if (vod == null) return out;
        String from = vod.optString("vod_play_from", "");
        String pu = vod.optString("vod_play_url", "");
        if (pu.length() == 0) return out;
        String[] flags = from.split("\\$\\$\\$");
        String[] groups = pu.split("\\$\\$\\$");
        boolean multi = groups.length > 1;
        for (int g = 0; g < groups.length; g++) {
            String flag = g < flags.length ? flags[g].trim() : "";
            for (String ep : groups[g].split("#")) {
                String e = ep.trim();
                if (e.length() == 0) continue;
                int d = e.indexOf('$');
                String name = d > 0 ? e.substring(0, d).trim() : "播放";
                String url = d > 0 ? e.substring(d + 1).trim() : e;
                String title = (multi && flag.length() > 0 ? flag + "·" : "") + name;
                try {
                    if (url.startsWith("http")) {
                        out.put(new JSONObject().put("guid", "direct:" + url).put("title", title));
                    } else {
                        out.put(new JSONObject()
                                .put("guid", "spp:" + com.fengxi.fnplayer.spider.SpiderRuntime.enc(flag + "$" + url))
                                .put("title", title));
                    }
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    /** 把宽容解析的列表规整成前端好用的 {guid,title,poster,sub,type} 数组 */
    private String resultJson(FnClient.Result r, boolean libraries) {
        JSONObject o = new JSONObject();
        JSONArray out = new JSONArray();
        try {
            o.put("ok", r.ok);
            o.put("error", r.error);
            o.put("usedPath", r.usedPath);
            o.put("ms", r.ms);
            if (!r.ok && r.raw.length() > 0) {
                // 失败时把服务器原始响应带回前端（截断），用户截图即可定位
                String raw = r.raw.replaceAll("\\s+", " ");
                o.put("raw", raw.substring(0, Math.min(raw.length(), 300)));
            }
            if (r.ok) {
                if (r.list != null) {
                    for (int i = 0; i < r.list.length(); i++) {
                        Object v = r.list.opt(i);
                        if (v instanceof JSONObject) {
                            JSONObject d = toItem((JSONObject) v);
                            if (d != null) out.put(d);
                        }
                    }
                } else if (r.data != null) {
                    // 详情类接口：返回单个对象
                    JSONObject s = r.data.optJSONObject("data");
                    if (s == null) s = r.data;
                    JSONObject d = toItem(s);
                    if (d != null) out.put(d);
                }
            }
            o.put("list", out);
        } catch (Throwable t) {
            try { o.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
        }
        return o.toString();
    }

    /** 把飞牛返回的一个条目规整成前端统一结构 */
    private JSONObject toItem(JSONObject s) {
        try {
            JSONObject d = new JSONObject();
            String guid = pick(s, "guid", "id", "media_guid", "item_guid", "vod_id", "type_id");
            String title = pick(s, "title", "name", "media_name", "show_name", "video_name",
                    "vod_name", "type_name");
            String poster = pick(s, "poster", "posters", "poster_path", "cover", "thumb",
                    "image", "backdrop", "backdrops", "poster_url", "cover_url", "vod_pic");
            d.put("guid", guid);
            d.put("title", title.length() > 0 ? title : "未命名");
            // 海报走本地代理带鉴权：sys/img 需要 Cookie/Token，
            // 原生账号密码登录后 WebView 里没有 Cookie，<img> 直连会 401 全挂
            d.put("poster", posterUrl(poster));
            d.put("sub", pick(s, "year", "release_date", "overview", "desc", "vod_content", "tagline",
                    "remarks", "vod_remarks"));
            d.put("type", pick(s, "type", "media_type", "item_type", "type_name"));
            // v2.20.1：聚合搜索时给结果标注来源源名（前端渲染「来源：X」）
            String srcName = s.optString("__src", "");
            if (srcName.length() > 0) d.put("src", srcName);
            return d;
        } catch (Throwable t) { return null; }
    }

    /** 海报地址：完整 URL 直接用；相对值拼 sys/img 端点后走本地代理补鉴权头。 */
    private String posterUrl(String raw) {
        if (raw == null || raw.length() == 0) return "";
        try {
            // posters 字段有时是 JSON 对象/数组（如 {"original":"x.jpg"}），先取出真实值
            String t = raw.trim();
            if (t.startsWith("{") || t.startsWith("[")) {
                try {
                    org.json.JSONTokener tk = new org.json.JSONTokener(t);
                    Object jo = tk.nextValue();
                    raw = firstString(jo, 0);
                    if (raw == null || raw.length() == 0) return "";
                } catch (Throwable ignored) { return ""; }
            }
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                // v2.16：豆瓣等外部图床普遍有防盗链（校验 Referer/UA），WebView 里
                // <img> 直连会 403 → 海报整片空白。改走本地代理：带浏览器 UA +
                // 以图床自身域为 Referer，既过防盗链又不依赖 NAS 鉴权头。
                // 仅对 http/https 生效，代理失败时退回原直链。
                java.util.Map<String, String> ph = new java.util.HashMap<String, String>();
                try {
                    java.net.URL u = new java.net.URL(raw);
                    ph.put("Referer", u.getProtocol() + "://" + u.getHost() + "/");
                } catch (Throwable ignored) {}
                String local = LocalProxy.serveStable(raw, ph, true);
                return local != null ? local : raw;
            }
            String p = raw.startsWith("/") ? raw.substring(1) : raw;
            String real = absUrl("api/v1/sys/img/92/17/" + p) + "?w=200";
            // 稳定代理地址：同 URL 复用同一 id，不被播放代理的淘汰逻辑挤掉（封面空白修复）
            String local = LocalProxy.serveStable(real, FnClient.shared().playHeaders(), Config.trustSsl());
            return local != null ? local : real;
        } catch (Throwable t) { return ""; }
    }

    /** 从嵌套 JSON 里按深度优先找第一个像文件名的字符串值。 */
    private static String firstString(Object o, int depth) {
        if (o == null || depth > 4) return null;
        if (o instanceof String) {
            String s = (String) o;
            return s.length() > 0 && !s.startsWith("http") ? s : null;
        }
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            for (String k : new String[]{"original", "path", "url", "poster", "file", "value"}) {
                String r = firstString(j.opt(k), depth + 1);
                if (r != null) return r;
            }
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String r = firstString(j.opt(it.next()), depth + 1);
                if (r != null) return r;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                String r = firstString(a.opt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static String pick(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v.length() > 0 && !"null".equals(v)) return v;
        }
        return "";
    }

    // ==================== 播放 / 杂项 ====================

    // ==================== 源管理（v2.4：分层 —— 源分类 → 源 → 源设置） ====================
    // 分类：nas=NAS 源（原内置飞牛改为该分类下的可配置源）、cms=资源站、js=脚本源（短剧）

    /** 首次调用时：预置红果短剧脚本源 + 把旧版内置飞牛迁移成一个 NAS 源实例。 */
    private static boolean srcSeeded = false;

    private void ensureSourceSeed() {
        if (srcSeeded) return;
        srcSeeded = true;
        try {
            JSONArray arr = new JSONArray(Config.sources());
            // 1) 旧版 fnos 源改名为 nas（数据兼容）
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && "fnos".equals(s.optString("type"))) {
                    s.put("type", "nas");
                    if (s.optString("cat", "").length() == 0) s.put("cat", "NAS 源");
                    changed = true;
                }
            }
            // 2) 旧版「内置飞牛」→ 迁移成 NAS 源实例
            if (Config.base().length() > 0 && countType(arr, "nas") == 0) {
                JSONObject nas = new JSONObject();
                nas.put("id", "s" + System.currentTimeMillis());
                nas.put("name", "我的 NAS");
                nas.put("type", "nas").put("cat", "NAS 源");
                nas.put("api", Config.base()).put("user", Config.user())
                        .put("pass", Config.pass()).put("token", Config.token())
                        .put("trustSsl", Config.trustSsl())
                        .put("secret", Config.secret()).put("apiKey", Config.apiKey());
                arr.put(nas);
                Config.setCurSourceId(nas.optString("id"));
                changed = true;
            }
            // 3) v2.6：不再内置红果短剧源 —— 删除老版本预置的 s_hongguo（用户可自导入 JS 源）
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && "s_hongguo".equals(s.optString("id"))) {
                    arr.remove(i);
                    changed = true;
                }
            }
            if (changed) Config.setSources(arr.toString());
        } catch (Throwable ignored) {}
    }

    private static int countType(JSONArray arr, String type) {
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s != null && type.equals(s.optString("type"))) n++;
        }
        return n;
    }

    private String readAsset(String path) {
        try {
            InputStream is = act.getAssets().open(path);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) { return ""; }
    }

    /** 源列表 + 当前选中（v2.25：只给摘要，不带 script/ext/jar 大字段）。 */
    @JavascriptInterface
    public String listSources() {
        JSONObject o = new JSONObject();
        try {
            ensureSourceSeed();
            o.put("ok", true);
            o.put("list", slimSources(new JSONArray(Config.sources())));
            o.put("cur", Config.curSourceId());
        } catch (Throwable t) {
            try { o.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
        }
        return o.toString();
    }

    /**
     * v2.25：TVBox 一份配置常有几十上百个站点，站点的 ext / 脚本 / img+ base64 jar
     * 每个几 KB～几百 KB。旧版 listSources 把整个源数组原样返回给 WebView，
     * 前端 loadSources / renderSrcCat / loadHome 又要反复 JSON.parse 这份 MB 级字符串，
     * 卡死甚至 OOM 闪退。这里改成「摘要清单」，大字段只在需要时按需取。
     */
    private static JSONArray slimSources(JSONArray arr) {
        JSONArray out = new JSONArray();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            JSONObject d = new JSONObject();
            java.util.Iterator<String> it = s.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("script".equals(k) || "ext".equals(k) || "jar".equals(k)) continue;
                Object v = s.opt(k);
                if (v == null) continue;
                if (v instanceof String && ((String) v).length() > 4096)
                    v = ((String) v).substring(0, 4096);
                try { d.put(k, v); } catch (Throwable ignored) {}
            }
            try {
                d.put("hasScript", s.optString("script", "").length() > 0)
                 .put("hasExt", s.optString("ext", "").length() > 0
                         || s.optString("extUrl", "").length() > 0)
                 .put("hasJar", s.optString("jar", "").length() > 0);
            } catch (Throwable ignored) {}
            out.put(d);
        }
        return out;
    }

    /** 按需取单个源的完整参数（编辑 / 诊断用），避免大字段常驻前端。 */
    @JavascriptInterface
    public String getSource(String id) {
        try {
            JSONArray arr = new JSONArray(Config.sources());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && id != null && id.equals(s.optString("id"))) return s.toString();
            }
        } catch (Throwable ignored) {}
        return "{}";
    }

    /**
     * 添加源。json: {name, type:"nas"|"cms"|"js", api, user, pass, trustSsl, secret, apiKey, script}
     * nas 源会立即用账号密码登录验证；成功后保存 token 到源参数里。
     */
    /**
     * v2.23：异步包装。NAS 源会同步登录验证，绝不能跑在 WebView 的 JavaBridge
     * 线程上（历史上「选源卡死 20s」就是同步网络堵死整条桥）。结果走回调。
     */
    @JavascriptInterface
    public void addSourceAsync(final String json) {
        io.execute(new Runnable() {
            @Override public void run() {
                String r = addSource(json);
                js("window.onAddSourceDone && window.onAddSourceDone(" + r + ")");
            }
        });
    }

    /** 同步实现：仅 Java 内部调用，前端一律用 addSourceAsync。 */
    public String addSource(String json) {
        JSONObject r = new JSONObject();
        try {
            JSONObject s = new JSONObject(json);
            String type = s.optString("type", "nas");
            if ("fnos".equals(type)) type = "nas";
            if (!"nas".equals(type) && !"cms".equals(type) && !"js".equals(type)) type = "nas";
            String cat = "nas".equals(type) ? "NAS 源" : "cms".equals(type) ? "资源站" : "短剧源";

            String script = s.optString("script", "");
            if ("js".equals(type)) {
                if (script.trim().length() == 0) {
                    r.put("ok", false).put("error", "JS 源需要脚本内容（或用导入功能）");
                    return r.toString();
                }
            } else {
                String api = s.optString("api", "").trim();
                if (api.length() == 0) {
                    r.put("ok", false).put("error", "请填接口地址");
                    return r.toString();
                }
                try {
                    // CMS 接口地址保留路径（api.php/provide/vod），NAS 才用 normalizeBase
                    api = "cms".equals(type) ? Utils.normalizeApi(api) : Utils.normalizeBase(api);
                }
                catch (Throwable t) { r.put("ok", false).put("error", t.getMessage()); return r.toString(); }
                s.put("api", api);
            }
            String name = s.optString("name", "").trim();
            if (name.length() == 0)
                name = "js".equals(type) ? "JS 源" : Utils.hostOf(s.optString("api", ""));

            String token = s.optString("token", "");
            if ("nas".equals(type) && s.optString("user", "").length() > 0) {
                FnClient fc = new FnClient(s.optString("api", ""), s.optBoolean("trustSsl", true), "",
                        "", s.optString("user", ""), s.optString("secret", ""), s.optString("apiKey", ""));
                FnClient.Result lr = fc.loginNative(s.optString("user", ""), s.optString("pass", ""));
                String tk = FnClient.extractToken(lr);
                if (tk.length() > 0) token = tk;
                else {
                    r.put("ok", false).put("error", "登录失败："
                            + (lr.error.length() > 0 ? lr.error : "code=" + lr.code));
                    return r.toString();
                }
            }
            JSONObject src = new JSONObject();
            String id = "s" + System.currentTimeMillis();
            src.put("id", id).put("name", name).put("type", type).put("cat", cat)
                    .put("manual", true)   // v2.15：切线路时不被清掉
                    .put("api", s.optString("api", ""))
                    .put("user", s.optString("user", "")).put("pass", s.optString("pass", ""))
                    .put("token", token).put("trustSsl", s.optBoolean("trustSsl", true))
                    .put("secret", s.optString("secret", "")).put("apiKey", s.optString("apiKey", ""))
                    .put("script", script);
            JSONArray arr = new JSONArray(Config.sources());
            arr.put(src);
            Config.setSources(arr.toString());
            r.put("ok", true).put("id", id);
        } catch (Throwable t) {
            try { r.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
        }
        return r.toString();
    }

    /** 编辑源设置（第三层展开后的保存）。json: {id, name?, api?, user?, pass?, trustSsl?, secret?, apiKey?} */
    @JavascriptInterface
    public String updateSource(String json) {
        JSONObject r = new JSONObject();
        try {
            JSONObject p = new JSONObject(json);
            String id = p.optString("id", "");
            JSONArray arr = new JSONArray(Config.sources());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null || !id.equals(s.optString("id"))) continue;
                if (p.has("name")) s.put("name", p.optString("name", "").trim());
                if (p.has("api")) {
                    String api = p.optString("api", "").trim();
                    try {
                        api = "cms".equals(p.optString("type", s.optString("type", "")))
                                ? Utils.normalizeApi(api) : Utils.normalizeBase(api);
                    } catch (Throwable ignored) {}
                    s.put("api", api);
                }
                if (p.has("user")) s.put("user", p.optString("user", ""));
                if (p.has("pass")) s.put("pass", p.optString("pass", ""));
                if (p.has("trustSsl")) s.put("trustSsl", p.optBoolean("trustSsl", true));
                if (p.has("secret")) s.put("secret", p.optString("secret", ""));
                if (p.has("apiKey")) s.put("apiKey", p.optString("apiKey", ""));
                // 改了 NAS 连接参数，旧 token 作废，要求重新登录
                if (p.has("api") || p.has("user") || p.has("pass")) s.put("token", "");
                Config.setSources(arr.toString());
                // 若是当前源，同步生效字段
                if (id.equals(Config.curSourceId())) syncEffective(s);
                r.put("ok", true);
                return r.toString();
            }
            r.put("ok", false).put("error", "源不存在");
        } catch (Throwable t) {
            try { r.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
        }
        return r.toString();
    }

    /** 用源的参数重新登录（NAS 源），成功后 token 存回源里。结果回调 onSourceLogin。 */
    @JavascriptInterface
    public void loginSource(final String id) {
        io.execute(new Runnable() {
            @Override public void run() {
                String msg = "源不存在";
                boolean ok = false;
                try {
                    JSONArray arr = new JSONArray(Config.sources());
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject s = arr.optJSONObject(i);
                        if (s == null || !id.equals(s.optString("id"))) continue;
                        FnClient fc = new FnClient(s.optString("api", ""),
                                s.optBoolean("trustSsl", true), "", "",
                                s.optString("user", ""), s.optString("secret", ""),
                                s.optString("apiKey", ""));
                        FnClient.Result lr = fc.loginNative(s.optString("user", ""),
                                s.optString("pass", ""));
                        String tk = FnClient.extractToken(lr);
                        if (tk.length() > 0) {
                            s.put("token", tk);
                            Config.setSources(arr.toString());
                            if (id.equals(Config.curSourceId())) syncEffective(s);
                            ok = true;
                            msg = "登录成功";
                        } else {
                            msg = "登录失败：" + (lr.error.length() > 0 ? lr.error
                                    : "code=" + lr.code);
                        }
                        break;
                    }
                } catch (Throwable t) {
                    msg = "登录失败：" + Utils.brief(t);
                }
                final boolean fok = ok;
                final String fmsg = msg;
                js("window.onSourceLogin && window.onSourceLogin(" + fok + "," + q(fmsg) + ")");
            }
        });
    }

    /** 把源参数写入生效字段（base/token/user/pass…），FnClient 零改动。 */
    private void syncEffective(JSONObject s) {
        try {
            String api = s.optString("api", "");
            String type = s.optString("type", "nas");
            // v2.16：spider / js 源的 api 是「爬虫类名」（csp_Douban）或脚本地址，
            // 不是服务器地址。若写进 Config.base()，FnClient.playUrl 会拼出
            // "csp_Douban/v/media/xxx/preset.m3u8" → MalformedURLException: no protocol，
            // 也会让相对封面地址补全错乱。非 NAS 源一律清空 base，只保留 token 等。
            boolean nonNas = "spider".equals(type) || "js".equals(type) || "cms".equals(type);
            if (nonNas && !api.startsWith("http")) {
                Config.setBase("");
            } else {
                // cms 源 api 是 http 接口地址，cms() 仍依赖 Config.base() 读它；
                // nas 源 api 是服务器根地址。两者照旧写入。
                Config.setBase(api);
            }
            Config.setUser(s.optString("user", ""));
            Config.setPass(s.optString("pass", ""));
            Config.setToken(s.optString("token", ""));
            Config.setTrustSsl(s.optBoolean("trustSsl", true));
            Config.setSecret(s.optString("secret", ""));
            Config.setApiKey(s.optString("apiKey", ""));
            injectCookie();
            com.fengxi.fnplayer.fnos.FnClient.forgetShared();   // 连接参数变了，清客户端缓存
        } catch (Throwable ignored) {}
    }

    /** 删除源。 */
    @JavascriptInterface
    public String delSource(String id) {
        JSONObject r = new JSONObject();
        try {
            JSONArray arr = new JSONArray(Config.sources());
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && !id.equals(s.optString("id"))) out.put(s);
            }
            Config.setSources(out.toString());
            if (id.equals(Config.curSourceId())) Config.setCurSourceId("");
            r.put("ok", true);
        } catch (Throwable t) {
            try { r.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
        }
        return r.toString();
    }

    /** 切换当前源：把源的连接参数写入生效字段。 */
    @JavascriptInterface
    public String selectSource(String id) {
        JSONObject r = new JSONObject();
        try {
            ensureSourceSeed();
            Reach.forget();
            if (id == null || id.length() == 0) {
                Config.setCurSourceId("");
                r.put("ok", false).put("error", "请选择一个源");
                return r.toString();
            }
            JSONArray arr = new JSONArray(Config.sources());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && id.equals(s.optString("id"))) {
                    // v2.13：spider 源也可选 —— 内置 spider 运行时直接驱动播放
                    Config.setCurSourceId(id);
                    syncEffective(s);
                    r.put("ok", true).put("name", s.optString("name", ""))
                            .put("type", s.optString("type", "nas"));
                    return r.toString();
                }
            }
            r.put("ok", false).put("error", "源不存在");
        } catch (Throwable t) {
            try { r.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
        }
        return r.toString();
    }

    /**
     * 导入源（v2.4）。input 可以是：
     * <ul>
     *   <li>http(s) URL —— 自动下载；若是 zip（PK 头）解包读 manifest.json + scripts；</li>
     *   <li>直接粘贴的 JS 源脚本文本（含 registerSource）。</li>
     * </ul>
     * 导入成功自动归入对应分类（manifest group 或名称含「短剧」→ 短剧源）。
     */
    /**
     * v2.23：异步包装。URL 导入最长 30s，原来同步跑在桥线程会让整个界面假死。
     * 复用已有的 onImportDone 回调（文件导入也走它）。
     */
    @JavascriptInterface
    public void importSourceAsync(final String input, final String nameHint) {
        io.execute(new Runnable() {
            @Override public void run() {
                String r = importSource(input, nameHint);
                js("window.onImportDone && window.onImportDone(" + r + ")");
            }
        });
    }

    /** 同步实现：仅 Java 内部调用，前端一律用 importSourceAsync。 */
    public String importSource(String input, String nameHint) {
        JSONObject r = new JSONObject();
        try {
            String text = input == null ? "" : input.trim();
            if (text.length() == 0) {
                r.put("ok", false).put("error", "请填源地址或粘贴脚本内容");
                return r.toString();
            }
            String name = nameHint == null ? "" : nameHint.trim();
            String cat = "";
            String script = "";
            if (text.startsWith("http://") || text.startsWith("https://")) {
                Map<String, String> h = new java.util.LinkedHashMap<String, String>();
                h.put("User-Agent", com.fengxi.fnplayer.core.Net.UA);
                com.fengxi.fnplayer.core.Net.Resp resp =
                        com.fengxi.fnplayer.core.Net.get(text, h, true, 30000);
                if (resp.error.length() > 0 || resp.code >= 400) {
                    r.put("ok", false).put("error", resp.error.length() > 0 ? resp.error
                            : "HTTP " + resp.code);
                    return r.toString();
                }
                byte[] data = resp.bytes != null ? resp.bytes : resp.body.getBytes("UTF-8");
                if (data.length > 2 && data[0] == 'P' && data[1] == 'K') {
                    JSONObject m = unzipSource(data);
                    script = m.optString("script", "");
                    if (name.length() == 0) name = m.optString("name", "");
                    if (cat.length() == 0) cat = m.optString("group", "");
                } else {
                    script = new String(data, "UTF-8");
                }
                if (name.length() == 0) name = Utils.hostOf(text);
            } else {
                script = text;
            }
            if (script.indexOf("registerSource") < 0) {
                r.put("ok", false).put("error", "没有识别到 registerSource（不是本 App 支持的 JS 源格式）");
                return r.toString();
            }
            if (cat.length() == 0)
                cat = name.contains("短剧") || script.contains("短剧") ? "短剧源" : "短剧源";
            JSONObject src = new JSONObject();
            String id = "s" + System.currentTimeMillis();
            src.put("id", id).put("name", name.length() > 0 ? name : "导入源")
                    .put("type", "js").put("cat", cat).put("script", script);
            JSONArray arr = new JSONArray(Config.sources());
            arr.put(src);
            Config.setSources(arr.toString());
            r.put("ok", true).put("id", id).put("name", src.optString("name")).put("cat", cat);
        } catch (Throwable t) {
            try { r.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
        }
        return r.toString();
    }

    /** 解 zip 源包：读 manifest.json（name/group/scripts）+ 脚本正文。 */
    private JSONObject unzipSource(byte[] data) throws Exception {        JSONObject out = new JSONObject();
        ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(data));
        Map<String, byte[]> files = new java.util.LinkedHashMap<String, byte[]>();
        ZipEntry e;
        while ((e = zi.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = zi.read(buf)) > 0) bo.write(buf, 0, n);
            files.put(e.getName(), bo.toByteArray());
        }
        zi.close();
        // manifest.json
        JSONObject mf = null;
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            if (f.getKey().endsWith("manifest.json")) {
                mf = new JSONObject(new String(f.getValue(), "UTF-8"));
                break;
            }
        }
        if (mf != null) {
            out.put("name", mf.optString("name", ""));
            out.put("group", mf.optString("group", ""));
        }
        // scripts[0]
        String scriptName = mf != null && mf.optJSONArray("scripts") != null
                && mf.optJSONArray("scripts").length() > 0
                ? mf.optJSONArray("scripts").optString(0) : "";
        if (scriptName.length() > 0) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                if (f.getKey().endsWith(scriptName)) {
                    out.put("script", new String(f.getValue(), Charset.forName("UTF-8")));
                    break;
                }
            }
        }
        if (out.optString("script", "").length() == 0) {
            // 兜底：包里最大的 .js
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                if (f.getKey().endsWith(".js")) {
                    String s = new String(f.getValue(), Charset.forName("UTF-8"));
                    if (s.indexOf("registerSource") >= 0) { out.put("script", s); break; }
                }
            }
        }
        return out;
    }

    // ==================== TVBox 配置解析（v2.5，只做解析不内置） ====================

    /**
     * 解析 TVBox 配置（url 或文本）。支持两种形态：
     * <ul>
     *   <li>仓库列表 {"urls":[{url,name}…]} → 返回 {type:"repo",list}，由用户点选其一再解析；</li>
     *   <li>配置本体（含 sites / lives）→ type=1 的 CMS JSON 资源站存为资源站源，
     *       lives 存为直播源；type=0(xml)/3/4(需爬虫 jar) 跳过并计数。</li>
     * </ul>
     * 兼容 // 注释、/* *​/ 注释与尾逗号。
     */
    // v2.23：降为 Java 内部方法，前端已全走 parseTvboxAsync；不再暴露同步网络面。
    public String parseTvbox(String input) {
        JSONObject r = new JSONObject();
        try {
            String text = input == null ? "" : input.trim();
            if (text.length() == 0) { r.put("ok", false).put("error", "请填配置地址"); return r.toString(); }
            boolean fromUrl = text.startsWith("http://") || text.startsWith("https://");
            if (fromUrl) {
                text = extractJsonText(fetchConfigText(text));
                if (text.length() == 0) { r.put("ok", false).put("error", "下载配置失败（地址不可达、被拦或不是配置）"); return r.toString(); }
            } else {
                text = extractJsonText(text);
            }
            JSONObject o;
            try {
                o = new JSONObject(stripJsonComments(text));
            } catch (Throwable t) {
                r.put("ok", false).put("error", "不是有效的 TVBox 配置（JSON 解析失败）");
                return r.toString();
            }
            // 形态一：仓库列表
            JSONArray urls = o.optJSONArray("urls");
            if (urls != null && o.optJSONArray("sites") == null) {
                JSONArray list = new JSONArray();
                for (int i = 0; i < urls.length(); i++) {
                    JSONObject u = urls.optJSONObject(i);
                    if (u == null) continue;
                    JSONObject it = new JSONObject();
                    it.put("name", u.optString("name", "配置 " + (i + 1)));
                    it.put("url", u.optString("url", ""));
                    if (it.optString("url", "").length() > 0) list.put(it);
                }
                r.put("ok", true).put("type", "repo").put("list", list);
                return r.toString();
            }
            // 形态二：配置本体
            parseSitesAndLives(o, r, new JSONArray(), fromUrl ? input.trim() : "");
            // v2.25：只统计 CMS 是老 bug —— 现在主流线路几乎全是 type=3 爬虫站，
            // 旧代码会把「全是爬虫站」误判成解析失败并整体回滚，源一个都留不下。
            if (r.optInt("added", 0) == 0 && r.optInt("spiderAdded", 0) == 0
                    && r.optInt("liveAdded", 0) == 0) {
                r.put("ok", false).put("error", "没解析到可用的站点或直播源（"
                        + r.optInt("skipped", 0) + " 个站点缺少接口地址）");
                return r.toString();
            }
            r.put("ok", true).put("type", "config");
        } catch (Throwable t) {
            try { r.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
        }
        return r.toString();
    }

    /**
     * 异步解析 TVBox（v2.8）：仓库形态不再自动逐线路解析，而是存成一个
     * 「TVBox 源」（type=box，lines 字段保存全部线路），回调 window.onTvboxDone
     * 带 type:"repo" 与 lines —— 由前端弹出「选择线路」弹层（参考 TVBox 订阅交互），
     * 用户点选线路后 selectBoxLine() 解析该线路的 sites/lives 并对接首页与直播。
     * 配置本体（sites/lives）行为不变：直接解析入分类。
     */
    @JavascriptInterface
    public void parseTvboxAsync(final String input) {
        io.execute(new Runnable() {
            @Override public void run() {
                JSONObject r = new JSONObject();
                try {
                    String text = input == null ? "" : input.trim();
                    boolean fromUrl = false;
                    if (text.length() == 0) { r.put("ok", false).put("error", "请填配置地址"); return; }
                    fromUrl = text.startsWith("http://") || text.startsWith("https://");
                    if (fromUrl) {
                        text = extractJsonText(fetchConfigText(text));
                        if (text.length() == 0) { r.put("ok", false).put("error", "下载配置失败（地址不可达、被拦或不是配置）"); return; }
                    } else {
                        text = extractJsonText(text);
                    }
                    JSONObject o;
                    try { o = new JSONObject(stripJsonComments(text)); }
                    catch (Throwable t) { r.put("ok", false).put("error", "不是有效的 TVBox 配置（JSON 解析失败）"); return; }
                    JSONArray urls = o.optJSONArray("urls");
                    if (urls != null && o.optJSONArray("sites") == null) {
                        // 仓库形态：存为 box 源（含线路列表），不自动展开
                        JSONArray lines = new JSONArray();
                        for (int i = 0; i < urls.length(); i++) {
                            JSONObject u = urls.optJSONObject(i);
                            if (u == null) continue;
                            String cu = u.optString("url", "").trim();
                            // v2.25：多仓里也常见相对线路地址，按仓库地址补全
                            if (!cu.startsWith("http") && input != null
                                    && input.trim().startsWith("http"))
                                cu = completeRelative(cu, input.trim());
                            if (!cu.startsWith("http")) continue;
                            JSONObject ln = new JSONObject();
                            ln.put("name", u.optString("name", "线路 " + (i + 1)));
                            ln.put("url", cu);
                            lines.put(ln);
                        }
                        if (lines.length() == 0) {
                            r.put("ok", false).put("error", "仓库里没有可用的线路地址"); return;
                        }
                        JSONObject src = new JSONObject();
                        String id = "s" + SRC_SEQ.incrementAndGet();
                        String name = input == null ? "" : input.trim();
                        name = name.startsWith("http") ? Utils.hostOf(name) : "TVBox 多仓";
                        src.put("id", id).put("name", name).put("type", "box")
                                .put("cat", "TVBox 源").put("api", input == null ? "" : input.trim())
                                .put("lines", lines);
                        JSONArray arr = new JSONArray(Config.sources());
                        arr.put(src);
                        Config.setSources(arr.toString());
                        r.put("ok", true).put("type", "repo").put("id", id)
                                .put("name", name).put("lines", lines);
                        return;
                    }
                    int added = 0, skipped = 0, liveAdded = 0, spiderAdded = 0;
                    JSONArray names = new JSONArray();
                    // v2.25：配置里 sites 也可能是一个指向站点列表的 URL
                    if (o.optJSONArray("sites") == null) {
                        String sitesUrl = o.optString("sites", "").trim();
                        if (sitesUrl.startsWith("http")) {
                            String sub = extractJsonText(fetchConfigText(sitesUrl));
                            if (sub.length() > 0) {
                                try { o = new JSONObject(stripJsonComments(sub)); }
                                catch (Throwable ignored) {}
                            }
                        }
                    }
                    parseSitesAndLives(o, r, names, fromUrl ? input.trim() : "");
                    added = r.optInt("added", 0);
                    skipped = r.optInt("skipped", 0);
                    liveAdded = r.optInt("liveAdded", 0);
                    spiderAdded = r.optInt("spiderAdded", 0);
                    if (added == 0 && spiderAdded == 0 && liveAdded == 0) {
                        r.put("ok", false).put("error", "没解析到可用的站点/直播源"
                                + (skipped > 0 ? "（" + skipped + " 个站点缺少接口地址）" : "")
                                + liveSkipHint(r));
                        return;
                    }
                    r.put("ok", true).put("type", "config").put("added", added)
                            .put("spiderAdded", spiderAdded).put("skipped", skipped)
                            .put("liveAdded", liveAdded).put("names", names);
                } catch (Throwable t) {
                    try { r.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
                } finally {
                    js("window.onTvboxDone && window.onTvboxDone(" + r + ")");
                }
            }
        });
    }

    /**
     * 选择 TVBox 仓库的一条线路（v2.8 引入，v2.10 改异步）：下载该线路配置 →
     * sites 存入资源站、lives 存入直播源 → 线路名记回 box 源 → 自动把线路第一个
     * 资源站设为当前源。结果回调 window.onBoxLineDone(json)。
     */
    @JavascriptInterface
    public void selectBoxLine(final String id, final String lineUrl, final String lineName) {
        // ★ v2.10 改异步：原来同步跑在 WebView 的 JavaBridge 线程上，网络慢时
        //   整个前端 JS 被堵住（toast/弹层全卡），多次操作直接假死。
        io.execute(new Runnable() {
            @Override public void run() {
                JSONObject r = new JSONObject();
                // ★ v2.17：先备份 + 先清理。旧版把 clearAutoSources() 放在下载成功之后，
                // 线路下载失败直接 return → 旧线路的 csp_ 源全部残留，于是小米线的
                // csp_Douban 和王二小线的 csp_AiNewHuaJuanGuard 会混在一个源列表里。
                final String bakSources = Config.sources();
                final String bakLives = Config.lives();
                final String bakCur = Config.curSourceId();
                clearAutoSources();
                boolean ok = false;
                try {
                    String body = extractJsonText(fetchConfigText(lineUrl == null ? "" : lineUrl.trim()));
                    if (body.length() == 0) {
                        r.put("ok", false).put("error", "线路配置下载失败（地址不可达或超时）");
                    } else {
                        JSONObject o = null;
                        try { o = new JSONObject(stripJsonComments(body)); }
                        catch (Throwable ignored) {}
                        if (o == null) {
                            r.put("ok", false).put("error", "线路配置不是有效的 TVBox JSON");
                        } else {
                            r.put("newIds", new JSONArray());
                            JSONArray names = new JSONArray();
                            parseSitesAndLives(o, r, names, lineUrl == null ? "" : lineUrl.trim());
                            int added = r.optInt("added", 0), liveAdded = r.optInt("liveAdded", 0);
                            int spiderAdded = r.optInt("spiderAdded", 0);
                            if (added == 0 && spiderAdded == 0 && liveAdded == 0) {
                                r.put("ok", false).put("error", "该线路没解析到可用的站点/直播源"
                                        + (r.optInt("skipped", 0) > 0 ? "（这些站点缺少接口地址）" : "")
                                        + liveSkipHint(r));
                            } else {
                                // 线路名记回 box 源
                                JSONArray arr = new JSONArray(Config.sources());
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject s = arr.optJSONObject(i);
                                    if (s == null || !id.equals(s.optString("id"))) continue;
                                    s.put("selectedLine", lineName == null ? "" : lineName);
                                    Config.setSources(arr.toString());
                                    break;
                                }
                                // 对接到首页：挑一个「探活通过」的源设为当前源
                                String firstName = "";
                                String chosen = pickHealthySource(r.optJSONArray("newIds"));
                                if (chosen.length() > 0) {
                                    JSONArray arr2 = new JSONArray(Config.sources());
                                    for (int i = 0; i < arr2.length(); i++) {
                                        JSONObject s = arr2.optJSONObject(i);
                                        if (s != null && chosen.equals(s.optString("id")))
                                            firstName = s.optString("name", "");
                                    }
                                    selectSource(chosen);
                                }
                                // 第一个直播源 id 回传给前端（自动选中直播下拉）
                                String firstLive = "";
                                try {
                                    JSONArray la = new JSONArray(Config.lives());
                                    // parseSitesAndLives 刚追加过，取最后一个 id 即最新加入的
                                    if (liveAdded > 0 && la.length() > 0)
                                        firstLive = la.optJSONObject(la.length() - 1).optString("id", "");
                                } catch (Throwable ignored) {}
                                r.put("ok", true).put("added", added).put("spiderAdded", spiderAdded)
                                        .put("liveAdded", liveAdded).put("skipped", r.optInt("skipped", 0))
                                        .put("lineName", lineName == null ? "" : lineName)
                                        .put("firstName", firstName).put("firstLive", firstLive)
                                        .put("names", names);
                                ok = true;
                            }
                        }
                    }
                } catch (Throwable t) {
                    try { r.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
                }
                if (!ok) {
                    // 失败回滚：不让半吊子状态污染源列表
                    try {
                        Config.setSources(bakSources);
                        Config.setLives(bakLives);
                        Config.setCurSourceId(bakCur);
                    } catch (Throwable ignored) {}
                }
                // ★ v2.17：无论成功失败都必须回调。旧版失败分支直接 return，
                // 跳过了这里的 js()，前端弹窗永远停在「正在解析线路…」。
                js("window.onBoxLineDone && window.onBoxLineDone(" + r + ")");
            }
        });
    }

    /**
     * 配置本体 → CMS 资源站存入源列表、lives 存入直播源。计数写入 r、站名追加 names、新源 id 追加 newIds。
     * v2.13：cfgUrl 传配置的下载地址，用于把相对 api（"api.php/provide/vod"、"/api.php" 等）拼成完整地址；
     * CMS 判定放宽 —— type=1 一律按资源站（api 相对也能拼），未标 type 但 api 像 CMS 的也收。
     */
    private void parseSitesAndLives(JSONObject o, JSONObject r, JSONArray names, String cfgUrl) throws Exception {
        int added = 0, skipped = 0, spiderAdded = 0;
        // v2.25：保留配置「完整 URL」用于相对路径补全。旧版只取 scheme://host，
        // 把 https://host/tv/config.json 旁的相对 jar/ext 全拼到根目录 → 404。
        String cfgFull = cfgUrl == null ? "" : cfgUrl.trim();
        if (!cfgFull.startsWith("http")) cfgFull = "";
        String base = cfgFull.length() > 0 ? baseUrlOf(cfgFull) : "";
        JSONArray sites = o.optJSONArray("sites");
        JSONArray arr = null;
        try { arr = new JSONArray(Config.sources()); } catch (Throwable ignored) {}
        if (arr == null) arr = new JSONArray();
        if (sites != null) {
            for (int i = 0; i < sites.length(); i++) {
                JSONObject s = sites.optJSONObject(i);
                if (s == null) continue;
                int type = s.optInt("type", -1);
                String api = s.optString("api", "").trim();
                String name = s.optString("name", s.optString("key", "源"));
                // v2.13 放宽：type=1 是 CMS 资源站，api 是相对路径时用配置地址补全
                boolean isCms = type == 1;
                if (isCms && !api.startsWith("http")) {
                    String full = completeRelative(api, cfgFull);
                    if (full.length() > 0) api = full;
                    else isCms = false;
                }
                if (!isCms && api.startsWith("//") && api.length() > 2) {
                    String full = completeRelative(api, cfgFull);
                    if (full.startsWith("http")) api = full;
                }
                // 未标 type（或 0/4）但 api 像 CMS 接口的，也按资源站收
                if (!isCms && type != 3 && api.startsWith("http")
                        && !api.contains("csp_") && !api.endsWith(".js") && !api.endsWith(".jar")
                        && CmsClient.looksLikeCms(api)) {
                    isCms = true;
                }
                // v2.25：type=0 是 XML 采集站（不是爬虫）。TVBox 生态里大量站点是
                // 这个类型，旧版会把「非 CMS 且有 api」的站点一律塞进 spider，
                // 于是变成一堆找不到 jar 的死源。这里按资源站收，CmsClient 负责
                // 兼容 XML 响应。
                if (!isCms && type != 3 && api.startsWith("http")
                        && !api.contains("csp_") && !api.endsWith(".js") && !api.endsWith(".jar")) {
                    isCms = true;
                }
                // v2.12：type=3 爬虫站（csp_ 类名 / .js / .jar 接口）统一收档展示，
                // jar 地址优先取站点内 jar 字段，其次配置顶层 spider
                boolean isSpider = !isCms && api.length() > 0
                        && (type == 3 || api.contains("csp_") || api.endsWith(".js") || api.endsWith(".jar"));
                if (isCms) {
                    try { api = Utils.normalizeApi(api); } catch (Throwable ignored) {}
                } else if (!isSpider) {
                    // type=1 但 api 既不是 http 也补不出相对地址 → 归爬虫（类名）。
                    // v2.25：只有「非 http」的接口才可能是类名/脚本名，http 地址
                    // 一律按资源站处理，避免制造大量无 jar 的假爬虫源。
                    isSpider = api.length() > 0 && !api.startsWith("http");
                    if (!isSpider) { skipped++; continue; }
                }
                if (isCms || isSpider) {
                    // 去重：同名同类型同接口的源只存一次（重复解析同一线路不堆积）
                    boolean exists = false;
                    for (int k = 0; k < arr.length(); k++) {
                        JSONObject ex = arr.optJSONObject(k);
                        if (ex != null && name.equals(ex.optString("name"))
                                && api.equals(ex.optString("api", ""))
                                && (isCms ? "cms" : "spider").equals(ex.optString("type"))) {
                            exists = true; break;
                        }
                    }
                    if (exists) continue;
                    JSONObject src = new JSONObject();
                    String sid = "s" + SRC_SEQ.incrementAndGet();
                    src.put("id", sid);
                    src.put("name", name).put("type", isCms ? "cms" : "spider")
                            .put("cat", "资源站").put("api", api)
                            .put("autoLine", true);   // v2.15：切线路时清除这批自动源
                    if (!isCms) {
                        String jar = s.optString("jar", "").trim();
                        if (jar.length() == 0) jar = o.optString("spider", "").trim();
                        // v2.25：FongMi 官方示例里 spider 就是 "spider.jar" 相对路径，
                        // 旧版只收 http/img+ → 每个爬虫源都没有 jar，全部解析失败。
                        jar = resolveCfgPath(jar, cfgFull);
                        if (jar.startsWith("http") || jar.startsWith("img+")) src.put("jar", jar);
                        // ★ v2.17：站点的 extend 参数必须随源保存。花卷的 site_urls、
                        // 玩偶/网盘类的 token、ucCookie、quarkCookie 都在 ext 里，
                        // 不传爬虫会返回空列表 → 「首页解析失败 / 很多播放失败」。
                        Object ex = s.opt("ext");
                        if (ex != null && !"null".equals(String.valueOf(ex))) {
                            String ev = (ex instanceof String) ? (String) ex : ex.toString();
                            // v2.17：EasyBox 的 img+ 形态，ext 本体是 base64
                            if (ev.startsWith("img+")) {
                                try {
                                    ev = new String(android.util.Base64.decode(
                                            ev.substring(4), android.util.Base64.DEFAULT), "UTF-8");
                                } catch (Throwable ignored) {}
                            }
                            // v2.25：ext 是 file://one.json / ./xx.json / 远程 URL 时，
                            // 要下载「内容」再传给 Spider.init，而不是传文件名。
                            String extUrl = resolveCfgPath(ev, cfgFull);
                            if (extUrl.startsWith("http")) src.put("extUrl", extUrl);
                            else if (ev.length() > 0) src.put("ext", ev);
                        }
                    }
                    try {
                        arr.put(src);
                        names.put(name);
                        // v2.15：爬虫源也要进候选（旧版只收 cms，坏掉的 CMS 站
                        // 被设为当前源后首页永远解析失败）
                        if (r.optJSONArray("newIds") != null) r.optJSONArray("newIds").put(sid);
                        if (isCms) added++; else spiderAdded++;
                    } catch (Throwable ignored) {}
                } else {
                    skipped++;   // 无接口地址的源（xml 等），暂不支持
                }
            }
            try { Config.setSources(arr.toString()); } catch (Throwable ignored) {}
        }
        // v2.14：直播源多形态容错。旧版只认 lives 为数组、url 必须已是 http，
        // 相对路径 / 单对象 / 顶层 liveUrl / sites 里的 m3u 站点全被丢掉 ——
        // 这就是「选线路后直播下拉一直空」的原因。
        int liveAdded = 0, liveSkipped = 0;
        try {
            java.util.List<String[]> found = new java.util.ArrayList<String[]>();
            collectLiveNodes(o, cfgUrl, found);
            JSONArray la = new JSONArray(Config.lives());
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            for (int k = 0; k < la.length(); k++) {
                JSONObject ex = la.optJSONObject(k);
                if (ex != null) seen.add(ex.optString("url", ""));
            }
            for (String[] it : found) {
                String u = it[1].trim();
                // 只接受可直接 GET 的 http(s) 列表；proxy:// / file:// / 内嵌文本
                // 由 TVBox 私有协议承载，本 App 的原生网络层取不到，明确计数跳过。
                // ★ v2.17：127.0.0.1:9978/proxy 这类是 TVBox 本机代理端口，
                // 在手机上指向自己，收进来只会得到一个永远加载不了的直播源。
                if (!u.startsWith("http") || u.contains("://127.0.0.1")
                        || u.contains("://localhost") || u.contains("://0.0.0.0")
                        || u.contains("://[::1]")) { liveSkipped++; continue; }
                if (!seen.add(u)) continue;   // 重复选同一线路不堆积
                        JSONObject li = new JSONObject()
                                .put("id", "l" + System.currentTimeMillis() + "_" + liveAdded)
                                .put("name", it[0].length() > 0 ? it[0] : "直播源")
                                .put("url", u)
                                .put("autoLine", true);   // v2.15：切线路时一并清除
                if (it[2].startsWith("http")) li.put("icon", it[2]);
                la.put(li);
                liveAdded++;
            }
            if (liveAdded > 0) Config.setLives(la.toString());
        } catch (Throwable ignored) {}
        try {
            r.put("added", r.optInt("added", 0) + added);
            r.put("skipped", r.optInt("skipped", 0) + skipped);
            r.put("spiderAdded", r.optInt("spiderAdded", 0) + spiderAdded);
            r.put("liveAdded", r.optInt("liveAdded", 0) + liveAdded);
            r.put("liveSkipped", r.optInt("liveSkipped", 0) + liveSkipped);
        } catch (Throwable ignored) {}
    }

    /** 从配置各处收集直播源候选 {name,url,icon}。 */
    private static void collectLiveNodes(JSONObject o, String cfgUrl,
                                         java.util.List<String[]> out) {
        if (o == null) return;
        // 1) lives：数组 / 单对象 / 单字符串都兼容
        addLiveNode(o.opt("lives"), cfgUrl, out);
        // 2) 有些线路把直播地址放在顶层 liveUrl / livesUrl / live
        String[] top = {"liveUrl", "livesUrl", "live"};
        for (int i = 0; i < top.length; i++) addLiveNode(o.opt(top[i]), cfgUrl, out);
        // 3) 少数配置把直播列表当站点写进 sites（type=0 且指向 m3u/txt）
        JSONArray sites = o.optJSONArray("sites");
        if (sites != null) {
            for (int i = 0; i < sites.length(); i++) {
                JSONObject s = sites.optJSONObject(i);
                if (s == null) continue;
                String u = firstNonEmpty(s.optString("url", ""), s.optString("api", ""));
                String low = u.toLowerCase();
                if (low.contains(".m3u") || low.contains(".txt")) {
                    JSONObject lv = new JSONObject();
                    try {
                        lv.put("name", firstNonEmpty(s.optString("name", ""), "直播"));
                        lv.put("url", u);
                    } catch (Throwable ignored) {}
                    addLiveNode(lv, cfgUrl, out);
                }
            }
        }
    }

    private static void addLiveNode(Object v, String cfgUrl, java.util.List<String[]> out) {
        if (v == null) return;
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) addLiveNode(a.opt(i), cfgUrl, out);
            return;
        }
        String name = "", url = "", ico = "";
        if (v instanceof JSONObject) {
            JSONObject lv = (JSONObject) v;
            name = firstNonEmpty(lv.optString("name", ""), lv.optString("title", ""), "直播源");
            url = firstNonEmpty(lv.optString("url", ""), lv.optString("api", ""),
                    lv.optString("link", ""), lv.optString("source", ""));
            ico = firstNonEmpty(lv.optString("icon", ""), lv.optString("logo", ""),
                    lv.optString("pic", ""));
        } else {
            String s = String.valueOf(v).trim();
            int p = s.indexOf(',');
            if (p > 0) { name = s.substring(0, p).trim(); url = s.substring(p + 1).trim(); }
            else if (s.startsWith("http") || s.startsWith("/") || s.startsWith("./")) url = s;
            else return;
        }
        if (url.length() == 0) return;
        out.add(new String[]{name, normalizeLiveUrl(url, cfgUrl), ico});
    }

    /** 相对直播地址基于配置文件自身 URL 补全；非 http 私有协议原样返回（后面会被过滤）。 */
    private static String normalizeLiveUrl(String u, String cfgUrl) {
        String a = u.trim();
        if (a.startsWith("http://") || a.startsWith("https://")) return a;
        if (a.contains("://")) return a;   // proxy://、file:// 等
        String full = completeRelative(a, cfgUrl == null ? "" : cfgUrl.trim());
        return full.length() > 0 ? full : a;
    }

    private static String firstNonEmpty(String... xs) {
        for (int i = 0; i < xs.length; i++)
            if (xs[i] != null && xs[i].trim().length() > 0) return xs[i].trim();
        return "";
    }

    private static String liveSkipHint(JSONObject r) {
        int n = r.optInt("liveSkipped", 0);
        if (n <= 0) return "";
        return "（另有 " + n + " 个直播地址为相对路径或私有协议，本机无法直接拉取；"
                + "可在直播页手动添加完整 http 的 m3u/txt 地址）";
    }

    /**
     * v2.15：切换线路时清掉上一条线路自动生成的资源站 / 爬虫源 / 直播源。
     * 用户在源管理里手动添加的源不带 autoLine 标记，不会被删。
     */
    private static void clearAutoSources() {
        try {
            JSONArray arr = new JSONArray(Config.sources());
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && isAutoSource(s)) continue;
                out.put(s);
            }
            Config.setSources(out.toString());
            JSONArray lv = new JSONArray(Config.lives());
            JSONArray lo = new JSONArray();
            for (int i = 0; i < lv.length(); i++) {
                JSONObject s = lv.optJSONObject(i);
                if (s != null && isAutoLive(s)) continue;
                lo.put(s);
            }
            Config.setLives(lo.toString());
        } catch (Throwable ignored) {}
    }

    /**
     * 是否线路自动生成的源。兼容 v2.14 及更早升级上来的旧数据：
     * 那时还没有 autoLine 标记，只能按「cms/spider + 资源站分类 + 非手动」识别。
     */
    private static boolean isAutoSource(JSONObject s) {
        if (s.optBoolean("autoLine", false)) return true;
        if (s.optBoolean("manual", false)) return false;
        String type = s.optString("type", "");
        return ("cms".equals(type) || "spider".equals(type))
                && "资源站".equals(s.optString("cat", ""));
    }

    /** 旧版直播源全部由线路导入；手动添加的会带 manual 标记。 */
    private static boolean isAutoLive(JSONObject s) {
        return s.optBoolean("autoLine", false) || !s.optBoolean("manual", false);
    }

    /**
     * v2.17：挑一个「探活通过」的源设为当前源。
     * 旧版 pass0 无条件优先 spider，而 z.qiqiv.cn 的线路几乎全是 type=3 爬虫源，
     * 且部分线路的 jar 带 ;md5; 后缀会返回 404 HTML → 连完线路首页立刻整页失败，
     * tryHealSource 只试 2 个候选且还是同线路坏源，自愈无效。
     * 现在：CMS 优先（不依赖 jar），spider 逐个验证 jar 能下载且类能加载，
     * 8 秒总预算、最多 4 个候选；全失败才退回第一个。
     */
    private String pickHealthySource(JSONArray ids) {
        if (ids == null || ids.length() == 0) return "";
        java.util.List<JSONObject> ordered = new java.util.ArrayList<JSONObject>();
        try {
            JSONArray arr = new JSONArray(Config.sources());
            for (int pass = 0; pass < 2; pass++) {
                String want = pass == 0 ? "cms" : "spider";
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.optString(i);
                    for (int k = 0; k < arr.length(); k++) {
                        JSONObject s = arr.optJSONObject(k);
                        if (s != null && id.equals(s.optString("id"))
                                && want.equals(s.optString("type"))) ordered.add(s);
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (ordered.isEmpty()) return ids.optString(0);
        long deadline = System.currentTimeMillis() + 8000;
        int checked = 0;
        for (JSONObject s : ordered) {
            if (checked >= 4 || System.currentTimeMillis() > deadline) break;
            checked++;
            String type = s.optString("type", "");
            if ("cms".equals(type)) {
                if (s.optString("api", "").startsWith("http")) return s.optString("id");
            } else if ("spider".equals(type)) {
                if (spiderUsable(s)) return s.optString("id");
            }
        }
        return ordered.get(0).optString("id");
    }

    /** spider 源可用性 = jar 能下载、类能实例化、homeContent 能出内容。带 8 秒超时。 */
    private boolean spiderUsable(final JSONObject s) {
        java.util.concurrent.Future<Boolean> f = PROBE.submit(
                new java.util.concurrent.Callable<Boolean>() {
                    @Override public Boolean call() {
                        try {
                            com.github.catvod.crawler.Spider sp =
                                    com.fengxi.fnplayer.spider.SpiderRuntime.get(
                                            act.getApplicationContext(), s.optString("jar", ""),
                                            s.optString("api", ""), s.optString("ext", ""));
                            // v2.18：不止验证类能加载——Guard 壳源（DexNative）
                            // newInstance 都能成功，一调方法才炸，必须真调一次 homeContent
                            String h = sp.homeContent(false);
                            return (h != null && h.length() > 20) ? Boolean.TRUE : Boolean.FALSE;
                        } catch (Throwable t) { return Boolean.FALSE; }
                    }
                });
        try { return Boolean.TRUE.equals(f.get(8, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (Throwable t) { f.cancel(true); return false; }
    }

    /**
     * v2.18：爬虫源报错的可读化。饭太硬/王二小/嗷呜这类 Guard 加密壳 jar
     * 抛 NoClassDefFoundError: com.github.catvod.spider.DexNative —— 壳的真实
     * 爬虫字节码要靠 jar 自带 so 解密运行，宿主没有专有环境，无法兼容。
     * 直接给结论，不再让用户对着 NoClassDefFoundError 猜。
     */
    static String spiderErrorHint(Throwable t) {
        String s = t == null ? "" : String.valueOf(t);
        if (t instanceof NoClassDefFoundError || t instanceof ExceptionInInitializerError
                || t instanceof UnsatisfiedLinkError || s.contains("DexNative")
                || s.contains("UnsatisfiedLink") || s.contains("Could not initialize class")) {
            return "该源是加密壳 jar（Guard 系），需要专有解密环境，本 App 暂不支持这类源，请换其它源或线路";
        }
        return Utils.brief(t == null ? new Exception("未知错误") : t);
    }

    /** v2.19：源/分类名是否带短剧特征（供短剧页优先挑选）。 */
    private static boolean isShortHinted(String s) {
        if (s == null || s.length() == 0) return false;
        String low = s.toLowerCase();
        return low.contains("短剧") || low.contains("剧场") || low.contains("红果")
                || low.contains("hongguo") || low.contains("duanju")
                || low.contains("短视") || low.contains("快手") || low.contains("kuaishou")
                || low.contains("抖音") || low.contains("douyin");
    }

    /**
     * GET 配置文本（v2.13 加强）：手动跟重定向；多 UA 依次重试（不少配置站按 UA 拦截）；
     * 用二进制拿响应后做编码嗅探（GBK 配置很常见，UTF-8 解出 U+FFFD 就换 GBK）。
     */
    /**
     * v2.17：TVBox 配置下载 + 解密链（对齐 EasyBox / FongMi 的 Decoder）。
     * 支持地址尾 {@code xxx;md5;key}（第三段是 AES/ECB 密钥）、
     * 8 字符标记 + ** 的 base64 密文、##（2423）hex 密文（AES/CBC）。
     * 返回文本再交给 extractJsonText（含「图片藏 base64」兜底）。
     */
    private String fetchConfigText(String rawUrl) throws Exception {
        String url = rawUrl == null ? "" : rawUrl.trim();
        String key = "";
        if (url.contains(";")) {
            String[] p = url.split(";");
            key = p.length > 2 ? p[2] : "";
            url = p[0];
        }
        String data = fetchTextFollow(url);
        if (data.length() == 0) return "";
        return TvboxDecoder.decode(url, key, data);
    }

    private String fetchTextFollow(String url0) {
        String url = idnUrl(url0);   // ★ v2.9：TVBox 线路常用中文域名，Java 不认，必须转 punycode
        // ★ v2.19：很多线路（肥猫/摸鱼儿/菜妮丝）对 okhttp UA 返回 JSON 配置，
        // 对浏览器 UA 返回 HTML 发布页 —— okhttp UA 必须放第一位
        String[] uas = {
                "okhttp/3.15",
                com.fengxi.fnplayer.core.Net.UA,
                "okhttp/4.9.3"
        };
        String best = "";
        for (int a = 0; a < uas.length; a++) {
            String got = fetchTextOnce(url, uas[a]);
            if (got.length() > 0) {
                // ★ v2.19：接受条件改为「真 JSON」或「## 加密配置」。
                // 旧条件只查 '{'，HTML 里也有 '{'，导致肥猫线永远拿到发布页
                if (looksLikeConfig(got)) return got;
                if (best.length() == 0) best = got;
            }
        }
        return best;
    }

    /** v2.19：像不像一份可用的配置 —— 能解析成 JSON，或形如 ## hex 加密配置。 */
    private static boolean looksLikeConfig(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 2) return false;
        // TvboxDecoder 的 2423/## hex 加密形态：整段长 hex
        if (t.length() > 400 && t.matches("[0-9a-fA-F]+")) return true;
        if (t.startsWith("2423")) return true;
        // 图片藏 base64 的 JPEG/PNG 数据没有明文 { —— 由 extractJsonText 的 base64 分支处理，
        // 这里只排除「明显是 HTML」
        String low = t.substring(0, Math.min(200, t.length())).toLowerCase();
        if (low.startsWith("<!doctype") || low.startsWith("<html") || low.startsWith("<?xml")) return false;
        try { new JSONObject(stripJsonComments(t)); return true; }
        catch (Throwable ignored) {}
        try { new JSONArray(stripJsonComments(t)); return true; }
        catch (Throwable ignored) {}
        // base64 大段（图片藏配置）：让后续解码分支处理
        return t.length() > 5000 && !low.contains("<div") && !low.contains("<script");
    }

    private String fetchTextOnce(String url, String ua) {
        Map<String, String> h = new java.util.LinkedHashMap<String, String>();
        h.put("User-Agent", ua);
        for (int i = 0; i < 3; i++) {
            com.fengxi.fnplayer.core.Net.Resp resp =
                    com.fengxi.fnplayer.core.Net.getBytes(url, h, true, 20000, 16 * 1024 * 1024);
            if (resp.error.length() > 0) return "";
            if (resp.code >= 300 && resp.code < 400 && resp.location.length() > 0) {
                url = idnUrl(resp.location.startsWith("http") ? resp.location
                        : baseUrlOf(url) + resp.location);
                continue;
            }
            if (resp.code != 200 || resp.bytes == null) return "";
            byte[] d = resp.bytes;
            // 编码嗅探：先 UTF-8；解出替换符（乱码）就按 GBK 再解一次
            String body = new String(d, java.nio.charset.StandardCharsets.UTF_8);
            if (body.indexOf('\uFFFD') >= 0) {
                try {
                    String gbk = new String(d, "GBK");
                    int badG = countChar(gbk, '\uFFFD');
                    if (badG < countChar(body, '\uFFFD')) body = gbk;
                } catch (Throwable ignored) {}
            }
            return body;
        }
        return "";
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    /**
     * v2.13：从下载到的文本里截出 JSON 本体 —— 不少配置页前面带广告/公告文本，
     * 或后面挂统计脚本，直接 JSONObject() 会解析失败。取第一个 { 到最后一个 }。
     */
    private static String extractJsonText(String text) {
        if (text == null) return "";
        String t = text.trim();
        String normal = sliceJson(t);
        if (looksLikeJson(normal)) return normal;
        // ★ v2.17：图片藏配置。饭太硬 /tv 实测返回 19951 字节 JPEG，
        // 真正的 TVBox JSON 以 17540 字符 base64 追加在图片尾部。
        // 旧版只找 { ，拿到一堆二进制乱码 → 线路解析永远卡住、直播列表为空。
        String hidden = decodeHiddenConfig(t);
        if (hidden.length() > 0) return sliceJson(hidden);
        return normal;
    }

    /**
     * v2.25：从第一个 { / [ 起做括号平衡扫描，取到与之匹配的结尾。
     * 旧版用 lastIndexOf('}')，配置尾部挂统计脚本 / 广告文本 / 第二个 JSON 时
     * 会把整段截进来 → JSON 解析失败（表现为「这个源根本解析不了」）。
     */
    private static String sliceJson(String t) {
        t = t.trim();
        int s = t.indexOf('{');
        int s2 = t.indexOf('[');
        if (s2 >= 0 && (s < 0 || s2 < s)) s = s2;
        if (s < 0) return t;
        char open = t.charAt(s), close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = s; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return t.substring(s, i + 1);
            }
        }
        int e = Math.max(t.lastIndexOf('}'), t.lastIndexOf(']'));
        return (e > s) ? t.substring(s, e + 1) : t;
    }

    private static boolean looksLikeJson(String s) {
        if (s == null || s.length() < 2) return false;
        try { new JSONObject(stripJsonComments(s)); return true; }
        catch (Throwable ignored) {}
        try { new JSONArray(stripJsonComments(s)); return true; }
        catch (Throwable ignored) {}
        return false;
    }

    /** 从二进制/文本里找出最长的 base64 段，解出含 { 的配置正文。 */
    private static String decodeHiddenConfig(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[A-Za-z0-9+/]{120,}={0,2}").matcher(t);
        String best = "";
        int bestSegLen = 0;
        while (m.find()) {
            String seg = m.group();
            if (seg.length() <= bestSegLen) continue;
            try {
                String pad = seg;
                while (pad.length() % 4 != 0) pad += "=";
                byte[] raw = android.util.Base64.decode(pad, android.util.Base64.DEFAULT);
                if (raw.length < 40) continue;
                String s = new String(raw, "UTF-8");
                if (s.indexOf('{') < 0 && s.indexOf('[') < 0) continue;
                best = s;
                bestSegLen = seg.length();
            } catch (Throwable ignored) {}
        }
        return best;
    }

    /**
     * v2.25：把配置里的 {@code file://one.json} / {@code ./spider.jar} /
     * {@code ../ext/xx.json} 这类相对资源补成绝对 URL。
     * 只处理「看起来像路径」的值，避免把 {@code csp_Douban} 这类爬虫类名当文件。
     */
    private static String resolveCfgPath(String v, String cfgUrl) {
        if (v == null) return "";
        String s = v.trim();
        if (s.length() == 0) return "";
        if (s.startsWith("img+") || s.startsWith("http://") || s.startsWith("https://")) return s;
        if (s.startsWith("file://")) s = s.substring(7);
        boolean pathLike = s.startsWith("./") || s.startsWith("../") || s.startsWith("/")
                || s.contains("/") || s.endsWith(".json") || s.endsWith(".jar")
                || s.endsWith(".js") || s.endsWith(".txt") || s.endsWith(".xml");
        if (!pathLike || cfgUrl == null || !cfgUrl.startsWith("http")) return s;
        String full = completeRelative(s, cfgUrl);
        return full.length() > 0 ? full : s;
    }

    /**
     * v2.13：把相对 api 补成绝对地址（基于配置文件自身的 URL）。
     * 支持 "//host/x"、"/abs/path"、"rel/path" 三种形态；base 缺失返回空串。
     */
    private static String completeRelative(String api, String base) {
        if (api == null) return "";
        String a = api.trim();
        if (a.startsWith("http://") || a.startsWith("https://")) return a;
        if (base == null || base.length() == 0) return "";
        if (a.startsWith("//")) {
            return (base.startsWith("https") ? "https:" : "http:") + a;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(https?://[^/]+)").matcher(base);
        if (!m.find()) return "";
        String root = m.group(1);
        if (a.startsWith("/")) return root + a;
        // 相对路径：基于配置 URL 去掉最后一段。先规整 ./ 与 ../，
        // 否则会拼出 /sub/./live.m3u 这类地址，部分服务器直接 404。
        while (a.startsWith("./")) a = a.substring(2);
        String dir = base;
        int q = dir.indexOf('?');
        if (q > 0) dir = dir.substring(0, q);
        int sl = dir.lastIndexOf('/');
        if (sl > root.length()) dir = dir.substring(0, sl + 1); else dir = root + "/";
        while (a.startsWith("../")) {
            a = a.substring(3);
            if (dir.length() > root.length() + 1) {
                String t = dir.substring(0, dir.length() - 1);
                int p = t.lastIndexOf('/');
                dir = (p > root.length()) ? t.substring(0, p + 1) : root + "/";
            }
        }
        return dir + a;
    }

    /** 把 URL 里的非 ASCII 域名转成 IDN（punycode），路径中文做 URL 编码；解析失败原样返回。 */
    private static String idnUrl(String url) {
        if (url == null) return url;
        try {
            // ★ 不用 URI/URL 解析：java.net.URI 对中文域名 getHost() 返回 null、
            //   new URL 能建但 InetAddress 解析失败 —— 都要在这里手工拆转换
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(https?://)([^/?#]+)(.*)$").matcher(url);
            if (!m.find()) return url;
            String auth = m.group(2), host = auth, port = "";
            int p = auth.indexOf(':');
            if (p >= 0) { host = auth.substring(0, p); port = auth.substring(p); }
            if (host.matches("[\\x00-\\x7F]+")) return url;   // 纯 ASCII 域名原样
            String ascii = java.net.IDN.toASCII(host);
            // 路径里的中文逐段 URL 编码（保留 /）
            String path = m.group(3);
            if (path.matches(".*[^\\x00-\\x7F].*")) {
                StringBuilder pb = new StringBuilder();
                for (String seg : path.split("/")) {
                    if (seg.isEmpty()) continue;   // 空段（首斜杠）不输出
                    if (pb.length() > 0 || path.startsWith("/")) pb.append('/');
                    if (seg.matches(".*[^\\x00-\\x7F].*")) {
                        try { pb.append(java.net.URLEncoder.encode(seg, "UTF-8")); }
                        catch (Throwable e2) { pb.append(seg); }
                    } else pb.append(seg);
                }
                path = pb.toString();
            }
            return m.group(1) + ascii + port + path;
        } catch (Throwable t) { return url; }
    }

    private static String baseUrlOf(String url) {
        try {
            java.net.URI u = new java.net.URI(url);
            return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Throwable t) { return ""; }
    }

    /** TVBox json 预处理：去掉 // 与 块注释、去尾逗号（均不破坏字符串字面量）。 */
    private static String stripJsonComments(String s) {
        if (s.startsWith("﻿")) s = s.substring(1);
        StringBuilder out = new StringBuilder(s.length());
        boolean inStr = false, esc = false;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (inStr) {
                out.append(c);
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; out.append(c); continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
                out.append('\n');
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i++;
                continue;
            }
            if ((c == '}' || c == ']')) {
                // 回删尾逗号（含逗号后的空白）
                int j = out.length() - 1;
                while (j >= 0 && Character.isWhitespace(out.charAt(j))) j--;
                if (j >= 0 && out.charAt(j) == ',') out.setLength(j);
            }
            out.append(c);
        }
        return out.toString();
    }

    // ==================== 本地文件导入（v2.5：系统文件管理器选源文件） ====================

    /** 弹系统文件选择器（MainActivity.onActivityResult 回传给 handleImportBytes）。 */
    @JavascriptInterface
    public void importPick() {
        act.runOnUiThread(new Runnable() {
            @Override public void run() { act.pickImportFile(); }
        });
    }

    /** 文件选择器选中的源文件内容（zip / js / tvbox json 自动识别）。 */
    public void handleImportBytes(final byte[] data, final String fileName) {
        String name = fileName == null ? "" : fileName.replaceAll("\\.[a-zA-Z]+$", "");
        JSONObject r = new JSONObject();
        try {
            String script = "", cat = "短剧源";
            boolean isTvbox = false;
            if (data.length > 2 && data[0] == 'P' && data[1] == 'K') {
                JSONObject m = unzipSource(data);
                script = m.optString("script", "");
                if (name.length() == 0) name = m.optString("name", "");
                if (m.optString("group", "").contains("短剧")) cat = "短剧源";
            } else {
                String text = new String(data, "UTF-8").trim();
                if (text.indexOf("registerSource") >= 0) {
                    script = text;
                } else if (text.startsWith("{")) {
                    isTvbox = true;
                } else {
                    r.put("ok", false).put("error", "不认识的文件内容（需要 JS 源脚本 / 源包 / TVBox 配置）");
                    js("window.onImportDone && window.onImportDone(" + r + ")");
                    return;
                }
            }
            if (isTvbox) {
                parseTvboxAsync(new String(data, "UTF-8"));   // 结果走 window.onTvboxDone
                return;
            }
            if (script.indexOf("registerSource") < 0) {
                r.put("ok", false).put("error", "文件里没有找到 registerSource 源脚本");
                js("window.onImportDone && window.onImportDone(" + r + ")");
                return;
            }
            JSONObject src = new JSONObject();
            String id = "s" + System.currentTimeMillis();
            src.put("id", id).put("name", name.length() > 0 ? name : "导入源")
                    .put("type", "js").put("cat", cat).put("script", script);
            JSONArray arr = new JSONArray(Config.sources());
            arr.put(src);
            Config.setSources(arr.toString());
            r.put("ok", true).put("name", src.optString("name")).put("cat", cat);
        } catch (Throwable t) {
            try { r.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
        }
        js("window.onImportDone && window.onImportDone(" + r + ")");
    }

    // ==================== 直播源（v2.3） ====================

    @JavascriptInterface
    public String listLives() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("list", new JSONArray(Config.lives()));
        } catch (Throwable t) {
            try { o.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
        }
        return o.toString();
    }

    @JavascriptInterface
    public String addLive(String name, String url) {
        JSONObject r = new JSONObject();
        try {
            if (url == null || url.trim().length() == 0) {
                r.put("ok", false).put("error", "请填直播源地址");
                return r.toString();
            }
            JSONArray arr = new JSONArray(Config.lives());
            arr.put(new JSONObject().put("id", "l" + System.currentTimeMillis())
                    .put("name", name == null || name.trim().length() == 0 ? "直播源" : name.trim())
                    .put("url", url.trim()).put("manual", true));
            Config.setLives(arr.toString());
            r.put("ok", true);
        } catch (Throwable t) {
            try { r.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
        }
        return r.toString();
    }

    @JavascriptInterface
    public String delLive(String id) {
        JSONObject r = new JSONObject();
        try {
            JSONArray arr = new JSONArray(Config.lives());
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && !id.equals(s.optString("id"))) out.put(s);
            }
            Config.setLives(out.toString());
            r.put("ok", true);
        } catch (Throwable t) {
            try { r.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
        }
        return r.toString();
    }

    /** 原生拉取 m3u 文本（绕开 WebView 跨源限制），返回 {ok, text}。 */
    // v2.23：降为 Java 内部方法，前端已全走 loadLiveAsync；不再暴露同步网络面。
    public String fetchLive(String url) {
        JSONObject r = new JSONObject();
        try {
            Map<String, String> h = new java.util.LinkedHashMap<String, String>();
            h.put("User-Agent", com.fengxi.fnplayer.core.Net.UA);
            com.fengxi.fnplayer.core.Net.Resp resp =
                    com.fengxi.fnplayer.core.Net.get(url.trim(), h, true, 15000);
            if (resp.error.length() > 0 || resp.code >= 400) {
                r.put("ok", false).put("error", resp.error.length() > 0 ? resp.error
                        : "HTTP " + resp.code);
            } else {
                r.put("ok", true).put("text", resp.body);
            }
        } catch (Throwable t) {
            try { r.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
        }
        return r.toString();
    }

    /**
     * 异步拉取直播源文本（v2.6：原来 fetchLive 是同步桥调用，大文件 / 慢源会把
     * WebView 桥队列堵死造成「卡死」）。结果回调 window.onLiveText(ok, json)。
     */
    private static final class LiveFetch {
        String text = "";
        String error = "";
    }

    /** v2.15：直播源专用拉取。多 UA + Referer、手动跟跳转、带详细失败原因。 */
    private LiveFetch fetchLiveText(String url0) {
        LiveFetch out = new LiveFetch();
        String url = idnUrl(url0 == null ? "" : url0.trim());
        if (!url.startsWith("http")) {
            out.error = "直播源地址不是 http(s)：" + url;
            return out;
        }
        String[] uas = {
                com.fengxi.fnplayer.core.Net.UA,
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        };
        String last = "";
        for (int a = 0; a < uas.length; a++) {
            Map<String, String> h = new java.util.LinkedHashMap<String, String>();
            h.put("User-Agent", uas[a]);
            h.put("Accept", "*/*");
            h.put("Referer", baseUrlOf(url) + "/");
            String cur = url;
            for (int hop = 0; hop < 4; hop++) {
                com.fengxi.fnplayer.core.Net.Resp resp =
                        com.fengxi.fnplayer.core.Net.getBytes(cur, h, true, 12000,
                                16 * 1024 * 1024);
                if (resp.error.length() > 0) {
                    last = "网络错误：" + resp.error;
                    break;
                }
                if (resp.code >= 300 && resp.code < 400 && resp.location.length() > 0) {
                    cur = idnUrl(resp.location.startsWith("http") ? resp.location
                            : baseUrlOf(cur) + resp.location);
                    last = "跳转后仍无内容：" + cur;
                    continue;
                }
                if (resp.code < 200 || resp.code >= 300) {
                    last = "HTTP " + resp.code;
                    // 4xx/5xx 换 UA 通常也没用，但保留一次机会
                    break;
                }
                if (resp.bytes == null || resp.bytes.length == 0) {
                    last = "HTTP 200 但内容为空";
                    break;
                }
                String body = new String(resp.bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (body.indexOf('\uFFFD') >= 0) {
                    try {
                        String gbk = new String(resp.bytes, "GBK");
                        if (countChar(gbk, '\uFFFD') < countChar(body, '\uFFFD')) body = gbk;
                    } catch (Throwable ignored) {}
                }
                String low = body.trim().toLowerCase();
                if (low.startsWith("<!doctype") || low.startsWith("<html")) {
                    last = "地址返回的是网页，不是 m3u/txt 列表";
                    break;
                }
                out.text = body;
                return out;
            }
        }
        out.error = "下载直播源失败：" + (last.length() > 0 ? last : "无响应")
                + "\n地址 " + url;
        return out;
    }

    @JavascriptInterface
    public void loadLiveAsync(final String url) {
        io.execute(new Runnable() {
            @Override public void run() {
                JSONObject r = new JSONObject();
                try {
                    String cur = url == null ? "" : url.trim();
                    LiveFetch f = fetchLiveText(cur);
                    // v2.15：这个源拉不到就自动试列表里其它源，不用手动一个个点
                    if (f.text.length() == 0) {
                        String firstErr = f.error;
                        JSONArray arr = new JSONArray(Config.lives());
                        for (int i = 0; i < arr.length() && f.text.length() == 0; i++) {
                            JSONObject s = arr.optJSONObject(i);
                            if (s == null) continue;
                            String u = s.optString("url", "");
                            if (u.equals(cur) || !u.startsWith("http")) continue;
                            LiveFetch alt = fetchLiveText(u);
                            if (alt.text.length() > 0) {
                                f = alt;
                                r.put("srcId", s.optString("id", ""));
                                r.put("srcName", s.optString("name", ""));
                            }
                        }
                        if (f.text.length() == 0) f.error = firstErr;
                    }
                    if (f.text.length() == 0) r.put("ok", false).put("error", f.error);
                    else r.put("ok", true).put("text", f.text);
                } catch (Throwable t) {
                    try { r.put("ok", false).put("error", Utils.brief(t)); } catch (Throwable ignored) {}
                }
                js("window.onLiveText && window.onLiveText(" + r + ")");
            }
        });
    }

    // ==================== 直链播放 / 短剧 ====================

    /**
     * 直接播放一个 URL（直播频道、cms 直链集）。
     * ★ v2.8 修复：必须用 "direct:" 前缀放进 guid 里传 —— VideoActivity 只读
     * "guid" extra，之前单独传 "direct" extra 根本没被读，直播点播放一直
     * 报「缺少影片 ID」直接退出。live=true 时播放器走直播低延迟选项。
     */
    @JavascriptInterface
    public void playDirect(final String url, final String title) {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                Intent i = new Intent(act, VideoActivity.class);
                i.putExtra("guid", "direct:" + (url == null ? "" : url));
                i.putExtra("title", title == null ? "播放" : title);
                i.putExtra("live", true);
                act.startActivity(i);
            }
        });
    }

    /** 打开短剧竖滑播放页（guid 为一部剧，集列表在其内部切换）。 */
    @JavascriptInterface
    public void openShort(final String guid, final String title) {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                Intent i = new Intent(act, ShortActivity.class);
                i.putExtra("guid", guid == null ? "" : guid);
                i.putExtra("title", title == null ? "" : title);
                act.startActivity(i);
            }
        });
    }

    /**
     * 短剧列表（v2.4）：优先用已导入的「短剧源」（JS 脚本源，自动接入），
     * 没有再回退到当前 NAS/资源站里按名称筛「短剧」。tabUrl 为 JS 源的榜单地址。
     */
    @JavascriptInterface
    public void loadShortList() { loadShortList(""); }

    @JavascriptInterface
    public void loadShortList(final String tabUrl) { loadShortPage(tabUrl, 1); }

    /**
     * 短剧列表分页（v2.9）：page>1 且 tabUrl 是 http 地址时自动拼 ?page=/&page=，
     * 回调 window.onShortPage(json)（首页仍走 onShortList）。totalPage 由 JS 源返回。
     */
    @JavascriptInterface
    public void loadShortPage(final String tabUrl, final int page) {
        io.execute(new Runnable() {
            @Override public void run() {
                FnClient.Result r = new FnClient.Result();
                try {
                    JSONObject js = findShortSource();
                    if (js == null) {
                        // v2.14：当前线路是 TVBox 爬虫源时，短剧页也用它。
                        // 旧版只找 js / 含「短剧」的 cms 源，导入线路后短剧栏必然空白。
                        if (spiderShortPage(tabUrl, page)) return;
                        loadShortListFallback(r, page);
                        js("window.onShortList && window.onShortList(" + resultJson(r, false) + ")");
                        return;
                    }
                    String sid = js.optString("id");
                    String script = js.optString("script", "");
                    // 榜单 tabs（categoryMenus → 第一组的 tabs 扁平化）
                    JSONArray groups = JsSource.categoryMenus(sid, script);
                    JSONArray tabs = new JSONArray();
                    for (int g = 0; g < groups.length() && tabs.length() == 0; g++) {
                        JSONObject grp = groups.optJSONObject(g);
                        JSONArray t = grp == null ? null : grp.optJSONArray("tabs");
                        if (t != null) tabs = t;
                    }
                    String url = tabUrl == null ? "" : tabUrl.trim();
                    if (url.length() == 0 && tabs.length() > 0)
                        url = tabs.optJSONObject(0).optString("url", "");
                    if (page > 1 && url.startsWith("http"))
                        url = url + (url.contains("?") ? "&" : "?") + "page=" + page;
                    JSONObject pg = url.length() > 0
                            ? JsSource.categoryPage(sid, script, url) : new JSONObject();
                    JSONArray books = pg.optJSONArray("books");
                    JSONArray list = new JSONArray();
                    if (books != null) {
                        for (int i = 0; i < books.length(); i++) {
                            JSONObject b = books.optJSONObject(i);
                            if (b == null) continue;
                            JSONObject card = new JSONObject();
                            card.put("guid", "jssrc|" + sid + "|" + b.optString("bookUrl", ""));
                            card.put("title", b.optString("title", "未命名短剧"));
                            card.put("poster", b.optString("coverUrl", ""));
                            String sub = b.optString("status", "");
                            if (sub.length() == 0) sub = b.optString("intro", "");
                            card.put("sub", sub);
                            list.put(card);
                        }
                    }
                    int totalPage = Math.max(1, pg.optInt("totalPage", 1));
                    final JSONArray tabsF = tabs;
                    final JSONArray listF = list;
                    String cb = page > 1 ? "window.onShortPage" : "window.onShortList";
                    String errF = list.length() == 0 ? (page > 1 ? "没有更多了" : "该榜单暂时没有内容") : "";
                    js(cb + " && " + cb + "({ok:true,error:" + q(errF)
                            + ",js:true,tabs:" + tabsF.toString()
                            + ",list:" + listF.toString()
                            + ",tabUrl:" + q(tabUrl == null ? "" : tabUrl)
                            + ",page:" + page + ",totalPage:" + totalPage + "})");
                    return;
                } catch (Throwable t) {
                    r.ok = false;
                    r.error = Utils.brief(t);
                    if (page > 1) {
                        js("window.onShortPage && window.onShortPage({ok:false,error:"
                                + q(r.error) + ",page:" + page + "})");
                        return;
                    }
                }
                final FnClient.Result fr = r;
                js("window.onShortList && window.onShortList(" + resultJson(fr, false) + ")");
            }
        });
    }

    /** 非 JS 短剧源（cms / NAS）时的首页列表（不分页）。 */
    private void loadShortListFallback(FnClient.Result r, int page) throws Exception {
        // v2.12：名称含「短剧」的 cms 源自动对接短剧页（不要求是当前源），支持分页
        CmsClient shortCms = findShortCmsSource();
        if (shortCms != null) {
            FnClient.Result cats = shortCms.categories();
            String tid = "";
            if (cats.ok && cats.list != null) {
                for (int i = 0; i < cats.list.length(); i++) {
                    JSONObject t = cats.list.optJSONObject(i);
                    if (t != null && t.optString("type_name", "").contains("短剧")) {
                        tid = t.optString("type_id", "");
                        break;
                    }
                }
            }
            if (tid.length() > 0) { FnClient.Result rr = shortCms.items(tid, page); r.ok = rr.ok; r.error = rr.error; r.list = rr.list; return; }
            r.ok = false; r.error = "该源没有「短剧」分类";
            return;
        }
        if ("cms".equals(curSourceType())) {
            CmsClient c = cms();
            FnClient.Result cats = c.categories();
            String tid = "";
            if (cats.ok && cats.list != null) {
                for (int i = 0; i < cats.list.length(); i++) {
                    JSONObject t = cats.list.optJSONObject(i);
                    if (t != null && t.optString("type_name", "").contains("短剧")) {
                        tid = t.optString("type_id", "");
                        break;
                    }
                }
            }
            if (tid.length() > 0) { FnClient.Result rr = c.items(tid, page); r.ok = rr.ok; r.error = rr.error; r.list = rr.list; return; }
            r.ok = false; r.error = "该源没有「短剧」分类，可到设置→源管理导入短剧源";
        } else {
            FnClient fc = FnClient.shared();
            FnClient.Result libs = fc.libraries();
            String gid = "";
            if (libs.ok && libs.list != null) {
                for (int i = 0; i < libs.list.length(); i++) {
                    JSONObject l = libs.list.optJSONObject(i);
                    if (l != null && l.optString("title", "").contains("短剧")) {
                        gid = l.optString("guid", "");
                        break;
                    }
                }
            }
            if (gid.length() > 0) r = fc.items(gid, 1);
            else { r.ok = false; r.error = "NAS 里没有名称带「短剧」的媒体库，可到设置→源管理导入短剧源"; }
        }
    }

    /**
     * v2.14：短剧页复用当前 TVBox 爬虫源。homeContent 的 class[] 当榜单 tab，
     * categoryContent(tid,pg,...) 取卡片。点卡片走 openVideo（复用已有 spider
     * 详情/选集/播放链路），不进 ShortActivity 的竖滑页。
     * 返回 false = 没有可用 spider 源，交回原 fallback。
     */
    private boolean spiderShortPage(String tabUrl, int page) {
        // v2.18：逐个试 spider 候选源。Guard 加密壳 jar（饭太硬/王二小/嗷呜）
        // 类加载都成功，一调 homeContent 就抛 NoClassDefFoundError: DexNative，
        // 必须真调一次并跳过，换下一个可用源，短剧页不再整页报错。
        // v2.19：候选源按短剧特征排序（key/名/api 含 短剧/剧场/红果 等），
        // 且优先选「分类里真有短剧」的源——旧版拿第一个 spider 源的第一个
        // 分类（电影/连续剧），内容自然和首页一模一样。
        java.util.List<JSONObject> candidates = new java.util.ArrayList<JSONObject>();
        java.util.List<JSONObject> hinted = new java.util.ArrayList<JSONObject>();
        JSONObject curSrc = currentSourceObj();
        if (curSrc != null && "spider".equals(curSrc.optString("type"))) candidates.add(curSrc);
        try {
            JSONArray arr = new JSONArray(Config.sources());
            for (int i = 0; i < arr.length() && candidates.size() < 8; i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null || !"spider".equals(s.optString("type"))) continue;
                boolean dup = false;
                for (JSONObject c : candidates)
                    if (c.optString("id").equals(s.optString("id"))) { dup = true; break; }
                if (dup) continue;
                candidates.add(s);
            }
        } catch (Throwable ignored) {}
        if (candidates.isEmpty()) return false;
        for (JSONObject c : candidates)
            if (isShortHinted(c.optString("key") + " " + c.optString("name")
                    + " " + c.optString("api") + " " + c.optString("jar", ""))) hinted.add(c);
        java.util.List<JSONObject> ordered = new java.util.ArrayList<JSONObject>();
        ordered.addAll(hinted);
        for (JSONObject c : candidates) if (!ordered.contains(c)) ordered.add(c);
        com.github.catvod.crawler.Spider sp = null;
        com.github.catvod.crawler.Spider spAny = null;
        JSONObject spSrc = null;
        JSONObject spSrcAny = null;
        JSONObject home0 = null;
        JSONObject homeAny = null;
        String lastErr = "";
        for (JSONObject cand : ordered) {
            try {
                com.github.catvod.crawler.Spider s0 =
                        com.fengxi.fnplayer.spider.SpiderRuntime.get(
                                act, cand.optString("jar", ""), cand.optString("api", ""),
                                cand.optString("ext", ""));
                JSONObject home = new JSONObject(s0.homeContent(false));
                JSONArray cls = home.optJSONArray("class");
                if (cls == null || cls.length() == 0) { lastErr = "homeContent 没有返回分类"; continue; }
                if (spAny == null) {
                    spAny = s0; spSrcAny = cand; homeAny = home;
                    if (hinted.contains(cand)) { sp = s0; spSrc = cand; home0 = home; break; }
                }
                // 分类名里带短剧字样的源是最佳候选
                boolean shortCls = false;
                for (int i = 0; i < cls.length(); i++) {
                    JSONObject c = cls.optJSONObject(i);
                    if (c != null && isShortHinted(c.optString("type_name", ""))) { shortCls = true; break; }
                }
                if (shortCls) { sp = s0; spSrc = cand; home0 = home; break; }
            } catch (Throwable t) {
                lastErr = spiderErrorHint(t);
            }
        }
        if (sp == null) { sp = spAny; spSrc = spSrcAny; home0 = homeAny; }
        if (sp == null) {
            String e = "短剧源加载失败：" + lastErr
                    + "；可到「设置 → 源管理 → 导入源」导入 JS 短剧源（zip 源包或脚本），或换一条线路";
            if (page > 1) js("window.onShortPage && window.onShortPage({ok:false,error:"
                    + q(e) + ",page:" + page + "})");
            else js("window.onShortList && window.onShortList({ok:false,error:"
                    + q(e) + ",tabUrl:''})");
            return true;
        }
        final String spId = spSrc.optString("id", "");
        try {
            JSONObject home = home0 != null ? home0 : new JSONObject(sp.homeContent(false));
            JSONArray cls = home.optJSONArray("class");
            JSONArray tabs = new JSONArray();
            String shortTid = "";
            if (cls != null) {
                for (int i = 0; i < cls.length(); i++) {
                    JSONObject c = cls.optJSONObject(i);
                    if (c == null) continue;
                    String tid = c.optString("type_id", "");
                    String nm = c.optString("type_name", "");
                    if (tid.length() == 0) continue;
                    tabs.put(new JSONObject().put("title", nm).put("url", tid));
                    if (shortTid.length() == 0 && isShortHinted(nm)) shortTid = tid;
                }
            }
            String tid = tabUrl == null ? "" : tabUrl.trim();
            if (tid.length() == 0) tid = shortTid;
            // 该线路没有「短剧」类分类时退而用第一个分类，避免整页空白
            if (tid.length() == 0 && tabs.length() > 0)
                tid = tabs.optJSONObject(0).optString("url", "");
            JSONArray list = new JSONArray();
            int totalPage = 1;
            if (tid.length() > 0) {
                JSONObject pg = new JSONObject(sp.categoryContent(
                        tid, String.valueOf(page <= 0 ? 1 : page), false,
                        new java.util.HashMap<String, String>()));
                JSONArray vods = pg.optJSONArray("list");
                if (vods != null) {
                    for (int i = 0; i < vods.length(); i++) {
                        JSONObject v = vods.optJSONObject(i);
                        if (v == null) continue;
                        list.put(new JSONObject()
                                .put("guid", "spid|" + spId + "|" + v.optString("vod_id", ""))
                                .put("title", v.optString("vod_name", "未命名"))
                                .put("poster", v.optString("vod_pic", ""))
                                .put("sub", v.optString("vod_remarks", ""))
                                .put("kind", "spider"));
                    }
                }
                totalPage = Math.max(1, pg.optInt("pagecount", pg.optInt("page", 1)));
            }
            String cb = page > 1 ? "window.onShortPage" : "window.onShortList";
            js(cb + " && " + cb + "({ok:true,error:" + q("")
                    + ",js:true,tabs:" + tabs.toString()
                    + ",list:" + list.toString()
                    + ",tabUrl:" + q(tid) + ",page:" + page
                    + ",totalPage:" + totalPage + "})");
        } catch (Throwable t) {
            String e = "短剧源加载失败：" + Utils.brief(t);
            if (page > 1) {
                js("window.onShortPage && window.onShortPage({ok:false,error:"
                        + q(e) + ",page:" + page + "})");
            } else {
                js("window.onShortList && window.onShortList({ok:false,error:"
                        + q(e) + ",tabUrl:''})");
            }
        }
        return true;
    }

    /** 找第一个「短剧源」（JS 脚本源）。 */
    private JSONObject findShortSource() {
        try {
            JSONArray arr = new JSONArray(Config.sources());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && "js".equals(s.optString("type"))) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** v2.12：找第一个名称含「短剧」的 CMS 资源站（TVBox 接口自动对接短剧页）。 */
    private CmsClient findShortCmsSource() {
        try {
            JSONArray arr = new JSONArray(Config.sources());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && "cms".equals(s.optString("type"))
                        && s.optString("name", "").contains("短剧")
                        && s.optString("api", "").startsWith("http")) {
                    return new CmsClient(s.optString("api"), s.optBoolean("trustSsl", false));
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 兼容旧调用（详情页按钮只传两个参数）：WebView 按「方法名+参数个数」匹配，个数不对会静默失败。 */
    @JavascriptInterface
    public void openVideo(String guid, String title) {
        openVideo(guid, title, guid);
    }

    @JavascriptInterface
    public void openVideo(final String guid, final String title, final String item) {
        final String g = guid == null ? "" : guid;
        // 播放器已经认识的直链 / 解析标识，直接开播
        if (g.startsWith("direct:") || g.startsWith("spp:") || g.startsWith("jssrc|")) {
            launchVideo(g, title, item == null || item.length() == 0 ? g : item);
            return;
        }
        // v2.15：CMS / 爬虫源的影片 id 不能直接丢给飞牛取链。
        // 先解出第一集的 direct:/spp: 再进播放页，item 保留原始 id 供选集列表用。
        // 用独立线程，避免排在首页自愈等慢请求后面让用户干等。
        new Thread(new Runnable() {
            @Override public void run() {
                String real = g;
                boolean nonNas = isNonNasSource();
                JSONArray eps = episodesFor(act.getApplicationContext(), g);
                if (eps != null && eps.length() > 0) {
                    JSONObject first = eps.optJSONObject(0);
                    String fg = first == null ? "" : first.optString("guid", "");
                    if (fg.startsWith("direct:") || fg.startsWith("spp:")) real = fg;
                }
                // v2.16：非 NAS 源又没解出直链时，绝不把原始 id（msearch: / csp_...）
                // 丢进飞牛 FnClient —— 那会拼出 "no protocol" 或 code=1 数据列表。
                // 传 nosrc: 前缀，播放器给出可读的「请换线路」提示。
                if (real.equals(g) && nonNas) real = "nosrc:" + g;
                launchVideo(real, title, g);
            }
        }, "fx-openvideo").start();
    }

    /** 当前源是否非 NAS（cms/spider/js）。供 openVideo / 播放器兜底判断。 */
    private static boolean isNonNasSource() {
        JSONObject s = currentSourceObj();
        if (s == null) return false;
        String t = s.optString("type", "nas");
        return "cms".equals(t) || "spider".equals(t) || "js".equals(t);
    }

    private void launchVideo(final String guid, final String title, final String item) {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                Intent i = new Intent(act, VideoActivity.class);
                i.putExtra("guid", guid);
                i.putExtra("title", title == null ? "" : title);
                if (item != null && item.length() > 0) i.putExtra("item", item);
                act.startActivity(i);
            }
        });
    }

    @JavascriptInterface
    public void toast(final String s) {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** v2.25：真机闪退后，用户可在「我的 → 崩溃日志」里复制堆栈发回来定位。 */
    @JavascriptInterface
    public String getCrashLog() {
        try {
            String s = com.fengxi.fnplayer.core.CrashGuard.read(act.getApplicationContext());
            if (s.length() > 32000) s = s.substring(s.length() - 32000);
            return new JSONObject().put("ok", true).put("text", s).toString();
        } catch (Throwable t) { return "{\"ok\":false,\"text\":\"\"}"; }
    }

    @JavascriptInterface
    public void clearCrashLog() {
        try { com.fengxi.fnplayer.core.CrashGuard.clear(act.getApplicationContext()); }
        catch (Throwable ignored) {}
    }

    @JavascriptInterface
    public void copyText(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("log", s));
            toast("已复制");
        } catch (Throwable ignored) {}
    }

    @JavascriptInterface
    public void exitApp() { act.finish(); }

    // ==================== v2.22 悬浮小窗 ====================

    /** 是否已授予悬浮窗权限（前端引导页判断用）。 */
    @JavascriptInterface
    public boolean canDrawOverlays() {
        try { return android.provider.Settings.canDrawOverlays(act); }
        catch (Throwable t) { return false; }
    }

    /** 跳系统悬浮窗授权页。 */
    @JavascriptInterface
    public void openOverlaySettings() {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    act.startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + act.getPackageName())));
                } catch (Throwable ignored) {}
            }
        });
    }

    /**
     * 从前端直接把当前播放缩小为悬浮小窗。
     * 实现：启动对应播放 Activity（autoFloat=1），Activity 起播后自动 goFloat。
     * 未授权时回 window.onFloatPerm 让前端弹引导。
     */
    @JavascriptInterface
    public void openFloat(final String guid, final String title) {
        if (!canDrawOverlays()) {
            js("window.onFloatPerm && window.onFloatPerm({ok:false,error:'需要悬浮窗权限'})");
            return;
        }
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                boolean isShort = guid != null && !guid.startsWith("direct:")
                        && (guid.startsWith("jssrc|") || guid.startsWith("spid|"));
                Intent i = new Intent(act, isShort ? ShortActivity.class : VideoActivity.class);
                i.putExtra("guid", guid == null ? "" : guid);
                i.putExtra("title", title == null ? "" : title);
                if (!isShort && guid != null && guid.length() > 0) i.putExtra("item", guid);
                i.putExtra("autoFloat", "1");
                act.startActivity(i);
            }
        });
    }

    /** 关闭悬浮小窗。 */
    @JavascriptInterface
    public void closeFloat() {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                act.stopService(new Intent(act, FloatVideoService.class));
            }
        });
    }

    /** 悬浮小窗是否显示中。 */
    @JavascriptInterface
    public boolean isFloatShowing() {
        return com.fengxi.fnplayer.player.PlaySession.active();
    }
}
