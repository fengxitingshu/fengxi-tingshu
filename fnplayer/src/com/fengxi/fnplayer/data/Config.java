package com.fengxi.fnplayer.data;

import android.content.Context;
import android.content.SharedPreferences;

/** 独立 SP，不与听书 App 共用（本 App 是独立包名的独立进程/数据目录）。 */
public final class Config {

    private static SharedPreferences sp;
    private static Context appCtx;

    private Config() {}

    public static void init(Context c) {
        appCtx = c.getApplicationContext();
        sp = appCtx.getSharedPreferences("fnplayer", Context.MODE_PRIVATE);
    }

    /** 应用上下文（JS 源缓存等需要）。 */
    public static Context ctx() { return appCtx; }

    /** JS 源脚本的持久缓存（host.readCache/writeCache）。 */
    public static SharedPreferences spCache() {
        return appCtx.getSharedPreferences("jssource", Context.MODE_PRIVATE);
    }

    private static SharedPreferences s() {
        if (sp == null) throw new IllegalStateException("Config 未初始化");
        return sp;
    }

    public static String base()      { return s().getString("base", ""); }
    public static String token()     { return s().getString("token", ""); }
    public static String user()      { return s().getString("user", ""); }
    public static String pass()      { return s().getString("pass", ""); }
    public static String cookie()    { return s().getString("cookie", ""); }
    public static String secret()    { return s().getString("secret", ""); }
    public static String apiKey()    { return s().getString("api_key", ""); }
    public static boolean trustSsl() { return s().getBoolean("trust_ssl", false); }
    public static boolean webMode()  { return s().getBoolean("web_mode", false); }
    public static String lastMdb()   { return s().getString("last_mdb", ""); }

    public static void setBase(String v)     { s().edit().putString("base", v == null ? "" : v.trim()).apply(); }
    public static void setToken(String v)    { s().edit().putString("token", v == null ? "" : v.trim()).apply(); }
    public static void setUser(String v)     { s().edit().putString("user", v == null ? "" : v.trim()).apply(); }
    public static void setPass(String v)     { s().edit().putString("pass", v == null ? "" : v).apply(); }
    public static void setCookie(String v)   { s().edit().putString("cookie", v == null ? "" : v).apply(); }
    public static void setSecret(String v)   { s().edit().putString("secret", v == null ? "" : v.trim()).apply(); }
    public static void setApiKey(String v)   { s().edit().putString("api_key", v == null ? "" : v.trim()).apply(); }
    public static void setTrustSsl(boolean v){ s().edit().putBoolean("trust_ssl", v).apply(); }
    public static void setWebMode(boolean v) { s().edit().putBoolean("web_mode", v).apply(); }
    public static void setLastMdb(String v)  { s().edit().putString("last_mdb", v == null ? "" : v).apply(); }

    /** 登录是否可用（有 token 或 Cookie 均算）。 */
    public static boolean hasCredential() {
        return token().length() > 0 || cookie().length() > 0;
    }

    // ==================== 设置中心（v2.0，分组行式设置页） ====================

    public static int homeColumns()    { return s().getInt("home_columns", 2); }
    public static boolean textureRender() { return s().getBoolean("render_texture", false); }
    public static boolean preferSoft() { return s().getBoolean("prefer_soft", false); }
    public static float longPressSpeed(){ return s().getFloat("long_press_speed", 3.0f); }
    public static int defaultRatio()   { return s().getInt("default_ratio", 0); }
    public static boolean autoNext()   { return s().getBoolean("auto_next", true); }
    public static boolean backgroundPlay() { return s().getBoolean("background_play", false); }
    public static String history()     { return s().getString("history", "[]"); }
    /** 主题模式（v2.11）：auto=跟随系统 / light / dark */
    public static String theme()       { return s().getString("theme", "auto"); }

    public static void setTheme(String v) { s().edit().putString("theme",
            v == null || v.length() == 0 ? "auto" : v).apply(); }

    public static void setHomeColumns(int v)  { s().edit().putInt("home_columns", v).apply(); }
    public static void setTextureRender(boolean v) { s().edit().putBoolean("render_texture", v).apply(); }
    public static void setPreferSoft(boolean v)    { s().edit().putBoolean("prefer_soft", v).apply(); }
    public static void setLongPressSpeed(float v)  { s().edit().putFloat("long_press_speed", v).apply(); }
    public static void setDefaultRatio(int v) { s().edit().putInt("default_ratio", v).apply(); }
    public static void setAutoNext(boolean v) { s().edit().putBoolean("auto_next", v).apply(); }
    public static void setBackgroundPlay(boolean v) { s().edit().putBoolean("background_play", v).apply(); }
    public static void setHistory(String v)   { s().edit().putString("history", v == null ? "[]" : v).apply(); }

    // ==================== v2.22 悬浮小窗位置 ====================

    public static int floatX() { return s().getInt("float_x", -1); }
    public static int floatY() { return s().getInt("float_y", -1); }
    public static void setFloatPos(int x, int y) {
        s().edit().putInt("float_x", x).putInt("float_y", y).apply();
    }

    // ==================== 外挂字幕 ====================

    public static int subSize()       { return s().getInt("sub_size", 18); }
    public static void setSubSize(int v) { s().edit().putInt("sub_size", v).apply(); }

    // ==================== 视频源管理（v2.3，参照听书 App 的源机制） ====================
    // sources: [{id,name,type,api,user,pass}]  type: fnos=飞牛NAS / cms=苹果CMS V10 资源站
    // 选中源时把它的连接参数写入上方"生效字段"（base/token/user/pass...），FnClient 零改动。

    public static String sources()    { return s().getString("video_sources", "[]"); }
    public static void setSources(String v) { s().edit().putString("video_sources", v == null ? "[]" : v).apply(); }
    public static String curSourceId(){ return s().getString("cur_source", ""); }
    public static void setCurSourceId(String v) { s().edit().putString("cur_source", v == null ? "" : v).apply(); }

    // ==================== 直播源（v2.3）：[{id,name,url}]，url 为 m3u/m3u8 列表或文本 ====================

    public static String lives()      { return s().getString("live_sources", "[]"); }
    public static void setLives(String v) { s().edit().putString("live_sources", v == null ? "[]" : v).apply(); }

    /** 记录一条播放历史（去重置顶，最多 50 条）。guid/title 任一为空忽略。 */
    public static void pushHistory(String guid, String title) {
        if (guid == null || guid.length() == 0) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(history());
            org.json.JSONArray out = new org.json.JSONArray();
            org.json.JSONObject item = new org.json.JSONObject();
            item.put("guid", guid);
            item.put("title", title == null ? "" : title);
            item.put("ts", System.currentTimeMillis());
            out.put(item);
            for (int i = 0; i < arr.length() && out.length() < 50; i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o != null && !guid.equals(o.optString("guid"))) out.put(o);
            }
            setHistory(out.toString());
        } catch (Throwable ignored) {}
    }

    public static void clearHistory() { setHistory("[]"); }

    public static void clearCredential() {
        s().edit().putString("token", "").putString("cookie", "").apply();
    }
}
