package com.tingshu.app.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 所有标量配置的集中入口。文件名 settings。
 * 全部是简单键值，用 SharedPreferences 就够，不进 SQLite。
 */
public final class Settings {

    private static SharedPreferences SP;
    private static Context CTX;

    private Settings() {}

    public static void init(Context c) {
        CTX = c.getApplicationContext();
        SP = CTX.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static SharedPreferences sp() {
        return SP;
    }

    public static Context ctx() {
        return CTX;
    }

    // ---------- 播放 ----------

    /**
     * 全局默认倍速。
     *
     * <p>★★ v3.9.6 关键修复：**绝不能用 {@code SP.getFloat()}**！
     *
     * <p>{@code SharedPreferencesImpl.getFloat()} 内部是
     * {@code (Float) map.get(key)} 的硬转型 —— 只要这个键存的是
     * Integer/Long/String，就会抛
     * {@code ClassCastException: java.lang.Integer cannot be cast to java.lang.Float}，
     * 而它在 {@code MainActivity.onCreate} 里被调用（{@code core.setSpeed(...)}），
     * 异常直接把 Activity 启动带崩 → **App 一打开就闪退**。
     *
     * <p>这个坏值从哪来：备份恢复（{@code BackupRestore.restoreSettings}）时，
     * 若包内 {@code speed_global} 是 JSON 整数，就会写成 int；之后再读 float 必炸。
     * 旧机型/旧版本升级上来也可能遗留 int 值。
     *
     * <p>现在改为「读原始对象 + 宽容地转成 float」：不管历史存的是
     * Integer / Long / Float / Double / String，都能读出正确倍速，
     * 并顺手把清洗后的 float 值写回去（自愈）。
     */
    public static float speed() {
        float v = 1.0f;
        try {
            Object raw = SP.getAll().get("speed_global");
            if (raw instanceof Number) {
                v = ((Number) raw).floatValue();
            } else if (raw instanceof String) {
                try { v = Float.parseFloat(((String) raw).trim()); } catch (Throwable ignored) {}
            }
            // 顺手自愈：把非 float 的历史值改写成本类型，避免下次再踩
            if (!(raw instanceof Float)) {
                setSpeed(v);
            }
        } catch (Throwable t) {
            v = 1.0f;
        }
        // 兜底：倍速必须落在合法区间，坏值（0/负数/超大）一律回落 1.0
        if (!(v >= 0.5f && v <= 3.0f)) v = 1.0f;
        return v;
    }

    public static void setSpeed(float v) {
        try {
            SP.edit().putFloat("speed_global", v).apply();
        } catch (Throwable ignored) {}
    }

    /** 快进快退步长（秒） */
    public static int ffSeconds() {
        return SP.getInt("ff_seconds", 30);
    }

    public static void setFfSeconds(int v) {
        SP.edit().putInt("ff_seconds", v).apply();
    }

    /** 播放时自动倒回毫秒（防走神），0 = 关 */
    public static int rewindOnPlayMs() {
        return SP.getInt("rewind_on_play_ms", 0);
    }

    public static void setRewindOnPlayMs(int v) {
        SP.edit().putInt("rewind_on_play_ms", v).apply();
    }

    /** 播放模式 0顺序 1单集循环 2列表循环 3随机 */
    public static int playMode() {
        return SP.getInt("play_mode", 0);
    }

    public static void setPlayMode(int v) {
        SP.edit().putInt("play_mode", v).apply();
    }

    /** ★ v4.12.0：自动连播（播完当前集是否自动跳下一集），默认开 */
    public static boolean autoNext() {
        return SP.getBoolean("auto_next", true);
    }

    public static void setAutoNext(boolean v) {
        SP.edit().putBoolean("auto_next", v).apply();
    }

    // ---------- ★ v3.1 音量增益 ----------

    /**
     * 音量增益百分比：100 = 原始音量（默认），范围 50~200。
     *
     * <p>★ v3.9.7：上限 300 → 200 —— 原来的高档位实际听感是削波爆音而非"更响"。
     * 100 以下靠 MediaPlayer 音量衰减，100 以上靠 LoudnessEnhancer 响度增强。
     * 见 {@code PlayerCore.applyGainInternal}。
     */
    public static int gain() {
        int v = SP.getInt("gain_pct", 100);
        if (v < 50) return 50;
        if (v > 300) return 300;
        return v;
    }

    public static void setGain(int v) {
        if (v < 50) v = 50;
        if (v > 300) v = 300;
        SP.edit().putInt("gain_pct", v).apply();
    }

    // ---------- ★ v3.8.1 跳过片头 ----------

    /**
     * 起播时自动跳过的秒数。0 = 关闭（默认）。合法范围 0~300 秒。
     *
     * <p>只在「没有续播进度」时生效：如果这一集之前听到一半，
     * 续播位置优先，不会再跳一次片头（否则每次续听都被往前推）。
     */
    public static int skipIntroSec() {
        int v = SP.getInt("skip_intro_sec", 0);
        if (v < 0) return 0;
        if (v > 300) return 300;
        return v;
    }

    public static void setSkipIntroSec(int v) {
        if (v < 0) v = 0;
        if (v > 300) v = 300;
        try { SP.edit().putInt("skip_intro_sec", v).commit(); }
        catch (Throwable t) { SP.edit().putInt("skip_intro_sec", v).apply(); }
    }

    /**
     * ★ v3.9.34 按专辑（书籍）记录的跳过设置。
     * 播放页「更多 → 跳过片头/片尾」现在写入当前专辑；无记录的书跟随全局。
     * @return 秒数；无专辑记录时返回 -1（调用方回退全局值）
     */
    public static int perBookSkip(String prefix, String bookKey) {
        if (bookKey == null || bookKey.length() == 0) return -1;
        int v = SP.getInt(prefix + bookKey, -1);
        if (v < -1) v = -1;
        return Math.min(v, 300);
    }

    public static void setPerBookSkip(String prefix, String bookKey, int sec) {
        if (bookKey == null || bookKey.length() == 0) return;
        sec = Math.max(0, Math.min(300, sec));
        try { SP.edit().putInt(prefix + bookKey, sec).commit(); }
        catch (Throwable t) { SP.edit().putInt(prefix + bookKey, sec).apply(); }
    }

    /** 当前专辑的跳过片头秒数（无专辑记录 → 全局值） */
    public static int skipIntroFor(String bookKey) {
        int v = perBookSkip("ski_", bookKey);
        return v < 0 ? skipIntroSec() : v;
    }

    /** 当前专辑的跳过片尾秒数（无专辑记录 → 全局值） */
    public static int skipOutroFor(String bookKey) {
        int v = perBookSkip("sko_", bookKey);
        return v < 0 ? skipOutroSec() : v;
    }

    // ---------- ★ v3.8.2 跳过片尾 ----------

    /**
     * 播放到距结尾不足这么多秒时，自动完结本集（走自动下一集 / 停止）。
     * 0 = 关闭（默认）。合法范围 0~300 秒。
     *
     * <p>用于跳过片尾曲 / 结尾广告。与 {@link #skipIntroSec()} 的区别：
     * 片头只在「没有续播进度」时生效，片尾则按**播放位置**判断 ——
     * 只要当前位置落在结尾 N 秒内就生效（含用户手动拖动seek过去）。
     *
     * <p>「单集循环」模式下同样生效：跳过后从头重播本集。
     */
    public static int skipOutroSec() {
        int v = SP.getInt("skip_outro_sec", 0);
        if (v < 0) return 0;
        if (v > 300) return 300;
        return v;
    }

    public static void setSkipOutroSec(int v) {
        if (v < 0) v = 0;
        if (v > 300) v = 300;
        try { SP.edit().putInt("skip_outro_sec", v).commit(); }
        catch (Throwable t) { SP.edit().putInt("skip_outro_sec", v).apply(); }
    }

    // ---------- ★ v3.1 音频焦点（其他媒体声音打断） ----------

    /**
     * 被其他应用（微信语音等）抢占音频时的策略：
     * <ul>
     *   <li>0 = 不处理（默认）。**也不申请音频焦点**，听书不会被任何东西打断</li>
     *   <li>1 = 降低音量（Duck）：对方发声时把音量压到 20%，结束后恢复</li>
     *   <li>2 = 暂停播放：对方发声时暂停，结束后自动续播</li>
     * </ul>
     */
    public static int audioFocusMode() {
        int v = SP.getInt("audio_focus_mode", 0);
        if (v < 0) return 0;
        if (v > 2) return 2;
        return v;
    }

    public static void setAudioFocusMode(int v) {
        if (v < 0) v = 0;
        if (v > 2) v = 2;
        SP.edit().putInt("audio_focus_mode", v).apply();
    }

    // ---------- ★ v3.1 书源验证 ----------
    //
    // ★ v3.2：「启动后自动验证」开关已移除 —— 冷启动时跑十几个源的真实网络请求
    //   会把首屏拖到十几秒，用户观感就是「一打开就卡」。
    //   验证改为完全手动（书源管理页的「批量验证」），这里只保留结果摘要。

    /** 上次验证的结果摘要（一行字），源管理页顶部展示；空串表示还没跑过 */
    public static String lastVerifySummary() {
        return SP.getString("last_verify_summary", "");
    }

    public static void setLastVerifySummary(String v) {
        SP.edit().putString("last_verify_summary", v == null ? "" : v).apply();
    }

    /** 仅 WiFi 播放 */
    public static boolean wifiPlayOnly() {
        return SP.getBoolean("wifi_play_only", false);
    }

    public static void setWifiPlayOnly(boolean v) {
        SP.edit().putBoolean("wifi_play_only", v).apply();
    }

    /** 点悬浮球进全屏播放页时，是否立刻开始播放（默认开） */
    public static boolean playOnFull() {
        return SP.getBoolean("play_on_full", true);
    }

    public static void setPlayOnFull(boolean v) {
        SP.edit().putBoolean("play_on_full", v).apply();
    }

    // ==================== ★ v2.8 悬浮球 ====================

    /** 悬浮球开关（默认关，需用户主动开启并授予悬浮窗权限） */
    public static boolean floatBallEnabled() {
        return SP.getBoolean("float_ball", false);
    }

    public static void setFloatBallEnabled(boolean v) {
        SP.edit().putBoolean("float_ball", v).apply();
    }

    /** 悬浮球 X 坐标（px）。-1 表示「还没摆过」，显示时取默认右侧位置 */
    public static int floatBallX() {
        return SP.getInt("float_ball_x", -1);
    }

    public static void setFloatBallX(int v) {
        SP.edit().putInt("float_ball_x", v).apply();
    }

    /** 悬浮球 Y 坐标（px） */
    public static int floatBallY() {
        return SP.getInt("float_ball_y", -1);
    }

    public static void setFloatBallY(int v) {
        SP.edit().putInt("float_ball_y", v).apply();
    }

    // ---------- ★ v3.8.1：悬浮球位置改为「归一化」存储 ----------
    //
    // 以前存的是绝对像素。两个后果：
    //   ① 横竖屏切换 / 分屏 / 折叠屏展开后，屏幕尺寸变了，旧的绝对坐标
    //      会被 clamp 到一个莫名其妙的位置（典型就是 y 被归零 → 右上角）；
    //   ② 松手吸附时允许 y 为负（FLAG_LAYOUT_NO_LIMITS 让球能拖出上边界），
    //      clamp 成 0 之后存下来，下次打开就在右上角。
    // 现在存「靠左还是靠右」+「纵向千分比」，屏幕怎么变都能回到对的地方。

    /** 吸附侧：0=左，1=右，-1=没存过 */
    public static int floatBallSide() {
        return SP.getInt("float_ball_side", -1);
    }

    public static void setFloatBallSide(int v) {
        SP.edit().putInt("float_ball_side", v).apply();
    }

    /** 纵向位置千分比（0~1000）。-1=没存过 */
    public static int floatBallYR() {
        return SP.getInt("float_ball_yr", -1);
    }

    public static void setFloatBallYR(int v) {
        SP.edit().putInt("float_ball_yr", v).apply();
    }

    // ==================== ★ v2.9 下载目录（SAF） ====================

    /**
     * 下载要「移动」到的外部文件夹（SAF 目录树 URI）。
     * 空字符串 = 只保留在 App 私有目录（其它播放器看不到）。
     */
    public static String downloadTreeUri() {
        return SP.getString("download_tree_uri", "");
    }

    public static void setDownloadTreeUri(String v) {
        SP.edit().putString("download_tree_uri", v == null ? "" : v).apply();
    }

    /** 该目录的展示名（如「Music」），仅用于设置页显示 */
    public static String downloadTreeName() {
        return SP.getString("download_tree_name", "");
    }

    public static void setDownloadTreeName(String v) {
        SP.edit().putString("download_tree_name", v == null ? "" : v).apply();
    }

    /** 是否配了外部下载目录 */
    public static boolean hasDownloadTree() {
        String s = downloadTreeUri();
        return s != null && s.length() > 0;
    }

    /** 定时关闭：分钟数，0 = 关 */
    public static int sleepMinutes() {
        return SP.getInt("sleep_minutes", 0);
    }

    public static void setSleepMinutes(int v) {
        SP.edit().putInt("sleep_minutes", v).apply();
    }

    /** 定时到点后完全退出 App */
    public static boolean exitOnTimer() {
        return SP.getBoolean("exit_on_timer", false);
    }

    public static void setExitOnTimer(boolean v) {
        SP.edit().putBoolean("exit_on_timer", v).apply();
    }

    /** 听 N 集后暂停，0 = 关 */
    public static int pauseAfterChapters() {
        return SP.getInt("pause_after_chapters", 0);
    }

    public static void setPauseAfterChapters(int v) {
        SP.edit().putInt("pause_after_chapters", v).apply();
    }

    // ---------- 缓存 ----------

    public static boolean autoCacheEnabled() {
        return SP.getBoolean("auto_cache_enabled", true);
    }

    public static void setAutoCacheEnabled(boolean v) {
        SP.edit().putBoolean("auto_cache_enabled", v).apply();
    }

    /** 自动缓存后 N 集 */
    public static int autoCacheCount() {
        return SP.getInt("auto_cache_count", 3);
    }

    public static void setAutoCacheCount(int v) {
        SP.edit().putInt("auto_cache_count", v).apply();
    }

    public static boolean wifiCacheOnly() {
        return SP.getBoolean("wifi_cache_only", false);
    }

    public static void setWifiCacheOnly(boolean v) {
        SP.edit().putBoolean("wifi_cache_only", v).apply();
    }

    /** 缓存上限 MB */
    public static int cacheLimitMb() {
        return SP.getInt("cache_limit_mb", 2048);
    }

    public static void setCacheLimitMb(int v) {
        SP.edit().putInt("cache_limit_mb", v).apply();
    }

    // ---------- 界面 ----------

    /** system / light / dark */
    public static String theme() {
        return SP.getString("theme", "system");
    }

    /**
     * ★ v3.8.1：主题必须用 {@code commit()} 而不是 {@code apply()}。
     * apply() 是异步落盘，用户选完深色立刻杀进程（或系统回收）时，
     * 最后一次写入还没刷到磁盘 → 重启又变回白色。主题是低频写但高感知的
     * 设置，同步写的代价可以忽略。
     */
    public static void setTheme(String v) {
        try { SP.edit().putString("theme", v).commit(); }
        catch (Throwable t) { SP.edit().putString("theme", v).apply(); }
    }

    public static String themeColor() {
        return SP.getString("theme_color", "#E8503A");
    }

    public static void setThemeColor(String v) {
        try { SP.edit().putString("theme_color", v).commit(); }
        catch (Throwable t) { SP.edit().putString("theme_color", v).apply(); }
    }

    // ---------- 源 ----------

    public static String lastSource() {
        return SP.getString("last_source", "");
    }

    public static void setLastSource(String v) {
        SP.edit().putString("last_source", v).apply();
    }

    /** 精确搜索时选中的源 id，逗号分隔；空 = 全部 */
    public static String searchSources() {
        return SP.getString("search_sources", "");
    }

    public static void setSearchSources(String v) {
        SP.edit().putString("search_sources", v).apply();
    }

    /** 忽略的版本号（自更新用） */
    public static String ignoreVersion() {
        return SP.getString("ignore_version", "");
    }

    public static void setIgnoreVersion(String v) {
        SP.edit().putString("ignore_version", v).apply();
    }

    // ---------- 前端统一写入入口（白名单） ----------

    /**
     * 按 key 写设置。白名单制：未知 key 直接忽略，避免前端乱写。
     * 值统一是字符串（JS 只有 number/boolean，都转成字符串传）。
     */
    public static void apply(String key, String value) {
        if (key == null) return;
        // boolean 类
        if ("wifi_play_only".equals(key)) { setWifiPlayOnly(b(value)); return; }
        if ("play_on_full".equals(key)) { setPlayOnFull(b(value)); return; }
        if ("wifi_cache_only".equals(key)) { setWifiCacheOnly(b(value)); return; }
        if ("auto_cache_enabled".equals(key)) { setAutoCacheEnabled(b(value)); return; }
        if ("exit_on_timer".equals(key)) { setExitOnTimer(b(value)); return; }
        if ("float_ball".equals(key)) { setFloatBallEnabled(b(value)); return; }
        if ("download_tree".equals(key)) { setDownloadTreeUri(value); return; }
        if ("auto_next".equals(key)) { setAutoNext(b(value)); return; }
        // int 类
        if ("ff_seconds".equals(key)) { setFfSeconds(i(value, 30)); return; }
        if ("auto_cache_count".equals(key)) { setAutoCacheCount(i(value, 3)); return; }
        if ("cache_limit_mb".equals(key)) { setCacheLimitMb(i(value, 2048)); return; }
        if ("play_mode".equals(key)) { setPlayMode(i(value, 0)); return; }
        if ("pause_after_chapters".equals(key)) { setPauseAfterChapters(i(value, 0)); return; }
        if ("sleep_minutes".equals(key)) { setSleepMinutes(i(value, 0)); return; }
        if ("rewind_on_play_ms".equals(key)) { setRewindOnPlayMs(i(value, 0)); return; }
        // ★ v4.14.0：进度条锚点动效（0~5）与下载转存 WebDAV 开关
        if ("bar_fx".equals(key)) { setBarFx(i(value, 1)); return; }
        if ("dav_export".equals(key)) { setWebdavExportOn(b(value)); return; }
        // ★ v3.1：音量增益（50~300）与其他声音打断策略（0/1/2）
        if ("gain_pct".equals(key)) { setGain(i(value, 100)); return; }
        if ("audio_focus_mode".equals(key)) { setAudioFocusMode(i(value, 0)); return; }
        // float 类
        if ("speed_global".equals(key)) { setSpeed(f(value, 1.0f)); return; }
        // string 类
        if ("theme".equals(key)) { setTheme(value); return; }
        if ("theme_color".equals(key)) { setThemeColor(value); return; }
        if ("last_source".equals(key)) { setLastSource(value); return; }
        if ("search_sources".equals(key)) { setSearchSources(value); return; }
        // 未知 key：忽略
    }

    // ==================== ★ v3.9.43 WebDAV 自签名证书 ====================

    /**
     * 家庭 NAS（飞牛/群晖等）的 HTTPS 全是自签名证书。默认拒绝（安全底线）；
     * 只有用户在备份页明确开启「信任自签名证书」后才放宽——且只影响
     * WebDAV 这条连接，不波及 App 其它网络请求。
     */
    // ==================== ★ v4.14.0 进度条锚点动效 + 下载转存 ====================

    /** 进度条锚点动效：0 无 / 1 呼吸光晕 / 2 脉冲波纹 / 3 轨道环绕 / 4 心跳脉动 / 5 霓虹闪烁 */
    public static int barFx() {
        try { return sp().getInt("bar_fx", 1); } catch (Throwable t) { return 1; }
    }
    public static void setBarFx(int v) {
        try { SP.edit().putInt("bar_fx", v).apply(); } catch (Throwable ignored) {}
    }

    /** 下载转存 WebDAV（试用功能）：下载完成后自动把文件传到备份服务器 */
    public static boolean webdavExportOn() {
        try { return sp().getBoolean("dav_export_on", false); } catch (Throwable t) { return false; }
    }
    public static void setWebdavExportOn(boolean v) {
        try { SP.edit().putBoolean("dav_export_on", v).apply(); } catch (Throwable ignored) {}
    }

    /** ★ v4.16.0 多设备续听：本地开关（远程功能锁 sync_progress 另判，两处都过才同步） */
    public static boolean progressSyncOn() {
        try { return sp().getBoolean("progress_sync_on", true); } catch (Throwable t) { return true; }
    }
    public static void setProgressSyncOn(boolean v) {
        try { sp().edit().putBoolean("progress_sync_on", v).apply(); } catch (Throwable ignored) {}
    }

    /** ★ v4.16.0 设备标识：多设备续听里区分进度来源，首次生成后固定 */
    public static String deviceId() {
        try {
            String v = sp().getString("device_id", "");
            if (v != null && v.length() > 0) return v;
            v = java.util.UUID.randomUUID().toString().substring(0, 8);
            sp().edit().putString("device_id", v).apply();
            return v;
        } catch (Throwable t) { return "dev"; }
    }

    public static boolean davTrustAllSsl() {
        try { return sp().getBoolean("dav_trust_ssl", false); } catch (Throwable t) { return false; }
    }

    public static void setDavTrustAllSsl(boolean on) {
        try { sp().edit().putBoolean("dav_trust_ssl", on).apply(); } catch (Throwable ignored) {}
    }

    private static boolean b(String v) {
        return v != null && ("true".equalsIgnoreCase(v) || "1".equals(v));
    }

    private static int i(String v, int def) {
        try {
            return Integer.parseInt(v);
        } catch (Throwable t) {
            return def;
        }
    }

    private static float f(String v, float def) {
        try {
            return Float.parseFloat(v);
        } catch (Throwable t) {
            return def;
        }
    }
}
