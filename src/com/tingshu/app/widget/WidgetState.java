package com.tingshu.app.widget;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.Chapter;

import java.util.List;

/**
 * ★ v3.8.2：桌面小组件的数据快照。
 *
 * <h3>为什么需要它</h3>
 * AppWidget 的 {@code onUpdate()} 可能在**任意时刻**被系统触发
 * （周期刷新、开机、用户拖动位置），而播放状态只存在于 App 进程的
 * {@link com.tingshu.app.player.PlayerCore} 里。小组件又不能像通知那样
 * 随时拿到最新状态，所以这里维护一份"最后一次已知状态"的静态快照：
 * 播放状态每次变化都写一份，小组件渲染时直接读它。
 * 进程被杀后快照丢失 —— 此时小组件显示"未在播放"占位，可接受
 * （没有播放就没有可控制的东西）。
 *
 * <h3>与 v3.7.18 删除版的差别</h3>
 * <ul>
 *   <li>封面位图改为<b>常驻持有</b>：删除版"渲染后即置 null"，而秒级进度
 *       刷新每秒都要重建 RemoteViews，第二秒起封面就没了（用户看到的是
 *       「封面闪一下消失」）。现在持有一张 128px 缩略图（与通知栏
 *       {@code PlayerService.coverBitmap()} 同一张源图缩放而来），
 *       内存增量可忽略；</li>
 *   <li>数据直接由 MainActivity 从 PlayerCore 灌入，不再绕路闲置的
 *       PlayState（那个单例目前已无人写入）。</li>
 * </ul>
 */
public final class WidgetState {

    public static volatile boolean hasContent = false;
    public static volatile boolean playing = false;
    public static volatile boolean buffering = false;
    public static volatile String title = "";
    public static volatile String artist = "";
    public static volatile String epText = "";      // 如 "12 / 340"
    public static volatile int index = 0;
    public static volatile int total = 0;
    public static volatile int curSec = 0;
    public static volatile int durSec = 0;
    /** 封面缩略图（128px，常驻持有，保证秒级刷新不丢图） */
    public static volatile Bitmap cover = null;
    /** ★ v3.9.0：当前倍速（小组件角标，如 2.0） */
    public static volatile float rate = 1.0f;
    /** ★ v3.9.0：定时关闭角标文案（"" = 未设置；"⏱ 23分" / "⏱ 停表"） */
    public static volatile String sleepChip = "";

    private WidgetState() {}

    /**
     * 从播放器当前状态刷一次快照。
     *
     * @param book    当前书（null = 没有在放东西）
     * @param chs     章节表（可为 null）
     * @param idx     当前集下标（0 基）
     * @param playing 是否正在播放
     * @param buffering 是否缓冲中
     * @param curSec  当前进度秒
     * @param durSec  总时长秒
     */
    public static void sync(Book book, List<Chapter> chs, int idx,
                            boolean playing, boolean buffering,
                            int curSec, int durSec) {
        if (book == null) {
            clear();
            return;
        }
        hasContent = true;
        WidgetState.playing = playing;
        WidgetState.buffering = buffering;
        title = book.title == null ? "" : book.title;
        artist = book.artist == null ? "" : book.artist;
        index = idx;
        total = chs == null ? 0 : chs.size();
        if (chs != null && idx >= 0 && idx < chs.size()) {
            Chapter c = chs.get(idx);
            int no = (c != null && c.index > 0) ? c.index : (idx + 1);
            epText = no + " / " + chs.size();
        } else {
            epText = "";
        }
        WidgetState.curSec = curSec;
        WidgetState.durSec = durSec;
    }

    /** 只更新进度（秒级 tick 走这条，不重排其它字段） */
    public static void syncProgress(int curSec, int durSec) {
        if (durSec > 0) WidgetState.durSec = durSec;
        if (curSec >= 0) WidgetState.curSec = curSec;
    }

    /** 清空（停止播放 / 切空书时调用） */
    public static void clear() {
        hasContent = false;
        playing = false;
        buffering = false;
        title = "";
        artist = "";
        epText = "";
        index = 0;
        total = 0;
        curSec = 0;
        durSec = 0;
        cover = null;
        // ★ v3.9.0：倍速/定时角标随播放态一起清（定时是全局的，不随书清 ——
        //   见 syncSleepChip，由 SleepTimer 监听单独维护）
    }

    /**
     * 请求刷新所有小组件实例（节流版，普通进度刷新用）。
     */
    public static void requestUpdate(Context ctx) {
        requestUpdate(ctx, false);
    }

    /**
     * 请求刷新所有小组件实例。
     *
     * <p>内部走显式广播（带 ids）而不是 {@code notifyAppWidgetViewDataChanged}：
     * 后者是给集合控件（ListView 等）用的，普通面板要用前者。
     *
     * <p>★ v3.9.14：加节流。★ v3.9.19：App 退后台后节流放宽到 3s——
     * 秒级后台刷新容易触发 originOS 等省电策略的「高耗电」冻结，
     * 冻结后小组件白卡且点不动；前台仍 800ms 保证跟手。
     *
     * @param force true = 关键状态变化，立即刷新不节流
     */
    public static void requestUpdate(Context ctx, boolean force) {
        if (ctx == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        long minGap = appForeground ? 800 : 3000;
        if (!force && now - lastReqMs < minGap) return;   // 节流
        lastReqMs = now;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            if (mgr == null) return;
            ComponentName cn = new ComponentName(ctx, PlayerWidgetProvider.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids == null || ids.length == 0) return;   // 没往桌面放，不白忙
            Intent i = new Intent(ctx, PlayerWidgetProvider.class);
            i.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    /** 上次发起刷新的时间（节流用，elapsedRealtime 基准） */
    private static volatile long lastReqMs = 0;

    /** ★ v3.9.19：App 是否在前台（MainActivity.onResume/onPause 维护） */
    public static volatile boolean appForeground = true;
}
