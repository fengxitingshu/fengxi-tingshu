package com.tingshu.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * ★ v3.8.0 WebDAV 凭据存储。
 *
 * <p><b>为什么用独立的 sp 文件 {@code backup} 而不是塞进业务的 {@code settings}</b>：
 * {@link BackupRepo#buildPackage} 会把 {@code settings} 里的白名单键导出进备份包。
 * 如果 WebDAV 密码也存在那里，就会「备份包里带着自己的备份密码」——
 * 而备份包本身可能被上传到云端/分享出去。独立文件彻底隔离。
 *
 * <p><b>关于明文存储</b>：按用户明确选择，密码明文存在本机
 * （不使用 Android Keystore）。原因是手工构建链下 Keystore 代码量大，
 * 且密钥不可跨设备迁移，换机恢复后密码仍要重填，收益有限。
 * 界面侧密码默认打码显示，只有用户主动点「显示」才明文。
 * 本 sp 文件位于 App 私有目录，未 root 的设备上其它 App 无法读取。
 */
public final class BackupCred {

    private BackupCred() {}

    private static final String FILE = "backup";

    private static final String K_URL = "dav_url";
    private static final String K_USER = "dav_user";
    /** ★ 密码单独一个 key —— 便于单独清理，也便于审计「密码存在哪」 */
    private static final String K_PASS = "dav_pass";
    private static final String K_DIR = "dav_dir";

    /** 默认远端子目录名 */
    public static final String DEFAULT_DIR = "fengxi-backup";

    private static SharedPreferences sp() {
        Context c = com.tingshu.app.core.App.ctx();
        if (c == null) return null;
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static void save(String url, String user, String pass, String dir) {
        SharedPreferences p = sp();
        if (p == null) return;
        p.edit()
                .putString(K_URL, url == null ? "" : url.trim())
                .putString(K_USER, user == null ? "" : user.trim())
                .putString(K_PASS, pass == null ? "" : pass)
                .putString(K_DIR, normDir(dir))
                .commit();
    }

    /** 读全部配置。返回 {ok, url, user, pass, dir, hasPass} */
    public static JSONObject load() {
        JSONObject o = new JSONObject();
        SharedPreferences p = sp();
        try {
            String url = p == null ? "" : p.getString(K_URL, "");
            String user = p == null ? "" : p.getString(K_USER, "");
            String pass = p == null ? "" : p.getString(K_PASS, "");
            String dir = p == null ? "" : p.getString(K_DIR, "");
            o.put("ok", true);
            o.put("url", url);
            o.put("user", user);
            o.put("pass", pass);            // 前端负责打码显示
            o.put("hasPass", pass != null && pass.length() > 0);
            o.put("dir", dir == null || dir.length() == 0 ? DEFAULT_DIR : dir);
        } catch (Throwable ignored) {}
        return o;
    }

    public static String url() {
        SharedPreferences p = sp();
        try { return p == null ? "" : p.getString(K_URL, ""); } catch (Throwable t) { return ""; }
    }

    public static String user() {
        SharedPreferences p = sp();
        try { return p == null ? "" : p.getString(K_USER, ""); } catch (Throwable t) { return ""; }
    }

    public static String pass() {
        SharedPreferences p = sp();
        try { return p == null ? "" : p.getString(K_PASS, ""); } catch (Throwable t) { return ""; }
    }

    public static String dir() {
        SharedPreferences p = sp();
        try {
            String d = p == null ? "" : p.getString(K_DIR, "");
            return (d == null || d.length() == 0) ? DEFAULT_DIR : d;
        } catch (Throwable t) {
            return DEFAULT_DIR;
        }
    }

    /** 是否已配置（有地址即视为已配置） */
    public static boolean configured() {
        String u = url();
        return u != null && u.length() > 0;
    }

    /**
     * 清空凭据。
     * <p>{@code dav_dir} 也一并清掉 —— 用户「取消配置」时应该回到全新状态。
     */
    public static void clear() {
        SharedPreferences p = sp();
        if (p != null) p.edit().clear().commit();
    }

    /** 目录名规范化：去首尾斜杠，空则回默认 */
    private static String normDir(String raw) {
        String d = raw == null ? "" : raw.trim();
        while (d.startsWith("/")) d = d.substring(1);
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        return d.length() == 0 ? DEFAULT_DIR : d;
    }
}
