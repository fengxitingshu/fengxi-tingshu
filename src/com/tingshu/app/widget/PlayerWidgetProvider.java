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

    /** 进度条点击分区数（每格 5%） */

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

    /** 进度格数（20 格，每格 5%，点击跳转） */
    static final int SEEK_SEGMENTS = 20;

    /**
     * 组装一块小组件的视图（读 WidgetState 快照）。
     *
     * <p>★ v3.9.24 正式版 —— 白卡元凶已定位并移除：<b>跑马灯动作</b>
     * （{@code setBoolean(wgTitle,"setSelected",true)}）。originOS 桌面对
     * RemoteViews 内 marquee 滚动兼容性差，视图挂载数秒后触发崩溃 → 整块
     * 白卡且点不动。布局里的 marquee 属性已全部移除（超长书名改为省略号）。
     *
     * <p>本版恢复完整功能：
     * <ol>
     *   <li>背景：透明底 + 白色半透明柔边描边（唯一样式）；</li>
     *   <li>封面：有则贴图（≤200KB 双保险），无则跳过动作；</li>
     *   <li>进度格：20 透明点击格挂 seek（可拖动跳转）；</li>
     *   <li>四键：上一集/播放暂停/下一集/停止（本包矢量图）；</li>
     *   <li>点击：封面/文字区打开 App。</li>
     * </ol>
     * 每段独立 try-catch 并写诊断日志。
     */
    private RemoteViews build(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_player);

        boolean has = WidgetState.hasContent;
        boolean playing = WidgetState.playing;

        String title = has && !WidgetState.title.isEmpty()
                ? WidgetState.title : "枫兮听书";
        String sub;
        if (!has) {
            sub = "点一下，继续听";
        } else if (!playing && WidgetState.curSec <= 0) {
            sub = "准备中…";
        } else {
            String ep = WidgetState.epText;
            String ar = WidgetState.artist == null ? "" : WidgetState.artist;
            if (ep != null && !ep.isEmpty() && ar != null && !ar.isEmpty()) sub = ar + " · " + ep;
            else if (ep != null && !ep.isEmpty()) sub = "第 " + ep + " 集";
            else sub = playing ? "正在播放" : "已暂停";
        }

        // ---- 背景 ★ v3.9.33 只保留「材质」一种样式 ----
        // 透明底 + 白字白图标 + 白色半透明柔边（描边 drawable）。
        // 浅色玻璃/不透明度档位已按用户反馈移除（只留一种，简单直接）。
        try {
            v.setInt(R.id.wgRoot, "setBackgroundResource",
                    R.drawable.wg_frame_glass);
            v.setInt(R.id.wgBody, "setBackgroundColor", 0x00000000);
            v.setTextColor(R.id.wgTitle, 0xFFFFFFFF);
            v.setTextColor(R.id.wgSub, 0xC9FFFFFF);
            v.setTextColor(R.id.wgTimeCur, 0xC9FFFFFF);
            v.setTextColor(R.id.wgTimeDur, 0xC9FFFFFF);
            v.setInt(R.id.wgCover, "setBackgroundColor", 0x33FFFFFF);
            // 四键矢量图标重着色（XML 里是白色 tint，保持一致）
            v.setInt(R.id.wgPrev, "setColorFilter", 0xFFFFFFFF);
            v.setInt(R.id.wgToggle, "setColorFilter", 0xFFFFFFFF);
            v.setInt(R.id.wgNext, "setColorFilter", 0xFFFFFFFF);
            v.setInt(R.id.wgStop, "setColorFilter", 0xFFFFFFFF);
        } catch (Throwable t) {
            diag(ctx, "build bg FAILED: " + t);
        }

        // ---- 文案（与通知栏同源：书名 / 作者 · 集数） ----
        try {
            v.setTextViewText(R.id.wgTitle, title);
            v.setTextViewText(R.id.wgSub, sub);
        } catch (Throwable t) {
            diag(ctx, "build text FAILED: " + t);
        }

        // ---- 封面：没有则跳过动作；超 200KB 双保险丢弃 ----
        try {
            android.graphics.Bitmap bm = (has && WidgetState.cover != null)
                    ? WidgetState.cover : null;
            if (bm != null && bm.getRowBytes() * bm.getHeight() > 200 * 1024) {
                diag(ctx, "cover too large (" + (bm.getRowBytes() * bm.getHeight())
                        + "B) → drop");
                bm = null;
            }
            if (bm != null) v.setImageViewBitmap(R.id.wgCover, bm);
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

        // ---- 显示层：wgBar0..19 逐格上色（已播白 / 未播半透明白）。
        //      ★ v3.9.26：高亮球按用户反馈移除（观感违和） ----
        int filled = 0;
        if (seekable) {
            filled = (int) Math.floor((double) cur * SEEK_SEGMENTS / dur);
            if (filled < 0) filled = 0;
            if (filled > SEEK_SEGMENTS) filled = SEEK_SEGMENTS;
        }
        try {
            for (int i = 0; i < SEEK_SEGMENTS; i++) {
                int viewId = ctx.getResources().getIdentifier(
                        "wgBar" + i, "id", ctx.getPackageName());
                if (viewId == 0) continue;
                v.setInt(viewId, "setBackgroundColor",
                        i < filled ? 0xF2FFFFFF : 0x33FFFFFF);
            }
        } catch (Throwable t) {
            diag(ctx, "build progress FAILED: " + t);
        }

        // ---- 点击层：wgSeek0..19 挂 seek PendingIntent（点哪跳哪，5% 粒度）。
        //      RemoteViews 判定 PendingIntent 是否相同不看 extras，
        //      所以 20 格必须各配一个 requestCode（100+i） ----
        if (seekable) {
            try {
                for (int i = 0; i < SEEK_SEGMENTS; i++) {
                    int viewId = ctx.getResources().getIdentifier(
                            "wgSeek" + i, "id", ctx.getPackageName());
                    if (viewId == 0) continue;
                    int target = (int) ((i + 0.5) * dur / SEEK_SEGMENTS);
                    v.setOnClickPendingIntent(viewId, seekPi(ctx, target, 100 + i));
                }
            } catch (Throwable t) {
                diag(ctx, "build seek FAILED: " + t);
            }
        }

        // ---- 播放/暂停图标（本包矢量资源） ----
        try {
            v.setImageViewResource(R.id.wgToggle, playing
                    ? R.drawable.ic_notif_pause
                    : R.drawable.ic_notif_play);
        } catch (Throwable t) {
            diag(ctx, "build toggle FAILED: " + t);
        }

        // ---- 点击：四键（requestCode 11~14）投 exported 的
        //      WidgetClickReceiver；封面/文字区（15/16）打开 App ----
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

        // ★ v3.9.24：跑马灯动作（setBoolean setSelected）已永久移除 ——
        //   originOS 桌面白卡元凶，代价是超长书名不再滚动（省略号截断）。
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
     * 进度条点击的 PendingIntent：秒数按 5% 粒度算好塞 extra，
     * 经 WidgetClickReceiver 转成 PlayerService 的 ACT_SEEK。
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
