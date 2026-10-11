package com.tingshu.app.data;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ★ v3.8.0 备份包编解码器。
 *
 * <p><b>为什么用 ZIP 而不是纯 JSON</b>：备份包内要装 jar 插件（二进制）、
 * JS 插件整目录、{@code .py} 源文件 —— 纯 JSON 只能靠 base64，
 * 体积膨胀 33% 且解析慢。ZIP 是 JDK 自带（{@link java.util.zip}），
 * 手工构建链下<b>零新增依赖</b>。
 *
 * <p><b>扩展名用 {@code .fxbak} 而非 {@code .zip}</b>：
 * <ul>
 *   <li>避免被系统解压 App 关联（用户点开直接进解压器会很困惑）</li>
 *   <li>避免用户误当普通压缩包解压</li>
 *   <li>便于「这是不是本 App 的备份」的身份识别</li>
 * </ul>
 *
 * <p><b>★ 关键设计：zip 内条目名一律 ASCII。</b>
 * 原因是 {@link ZipOutputStream} 指定 UTF-8 编码的构造函数要 API 24+，
 * 而本项目 {@code minSdkVersion=21}。如果直接往条目名里写中文，
 * 在低版本设备上会用平台默认编码（多数是 UTF-8 但个别 ROM 是 GBK），
 * 解包时中文名就乱码了。把中文名一律挪进 JSON 字段（如
 * {@code data/plugin_jar.json} 的 {@code file_name}），
 * 条目名只用 {@code js/pkg1/}、{@code py/s1.py} 这种 ASCII 串，
 * 就彻底绕开了这个坑。
 *
 * <p><b>★ 安全防护</b>：
 * <ol>
 *   <li><b>防 zip-slip</b>：每个条目名过 {@link #normEntry}（拒 {@code ..}、
 *       拒绝对路径、拒盘符），落盘前再校验 {@code canonicalPath} 必须在
 *       {@code tmpDir} 之内。否则恶意包可以用 {@code ../../} 覆盖任意文件。</li>
 *   <li><b>防炸弹包</b>：单条目 &gt; {@link #MAX_ENTRY}、累计 &gt;
 *       {@link #MAX_TOTAL} 立即中止，防止解压撑爆存储/内存。</li>
 * </ol>
 */
public final class BackupCodec {

    private BackupCodec() {}

    /** 备份包格式版本。只增不减：新版 App 读旧包时按「字段存在才读」处理。 */
    public static final int SCHEMA = 1;

    /** 备份包扩展名 */
    public static final String EXT = ".fxbak";

    /** 包身份标识 —— 用来判定「这是不是本 App 导出的包」 */
    public static final String APP = "fengxi-tingshu";

    /** 包内顶层元信息文件名 */
    public static final String ENTRY_MANIFEST = "manifest.json";

    /** 单条目解压上限（16MB）。JS 插件全包不到 300KB，jar 也就几 MB。 */
    public static final long MAX_ENTRY = 16 * 1024 * 1024L;

    /** 解压累计上限（32MB）。配置类备份正常不到 1MB，留足余量。 */
    public static final long MAX_TOTAL = 32 * 1024 * 1024L;

    /** 复制缓冲区大小 */
    private static final int BUF = 8192;

    // ==================== 写出 ====================

    /**
     * 把 manifest + 文本条目 + 文件条目写入 ZIP 流。
     *
     * @param out      ZIP 输出流（调用方负责关闭）
     * @param manifest 顶层元信息，会写成 {@code manifest.json}
     * @param blobs    文本/JSON 条目：条目名 → 字节（条目名必须已是 ASCII 安全串）
     * @param files    二进制条目：条目名 → 本地文件（流式复制，不整块读进内存）
     */
    public static void writeZip(OutputStream out, JSONObject manifest,
                                Map<String, byte[]> blobs,
                                Map<String, File> files) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(out);
        // 打包速度优先（备份总量小，压缩率意义不大）
        try { zos.setLevel(Deflater.BEST_SPEED); } catch (Throwable ignored) {}

        // 1) manifest.json 永远第一个写，方便将来做「只读头部」的快速预览
        putBytes(zos, ENTRY_MANIFEST,
                (manifest == null ? new JSONObject() : manifest).toString().getBytes("UTF-8"));

        // 2) 文本条目
        if (blobs != null) {
            List<String> names = new ArrayList<String>(blobs.keySet());
            Collections.sort(names);          // 固定顺序 → 校验和可复现
            for (String name : names) {
                byte[] data = blobs.get(name);
                if (data == null) continue;
                putBytes(zos, normEntry(name), data);
            }
        }

        // 3) 二进制条目（流式，不整块读）
        if (files != null) {
            List<String> names = new ArrayList<String>(files.keySet());
            Collections.sort(names);
            for (String name : names) {
                File f = files.get(name);
                if (f == null || !f.isFile()) continue;
                putFile(zos, normEntry(name), f);
            }
        }

        zos.finish();
        zos.flush();
    }

    private static void putBytes(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        // 音频是随机的、压不动，但这里的 JSON 压缩率很高；统一用默认时间避免时间戳带来的不确定性
        e.setTime(0L);
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    private static void putFile(ZipOutputStream zos, String name, File f) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(0L);
        zos.putNextEntry(e);
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
        zos.closeEntry();
    }

    // ==================== 读入 ====================

    /** 解包结果 */
    public static final class Parsed {
        /** 顶层元信息（manifest.json） */
        public JSONObject manifest;
        /** 文本/JSON 条目：条目名 → 字节 */
        public final Map<String, byte[]> blobs = new LinkedHashMap<String, byte[]>();
        /** 二进制条目：条目名 → 已解到 tmpDir 的文件 */
        public final Map<String, File> files = new LinkedHashMap<String, File>();
        /** 解压累计字节 */
        public long totalSize;

        /** 取一个文本条目并转成 UTF-8 字符串；不存在返回 null */
        public String text(String name) {
            byte[] b = blobs.get(name);
            if (b == null) return null;
            try { return new String(b, "UTF-8"); } catch (Throwable t) { return null; }
        }

        /** 取一个文本条目并解析成 JSONObject；不存在或解析失败返回 null */
        public JSONObject json(String name) {
            String s = text(name);
            if (s == null) return null;
            return com.tingshu.app.core.Json.obj(s);
        }
    }

    /**
     * 读 ZIP。文本条目进内存，二进制条目解到 {@code tmpDir}。
     *
     * <p>用 {@link ZipInputStream} 顺序读而不是 {@link java.util.zip.ZipFile}，
     * 因为后者要求输入是<b>真实文件</b>，而我们的入口可能是一个 GZIP 流
     * （从 WebDAV 下载时直接是流），顺序读更通用。
     *
     * @param in     输入流（调用方负责关闭）
     * @param tmpDir 二进制条目的落地目录（必须已存在）
     */
    public static Parsed readZip(InputStream in, File tmpDir) throws IOException {
        if (tmpDir == null || !tmpDir.isDirectory()) {
            throw new IOException("临时目录不可用：" + tmpDir);
        }
        final String root = tmpDir.getCanonicalPath();
        Parsed p = new Parsed();

        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        byte[] buf = new byte[BUF];
        while ((e = zis.getNextEntry()) != null) {
            if (e.isDirectory()) continue;

            // ★ 防 zip-slip：条目名先规范化，落盘前再校验真实路径
            String raw = e.getName();
            String name = normEntry(raw);
            if (name.length() == 0) continue;

            // 判定是文本条目还是二进制条目：只看 manifest / data / localstorage 前缀
            boolean asText = isTextEntry(name);

            if (asText) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                long n = copy(zis, bo, buf, MAX_ENTRY);
                p.totalSize += n;
                p.blobs.put(name, bo.toByteArray());
                if (name.equals(ENTRY_MANIFEST)) {
                    p.manifest = com.tingshu.app.core.Json.obj(bo.toString("UTF-8"));
                }
            } else {
                File dest = new File(tmpDir, name);
                // ★ 二次校验：真实路径必须落在 tmpDir 内
                String destPath = dest.getCanonicalPath();
                if (!destPath.equals(root) && !destPath.startsWith(root + File.separator)) {
                    throw new IOException("备份包含非法路径，已中止：" + raw);
                }
                File parent = dest.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream fo = new FileOutputStream(dest);
                long n;
                try {
                    n = copy(zis, fo, buf, MAX_ENTRY);
                } finally {
                    try { fo.close(); } catch (Throwable ignored) {}
                }
                p.totalSize += n;
                p.files.put(name, dest);
            }

            if (p.totalSize > MAX_TOTAL) {
                throw new IOException("备份包解压后超过 " + (MAX_TOTAL / 1024 / 1024) + "MB，已中止");
            }
            zis.closeEntry();
        }
        zis.close();

        if (p.manifest == null) {
            throw new IOException("不是有效的备份包：缺少 manifest.json");
        }
        return p;
    }

    /** 文本条目：manifest.json / data/*.json / localstorage.json / sources/json_sources.json */
    private static boolean isTextEntry(String name) {
        if (name.equals(ENTRY_MANIFEST)) return true;
        if (name.equals("localstorage.json")) return true;
        if (name.equals("sources/json_sources.json")) return true;
        return name.startsWith("data/") && name.endsWith(".json");
    }

    /** 流式复制，同时限制单条目大小 */
    private static long copy(InputStream in, OutputStream out, byte[] buf, long limit)
            throws IOException {
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) throw new IOException("备份包内单个条目过大，已中止");
            out.write(buf, 0, n);
        }
        return total;
    }

    // ==================== 工具 ====================

    /**
     * ★ 条目名安全化 + 防 zip-slip。
     *
     * <p>规则：
     * <ul>
     *   <li>反斜杠统一成斜杠；</li>
     *   <li>去掉盘符（{@code C:}）与开头的斜杠（绝对路径）；</li>
     *   <li>逐段过滤 {@code .} / {@code ..} / 空段 —— 这是 zip-slip 的核心防线；</li>
     *   <li>非 ASCII 字符统一替换成 {@code _}（配合「中文名放 JSON」的设计）。</li>
     * </ul>
     *
     * @return 规范化后的条目名；非法（过滤后为空）返回空串
     */
    public static String normEntry(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\\', '/').trim();

        // 去盘符
        if (s.length() >= 2 && s.charAt(1) == ':') s = s.substring(2);
        // 去开头斜杠（绝对路径）
        while (s.startsWith("/")) s = s.substring(1);

        StringBuilder sb = new StringBuilder();
        String[] parts = s.split("/");
        for (String p : parts) {
            if (p == null || p.length() == 0) continue;
            if (".".equals(p) || "..".equals(p)) continue;   // ★ 关键
            if (sb.length() > 0) sb.append('/');
            sb.append(ascii(p));
        }
        return sb.toString();
    }

    /** 把一段名字转成 ASCII 安全串（非 ASCII → '_'） */
    private static String ascii(String p) {
        StringBuilder sb = new StringBuilder(p.length());
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < 128 && c != '/' && c != '\\' && c != ':' && c != '*' && c != '?'
                    && c != '"' && c != '<' && c != '>' && c != '|' && c >= 32) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String r = sb.toString();
        return r.length() == 0 ? "_" : r;
    }

    /** SHA-256 十六进制小写 */
    public static String sha256(byte[] data) {
        if (data == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 计算「内容校验和」：把指定的若干文本条目按<b>固定顺序</b>拼接后取 SHA-256。
     *
     * <p>为什么不用整个 zip 文件的哈希：zip 里含压缩后字节，
     * 而压缩实现细节（不同 JDK/设备）可能不同，导致同一个逻辑内容算出不同哈希。
     * 对<b>解压后的逻辑内容</b>取哈希才是稳定的。
     */
    public static String contentChecksum(Map<String, byte[]> blobs) {
        if (blobs == null || blobs.isEmpty()) return "";
        List<String> names = new ArrayList<String>(blobs.keySet());
        Collections.sort(names);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String n : names) {
                // 把条目名也混进去，防止「交换两个条目的内容」这种篡改
                md.update(n.getBytes("UTF-8"));
                md.update((byte) 0);
                byte[] d = blobs.get(n);
                if (d != null) md.update(d);
                md.update((byte) 0xFF);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 校验一个已解析的包是不是「本 App 的合法备份」。
     *
     * @return null 表示通过；否则返回给用户看的中文错误原因
     */
    public static String validate(Parsed p) {
        if (p == null || p.manifest == null) return "备份包损坏或不是本 App 的备份";
        JSONObject m = p.manifest;

        String app = m.optString("app", "");
        if (!APP.equals(app)) {
            return "这不是枫兮听书的备份包（标识不匹配）";
        }
        int schema = m.optInt("schema", 0);
        if (schema <= 0) return "备份包缺少格式版本号";
        if (schema > SCHEMA) {
            return "备份包由更新版本的 App 创建，请先升级 App 再恢复";
        }

        // 内容校验和（老包可能没有，有就必须对得上）
        JSONObject ck = m.optJSONObject("checksum");
        if (ck != null) {
            String want = ck.optString("value", "");
            if (want != null && want.length() > 0) {
                String got = contentChecksum(p.blobs);
                if (got.length() > 0 && !want.equalsIgnoreCase(got)) {
                    return "备份包内容校验失败（文件可能已损坏）";
                }
            }
        }
        return null;
    }
}
