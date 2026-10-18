package com.tingshu.app.data;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * ★ v3.8.0 零依赖 WebDAV 客户端。
 *
 * <p>一套 WebDAV 协议即可覆盖：坚果云、Nextcloud、群晖 DSM、威联通 QTS、
 * Alist/OpenList、各类 NAS 自建服务。只依赖 {@link java.net.HttpURLConnection}，
 * 不引入任何第三方库（手工构建链下这一点是硬约束）。
 *
 * <h3>★ 最大的技术障碍：HttpURLConnection 拒绝非标准 HTTP 方法</h3>
 * <p>WebDAV 需要 {@code PROPFIND}（列目录）与 {@code MKCOL}（建目录），
 * 但 {@code conn.setRequestMethod("PROPFIND")} 会直接抛
 * {@link java.net.ProtocolException} ——
 * JDK/Android 的实现里有一份「允许的方法」白名单，只有
 * GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE 能通过。
 *
 * <p>而 Android 的真实实现类是
 * {@code com.android.okhttp.internal.huc.HttpURLConnectionImpl}（OkHttp 背板），
 * 它的请求方法存在一个名为 {@code method} 的 String 字段里 ——
 * <b>这个字段不在 {@code java.net.HttpURLConnection} 抽象类上</b>，
 * 所以不能简单地 {@code conn.getClass().getDeclaredField("method")}。
 *
 * <p>解法是 {@link #setMethod}：先在<b>真实继承链</b>上逐级找，
 * 找不到再按候选类名兜底。见该方法的注释。
 *
 * <p>若反射彻底失败（极个别 ROM），{@link Result#error} 会明确说明，
 * <b>不静默失败</b>。此时 GET / DELETE 仍可用（它们是标准方法），
 * 所以「下载备份」和「删除备份」还能工作，只有「上传」不可用。
 *
 * <h3>★ 关于自签名证书</h3>
 * <p>Nextcloud / 自建 NAS 常用自签名 https 证书。本实现<b>明确不信任</b>
 * —— 不引入 {@code TrustAllManager}。那是安全红线：它会让 App 对任何
 * 中间人攻击都不设防。捕获到 {@code SSLHandshakeException} 时给出
 * 明确提示，引导用户改用 http（本项目 {@code usesCleartextTraffic="true"}，
 * 局域网 http 可直接用）或为该证书配置受信任的 CA。
 */
public final class WebDavClient {

    private WebDavClient() {}

    /** ★ v3.9.45：PROPFIND 的标准请求体（部分实现如飞牛对无 body 的 PROPFIND 回 405） */
    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<D:propfind xmlns:D=\"DAV:\"><D:prop>"
            + "<D:resourcetype/><D:getcontentlength/><D:getlastmodified/><D:displayname/>"
            + "</D:prop></D:propfind>";

    /**
     * ★ v3.9.37：把 URL 的 path 段逐段编码（scheme://host 原样保留）。
     * 已编码的段（%XX 形式）先解码再编码，避免二次编码。
     * 空格编码成 %20 而不是 +（URLEncoder 是表单语义，WebDAV path 要 RFC 3986）。
     *
     * <p>★ v3.9.40 重大修复：v3.9.37~39 的实现把 {@code /dav/} 编码成了
     * {@code //dav/}（开头空段多补了一个斜杠）—— 坚果云的 nginx 对双斜杠
     * 路径一律回 410 Gone，这就是这几版「坚果云测试连接报 410、
     * 诊断链 MKCOL/PROPFIND/PUT 全 410」的真正原因。
     * 现在按「每段前放一个分隔符」重建，空段/尾斜杠结构原样保留。
     */
    static String encodeDavUrl(String url) {
        if (url == null) return null;
        try {
            java.net.URL u = new java.net.URL(url);
            int port = u.getPort();
            String hostPart = u.getProtocol() + "://" + u.getHost()
                    + (port > 0 ? ":" + port : "");
            String path = u.getPath() == null ? "" : u.getPath();
            String query = u.getQuery() == null ? "" : "?" + u.getQuery();
            if (!path.startsWith("/")) path = "/" + path;
            String[] segs = path.substring(1).split("/", -1);
            StringBuilder sb = new StringBuilder(hostPart);
            for (int i = 0; i < segs.length; i++) {
                sb.append('/');
                String seg = segs[i];
                if (seg.length() == 0) continue;
                String dec;
                try { dec = java.net.URLDecoder.decode(seg, "UTF-8"); }
                catch (Throwable e) { dec = seg; }
                try {
                    sb.append(java.net.URLEncoder.encode(dec, "UTF-8").replace("+", "%20"));
                } catch (Throwable e) { sb.append(seg); }
            }
            return sb.toString() + query;
        } catch (Throwable t) {
            return url;   // 不是合法 URL 就原样交出去，让后面的请求自己报错
        }
    }


    private static final int CONNECT_TIMEOUT = 12000;
    private static final int READ_TIMEOUT = 30000;

    /** 认证协议：自动（默认，先试 Basic 遇 Digest 质询自动切）/ 仅 Basic / 仅 Digest */
    public static final String AUTH_AUTO = "auto";
    public static final String AUTH_BASIC = "basic";
    public static final String AUTH_DIGEST = "digest";

    /** 远端文件项 */
    public static final class DavFile {
        public String name = "";
        public String href = "";
        public long size = -1;
        public long lastModified = 0;
        public boolean dir;
    }

    /** 请求结果 */
    public static final class Result {
        public boolean ok;
        public int code;
        public String error = "";
        public String body = "";
        public byte[] bytes;
        /** 是否为反射失败（用于给出更有针对性的提示） */
        public boolean reflectFailed;
        /** 401 时服务端返回的 WWW-Authenticate 头（用于判断 Basic/Digest） */
        public String wwwAuth;
        /** ★ v3.9.25 服务端响应头 Server（识别到底连到了什么服务） */
        public String server;
        /** ★ v3.9.42：PROPFIND 解析出的目录内容（listWithErr 用） */
        public final java.util.List<DavFile> files = new java.util.ArrayList<DavFile>();
    }

    // ==================== 对外接口 ====================

    /** 探测可达性 + 认证是否正确（PROPFIND Depth:0 打到 baseUrl） */
    public static Result test(String baseUrl, String user, String pass, String authType) {
        return request("PROPFIND", baseUrl, user, pass, null, null, "0", false, CONNECT_TIMEOUT, authType);
    }

    /**
     * ★ v3.9.11 兼容坚果云的连接测试（三级降级）。
     *
     * <p>坚果云对 {@code /dav/} <b>根目录</b>的限制很特别：浏览器 GET 根目录会回
     * {@code OperationNotAllowed}，部分账号对根目录 PROPFIND 也会回 403，
     * 但对<b>子目录</b>的列目录 / 建目录 / 上传完全正常（Notability、Zotero
     * 等官方接入方式都是直接在根下建子目录用）。之前 {@link #test} 打到根目录
     * 一把梭，会把「根目录不可列」误报成连接失败。
     *
     * <ol>
     *   <li>PROPFIND 根目录 Depth:0 → 207 即成功；</li>
     *   <li>失败且配置了备份目录 → PROPFIND 备份目录 Depth:0 → 207 即成功
     *       （根不可列，但备份目录正常）；</li>
     *   <li>仍失败 → 向备份目录 PUT 一个极小探测文件并随即 DELETE，
     *       写入成功说明认证与读写权限全部正常。</li>
     * </ol>
     */
    public static Result smartTest(String baseUrl, String user, String pass, String dir, String authType) {
        // ★ v3.9.39：每一步的结果都记下来附在最终错误里 —— 服务器层拦截
        //   （坚果云的 nginx 直接回 410/403）光看最后一步根本定位不了。
        StringBuilder diag = new StringBuilder();

        Result r = test(baseUrl, user, pass, authType);
        diag.append("PROPFIND根:").append(r.code > 0 ? String.valueOf(r.code) : r.error);
        if (r.ok || r.code == 207) return r;

        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        String d = dir == null ? "" : dir.trim();
        while (d.startsWith("/")) d = d.substring(1);
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);

        if (d.length() > 0) {
            // 2) 先确保备份子目录存在（坚果云要求目标文件夹已存在/现建），
            //    再列它、再真实写入探测，三级全部贴近真实备份动作
            Result mk = ensureDir(base, user, pass, d, authType);   // 失败不阻断，下面用探针兜底
            boolean mkCreated = mk.ok && mk.code == 201;   // 新建目录成功 = 认证+写权限都正常
            diag.append(" MKCOL:").append(mk.code > 0 ? String.valueOf(mk.code) : mk.error);

            // 2a) 备份目录本身可列即可用
            Result r2 = request("PROPFIND", base + d + "/", user, pass,
                    null, null, "0", false, CONNECT_TIMEOUT, authType);
            diag.append(" PROPFIND目录:").append(r2.code > 0 ? String.valueOf(r2.code) : r2.error);
            if (r2.ok || r2.code == 207) {
                r2.ok = true;
                r2.error = "";
                return r2;
            }
            // 3) 写入探测：PUT + DELETE（最接近真实备份路径的动作）
            //
            // ★ v3.9.39 重大修复：探针文件名**绝不能以点开头**！
            //   坚果云的 nginx 对隐藏文件（.xxx）一律直接回 410 Gone（应用层根本
            //   收不到请求），之前两版的 .fx-probe.txt / .fx-probe-<ts>.txt 全撞
            //   在这上面 —— 这就是「坚果云测试连接报 410」的真正原因。
            String probeName = "fx-probe-" + (System.currentTimeMillis() % 1000000L) + ".txt";
            byte[] probe = "fengxi-probe".getBytes();
            Result r3 = request("PUT", base + d + "/" + probeName, user, pass,
                    null, probe, null, false, READ_TIMEOUT, authType);
            diag.append(" PUT:").append(r3.code > 0 ? String.valueOf(r3.code) : r3.error);
            if (r3.ok) {
                request("DELETE", base + d + "/" + probeName, user, pass,
                        null, null, null, false, READ_TIMEOUT, authType);
                r3.code = 207;   // 对上层表示「连接与读写全部正常」
                r3.error = "";
                return r3;
            }
            // 4) MKCOL 新建目录成功本身就证明了认证与写权限 —— 备份动作
            //    （MKCOL + PUT）都能做，探测文件被服务器层拦不影响真实备份。
            if (mkCreated) {
                Result ok = new Result();
                ok.ok = true;
                ok.code = 207;
                ok.error = "";
                return ok;
            }
            r3.error = (r3.error == null || r3.error.length() == 0 ? "" : r3.error + "。")
                    + "诊断[" + diag + "]";
            return r3;   // 写入失败 → 返回更具体的错误（403/507 等）
        }
        r.error = (r.error == null || r.error.length() == 0 ? "" : r.error + "。")
                + "诊断[" + diag + "]";
        return r;
    }

    /**
     * 确保远端子目录存在。逐级 MKCOL（WebDAV 不允许一次建多级）。
     * <b>405 / 301 视为「已存在」，按成功处理。</b>
     */
    public static Result ensureDir(String baseUrl, String user, String pass, String dir, String authType) {
        Result last = new Result();
        last.ok = true;
        last.code = 200;
        if (dir == null || dir.trim().length() == 0) return last;

        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        String[] parts = dir.split("/");
        StringBuilder acc = new StringBuilder(base);
        for (String seg : parts) {
            if (seg == null || seg.length() == 0) continue;
            acc.append(seg).append('/');
            Result r = request("MKCOL", acc.toString(), user, pass, null, null, null,
                    false, CONNECT_TIMEOUT, authType);
            // 201 新建成功；405 已存在（RFC 4918 说 405 Method Not Allowed）；
            // 301/302 部分服务端对已存在目录回重定向
            if (r.code == 201 || r.code == 405 || r.code == 301 || r.code == 302
                    || r.ok) {
                last = r;
                last.ok = true;
                continue;
            }
            // ★ v3.9.41：坚果云对「已存在的目录」MKCOL 也回 403
            //   OperationNotAllowed（而不是 RFC 的 405）—— 它根本不允许第三方
            //   应用 MKCOL，哪怕目录已经在那里。用 PROPFIND 验证目录是否真的
            //   存在：在 → 继续下一步；不在 → 才是真没权限。
            if (r.code == 403 || r.code == 410) {
                Result p = request("PROPFIND", acc.toString(), user, pass,
                        null, null, "0", false, CONNECT_TIMEOUT, authType);
                if (p.ok || p.code == 207) {
                    last = p;
                    last.ok = true;
                    continue;
                }
            }
            // 409 = 父目录不存在（理论上不会，因为逐级建）
            return r;
        }
        return last;
    }

    /** 上传文件（PUT，流式） */
    public static Result put(String url, String user, String pass, File file, String authType) {
        if (file == null || !file.isFile()) {
            Result r = new Result();
            r.error = "本地文件不存在";
            return r;
        }
        return request("PUT", url, user, pass, file, null, null, false, READ_TIMEOUT, authType);
    }

    /** 上传字节（备用） */
    public static Result put(String url, String user, String pass, byte[] data, String authType) {
        return request("PUT", url, user, pass, null, data, null, false, READ_TIMEOUT, authType);
    }

    /** 下载到文件（GET，流式，不整块进内存）；authType 支持 Digest 质询重试 */
    public static Result getToFile(String url, String user, String pass, File dest, String authType) {
        String initAuth = AUTH_DIGEST.equals(authType) ? null : basicHeader(user, pass);
        Result r = doGetToFile(url, user, pass, dest, initAuth);
        boolean triedDigest = false;
        if (r.code == 401 && r.wwwAuth != null && wantsDigest(authType)) {
            java.util.Map<String, String> ch = parseWwwAuth(r.wwwAuth);
            if ("digest".equals(ch.get("scheme"))) {
                String dig = digestHeader("GET", digestUri(url), user, pass, ch, null, null);
                if (dig != null) {
                    triedDigest = true;
                    Result r2 = doGetToFile(url, user, pass, dest, dig);
                    if (r2.code == 401) r2.error = better401(r2.wwwAuth, authType, true);
                    return r2;
                }
            }
        }
        if (r.code == 401) r.error = better401(r.wwwAuth, authType, triedDigest);
        return r;
    }

    /** 真正执行一次 GET 下载 */
    private static Result doGetToFile(String url, String user, String pass, File dest, String authHeader) {
        Result r = new Result();
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            conn = open(url, authHeader);
            conn.setRequestMethod("GET");
            r.code = conn.getResponseCode();
            try { r.wwwAuth = conn.getHeaderField("WWW-Authenticate"); } catch (Throwable ignored) {}
            if (r.code != 200) {
                r.error = httpError(r.code);
                return r;
            }
            in = conn.getInputStream();
            if (in == null) {
                r.error = "读取响应失败";
                return r;
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            out = new FileOutputStream(dest);
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0;
            // 下载上限 64MB（备份包正常不到 1MB，留足余量同时防被喂大文件）
            final long MAX = 64L * 1024 * 1024;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX) {
                    r.error = "远端文件超过 64MB，拒绝下载";
                    return r;
                }
                out.write(buf, 0, n);
            }
            out.flush();
            r.ok = true;
            return r;
        } catch (Throwable t) {
            r.error = explain(t);
            return r;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            disconnect(conn);
        }
    }

    /** 删除远端文件 */
    public static Result delete(String url, String user, String pass, String authType) {
        Result r = request("DELETE", url, user, pass, null, null, null, false, CONNECT_TIMEOUT, authType);
        // 204/200 都算成功；404/410 也算「已经没了」→ 当作成功
        //（坚果云对刚删除过的路径常回 410 Gone 而不是 404）
        if (r.code == 204 || r.code == 200 || r.code == 404 || r.code == 410) {
            r.ok = true;
            r.error = "";
        }
        return r;
    }

    /**
     * 列目录（PROPFIND Depth:1）。
     * <p>部分服务端不支持 Depth:1，此时返回空列表（调用方降级处理，不阻塞主流程）。
     */
    public static List<DavFile> list(String baseUrl, String user, String pass, String dir, String authType) {
        return listWithErr(baseUrl, user, pass, dir, authType).files;
    }

    /**
     * ★ v3.9.42：列目录（带错误信息版）。
     * <p>老 {@link #list} 在失败时返回空列表 —— 「服务器报错」和「目录真的是空」
     * 表现一模一样，恢复界面「刷不到记录」时根本无法定位。这个版本把失败原因
     * （含 HTTP 状态码）放进 {@link Result#error}，调用方展示给用户。
     *
     * <p>★ v3.9.46：多形式自动降级。飞牛等自建 WebDAV 对「列出备份目录」回
     * 405 Method Not Allowed 的实测（v3.9.45 用户反馈：根目录 Depth:0 探测
     * 207 正常、列表 Depth:1 却 405，服务器 body 是裸文本 "Method Not
     * Allowed"）可能对应多种服务端怪癖：目标目录不存在（自建实现对不存在
     * 路径常回 405 而非 RFC 的 404）、不认尾斜杠、拒绝带请求体、或对子目录
     * 禁用 Depth:1。这里按概率依次尝试 5 种形式，任何一种 207 即成功：
     * <ol>
     *   <li>PROPFIND Depth:1 → {@code <base>/<dir>/}（带标准请求体）</li>
     *   <li>同上去掉尾斜杠</li>
     *   <li>同①但不带请求体</li>
     *   <li>PROPFIND Depth:1 → 根目录，按 {@code /<dir>/} 前缀过滤出目标内容</li>
     *   <li>MKCOL 确保目录存在后重试①（目录不存在正是 405 的最常见原因）</li>
     * </ol>
     * 每一步的状态码都记进 diag，全部失败时原样附在错误里 —— 用户截图即可
     * 一次性定位到具体是哪种形式被拒。
     */
    public static Result listWithErr(String baseUrl, String user, String pass, String dir, String authType) {
        String d = dir == null ? "" : dir.trim();
        while (d.startsWith("/")) d = d.substring(1);
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);

        StringBuilder diag = new StringBuilder();

        // ① 标准形式：Depth:1 打 <base>/<dir>/（带标准请求体）
        Result r = request("PROPFIND", joinUrl(baseUrl, d), user, pass,
                null, null, "1", true, READ_TIMEOUT, authType);
        diag.append("P1目录:").append(codeOf(r));
        if (finishList(r)) return r;

        if (d.length() > 0) {
            // ② 去掉尾斜杠再试（部分实现的集合路由只认无斜杠形式）
            Result r2 = request("PROPFIND", stripTrailingSlash(joinUrl(baseUrl, d)), user, pass,
                    null, null, "1", true, READ_TIMEOUT, authType);
            diag.append(" P1无斜杠:").append(codeOf(r2));
            if (finishList(r2)) return r2;

            // ③ 不带请求体再试（个别实现把带 body 的 PROPFIND 当非法请求）
            Result r3 = requestX("PROPFIND", joinUrl(baseUrl, d), user, pass,
                    null, null, "1", true, READ_TIMEOUT, authType, false);
            diag.append(" P1无体:").append(codeOf(r3));
            if (finishList(r3)) return r3;

            // ④ 列根目录 Depth:1，按 /<dir>/ 前缀过滤 —— 目录本身不可列时，
            //    只要父目录可列，照样能拿到目标目录下的文件清单。
            //    ★ v3.9.46b：过滤后为空不提前返回 —— 根目录可列但 /<dir>/ 下
            //    一无所有，很可能就是「目录不存在」这个 405 的最常见诱因，
            //    必须落到⑤自动建目录后再正式重试，否则用户会看到"恢复列表
            //    成功但永远是空的"这个假象。
            Result r4 = request("PROPFIND", joinUrl(baseUrl, ""), user, pass,
                    null, null, "1", true, READ_TIMEOUT, authType);
            diag.append(" P1根:").append(codeOf(r4));
            if (finishList(r4)) {
                filterByDir(r4, d);
                if (!r4.files.isEmpty()) return r4;
                // 根目录下没有该子树：记下这次结果，继续⑤（建目录后重试①）
                diag.append("(空)");
            }

            // ⑤ 目录可能压根不存在：确保目录后回到标准形式重试最后一次
            ensureDir(baseUrl, user, pass, d, authType);
            Result r5 = request("PROPFIND", joinUrl(baseUrl, d), user, pass,
                    null, null, "1", true, READ_TIMEOUT, authType);
            diag.append(" MKCOL后P1:").append(codeOf(r5));
            if (finishList(r5)) return r5;

            // ★ v3.9.47：失败提示按服务商分流 —— 坚果云给排查清单，
            //   自建 NAS（飞牛等）解释 501/405 是服务端限制，别再让坚果云用户
            //   看到「飞牛」提示一头雾水。
            boolean isJgy = baseUrl != null && baseUrl.toLowerCase(java.util.Locale.ROOT)
                    .contains("jianguoyun");
            r.error = (r.error == null || r.error.length() == 0 ? "HTTP " + r.code : r.error)
                    + "。已自动尝试 5 种列表形式均失败[" + diag + "]。"
                    + (isJgy
                        ? "坚果云排查：① 地址须为 https://dav.jianguoyun.com/dav/；"
                          + "② 密码须用「应用密码」（网页端 账户信息→安全选项 里生成），不是登录密码；"
                          + "③ 目录会自动创建，若提示无权限请先在坚果云网页端建好该文件夹"
                        : "自建 NAS：请先手动执行一次「备份」让 App 建好远端目录再刷新恢复列表；"
                          + "501/405 表示服务器或其网关未实现该方法（服务端限制，客户端无法绕过），"
                          + "可换用坚果云等支持完整 WebDAV 的服务");
            com.tingshu.app.core.DiagLog.add("[dav-list] " + diag + " dir=" + d);
            return r;
        }

        r.error = (r.error == null || r.error.length() == 0 ? "HTTP " + r.code : r.error)
                + "。已自动尝试[" + diag + "]";
        com.tingshu.app.core.DiagLog.add("[dav-list] " + diag);
        return r;
    }

    /** 列表请求收尾：判定成功、解析 207 Multi-Status（Pull 失败降级正则） */
    private static boolean finishList(Result r) {
        r.ok = (r.ok || r.code == 207);
        if (!r.ok) {
            if (r.error == null || r.error.length() == 0) {
                r.error = "HTTP " + r.code;
            }
            return false;
        }
        String xml = r.body;
        if (xml == null || xml.length() == 0) return true;   // 目录确实是空
        try {
            parseMultiStatus(xml, r.files);
        } catch (Throwable t) {
            // Pull 解析器异常 → 降级正则
            parseByRegex(xml, r.files);
        }
        if (r.files.isEmpty()) parseByRegex(xml, r.files);
        return true;
    }

    /** diag 用：有状态码显示状态码，没有（网络层异常）显示错误 */
    private static String codeOf(Result r) {
        return r.code > 0 ? String.valueOf(r.code)
                : (r.error == null || r.error.length() == 0 ? "?" : r.error);
    }

    /** 只去掉末尾一个斜杠（根路径 "/" 不动） */
    private static String stripTrailingSlash(String url) {
        if (url != null && url.length() > 1 && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * 从根目录列表里筛出 {@code /<dir>/} 子树下的条目（降级④的配套）。
     * href 可能是完整 URL 也可能是绝对路径，统一解码后按前缀匹配。
     */
    private static void filterByDir(Result r, String dir) {
        String prefix = "/" + dir + "/";
        List<DavFile> keep = new ArrayList<DavFile>();
        for (DavFile f : r.files) {
            if (f == null) continue;
            String href = f.href == null ? "" : f.href;
            String dec;
            try { dec = java.net.URLDecoder.decode(href, "UTF-8"); }
            catch (Throwable t) { dec = href; }
            if (dec.contains(prefix)) keep.add(f);
        }
        r.files.clear();
        r.files.addAll(keep);
    }

    /** base + dir 拼成完整 URL（都做斜杠归一） */
    public static String joinUrl(String baseUrl, String dir) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (!b.endsWith("/")) b += "/";
        String d = dir == null ? "" : dir.trim();
        while (d.startsWith("/")) d = d.substring(1);
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        return d.length() == 0 ? b : b + d + "/";
    }

    // ==================== 核心请求 ====================

    /**
     * ★ 带认证协议处理的请求（对外）。
     *
     * <p>{@code authType} 取值：{@code auto}（默认，先试 Basic，遇 Digest 质询自动切）、
     * {@code basic}（只用 Basic）、{@code digest}（先不带头，遇 Digest 质询再算）。
     * 当服务端要求 Digest 而我们一开始用了 Basic（或根本没带）时，会在 401 后
     * 自动应答 Digest 并重试一次 —— 这正是过去「报密码错误其实是无协议」的根因。
     */
    private static Result request(String method, String url, String user, String pass,
                                  File bodyFile, byte[] bodyBytes, String depth,
                                  boolean wantText, int readTimeout, String authType) {
        return requestX(method, url, user, pass, bodyFile, bodyBytes, depth,
                wantText, readTimeout, authType, true);
    }

    /**
     * ★ 带认证协议处理的请求（含 autoPropBody 开关的完整版）。
     *
     * @param autoPropBody 是否给无请求体的 PROPFIND 自动补标准请求体
     *                     （v3.9.45 引入；v3.9.46 的列表降级③需要关掉它来试
     *                     「无请求体」形式，其余调用方一律 true）
     */
    private static Result requestX(String method, String url, String user, String pass,
                                  File bodyFile, byte[] bodyBytes, String depth,
                                  boolean wantText, int readTimeout, String authType,
                                  boolean autoPropBody) {
        // ★ v3.9.37：URL 路径段统一编码。
        //   之前目录名/文件名里的中文、空格、#、? 等是原样拼进 URL 的——
        //   坚果云（dav.jianguoyun.com）对未编码路径直接回 403/404，表现就是
        //   「测试连接正常（探针文件名是纯 ASCII）但一备份就报没有权限」。
        url = encodeDavUrl(url);

        // ★ v3.9.45：PROPFIND 必须带请求体。RFC 4918 允许无 body（等同 allprop），
        //   但飞牛等部分实现对无请求体的 PROPFIND 直接回 405 Method Not Allowed
        //   —— 表现就是「备份上传成功、恢复列表却刷不出来」。统一补标准 prop 请求体。
        if (autoPropBody && "PROPFIND".equals(method) && bodyFile == null && bodyBytes == null) {
            try { bodyBytes = PROPFIND_BODY.getBytes("UTF-8"); } catch (Throwable ignored) {}
        }

        // 初始认证头：digest 模式先不带头（等 401 质询），其余先发 Basic
        String initAuth = AUTH_DIGEST.equals(authType) ? null : basicHeader(user, pass);

        Result r = doRequest(method, url, user, pass, bodyFile, bodyBytes, depth,
                wantText, readTimeout, initAuth);

        // 401 + 服务端要求 Digest → 应答 Digest 并重试一次（仅当协议选择允许 digest）
        boolean triedDigest = false;
        if (r.code == 401 && r.wwwAuth != null && wantsDigest(authType)) {
            java.util.Map<String, String> ch = parseWwwAuth(r.wwwAuth);
            if ("digest".equals(ch.get("scheme"))) {
                String uri = digestUri(url);
                String dig = digestHeader(method, uri, user, pass, ch,
                        bodyFile, bodyBytes);
                if (dig != null) {
                    triedDigest = true;
                    Result r2 = doRequest(method, url, user, pass, bodyFile, bodyBytes, depth,
                            wantText, readTimeout, dig);
                    if (r2.code == 401) r2.error = better401(r2.wwwAuth, authType, true);
                    return r2;
                }
            }
        }
        if (r.code == 401) {
            r.error = better401(r.wwwAuth, authType, triedDigest);
            // ★ v3.9.25：401 时把服务器指纹写进诊断日志（「小组件诊断」页可看），
            //   便于定位「密码没错却 401」——到底是哪个服务在拒绝
            String credB64 = "";
            try {
                String token = user + ":" + (pass == null ? "" : pass);
                byte[] raw = token.getBytes("UTF-8");
                credB64 = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP);
            } catch (Throwable ignored) {
                try { credB64 = java.util.Base64.getEncoder()
                        .encodeToString((user + ":" + (pass == null ? "" : pass))
                                .getBytes("UTF-8")); } catch (Throwable ignored2) {}
            }
            // cred=Basic base64(账号:密码) —— 拿到后可离线解码，逐字符核对
            // 输入法到底发了什么（空格/大小写/全半角）。诊断日志仅存本机。
            com.tingshu.app.core.DiagLog.add("[dav] 401 server="
                    + (r.server == null ? "?" : r.server)
                    + " auth=" + (r.wwwAuth == null ? "?" : r.wwwAuth)
                    + " passLen=" + (pass == null ? 0 : pass.length())
                    + " cred=" + credB64);
            // ★ v3.9.42：把实际发送的凭据直接回显在错误里 —— 「密码没错却
            //   一直 401」的排查，九成时间花在「App 里存的凭据到底是不是
            //   你以为的那份」上。截图这个提示即可当场核对（凭据 base64
            //   任意工具解码即可看到完整 账号:密码）。
            r.error += "\n[凭据核对] 账号=\"" + (user == null ? "" : user)
                    + "\" 密码长度=" + (pass == null ? 0 : pass.length())
                    + " 发送的凭据base64=" + credB64;
        }
        return r;
    }

    /** 真正发一次请求（authHeader 为已算好的 Authorization 头，可为 null） */
    private static Result doRequest(String method, String url, String user, String pass,
                                    File bodyFile, byte[] bodyBytes, String depth,
                                    boolean wantText, int readTimeout, String authHeader) {
        Result r = new Result();
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            conn = open(url, authHeader);
            conn.setReadTimeout(readTimeout);

            // ★ 关键：改方法名必须在 getOutputStream()/getResponseCode() 之前
            if (!setMethod(conn, method)) {
                r.reflectFailed = true;
                r.error = "该设备不支持 WebDAV 所需的 HTTP 方法（" + method
                        + "），系统限制了反射改方法名";
                return r;
            }

            if (depth != null) conn.setRequestProperty("Depth", depth);

            if (bodyFile != null || bodyBytes != null) {
                conn.setDoOutput(true);
                // bodyFile = 上传的备份包（二进制）；bodyBytes = 探针文本 /
                // PROPFIND 的 XML 请求体 → 用 xml 类型（飞牛对 PROPFIND 的
                // Content-Type 有校验，octet-stream 同样可能被拒）
                conn.setRequestProperty("Content-Type",
                        bodyFile != null ? "application/octet-stream" : "application/xml");
                try {
                    conn.setFixedLengthStreamingMode(
                            bodyFile != null ? bodyFile.length() : bodyBytes.length);
                } catch (Throwable ignored) {}
                out = conn.getOutputStream();
                if (bodyFile != null) {
                    InputStream fi = new FileInputStream(bodyFile);
                    try {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = fi.read(buf)) > 0) out.write(buf, 0, n);
                    } finally {
                        closeQuietly(fi);
                    }
                } else {
                    out.write(bodyBytes);
                }
                out.flush();
            }

            r.code = conn.getResponseCode();
            // 401 时务必读取服务端返回的 WWW-Authenticate，用于判断 Basic/Digest
            try { r.wwwAuth = conn.getHeaderField("WWW-Authenticate"); } catch (Throwable ignored) {}
            // ★ v3.9.25 记录 Server 头：识别连接到的真实服务（飞牛/nginx/Go 等）
            try { r.server = conn.getHeaderField("Server"); } catch (Throwable ignored) {}

            // 读响应体（PROPFIND 是 207，仍属成功）
            InputStream is;
            try {
                is = conn.getInputStream();
            } catch (Throwable t) {
                is = conn.getErrorStream();
            }
            in = is;
            if (in != null) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                final long MAX = 4L * 1024 * 1024;      // 响应体上限 4MB（防被喂大文件）
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX) break;
                    bo.write(buf, 0, n);
                }
                r.bytes = bo.toByteArray();
                // ★ v3.9.37：403/507 时也解码 body —— 「没有权限」的真正原因
                //   （限流/流量用超）往往就写在响应体里，不能只看状态码。
                // ★ v3.9.38：410/404 同理 —— 坚果云的删除墓碑/目录消失原因也在 body。
                if (wantText || r.code == 403 || r.code == 507 || r.code == 410 || r.code == 404 || r.code == 405) {
                    r.body = decode(bo.toByteArray(), conn.getContentEncoding());
                }
            }

            r.ok = (r.code >= 200 && r.code < 300);
            if (!r.ok) {
                r.error = httpError(r.code);
                // ★ v3.9.37：403/507 时带上服务器响应里的人话片段。
                //   坚果云限流/流量用超时，body 会写明原因，只看状态码永远在猜。
                // ★ v3.9.38：410/404 同样带原因。
                if (r.code == 403 || r.code == 507 || r.code == 410 || r.code == 404 || r.code == 405) {
                    String hint = bodyHint(r.body);
                    if (hint.length() > 0) r.error += "。服务器返回：" + hint;
                }
            }
            return r;
        } catch (Throwable t) {
            r.error = explain(t);
            return r;
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            disconnect(conn);
        }
    }

    /** 计算 Basic 认证头（无账号时返回 null） */
    private static String basicHeader(String user, String pass) {
        if (user == null || user.length() == 0) return null;
        String token = user + ":" + (pass == null ? "" : pass);
        byte[] raw;
        try { raw = token.getBytes("UTF-8"); }
        catch (Throwable t) { raw = token.getBytes(); }
        String b64;
        try {
            b64 = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            b64 = java.util.Base64.getEncoder().encodeToString(raw);
        }
        return "Basic " + b64;
    }

    /** 建连接 + 基础设置；authHeader 为已算好的 Authorization 头（可为 null） */
    private static HttpURLConnection open(String url, String authHeader) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        // ★ v3.9.43：家庭 NAS（飞牛/群晖等）的 HTTPS 全是自签名证书。
        //   默认仍然严格校验（安全底线，见类注释）；只有用户在备份页明确开启
        //   「信任自签名证书」后才放宽 —— 且只影响 WebDAV 这一条连接。
        if (url != null && url.toLowerCase(java.util.Locale.ROOT).startsWith("https")
                && com.tingshu.app.data.Settings.davTrustAllSsl()
                && conn instanceof javax.net.ssl.HttpsURLConnection) {
            javax.net.ssl.HttpsURLConnection hc = (javax.net.ssl.HttpsURLConnection) conn;
            try {
                javax.net.ssl.TrustManager[] tm = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public void checkClientTrusted(
                                java.security.cert.X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(
                                java.security.cert.X509Certificate[] chain, String authType) {}
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
                };
                javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                sc.init(null, tm, new java.security.SecureRandom());
                hc.setSSLSocketFactory(sc.getSocketFactory());
                hc.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                    public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                        return true;   // 自签证书的 NAS 常用 IP 直连，主机名校验必然失败
                    }
                });
            } catch (Throwable ignored) {}
        }
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setInstanceFollowRedirects(false);   // WebDAV 的 301/302 语义特殊，自己处理更稳
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", "FengXiTingShu/3.8 (Android WebDAV)");
        conn.setRequestProperty("Accept", "*/*");
        if (authHeader != null && authHeader.length() > 0) {
            conn.setRequestProperty("Authorization", authHeader);
        }
        return conn;
    }

    // ==================== ★ Digest 认证（RFC 2617 / 7616） ====================

    /** 该认证协议是否允许走 Digest（auto / digest 都允许；basic 不允许） */
    private static boolean wantsDigest(String authType) {
        return AUTH_AUTO.equals(authType) || AUTH_DIGEST.equals(authType);
    }

    /**
     * 解析 {@code WWW-Authenticate} 头，返回 {scheme, realm, nonce, qop, opaque, algorithm}。
     * <p>对带引号的值（可能含逗号，如 {@code qop="auth,auth-int"}）做完整捕获。
     */
    private static java.util.Map<String, String> parseWwwAuth(String header) {
        java.util.Map<String, String> m = new java.util.HashMap<String, String>();
        if (header == null) return m;
        String h = header.trim();
        String lower = h.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("digest")) {
            m.put("scheme", "digest");
            h = h.substring(6).trim();
        } else if (lower.startsWith("basic")) {
            m.put("scheme", "basic");
            return m;
        } else {
            int sp = lower.indexOf(' ');
            m.put("scheme", sp > 0 ? lower.substring(0, sp) : lower);
            return m;
        }
        // key = "value" 或 key = value（值可带引号，引号内可含逗号）
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(\\w+)\\s*=\\s*(\"[^\"]*\"|[^,]+)");
        java.util.regex.Matcher mat = p.matcher(h);
        while (mat.find()) {
            String k = mat.group(1).toLowerCase(java.util.Locale.ROOT);
            String v = mat.group(2).trim();
            if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
            m.put(k, v);
        }
        return m;
    }

    /**
     * 根据 401 质询算 Digest Authorization 头。
     *
     * <p>★ v3.9.13 修复：qop 选择之前优先 {@code auth-int}，但哈希却按 {@code auth}
     * 算（HA2 不含 body hash）—— 服务器质询 {@code qop="auth,auth-int"} 时必被拒。
     * 现改为：<b>优先 {@code auth}</b>；仅当服务器只给 {@code auth-int} 时才用它，
     * 并按 RFC 2617 对请求体算 {@code H(entity-body)} 拼进 HA2。
     *
     * @param bodyFile 请求体文件（PUT 上传时用，可为 null）
     * @param bodyBytes 请求体字节（可为 null）
     */
    private static String digestHeader(String method, String uri, String user, String pass,
                                       java.util.Map<String, String> ch,
                                       File bodyFile, byte[] bodyBytes) {
        try {
            String realm = ch.get("realm") == null ? "" : ch.get("realm");
            String nonce = ch.get("nonce") == null ? "" : ch.get("nonce");
            String opaque = ch.get("opaque");
            String qop = ch.get("qop");

            String chosenQop = chooseQop(qop);
            // auth-int 需要 body hash（HA2 = H(method:uri:H(body))）
            String bodyHash = null;
            if ("auth-int".equals(chosenQop)) {
                bodyHash = bodyMd5(bodyFile, bodyBytes);
                if (bodyHash == null) chosenQop = "auth";   // 兜底：算不出就退回 auth
            }

            String ha1 = md5(user + ":" + realm + ":" + pass);
            String ha2 = "auth-int".equals(chosenQop)
                    ? md5(method + ":" + uri + ":" + bodyHash)
                    : md5(method + ":" + uri);
            String nc = "00000001";
            String cnonce = randomHex(16);
            String response;
            if (chosenQop != null) {
                response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce
                        + ":" + chosenQop + ":" + ha2);
            } else {
                response = md5(ha1 + ":" + nonce + ":" + ha2);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Digest username=\"").append(user).append("\",");
            sb.append("realm=\"").append(realm).append("\",");
            sb.append("nonce=\"").append(nonce).append("\",");
            sb.append("uri=\"").append(uri).append("\",");
            sb.append("response=\"").append(response).append("\"");
            if (chosenQop != null) {
                sb.append(",qop=").append(chosenQop);
                sb.append(",nc=").append(nc);
                sb.append(",cnonce=\"").append(cnonce).append("\"");
            }
            if (opaque != null && opaque.length() > 0) {
                sb.append(",opaque=\"").append(opaque).append("\"");
            }
            String alg = ch.get("algorithm");
            if (alg != null && alg.length() > 0
                    && alg.toLowerCase(java.util.Locale.ROOT).startsWith("md5")) {
                sb.append(",algorithm=MD5");
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** qop 选择：优先 auth（轻量、无需 body hash），其次 auth-int */
    private static String chooseQop(String qop) {
        if (qop == null || qop.length() == 0) return null;
        boolean hasAuth = false, hasAuthInt = false;
        for (String p : qop.split(",")) {
            String t = p.trim().toLowerCase(java.util.Locale.ROOT);
            if ("auth".equals(t)) hasAuth = true;
            else if ("auth-int".equals(t)) hasAuthInt = true;
        }
        if (hasAuth) return "auth";
        if (hasAuthInt) return "auth-int";
        return null;
    }

    /** 算请求体的 MD5 hex（auth-int 用）。文件太大（>8MB）时返回 null 退回 auth */
    private static String bodyMd5(File f, byte[] bytes) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("MD5");
            if (bytes != null) {
                return toHex(md.digest(bytes));
            }
            if (f != null && f.isFile()) {
                if (f.length() > 8L * 1024 * 1024) return null;
                java.io.FileInputStream fi = new java.io.FileInputStream(f);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fi.read(buf)) > 0) md.update(buf, 0, n);
                } finally {
                    closeQuietly(fi);
                }
                return toHex(md.digest());
            }
            // 无请求体：按 RFC 用空串的哈希
            return toHex(md.digest(new byte[0]));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Digest 用的请求 URI：取 URL 的路径（含 query），不含 host */
    private static String digestUri(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String uri = u.getPath();
            if (uri == null || uri.length() == 0) uri = "/";
            String q = u.getQuery();
            if (q != null && q.length() > 0) uri = uri + "?" + q;
            return uri;
        } catch (Throwable t) {
            return url;
        }
    }

    private static String md5(String s) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] b = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String randomHex(int n) {
        java.util.Random rnd = new java.util.Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", rnd.nextInt(256)));
        return sb.toString();
    }

    /**
     * ★ 401 的精准文案：区分「协议不匹配」「Digest 已试仍被拒」「真密码错」，
     * 并带上服务器质询摘要（v3.9.13），用户截图即可定位问题。
     */
    private static String better401(String wwwAuth, String authType, boolean triedDigest) {
        String ch = challengeBrief(wwwAuth);

        if (wwwAuth != null && wwwAuth.toLowerCase(java.util.Locale.ROOT).contains("digest")) {
            if (triedDigest) {
                return "认证失败（401）：已按服务器要求用 Digest 认证应答，仍被拒绝。"
                        + "说明账号或密码不对（Digest 模式下密码即服务器/应用密码，"
                        + "多一个空格都不行），请核对后重试。" + ch;
            }
            return "认证失败（401）：服务器要求 Digest 认证。"
                    + "请在 WebDAV 设置里把「认证协议」改成「自动」或「Digest」后重试。" + ch;
        }
        if (wwwAuth != null && wwwAuth.toLowerCase(java.util.Locale.ROOT).contains("basic")) {
            return "认证失败（401）：服务器要求 Basic 认证，App 已按 Basic 发送，"
                    + "是账号或密码不对。"
                    + "若用坚果云，请到网页端「安全选项」生成应用密码，不要填登录密码；"
                    + "账号一般填注册邮箱/手机号。" + ch;
        }
        if (wwwAuth == null || wwwAuth.length() == 0) {
            return "认证失败（401）：服务器未说明认证方式。"
                    + "常见原因是地址不是 WebDAV 入口（被网页页面拦截）。"
                    + "坚果云应为 https://dav.jianguoyun.com/dav/ ，"
                    + "飞牛 NAS 应为 http://NAS的IP:5005 。请核对地址。" + ch;
        }
        return "认证失败（401）：账号或密码不对，或认证协议不匹配。"
                + "建议「认证协议」用「自动」，并核对账号密码" + ch;
    }

    /** 把 WWW-Authenticate 压缩成一句话放进错误里，方便用户截图排查 */
    private static String challengeBrief(String wwwAuth) {
        if (wwwAuth == null || wwwAuth.length() == 0) return "";
        String s = wwwAuth.replaceAll("\\s+", " ").trim();
        if (s.length() > 90) s = s.substring(0, 90) + "…";
        return "（服务器要求：" + s + "）";
    }

    // ==================== ★ 反射改方法名（多级 fallback） ====================

    /**
     * ★ 把连接的请求方法改成任意动词。
     *
     * <p>{@link HttpURLConnection#setRequestMethod} 只接受
     * GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE，其余抛
     * {@link java.net.ProtocolException}。所以要绕过它。
     *
     * <p><b>为什么要「多级 fallback」</b>：这个字段在不同实现上挂的位置不一样：
     * <ul>
     *   <li>Android 真机：{@code com.android.okhttp.internal.huc.HttpURLConnectionImpl}
     *       持有一个 {@code private String method;}（OkHttp 背板）</li>
     *   <li>桌面 JVM：{@code sun.net.www.protocol.http.HttpURLConnection} 上有
     *       {@code protected String method;}</li>
     *   <li>部分 ROM / 不同 Android 版本：实现类层次可能不同</li>
     * </ul>
     * 所以策略是：<b>先沿真实对象的继承链逐级找</b>（覆盖绝大多数情况），
     * 找不到再按候选类名 {@code Class.forName} 兜底。
     *
     * @return 是否成功。失败时调用方必须明确报错，不能静默当成 GET。
     */
    public static boolean setMethod(HttpURLConnection conn, String verb) {
        if (conn == null || verb == null || verb.length() == 0) return false;
        String m = verb.toUpperCase(java.util.Locale.ROOT);

        // 1) 标准方法直接走官方 API
        if ("GET".equals(m) || "POST".equals(m) || "HEAD".equals(m)
                || "PUT".equals(m) || "DELETE".equals(m) || "OPTIONS".equals(m)
                || "TRACE".equals(m)) {
            try {
                conn.setRequestMethod(m);
                return true;
            } catch (Throwable ignored) {
                // 个别 ROM 连 PUT/DELETE 也拦 → 落到反射
            }
        }

        // 2) 非标准动词（PROPFIND / MKCOL / …）：反射
        Field f = findMethodField(conn.getClass());
        if (f == null) return false;
        try {
            f.setAccessible(true);
            f.set(conn, m);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 逐级探测名为 {@code method} 的 String 字段。
     * <p>先走对象真实继承链，再走候选类名。
     */
    private static Field findMethodField(Class<?> start) {
        // ---- 第一级：真实继承链 ----
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            Field f = declaredStringField(c, "method");
            if (f != null) return f;
        }
        // ---- 第二级：候选类名兜底（各实现/ROM 层次不一） ----
        String[] candidates = {
                "com.android.okhttp.internal.huc.HttpURLConnectionImpl",
                "com.android.okhttp.internal.http.HttpURLConnectionImpl",
                "sun.net.www.protocol.http.HttpURLConnection",
                "java.net.HttpURLConnection"
        };
        for (String n : candidates) {
            try {
                Field f = declaredStringField(Class.forName(n), "method");
                if (f != null) return f;
            } catch (Throwable ignored) {
                // 该类不存在（正常：Android 上没有 sun.net.*）→ 继续试下一个
            }
        }
        return null;
    }

    /** 取 {@code c} 自己声明的、类型为 String 的 {@code name} 字段 */
    private static Field declaredStringField(Class<?> c, String name) {
        if (c == null) return null;
        try {
            Field f = c.getDeclaredField(name);
            if (f != null && f.getType() == String.class) return f;
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== ★ PROPFIND XML 解析 ====================

    /**
     * 用 {@link android.util.Xml} 的 Pull 解析器解析 207 Multi-Status。
     *
     * <p><b>为什么只看 localName</b>：WebDAV 响应的命名空间前缀极不统一 ——
     * 坚果云用 {@code D:}，Nextcloud 用 {@code d:}，有的用 {@code dav:}，
     * 还有的直接不写前缀。Pull 解析器的 {@code getName()} 天然去掉了前缀，
     * 只比较 localName 是对所有服务端都稳的写法。
     */
    private static void parseMultiStatus(String xml, List<DavFile> out) throws Exception {
        org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
        p.setInput(new java.io.StringReader(xml));

        DavFile cur = null;
        String tag = "";
        StringBuilder text = new StringBuilder();
        boolean inCollection = false;

        int ev = p.getEventType();
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                tag = p.getName() == null ? "" : p.getName();
                text.setLength(0);
                if ("response".equalsIgnoreCase(tag)) {
                    cur = new DavFile();
                    inCollection = false;
                }
            } else if (ev == org.xmlpull.v1.XmlPullParser.TEXT) {
                if (p.getText() != null) text.append(p.getText());
            } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG) {
                String end = p.getName() == null ? "" : p.getName();
                String val = text.toString().trim();

                if ("href".equalsIgnoreCase(end) && cur != null) {
                    cur.href = val;
                    cur.name = lastSegment(val);
                } else if ("getcontentlength".equalsIgnoreCase(end) && cur != null) {
                    try { cur.size = Long.parseLong(val); } catch (Throwable ignored) {}
                } else if ("getlastmodified".equalsIgnoreCase(end) && cur != null) {
                    cur.lastModified = parseHttpDate(val);
                } else if ("collection".equalsIgnoreCase(end) && cur != null) {
                    inCollection = true;
                } else if ("response".equalsIgnoreCase(end) && cur != null) {
                    cur.dir = inCollection;
                    if (cur.name != null && cur.name.length() > 0) out.add(cur);
                    cur = null;
                }
                text.setLength(0);
            }
            ev = p.next();
        }
    }

    /**
     * 正则兜底：Pull 解析器在某些 ROM 上抛异常时用。
     * 只抓两样最关键的：{@code <...href>} 与 {@code <...getcontentlength>}。
     */
    private static void parseByRegex(String xml, List<DavFile> out) {
        if (xml == null || xml.length() == 0) return;
        java.util.regex.Pattern pr = java.util.regex.Pattern.compile(
                "<[^>]*?href[^>]*>([^<]*)</[^>]*?href[^>]*>",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = pr.matcher(xml);
        while (m.find()) {
            String href = unescape(m.group(1)).trim();
            if (href.length() == 0) continue;
            DavFile f = new DavFile();
            f.href = href;
            f.name = lastSegment(href);
            f.dir = href.endsWith("/");
            if (f.name != null && f.name.length() > 0) out.add(f);
        }
    }

    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    /** 取 URL 路径最后一段（去掉尾斜杠），并做 URL 解码 */
    private static String lastSegment(String href) {
        if (href == null) return "";
        String s = href;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int i = s.lastIndexOf('/');
        String seg = i >= 0 ? s.substring(i + 1) : s;
        try {
            seg = java.net.URLDecoder.decode(seg, "UTF-8");
        } catch (Throwable ignored) {}
        return seg == null ? "" : seg;
    }

    /** 解析 HTTP 日期（RFC 1123），失败返回 0 */
    private static long parseHttpDate(String s) {
        if (s == null || s.length() == 0) return 0;
        String[] pats = {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEEE, dd-MMM-yy HH:mm:ss zzz",
                "EEE MMM d HH:mm:ss yyyy"
        };
        for (String pat : pats) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(pat,
                        java.util.Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
                java.util.Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    // ==================== 错误文案 ====================

    /**
     * ★ v3.9.37：从错误响应 body 里提取人话片段（去 XML/HTML 标签，取前 80 字）。
     * 坚果云 403 的 body 形如 {@code <?xml ...><error><message>频率限制</message>...}，
     * 这段话往往就是「没有权限」的真正原因。
     */
    private static String bodyHint(String body) {
        if (body == null || body.length() == 0) return "";
        String s = body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (s.length() == 0) return "";
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private static String httpError(int code) {
        switch (code) {
            case 400:
                return "请求格式错误（400）：常见原因是用 http:// 访问了 HTTPS 端口。"
                        + "飞牛 NAS 默认 HTTP 端口 5005、HTTPS 端口 5006："
                        + "要么把地址开头的 http:// 改成 https://，"
                        + "要么把端口改成 5005";
            case 401:
                return "认证失败（401）：账号或密码不对，或服务端要求 Digest 认证。"
                        + "坚果云请到网页端「安全选项」生成应用密码；"
                        + "若服务器要求 Digest，请把「认证协议」改成「自动」或「Digest」";
            case 403:
                return "没有权限（403）：服务器拒绝该操作。"
                        + "坚果云用户建议：①在坚果云里先建一个文件夹（如 backup），"
                        + "地址改填 https://dav.jianguoyun.com/dav/backup/ ；"
                        + "②检查网页端本月流量是否用超；"
                        + "③触发频率限制时等 30 分钟再试（免费版每 30 分钟 600 次）";
            case 404:
                return "目录不存在（404）：请检查地址与备份目录";
            case 410:
                return "资源已不存在（410）：服务器上的目录或文件已被删除或移动。"
                        + "坚果云用户请检查网页端备份目录是否还在；"
                        + "若刚删除过同名文件，稍等片刻再试";
            case 405:
                return "服务端不允许该操作（405）";
            case 409:
                return "父目录不存在（409）";
            case 507:
                return "网盘空间不足（507）";
            default:
                return "服务器返回 HTTP " + code;
        }
    }

    private static String explain(Throwable t) {
        if (t == null) return "未知错误";
        String cls = t.getClass().getName();
        String m = t.getMessage();

        if (cls.contains("SSLHandshakeException") || cls.contains("SSLException")
                || cls.contains("CertPathValidatorException")
                || (m != null && m.toLowerCase(java.util.Locale.ROOT).contains("certificate"))) {
            return "服务器证书不受信任（自签名证书）。"
                    + "本 App 不会为任何服务器关闭证书校验。请改用 http:// 地址，"
                    + "或为该证书配置受信任的 CA";
        }
        if (cls.contains("UnknownHostException")) {
            return "找不到服务器地址，请检查网络与地址拼写";
        }
        if (m != null && m.toLowerCase(java.util.Locale.ROOT)
                .contains("unexpected end of stream")) {
            return "连接被服务器中途断开：常见原因是 http/https 端口不匹配"
                    + "（如用 http:// 访问了 5006 这类 HTTPS 端口），"
                    + "或服务端拒绝了该请求。请核对地址的协议与端口";
        }
        if (cls.contains("SocketTimeoutException")) {
            return "连接超时，请检查网络或服务器状态";
        }
        if (cls.contains("ConnectException")) {
            return "无法连接服务器，请检查地址与端口";
        }
        if (m == null || m.length() == 0) m = t.getClass().getSimpleName();
        return m;
    }

    private static String decode(byte[] data, String encoding) {
        if (data == null) return "";
        String enc = "UTF-8";
        try {
            if (encoding != null && encoding.length() > 0) enc = encoding.trim();
        } catch (Throwable ignored) {}
        try {
            return new String(data, enc);
        } catch (Throwable t) {
            try { return new String(data, "UTF-8"); } catch (Throwable t2) { return ""; }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (Throwable ignored) {}
    }

    private static void disconnect(HttpURLConnection c) {
        try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
    }

    /** 便捷：把 Result 转成前端用的 JSONObject */
    public static JSONObject toJson(Result r) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", r.ok);
            o.put("code", r.code);
            o.put("error", r.error == null ? "" : r.error);
            // ★ v3.9.25：服务器指纹（前端可显示，也便于截图定位）
            if (r.server != null && r.server.length() > 0) o.put("server", r.server);
            if (r.wwwAuth != null && r.wwwAuth.length() > 0) o.put("auth", r.wwwAuth);
            if (r.reflectFailed) o.put("reflectFailed", true);
        } catch (Throwable ignored) {}
        return o;
    }
}
