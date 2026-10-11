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
    /** 点进度条某格 → seek（extra "sec" = 目标秒），v3.7.15 */
    public static final String WIDGET_SEEK = "com.tingshu.app.widget.SEEK";
    /**
     * ★ v3.7.16：小组件的三个控制键与进度条统一经由
     * {@link WidgetClickReceiver} 这个**独立且 exported** 的接收器投递。
     *
     * <p>为什么不直接在这里处理（v3.7.15 的做法，已证实失效）：
     * 本 Provider 在清单里是 {@code exported="false"}。桌面（launcher）是
     * **另一个 App**，它替我们触发 PendingIntent 时会被 exported 检查拦下 ——
     * 于是点播放键、点进度条全都静默失败，只有 "打开 App" 那个
     * {@code getActivity} 能生效（Activity 是 exported 的），
     * 表现正好就是用户报的「点击只能回到 App」。
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

        // ★ v3.7.16：控制键 / 进度条点击已改由 WidgetClickReceiver 处理
        //   （本 Provider 是 exported="false"，桌面无法把 PendingIntent 投进来）。
        //   这里只负责状态刷新广播。

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

    /**
     * ★ v3.7.16：把一条控制命令投给 PlayerService。
     *
     * <p>由 {@link WidgetClickReceiver} 调用 —— 那里是 exported 的，
     * 桌面点按钮才递得进来。
     *
     * <p>用 {@code startForegroundService}：进程被回收时也要能把服务拉起来，
     * 否则"点了毫无动静"。Android 8+ 起后台启动前台服务受限，但这里属于
     * **用户交互触发的点击**（有 PendingIntent 的临时代理授权），是允许的。
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
            v.setTextViewText(R.id.wgTimeCur, fmt(cur));
            v.setTextViewText(R.id.wgTimeDur, fmt(dur));
        } else {
            v.setProgressBar(R.id.wgBar, 1000, 0, false);
            v.setTextViewText(R.id.wgTimeCur, "");
            v.setTextViewText(R.id.wgTimeDur, "");
        }

        // ---- ★ v3.7.15 可点进度条：20 格，点第 i 格 seek 到 (i+0.5)/20 ----
        //
        //  RemoteViews 用的是普通 PendingIntent，extras 不参与"同不同一"的判定，
        //  所以 20 格必须配 20 个**不同 requestCode**，否则 FLAG_UPDATE_CURRENT
        //  会让最后注册的那个覆盖全部，点哪一格都跳同一个位置。
        //  这里 requestCode 用 100+i 固定偏移，与三个按钮（11/12/13）、
        //  打开 App（14/15）都不冲突。
        //
        //  秒数直接算进 PendingIntent：目标 = (i + 0.5) / 20 * dur。
        //  没有播放内容时（dur<=0）不挂点击，避免点了乱跳。
        boolean seekable = has && dur > 0;
        for (int i = 0; i < SEEK_SEGMENTS; i++) {
            int viewId = ctx.getResources().getIdentifier(
                    "wgSeek" + i, "id", ctx.getPackageName());
            if (viewId == 0) continue;
            if (!seekable) {
                // 清掉旧 PendingIntent：挂一个"什么都不做"的空 PendingIntent 反而更糟，
                // 直接指向同一个 view 但用 NO_OP 语义难以表达；这里改用"跳到 0"以外
                // 的做法 —— 不设置。RemoteViews 重建时未设置的点击本来就是空的。
                continue;
            }
            int target = (int) ((i + 0.5) * dur / SEEK_SEGMENTS);
            v.setOnClickPendingIntent(viewId, seekPi(ctx, target, 100 + i));
        }

        // ---- 播放/暂停图标 ----
        v.setImageViewResource(R.id.wgToggle, playing
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);

        // ---- 点击 ----
        //
        // ★ v3.7.14：三个按钮各自挂**独立**的 PendingIntent，
        //   并且**不**在 wgRoot（整块面板）上挂任何 PendingIntent。
        //   原来挂在 wgRoot 上，它的点击区域覆盖所有子 View，于是点哪都被
        //   "打开 App"吃掉。
        //
        // ★ v3.7.16：投递目标从本 Provider/PlayerService 换成 exported 的
        //   WidgetClickReceiver —— 前两者都是 exported="false"，桌面作为
        //   另一个 App 触发 PendingIntent 时会被拦住，点击全部静默失效。
        v.setOnClickPendingIntent(R.id.wgToggle, xpi(ctx, WIDGET_TOGGLE, 11));
        v.setOnClickPendingIntent(R.id.wgPrev, xpi(ctx, WIDGET_PREV, 12));
        v.setOnClickPendingIntent(R.id.wgNext, xpi(ctx, WIDGET_NEXT, 13));
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
     * <p>★ v3.7.16 关键修复：改为投给 {@link WidgetClickReceiver}
     * （独立、{@code exported="true"} 的 BroadcastReceiver），
     * <b>不再</b>投给本 Provider / PlayerService。
     *
     * <p>为什么非改不可：本 Provider 在清单里 exported="false"，桌面是另一个 App，
     * 它触发 PendingIntent 时会被 exported 检查拦掉 —— 这就是「三个控制键
     * 和进度条全都没反应、只有点封面能回 App」的根因。投给 service 同样受
     * exported 限制（PlayerService 也是 exported="false"）。
     */
    private PendingIntent xpi(Context ctx, String action, int req) {
        Intent i = new Intent(ctx, WidgetClickReceiver.class);
        i.setAction(WIDGET_ACTION);
        i.putExtra("act", action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, req, i, flags);
    }

    /**
     * ★ v3.7.15 进度条点击的 PendingIntent（v3.7.16 改投 exported 接收器）。
     *
     * <p>Android 判定两个 PendingIntent "是否相同"只看 requestCode + Intent 的
     * action/data/component/categories，<b>不看 extras</b>。所以 20 格必须用
     * 20 个不同 requestCode（见调用处的 {@code 100 + i}），
     * 否则 FLAG_UPDATE_CURRENT 会让最后注册的那个覆盖全部。
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

    /** 打开 App（点面板主体） */
    private PendingIntent openApp(Context ctx, int req) {        Intent i = new Intent(ctx, MainActivity.class);
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
