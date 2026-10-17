package com.tingshu.app.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import com.tingshu.app.MainActivity;
import com.tingshu.app.R;
import com.tingshu.app.player.PlayerService;

/**
 * ★ v3.8.2：桌面播放控制小组件（样式参考通知栏播放控制）。
 * ★ v3.9.3：白卡终极修复 —— 布局结构与 build() 调用序列 = v3.8.5 逐字节复刻
 *   （v3.9.2 只收敛动作集没用，问题出在 v3.9.0 重写布局的结构差异，见
 *   {@link #build} 与 widget_player.xml 头注释）。
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
 *       {@code <ImageView>}；背景一律字面颜色，不用 CardView；</li>
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
    /** 点进度条某格 → seek（extra "sec" = 目标秒）。★ v3.9.16 起布局不再挂，
     *  保留常量供 WidgetClickReceiver 兼容旧桌面缓存的小组件点击。 */
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

    /** ★ v3.9.16 兼容模式后不再使用（进度条改为单 ProgressBar），保留常量占位 */

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        if (ids == null) return;
        // ★ v3.8.4：小组件诊断日志（「设置 → 小组件诊断」可查看）
        com.tingshu.app.core.DiagLog.add("[widget] onUpdate ids=" + ids.length);
        for (int id : ids) {
            // ★ v3.9.17：托管探针——桌面真正托管实例时会回报尺寸选项；
            //   若取不到（-1），说明桌面侧绑定就是坏的（与画法无关，
            //   桌面进程状态问题，重启手机/清除桌面数据可解）。
            try {
                android.os.Bundle opt = mgr.getAppWidgetOptions(id);
                int minW = opt == null ? -1
                        : opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, -1);
                int minH = opt == null ? -1
                        : opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, -1);
                String probe = "[widget] host id=" + id + " size=" + minW + "x" + minH;
                if (!probe.equals(lastHostProbe)) {
                    lastHostProbe = probe;
                    com.tingshu.app.core.DiagLog.add(probe + (minW < 0 ? "（未托管！）" : ""));
                }
            } catch (Throwable ignored) {}
            try {
                mgr.updateAppWidget(id, build(ctx));
                // ★ v3.9.14：提交成功也留痕（带内容态），且只在状态变化时记一条，
                //   避免秒级刷新刷屏。若桌面渲染失败，从这里能看出 App 侧是否正常。
                // ★ v3.9.18：带上封面位图字节数——排查「进 App 后白卡」是否与
                //   封面位图相关（大图走 Binder / 桌面解码失败会整块白卡）。
                String state = "has=" + WidgetState.hasContent + " playing=" + WidgetState.playing
                        + " cover=" + (WidgetState.cover == null ? -1
                                : WidgetState.cover.getRowBytes() * WidgetState.cover.getHeight())
                        + " title=" + (WidgetState.title == null ? "" : WidgetState.title);
                if (!state.equals(lastOkState)) {
                    lastOkState = state;
                    com.tingshu.app.core.DiagLog.add("[widget] update OK " + state);
                }
            } catch (Throwable t) {
                // ★ v3.9.14：这里原来是静默 ignored —— Binder 传输失败
                //  （如 TransactionTooLarge）会被无声吞掉，桌面永远收不到更新。
                com.tingshu.app.core.DiagLog.add("[widget] updateAppWidget FAILED: " + t);
            }
        }
    }

    /** 上一次成功提交时的状态摘要（避免秒级刷新刷屏诊断日志） */
    private static String lastOkState = "";

    /** 上一次托管探针结果（去重防刷屏） */
    private static String lastHostProbe = "";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        String a = intent == null ? null : intent.getAction();
        if (a == null) return;

        // ★ v3.8.4：小组件生命周期写入诊断日志
        if (AppWidgetManager.ACTION_APPWIDGET_ENABLED.equals(a)) {
            com.tingshu.app.core.DiagLog.add("[widget] 首个小组件添加/启用");
        } else if (AppWidgetManager.ACTION_APPWIDGET_DELETED.equals(a)) {
            com.tingshu.app.core.DiagLog.add("[widget] 移除一个小组件");
        } else if (AppWidgetManager.ACTION_APPWIDGET_DISABLED.equals(a)) {
            com.tingshu.app.core.DiagLog.add("[widget] 最后一个小组件移除");
        }

        // 状态变化广播（由 WidgetState.requestUpdate 发出）。
        // ★ v3.9.14：不再在这里手动调 onUpdate —— super.onReceive() 对
        //   ACTION_APPWIDGET_UPDATE 本来就会调一次 onUpdate，此前我们再手动
        //   调一次，每次刷新都渲染两遍（诊断日志可见每秒 2 条 onUpdate），
        //   桌面高频收到重复 RemoteViews，部分桌面（originOS 等）会卡住/白卡。
        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(a)) {
            com.tingshu.app.core.DiagLog.add("[widget] onReceive UPDATE（super 已渲染，不再重复）");
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

    /**
     * 组装一块小组件的视图（读 WidgetState 快照）。
     *
     * <p>★ v3.9.16 <b>兼容模式</b>：诊断日志证实 App 侧构建与提交全部成功
     * （update OK、无异常、内容正确），白卡发生在<b>桌面渲染环节</b>。
     * 本版把每次刷新的 RemoteViews 动作从「40+ 个（含 20 个 seek
     * PendingIntent + 20 格上色）」砍到 10 个左右：
     * <ol>
     *   <li>{@code setInt(wgRoot,"setBackgroundColor",背景)}</li>
     *   <li>{@code setTextViewText} × 书名/副标题/两个时间</li>
     *   <li>{@code setImageViewBitmap(wgCover, 封面或 null)}</li>
     *   <li>{@code setProgressBar(wgProgress,…)}（替代 40 格两层结构）</li>
     *   <li>{@code setImageViewResource(wgToggle,播放/暂停)}</li>
     *   <li>{@code setOnClickPendingIntent} × 六处（四键 + 封面 + 文字区）</li>
     *   <li>{@code setBoolean(…,"setSelected",true)} × 2（跑马灯）</li>
     * </ol>
     * 代价：放弃点格子跳进度（App 内可拖进度）。布局同步简化，
     * 四键图标全部用本包资源（v3.9.14 起）。每段独立 try-catch 并写诊断日志。
     */
    private RemoteViews build(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_player);

        boolean has = WidgetState.hasContent;
        boolean playing = WidgetState.playing;

        // ---- 根背景（3.8.5 在 build 里再设一次，这里照抄） ----
        try {
            v.setInt(R.id.wgRoot, "setBackgroundColor", 0xE6222530);
        } catch (Throwable t) {
            diag(ctx, "build root FAILED: " + t);
        }

        // ---- 文案（与通知栏同源：书名 / 作者 · 集数） ----
        try {
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
        } catch (Throwable t) {
            diag(ctx, "build text FAILED: " + t);
        }

        // ---- 封面：没有/超大一律塞 null（该设备实测安全）。
        //      ★ v3.9.18：双保险——即使快照里混进大图也不进 RemoteViews ----
        try {
            android.graphics.Bitmap bm = (has && WidgetState.cover != null)
                    ? WidgetState.cover : null;
            if (bm != null && bm.getRowBytes() * bm.getHeight() > 200 * 1024) {
                diag(ctx, "cover too large (" + (bm.getRowBytes() * bm.getHeight())
                        + "B) → drop");
                bm = null;
            }
            v.setImageViewBitmap(R.id.wgCover, bm);
        } catch (Throwable t) {
            diag(ctx, "build cover FAILED: " + t);
        }

        // ---- 时间文案 ----
        int dur = WidgetState.durSec;
        int cur = WidgetState.curSec;
        boolean seekable = has && dur > 0;
        try {
            v.setTextViewText(R.id.wgTimeCur, seekable ? fmt(cur) : "");
            v.setTextViewText(R.id.wgTimeDur, seekable ? fmt(dur) : "");
        } catch (Throwable t) {
            diag(ctx, "build time FAILED: " + t);
        }

        int pct = 0;
        if (seekable) {
            pct = (int) Math.floor((double) cur * 1000 / dur);
            if (pct < 0) pct = 0;
            if (pct > 1000) pct = 1000;
        }

        // ---- 进度条：★ v3.9.16 兼容模式 —— 单个 ProgressBar + setProgressBar。
        //      旧的「20 显示格 + 20 点击格」带 20 个 PendingIntent，部分桌面
        //      （originOS 等）对 RemoteViews 里挂大量 PendingIntent 渲染失败
        //      → 整块白卡。诊断已证实 App 侧构建/提交全部正常，白卡在桌面环节。 ----
        try {
            v.setProgressBar(R.id.wgProgress, 1000, seekable ? pct : 0, false);
        } catch (Throwable t) {
            diag(ctx, "build progress FAILED: " + t);
        }

        // ---- 播放/暂停图标 ----
        try {
            // ★ v3.9.4：必须用本包资源。RemoteViews 由桌面（另一个 App）
            //   inflate，框架资源 ID（android.R.drawable.*，包 ID 0x01）
            //   在桌面的 Resources 里解析不到，会抛 Resources$NotFoundException，
            //   轻则整块小组件空白、重则拖崩桌面。
            v.setImageViewResource(R.id.wgToggle, playing
                    ? R.drawable.ic_notif_pause
                    : R.drawable.ic_notif_play);
        } catch (Throwable t) {
            diag(ctx, "build toggle FAILED: " + t);
        }

        // ---- 点击：四键（requestCode 11~14）投 exported 的
        //      WidgetClickReceiver；封面/文字区（15/16）打开 App。
        //      与 3.8.5 完全一致的挂点：wgCover、wgInfoTap。 ----
        try {
            v.setOnClickPendingIntent(R.id.wgPrev, xpi(ctx, WIDGET_PREV, 11));
            v.setOnClickPendingIntent(R.id.wgToggle, xpi(ctx, WIDGET_TOGGLE, 12));
            v.setOnClickPendingIntent(R.id.wgNext, xpi(ctx, WIDGET_NEXT, 13));
            v.setOnClickPendingIntent(R.id.wgStop, xpi(ctx, WIDGET_STOP, 14));
            v.setOnClickPendingIntent(R.id.wgCover, openApp(ctx, 15));
            v.setOnClickPendingIntent(R.id.wgInfoTap, openApp(ctx, 16));
        } catch (Throwable t) {
            diag(ctx, "build click FAILED: " + t);
        }

        // 跑马灯：RemoteViews 里的 TextView 要真滚动，渲染后必须设为
        // selected —— marquee 只对"被选中且获得焦点"的 TextView 生效。
        try {
            v.setBoolean(R.id.wgTitle, "setSelected", true);
            v.setBoolean(R.id.wgSub, "setSelected", true);
        } catch (Throwable t) {
            diag(ctx, "build marquee FAILED: " + t);
        }
        return v;
    }

    /** 写一条小组件构建诊断（v3.8.5 同款，出白卡时看这里） */
    private static void diag(Context ctx, String msg) {
        try {
            com.tingshu.app.core.DiagLog.add("[widget] " + msg);
        } catch (Throwable ignored) {}
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
     * 打开 App（点封面 / 书名）
     */
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
