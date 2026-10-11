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
import com.tingshu.app.player.WidgetState;

/**
 * ★ v3.7.11：桌面播放控制小组件。
 *
 * <h3>功能</h3>
 * 在系统桌面放置一块小面板，直接控制正在播放的有声书：
 * <ul>
 *   <li>显示书名 + 当前集数（如「12 / 340」）+ 封面；</li>
 *   <li>进度条（当前进度，只读，反映播放位置）；</li>
 *   <li>上一集 / 播放暂停 / 下一集 三个按钮。</li>
 * </ul>
 *
 * <h3>怎么驱动播放</h3>
 * 按钮点击走 {@link PlayerService} 已经定义好的控制 action
 * （{@code ACT_PREV/ACT_TOGGLE/ACT_NEXT}），通过
 * {@code PendingIntent.getService} 直接投给服务 —— 不需要 App 界面在前台，
 * 也不需要额外建立连接。这与通知栏按钮走的是**同一条**通路，
 * 所以行为（含 MediaSession 同步、原子随身听的状态回传）完全一致。
 *
 * <h3>原子随身听（iQOO 等）适配</h3>
 * 系统「原子随身听 / 音乐控制中心」读取的是 {@code MediaSession} 的
 * PlaybackState + Metadata。本 App 的 {@link PlayerService} 已经在维护
 * MediaSession（见 PlayerService#session），因此小组件的三键与系统控制中心
 * 天然同源、状态不会打架：谁改都会触发 {@code onStateChanged} →
 * {@link WidgetState#requestUpdate} → 小组件重绘。
 *
 * <h3>为什么不写 updatePeriodMillis 之外的自定义刷新</h3>
 * 小组件刷新全部由播放状态驱动（状态变 → 主动 notify），
 * {@code updatePeriodMillis} 只做兜底（30 分钟），避免空耗电。
 */
public class PlayerWidgetProvider extends AppWidgetProvider {

