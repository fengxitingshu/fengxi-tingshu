package com.fengxi.fnplayer.fnos;

import com.fengxi.fnplayer.core.Net;
import com.fengxi.fnplayer.core.Reach;
import com.fengxi.fnplayer.core.Utils;
import com.fengxi.fnplayer.data.Config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞牛影视 API 客户端。
 *
 * <p><b>路径兼容</b>：官方影视 Web 端挂在 {@code /v/}（{@code /v/api/v1/...}），
 * 部分第三方/自建客户端用 {@code /api/v1/...}。列表类接口会按候选顺序尝试，
 * 首个返回 code=0 的即为可用路径，结果缓存进内存（同一进程内不重复探测）。
 *
 * <p><b>鉴权兼容</b>：同时带 {@code Authorization}、完整 Cookie、
 * {@code authorization=<token>}、{@code Trim-MC-token=<token>}、
 * {@code lastLoginUsername=<user>}，覆盖网页登录抓 Cookie / 粘贴 Token 两种来源。
 */
public final class FnClient {

    public interface StepListener {
        void onStep(int index, String name, boolean ok, String msg, long ms);
    }

    public static final class Result {
        public boolean ok;
        public int code = Integer.MIN_VALUE;
        public String raw = "";
        public JSONObject data;      // 可能是对象或数组包装
        public JSONArray list;
        public String error = "";
        public String usedPath = "";
        public String trail = "";    // 诊断用：候选路径逐一尝试的结果矩阵
        public long ms;
    }

    private final String base;
    private final boolean trustSsl;
    private String token;
    private final String cookie;
    private final String user;
    private final String secret;
    private final String apiKey;

    private static volatile String apiPrefix = null;   // "/v" 或 ""

    public FnClient() {
        this(pickBase(), Config.trustSsl(), Config.token(), Config.cookie(),
                Config.user(), Config.secret(), Config.apiKey());
    }

    /**
     * 共享实例缓存（v2.6 修「所有源加载异常缓慢」）：以前每次 loadItems/detail/episodes
     * 都 new FnClient() → 每次都重跑接口路径探测矩阵（最多 6 个请求 × 6s 超时），
     * 单线程 IO 池里排队后整体巨慢。现在按连接参数缓存实例，apiPrefix 探测一次复用。
     */
    private static final java.util.Map<String, FnClient> SHARED =
            new java.util.concurrent.ConcurrentHashMap<String, FnClient>();
    private static final int SHARED_MAX = 8;

    public static FnClient shared() {
        String key = pickBase() + "|" + Config.trustSsl() + "|" + Config.token()
                + "|" + Config.cookie() + "|" + Config.user()
                + "|" + Config.secret() + "|" + Config.apiKey();
        FnClient c = SHARED.get(key);
        if (c == null) {
            c = new FnClient();
            if (SHARED.size() >= SHARED_MAX) SHARED.clear();   // 简单防膨胀
            SHARED.put(key, c);
        }
        return c;
    }

    /** 当前源/Token 变化后清缓存（切换源、重新登录时调用）。 */
    public static void forgetShared() { SHARED.clear(); }

    /** 优先用自愈阶段确认过、网页确实能打开的地址；没有则退回用户填的。 */
    private static String pickBase() {
        String c = Reach.cached();
        return c.length() > 0 ? c : Config.base();
    }

