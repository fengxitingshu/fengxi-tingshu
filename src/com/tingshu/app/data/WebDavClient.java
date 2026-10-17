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

    private static final int CONNECT_TIMEOUT = 12000;
    private static final int READ_TIMEOUT = 30000;

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
    }

    // ==================== 对外接口 ====================

    /** 探测可达性 + 认证是否正确（PROPFIND Depth:0 打到 baseUrl） */
    public static Result test(String baseUrl, String user, String pass) {
        return request("PROPFIND", baseUrl, user, pass, null, null, "0", false, CONNECT_TIMEOUT);
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
    public static Result smartTest(String baseUrl, String user, String pass, String dir) {
        Result r = test(baseUrl, user, pass);
        if (r.ok || r.code == 207) return r;

        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        String d = dir == null ? "" : dir.trim();
        while (d.startsWith("/")) d = d.substring(1);
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);

        if (d.length() > 0) {
            // 2) 先确保备份子目录存在（坚果云要求目标文件夹已存在/现建），
            //    再列它、再真实写入探测，三级全部贴近真实备份动作
            ensureDir(base, user, pass, d);   // 失败不阻断，下面用探针兜底

            // 2a) 备份目录本身可列即可用
            Result r2 = request("PROPFIND", base + d + "/", user, pass,
                    null, null, "0", false, CONNECT_TIMEOUT);
            if (r2.ok || r2.code == 207) {
                r2.ok = true;
                r2.error = "";
                return r2;
            }
            // 3) 写入探测：PUT + DELETE（最接近真实备份路径的动作）
            byte[] probe = "fengxi-probe".getBytes();
            Result r3 = request("PUT", base + d + "/.fx-probe.txt", user, pass,
                    null, probe, null, false, READ_TIMEOUT);
            if (r3.ok) {
                request("DELETE", base + d + "/.fx-probe.txt", user, pass,
                        null, null, null, false, READ_TIMEOUT);
                r3.code = 207;   // 对上层表示「连接与读写全部正常」
                r3.error = "";
                return r3;
            }
            return r3;   // 写入失败 → 返回更具体的错误（403/507 等）
        }
        return r;
    }

    /**
     * 确保远端子目录存在。逐级 MKCOL（WebDAV 不允许一次建多级）。
     * <b>405 / 301 视为「已存在」，按成功处理。</b>
     */
    public static Result ensureDir(String baseUrl, String user, String pass, String dir) {
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
                    false, CONNECT_TIMEOUT);
            // 201 新建成功；405 已存在（RFC 4918 说 405 Method Not Allowed）；
            // 301/302 部分服务端对已存在目录回重定向
            if (r.code == 201 || r.code == 405 || r.code == 301 || r.code == 302
                    || r.ok) {
                last = r;
                last.ok = true;
                continue;
            }
            // 409 = 父目录不存在（理论上不会，因为逐级建）
            return r;
        }
        return last;
    }

    /** 上传文件（PUT，流式） */
    public static Result put(String url, String user, String pass, File file) {
        if (file == null || !file.isFile()) {
            Result r = new Result();
            r.error = "本地文件不存在";
            return r;
        }
        return request("PUT", url, user, pass, file, null, null, false, READ_TIMEOUT);
    }

    /** 上传字节（备用） */
    public static Result put(String url, String user, String pass, byte[] data) {
        return request("PUT", url, user, pass, null, data, null, false, READ_TIMEOUT);
    }

    /** 下载到文件（GET，流式，不整块进内存） */
    public static Result getToFile(String url, String user, String pass, File dest) {
        Result r = new Result();
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            conn = open(url, user, pass);
            conn.setRequestMethod("GET");
            r.code = conn.getResponseCode();
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
    public static Result delete(String url, String user, String pass) {
        Result r = request("DELETE", url, user, pass, null, null, null, false, CONNECT_TIMEOUT);
        // 204/200 都算成功；404 也算「已经没了」→ 当作成功
        if (r.code == 204 || r.code == 200 || r.code == 404) {
            r.ok = true;
            r.error = "";
        }
        return r;
    }

    /**
     * 列目录（PROPFIND Depth:1）。
     * <p>部分服务端不支持 Depth:1，此时返回空列表（调用方降级处理，不阻塞主流程）。
     */
    public static List<DavFile> list(String baseUrl, String user, String pass, String dir) {
        List<DavFile> out = new ArrayList<DavFile>();
        String url = joinUrl(baseUrl, dir);
        Result r = request("PROPFIND", url, user, pass, null, null, "1", true, READ_TIMEOUT);
        if (!r.ok && r.code != 207) return out;
        String xml = r.body;
        if (xml == null || xml.length() == 0) return out;
        try {
            parseMultiStatus(xml, out);
        } catch (Throwable t) {
            // Pull 解析器异常 → 降级正则
            parseByRegex(xml, out);
        }
        if (out.isEmpty()) parseByRegex(xml, out);
        return out;
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

    private static Result request(String method, String url, String user, String pass,
                                  File bodyFile, byte[] bodyBytes, String depth,
                                  boolean wantText, int readTimeout) {
        Result r = new Result();
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            conn = open(url, user, pass);
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
                conn.setRequestProperty("Content-Type", "application/octet-stream");
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
                if (wantText) {
                    r.body = decode(bo.toByteArray(), conn.getContentEncoding());
                }
            }

            r.ok = (r.code >= 200 && r.code < 300);
            if (!r.ok) r.error = httpError(r.code);
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

    /** 建连接 + 基础设置 + Basic 认证 */
    private static HttpURLConnection open(String url, String user, String pass) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setInstanceFollowRedirects(false);   // WebDAV 的 301/302 语义特殊，自己处理更稳
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", "FengXiTingShu/3.8 (Android WebDAV)");
        conn.setRequestProperty("Accept", "*/*");
        if (user != null && user.length() > 0) {
            String token = user + ":" + (pass == null ? "" : pass);
            String b64;
            try {
                b64 = android.util.Base64.encodeToString(token.getBytes("UTF-8"),
                        android.util.Base64.NO_WRAP);
            } catch (Throwable t) {
                b64 = java.util.Base64.getEncoder().encodeToString(token.getBytes("UTF-8"));
            }
            conn.setRequestProperty("Authorization", "Basic " + b64);
        }
        return conn;
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

    private static String httpError(int code) {
        switch (code) {
            case 400:
                return "请求格式错误（400）：常见原因是用 http:// 访问了 HTTPS 端口。"
                        + "飞牛 NAS 默认 HTTP 端口 5005、HTTPS 端口 5006："
                        + "要么把地址开头的 http:// 改成 https://，"
                        + "要么把端口改成 5005";
            case 401:
                return "认证失败（401）：账号或密码不对。"
                        + "若用坚果云，请到网页端「安全选项」生成应用密码，不要填登录密码";
            case 403:
                return "没有权限（403）：服务器拒绝该操作。"
                        + "坚果云用户建议：①在坚果云里先建一个文件夹（如 backup），"
                        + "地址改填 https://dav.jianguoyun.com/dav/backup/ ；"
                        + "②检查网页端本月流量是否用超；"
                        + "③触发频率限制时等 30 分钟再试（免费版每 30 分钟 600 次）";
            case 404:
                return "目录不存在（404）：请检查地址与备份目录";
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
            if (r.reflectFailed) o.put("reflectFailed", true);
        } catch (Throwable ignored) {}
        return o;
    }
}
