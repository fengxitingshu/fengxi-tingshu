package com.tingshu.app.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import com.tingshu.app.MainActivity;
import com.tingshu.app.R;
import com.tingshu.app.player.PlayerService;

/**
 * ★ v3.8.2：桌面播放控制小组件（样式参考通知栏播放控制）。
 *
 * <h3>功能</h3>
 * 在系统桌面放置一块面板，直接控制正在播放的有声书：
 * <ul>
 *   <li>封面 + 书名 + 副标题（作者 · 集数），与通知栏同源；</li>
 *   <li>进度条 + 当前/总时长（进度条可点：20 格，每格 5%，点哪跳哪）；</li>
 *   <li>四键：上一集 / 播放暂停 / 下一集 / 停止 —— <b>顺序与通知栏动作
 *       完全一致</b>，用户从通知栏迁到桌面零学习成本。</li>
 * </ul>
 *
 * <h3>怎么驱动播放</h3>
 * 按钮点击走 {@link PlayerService} 已经定义好的控制 action
 * （{@code ACT_PREV/ACT_TOGGLE/ACT_NEXT/ACT_STOP/ACT_SEEK}），
 * 经 {@link WidgetClickReceiver}（exported）转发后
 * {@code startForegroundService} 直接投给服务 —— 不需要 App 界面在前台。
 * 与通知栏按钮是同一条通路，行为（含 MediaSession 同步）完全一致。
 *
 * <h3>三个历史大坑（本版全部规避）</h3>
 * <ol>
 *   <li><b>R8 混淆改名</b>（v3.7.16）：Manifest 注册的 Provider/Receiver
 *       被改名成 a/b，系统按原始类名找不到 → 全失灵。proguard 已 keep
 *       {@code com.tingshu.app.widget.**}，build.sh 有保名闸门兜底；</li>
 *   <li><b>RemoteViews 白名单</b>（v3.7.15）：布局里用 {@code <View>} 做
 *       点击分区，桌面 inflate 失败 → 整块空白。本版 20 格全是
 *       {@code <ImageView>}；</li>
 *   <li><b>exported 投递</b>（v3.7.16）：桌面是另一个 App，把 PendingIntent
 *       投给 exported="false" 的组件会被静默丢弃 → "点了没反应"。
 *       控制类点击全部走 exported 的 {@link WidgetClickReceiver}。</li>
 * </ol>
 */
public class PlayerWidgetProvider extends AppWidgetProvider {

    /** 点「播放/暂停」 */
    public static final String WIDGET_TOGGLE = "com.tingshu.app.widget.TOGGLE";
    /** 点「上一集」 */
    public static final String WIDGET_PREV = "com.tingshu.app.widget.PREV";
    /** 点「下一集」 */
    public static final String WIDGET_NEXT = "com.tingshu.app.widget.NEXT";
    /** 点「停止」（★ v3.8.2 新增，与通知栏动作对齐） */
    public static final String WIDGET_STOP = "com.tingshu.app.widget.STOP";
    /** 点进度条某格 → seek（extra "sec" = 目标秒） */
    public static final String WIDGET_SEEK = "com.tingshu.app.widget.SEEK";
    /**
     * 控制键/进度条的统一投递 action。
     *
     * <p>目标必须是 exported 的 {@link WidgetClickReceiver}：本 Provider
     * 与 PlayerService 在清单里都是 exported="false"（状态刷新广播、
     * 播放服务都不该对外暴露），桌面替用户触发 PendingIntent 时会被
     * exported 检查拦下、静默失败。
     */
    public static final String WIDGET_ACTION =
            "com.tingshu.app.widget.ACTION";

    /** 进度条点击分区数（每格 5%），与布局里 wgSeek0..19 一一对应 */
    static final int SEEK_SEGMENTS = 20;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        if (ids == null) return;
        for (int id : ids) {
            try {
                mgr.updateAppWidget(id, build(ctx));
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        String a = intent == null ? null : intent.getAction();
        if (a == null) return;

        // 状态变化广播（由 WidgetState.requestUpdate 发出）；
        // 另外 APPWIDGET_UPDATE 也会到这里（系统周期刷新 / 用户添加小组件），
        // 一并重绘，保证快照丢了也能画出占位态而不是空白。
        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(a)) {
            int[] ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            if (ids == null) {
                try {
                    ids = AppWidgetManager.getInstance(ctx)
                            .getAppWidgetIds(new ComponentName(ctx, PlayerWidgetProvider.class));
                } catch (Throwable ignored) {}
            }
            onUpdate(ctx, AppWidgetManager.getInstance(ctx), ids == null ? new int[0] : ids);
        }
    }