    public FnClient(String base, boolean trustSsl, String token, String cookie,
                    String user, String secret, String apiKey) {
        this.base = base == null ? "" : base;
        this.trustSsl = trustSsl;
        this.token = token == null ? "" : token;
        this.cookie = cookie == null ? "" : cookie;
        this.user = user == null ? "" : user;
        this.secret = secret == null ? "" : secret;
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public String base() { return base; }

    /** 完整 API URL。path 形如 /api/v1/item/list */
    public String url(String path) {
        String pfx = apiPrefix == null ? "/v" : apiPrefix;
        return base + pfx + path;
    }

    public String mediaUrl(String guid) {
        String pfx = apiPrefix == null ? "/v" : apiPrefix;
        return base + pfx + "/media/" + guid + "/preset.m3u8";
    }

    // ==================== 请求头 ====================

    private Map<String, String> headers(String method, String fullPath, String json) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Referer", base + "/v/");
        if (token.length() > 0) h.put("Authorization", token);

        StringBuilder ck = new StringBuilder();
        if (cookie.length() > 0) ck.append(cookie);
        if (token.length() > 0) {
            if (ck.length() > 0) ck.append("; ");
            ck.append("authorization=").append(token)
                    .append("; Trim-MC-token=").append(token);
        }
        if (user.length() > 0) {
            if (ck.length() > 0) ck.append("; ");
            ck.append("lastLoginUsername=").append(user);
        }
        if (ck.length() > 0) h.put("Cookie", ck.toString());

        // authx 签名：飞牛影视每个接口都要求。secret/api_key 是前端公开常量，
        // 已内置默认值（Authx.DEFAULT_*），用户在高级设置手填则优先。
        String sec = secret.length() > 0 ? secret : Authx.DEFAULT_SECRET;
        String key = apiKey.length() > 0 ? apiKey : Authx.DEFAULT_API_KEY;
        String path = fullPath;
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }
        h.put("authx", Authx.header(method, path, query, json, sec, key));
        return h;
    }

    /** 播放链路用的头（Cookie 是飞牛 HLS 直链的关键） */
    public Map<String, String> playHeaders() {
        return headers("GET", "/media/", null);
    }

    // ==================== 底层调用 ====================

    private Result call(String method, String path, String json, int timeoutMs) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        // POST 关键：签名与发送必须是同一个字符串。服务器按收到的 body 原文算 md5，
        // 而 org.json 的 key 顺序是 HashMap 序，必须先规范化（排序+紧凑）再发。
        if (!"GET".equals(method) && json != null) {
            json = Authx.compactSortedJson(json);
        }
        String u = url(path);
        Map<String, String> h = headers(method, (apiPrefix == null ? "/v" : apiPrefix) + path, json);
        Net.Resp resp = "POST".equals(method)
                ? Net.post(u, json, h, trustSsl, timeoutMs)
                : Net.get(u, h, trustSsl, timeoutMs);
        r.ms = System.currentTimeMillis() - t0;
        r.usedPath = (apiPrefix == null ? "/v" : apiPrefix) + path;
        r.raw = resp.body;
        if (resp.error.length() > 0) {
            r.error = Utils.explain(new Exception(resp.error));
            return r;
        }
        if (resp.code < 200 || resp.code >= 300) {
            r.error = "HTTP " + resp.code + "（" + r.usedPath + "）";
            return r;
        }
        try {
            JSONObject o = new JSONObject(resp.body);
            r.code = o.optInt("code", o.optInt("Code", 0));
            r.data = o;
            r.list = extractArray(o);
            // 详情类接口返回的是单个对象（没有 list），不能因此判失败
            r.ok = (r.code == 0 || r.code == 200);
            if (!r.ok && r.error.length() == 0) {
                String msg = o.optString("message", o.optString("msg", ""));
                r.error = "code=" + r.code + (msg.length() > 0 ? " " + msg : "（接口拒绝，可能未登录或需 authx 签名）");
            }
        } catch (Throwable t) {
            // 不是标准 JSON 包（有的接口直接返回数组 / 纯文本）
            if (resp.body.trim().startsWith("[")) {
                try { r.list = new JSONArray(resp.body); r.ok = true; }
                catch (Throwable ignored) {}
            }
            if (!r.ok) r.error = "响应不是预期 JSON：" + resp.body.substring(0,
                    Math.min(resp.body.length(), 80));
        }
        return r;
    }

    /** 宽容提取列表：data / data.list / list / data.items / data.records */
    private static JSONArray extractArray(JSONObject o) {
        if (o == null) return null;
        JSONArray a = o.optJSONArray("list");
        if (a != null) return a;
        Object d = o.opt("data");
        if (d instanceof JSONArray) return (JSONArray) d;
        if (d instanceof JSONObject) {
            JSONObject dj = (JSONObject) d;
            for (String k : new String[]{"list", "items", "records", "result", "data"}) {
                JSONArray x = dj.optJSONArray(k);
                if (x != null) return x;
            }
        }
        JSONArray x = o.optJSONArray("items");
        if (x != null) return x;
        return null;
    }

    // ==================== 业务接口 ====================

    /**
     * 原生账号密码登录：{@code POST /v/api/v1/login}，body
     * {@code {"username","password","app_name":"trimemedia-web"}}（fnv.py 同款），
     * 成功后 {@link #extractToken} 取 {@code data.token}。
     */
    public Result loginNative(String username, String password) {
        JSONObject b = new JSONObject();
        try {
            b.put("username", username == null ? "" : username);
            b.put("password", password == null ? "" : password);
            b.put("app_name", "trimemedia-web");
        } catch (Throwable ignored) {}
        String save = token;
        token = "";                       // 登录请求不带旧 Authorization
        Result r = call("POST", "/api/v1/login", b.toString(), 10000);
        token = save;
        return r;
    }

    /** 从登录响应取 {@code data.token}（兼容顶层 token）。 */
    public static String extractToken(Result r) {
        if (r == null || r.data == null) return "";
        JSONObject d = r.data.optJSONObject("data");
        if (d != null) {
            String t = d.optString("token", "");
            if (t.length() > 0) return t;
        }
        return r.data.optString("token", "");
    }

    /**
     * 探测 API 前缀（/v 或空）与可用路径，并生成诊断矩阵。
     *
     * <p>候选依据社区两个独立开源实现（fnv.py / fnos-tv）的实测路径：
     * <ul>
     *   <li>{@code GET /v/api/v1/user/info} —— 登录态校验端点（fnos-tv 同款）</li>
     *   <li>{@code GET /v/api/v1/mdb/list} —— 媒体库列表（fnv.py 同款）</li>
     *   <li>{@code GET /v/api/v1/mediadb/list} —— 媒体库列表（fnos-tv-web 同款）</li>
     * </ul>
     * 矩阵记录每个候选的 HTTP 结果：404=路径不存在，401/403=路径在但未登录，
     * code=-2=凭据过期，code=0=通。
     */
    public Result probe() {
        Result fallback = null;
        StringBuilder trail = new StringBuilder();
        for (String pfx : new String[]{"/v", ""}) {
            apiPrefix = pfx;
            Result u = call("GET", "/api/v1/user/info", null, 6000);
            trail.append("  ").append(u.ok ? "✓" : "✗").append(" ").append(pfx)
                    .append("/api/v1/user/info")
                    .append(u.ok ? "（登录态有效）" : " → " + u.error).append("\n");
            if (u.ok && fallback == null) fallback = u;
            for (String p : new String[]{"/api/v1/mdb/list", "/api/v1/mediadb/list"}) {
                Result r = call("GET", p, null, 6000);
                trail.append("  ").append(r.ok ? "✓" : "✗").append(" ").append(pfx).append(p)
                        .append(r.ok ? "（code=" + r.code + "，"
                                + (r.list == null ? 0 : r.list.length()) + " 个媒体库）"
                                : " → " + r.error).append("\n");
                if (r.ok && r.list != null) { r.trail = trail.toString(); return r; }
                if (fallback == null) fallback = r;
            }
        }
        apiPrefix = null;   // 全失败，下次重新探测
        if (fallback != null) fallback.trail = trail.toString();
        return fallback == null ? new Result() : fallback;
    }

    public boolean probeNoAuthx() { return probe().ok; }

    public Result libraries() {
        Result r = probe();
        return r;
    }

    public Result items(String mdbGuid, int page) {
        if (apiPrefix == null) probe();
        int size = 48;
        JSONObject body = new JSONObject();
        try {
            if (mdbGuid != null && mdbGuid.length() > 0)
                body.put("ancestor_guid", mdbGuid);
            body.put("tags", new JSONObject().put("type",
                    new JSONArray().put("Movie").put("TV").put("Directory").put("Video")));
            body.put("exclude_grouped_video", 1);
            body.put("sort_type", "DESC");
            body.put("sort_column", "create_time");
            body.put("page_size", size);
            // 官方前端不传 page（只有 page_size）；仅翻页时附加，避免首屏兼容性风险
            if (page > 1) body.put("page", page);
        } catch (Throwable ignored) {}
        return call("POST", "/api/v1/item/list", body.toString(), 10000);
    }

    public Result detail(String guid) {
        if (apiPrefix == null) probe();
        return call("GET", "/api/v1/item/" + enc(guid), null, 8000);
    }

    public Result episodes(String guid) {
        if (apiPrefix == null) probe();
        return call("GET", "/api/v1/episode/list/" + enc(guid), null, 8000);
    }

    public Result seasons(String guid) {
        if (apiPrefix == null) probe();
        return call("GET", "/api/v1/season/list/" + enc(guid), null, 8000);
    }

    /** 最近一次取链的过程记录（play/info → stream/list → quality → play/play），诊断用。 */
    public static volatile String lastPlayTrail = "";
    /** 最近一次取链读到的清晰度列表（play/quality），供播放器弹窗切换。 */
    public static volatile String[] lastQualityRes = new String[0];

    /**
     * 播放地址 —— 完整复刻 fnos-tv-web（飞牛影视第三方网页客户端）的真实四步链：
     * <ol>
     *   <li>{@code POST /api/v1/play/info} body {@code {"item_guid"}} →
     *       {@code item.parent_guid}（空=电影本身可播，非空=TV 要换成该 guid）</li>
     *   <li>{@code GET /api/v1/stream/list/{guid}?before_play=1} →
     *       {@code files[] / video_streams[] / audio_streams[]}；
     *       文件优先选 path 不带「数字-数字-」前缀的本地文件（正则同官方前端）</li>
     *   <li>{@code POST /api/v1/play/quality} body {@code {"media_guid"}} → 清晰度列表取第一个</li>
     *   <li>{@code POST /api/v1/play/play} body media/video/audio 三 guid + 编码器 +
     *       resolution + bitrate + channels → {@code play_link}</li>
     * </ol>
     * 任一步失败则回退旧的 m3u8 直链猜测（个别旧版本可能支持）。
     */
    public String playUrl(String guid) {
        return playUrl(guid, null);
    }

    /** 指定清晰度取链（清晰度切换用）。forceRes 为 null 时取 quality 列表第一个。 */
    public String playUrl(String guid, String forceRes) {
        String trail = "";
        lastPlayTrail = "";
        lastQualityRes = new String[0];
        try {
            if (apiPrefix == null) probe();
            // 1) play/info：拿 parent_guid 与观看进度
            String playGuid = guid;
            long watched = 0;
            JSONObject b1 = new JSONObject().put("item_guid", guid);
            Result r1 = call("POST", "/api/v1/play/info", b1.toString(), 8000);
            trail += "play/info " + (r1.ok ? "OK" : r1.error) + "\n";
            lastPlayTrail = trail;
            if (r1.ok && r1.data != null) {
                JSONObject d1 = r1.data.optJSONObject("data");
                if (d1 != null) {
                    JSONObject item = d1.optJSONObject("item");
                    if (item != null) {
                        String pg = item.optString("parent_guid", "");
                        if (pg.length() > 0) playGuid = pg;
                        watched = item.optLong("watched_ts", 0);
                    }
                }
            }
            // 2) stream/list：文件与音视频流
            Result r2 = call("GET", "/api/v1/stream/list/" + enc(playGuid) + "?before_play=1", null, 8000);
            trail += "stream/list " + (r2.ok ? "OK" : r2.error) + "\n";
            lastPlayTrail = trail;
            if (!r2.ok) return fallbackPlay(guid, trail);
            JSONObject d2 = r2.data == null ? null : r2.data.optJSONObject("data");
            if (d2 == null) return fallbackPlay(guid, trail);
            JSONArray files = d2.optJSONArray("files");
            JSONArray vs = d2.optJSONArray("video_streams");
            JSONArray as = d2.optJSONArray("audio_streams");
            if (files == null || files.length() == 0 || vs == null || vs.length() == 0)
                return fallbackPlay(guid, trail);
            JSONObject file = files.optJSONObject(0);
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f != null && !f.optString("path", "").matches("\\d+-\\d+-\\S+")) { file = f; break; }
            }
            JSONObject video = vs.optJSONObject(0);
            JSONObject audio = as == null || as.length() == 0 ? null : as.optJSONObject(0);
            // 3) play/quality：清晰度列表（供播放器切换），默认取第一个
            String resolution = "1080";
            if (forceRes != null && forceRes.length() > 0) {
                resolution = forceRes;          // 用户切换清晰度：跳过 quality 直接用
            } else {
                JSONObject b3 = new JSONObject().put("media_guid", file.optString("guid", ""));
                Result r3 = call("POST", "/api/v1/play/quality", b3.toString(), 8000);
                trail += "play/quality " + (r3.ok ? "OK" : r3.error) + "\n";
                if (r3.ok && r3.data != null) {
                    JSONArray q = r3.data.optJSONArray("data");
                    if (q == null && r3.data.optJSONObject("data") != null)
                        q = r3.data.optJSONObject("data").optJSONArray("list");
                    if (q != null && q.length() > 0) {
                        java.util.List<String> res = new java.util.ArrayList<String>();
                        for (int i = 0; i < q.length(); i++) {
                            JSONObject q0 = q.optJSONObject(i);
                            if (q0 != null && q0.optString("resolution").length() > 0)
                                res.add(q0.optString("resolution"));
                        }
                        lastQualityRes = res.toArray(new String[res.size()]);
                        if (lastQualityRes.length > 0) resolution = lastQualityRes[0];
                    }
                }
            }
            // 4) play/play：换 play_link
            JSONObject b4 = new JSONObject();
            b4.put("media_guid", file.optString("guid", ""));
            b4.put("video_guid", video.optString("guid", ""));
            b4.put("video_encoder", video.optString("codec_name", ""));
            b4.put("resolution", resolution);
            if (video.has("bps")) b4.put("bitrate", video.optLong("bps", 0));
            b4.put("startTimestamp", watched);   // 官方传数字
            b4.put("audio_encoder", "aac");
            b4.put("audio_guid", audio == null ? "" : audio.optString("guid", ""));
            b4.put("subtitle_guid", "");
            b4.put("channels", 2);
            Result r4 = call("POST", "/api/v1/play/play", b4.toString(), 10000);
            trail += "play/play " + (r4.ok ? "OK" : r4.error);
            lastPlayTrail = trail;
            if (r4.ok && r4.data != null) {
                JSONObject d4 = r4.data.optJSONObject("data");
                String link = d4 == null ? "" : d4.optString("play_link", "");
                if (link.length() == 0) link = r4.data.optString("play_link", "");
                if (link.length() > 0) {
                    if (link.startsWith("http")) return link;
                    return base + (link.startsWith("/") ? link : "/" + link);
                }
            }
            return fallbackPlay(guid, trail);
        } catch (Throwable t) {
            return fallbackPlay(guid, trail + Utils.brief(t));
        }
    }

    /** 旧版回退：m3u8 直链猜测 + play_link 兜底探测。 */
    private String fallbackPlay(String guid, String trail) {
        String direct = mediaUrl(guid);
        Map<String, String> h = playHeaders();
        Net.Resp r = Net.get(direct, h, trustSsl, 6000);
        if (r.error.length() == 0 && r.code >= 200 && r.code < 400) return direct;
        android.util.Log.w("FnClient", "播放链失败：" + trail + " → 回退直链 " + r.code + " " + r.error);
        return direct;   // 交给播放器/代理，失败信息在播放页状态行显示
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Throwable t) { return s == null ? "" : s; }
    }

    // ==================== 连接诊断 ====================

    /**
     * 分 5 段诊断，逐段回调给前端。用户手机自测时只需看哪一步变红、把日志发回。
     */
    public void diagnose(String rawBase, boolean trust, StepListener l) {
        long t0;
        String norm;
        // 1) 地址解析
        t0 = System.currentTimeMillis();
        try {
            norm = Utils.normalizeBase(rawBase);
            l.onStep(1, "地址解析", true, norm, System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            l.onStep(1, "地址解析", false, t.getMessage(), System.currentTimeMillis() - t0);
            return;
        }
        String host = Utils.hostOf(norm);
        String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        boolean pureV6 = Utils.isIpv6Literal(norm);
        int port = 5666;
        try { port = Integer.parseInt(norm.substring(norm.lastIndexOf(':') + 1)); }
        catch (Throwable ignored) {}

        // 2) DNS（IPv6 字面量跳过查询，但仍解析出地址对象供 TCP 阶段复用）
        t0 = System.currentTimeMillis();
        InetAddress[] addrs = null;
        try {
            addrs = InetAddress.getAllByName(bare);
            if (pureV6) {
                l.onStep(2, "地址族", true, "IPv6 字面量 " + bare + "（跳过 DNS）",
                        System.currentTimeMillis() - t0);
            } else {
                StringBuilder sb = new StringBuilder();
                int v6 = 0, v4 = 0;
                for (InetAddress a : addrs) {
                    if (sb.length() > 0) sb.append("  ");
                    sb.append(a.getHostAddress());
                    if (a instanceof java.net.Inet6Address) v6++; else v4++;
                }
                l.onStep(2, "DNS 解析", true,
                        "IPv4=" + v4 + " IPv6=" + v6 + "  " + sb,
                        System.currentTimeMillis() - t0);
            }
        } catch (Throwable t) {
            l.onStep(2, "DNS 解析", false, Utils.explain(t),
                    System.currentTimeMillis() - t0);
            return;
        }
        boolean hasV6 = false;
        if (addrs != null) for (InetAddress a : addrs)
            if (a instanceof java.net.Inet6Address) hasV6 = true;

        // 3) 网页可达性：发真实 HTTP 请求，自动挑出「浏览器能打开」的那条路。
        //    裸 TCP 只作参考、不再一票否决——5667 被拒往往只是直连端口没放行，
        //    而浏览器走的是 443 反代 / 官方中继。
        t0 = System.currentTimeMillis();
        Probe tcp = tcpProbe(addrs, bare, port, 2500);
        Reach.Heal heal = Reach.heal(norm);
        String used = heal.ok ? heal.base : norm;
        StringBuilder msg = new StringBuilder();
        msg.append("【裸 TCP 参考】").append(host).append(":").append(port)
                .append(tcp.ok ? " 可连通（" + tcp.via + "）" : " 被拒绝 —— 仅代表这条直连路径没服务")
                .append("\n\n【跟网页走】对同一主机逐一发真实 HTTP 请求（含 443 / 80 / 5666 / 5667 / 8005 / 8000 / 8001）：\n")
                .append(heal.detail);

        if (heal.ok) {
            if (!used.equals(norm)) {
                msg.append("\n➤ 网页能打开的是 ").append(used)
                        .append("，设置里填的 ").append(norm)
                        .append(" 走的是另一条路（直连端口未放行 / 中继转发）。\n")
                        .append("   已自动把服务器地址切换为 ").append(used)
                        .append(" 并写回设置，回到首页下拉刷新媒体库即可。");
            } else {
                msg.append("\n➤ 当前地址与浏览器一致，可直接使用。");
            }
            if (used.startsWith("https")) {
                msg.append("\n➤ 检测到 HTTPS，已自动信任自签证书（否则内嵌网页会白屏）。");
            }
            l.onStep(3, "网页可达性（自动选路）", true, msg.toString(),
                    System.currentTimeMillis() - t0);
        } else {
            msg.append("\n【全部不通】同一主机的常见端口都没有 HTTP 服务响应。");
            if (hasV6) {
                msg.append("\n\n【IPv6 排查】域名只有 AAAA 记录：\n"
                        + "  ① 路由器 IPv6 防火墙默认拦入站 —— 放行 TCP 5667/5666，或临时关闭"
                        + "（小米路由器：ip6tables -I forwarding_rule -p tcp --dport 5667 -j ACCEPT）\n"
                        + "  ② NAS「设置 → 安全性 → 端口」确认监听端口，重拨后 IPv6 前缀会变，核对 DDNS 是否同步\n"
                        + "  ③ 用手机流量（关 WiFi）测试，排除局域网内假通");
            } else {
                msg.append("\n请确认 NAS 已开机、端口设置、以及路由器端口转发 / 防火墙。");
            }
            msg.append("\n\n➤ 最有效的定位办法：在手机浏览器里打开飞牛登录页，"
                    + "长按地址栏复制完整网址发我（要带 http/https 和端口）。"
                    + "网页能登录就说明一定存在一条可用路径，App 会自动跟过去。");
            l.onStep(3, "网页可达性（自动选路）", false, msg.toString(),
                    System.currentTimeMillis() - t0);
            return;
        }

        // 4) 用「网页能打开」的地址做 HTTP / TLS 握手
        t0 = System.currentTimeMillis();
        Net.Resp r = Net.get(used + "/v/", null, true, 6000);
        if (r.error.length() > 0) {
            l.onStep(4, "HTTP / TLS", false, Utils.explain(new Exception(r.error)),
                    System.currentTimeMillis() - t0);
            return;
        }
        String page = r.body == null ? "" : r.body.toLowerCase(java.util.Locale.ROOT);
        boolean loginPage = page.contains("fnos") || page.contains("飞牛")
                || page.contains("登录") || page.contains("<title");
        l.onStep(4, "HTTP / TLS", true, "HTTP " + r.code
                + (loginPage ? "（已拿到飞牛页面）" : "（有响应，非标准登录页）")
                + "  " + used, System.currentTimeMillis() - t0);

        // 5) 影视接口 / 登录态：输出候选路径矩阵；填了账号密码时失败会自动原生登录重试
        t0 = System.currentTimeMillis();
        FnClient tmp = new FnClient(used, true, token, cookie, user, secret, apiKey);
        Result pr = tmp.probe();
        String note = "";
        if (!pr.ok && Config.user().length() > 0 && Config.pass().length() > 0) {
            Result lr = tmp.loginNative(Config.user(), Config.pass());
            String tk = FnClient.extractToken(lr);
            if (tk.length() > 0) {
                Config.setToken(tk);
                tmp = new FnClient(used, true, tk, cookie, user, secret, apiKey);
                pr = tmp.probe();
                note = "\n➤ 已用账号密码自动登录成功，Token 已保存。";
            } else {
                note = "\n➤ 账号密码自动登录失败："
                        + (lr.error.length() > 0 ? lr.error : "code=" + lr.code)
                        + "（请到设置里检查用户名密码）";
            }
        }
        String head = pr.trail.length() > 0 ? "【接口路径探测】\n" + pr.trail : "";
        if (pr.ok) {
            l.onStep(5, "影视接口 / 登录态", true,
                    head + "可用路径 " + pr.usedPath + "，读到 "
                            + (pr.list == null ? 0 : pr.list.length()) + " 个媒体库" + note,
                    System.currentTimeMillis() - t0);
        } else {
            String em = head;
            if (pr.trail.contains("404")) {
                em += "【结论】候选路径全部 404：中继可能只转发了网页、没转发 /v/api/ 接口，"
                        + "或你的飞牛版本路径不同。请先点「账号密码登录」再诊断；仍失败就把本日志发回。";
            } else if (!Config.hasCredential()) {
                em += "【结论】接口路径存在但需要登录：请到设置里填用户名密码点「账号密码登录」，或用「网页登录」。";
            } else {
                em += "【结论】" + (pr.error.length() > 0 ? pr.error : "接口无响应")
                        + "。凭据可能已过期，请重新登录。";
            }
            l.onStep(5, "影视接口 / 登录态", false, em + note,
                    System.currentTimeMillis() - t0);
        }
    }

    /** TCP 探测结果 */
    private static class Probe {
        boolean ok;
        String err = "";
        String via = "";
    }

    /**
     * 对解析出的所有地址逐个尝试 TCP 连接（IPv4/IPv6 双栈都试），任一连通即算成功。
     * 单独成方法是因为诊断阶段需要复用它扫多个端口。
     */
    private static Probe tcpProbe(InetAddress[] addrs, String host, int port, int timeoutMs) {
        Probe p = new Probe();
        if (addrs != null && addrs.length > 0) {
            for (InetAddress a : addrs) {
                Socket s = null;
                try {
                    s = new Socket();
                    s.connect(new InetSocketAddress(a, port), timeoutMs);
                    p.ok = true;
                    p.via = a.getHostAddress();
                    return p;
                } catch (Throwable t) {
                    if (p.err.length() == 0) p.err = Utils.explain(t);
                } finally {
                    if (s != null) try { s.close(); } catch (Throwable ignored) {}
                }
            }
        } else {
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(host, port), timeoutMs);
                p.ok = true;
                p.via = host;
            } catch (Throwable t) {
                p.err = Utils.explain(t);
            } finally {
                if (s != null) try { s.close(); } catch (Throwable ignored) {}
            }
        }
        return p;
    }
}
