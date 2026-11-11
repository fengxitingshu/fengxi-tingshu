package com.tingshu.app.pan;

import com.tingshu.app.core.DiagLog;
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
        public String path;    // ★ v4.10.0 父目录链（"鬼吹灯/第1卷"），根级 = ""
                               //   前端按它把「文件夹 → 书」分组，文件夹可点开成书
        public String apiPath; // ★ v4.21.12 百度：wxlist 返回的绝对路径（/sharelink…/书名），列子目录要用它
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
            // ★ v4.21.22：对照 baiduwp-php —— dlink 下载只带 User-Agent（netdisk），
            //   把整套网页 Cookie/Referer 一起发给百度 CDN 反而 403
            //   （v4.21.21 用户实测：AudioProbe 直链 HTTP 403 text/plain）。
            //   Cookie 留给 LocalProxy 的 403 自愈第三梯队用。
            h.put("User-Agent", "netdisk");
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

    private static String quarkToken(String pwdId, String pwd, boolean uc) throws Exception {
        Map<String, String> h = quarkHeaders(uc);
        JSONObject body = new JSONObject();
        body.put("pwd_id", pwdId);
        // ★ v4.18.1：passcode 以前写死空串 —— 带提取码的夸克/UC 分享必然拿不到 stoken。
        body.put("passcode", pwd == null ? "" : pwd);
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
        String stoken = quarkToken(pwdId, pwdOf(shareUrl), uc);
        Map<String, String> h = quarkHeaders(uc);
        List<PanFile> out = new ArrayList<PanFile>();
        List<PanFile> first = quarkSharePage(pwdId, stoken, "0", uc);
        for (PanFile f : first) { f.path = ""; out.add(f); }
        // ★ v4.10.0：有声书分享常见「书名 → 卷 → (章节文件夹) → 音频」多层嵌套。
        //   旧版只展平一层，第一层全是文件夹时用户看到一堆点不动的目录
        //   （nAudio=0，连自动入库都不会触发）。现在递归展平到 3 层、
        //   最多 600 项防超大分享，每个文件记下父目录链（path），
        //   前端就能「点文件夹 → 该文件夹下的音频整本进选集」。
        int budget = 600;
        for (PanFile f : first) {
            if (!f.dir) continue;
            if (budget <= 0) break;
            quarkFlatten(pwdId, stoken, f.id, f.name, uc, out, budget, 1);
        }
        if (out.isEmpty()) throw new Exception("分享里没有文件（或链接失效）");
        return out;
    }

    /** 递归拉取文件夹内容（深度 ≤3），子文件记 path；文件夹本身也已在外层列表 */
    private static void quarkFlatten(String pwdId, String stoken, String fid, String path,
                                     boolean uc, List<PanFile> out, int budget, int depth) {
        try {
            List<PanFile> page = quarkSharePage(pwdId, stoken, fid, uc);
            for (PanFile f : page) {
                if (budget <= 0) return;
                budget--;
                f.path = path;
                out.add(f);
                if (f.dir && depth < 3) {
                    quarkFlatten(pwdId, stoken, f.id,
                            path + "/" + f.name, uc, out, budget, depth + 1);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static List<PanFile> quarkSharePage(String pwdId, String stoken,
                                                String pdirFid, boolean uc) throws Exception {
        Map<String, String> h = quarkHeaders(uc);
        List<PanFile> out = new ArrayList<PanFile>();
        String url = quarkBase(uc) + "/share/sharepage/detail?pr=ucpro&fr=pc"
                + "&pwd_id=" + pwdId + "&stoken=" + java.net.URLEncoder.encode(stoken, "UTF-8")
                + "&pdir_fid=" + java.net.URLEncoder.encode(pdirFid, "UTF-8")
                + "&force=0&_page=1&_size=150"
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
        return out;
    }

    /**
     * 夸克/UC 分享文件取直链。
     *
     * <p>★ v4.9.4 关键修复：夸克（2024 起）**没有**公开的分享直链接口 ——
     * 旧代码调的 /file/download 是「自己网盘文件」的下载接口，拿分享 fid
     * 去问必然失败（表现为 mp3/wav 全部播放失败，与格式无关）。
     * 业界（alist/quark-auto-save）的唯一可行路径是三步：
     * 转存到网盘 → 轮询转存任务拿新 fid → 按自己文件取直链，取完即删。
     *
     * <p>需要有效的网页登录 Cookie（转存/下载/删除都要）。
     */
    private static String quarkDownload(String shareUrl, PanFile f, boolean uc) throws Exception {
        String pid = uc ? UC : QUARK;
        if (!configured(pid)) {
            throw new Exception("夸克/UC 播放分享需要登录 Cookie，请在 设置→自定义源→"
                    + (uc ? "UC网盘" : "夸克网盘") + " 里粘贴网页 Cookie");
        }
        String pwdId = quarkPwdId(shareUrl);
        String stoken = quarkToken(pwdId, pwdOf(shareUrl), uc);
        Map<String, String> h = quarkHeaders(uc);
        String referer = hostOf(uc ? UC : QUARK) + "/";

        // ① 转存到网盘根目录（to_pdir_fid="0"）
        JSONObject saveBody = new JSONObject();
        saveBody.put("pwd_id", pwdId);
        saveBody.put("stoken", stoken);
        JSONArray sfids = new JSONArray();   sfids.put(f.id);
        JSONArray stk = new JSONArray();     stk.put(f.token == null ? "" : f.token);
        saveBody.put("fids", sfids);
        saveBody.put("fids_token", stk);
        saveBody.put("to_pdir_fid", "0");
        Net.Resp r = Net.request(quarkBase(uc) + "/share/sharepage/save?pr=ucpro&fr=pc",
                saveBody.toString(), referer, h, false);
        if (r == null || r.body == null) throw new Exception("夸克/UC 转存无响应（Cookie 失效？）");
        JSONObject saveData = new JSONObject(r.body).optJSONObject("data");
        String taskId = saveData == null ? "" : saveData.optString("task_id", "");
        if (taskId.length() == 0) {
            throw new Exception("夸克/UC 转存失败：" + brief(r.body));
        }

        // ② 轮询转存任务（一般 1~3 秒），拿转存后的 fid
        String newFid = "";
        for (int i = 0; i < 12 && newFid.length() == 0; i++) {
            Thread.sleep(500);
            Net.Resp tr = Net.request(quarkBase(uc) + "/task?pr=ucpro&fr=pc"
                    + "&task_id=" + java.net.URLEncoder.encode(taskId, "UTF-8")
                    + "&retry_index=" + i, null, referer, h, false);
            if (tr == null || tr.body == null) continue;
            try {
                JSONObject td = new JSONObject(tr.body).optJSONObject("data");
                JSONObject saveAs = td == null ? null : td.optJSONObject("save_as");
                JSONArray topFids = saveAs == null ? null : saveAs.optJSONArray("save_as_top_fids");
                if (topFids != null && topFids.length() > 0 && topFids.optJSONObject(0) != null) {
                    newFid = topFids.optJSONObject(0).optString("fid", "");
                }
            } catch (Throwable ignored) {}
        }
        if (newFid.length() == 0) {
            throw new Exception("夸克/UC 转存任务超时（网盘空间不足？）");
        }

        // ③ 按自己文件取直链；④ 取到（或失败）后都把转存文件删掉，不占空间
        try {
            JSONObject dlBody = new JSONObject();
            JSONArray dfids = new JSONArray();
            dfids.put(newFid);
            dlBody.put("fids", dfids);
            // ★ v4.9.8：转存刚落盘的文件，夸克要数秒~十几秒才生成签名直链 ——
            //   太早调 /file/download 会返回 status=200/code=0 但
            //   data[0] 里只有 fid（status=1），download_url 为空。
            //   真机日志已实证。先等 1 秒再开始，最多重试 8 次 × 1.5s（约 13s 窗口）。
            Net.Resp dr = null;
            String url = "";
            String lastBody = "";
            String keys = "";
            for (int i = 0; i < 8 && url.length() == 0; i++) {
                Thread.sleep(1500);
                dr = Net.request(quarkBase(uc) + "/file/download?pr=ucpro&fr=pc",
                        dlBody.toString(), referer, h, false);
                if (dr == null || dr.body == null) continue;
                lastBody = dr.body;
                try {
                    JSONObject root = new JSONObject(dr.body);
                    JSONArray darr = root.optJSONArray("data");
                    if (darr != null && darr.length() > 0 && darr.optJSONObject(0) != null) {
                        JSONObject it = darr.optJSONObject(0);
                        url = it.optString("download_url", "");
                        if (url.length() == 0 && keys.length() == 0) {
                            StringBuilder kb = new StringBuilder();
                            java.util.Iterator<String> it2 = it.keys();
                            while (it2.hasNext() && kb.length() < 100) {
                                if (kb.length() > 0) kb.append(',');
                                kb.append(it2.next());
                            }
                            keys = kb.toString();
                        }
                    } else {
                        JSONObject data = root.optJSONObject("data");
                        url = data == null ? "" : data.optString("download_url", "");
                    }
                } catch (Throwable ignored) {}
            }
            if (url.length() == 0) {
                throw new Exception("夸克/UC 未返回直链（已重试 8 次/约13秒）"
                        + (keys.length() > 0 ? "，返回字段[" + keys + "]" : "")
                        + "：" + brief(lastBody));
            }
            return url;
        } finally {
            try {
                JSONObject delBody = new JSONObject();
                delBody.put("action", "delete");
                JSONArray dl = new JSONArray();
                dl.put(newFid);
                delBody.put("filelist", dl);
                delBody.put("exclude_fids", new JSONArray());
                Net.request(quarkBase(uc) + "/file/delete?pr=ucpro&fr=pc",
                        delBody.toString(), referer, h, false);
            } catch (Throwable ignored) {}
        }
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
        // ★ v4.18.1：115 的访问码参数名是 password / receive_code，旧实现完全没带
        String pwd = pwdOf(shareUrl);
        String url = "https://webapi.115.com/share/snap?share_code=" + code
                + (pwd.length() > 0 ? "&receive_code=" + pwd : "");
        Net.Resp r = Net.request(url, null, "https://115.com/", p115Headers(), false);
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
        h.put("Referer", "https://cloud.189.cn/");

        // ★ v4.18.1：先走分享信息接口（getShareInfoByCodeV2）—— 比解析 HTML 稳，
        //   且能带访问码（accessCode）。
        String pwd = pwdOf(shareUrl);
        String api = "https://cloud.189.cn/api/open/share/getShareInfoByCodeV2.action"
                + "?shareCode=" + code + "&noCache=" + System.currentTimeMillis()
                + (pwd.length() > 0 ? "&accessCode=" + pwd : "");
        try {
            Net.Resp ar = Net.request(api, null, "https://cloud.189.cn/", h, false);
            if (ar != null && ar.body != null && ar.body.length() > 0) {
                JSONObject o = new JSONObject(ar.body);
                String shareId = o.optString("shareId", "");
                String fileId = o.optString("fileId", "");
                if (shareId.length() > 0 && fileId.length() > 0) {
                    return new String[]{shareId, fileId};
                }
                int rc = o.optInt("res_code", o.optInt("errorCode", 0));
                if (rc != 0) {
                    throw new Exception("天翼云盘拒绝："
                            + o.optString("res_message", o.optString("errorMsg", "")) +
                            "（访问码错误 / Cookie 失效？）");
                }
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().indexOf("天翼云盘拒绝") >= 0) throw e;
            // 接口失败 → 退回 HTML 解析（老路）
        }

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
        String shareId = ids[0], rootId = ids[1];
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie(TIANYI));
        h.put("User-Agent", Net.UA_DESKTOP);
        h.put("Referer", "https://cloud.189.cn/");
        List<PanFile> out = new ArrayList<PanFile>();
        // ★ v4.18.1：旧实现只塞一个假文件「天翼分享」就返回 —— 用户点开当然播不了。
        //   现在真列目录（listShareDir），再展平一层子目录。
        tianyiPage(shareId, rootId, "", h, out);
        List<PanFile> dirs = new ArrayList<PanFile>();
        for (PanFile f : out) if (f.dir) dirs.add(f);
        for (PanFile d : dirs) {
            try {
                tianyiPage(shareId, d.id, (d.path.length() == 0 ? "" : d.path + "/") + d.name, h, out);
            } catch (Throwable ignored) {}
        }
        if (out.isEmpty()) throw new Exception("分享里没有文件（或链接失效）");
        return out;
    }

    /** 天翼列一页目录 */
    private static void tianyiPage(String shareId, String fileId, String path,
                                   Map<String, String> h, List<PanFile> out) throws Exception {
        String url = "https://cloud.189.cn/api/open/share/listShareDir.action"
                + "?pageNum=1&pageSize=120&fileId=" + java.net.URLEncoder.encode(fileId, "UTF-8")
                + "&shareId=" + java.net.URLEncoder.encode(shareId, "UTF-8")
                + "&shareMode=1&isVersion=0&noCache=" + System.currentTimeMillis();
        Net.Resp r = Net.request(url, null, "https://cloud.189.cn/", h, false);
        if (r == null || r.body == null) throw new Exception("天翼云盘拉文件列表无响应");
        JSONObject o = new JSONObject(r.body);
        JSONArray items = o.optJSONArray("fileList");
        if (items == null) {
            int rc = o.optInt("res_code", 0);
            if (rc != 0) throw new Exception("天翼云盘列目录失败："
                    + o.optString("res_message", brief(r.body)));
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            PanFile f = new PanFile();
            f.id = it.optString("id", "");
            f.name = it.optString("name", "");
            f.size = it.optLong("size", 0);
            f.dir = it.optBoolean("isFolder", false);
            f.path = path;
            out.add(f);
        }
    }

    private static String tianyiDownload(String shareUrl, PanFile f) throws Exception {
        String[] ids = tianyiIds(shareUrl);
        if (!configured(TIANYI)) throw new Exception("天翼云盘下载需要登录 Cookie");
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie(TIANYI));
        h.put("User-Agent", Net.UA_DESKTOP);
        // ★ v4.18.1：旧实现固定用「根 fileId」取直链 —— 选集后全是同一个链接。
        //   现在优先走 getFileDownloadUrl（按选集 fileId），再退回 openFile.do。
        String fid = (f != null && f.id != null && f.id.length() > 0) ? f.id : ids[1];
        try {
            String api = "https://cloud.189.cn/api/open/file/getFileDownloadUrl.action"
                    + "?fileId=" + java.net.URLEncoder.encode(fid, "UTF-8")
                    + "&shareId=" + java.net.URLEncoder.encode(ids[0], "UTF-8")
                    + "&noCache=" + System.currentTimeMillis();
            Net.Resp ar = Net.request(api, null, "https://cloud.189.cn/", h, false);
            if (ar != null && ar.body != null) {
                JSONObject o = new JSONObject(ar.body);
                String u = o.optString("fileDownloadUrl", "");
                if (u.length() == 0) u = o.optString("downloadUrl", "");
                if (u.length() > 0) return u;
            }
        } catch (Throwable ignored) {}
        // openFile 302 到真实直链；不自动跟随，取 Location
        Net.Resp r = Net.request(
                "https://cloud.189.cn/api/openFile.do?fileId=" + fid
                        + "&shareId=" + ids[0],
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

    /**
     * ★ v4.21.12：百度 wxlist 解析上下文。
     *
     * <p>列表/下载各现拉一遍 wxlist（shareid / uk / seckey 有时效，不复用）；
     * {@link #randsk} 是 seckey 解码产物，sharedownload 必带。
     */
    private static final class BaiduShare {
        String surl = "", pwd = "", shareid = "", uk = "", sign = "", timestamp = "";
        String randsk = ""; // v4.21.12：wxlist 返回的 seckey 解码而来，取直链必带
    }

    /**
     * v4.21.11：调 /share/tplconfig 拿现行有效的 sign/timestamp。
     * 百度网页版现在的流程是「页面加载 → JS 调 tplconfig 取限时签名（约 5 分钟有效）」，
     * 页面 HTML 里的 sign 经常为空或已过期——用过期签名调 share/list 会被报成
     * errno=-7「分享已取消/文件已删除」。UA 必须 netdisk 开头，否则 errno=2。
     */
    private static void baiduSign(BaiduShare bs) {
        try {
            Map<String, String> h = baiduHeaders();
            h.put("User-Agent", "netdisk;pan.baidu.com");
            String url = "https://pan.baidu.com/share/tplconfig?surl=" + bs.surl
                    + "&fields=sign,timestamp&channel=chunlei&web=1&app_id=250528&clienttype=0";
            Net.Resp r = Net.request(url, null,
                    "https://pan.baidu.com/s/" + bs.surl, h, false);
            if (r == null || r.body == null) return;
            JSONObject o = new JSONObject(r.body);
            if (o.optInt("errno", -1) != 0) return;
            JSONObject d = o.optJSONObject("data");
            if (d == null) return;
            String s = d.optString("sign", "");
            String t = d.optString("timestamp", "");
            if (s.length() > 0) bs.sign = s;
            if (t.length() > 0) bs.timestamp = t;
        } catch (Throwable ignored) {
        }
    }

    private static Map<String, String> baiduHeaders() {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie(BAIDU));
        h.put("User-Agent", Net.UA_DESKTOP);
        h.put("Referer", "https://pan.baidu.com/");
        return h;
    }

    /** 百度 errno → 人话（其余原样带出，便于排查） */
    private static String baiduErr(int errno, String body) {
        String t;
        if (errno == -7) t = "分享的文件已被删除或分享已取消";
        else if (errno == -9) t = "提取码错误";
        else if (errno == 105) t = "这个分享需要提取码（请在链接末尾加 ?pwd=xxxx）或需要验证码";
        else if (errno == -130) t = "分享链接已失效，或百度接口要求验证（可换公开分享 / 重新复制链接）";
        else if (errno == 2) t = "百度接口参数被拒绝（接口可能已改版）";
        else if (errno == 12 || errno == -6) t = "百度登录状态已失效（百度接口拒绝当前 Cookie），"
                + "请到「源管理 → 百度网盘」重新登录/粘贴 Cookie 后重试";
        else t = "百度网盘 errno=" + errno;
        return t + "（" + brief(body) + "）";
    }

    /**
     * v4.21.12：只拆链接拿 surl/pwd + 检查登录态（列表/下载共用）。
     * wxlist 的 shorturl 必须带 1 前缀，链接里天然带着。
     */
    private static BaiduShare baiduShare(String shareUrl) throws Exception {
        String u = shareUrl == null ? "" : shareUrl;
        BaiduShare bs = new BaiduShare();
        // ★ v4.21.28：支持三种分享链接形态——
        //   ① pan.baidu.com/s/1xxxx        （网页版，surl 天然带 1 前缀）
        //   ② pan.baidu.com/share/init?surl=xxxx （网页初始化页，surl 不带 1）
        //   ③ pan.baidu.com/wap/init?surl=xxxx   （手机 WAP 版，微信点开常见，surl 不带 1）
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("pan\\.baidu\\.com/s/([a-zA-Z0-9_-]+)")
                .matcher(u);
        if (m.find()) {
            bs.surl = m.group(1);
        } else {
            m = java.util.regex.Pattern
                    .compile("pan\\.baidu\\.com/(?:share/init|wap/init)\\?surl=([a-zA-Z0-9_-]+)")
                    .matcher(u);
            if (!m.find()) {
                throw new Exception("链接不像百度网盘分享（支持 pan.baidu.com/s/… 、"
                        + "share/init?surl=… 和 wap/init?surl=… 三种）");
            }
            // wxlist 的 shorturl 必须带 1 前缀；surl= 参数里没有，补上
            bs.surl = m.group(1).startsWith("1") ? m.group(1) : "1" + m.group(1);
        }
        bs.pwd = pwdOf(u);
        if (!configured(BAIDU)) throw new Exception("百度网盘解析需要登录，请先点「网页登录」");
        return bs;
    }

    /**
     * v4.21.12：现行列表接口 POST /share/wxlist（现行网页版/开源实现同款）。
     * 表单：shorturl（带 1 前缀）/ dir / root（1=根目录）/ pwd / page / num / order。
     * 只带 BDUSS，UA 用 netdisk，Referer 用盘内首页。返回 data
     * （list + shareid + uk + seckey），完全不再依赖分享页 HTML——
     * 旧流程依赖页面 yunData，百度对未登录直接 302 错误页、sign 又常过期，
     * 列目录报 errno=-7「分享已取消」的假报。提取码也不再走 verify/BDCLND。
     */
    private static JSONObject baiduWxlist(BaiduShare bs, String dir, boolean root) throws Exception {
        String url = "https://pan.baidu.com/share/wxlist?channel=weixin&version=2.2.2&clienttype=25&web=1";
        String body = "&shorturl=" + bs.surl
                + "&dir=" + java.net.URLEncoder.encode(dir == null ? "" : dir, "UTF-8")
                + "&root=" + (root ? "1" : "0")
                + "&pwd=" + java.net.URLEncoder.encode(bs.pwd, "UTF-8")
                + "&page=1&num=1000&order=name";
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", "netdisk");
        h.put("Referer", "https://pan.baidu.com/disk/home");
        String bd = grab(cookie(BAIDU), "(BDUSS=[^;\\s]+)");
        h.put("Cookie", bd.length() > 0 ? bd : cookie(BAIDU));
        // ★ v4.21.19：传输层失败（无响应/非 JSON）自动重试 3 次——播放每次都
        //   要现取 wxlist，偶发超时就「拉文件列表无响应」太伤；服务端有应答
        //   的业务错误（提取码错等）不重试，直接抛
        Net.Resp r = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            r = Net.request(url, body, "https://pan.baidu.com/disk/home", h, false);
            boolean ok = r != null && r.body != null && r.body.trim().startsWith("{");
            if (ok) break;
            if (attempt < 2) {
                try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException ie) {}
            }
        }
        if (r == null || r.body == null) {
            throw new Exception("百度网盘拉文件列表无响应（已重试 3 次）：请检查网络后重试");
        }
        JSONObject o;
        try {
            o = new JSONObject(r.body);
        } catch (Throwable t) {
            throw new Exception("百度返回了非 JSON（可能被风控）：" + brief(r.body));
        }
        int errno = o.optInt("errno", -1);
        if (errno != 0) throw new Exception(baiduListErr(errno, r.body) + "（errno=" + errno + "）");
        JSONObject data = o.optJSONObject("data");
        if (data == null) throw new Exception("百度列表返回缺 data：" + brief(r.body));
        // ★ v4.21.20：功能诊断插桩——每次列目录都留痕，诊断页可回放全过程
        JSONArray _lst = data.optJSONArray("list");
        DiagLog.add("[baidu] wxlist OK " + (root ? "根目录" : dir)
                + " → " + (_lst == null ? 0 : _lst.length()) + " 项");
        return data;
    }

    /** wxlist 错误码 → 人话（errtype 的 mispw_* 是提取码错误） */
    private static String baiduListErr(int errno, String body) {
        if (body != null && body.contains("mispw")) return "提取码错误，请检查链接末尾的 ?pwd=xxxx";
        switch (errno) {
            case 5: return "链接不存在或提取码错误（带提取码的分享请在链接末尾加 ?pwd=xxxx）";
            case 3: return "分享内容因违规无法访问";
            case 9: return "提取码错误";
            case 10: return "分享已过期";
            case 2: return "参数被拒绝（短链格式或请求头问题）";
            case 8001:
            case 9013: return "该百度账号被限制，请换账号或检查账号状态";
            case 9019: return "账号 Cookie 状态异常（被限制或已过期），请重新「网页登录」。另：普通账号无法直接取分享直链（仅 SVIP），App 会自动转存到自己网盘后取链";
            default: return "百度接口错误";
        }
    }

    /** seckey → randsk：base64url 变体字符还原（-→+ ~→= _→/） */
    private static String decodeSceKey(String seckey) {
        if (seckey == null) return "";
        return seckey.replace("-", "+").replace("~", "=").replace("_", "/");
    }

    /** v4.21.14：自然排序比较（数字段按数值比，"第2章" < "第10章"） */
    private static int natCmp(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String ta = a.substring(si, i).replaceFirst("^0+", "");
                String tb = b.substring(sj, j).replaceFirst("^0+", "");
                int c;
                if (ta.length() != tb.length()) c = Integer.compare(ta.length(), tb.length());
                else c = ta.compareTo(tb);
                if (c != 0) return c;
            } else {
                if (ca != cb) return ca - cb;
                i++;
                j++;
            }
        }
        return a.length() - b.length();
    }

    private static List<PanFile> baiduList(String shareUrl) throws Exception {
        BaiduShare bs = baiduShare(shareUrl);
        List<PanFile> out = new ArrayList<PanFile>();
        // v4.21.12：递归展平到第 3 层（书 → 卷/季 → 集）。有声书常见三层结构，
        // 旧实现只展开一层，「卷」里没有直接音频文件时前端只能报
        // 「下没有音频文件（嵌套超过 3 层？）」。请求预算 40 次，防超深目录刷爆。
        int[] budget = {40};
        baiduExpand(bs, "", true, "", 0, out, budget);
        if (out.isEmpty()) throw new Exception("分享里没有文件（或链接失效）");
        return out;
    }

    /**
     * 递归列目录：depth 0=根 → 1=书 → 2=卷（再往下一般是纯文件，不再展开）。
     * dir 为 wxlist 的绝对路径（根传 ""，root=1）；displayPath 是给前端的分组链。
     */
    private static void baiduExpand(BaiduShare bs, String dir, boolean root,
                                    String displayPath, int depth,
                                    List<PanFile> out, int[] budget) {
        if (depth > 2 || budget[0] <= 0) return;
        budget[0]--;
        try {
            JSONObject data = baiduWxlist(bs, dir, root);
            if (depth == 0) {
                // shareid / uk / seckey 只在根列表里有，顺路存下（下载时还会现取）
                bs.shareid = data.optString("shareid", "");
                bs.uk = data.optString("uk", "");
                bs.randsk = decodeSceKey(data.optString("seckey", ""));
            }
            List<PanFile> level = new ArrayList<PanFile>();
            baiduItems(data, displayPath, level);
            // v4.21.14：每层目录内自然排序（数字按数值比，1.mp3 < 2.mp3 < 10.mp3），
            // 目录排前面。旧版用 order=time 直接是修改时间倒序（999.mp3 在最前）。
            java.util.Collections.sort(level, new java.util.Comparator<PanFile>() {
                @Override public int compare(PanFile a, PanFile b) {
                    if (a.dir != b.dir) return a.dir ? -1 : 1;
                    return natCmp(a.name == null ? "" : a.name, b.name == null ? "" : b.name);
                }
            });
            out.addAll(level);
            for (PanFile f : level) {
                if (!f.dir || f.apiPath == null || f.apiPath.length() == 0) continue;
                baiduExpand(bs, f.apiPath, false,
                        (displayPath.length() == 0 ? "" : displayPath + "/") + f.name,
                        depth + 1, out, budget);
            }
        } catch (Throwable ignored) {
            // 单个目录失败不拖垮整体（与旧版一层展平的行为一致）
        }
    }

    /** wxlist 的 data.list → PanFile（path 为给前端的分组链） */
    private static void baiduItems(JSONObject data, String path, List<PanFile> out) {
        JSONArray items = data.optJSONArray("list");
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            PanFile f = new PanFile();
            f.id = String.valueOf(it.optLong("fs_id", 0));
            f.name = it.optString("server_filename", "");
            f.size = it.optLong("size", 0);
            f.dir = it.optInt("isdir", 0) == 1;
            f.path = path;
            f.apiPath = it.optString("path", "");
            out.add(f);
        }
    }

    private static String baiduDownload(String shareUrl, PanFile f) throws Exception {
        BaiduShare bs = baiduShare(shareUrl);
        // ① 现取现用：wxlist 根目录（shareid/uk/seckey 都有时效，不能存着复用）
        JSONObject root = baiduWxlist(bs, "", true);
        bs.shareid = root.optString("shareid", "");
        bs.uk = root.optString("uk", "");
        bs.randsk = decodeSceKey(root.optString("seckey", ""));
        // ② tplconfig 取现行限时签名（约 5 分钟有效；UA 必须 netdisk 开头）
        baiduSign(bs);
        if (bs.sign.length() == 0) throw new Exception("取百度签名失败（tplconfig），请稍后重试");
        // ③ sharedownload：randsk 放 extra.sekey，缺了会报 118/-20 验证码
        String extra = "{\"sekey\":\"" + bs.randsk + "\"}";
        String body = "encrypt=0&extra=" + java.net.URLEncoder.encode(extra, "UTF-8")
                + "&fid_list=" + java.net.URLEncoder.encode("[" + f.id + "]", "UTF-8")
                + "&primaryid=" + bs.shareid
                + "&uk=" + bs.uk
                + "&product=share&type=nolimit";
        String url = "https://pan.baidu.com/api/sharedownload?app_id=250528&channel=chunlei"
                + "&clienttype=12&sign=" + java.net.URLEncoder.encode(bs.sign, "UTF-8")
                + "&timestamp=" + bs.timestamp + "&web=1";
        Map<String, String> h = baiduHeaders();
        h.put("Referer", "https://pan.baidu.com/disk/home");
        Net.Resp r = Net.request(url, body, "https://pan.baidu.com/disk/home", h, false);
        if (r == null || r.body == null) throw new Exception("百度网盘取直链无响应");
        JSONObject o = new JSONObject(r.body);
        int errno = o.optInt("errno", -1);
        if (errno != 0) {
            // v4.21.14：普通账号百度只放行 SVIP 走分享直链（9019 needverify）。
            // 兜底：转存到自己网盘 → 盘内 filemetas 取链（普通账号可用）。
            if (errno == 9019) return baiduSaveAndLink(bs, f);
            throw new Exception(baiduErr(errno, r.body));
        }
        JSONArray arr = o.optJSONArray("dlink");
        if (arr != null && arr.length() > 0) {
            JSONObject it = arr.optJSONObject(0);
            if (it != null) {
                String d = it.optString("dlink", "");
                if (d.length() > 0) return d;
            }
        }
        throw new Exception("百度网盘未返回直链：" + brief(r.body));
    }

    // ==================== 工具 ====================

    /**
     * ★ v4.18.1：从分享链接里提提取码/访问码。
     *
     * <p>各平台参数名不同：百度/阿里/夸克/UC/天翼用 {@code pwd}，
     * 115 用 {@code password}；位置可能出现在 query（?pwd=）或 fragment（#pwd=）。
     * 旧实现各平台要么压根不认提取码（百度）、要么把 passcode 写死空串（夸克/UC），
     * 带码分享必然失败 —— 这里统一收口。
     */
    static String pwdOf(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[?&#](?:pwd|password)=([a-zA-Z0-9]{3,8})")
                .matcher(url);
        return m.find() ? m.group(1) : "";
    }

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
        // ★ v4.9.6：压缩成关键词摘要（状态码 + 业务码），整坨 JSON 在 toast 里没法看
        String t = s.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"status\"\\s*:\\s*(-?\\d+)|\"code\"\\s*:\\s*(-?\\d+)" +
                         "|\"message\"\\s*:\\s*\"([^\"]{0,60})\"")
                .matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (sb.length() > 0) sb.append(' ');
            if (m.group(1) != null) sb.append("status=").append(m.group(1));
            else if (m.group(2) != null) sb.append("code=").append(m.group(2));
            else sb.append("msg=").append(m.group(3));
        }
        String out = sb.length() > 0 ? sb.toString() : t;
        return out.length() > 110 ? out.substring(0, 110) + "…" : out;
    }

    // ==================== 盘内目录浏览 / 扫描（v4.5.0） ====================

    /** 盘内扫描出的一本「书」（一个含音频的文件夹） */
    public static final class DiskBook {
        public String name;
        public List<PanFile> files = new ArrayList<PanFile>();
    }

    private static final String[] AUDIO_EXT = {
            ".mp3", ".m4a", ".m4b", ".flac", ".wav", ".aac", ".ogg", ".wma", ".opus"
    };

    private static boolean isAudio(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        for (String e : AUDIO_EXT) if (n.endsWith(e)) return true;
        return false;
    }

    /** 各平台盘内根目录的 folderId */
    private static String diskRoot(String pid) {
        if (TIANYI.equals(pid)) return "-11";
        if (BAIDU.equals(pid)) return "/";
        return "0";
    }

    /**
     * 列出自己网盘某目录的直接子项（已登录 Cookie）。
     * folderId：夸克/UC/115 为目录 fid（根 0），百度为目录路径（根 "/"），
     * 天翼为 folderId（根 -11）。
     */
    public static List<PanFile> listDir(String pid, String folderId) throws Exception {
        if (QUARK.equals(pid)) return quarkDir(folderId, false);
        if (UC.equals(pid)) return quarkDir(folderId, true);
        if (P115.equals(pid)) return p115Dir(folderId);
        if (BAIDU.equals(pid)) return baiduDir(folderId);
        if (TIANYI.equals(pid)) return tianyiDir(folderId);
        throw new Exception("阿里云盘暂不支持盘内扫描（网页接口有签名风控），请用分享链接");
    }

    /** 盘内文件取下载直链（token：115=pickcode，其余平台不要求） */
    public static String diskDownloadUrl(String pid, PanFile f) throws Exception {
        if (!configured(pid)) throw new Exception(nameOf(pid) + "需要登录，请先「网页登录」");
        if (QUARK.equals(pid) || UC.equals(pid)) return quarkDiskDownload(pid, f, UC.equals(pid));
        if (P115.equals(pid)) return p115Download(f); // pickcode 对盘内文件同样适用
        if (BAIDU.equals(pid)) return baiduDiskDownload(f);
        if (TIANYI.equals(pid)) return tianyiDiskDownload(f);
        throw new Exception("不支持的平台");
    }

    // ---- 夸克 / UC 盘内 ----

    private static List<PanFile> quarkDir(String fid, boolean uc) throws Exception {
        Map<String, String> h = quarkHeaders(uc);
        List<PanFile> out = new ArrayList<PanFile>();
        for (int page = 1; page <= 15; page++) {
            String url = quarkBase(uc) + "/file/sort?pr=ucpro&fr=pc"
                    + "&pdir_fid=" + urlEnc(fid) + "&_page=" + page + "&_size=150"
                    + "&_sort=file_type:asc,file_name:asc";
            Net.Resp r = Net.request(url, null, hostOf(uc ? UC : QUARK) + "/", h, false);
            if (r == null || r.body == null) throw new Exception(nameOf(uc ? UC : QUARK) + "拉目录无响应");
            JSONObject data = new JSONObject(r.body).optJSONObject("data");
            JSONArray items = data == null ? null : data.optJSONArray("list");
            if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                PanFile f = new PanFile();
                f.id = it.optString("fid", "");
                f.name = it.optString("file_name", "");
                f.size = it.optLong("size", 0);
                f.dir = it.optBoolean("dir", false);
                out.add(f);
            }
            if (items.length() < 150) break;
        }
        return out;
    }

    private static String quarkDiskDownload(String pid, PanFile f, boolean uc) throws Exception {
        Map<String, String> h = quarkHeaders(uc);
        JSONObject body = new JSONObject();
        JSONArray fids = new JSONArray();
        fids.put(f.id);
        body.put("fids", fids);
        // ★ v4.9.9 关键修复：盘内书的直链走的是这里（quarkDiskDownload），
        //   之前一直没修 —— 用户扫到的书全是盘内书，点播全撞在这。
        //   与分享路径同一套坑：/file/download 成功时 data 是**数组**
        //   （[{fid,status,…}]，按对象解析必取空），且直链可能延迟生成
        //   （data[0].status=1 时 download_url 为空）→ 数组解析 + 重试 8 次。
        Net.Resp dr = null;
        String url = "";
        String lastBody = "";
        String keys = "";
        for (int i = 0; i < 8 && url.length() == 0; i++) {
            Thread.sleep(1500);
            dr = Net.request(quarkBase(uc) + "/file/download?pr=ucpro&fr=pc",
                    body.toString(), hostOf(uc ? UC : QUARK) + "/", h, false);
            if (dr == null || dr.body == null) continue;
            lastBody = dr.body;
            try {
                JSONObject root = new JSONObject(dr.body);
                JSONArray darr = root.optJSONArray("data");
                if (darr != null && darr.length() > 0 && darr.optJSONObject(0) != null) {
                    JSONObject it = darr.optJSONObject(0);
                    url = it.optString("download_url", "");
                    if (url.length() == 0 && keys.length() == 0) {
                        StringBuilder kb = new StringBuilder();
                        java.util.Iterator<String> it2 = it.keys();
                        while (it2.hasNext() && kb.length() < 100) {
                            if (kb.length() > 0) kb.append(',');
                            kb.append(it2.next());
                        }
                        keys = kb.toString();
                    }
                } else {
                    JSONObject data = root.optJSONObject("data");
                    if (data != null) {
                        url = data.optString("download_url", "");
                        JSONArray arr = data.optJSONArray("list");
                        if (url.length() == 0 && arr != null && arr.length() > 0
                                && arr.optJSONObject(0) != null) {
                            url = arr.optJSONObject(0).optString("download_url", "");
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (url.length() == 0) {
            throw new Exception(nameOf(pid) + "未返回直链（已重试 8 次/约13秒）"
                    + (keys.length() > 0 ? "，返回字段[" + keys + "]" : "")
                    + "：" + brief(lastBody));
        }
        return url;
    }

    // ---- 115 盘内 ----

    private static List<PanFile> p115Dir(String fid) throws Exception {
        if (!configured(P115)) throw new Exception("115 需要登录，请先「网页登录」");
        List<PanFile> out = new ArrayList<PanFile>();
        for (int page = 0; page <= 15; page++) {
            String url = "https://webapi.115.com/files?pid=" + urlEnc(fid)
                    + "&format=json&show_dir=1&limit=200&o=user_ptime&asc=0&aid=1&cid="
                    + urlEnc(fid) + "&o=user_ptime&offset=" + (page * 200);
            Net.Resp r = Net.request(url, null, "https://115.com/", p115Headers(), false);
            if (r == null || r.body == null) throw new Exception("115 拉目录无响应");
            JSONObject o = new JSONObject(r.body);
            if (!o.optBoolean("state", false)) {
                throw new Exception("115 拒绝访问：" + brief(r.body) + "（Cookie 失效？）");
            }
            JSONObject data = o.optJSONObject("data");
            JSONArray items = data == null ? null : data.optJSONArray("list");
            if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                PanFile f = new PanFile();
                f.id = it.optString("fid", it.optString("cid", ""));
                f.name = it.optString("n", "");
                f.size = it.optLong("s", 0);
                f.dir = it.optString("fc", "").equals("1");
                f.token = it.optString("pc", "");
                out.add(f);
            }
            if (items.length() < 200) break;
        }
        return out;
    }

    // ---- 百度盘内 ----

    private static List<PanFile> baiduDir(String path) throws Exception {
        if (!configured(BAIDU)) throw new Exception("百度网盘需要登录，请先「网页登录」");
        List<PanFile> out = new ArrayList<PanFile>();
        // ★ v4.19.0：分页拉全（原 num=200&page=1 只能拿前 200 个，大目录会漏）
        for (int page = 1; page <= 20; page++) {
            Map<String, String> h = new HashMap<String, String>();
            h.put("Cookie", cookie(BAIDU));
            h.put("User-Agent", Net.UA_DESKTOP);
            String q = "dir=" + urlEnc(path)
                    + "&order=name&desc=0&web=1&clienttype=0&num=500&page=" + page;
            Net.Resp r = Net.request("https://pan.baidu.com/api/list?" + q + "&app_id=250528",
                    null, "https://pan.baidu.com/", h, false);
            if (r == null || r.body == null) throw new Exception("百度网盘拉目录无响应");
            JSONObject o = new JSONObject(r.body);
            int errno = o.optInt("errno", -1);
            if (errno != 0) {
                // ★ v4.19.0：-7（目录不存在）/ 2（参数被拒）时去掉 app_id 降级重试一次
                //   —— 部分账号登录态与固定 app_id 不匹配，去掉反而能列
                if (errno == -7 || errno == 2) {
                    r = Net.request("https://pan.baidu.com/api/list?" + q,
                            null, "https://pan.baidu.com/", h, false);
                    if (r != null && r.body != null) {
                        o = new JSONObject(r.body);
                        errno = o.optInt("errno", -1);
                    }
                }
                if (errno != 0) throw new Exception("百度网盘 errno=" + errno + "：" + brief(r.body));
            }
            JSONArray items = o.optJSONArray("list");
            if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                PanFile f = new PanFile();
                f.id = String.valueOf(it.optLong("fs_id", 0));
                f.name = it.optString("server_filename", "");
                f.size = it.optLong("size", 0);
                f.dir = it.optInt("isdir", 0) == 1;
                f.token = it.optString("path", ""); // 百度目录用路径做 folderId
                out.add(f);
            }
            if (items.length() < 500) break;
        }
        return out;
    }

    /**
     * ★ v4.21.15：滚动缓存——已转存到自己网盘的文件路径。
     * 播新一集时把上一批删掉，网盘里始终只留当前 1 集，不占用户容量。
     */
    private static final List<String> PENDING_BAIDU_DEL = new ArrayList<String>();

    /** 取 bdstoken（转存/删除参数完整性；拿不到就空着，多数情况仍可用） */
    private static String baiduBdstoken(Map<String, String> h) {
        try {
            Net.Resp r = Net.request("https://pan.baidu.com/api/gettemplatevariable"
                            + "?app_id=250528&web=1&clienttype=0&fields=%5B%22bdstoken%22%5D",
                    null, "https://pan.baidu.com/disk/home", h, false);
            if (r != null && r.body != null)
                return grab(r.body, "\"bdstoken\"\\s*:\\s*\"([0-9a-f]+)\"");
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * v4.21.14：9019 兜底——把分享文件转存到自己网盘（/apps/枫兮听书），
     * 再走盘内 filemetas 取直链。普通账号 sharedownload 被百度拒之门外
     * （needverify，仅 SVIP），但转存和自己盘内取链都正常可用。
     * <p>v4.21.15：① 修 errno=2——转存表单参数名必须是 fsidlist（此前误写
     * fid_list）；② errno=4（网盘里已有）视为就绪；③ 滚动缓存——转存新集前
     * 把上一集从网盘删掉，用户网盘不因自动转存而堆文件。
     */
    private static String baiduSaveAndLink(BaiduShare bs, PanFile f) throws Exception {
        Map<String, String> h = baiduHeaders();
        h.put("Referer", "https://pan.baidu.com/disk/home");
        String dir = "/apps/枫兮听书";
        String curPath = dir + "/" + f.name;
        String bdst = baiduBdstoken(h);
        // ① 滚动清理：删掉上一集（保留当前这集，防止正在播的流被删断）
        baiduFlushPending(h, bdst, curPath);
        // ② 先查自己盘：同名文件已在 → 直接复用（重播秒出，且不依赖转存响应格式）
        String reuseId = baiduFindInDir(dir, f.name, h);
        if (reuseId.length() > 0 && !"0".equals(reuseId)) {
            PanFile rf = new PanFile();
            rf.id = reuseId;
            return baiduDiskDownload(rf);
        }
        // ③ 目标目录（已存在时 create 报错，忽略）
        try {
            Net.request("https://pan.baidu.com/api/create?a=commit&channel=chunlei"
                            + "&web=1&app_id=250528&clienttype=0"
                            + (bdst.length() > 0 ? "&bdstoken=" + bdst : ""),
                    "path=" + urlEnc(dir) + "&isdir=1&block_list=%5B%5D",
                    "https://pan.baidu.com/disk/home", h, false);
        } catch (Throwable ignored) {}
        // ③ 转存：from=分享者的 uk；sekey 必带；ondup=skip（重播时网盘已有，跳过）
        String turl = "https://pan.baidu.com/share/transfer?shareid=" + bs.shareid
                + "&from=" + bs.uk + "&sekey=" + urlEnc(bs.randsk)
                + "&ondup=skip&channel=chunlei&web=1&app_id=250528&clienttype=0"
                + (bdst.length() > 0 ? "&bdstoken=" + bdst : "");
        Net.Resp tr = Net.request(turl,
                "fsidlist=" + urlEnc("[" + f.id + "]") + "&path=" + urlEnc(dir),
                "https://pan.baidu.com/disk/home", h, false);
        if (tr == null || tr.body == null) throw new Exception("转存到自己网盘无响应");
        JSONObject to;
        try {
            to = new JSONObject(tr.body);
        } catch (Throwable t) {
            throw new Exception("转存返回了非 JSON（可能被风控）：" + brief(tr.body));
        }
        int terr = to.optInt("errno", -1);
        // errno 0=成功 4=网盘里已存在（重播场景）12=文件已存在（带 duplicated 明细），
        // 都算就绪 —— ★ v4.21.20 补 12：ondup=skip 时百度用它报「已有同文件」，
        // 明细在 duplicated.list 里，下面照常提取
        if (terr != 0 && terr != 4 && terr != 12) {
            DiagLog.add("[baidu] 转存被拒 errno=" + terr);
            throw new Exception("自动转存失败（errno=" + terr + "）："
                    + (terr == 12 ? "网盘剩余空间不足或单次转存数量超限，清一点网盘空间再试"
                       : terr == 255 ? "转存数量太多（当天转存次数用完），明天再试"
                       : terr == 2 ? "账号被百度风控、需要验证，或本设备的百度登录态不完整/过期——"
                           + "先在 设置→自定义源→网盘接口 重新「网页登录」；"
                           + "还不行就换一个百度账号，或在该账号的百度网盘 App 里手动保存该分享后用「扫描盘内书」播放"
                       : "账号被百度风控或需要验证；可在百度网盘 App 里手动保存该分享后用「扫描盘内书」播放"));
        }
        DiagLog.add("[baidu] 转存受理 errno=" + terr + "（0/4/12 均视为就绪）");
        // ★ v4.21.20：id 提取不再猜字段名 —— 用户 v4.21.19 截图实证：
        //   duplicated.list 条目只有 path/file_key/size 这套字段，
        //   v4.21.18 只扫 from_fs_id 必然落空。现在 JSON 逐项取
        //   from_fs_id / fs_id / id 三种键名（优先 path 匹配目标文件的条目），
        //   全文正则兜底，见 baiduPickNewId。
        String newId = baiduPickNewId(tr.body, curPath);
        if (newId.length() > 0) {
            DiagLog.add("[baidu] 转存响应提取到新文件 fs_id=" + newId);
        }
        if (newId.length() == 0 || "0".equals(newId)) {
            // ★ v4.21.20：转存刚落盘，盘内目录列表可能还没刷出该文件（最终一致），
            //   旧版查一次不到就放弃 → 「转存响应里没有新文件 id」。
            //   现在隔 1.5 秒回查一次，最多 5 次（约 8 秒窗口）。
            for (int i = 0; i < 5 && (newId.length() == 0 || "0".equals(newId)); i++) {
                try { Thread.sleep(1500); } catch (InterruptedException ie) {}
                newId = baiduFindInDir(dir, f.name, h);
                DiagLog.add("[baidu] 盘内回查第 " + (i + 1) + " 次："
                        + (newId.length() > 0 && !"0".equals(newId)
                           ? "命中 fs_id=" + newId : "未刷出，继续等"));
            }
        }
        if (newId.length() == 0 || "0".equals(newId)) {
            // 仍拿不到：把响应头 700 字符打出来（诊断页也能看到全过程）
            String dump = tr.body.length() > 700 ? tr.body.substring(0, 700) + "…" : tr.body;
            DiagLog.add("[baidu] 转存 id 提取失败，响应片段：" + dump);
            throw new Exception("转存响应里没有新文件 id（转存本身可能已成功，重试一次通常可恢复）：" + dump);
        }
        // ④ 盘内取链（普通账号可用）；当前集已登记在 PENDING（flush 时 keep）
        PanFile nf = new PanFile();
        nf.id = newId;
        return baiduDiskDownload(nf);
    }

    /** 把上一批转存文件从网盘删掉（keep 之外全删；删除失败静默，下次再清） */
    private static void baiduFlushPending(Map<String, String> h, String bdst, String keep) {
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (String p : PENDING_BAIDU_DEL) {
            if (p.equals(keep)) continue;
            if (n++ > 0) sb.append(",");
            sb.append("\"").append(p.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        List<String> old = new ArrayList<String>(PENDING_BAIDU_DEL);
        PENDING_BAIDU_DEL.clear();
        if (keep != null && keep.length() > 0) PENDING_BAIDU_DEL.add(keep);
        if (n == 0) return;
        try {
            Net.request("https://pan.baidu.com/api/filemanager?opera=delete&async=1"
                            + "&web=1&app_id=250528&clienttype=0"
                            + (bdst.length() > 0 ? "&bdstoken=" + bdst : ""),
                    "filelist=" + urlEnc(sb.toString()),
                    "https://pan.baidu.com/disk/home", h, false);
        } catch (Throwable ignored) {
            PENDING_BAIDU_DEL.addAll(old); // 删失败，下次再试
        }
    }

    /**
     * ★ v4.21.20：从转存响应里提取「新落盘文件」的 fs_id。
     *
     * <p>百度转存响应不同分支字段不同：info 数组里是 from_fs_id；
     * duplicated.list（文件已在盘里）条目是 path/file_key/size 那套，
     * id 在 fs_id。这里三路并取：
     * ① JSON 逐项取 from_fs_id / fs_id / id（优先 path 与目标一致的条目）；
     * ② 全文正则兜底 {@code "(?:from_)?fs_id"}（数字/字符串两种形态都认，
     *    一个正则同时覆盖两种键名，不会互相误匹配）。
     */
    private static String baiduPickNewId(String body, String wantPath) {
        if (body == null || body.length() == 0) return "";
        String best = "", any = "";
        try {
            JSONObject root = new JSONObject(body);
            java.util.List<JSONArray> arrs = new ArrayList<JSONArray>();
            JSONArray info = root.optJSONArray("info");
            if (info != null) arrs.add(info);
            JSONObject dup = root.optJSONObject("duplicated");
            if (dup != null) {
                JSONArray dl = dup.optJSONArray("list");
                if (dl != null) arrs.add(dl);
            }
            for (JSONArray arr : arrs) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null) continue;
                    String id = String.valueOf(it.optLong("from_fs_id",
                            it.optLong("fs_id", it.optLong("id", 0))));
                    if ("0".equals(id)) continue;
                    if (any.length() == 0) any = id;
                    String p = it.optString("path", "");
                    if (wantPath != null && wantPath.length() > 0 && wantPath.equals(p)) {
                        best = id;
                        break;
                    }
                }
                if (best.length() > 0) break;
            }
        } catch (Throwable ignored) {}
        if (best.length() > 0) return best;
        if (any.length() > 0) return any;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"(?:from_)?fs_id\"\\s*:\\s*\"?(\\d+)").matcher(body);
        return m.find() ? m.group(1) : "";
    }

    /** 在自己网盘目录里按文件名回查 fs_id（对齐 baiduDir 的已验证参数与降级逻辑） */
    private static String baiduFindInDir(String dir, String name, Map<String, String> h) {
        String q = "dir=" + urlEnc(dir) + "&order=name&desc=0&web=1&clienttype=0&num=500&page=1";
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String url = "https://pan.baidu.com/api/list?" + q
                        + (attempt == 0 ? "&app_id=250528" : "");
                Net.Resp r = Net.request(url, null, "https://pan.baidu.com/", h, false);
                if (r == null || r.body == null) continue;
                JSONObject o = new JSONObject(r.body);
                if (o.optInt("errno", -1) != 0) continue; // 第二次去掉 app_id 再试
                JSONArray list = o.optJSONArray("list");
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject it = list.optJSONObject(i);
                        if (it != null && name.equals(it.optString("server_filename", "")))
                            return String.valueOf(it.optLong("fs_id", 0));
                    }
                }
                return ""; // 目录列到了但没有同名文件（转存可能还没落盘）
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static String baiduDiskDownload(PanFile f) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie(BAIDU));
        h.put("User-Agent", Net.UA_DESKTOP);
        String url = "https://pan.baidu.com/api/filemetas?fsids=%5B" + f.id + "%5D"
                + "&dlink=1&web=1&clienttype=0&app_id=250528";
        // ★ v4.21.19：传输层失败重试（同 wxlist）
        Net.Resp r = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            r = Net.request(url, null, "https://pan.baidu.com/", h, false);
            boolean ok = r != null && r.body != null && r.body.trim().startsWith("{");
            if (ok) break;
            if (attempt < 2) {
                try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException ie) {}
            }
        }
        if (r == null || r.body == null) throw new Exception("百度网盘取直链无响应（已重试 3 次）");
        JSONObject o = new JSONObject(r.body);
        if (o.optInt("errno", -1) != 0) {
            // v4.21.35：走 baiduErr 人话映射 —— errno=-6（身份验证失败）是百度
            // 接口拒绝了当前 Cookie（登录态过期），不是 App 的链路问题
            int en = o.optInt("errno", -1);
            throw new Exception(baiduErr(en, r.body) + "（errno=" + en + "）");
        }
        // ★ v4.21.21：响应数组键名是 info 不是 list！此前读 "list" 永远为空，
        //   导致「errno=0 却报未返回直链」（功能诊断实测锁定的真凶）。
        //   两个键名都认，防百度换版。
        JSONArray list = o.optJSONArray("info");
        if (list == null) list = o.optJSONArray("list");
        if (list != null && list.length() > 0 && list.optJSONObject(0) != null) {
            JSONObject it = list.optJSONObject(0);
            String d = it.optString("dlink", "");
            String path = it.optString("path", "");
            if (d.length() > 0) {
                // ★ v4.21.27：姿势梯队升级（对照 OpenList/aria2 生态的成熟做法）。
                //   dlink 有时效（几分钟~几十分钟），所以每次播放都现取；
                //   CDN 的拒法各有含义，全部试完给「人话结论」：
                String[] body = new String[1];
                Map<String, String> ph = new HashMap<String, String>();
                ph.put("User-Agent", "netdisk");

                // ① dlink + UA netdisk（baiduwp/OpenList 官方姿势）
                body[0] = null;
                int c1 = probeLink(d, ph, body);
                DiagLog.add("[baidu] 姿势① dlink+netdisk → " + probeVerdict(c1, body[0]));
                if (c1 >= 200 && c1 < 400) return d;

                // ② dlink + UA netdisk;pan.baidu.com
                Map<String, String> ph2 = new HashMap<String, String>();
                ph2.put("User-Agent", "netdisk;pan.baidu.com");
                body[0] = null;
                int c2 = probeLink(d, ph2, body);
                DiagLog.add("[baidu] 姿势② dlink+netdisk;pan.baidu.com → " + probeVerdict(c2, body[0]));
                if (c2 >= 200 && c2 < 400) return d;

                // ③ filemetas 用 origin=dlna 现取一条 dlna 版 dlink
                try {
                    Map<String, String> h2 = new HashMap<String, String>();
                    h2.put("Cookie", cookie(BAIDU));
                    h2.put("User-Agent", Net.UA_DESKTOP);
                    String u2 = "https://pan.baidu.com/api/filemetas?fsids=%5B" + f.id + "%5D"
                            + "&dlink=1&origin=dlna&web=1&clienttype=0&app_id=250528";
                    Net.Resp r2 = Net.request(u2, null, "https://pan.baidu.com/", h2, false);
                    if (r2 != null && r2.body != null && r2.body.trim().startsWith("{")) {
                        JSONObject o2 = new JSONObject(r2.body);
                        JSONArray l2 = o2.optJSONArray("info");
                        String dd = l2 != null && l2.length() > 0
                                && l2.optJSONObject(0) != null
                                ? l2.optJSONObject(0).optString("dlink", "") : "";
                        if (dd.length() > 0) {
                            body[0] = null;
                            int c3 = probeLink(dd, ph, body);
                            DiagLog.add("[baidu] 姿势③ dlna版dlink → " + probeVerdict(c3, body[0]));
                            if (c3 >= 200 && c3 < 400) return dd;
                        } else {
                            DiagLog.add("[baidu] 姿势③ dlna版dlink → filemetas 未给 dlink："
                                    + brief(r2.body));
                        }
                    }
                } catch (Throwable t2) {
                    DiagLog.add("[baidu] 姿势③ dlna版dlink → 请求异常：" + t2);
                }

                // ④ dlink + origin=dlna 参数（电视盒生态老姿势）
                String d4 = d + (d.contains("?") ? "&" : "?") + "origin=dlna";
                body[0] = null;
                int c4 = probeLink(d4, ph, body);
                DiagLog.add("[baidu] 姿势④ dlink+origin=dlna → " + probeVerdict(c4, body[0]));
                if (c4 >= 200 && c4 < 400) return d4;

                // ⑤ PCS 按 path 直下（自有文件老接口，BDUSS 即可）
                if (path.length() > 0 && configured(BAIDU)) {
                    Map<String, String> ph5 = new HashMap<String, String>();
                    ph5.put("User-Agent", "netdisk");
                    ph5.put("Cookie", cookie(BAIDU));
                    String purl = "https://d.pcs.baidu.com/rest/2.0/pcs/file"
                            + "?app_id=250528&method=download&path=" + urlEnc(path);
                    body[0] = null;
                    int c5 = probeLink(purl, ph5, body);
                    DiagLog.add("[baidu] 姿势⑤ PCS按path直下 → " + probeVerdict(c5, body[0]));
                    if (c5 >= 200 && c5 < 400) return purl;
                }
                throw new Exception(probeSummary(c1, c2, c4, body[0]));
            }
            // dlink 字段缺失时把条目打出来看实际键名
            DiagLog.add("[baidu] filemetas 条目无 dlink：" + brief(it.toString()));
        }
        DiagLog.add("[baidu] filemetas 未返回 dlink：" + brief(r.body));
        throw new Exception("百度网盘未返回直链：" + brief(r.body));
    }

    /**
     * ★ v4.21.23：直链可用性探测（GET Range: bytes=0-1，只拉 2 字节）。
     * 返回 HTTP 状态码（-1=连不上）；响应/错误体前 240 字符经 bodyOut[0] 带回，
     * 留给诊断日志 —— 31326/31329/112 各代表不同的拒因，一眼定位。
     */
    private static int probeLink(String url, Map<String, String> headers, String[] bodyOut) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Range", "bytes=0-1");
            conn.setRequestProperty("Accept", "*/*");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null || e.getValue().length() == 0) continue;
                    try { conn.setRequestProperty(e.getKey(), e.getValue()); } catch (Throwable ignored) {}
                }
            }
            int code = conn.getResponseCode();
            if (bodyOut != null && bodyOut.length > 0) {
                java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (in != null) {
                    byte[] b = new byte[240];
                    int n = in.read(b);
                    if (n > 0) bodyOut[0] = new String(b, 0, n, "UTF-8");
                    try { in.close(); } catch (Throwable ignored) {}
                }
            }
            return code;
        } catch (Throwable t) {
            // 连接被掐/超时：把异常类型带回（Connection reset / timeout / EOF）
            if (bodyOut != null && bodyOut.length > 0) {
                bodyOut[0] = "异常:" + t.getClass().getSimpleName();
            }
            return -1;
        } finally {
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** 探测结果翻译成一行人话（诊断日志用） */
    private static String probeVerdict(int code, String body) {
        if (code >= 200 && code < 400) return "HTTP " + code + " ✓ 可用";
        String b = body == null ? "" : body;
        String why = baiduCdnErr(b);
        if (code == -1) return "被掐断（" + b + "）——百度 CDN 连响应都不给，典型风控";
        return "HTTP " + code + (why.length() > 0 ? "（" + why + "）" : "") + " " + b;
    }

    /** 百度 CDN 错误码翻译 */
    private static String baiduCdnErr(String body) {
        if (body == null) return "";
        if (body.contains("31329") || body.contains("31119"))
            return "31329/31119 账号命中黑名单（illeage dlna），换网络无用，等几小时或换账号";
        if (body.contains("31326")) return "31326 非法链接（签名/UA 不对）";
        if (body.contains("112")) return "112 链接过期";
        if (body.contains("-6")) return "-6 身份验证失败";
        return "";
    }

    /** 全姿势失败后的结论 */
    private static String probeSummary(int c1, int c2, int c4, String lastBody) {
        String s = "百度直链五种姿势均被拒（" + c1 + "/" + c2 + "/" + c4 + "）。";
        String why = baiduCdnErr(lastBody);
        if (why.length() > 0) s += why + "。";
        else if (c1 == -1 || c2 == -1 || c4 == -1)
            s += "CDN 直接掐断 TCP（下载太频繁触发风控）。";
        s += "建议：隔几小时再试、换网络环境，或在百度网盘 App 里手动下载验证账号状态";
        return s;
    }

    // ==================== 功能诊断（★ v4.21.20） ====================

    /**
     * 功能诊断：各网盘登录态。
     * 百度做在线校验（gettemplatevariable 的 bdstoken 接口，登录态有效才有值）；
     * 其余平台只报配置状态，用到时再补各自的轻量校验。
     * 行首 ✓ / ✗ 供前端判定该块整体通过与否。
     */
    public static java.util.List<String> diagPanLogin() {
        java.util.List<String> out = new ArrayList<String>();
        for (String pid : ALL) {
            String tag = nameOf(pid);
            if (!configured(pid)) {
                out.add("· " + tag + "：未配置（用到时在 设置→自定义源→网盘接口 网页登录）");
                continue;
            }
            if (BAIDU.equals(pid)) {
                String ck = cookie(BAIDU);
                String bduss = grab(ck, "(BDUSS=[^;\\s]+)");
                out.add("· 百度网盘 Cookie " + ck.length() + " 字符"
                        + (bduss.length() > 0 ? "（含 BDUSS）" : "（⚠ 不含 BDUSS，登录不完整）"));
                String bdst = baiduBdstoken(baiduHeaders());
                if (bdst.length() > 0) {
                    out.add("✓ 百度网盘登录态有效（bdstoken 在线校验通过）");
                } else {
                    out.add("✗ 百度网盘登录态校验失败（Cookie 失效或被风控，请重新「网页登录」）");
                }
            } else {
                out.add("· " + tag + "：已配置 Cookie（" + cookie(pid).length()
                        + " 字符，未做在线校验）");
            }
        }
        return out;
    }

    /**
     * 功能诊断：自动转存目录（/apps/枫兮听书）体检。
     * 列目录看残留文件（滚动缓存正常只留 1 集），再拿目录里第一个文件
     * 实测 filemetas 取链接口（普通账号播放的关键一环）。
     */
    public static java.util.List<String> diagBaiduSave() {
        java.util.List<String> out = new ArrayList<String>();
        if (!configured(BAIDU)) {
            out.add("✗ 百度网盘未登录，转存链路不可用（先「网页登录」）");
            return out;
        }
        String dir = "/apps/枫兮听书";
        Map<String, String> h = baiduHeaders();
        h.put("Referer", "https://pan.baidu.com/disk/home");
        String q = "dir=" + urlEnc(dir) + "&order=name&desc=0&web=1&clienttype=0&num=100&page=1";
        Net.Resp r = null;
        try {
            r = Net.request("https://pan.baidu.com/api/list?" + q + "&app_id=250528",
                    null, "https://pan.baidu.com/", h, false);
        } catch (Throwable t) {
            out.add("✗ 目录请求异常：" + t);
            return out;
        }
        if (r == null || r.body == null) {
            out.add("✗ 转存目录拉取无响应（网络问题）");
            return out;
        }
        JSONObject o;
        try {
            o = new JSONObject(r.body);
        } catch (Throwable t) {
            out.add("✗ 转存目录返回非 JSON：" + brief(r.body));
            return out;
        }
        int errno = o.optInt("errno", -1);
        if (errno != 0) {
            // -7/2 时去掉 app_id 降级再试一次（与 baiduDir 同款逻辑）
            if (errno == -7 || errno == 2) {
                try {
                    r = Net.request("https://pan.baidu.com/api/list?" + q,
                            null, "https://pan.baidu.com/", h, false);
                    if (r != null && r.body != null) {
                        o = new JSONObject(r.body);
                        errno = o.optInt("errno", -1);
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (errno == -7 || errno == -9) {
            out.add("· 转存目录（" + dir + "）还不存在——首次播放转存时会自动创建，属正常");
            return out;
        }
        if (errno != 0) {
            out.add("✗ 转存目录拉取失败 errno=" + errno + "：" + brief(r.body));
            return out;
        }
        JSONArray list = o.optJSONArray("list");
        int n = list == null ? 0 : list.length();
        out.add("✓ 转存目录可访问（" + dir + "）");
        if (n == 0) {
            out.add("· 目录里没有残留文件（滚动缓存干净）");
        } else {
            out.add("· 目录里有 " + n + " 个文件（滚动缓存会自动清理，只留当前播放的 1 集）");
            for (int i = 0; i < n && i < 5; i++) {
                JSONObject it = list.optJSONObject(i);
                if (it != null) out.add("    - " + it.optString("server_filename", ""));
            }
            JSONObject first = list.optJSONObject(0);
            if (first != null) {
                // 实测 filemetas 取链（普通账号播放的关键一环）
                PanFile pf = new PanFile();
                pf.id = String.valueOf(first.optLong("fs_id", 0));
                try {
                    String dlink = baiduDiskDownload(pf);
                    out.add(dlink.length() > 0
                            ? "✓ 盘内取链接口（filemetas）实测可用，普通账号可播"
                            : "✗ 盘内取链接口未返回 dlink");
                } catch (Throwable t) {
                    out.add("✗ 盘内取链接口实测失败：" + t.getMessage());
                }
            }
        }
        return out;
    }

    // ---- 天翼盘内 ----

    private static List<PanFile> tianyiDir(String folderId) throws Exception {
        if (!configured(TIANYI)) throw new Exception("天翼云盘需要登录，请先「网页登录」");
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie(TIANYI));
        h.put("User-Agent", Net.UA_DESKTOP);
        h.put("Referer", "https://cloud.189.cn/");
        String url = "https://cloud.189.cn/api/open/file/listFiles.action?folderId="
                + urlEnc(folderId) + "&pageNum=1&pageSize=200&orderBy=lastOpTime&descending=true";
        Net.Resp r = Net.request(url, null, "https://cloud.189.cn/", h, false);
        if (r == null || r.body == null) throw new Exception("天翼云盘拉目录无响应");
        JSONObject data = new JSONObject(r.body).optJSONObject("data");
        JSONObject ao = data == null ? null : data.optJSONObject("fileListAO");
        List<PanFile> out = new ArrayList<PanFile>();
        if (ao != null) {
            JSONArray folders = ao.optJSONArray("folderList");
            if (folders != null) {
                for (int i = 0; i < folders.length(); i++) {
                    JSONObject it = folders.optJSONObject(i);
                    if (it == null) continue;
                    PanFile f = new PanFile();
                    f.id = String.valueOf(it.optLong("id", 0));
                    f.name = it.optString("name", "");
                    f.dir = true;
                    out.add(f);
                }
            }
            JSONArray files = ao.optJSONArray("fileList");
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject it = files.optJSONObject(i);
                    if (it == null) continue;
                    PanFile f = new PanFile();
                    f.id = String.valueOf(it.optLong("id", 0));
                    f.name = it.optString("name", "");
                    f.size = it.optLong("size", 0);
                    f.dir = false;
                    out.add(f);
                }
            }
        }
        return out;
    }

    private static String tianyiDiskDownload(PanFile f) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie(TIANYI));
        h.put("User-Agent", Net.UA_DESKTOP);
        // 盘内 openFile.do（不带 shareId），登录态下 302 到真实直链
        Net.Resp r = Net.request("https://cloud.189.cn/api/openFile.do?fileId=" + urlEnc(f.id),
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

    // ---- 扫描：DFS 找有声书（深度 ≤3，文件 ≤400） ----

    public static List<DiskBook> scanDisk(String pid) throws Exception {
        List<DiskBook> out = new ArrayList<DiskBook>();
        java.util.LinkedHashMap<String, DiskBook> byName =
                new java.util.LinkedHashMap<String, DiskBook>();
        int[] counters = {0, 0}; // [0]=文件数 [1]=文件夹数
        synchronized (DIR_NAME_CACHE) { DIR_NAME_CACHE.clear(); }
        scanRec(pid, diskRoot(pid), 0, byName, out, counters);
        return out;
    }

    private static void scanRec(String pid, String folderId, int depth,
                                java.util.LinkedHashMap<String, DiskBook> byName,
                                List<DiskBook> order, int[] counters) throws Exception {
        if (depth > 5 || counters[0] >= 4000 || counters[1] >= 2000) return;
        List<PanFile> items = listDir(pid, folderId);
        for (PanFile f : items) {
            if (counters[0] >= 4000) return;
            if (f.dir) {
                counters[1]++;
                synchronized (DIR_NAME_CACHE) { DIR_NAME_CACHE.put(f.id, f.name); }
                try {
                    scanRec(pid, f.id, depth + 1, byName, order, counters);
                } catch (Throwable t) {
                    // ★ v4.19.0：单个子目录拉不动（路径含特殊字符 / 接口风控 / 临时失败）
                    //   → 跳过这个目录继续扫，不再让整盘扫描直接失败。
                    //   根目录（depth==0）本身拉不动仍会抛 —— 那是登录态问题，必须报给用户。
                }
            } else if (isAudio(f.name)) {
                counters[0]++;
                // 父文件夹名 = 书名；folderId 就是当前目录
                String parentName = folderNameOf(pid, folderId);
                DiskBook bk = byName.get(parentName);
                if (bk == null) {
                    bk = new DiskBook();
                    bk.name = parentName;
                    byName.put(parentName, bk);
                    order.add(bk);
                }
                bk.files.add(f);
            }
        }
    }

    /** 简化：书名用「目录名」——由调用方在 DFS 前缓存当前路径名，这里按 id 查不到时兜底 */
    private static String folderNameOf(String pid, String folderId) {
        String n = DIR_NAME_CACHE.get(folderId);
        return n != null ? n : (nameOf(pid) + " 根目录");
    }

    /** folderId → 目录名 缓存（DFS 时顺带填充） */
    private static final java.util.Map<String, String> DIR_NAME_CACHE =
            new java.util.HashMap<String, String>();

    // ==================== URL 编码（无依赖） ====================

    private static String urlEnc(String s) {
        if (s == null) return "";
        try {
            // ★ v4.19.0：URLEncoder.encode 是 HTML form 编码，空格会变成 "+"，
            //   百度把 query 里的 "+" 当字面加号 → 目录名带空格时 api/list 报 errno=-7
            //   （文件或目录不存在）。改为标准 query 编码：空格 = %20。
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return s;
        }
    }
}
