package com.tingshu.app.player;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.widget.PlayerWidgetProvider;

/**
 * ★ v3.7.11：桌面小组件的数据快照。
 *
 * <h3>为什么需要它</h3>
 * {@link PlayState} 是进程内单例，只在 App 进程活着时有效。而 AppWidget 的
 * {@code onUpdate()} 可能在**任意时刻**被系统触发（周期刷新、开机、用户拖动），
 * 那时播放服务可能没起来。小组件又不能像通知那样随时拿到最新状态。
 *
 * <p>所以这里维护一份"最后一次已知状态"的静态快照：播放状态每次变化都写一份，
 * 小组件渲染时直接读它。进程被杀后快照丢失 —— 此时小组件会显示"未在播放"，
 * 这是可接受的（没有播放就没有可控制的东西）。
 *
 * <p>快照里带 {@link Bitmap} 封面，但**不长期持有**：写入后由 Provider 立刻
 * 渲染进 RemoteViews 就释放，避免常驻内存占一张大图。
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
    /** 封面位图（渲染后即释放，不常驻） */
    public static volatile Bitmap cover = null;

    private WidgetState() {}

    /**
     * 从当前 {@link PlayState} 刷一次快照。
     *
     * <p>由 PlayState 的 Listener（PlayerService）在状态/进度变化时调用，
     * 顺带推一次小组件刷新。
     */
    public static void syncFrom(PlayState st) {
        if (st == null) return;
        try {
            hasContent = st.hasContent();
            playing = st.playing;
            buffering = st.buffering;
            index = st.index;
            curSec = st.curSec;
            durSec = st.durSec;
            if (st.book != null) {
                title = st.book.title == null ? "" : st.book.title;
                artist = st.book.artist == null ? "" : st.book.artist;
            } else {
                title = "";
                artist = "";
            }
            if (st.chapters != null && !st.chapters.isEmpty()) {
                total = st.chapters.size();
                Chapter c = st.currentChapter();
                int no = (c != null && c.index > 0) ? c.index : (st.index + 1);
                epText = no + " / " + total;
            } else {
                total = 0;
                epText = "";
            }
        } catch (Throwable ignored) {}
    }

    /** 清空（停止播放后调用） */
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
    }

    /** 请求刷新所有小组件实例 */
    public static void requestUpdate(Context ctx) {
        if (ctx == null) return;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            ComponentName cn = new ComponentName(ctx, PlayerWidgetProvider.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids == null || ids.length == 0) return;
            Intent i = new Intent(ctx, PlayerWidgetProvider.class);
            i.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {}
    }
}
