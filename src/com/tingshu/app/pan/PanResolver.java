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
        public String path;    // ★ v4.10.0 父目录链（"鬼吹灯/第1卷"），根级 = ""
                               //   前端按它把「文件夹 → 书」分组，文件夹可点开成书
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
     * ★ v4.18.1：百度分享解析上下文。
     *
     * <p>一次解析内复用分享页参数（shareid / uk / sign / timestamp），
     * 避免「列表一次 + 直链一次」把分享页重复拉两遍；
     * {@link #extraCookie} 存提取码验证后必须回带的 BDCLND。
     */
    private static final class BaiduShare {
        String surl = "", pwd = "", shareid = "", uk = "", sign = "", timestamp = "";
        String extraCookie = "";
    }

    private static Map<String, String> baiduHeaders(BaiduShare bs) {
        Map<String, String> h = new HashMap<String, String>();
        String ck = cookie(BAIDU);
        if (bs != null && bs.extraCookie.length() > 0) ck = ck + "; " + bs.extraCookie;
        h.put("Cookie", ck);
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
        else if (errno == 12 || errno == -6) t = "登录状态失效，请重新粘贴百度 Cookie";
        else t = "百度网盘 errno=" + errno;
        return t + "（" + brief(body) + "）";
    }

    /**
     * ★ v4.18.1：百度分享页解析 —— 重写。
     *
     * <h3>为什么重写</h3>
     * 旧实现调 {@code /share/wxlist}（早已下线的老接口）且完全没处理提取码，
     * 真机表现为「errno=-130 / -7」一步都走不下去。现在改走百度现行流程：
     * <pre>
     *   ① GET /s/{surl}?pwd=xxx  → 从 window.yunData（老版 locals.mset）
     *      取 shareid / uk / sign / timestamp
     *   ② 有提取码 → POST /share/verify 换 BDCLND（必须回带到后续请求）
     *   ③ /share/list + sign/timestamp 列目录
     *   ④ /api/sharedownload + sign/timestamp 取直链
     * </pre>
     */
    private static BaiduShare baiduShare(String shareUrl) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("pan\\.baidu\\.com/s/([a-zA-Z0-9_-]+)")
                .matcher(shareUrl == null ? "" : shareUrl);
        if (!m.find()) throw new Exception("链接不像百度网盘分享（应为 pan.baidu.com/s/ 开头）");
        BaiduShare bs = new BaiduShare();
        bs.surl = m.group(1);
        bs.pwd = pwdOf(shareUrl);
        if (!configured(BAIDU)) throw new Exception("百度网盘解析需要登录 Cookie，请先在上方粘贴");

        String pageUrl = "https://pan.baidu.com/s/" + bs.surl
                + (bs.pwd.length() > 0 ? "?pwd=" + bs.pwd : "");
        Net.Resp r = Net.request(pageUrl, null, "https://pan.baidu.com/", baiduHeaders(bs), false);
        if (r == null || r.body == null) throw new Exception("百度网盘无响应（网络不通？）");
        String html = r.body;

        // ① 新版：window.yunData = {...}
        String yun = grab(html, "window\\.yunData\\s*=\\s*(\\{[\\s\\S]{0,30000}?\\})\\s*;");
        String shareid = "", uk = "", sign = "", ts = "";
        if (yun.length() > 0) {
            shareid = grab(yun, "\"SHAREID\"\\s*:\\s*\"?(\\d+)");
            if (shareid.length() == 0) shareid = grab(yun, "\"shareid\"\\s*:\\s*\"?(\\d+)");
            uk = grab(yun, "\"SHARE_UK\"\\s*:\\s*\"?(\\d+)");
            if (uk.length() == 0) uk = grab(yun, "\"uk\"\\s*:\\s*\"?(\\d+)");
            sign = grab(yun, "\"sign\"\\s*:\\s*\"([^\"]*)\"");
            ts = grab(yun, "\"timestamp\"\\s*:\\s*\"?(\\d+)");
        }
        // ② 老版：locals.mset({...})
        if (shareid.length() == 0 || uk.length() == 0) {
            String blob = grab(html, "locals\\.mset\\(([\\s\\S]{0,12000}?)\\)");
            if (shareid.length() == 0) shareid = grab(blob, "shareid\\s*:\\s*\"?(\\d+)");
            if (uk.length() == 0) uk = grab(blob, "uk\\s*:\\s*\"?(\\d+)");
            if (sign.length() == 0) sign = grab(blob, "sign\\s*:\\s*\"([^\"]*)\"");
            if (ts.length() == 0) ts = grab(blob, "timestamp\\s*:\\s*\"?(\\d+)");
        }
        // ③ 兜底：整页里找 sign
        if (sign.length() == 0) sign = grab(html, "\"sign\"\\s*:\\s*\"([^\"]+)\"");
        if (shareid.length() == 0 || uk.length() == 0) {
            if (bs.pwd.length() == 0 && (html.indexOf("提取码") >= 0 || html.indexOf("verify") >= 0)) {
                throw new Exception("这个分享需要提取码：请在链接末尾加上 ?pwd=xxxx 再解析");
            }
            throw new Exception("没解析到 shareid/uk（页面结构可能已改版）："
                    + brief(html) + "（Cookie 失效？）");
        }
        if (ts.length() == 0) ts = String.valueOf(System.currentTimeMillis() / 1000L);
        bs.shareid = shareid;
        bs.uk = uk;
        bs.sign = sign;
        bs.timestamp = ts;

        // ④ 有提取码 → verify，拿 BDCLND 回带（公开分享跳过，直接列）
        if (bs.pwd.length() > 0) {
            String body = "pwd=" + java.net.URLEncoder.encode(bs.pwd, "UTF-8") + "&vcode=&vcode_str=";
            String vu = "https://pan.baidu.com/share/verify?surl=" + bs.surl
                    + "&t=" + ts + "&channel=chunlei&web=1&app_id=250528&clienttype=0";
            Net.Resp vr = Net.request(vu, body,
                    "https://pan.baidu.com/share/init?surl=" + bs.surl, baiduHeaders(bs), false);
            String vb = (vr == null || vr.body == null) ? "" : vr.body;
            int verr = -1;
            try { verr = new JSONObject(vb).optInt("errno", -1); } catch (Throwable ignored) {}
            if (verr == -9 || verr == 105 || verr == -12) {
                throw new Exception("提取码错误或需要验证码（百度 errno=" + verr + "）");
            }
            // 其余错误不阻断：验证码已在上一步 URL 里带着，部分分享会直接放行
            String sc = vr == null ? "" : vr.headers.get("Set-Cookie");
            String bd = grab(sc == null ? "" : sc, "(BDCLND=[^;\\s]+)");
            if (bd.length() == 0) {
                // 响应 Cookie 罐里可能已经累积（Net.captureCookies 自动落罐）
                String jar = Net.cookieForHost("pan.baidu.com");
                bd = grab(jar == null ? "" : jar, "(BDCLND=[^;\\s]+)");
            }
            bs.extraCookie = bd;
        }
        return bs;
    }

    private static List<PanFile> baiduList(String shareUrl) throws Exception {
        BaiduShare bs = baiduShare(shareUrl);
        List<PanFile> out = new ArrayList<PanFile>();
        // 根目录 + 一层子目录展平（有声书常见「书名/章节」两层结构）
        baiduPage(bs, "/", "", out);
        List<PanFile> dirs = new ArrayList<PanFile>();
        for (PanFile f : out) if (f.dir) dirs.add(f);
        for (PanFile d : dirs) {
            try {
                baiduPage(bs, (d.path.length() == 0 ? "" : "/" + d.path) + "/" + d.name,
                        (d.path.length() == 0 ? "" : d.path + "/") + d.name, out);
            } catch (Throwable ignored) {}
        }
        if (out.isEmpty()) throw new Exception("分享里没有文件（或链接失效）");
        return out;
    }

    /** 百度列一页目录（dir 为绝对路径，path 为给前端的分组链） */
    private static void baiduPage(BaiduShare bs, String dir, String path,
                                  List<PanFile> out) throws Exception {
        String url = "https://pan.baidu.com/share/list?uk=" + bs.uk
                + "&shareid=" + bs.shareid
                + "&order=other&desc=1&showempty=0&web=1&page=1&num=100"
                + "&dir=" + java.net.URLEncoder.encode(dir, "UTF-8")
                + "&t=" + bs.timestamp + "&channel=chunlei&app_id=250528&bdstoken="
                + "&sign=" + java.net.URLEncoder.encode(bs.sign, "UTF-8")
                + "&timestamp=" + bs.timestamp;
        Net.Resp r = Net.request(url, null, "https://pan.baidu.com/s/" + bs.surl,
                baiduHeaders(bs), false);
        if (r == null || r.body == null) throw new Exception("百度网盘拉文件列表无响应");
        JSONObject o = new JSONObject(r.body);
        int errno = o.optInt("errno", -1);
        if (errno != 0) throw new Exception(baiduErr(errno, r.body));
        JSONArray items = o.optJSONArray("list");
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
            out.add(f);
        }
    }

    private static String baiduDownload(String shareUrl, PanFile f) throws Exception {
        BaiduShare bs = baiduShare(shareUrl);
        // 百度公开接口：sharedownload 需要 sign + timestamp + fid_list(JSON)
        String body = "encrypt=0&product=share&type=dlink"
                + "&uk=" + bs.uk
                + "&primaryid=" + bs.shareid
                + "&fid_list=" + java.net.URLEncoder.encode("[" + f.id + "]", "UTF-8");
        String url = "https://pan.baidu.com/api/sharedownload?sign="
                + java.net.URLEncoder.encode(bs.sign, "UTF-8")
                + "&timestamp=" + bs.timestamp
                + "&channel=chunlei&web=1&app_id=250528&clienttype=0";
        Net.Resp r = Net.request(url, body, "https://pan.baidu.com/s/" + bs.surl,
                baiduHeaders(bs), false);
        if (r == null || r.body == null) throw new Exception("百度网盘取直链无响应");
        JSONObject o = new JSONObject(r.body);
        int errno = o.optInt("errno", -1);
        if (errno != 0) throw new Exception(baiduErr(errno, r.body));
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
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie(BAIDU));
        h.put("User-Agent", Net.UA_DESKTOP);
        String url = "https://pan.baidu.com/api/list?dir=" + urlEnc(path)
                + "&order=name&desc=0&web=1&clienttype=0&app_id=250528&num=200&page=1";
        Net.Resp r = Net.request(url, null, "https://pan.baidu.com/", h, false);
        if (r == null || r.body == null) throw new Exception("百度网盘拉目录无响应");
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
                f.token = it.optString("path", ""); // 百度目录用路径做 folderId
                out.add(f);
            }
        }
        return out;
    }

    private static String baiduDiskDownload(PanFile f) throws Exception {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cookie", cookie(BAIDU));
        h.put("User-Agent", Net.UA_DESKTOP);
        String url = "https://pan.baidu.com/api/filemetas?fsids=%5B" + f.id + "%5D"
                + "&dlink=1&web=1&clienttype=0&app_id=250528";
        Net.Resp r = Net.request(url, null, "https://pan.baidu.com/", h, false);
        if (r == null || r.body == null) throw new Exception("百度网盘取直链无响应");
        JSONObject o = new JSONObject(r.body);
        if (o.optInt("errno", -1) != 0) {
            throw new Exception("百度网盘取直链失败 errno=" + o.optInt("errno", -1) + "：" + brief(r.body));
        }
        JSONArray list = o.optJSONArray("list");
        if (list != null && list.length() > 0 && list.optJSONObject(0) != null) {
            String d = list.optJSONObject(0).optString("dlink", "");
            if (d.length() > 0) return d;
        }
        throw new Exception("百度网盘未返回直链：" + brief(r.body));
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
                scanRec(pid, f.id, depth + 1, byName, order, counters);
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
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }
}
