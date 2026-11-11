package com.fengxi.fnplayer.sub;

import com.fengxi.fnplayer.core.Net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 外挂字幕：射手网（shooter.cn）智能字幕匹配 + SRT/ASS 解析。
 *
 * <p><b>匹配协议</b>（射手影音智能字幕查询 API，社区公开规范）：
 * <ol>
 *   <li>对视频文件取 4 个 4KB 区块 —— 位置 4096、size/3、size*2/3、size-8192，
 *       各自算 MD5，用分号连接成 filehash（与文件名无关，内容指纹）；</li>
 *   <li>POST {@code https://www.shooter.cn/api/subapi.php}
 *       {@code filehash / pathinfo / format=json}；
 *       无匹配返回单字节 0xff，有匹配返回 JSON 数组
 *       {@code [{Desc, Delay, Files:[{Ext, Link}]}]}；</li>
 *   <li>下载 Link（可能是 .srt/.ass，也可能打成 .zip）解析成时间轴事件。</li>
 * </ol>
 *
 * <p>视频直链在 NAS 上，4 个区块用 Range 请求取（带播放鉴权头），
 * 不用下载整个文件。渲染由 VideoActivity 的字幕层做，本类只管取和解析。
 */
public final class Subtitle {

    /** 一条字幕事件（毫秒时间轴）。 */
    public static final class Cue {
        public final long start, end;
        public final String text;
        Cue(long s, long e, String t) { start = s; end = e; text = t; }
    }

    /** 结果回调（在后台线程回调，UI 侧自行切线程）。 */
    public interface Cb { void onResult(boolean ok, String msg); }

    /** 当前字幕事件列表；换片/清除时置 null。 */
    public static volatile List<Cue> cues = null;
    /** 最近一次匹配的字幕描述（射手 Desc 字段）。 */
    public static volatile String subDesc = "";

    private Subtitle() {}

    /** 清除当前字幕。 */
    public static void clear() { cues = null; subDesc = ""; }

    /** 是否已有加载好的字幕。 */
    public static boolean ready() {
        List<Cue> c = cues;
        return c != null && !c.isEmpty();
    }

    /** ms → 当前应显示的字幕文本（二分查找；无则空串）。 */
    public static String textAt(long ms) {
        List<Cue> c = cues;
        if (c == null || c.isEmpty()) return "";
        int lo = 0, hi = c.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Cue q = c.get(mid);
            if (ms < q.start) hi = mid - 1;
            else if (ms > q.end) lo = mid + 1;
            else return q.text;
        }
        return "";
    }

    /**
     * 在线匹配（耗时操作，请放后台线程）。
     *
     * @param videoUrl 视频真实直链（NAS）
     * @param headers  播放鉴权头（Cookie/Authorization），取 hash 区块时用
     * @param fileName pathinfo 参数，传片名即可
     */
    public static void fetchOnline(final String videoUrl, final Map<String, String> headers,
                                   final boolean trustSsl, final String fileName, final Cb cb) {
        try {
            if (videoUrl == null || !videoUrl.startsWith("http")) {
                cb.onResult(false, "还没有可用的视频地址，先播放再匹配");
                return;
            }
            // m3u8/HLS 流没有整文件特征可算，直接走名称匹配
            boolean hls = videoUrl.toLowerCase().contains(".m3u8")
                    || videoUrl.toLowerCase().contains(".m3u");
            String hash = "";
            if (!hls) {
                // 1) 文件特征 hash（4 个 Range 请求，各 4KB）
                hash = fileHash(videoUrl, headers, trustSsl);
            }
            if (hash.length() > 0) {
                // 2) 查询射手网
                String form = "filehash=" + enc(hash)
                        + "&pathinfo=" + enc(fileName == null || fileName.length() == 0 ? "movie.mp4" : fileName)
                        + "&format=json";
                byte[] resp = httpBytes("https://www.shooter.cn/api/subapi.php", form,
                        null, trustSsl, 12000);
                if (resp != null && !(resp.length == 1 && resp[0] == (byte) 0xff)) {
                    int loaded = tryLoadShooterResult(resp, trustSsl);
                    if (loaded > 0) {
                        cb.onResult(true, "已加载字幕（" + (subDesc.length() > 0 ? subDesc + "，" : "")
                                + loaded + " 条）");
                        return;
                    }
                }
                // 走到这说明射手没匹配到（无响应 / 0xff / 都不可下载）→ 落到名称匹配
            }
            // 3) 兜底：按片名在 SubtitleCat 搜（HLS 流 / 射手未收录时）
            String baseName = fileName == null ? "" : fileName;
            int dot = baseName.lastIndexOf('.');
            if (dot > 0) baseName = baseName.substring(0, dot);
            baseName = baseName.replaceAll("[\\[\\(].*[\\]\\)]", " ").trim();
            if (baseName.length() == 0) baseName = title_guess(videoUrl);
            if (baseName.length() == 0) {
                cb.onResult(false, hash.length() == 0 && !hls
                        ? lastHashError
                        : "射手网未匹配到该文件，且没有可用的片名可搜索");
                return;
            }
            int n = fetchByName(baseName, trustSsl);
            if (n > 0) {
                cb.onResult(true, "按名称「" + baseName + "」匹配到字幕（" + n + " 条）");
            } else if (hash.length() == 0 && !hls) {
                cb.onResult(false, lastHashError);
            } else {
                cb.onResult(false, "没找到与「" + baseName + "」匹配的字幕");
            }
        } catch (Throwable t) {
            cb.onResult(false, "字幕匹配失败：" + t.getClass().getSimpleName() + " "
                    + (t.getMessage() == null ? "" : t.getMessage()));
        }
    }

    /** 最近一次 hash 失败的具体原因（给用户看，不再笼统说网络问题）。 */
    private static String lastHashError = "";

    /** 从 URL 里猜片名（最后一段路径，去扩展名）。 */
    private static String title_guess(String url) {
        try {
            String p = new URL(url).getPath();
            p = p.substring(p.lastIndexOf('/') + 1);
            int dot = p.lastIndexOf('.');
            if (dot > 0) p = p.substring(0, dot);
            return p.trim();
        } catch (Throwable t) { return ""; }
    }

    /** 解析射手返回并下载字幕；成功返回 cue 条数，失败 0。 */
    private static int tryLoadShooterResult(byte[] resp, boolean trustSsl) throws Exception {
        String link = "", ext = "", desc = "";
        try {
            JSONArray arr = new JSONArray(new String(resp, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                JSONArray fs = o.optJSONArray("Files");
                if (fs == null) continue;
                for (int j = 0; j < fs.length(); j++) {
                    JSONObject f = fs.optJSONObject(j);
                    if (f == null) continue;
                    String e = f.optString("Ext", "").toLowerCase();
                    String l = f.optString("Link", f.optString("URL", ""));
                    if (l.length() == 0) continue;
                    if (e.equals("srt") || e.equals("ass") || e.equals("ssa")) {
                        link = l; ext = e; desc = o.optString("Desc", "");
                        break;
                    }
                    if (link.length() == 0) { link = l; ext = e; desc = o.optString("Desc", ""); }
                }
                if (ext.equals("srt") || ext.equals("ass") || ext.equals("ssa")) break;
            }
        } catch (Throwable ignored) {}
        if (link.length() == 0) return 0;
        // 4) 下载并解析
        byte[] data = httpBytes(link, null, null, trustSsl, 20000);
        if (data == null || data.length == 0) return 0;
        if ("zip".equals(ext) || (data.length > 2 && data[0] == 'P' && data[1] == 'K')) {
            data = unzipFirstText(data);
            if (data == null) return 0;
        }
        List<Cue> parsed = decodeAndParse(data);
        if (parsed.isEmpty()) return 0;
        cues = parsed;
        subDesc = desc;
        return parsed.size();
    }

    // ==================== 按名称匹配（SubtitleCat 兜底） ====================

    /**
     * 在 SubtitleCat 按片名搜索并加载中文（zh-CN/zh/chs）或英文字幕。
     * 返回 cue 条数，0 = 没找到。
     */
    private static int fetchByName(String name, boolean trustSsl) {
        try {
            String search = "https://www.subtitlecat.com/index.php?search=" + enc(name);
            byte[] b = httpBytes(search, null, null, trustSsl, 15000);
            if (b == null) return 0;
            String html = new String(b, "UTF-8");
            // 结果页：href="subs/xxxx/Name.html"
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("href=\"(subs/[^\"]+\\.html)\"").matcher(html);
            List<String> pages = new ArrayList<String>();
            while (m.find() && pages.size() < 8) pages.add(m.group(1));
            for (String pg : pages) {
                try {
                    byte[] pb = httpBytes("https://www.subtitlecat.com/" + pg,
                            null, null, trustSsl, 15000);
                    if (pb == null) continue;
                    String ph = new String(pb, "UTF-8");
                    // 语言优先级：简中 > 繁中 > 中文 > 英文
                    String[] prefs = {"-zh-CN.srt", "-zh.srt", "-chs.srt", "-cht.srt", "-en.srt"};
                    for (String pref : prefs) {
                        java.util.regex.Matcher sm = java.util.regex.Pattern
                                .compile("href=\"(/subs/[^\"]+" + java.util.regex.Pattern
                                        .quote(pref) + ")\"").matcher(ph);
                        if (!sm.find()) continue;
                        byte[] sb = httpBytes("https://www.subtitlecat.com" + sm.group(1),
                                null, null, trustSsl, 20000);
                        if (sb == null || sb.length < 32) continue;
                        List<Cue> parsed = decodeAndParse(sb);
                        if (!parsed.isEmpty()) {
                            cues = parsed;
                            subDesc = "名称匹配";
                            return parsed.size();
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    // ==================== 文件特征 hash ====================

    /** 射手 hash：位置 4096 / size/3 / size*2/3 / size-8192 各 4KB 分别 MD5，分号连接。 */
    private static String fileHash(String url, Map<String, String> headers, boolean trustSsl) {
        try {
            long size = contentLength(url, headers, trustSsl);
            if (size <= 0) {
                lastHashError = "读不到视频文件大小（服务器不支持 HEAD/Range 探测）";
                return "";
            }
            if (size < 8192 + 4096) {
                lastHashError = "视频文件太小（" + size + " 字节，可能拿到的是播放列表而非正片）";
                return "";
            }
            long[] pos = {4096, size / 3, size / 3 * 2, size - 8192};
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pos.length; i++) {
                byte[] b = rangeGet(url, headers, trustSsl, pos[i], 4096);
                if (b == null || b.length < 4096) {
                    lastHashError = "Range 取块失败（HTTP 拒绝，鉴权或不支持断点）";
                    return "";
                }
                if (i > 0) sb.append(';');
                sb.append(md5Hex(b));
            }
            return sb.toString();
        } catch (Throwable t) {
            lastHashError = "读取视频特征异常：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage());
            return "";
        }
    }

    private static long contentLength(String url, Map<String, String> headers,
                                      boolean trustSsl) throws Exception {
        long n = -1;
        try {                                   // 有些服务对 HEAD 直接 403/405，不能让它中断
            HttpURLConnection c = open(url, headers, trustSsl, 8000);
            try {
                c.setRequestMethod("HEAD");
                n = c.getContentLength();
            } finally {
                c.disconnect();
            }
        } catch (Throwable ignored) {}
        if (n <= 0) {   // 用 Range 0-0 拿总长
            HttpURLConnection c = open(url, headers, trustSsl, 8000);
            try {
                c.setRequestProperty("Range", "bytes=0-0");
                int code = c.getResponseCode();
                String cr = code / 100 == 2 ? c.getHeaderField("Content-Range") : null;
                if (cr != null && cr.contains("/")) {
                    try { n = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim()); }
                    catch (Throwable ignored) {}
                }
            } finally {
                c.disconnect();
            }
        }
        return n;
    }

    private static byte[] rangeGet(String url, Map<String, String> headers, boolean trustSsl,
                                   long start, int len) throws Exception {
        HttpURLConnection c = open(url, headers, trustSsl, 10000);
        try {
            c.setRequestProperty("Range", "bytes=" + start + "-" + (start + len - 1));
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n, total = 0;
            while (total < len && (n = in.read(buf, 0, Math.min(buf.length, len - total))) > 0) {
                bo.write(buf, 0, n);
                total += n;
            }
            in.close();
            return total >= len ? bo.toByteArray() : null;
        } finally {
            c.disconnect();
        }
    }

    /** 通用 HTTP 取字节。form 非 null 时走 POST（application/x-www-form-urlencoded）。 */
    private static byte[] httpBytes(String url, String form, Map<String, String> headers,
                                    boolean trustSsl, int timeoutMs) throws Exception {
        HttpURLConnection c = open(url, null, trustSsl, timeoutMs);
        try {
            if (form != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setRequestProperty("Referer", "https://www.shooter.cn/");
                OutputStream os = c.getOutputStream();
                os.write(form.getBytes("UTF-8"));
                os.close();
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, Map<String, String> headers,
                                          boolean trustSsl, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", Net.UA);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet())
                if (e.getKey() != null && e.getValue() != null)
                    c.setRequestProperty(e.getKey(), e.getValue());
        }
        if (c instanceof javax.net.ssl.HttpsURLConnection && trustSsl)
            Net.applyTrustAll((javax.net.ssl.HttpsURLConnection) c);
        return c;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Throwable t) { return ""; }
    }

    private static String md5Hex(byte[] b) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("MD5");
            byte[] r = d.digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : r) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    private static byte[] unzipFirstText(byte[] zip) {
        ZipInputStream zi = null;
        try {
            zi = new ZipInputStream(new java.io.ByteArrayInputStream(zip));
            ZipEntry e;
            byte[] fallback = null;
            while ((e = zi.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().toLowerCase();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zi.read(buf)) > 0) bo.write(buf, 0, n);
                byte[] data = bo.toByteArray();
                if (name.endsWith(".srt") || name.endsWith(".ass") || name.endsWith(".ssa"))
                    return data;
                if (fallback == null && data.length > 0) fallback = data;
            }
            return fallback;
        } catch (Throwable t) {
            return null;
        } finally {
            if (zi != null) try { zi.close(); } catch (Throwable ignored) {}
        }
    }

    // ==================== 解析 ====================

    /** 解码（BOM/UTF-8/GBK）后按格式解析。 */
    private static List<Cue> decodeAndParse(byte[] b) {
        String text = decode(b);
        List<Cue> out = parseSrt(text);
        if (out.isEmpty()) out = parseAss(text);
        return out;
    }

    private static String decode(byte[] b) {
        try {
            if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB
                    && (b[2] & 0xFF) == 0xBF)
                return new String(b, 3, b.length - 3, "UTF-8");
            if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)
                return new String(b, 2, b.length - 2, "UTF-16LE");
            if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF)
                return new String(b, 2, b.length - 2, "UTF-16BE");
            String t = new String(b, "UTF-8");
            int bad = 0;
            for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '\uFFFD') bad++;
            if (bad > 0 && bad * 50 > t.length()) {
                try { return new String(b, "GBK"); } catch (Throwable ignored) {}
            }
            return t;
        } catch (Throwable t) {
            return new String(b);
        }
    }

    /** SRT：块间空行分隔，时间行 {@code 00:00:01,000 --> 00:00:04,000}。 */
    private static List<Cue> parseSrt(String text) {
        List<Cue> out = new ArrayList<Cue>();
        if (text == null || text.length() == 0) return out;
        String norm = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = norm.split("\n");
        long start = -1, end = -1;
        StringBuilder body = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.contains("-->")) {
                if (start >= 0 && body.length() > 0) out.add(new Cue(start, end, body.toString()));
                body.setLength(0);
                String[] p = line.split("-->");
                start = parseTime(p.length > 0 ? p[0] : "");
                end = parseTime(p.length > 1 ? p[1] : "");
                if (end < start) end = start + 2000;
                continue;
            }
            if (start < 0) continue;               // 块序号行等
            if (line.length() == 0) {              // 块结束
                if (body.length() > 0) out.add(new Cue(start, end, body.toString()));
                start = -1; end = -1; body.setLength(0);
                continue;
            }
            if (body.length() > 0) body.append('\n');
            body.append(cleanText(line));
        }
        if (start >= 0 && body.length() > 0) out.add(new Cue(start, end, body.toString()));
        return out;
    }

    /** ASS/SSA：取 Dialogue 行，剥掉样式花括号。 */
    private static List<Cue> parseAss(String text) {
        List<Cue> out = new ArrayList<Cue>();
        if (text == null) return out;
        String norm = text.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : norm.split("\n")) {
            if (!line.startsWith("Dialogue:")) continue;
            String[] f = line.substring(9).split(",", 10);
            if (f.length < 10) continue;
            long s = parseAssTime(f[1]), e = parseAssTime(f[2]);
            if (e <= s) e = s + 2000;
            String t = cleanText(f[9].replace("\\N", "\n").replace("\\n", "\n")
                    .replace("\\h", " "));
            if (t.length() > 0) out.add(new Cue(s, e, t));
        }
        return out;
    }

    /** 去掉 HTML 标签 / ASS 样式块 {\...}。 */
    private static String cleanText(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\{[^}]*\\}", "")          // {\an8} {\i1} 等
                .replaceAll("<[^>]+>", "")                   // <i> <b> 等
                .trim();
        return t;
    }

    /** SRT 时间：00:00:01,000 / 00:00:01.000。 */
    private static long parseTime(String s) {
        try {
            String t = s.trim().replace(",", ".");
            String[] p = t.split(":");
            int h = 0, m = 0;
            double sec;
            if (p.length == 3) { h = Integer.parseInt(p[0].trim()); m = Integer.parseInt(p[1].trim()); sec = Double.parseDouble(p[2].trim()); }
            else if (p.length == 2) { m = Integer.parseInt(p[0].trim()); sec = Double.parseDouble(p[1].trim()); }
            else sec = Double.parseDouble(p[0].trim());
            return Math.round((h * 3600 + m * 60 + sec) * 1000);
        } catch (Throwable t) { return -1; }
    }

    /** ASS 时间：H:MM:SS.cc（百分之一秒）。 */
    private static long parseAssTime(String s) {
        try {
            String t = s.trim();
            String[] p = t.split(":");
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            double sec = Double.parseDouble(p[2].trim());
            return Math.round((h * 3600 + m * 60 + sec) * 1000);
        } catch (Throwable t) { return -1; }
    }
}