    /**
     * 把一条控制命令投给 PlayerService。
     *
     * <p>由 {@link WidgetClickReceiver} 调用。用
     * {@code startForegroundService}：进程被回收时也要能把服务拉起来，
     * 否则"点了毫无动静"。Android 8+ 起后台启动前台服务受限，但这里属于
     * **用户交互触发的点击**（PendingIntent 带临时代理授权），是允许的。
     */
    static void dispatch(Context ctx, String svcAction) {
        if (ctx == null || svcAction == null) return;
        try {
            Intent i = new Intent(ctx, PlayerService.class);
            i.setAction(svcAction);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable ignored) {}
    }

    /** 组装一块小组件的视图（读 WidgetState 快照） */
    private RemoteViews build(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_player);

        boolean has = WidgetState.hasContent;
        boolean playing = WidgetState.playing;
        boolean buffering = WidgetState.buffering;

        // ---- 文案（与通知栏同源：书名 / 作者 · 集数） ----
        String title = has && !WidgetState.title.isEmpty()
                ? WidgetState.title : "枫兮听书";
        v.setTextViewText(R.id.wgTitle, title);
        String sub;
        if (!has) {
            sub = "点一下，继续听";
        } else if (!playing && WidgetState.curSec <= 0) {
            // 有内容但没进度也没在播 = 取链/缓冲中（native 的 playing 在
            // prepareAsync 期间还是 false，这个组合正好覆盖转圈阶段）
            sub = "准备中…";
        } else {
            String ep = WidgetState.epText;
            String ar = WidgetState.artist == null ? "" : WidgetState.artist;
            if (ep != null && !ep.isEmpty() && ar != null && !ar.isEmpty()) sub = ar + " · " + ep;
            else if (ep != null && !ep.isEmpty()) sub = "第 " + ep + " 集";
            else sub = playing ? "正在播放" : "已暂停";
        }
        v.setTextViewText(R.id.wgSub, sub);

        // ---- 封面（128px 缩略图常驻快照，秒级刷新不丢图） ----
        if (has && WidgetState.cover != null) {
            try {
                v.setImageViewBitmap(R.id.wgCover, WidgetState.cover);
                v.setViewVisibility(R.id.wgCover, View.VISIBLE);
                v.setViewVisibility(R.id.wgCoverPh, View.GONE);
            } catch (Throwable t) {
                v.setViewVisibility(R.id.wgCover, View.GONE);
                v.setViewVisibility(R.id.wgCoverPh, View.VISIBLE);
            }
        } else {
            v.setViewVisibility(R.id.wgCover, View.GONE);
            v.setViewVisibility(R.id.wgCoverPh, View.VISIBLE);
        }

        // ---- 进度 ----
        int dur = WidgetState.durSec;
        int cur = WidgetState.curSec;
        if (has && dur > 0) {
            v.setProgressBar(R.id.wgBar, 1000,
                    Math.max(0, Math.min(1000, (int) ((long) cur * 1000 / dur))), false);
            v.setTextViewText(R.id.wgTimeCur, fmt(cur));
            v.setTextViewText(R.id.wgTimeDur, fmt(dur));
        } else {
            v.setProgressBar(R.id.wgBar, 1000, 0, false);
            v.setTextViewText(R.id.wgTimeCur, "");
            v.setTextViewText(R.id.wgTimeDur, "");
        }

