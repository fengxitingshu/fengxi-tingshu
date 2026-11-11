package com.fengxi.fnplayer.player;

import java.util.Map;

/**
 * v2.22 悬浮小窗移交载体（同进程静态单例）。
 * <ul>
 *   <li>方案 A：Activity 把 IjkHolder 引用交给浮窗 Service，Surface 就绪后
 *       {@link IjkHolder#setSurface} 换绑，解码管线不重建 → 零黑屏；</li>
 *   <li>holder != null 表示已移交浮窗：Activity onDestroy 的 release 必须
 *       以 transferred 守卫跳过，Service 停止时统一 release 并 clear；</li>
 *   <li>移交失败（holder 为 null/错误态）由 Service 走方案 B：
 *       用 guid/item/epIdx 重取链 + resumePos 续播。</li>
 * </ul>
 */
public final class PlaySession {

    /** 非 null = 播放器已移交浮窗（Activity 不得 release）。 */
    public static volatile IjkHolder holder;

    /** 移交时刻进度与播放地址（兜底/续播用）。 */
    public static String title = "";
    public static String playUrl = "";
    public static Map<String, String> headers;
    public static long posMs;

    /** 回全屏所需元数据。 */
    public static String backActivity = "";   // "short" / "video"
    public static String guid = "";
    public static String item = "";
    public static int epIdx;
    public static boolean portrait;           // 竖屏短剧 = true（浮窗宽高比 9:16）
    public static boolean live;               // 直播流（回全屏带 live extra）

    private PlaySession() {}

    /**
     * Activity → 浮窗移交。成功后调用方必须把 transferred 置 true 并 finish。
     *
     * @return false = 无可移交播放器（调用方走重取链兜底路径）
     */
    public static synchronized boolean takeover(IjkHolder h, String t, long pos,
                                                boolean isPortrait, String back, String g,
                                                String itemGuid, int epIndex, boolean isLive) {
        if (h == null) return false;
        holder = h;
        title = t == null ? "" : t;
        posMs = Math.max(0, pos);
        portrait = isPortrait;
        backActivity = back == null ? "" : back;
        guid = g == null ? "" : g;
        item = itemGuid == null ? "" : itemGuid;
        epIdx = epIndex;
        live = isLive;
        return true;
    }

    /** 浮窗关闭/回全屏成功后清空会话。 */
    public static synchronized void clear() {
        holder = null;
        title = "";
        playUrl = "";
        headers = null;
        posMs = 0;
        backActivity = "";
        guid = "";
        item = "";
        epIdx = 0;
        portrait = false;
        live = false;
    }

    public static synchronized boolean active() { return holder != null; }
}
