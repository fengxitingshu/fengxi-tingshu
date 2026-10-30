package com.tingshu.app.core;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * ★ v4.15.0 功能锁（Feature Gate）。
 *
 * <h3>要解决的问题</h3>
 * 转存 / WebDAV / 个人音频库这类「试用」功能，必须保证<b>随时可以把使用权限收回来</b>
 * —— 不能等发布出去之后想锁却锁不上（已发布的 APK 是死的，客户端改不了）。
 *
 * <h3>机制：三层判定</h3>
 * <ol>
 *   <li><b>远程开关（权威）</b>：{@link #REMOTE_URL} 指向一个静态 JSON
 *       （{@code {"dav_export": true, "webdav_lib": false, ...}}）。
 *       每 12 小时拉一次，失败/未配置时静默跳过。远程说了算 —— 只要这个文件一改，
 *       所有在线客户端 12 小时内全部生效，无需发版。</li>
 *   <li><b>本地覆写</b>：远程 JSON 里可带 {@code "_local"} 段之外的直接布尔值，
 *       缓存在 SP（加密无关紧要，只是体验开关）。</li>
 *   <li><b>本地默认</b>：远程拉不到时用 {@link #DEFAULTS} —— 现阶段全部 {@code true}
 *       （免费试用开放）。</li>
 * </ol>
 *
 * <h3>为什么不做客户端硬锁</h3>
 * 客户端开关（哪怕判断写在 native）对旧版本无约束力，也会被破解绕过。
 * 真正「锁得住」的只有远程判定 —— 本类就是那个判定点的唯一入口：
 * 所有试用功能在执行前调 {@link #allow(String)}，拒绝时用 {@link #denyMsg(String)}
 * 给出统一提示。将来上订阅授权（计划 #24）时，把远程判定换成服务端 token 校验即可，
 * 各调用点一行都不用改。
 *
 * <h3>内置功能点</h3>
 * <ul>
 *   <li>{@link #DAV_EXPORT} —— 下载转存 WebDAV（含自动转存与手动批量）</li>
 *   <li>{@link #WEBDAV_LIB} —— 个人音频库（WebDAV 浏览/播放，不含备份恢复）</li>
 *   <li>{@link #DAV_BACKUP} —— 网络备份（WebDAV 上行；恢复永不锁，保用户数据）</li>
 * </ul>
 */
public final class FeatureGate {

    private FeatureGate() {}

    // ==================== 功能点 id ====================

    /** 下载转存 WebDAV（试用） */
    public static final String DAV_EXPORT = "dav_export";
    /** 个人音频库（WebDAV 浏览与播放）（试用） */
    public static final String WEBDAV_LIB = "webdav_lib";
    /** 网络备份（WebDAV 上行）（试用）。恢复操作永不锁定。 */
    public static final String DAV_BACKUP = "dav_backup";
    /** ★ v4.16.0 多设备续听（播放进度经 NAS WebDAV 中转同步）（试用） */
    public static final String SYNC_PROGRESS = "sync_progress";
    /** ★ v4.18.0 网盘听书（pan_* 源：解析分享链/盘内扫描/播放）（易踩坑，可远程收回） */
    public static final String PAN = "pan";
    /** ★ v4.18.0 js/py 自定义源（导入与使用）（易踩坑，可远程收回） */
    public static final String CUSTOM_SRC = "custom_src";
    /** ★ v4.18.0 jar 插件源（导入与加载）（易踩坑，可远程收回） */
    public static final String PLUGIN = "plugin";

    /** 本地默认：现阶段全部开放（免费试用） */
    private static final Map<String, Boolean> DEFAULTS = new HashMap<String, Boolean>();
    static {
        DEFAULTS.put(DAV_EXPORT, Boolean.TRUE);
        DEFAULTS.put(WEBDAV_LIB, Boolean.TRUE);
        DEFAULTS.put(DAV_BACKUP, Boolean.TRUE);
        DEFAULTS.put(SYNC_PROGRESS, Boolean.TRUE);
        DEFAULTS.put(PAN, Boolean.TRUE);
        DEFAULTS.put(CUSTOM_SRC, Boolean.TRUE);
        DEFAULTS.put(PLUGIN, Boolean.TRUE);
    }

    /**
     * 远程开关配置地址（★ v4.15.2 已启用）。
     *
     * <p><b>防破解：地址不明文出现</b> —— Base64 拆两段存放，运行时拼接还原，
     * 反编译 dex 只能看到两个不相干的字符串片段。真正的「锁」在响应签名上：
     * 见 {@link #SIG_SALT_A}。
     */
    private static final String REMOTE_URL_B64_A = "aHR0cHM6Ly9taXkuNT";
    private static final String REMOTE_URL_B64_B = "EzOTY5NTExLnh5eg==";

    /** 远程开关地址（懒取） */
    private static String remoteUrl() {
        try {
            return new String(android.util.Base64.decode(
                    REMOTE_URL_B64_A + REMOTE_URL_B64_B, android.util.Base64.NO_WRAP),
                    "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * ★ v4.15.2 响应签名防伪：防止有人发现 URL 后挂自己的 JSON「永久解锁」。
     *
     * <p>响应体格式约定（两行）：
     * <pre>第一行 = sha256hex(第二行 + 盐)
     * 第二行 = 开关 JSON（{"dav_export": true, ...}）</pre>
     * 客户端校验第一行签名，不匹配 → 视为无效响应，按本地默认（全开）处理。
     * 盐拆两段存放在源码里；就算被逆向，也是"改一处常量重新打包"的成本，
     * 足以挡住随手改 JSON 的破解。**当前 JSON 全为 true（免费试用）**。
     */
    private static final String SIG_SALT_A = "fx@tingshu#";
    private static final String SIG_SALT_B = "gates-2026";

    private static final long REFRESH_MS = 12L * 3600L * 1000L;

    private static final Object LOCK = new Object();
    /** 远程生效的开关（null = 还没拉到远程配置，用 DEFAULTS） */
    private static volatile Map<String, Boolean> remote;
    private static volatile long lastFetch = 0L;

    // ==================== 判定入口 ====================

    /** 该功能当前是否可用。首次调用会顺带尝试刷新远程配置。 */
    public static boolean allow(String feature) {
        ensureFresh();
        Map<String, Boolean> r = remote;
        if (r != null && r.containsKey(feature)) {
            Boolean v = r.get(feature);
            return v != null && v.booleanValue();
        }
        Boolean d = DEFAULTS.get(feature);
        return d == null || d.booleanValue();
    }

    /** 拒绝时的统一提示语 */
    public static String denyMsg(String feature) {
        return "「" + titleOf(feature) + "」试用已暂停，暂时无法使用";
    }

    private static String titleOf(String feature) {
        if (DAV_EXPORT.equals(feature)) return "转存到 NAS";
        if (WEBDAV_LIB.equals(feature)) return "个人音频库";
        if (DAV_BACKUP.equals(feature)) return "网络备份";
        if (SYNC_PROGRESS.equals(feature)) return "多设备续听";
        if (PAN.equals(feature)) return "网盘听书";
        if (CUSTOM_SRC.equals(feature)) return "js/py 自定义源";
        if (PLUGIN.equals(feature)) return "jar 插件源";
        return feature;
    }

    /**
     * ★ v4.18.0：按源实例类型判定该源是否受功能锁约束、当前是否可用。
     *
     * <p>给 {@link com.tingshu.app.source.Aggregator} 的搜索/详情聚合用 ——
     * 锁不只锁「导入」，已导入的源在锁关闭时也要从聚合里退场，才是真正收回：
     * <ul>
     *   <li>{@link com.tingshu.app.pan.PanSource} → {@link #PAN}</li>
     *   <li>{@link com.tingshu.app.source.JsSourceAdapter}（js 源）→ {@link #CUSTOM_SRC}</li>
     *   <li>{@link com.tingshu.app.python.PySourceAdapter}（py 源）→ {@link #CUSTOM_SRC}</li>
     *   <li>其它（内置源 / webdav / local）→ 永远放行，不受锁约束</li>
     * </ul>
     * 反射拿类型避免 core → source/pan/python 的编译期反向依赖。
     */
    public static boolean sourceAllowed(Object src) {
        if (src == null) return true;
        String cn = src.getClass().getName();
        if (cn.startsWith("com.tingshu.app.pan.PanSource")) return allow(PAN);
        if (cn.startsWith("com.tingshu.app.source.JsSourceAdapter")) return allow(CUSTOM_SRC);
        if (cn.startsWith("com.tingshu.app.python.PySourceAdapter")) return allow(CUSTOM_SRC);
        return true;
    }

    // ==================== 远程刷新 ====================

    private static void ensureFresh() {
        final String url = remoteUrl();
        if (url == null || url.length() == 0) return;
        long now = System.currentTimeMillis();
        if (now - lastFetch < REFRESH_MS && (remote != null || lastFetch > 0)) return;
        synchronized (LOCK) {
            now = System.currentTimeMillis();
            if (now - lastFetch < REFRESH_MS && (remote != null || lastFetch > 0)) return;
            lastFetch = now;
            try {
                com.tingshu.app.core.Net.Resp resp =
                        com.tingshu.app.core.Net.request(url, null, null,
                                null, false);
                if (resp != null && resp.code == 200 && resp.body != null
                        && resp.body.length() > 0) {
                    Map<String, Boolean> parsed = parseVerified(resp.body);
                    if (parsed != null) remote = parsed;
                }
            } catch (Throwable ignored) {
                // 拉不到就用上次的缓存 / 本地默认 —— 判定永远有答案，不阻塞任何功能
            }
        }
    }

    /**
     * 解析 + 验签。格式：第一行 hex 签名，第二行起为 JSON。
     * 签名 = sha256hex(JSON文本 + SIG_SALT_A + SIG_SALT_B)。
     * 验签失败 / 格式不对 → 返回 null（保持现有开关状态）。
     */
    static Map<String, Boolean> parseVerified(String body) {
        try {
            String b = body.replace("\r\n", "\n").trim();
            int nl = b.indexOf('\n');
            if (nl <= 0) return null;
            String sig = b.substring(0, nl).trim().toLowerCase();
            String payload = b.substring(nl + 1).trim();
            String expect = sha256Hex(payload + SIG_SALT_A + SIG_SALT_B);
            if (expect == null || !expect.equals(sig)) return null;
            return parse(payload);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) {
                sb.append(Character.forDigit((x >> 4) & 0xf, 16));
                sb.append(Character.forDigit(x & 0xf, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解析远程 flags JSON（宽松：解析失败返回 null，保持现状） */
    static Map<String, Boolean> parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            Map<String, Boolean> m = new HashMap<String, Boolean>();
            Iterator<String> it = o.keys();
            while (it != null && it.hasNext()) {
                String k = it.next();
                m.put(k, o.optBoolean(k, true));
            }
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 测试支持：直接注入远程开关（App 内部诊断页可用，正常流程不走） */
    public static void setRemoteForTest(Map<String, Boolean> flags) {
        remote = flags;
        lastFetch = System.currentTimeMillis();
    }

    /** 诊断用：当前某个功能的判定来源（remote / default）与值 */
    public static String stateOf(String feature) {
        Map<String, Boolean> r = remote;
        boolean v = allow(feature);
        return v + " (" + (r != null && r.containsKey(feature) ? "remote" : "default") + ")";
    }
}