    /** 点「播放/暂停」 */
    public static final String WIDGET_TOGGLE = "com.tingshu.app.widget.TOGGLE";
    /** 点「上一集」 */
    public static final String WIDGET_PREV = "com.tingshu.app.widget.PREV";
    /** 点「下一集」 */
    public static final String WIDGET_NEXT = "com.tingshu.app.widget.NEXT";

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
        // ★ 按钮点击 → 转发给 PlayerService（走服务 action，与通知栏同一通路）
        String svc = null;
        if (WIDGET_TOGGLE.equals(a)) svc = PlayerService.ACT_TOGGLE;
        else if (WIDGET_PREV.equals(a)) svc = PlayerService.ACT_PREV;
        else if (WIDGET_NEXT.equals(a)) svc = PlayerService.ACT_NEXT;
        if (svc != null) {
            try {
                Intent i = new Intent(ctx, PlayerService.class);
                i.setAction(svc);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i);
                } else {
                    ctx.startService(i);
                }
            } catch (Throwable ignored) {}
            return;
        }
        // 状态变化广播（由 WidgetState.requestUpdate 发出）
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

    /** 组装一块小组件的视图（读 WidgetState 快照） */
    private RemoteViews build(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_player);

        boolean has = WidgetState.hasContent;
        boolean playing = WidgetState.playing;
        boolean buffering = WidgetState.buffering;

        // ---- 文案 ----
        String title = has && !WidgetState.title.isEmpty()
                ? WidgetState.title : "枫兮听书";
        v.setTextViewText(R.id.wgTitle, title);
        String sub;
        if (!has) {
            sub = "点一下，继续听";
        } else if (buffering) {
            sub = "缓冲中…";
        } else {
            String ep = WidgetState.epText;
            String ar = WidgetState.artist == null ? "" : WidgetState.artist;
            if (ep != null && !ep.isEmpty() && ar != null && !ar.isEmpty()) sub = ar + " · " + ep;
            else if (ep != null && !ep.isEmpty()) sub = "第 " + ep + " 集";
            else sub = playing ? "正在播放" : "已暂停";
        }
        v.setTextViewText(R.id.wgSub, sub);

        // ---- 封面 ----
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
            v.setTextViewText(R.id.wgTime, fmt(cur) + " / " + fmt(dur));
        } else {
            v.setProgressBar(R.id.wgBar, 1000, 0, false);
            v.setTextViewText(R.id.wgTime, "");
        }

        // ---- 播放/暂停图标 ----
        v.setImageViewResource(R.id.wgToggle, playing
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);

        // ---- 点击 ----
        //
        // ★ v3.7.14 关键修复：三个按钮各自挂**独立**的 PendingIntent，
        //   并且**不**在 wgRoot（整块面板）上挂任何 PendingIntent。
        //
        //   原来的写法是 v.setOnClickPendingIntent(R.id.wgRoot, openApp(...))，
        //   而 wgRoot 是整块面板的根布局 → 它的点击区域覆盖了所有子 View，
        //   于是无论点哪里（包括三个按钮）都被"打开 App"吃掉，
        //   表现为"点击直接回软件、不能控制"。这就是用户报的两个毛病同一个根因。
        //
        //   现在改成语义化分区：封面区 / 信息区 可点开 App，控制键只管控制。
        v.setOnClickPendingIntent(R.id.wgToggle, pi(ctx, WIDGET_TOGGLE, 11));
        v.setOnClickPendingIntent(R.id.wgPrev, pi(ctx, WIDGET_PREV, 12));
        v.setOnClickPendingIntent(R.id.wgNext, pi(ctx, WIDGET_NEXT, 13));
        // 点封面 / 书名 → 打开 App 播放页（只有这两块）
        v.setOnClickPendingIntent(R.id.wgCoverTap, openApp(ctx, 14));
        v.setOnClickPendingIntent(R.id.wgInfoTap, openApp(ctx, 15));

        // ★ v3.7.14：触发跑马灯。
        //   RemoteViews 里的 TextView 要真的滚动，除了 XML 里设
        //   ellipsize="marquee" + singleLine，还必须在渲染后把它设为 selected ——
        //   marquee 只对"被选中且获得焦点"的 TextView 生效。
        //   书名/副标题过长时就会横向滚动，而不是被截断成"三国演…"。
        try {
            v.setBoolean(R.id.wgTitle, "setSelected", true);
            v.setBoolean(R.id.wgSub, "setSelected", true);
        } catch (Throwable ignored) {}

        // 三键在"没有内容"时置灰（用 alpha 表示），避免点了没反应。
        // ★ v3.7.14：RemoteViews.setFloat 是 API 31 才有的，低版本调用会抛异常，
        //   所以这里只在支持的系统上做淡化，否则保持原样（宁可不淡也别崩）。
        if (Build.VERSION.SDK_INT >= 31) {
            float al = has ? 1f : 0.4f;
            try {
                v.setFloat(R.id.wgPrev, "setAlpha", al);
                v.setFloat(R.id.wgToggle, "setAlpha", has ? 1f : 0.6f);
                v.setFloat(R.id.wgNext, "setAlpha", al);
            } catch (Throwable ignored) {}
        }

        // 快照里的封面用完即释放，不常驻内存
        WidgetState.cover = null;
        return v;
    }

    /**
     * 控制类 PendingIntent。
     *
     * <p>★ v3.7.14 改成 {@code getService} 直投 PlayerService —— 原来走的是
     * {@code getBroadcast} + onReceive 里 startForegroundService 转一手。
     * 那条路在 Android 8+ 上有个坑：从**后台广播**里启动前台服务会被限制，
     * 点一下没反应是常态。直接投给服务就没有这个问题，也与通知栏按钮一致。
     */
    private PendingIntent pi(Context ctx, String action, int req) {
        Intent i = new Intent(ctx, PlayerService.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(ctx, req, i, flags);
    }

    /** 打开 App（点面板主体） */
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
