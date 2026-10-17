package com.tingshu.app.pan;

import com.tingshu.app.core.Net;
import com.tingshu.app.data.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ v3.9.0：主流网盘接口 —— 分享链接解析 + 直链获取。
 *
 * <h3>支持平台</h3>
 * 阿里云盘 / 百度网盘 / 夸克网盘 / 天翼云盘 / 115网盘 / UC网盘。
 *
 * <h3>工作方式</h3>
 * <ol>
 *   <li>用户在「书源管理 → 自定义源 → 网盘接口」里粘贴各平台网页版
 *       登录后的 <b>Cookie</b>（存本机 SharedPreferences，不上传）；</li>
 *   <li>粘贴分享链接 → {@link #parseShare} 拉文件清单；</li>
 *   <li>播放时 {@link #downloadUrl} 现取直链（直链有时效，所以不落库，
 *       每次播放重新解析，与源站直链同样对待）。</li>
 * </ol>
 *
 * <h3>边界与实话</h3>
 * 各平台的网页接口都不公开文档、随时可能改版。这里每个平台都是<b>独立
 * 方法 + 独立 try-catch</b>：某个平台失效只坏它一个，错误信息会明确
 * 告诉用户是「Cookie 失效」还是「接口改版」，不会连带其它功能。
 * 阿里云盘的公开分享走官方 open API（share_token 机制），最稳；
 * 其余平台依赖登录 Cookie，失效后重新粘贴即可。
 */
public final class PanResolver {

    public static final String ALI   = "pan_ali";
    public static final String BAIDU = "pan_baidu";
    public static final String QUARK = "pan_quark";
    public static final String TIANYI = "pan_tianyi";
    public static final String P115  = "pan_115";
    public static final String UC    = "pan_uc";

    public static final String[] ALL = { ALI, BAIDU, QUARK, TIANYI, P115, UC };

    private PanResolver() {}

    /** 分享里的一个文件/文件夹 */
    public static final class PanFile {
        public String id;      // 平台内文件标识（fid / file_id / fs_id / pickcode）
        public String name;
        public long size;
        public boolean dir;
        public String token;   // 阿里/夸克的 share_fid_token，下载时要带回
    }

    // ==================== 平台元信息 ====================

    public static String nameOf(String pid) {
        if (ALI.equals(pid)) return "阿里云盘";
        if (BAIDU.equals(pid)) return "百度网盘";
        if (QUARK.equals(pid)) return "夸克网盘";
        if (TIANYI.equals(pid)) return "天翼云盘";
        if (P115.equals(pid)) return "115网盘";
        if (UC.equals(pid)) return "UC网盘";
        return pid;
    }

    public static String hostOf(String pid) {
        if (ALI.equals(pid)) return "https://www.aliyundrive.com";
        if (BAIDU.equals(pid)) return "https://pan.baidu.com";
        if (QUARK.equals(pid)) return "https://pan.quark.cn";
        if (TIANYI.equals(pid)) return "https://cloud.189.cn";
        if (P115.equals(pid)) return "https://115.com";
        if (UC.equals(pid)) return "https://drive.uc.cn";
        return "";
    }

    /** 前端展示的取 Cookie 指引 */
    public static String helpOf(String pid) {
        if (ALI.equals(pid)) {
            return "公开分享一般无需 Cookie；仅在自己网盘/加密分享时需要。\n" +
                   "浏览器登录 aliyundrive.com 后 F12 → 网络 → 任一条请求的 " +
                   "Request Headers 里整条 Cookie 复制过来。";
        }
        return "电脑浏览器登录 " + hostOf(pid) + " → 按 F12 → 网络 → " +
               "刷新页面 → 任一条请求 → Request Headers → Cookie，整条复制粘贴到下面。";
    }

    // ==================== Cookie 存取 ====================

    public static String cookie(String pid) {
        return Settings.sp().getString("pan_cookie_" + pid, "");
    }

    public static boolean configured(String pid) {
        String c = cookie(pid);
        return c != null && c.trim().length() > 10;
    }

    public static void saveCookie(String pid, String cookie) {
        Settings.sp().edit().putString("pan_cookie_" + pid, cookie == null ? "" : cookie.trim()).apply();
    }

    // ==================== 分享解析 ====================

    /** 解析分享链接 → 文件清单（文件夹会展平其下音频/媒体文件） */
    public static List<PanFile> parseShare(String pid, String shareUrl) throws Exception {
        if (ALI.equals(pid)) return aliList(shareUrl);
        if (QUARK.equals(pid)) return quarkList(shareUrl, false);
        if (UC.equals(pid)) return quarkList(shareUrl, true);
        if (P115.equals(pid)) return p115List(shareUrl);
        if (TIANYI.equals(pid)) return tianyiList(shareUrl);
        if (BAIDU.equals(pid)) return baiduList(shareUrl);
        throw new Exception("不支持的平台");
    }

    /** 取下载直链（可能耗时） */
    public static String downloadUrl(String pid, PanFile f, String shareUrl) throws Exception {
        if (ALI.equals(pid)) return aliDownload(shareUrl, f);
        if (QUARK.equals(pid)) return quarkDownload(shareUrl, f, false);
        if (UC.equals(pid)) return quarkDownload(shareUrl, f, true);
        if (P115.equals(pid)) return p115Download(f);
        if (TIANYI.equals(pid)) return tianyiDownload(shareUrl, f);
        if (BAIDU.equals(pid)) return baiduDownload(shareUrl, f);
        throw new Exception("不支持的平台");
    }

    /** 播放直链要带的请求头（Cookie / Referer / UA） */
    public static Map<String, String> headers(String pid, String url) {
        Map<String, String> h = new HashMap<String, String>();
        String ck = cookie(pid);
        if (configured(pid)) h.put("Cookie", ck);
        if (QUARK.equals(pid) || UC.equals(pid)) {
            h.put("Referer", hostOf(pid) + "/");
            h.put("User-Agent", Net.UA);
        } else if (BAIDU.equals(pid)) {
            h.put("Referer", hostOf(pid) + "/");
            h.put("User-Agent", Net.UA_DESKTOP);
        } else if (TIANYI.equals(pid)) {
            h.put("Referer", hostOf(pid) + "/");
        }
        return h.isEmpty() ? null : h;
    }

    // ==================== 阿里云盘 ====================

    private static String aliShareId(String url) throws Exception {
        // https://www.aliyundrive.com/s/xxxx 或 alipan.com/s/xxxx
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:aliyundrive\\.com|alipan\\.com)/s/([a-zA-Z0-9]+)")
                .matcher(url == null ? "" : url);
        if (m.find()) return m.group(1);
        throw new Exception("链接不像阿里云盘分享（应为 …/s/ 开头的分享页）");
    }

    private static String aliToken(String shareId, String pwd) throws Exception {
        JSONObject body = new JSONObject();
        body.put("share_id", shareId);
        body.put("share_pwd", pwd == null ? "" : pwd);
        Net.Resp r = Net.request("https://api.aliyundrive.com/adrive/v3/share_link/get_share_token",
                body.toString(), "https://www.aliyundrive.com/", null, false);
        if (r == null || r.body == null) throw new Exception("阿里云盘无响应");
        JSONObject o = new JSONObject(r.body);
        String t = o.optString("share_token", "");
        if (t.length() == 0) throw new Exception("获取 share_token 失败：" + brief(r.body));
        return t;
    }

    private static List<PanFile> aliList(String shareUrl) throws Exception {
        String shareId = aliShareId(shareUrl);
        // 提取码（部分分享链接带 ?pwd= 或 Fragment）
        String pwd = "";
        java.util.regex.Matcher pm = java.util.regex.Pattern.compile("[?&#]pwd=([a-zA-Z0-9]{4})")
                .matcher(shareUrl == null ? "" : shareUrl);
        if (pm.find()) pwd = pm.group(1);
        String token = aliToken(shareId, pwd);

        Map<String, String> h = new HashMap<String, String>();
        h.put("x-share-token", token);
        List<PanFile> out = new ArrayList<PanFile>();
        String next = null;
        int guard = 0;
        do {
            JSONObject body = new JSONObject();
            body.put("share_id", shareId);
            body.put("parent_file_id", next == null ? "root" : next);
            body.put("limit", 100);
            body.put("order_by", "name");
            body.put("order_direction", "ASC");
            Net.Resp r = Net.request("https://api.aliyundrive.com/adrive/v2/file/list",
                    body.toString(), "https://www.aliyundrive.com/", h, false);
            if (r == null || r.body == null) throw new Exception("阿里云盘拉文件列表无响应");
            JSONObject o = new JSONObject(r.body);
            JSONArray items = o.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    PanFile f = new PanFile();
                    f.id = it.optString("file_id", "");
                    f.name = it.optString("name", "");
                    f.size = it.optLong("size", 0);
                    f.dir = "folder".equals(it.optString("type", ""));
                    out.add(f);
                    if (f.dir && guard < 4) {
                        // 展平一层子文件夹（有声书常见「书名/章节」结构）
                        try {
                            for (PanFile sub : aliChildren(shareId, token, f.id)) out.add(sub);
                        } catch (Throwable ignored) {}
                    }
                }
            }
            next = o.optString("next_marker", "");
            if (next.length() == 0) break;
        } while (++guard < 10);
        return out;
    }

    private static List<PanFile> aliChildren(String shareId, String token, String parent) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        h.put("x-share-token", token);
        JSONObject body = new JSONObject();
        body.put("share_id", shareId);
        body.put("parent_file_id", parent);
        body.put("limit", 100);
        Net.Resp r = Net.request("https://api.aliyundrive.com/adrive/v2/file/list",
                body.toString(), "https://www.aliyundrive.com/", h, false);
        List<PanFile> out = new ArrayList<PanFile>();
        if (r == null || r.body == null) return out;
        JSONArray items = new JSONObject(r.body).optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                PanFile f = new PanFile();
                f.id = it.optString("file_id", "");
                f.name = it.optString("name", "");
                f.size = it.optLong("size", 0);
                f.dir = "folder".equals(it.optString("type", ""));
                out.add(f);
            }
        }
        return out;
    }

    private static String aliDownload(String shareUrl, PanFile f) throws Exception {
        String shareId = aliShareId(shareUrl);
        String pwd = "";
        java.util.regex.Matcher pm = java.util.regex.Pattern.compile("[?&#]pwd=([a-zA-Z0-9]{4})")
                .matcher(shareUrl == null ? "" : shareUrl);
        if (pm.find()) pwd = pm.group(1);
        String token = aliToken(shareId, pwd);
        Map<String, String> h = new HashMap<String, String>();
        h.put("x-share-token", token);
        JSONObject body = new JSONObject();
        body.put("share_id", shareId);
        body.put("file_id", f.id);
        Net.Resp r = Net.request(
                "https://api.aliyundrive.com/adrive/v2/file/get_share_link_download_url",
                body.toString(), "https://www.aliyundrive.com/", h, false);
        if (r == null || r.body == null) throw new Exception("阿里云盘取直链无响应");
        JSONObject o = new JSONObject(r.body);
        String url = o.optString("download_url", "");
        if (url.length() == 0) throw new Exception("阿里云盘未返回直链：" + brief(r.body));
        return url;
    }

    // ==================== 夸克 / UC（同一套 API，换域名） ====================

    private static String quarkBase(boolean uc) {
        return uc ? "https://pc-api.uc.cn/1/clouddrive" : "https://drive-pc.quark.cn/1/clouddrive";
    }

    private static String quarkPwdId(String url) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:pan\\.quark\\.cn|drive\\.uc\\.cn|uc\\.cn)/s/([a-zA-Z0-9]+)")
                .matcher(url == null ? "" : url);
        if (m.find()) return m.group(1);
        throw new Exception("链接不像夸克/UC 分享（应为 /s/ 开头的分享页）");
    }

    private static String quarkToken(String pwdId, boolean uc) throws Exception {
        Map<String, String> h = quarkHeaders(uc);
        JSONObject body = new JSONObject();
        body.put("pwd_id", pwdId);
        body.put("passcode", "");
        Net.Resp r = Net.request(quarkBase(uc) + "/share/sharepage/token?pr=ucpro&fr=pc",
                body.toString(), hostOf(uc ? UC : QUARK) + "/", h, false);
        if (r == null || r.body == null) throw new Exception("夸克/UC 无响应（可能需要 Cookie）");
        JSONObject o = new JSONObject(r.body);
        String t = o.optJSONObject("data") == null ? "" : o.optJSONObject("data").optString("stoken", "");
        if (t.length() == 0) throw new Exception("获取 stoken 失败：" + brief(r.body) + "（Cookie 失效？）");
        return t;
    }

    private static Map<String, String> quarkHeaders(boolean uc) {
        Map<String, String> h = new HashMap<String, String>();
        String ck = cookie(uc ? UC : QUARK);
        if (ck.length() > 0) h.put("Cookie", ck);
        h.put("Referer", hostOf(uc ? UC : QUARK) + "/");
        h.put("User-Agent", Net.UA);
        return h;
    }

    private static List<PanFile> quarkList(String shareUrl, boolean uc) throws Exception {
        String pwdId = quarkPwdId(shareUrl);
        String stoken = quarkToken(pwdId, uc);
        Map<String, String> h = quarkHeaders(uc);
        List<PanFile> out = new ArrayList<PanFile>();
        String url = quarkBase(uc) + "/share/sharepage/detail?pr=ucpro&fr=pc"
                + "&pwd_id=" + pwdId + "&stoken=" + stoken
                + "&pdir_fid=0&force=0&_page=1&_size=100"
                + "&_sort=file_type:asc,updated_at:desc";
        Net.Resp r = Net.request(url, null, hostOf(uc ? UC : QUARK) + "/", h, false);
        if (r == null || r.body == null) throw new Exception("夸克/UC 拉文件列表无响应");
        JSONObject data = new JSONObject(r.body).optJSONObject("data");
        JSONArray items = data == null ? null : data.optJSONArray("list");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                PanFile f = new PanFile();
                f.id = it.optString("fid", "");
                f.name = it.optString("file_name", "");
                f.size = it.optLong("size", 0);
                f.dir = it.optBoolean("dir", false);
                f.token = it.optString("share_fid_token", "");
                out.add(f);
            }
        }
        if (out.isEmpty()) throw new Exception("分享里没有文件（或链接失效）");
        return out;
    }

    private static String quarkDownload(String shareUrl, PanFile f, boolean uc) throws Exception {
        if (!configured(uc ? UC : QUARK)) {
            throw new Exception("夸克/UC 下载需要登录 Cookie，请先在上方粘贴");
        }
        String pwdId = quarkPwdId(shareUrl);
        String stoken = quarkToken(pwdId, uc);
        Map<String, String> h = quarkHeaders(uc);
        JSONObject body = new JSONObject();
        JSONArray fids = new JSONArray();
        fids.put(f.id);
        body.put("fids", fids);
        Net.Resp r = Net.request(quarkBase(uc) + "/file/download?pr=ucpro&fr=pc",
                body.toString(), hostOf(uc ? UC : QUARK) + "/", h, false);
        if (r == null || r.body == null) throw new Exception("夸克/UC 取直链无响应");
        JSONObject data = new JSONObject(r.body).optJSONObject("data");
        String url = data == null ? "" : data.optString("download_url", "");
        if (url.length() == 0) throw new Exception("夸克/UC 未返回直链：" + brief(r.body) + "（Cookie 失效？）");
        return url;
    }

    // ==================== 115 ====================

    private static String p115Code(String url) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("115\\.com/s/([a-zA-Z0-9]+)")
                .matcher(url == null ? "" : url);
        if (m.find()) return m.group(1);
        throw new Exception("链接不像 115 分享（应为 115.com/s/ 开头）");
    }

    private static Map<String, String> p115Headers() {
        Map<String, String> h = new HashMap<String, String>();
        String ck = cookie(P115);
        if (ck.length() > 0) h.put("Cookie", ck);
        h.put("User-Agent", Net.UA);
        return h;
    }

    private static List<PanFile> p115List(String shareUrl) throws Exception {
        String code = p115Code(shareUrl);
        if (!configured(P115)) throw new Exception("115 解析需要登录 Cookie，请先在上方粘贴");
        Net.Resp r = Net.request("https://webapi.115.com/share/snap?share_code=" + code,
                null, "https://115.com/", p115Headers(), false);
        if (r == null || r.body == null) throw new Exception("115 无响应");
        JSONObject o = new JSONObject(r.body);
        if (!o.optBoolean("state", false)) {
            throw new Exception("115 拒绝访问：" + brief(r.body) + "（Cookie 失效？）");
        }
        JSONObject data = o.optJSONObject("data");
        JSONArray items = data == null ? null : data.optJSONArray("list");
        List<PanFile> out = new ArrayList<PanFile>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                PanFile f = new PanFile();
                // 115 web 接口字段：fid / n(名) / s(大小) / pc(pickcode) / fc(是文件夹)
                f.id = it.optString("fid", it.optString("pc", ""));
                f.name = it.optString("n", it.optString("file_name", ""));
                f.size = it.optLong("s", it.optLong("size", 0));
                f.dir = it.optString("fc", "").equals("1");
                f.token = it.optString("pc", "");
                out.add(f);
            }
        }
        if (out.isEmpty()) throw new Exception("分享里没有文件（或链接失效）");
        return out;
    }

    private static String p115Download(PanFile f) throws Exception {
        if (!configured(P115)) throw new Exception("115 下载需要登录 Cookie");
        // pickcode 直链接口（老接口，115 改版后会失效，错误信息如实给出）
        StringBuilder form = new StringBuilder("pickcode=" + f.token + "&need_link=1");
        Net.Resp r = Net.request("https://webapi.115.com/files/getfilelink",
                form.toString(), "https://115.com/", p115Headers(), false);
        if (r == null || r.body == null) throw new Exception("115 取直链无响应");
        JSONObject o = new JSONObject(r.body);
        if (!o.optBoolean("state", false)) {
            throw new Exception("115 未返回直链：" + brief(r.body) + "（接口可能已改版）");
        }
        JSONObject file = o.optJSONObject("data");
        if (file != null && file.has("url")) return file.optString("url", "");
        JSONArray arr = o.optJSONArray("data");
        if (arr != null && arr.length() > 0) {
            JSONObject it = arr.optJSONObject(0);
            if (it != null && it.has("url")) return it.optString("url", "");
        }
        throw new Exception("115 未返回直链：" + brief(r.body));
    }

    // ==================== 天翼云盘 ====================

    private static String[] tianyiIds(String shareUrl) throws Exception {
        // https://cloud.189.cn/web/share?code=xxx / t.cn 短链
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:cloud\\.189\\.cn|e\\.189\\.cn).*?[?&#]code=([a-zA-Z0-9]+)")
                .matcher(shareUrl == null ? "" : shareUrl);
        if (!m.find()) throw new Exception("链接不像天翼云盘分享（应含 code= 参数）");
        String code = m.group(1);
        if (!configured(TIANYI)) throw new Exception("天翼云盘解析需要登录 Cookie，请先在上方粘贴");
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie(TIANYI));
        h.put("User-Agent", Net.UA_DESKTOP);
        Net.Resp r = Net.request("https://cloud.189.cn/web/share?code=" + code,
                null, "https://cloud.189.cn/", h, false);
        if (r == null || r.body == null) throw new Exception("天翼云盘无响应");
        String html = r.body;
        String fileId = grab(html, "id=\"fileId\"[^>]*value=\"([^\"]+)\"");
        String shareId = grab(html, "id=\"shareId\"[^>]*value=\"([^\"]+)\"");
        if (fileId.length() == 0) fileId = grab(html, "\"fileId\"\\s*:\\s*\"([^\"]+)\"");
        if (shareId.length() == 0) shareId = grab(html, "\"shareId\"\\s*:\\s*\"([^\"]+)\"");
        if (fileId.length() == 0 || shareId.length() == 0) {
            throw new Exception("没解析到 shareId/fileId：" + brief(html) + "（Cookie 失效？）");
        }
        return new String[]{shareId, fileId};
    }

    private static List<PanFile> tianyiList(String shareUrl) throws Exception {
        String[] ids = tianyiIds(shareUrl);
        PanFile f = new PanFile();
        f.id = ids[1];
        f.name = "天翼分享";
        f.size = 0;
        f.dir = false;
        List<PanFile> out = new ArrayList<PanFile>();
        out.add(f);
        return out;
    }

    private static String tianyiDownload(String shareUrl, PanFile f) throws Exception {
        String[] ids = tianyiIds(shareUrl);
        if (!configured(TIANYI)) throw new Exception("天翼云盘下载需要登录 Cookie");
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie(TIANYI));
        h.put("User-Agent", Net.UA_DESKTOP);
        // openFile 302 到真实直链；不自动跟随，取 Location
        Net.Resp r = Net.request(
                "https://cloud.189.cn/api/openFile.do?fileId=" + ids[1] + "&shareId=" + ids[0],
                null, "https://cloud.189.cn/", h, false);
        if (r == null) throw new Exception("天翼云盘取直链无响应");
        String loc = r.location;
        if (loc == null || loc.length() == 0) {
            loc = grab(r.body == null ? "" : r.body, "url\\s*[:=]\\s*[\"']([^\"']+)[\"']");
        }
        if (loc == null || loc.length() == 0 || !loc.startsWith("http")) {
            throw new Exception("天翼云盘未返回直链：" + brief(r.body) + "（Cookie 失效？）");
        }
        return loc;
    }

    // ==================== 百度网盘 ====================

    private static String[] baiduIds(String shareUrl) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("pan\\.baidu\\.com/s/([a-zA-Z0-9_-]+)")
                .matcher(shareUrl == null ? "" : shareUrl);
        if (!m.find()) throw new Exception("链接不像百度网盘分享（应为 pan.baidu.com/s/ 开头）");
        String surl = m.group(1);
        if (!configured(BAIDU)) throw new Exception("百度网盘解析需要登录 Cookie，请先在上方粘贴");
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie(BAIDU));
        h.put("User-Agent", Net.UA_DESKTOP);
        Net.Resp r = Net.request("https://pan.baidu.com/s/" + surl,
                null, "https://pan.baidu.com/", h, false);
        if (r == null || r.body == null) throw new Exception("百度网盘无响应");
        String html = r.body;
        String shareid = grab(html, "\"shareid\"\\s*:\\s*\"?(\\d+)");
        String uk = grab(html, "\"uk\"\\s*:\\s*\"?(\\d+)");
        if (shareid.length() == 0 || uk.length() == 0) {
            // 旧版页面把参数放在 locals.mset(...) 里
            String blob = grab(html, "locals\\.mset\\(([\\s\\S]{0,4000}?)\\)");
            if (shareid.length() == 0) shareid = grab(blob, "shareid\\s*:\\s*\"?(\\d+)");
            if (uk.length() == 0) uk = grab(blob, "uk\\s*:\\s*\"?(\\d+)");
        }
        if (shareid.length() == 0 || uk.length() == 0) {
            throw new Exception("没解析到 shareid/uk：" + brief(html) + "（Cookie 失效？）");
        }
        return new String[]{shareid, uk};
    }

    private static List<PanFile> baiduList(String shareUrl) throws Exception {
        String[] ids = baiduIds(shareUrl);
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie(BAIDU));
        h.put("User-Agent", Net.UA_DESKTOP);
        String url = "https://pan.baidu.com/share/wxlist?app_id=250528&web=1&channel=chlink"
                + "&clienttype=0&shareid=" + ids[0] + "&uk=" + ids[1]
                + "&root=1&page=1&num=100";
        Net.Resp r = Net.request(url, null, "https://pan.baidu.com/s/" , h, false);
        if (r == null || r.body == null) throw new Exception("百度网盘拉文件列表无响应");
        JSONObject o = new JSONObject(r.body);
        if (o.optInt("errno", -1) != 0) {
            throw new Exception("百度网盘 errno=" + o.optInt("errno", -1) + "：" + brief(r.body));
        }
        JSONArray items = o.optJSONArray("list");
        List<PanFile> out = new ArrayList<PanFile>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                PanFile f = new PanFile();
                f.id = String.valueOf(it.optLong("fs_id", 0));
                f.name = it.optString("server_filename", "");
                f.size = it.optLong("size", 0);
                f.dir = it.optInt("isdir", 0) == 1;
                out.add(f);
            }
        }
        if (out.isEmpty()) throw new Exception("分享里没有文件（或链接失效）");
        return out;
    }

    private static String baiduDownload(String shareUrl, PanFile f) throws Exception {
        String[] ids = baiduIds(shareUrl);
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie(BAIDU));
        h.put("User-Agent", Net.UA_DESKTOP);
        // 百度直链要 sign 参数（由 wxlist 响应里的 sign 字段提供），
        // 没有 sign 时如实报错，不瞎拼。
        String url = "https://pan.baidu.com/share/download?shareid=" + ids[0]
                + "&uk=" + ids[1] + "&fid_list=%5B" + f.id + "%5D";
        Net.Resp r = Net.request(url, null, "https://pan.baidu.com/s/", h, false);
        if (r == null || r.body == null) throw new Exception("百度网盘取直链无响应");
        JSONObject o = new JSONObject(r.body);
        if (o.optInt("errno", -1) != 0) {
            throw new Exception("百度网盘取直链失败 errno=" + o.optInt("errno", -1)
                    + "（直链接口需要 sign 参数，可能已对第三方关闭）");
        }
        JSONArray dns = o.optJSONArray("dlink");
        if (dns != null && dns.length() > 0) return dns.getString(0);
        throw new Exception("百度网盘未返回直链：" + brief(r.body));
    }

    // ==================== 工具 ====================

    private static String grab(String s, String regex) {
        if (s == null) return "";
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s);
            return m.find() ? m.group(1).trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 错误响应只取前 120 字，避免把整页 HTML 糊到用户脸上 */
    private static String brief(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }
}