        // ---- 可点进度条：20 格，点第 i 格 seek 到 (i+0.5)/20 ----
        //
        //  RemoteViews 判定两个 PendingIntent "是否相同"只看 requestCode +
        //  Intent 的 action/data/component/categories，**不看 extras**。
        //  所以 20 格必须配 20 个**不同 requestCode**（100+i），
        //  否则 FLAG_UPDATE_CURRENT 会让最后注册的那个覆盖全部，
        //  点哪一格都跳同一个位置。
        //  秒数直接算进 PendingIntent；没有播放内容（dur<=0）不挂点击。
        boolean seekable = has && dur > 0;
        for (int i = 0; i < SEEK_SEGMENTS; i++) {
            int viewId = ctx.getResources().getIdentifier(
                    "wgSeek" + i, "id", ctx.getPackageName());
            if (viewId == 0) continue;
            if (!seekable) continue;   // 未设置的点击本就是空的，不用显式清
            int target = (int) ((i + 0.5) * dur / SEEK_SEGMENTS);
            v.setOnClickPendingIntent(viewId, seekPi(ctx, target, 100 + i));
        }

        // ---- 播放/暂停图标 ----
        v.setImageViewResource(R.id.wgToggle, playing
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);

        // ---- 点击 ----
        // 四键全部投给 exported 的 WidgetClickReceiver（requestCode 11~14）；
        // 封面/书名才挂"打开 App"（MainActivity 是 exported 的，15/16）。
        v.setOnClickPendingIntent(R.id.wgPrev, xpi(ctx, WIDGET_PREV, 11));
        v.setOnClickPendingIntent(R.id.wgToggle, xpi(ctx, WIDGET_TOGGLE, 12));
        v.setOnClickPendingIntent(R.id.wgNext, xpi(ctx, WIDGET_NEXT, 13));
        v.setOnClickPendingIntent(R.id.wgStop, xpi(ctx, WIDGET_STOP, 14));
        v.setOnClickPendingIntent(R.id.wgCoverTap, openApp(ctx, 15));
        v.setOnClickPendingIntent(R.id.wgInfoTap, openApp(ctx, 16));

        // 跑马灯：RemoteViews 里的 TextView 要真滚动，渲染后必须设为
        // selected —— marquee 只对"被选中且获得焦点"的 TextView 生效。
        try {
            v.setBoolean(R.id.wgTitle, "setSelected", true);
            v.setBoolean(R.id.wgSub, "setSelected", true);
        } catch (Throwable ignored) {}

        // 四键在"没有内容"时置灰（用 alpha 表示），避免点了没反应。
        // ★ setFloat 是 API 31 才有的，低版本调用会抛异常 ——
        //   所以只在支持的系统上淡化，否则保持原样（宁可不淡也别崩）。
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                float al = has ? 1f : 0.4f;
                v.setFloat(R.id.wgPrev, "setAlpha", al);
                v.setFloat(R.id.wgToggle, "setAlpha", has ? 1f : 0.6f);
                v.setFloat(R.id.wgNext, "setAlpha", al);
                v.setFloat(R.id.wgStop, "setAlpha", al);
            } catch (Throwable ignored) {}
        }
        return v;
    }

    /** 控制类 PendingIntent（投 exported 的 WidgetClickReceiver） */
    private PendingIntent xpi(Context ctx, String action, int req) {
        Intent i = new Intent(ctx, WidgetClickReceiver.class);
        i.setAction(WIDGET_ACTION);
        i.putExtra("act", action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, req, i, flags);
    }

    /**
     * 进度条点击的 PendingIntent。
     *
     * <p>秒数由这里按 5% 粒度算好塞进 extra，PlayerService 的 ACT_SEEK 消费。
     */
    private PendingIntent seekPi(Context ctx, int sec, int req) {
        Intent i = new Intent(ctx, WidgetClickReceiver.class);
        i.setAction(WIDGET_ACTION);
        i.putExtra("act", WIDGET_SEEK);
        i.putExtra("sec", sec);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, req, i, flags);
    }

    /** 打开 App（点封面 / 书名） */
    private PendingIntent openApp(Context ctx, int req) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setAction(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(ctx, req, i, flags);
    }

    private static String fmt(int sec) {
        if (sec < 0) sec = 0;
        int m = sec / 60, s = sec % 60;
        if (m >= 60) {
            int h = m / 60; m = m % 60;
            return h + ":" + two(m) + ":" + two(s);
        }
        return m + ":" + two(s);
    }

    private static String two(int n) { return n < 10 ? "0" + n : String.valueOf(n); }
}
