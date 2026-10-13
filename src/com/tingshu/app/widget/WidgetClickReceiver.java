package com.tingshu.app.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.tingshu.app.player.PlayerService;

/**
 * ★ v3.8.2：桌面小组件的点击接收器。
 *
 * <h3>为什么必须单独建一个类</h3>
 * 桌面（launcher）是**另一个 App**。它替用户触发小组件上的 PendingIntent 时，
 * 系统会按目标组件的 {@code android:exported} 做检查 —— 目标是别的 App
 * 的组件，就必须 {@code exported="true"}，否则投递被静默丢弃。
 *
 * <p>{@link PlayerWidgetProvider}（AppWidgetProvider）和
 * {@link PlayerService} 在清单里都是 {@code exported="false"}
 * （小组件的状态刷新广播、播放服务都不该对外暴露，这是对的）。
 * 但把**点击**也投给它们，结果就是用户报的「点了没反应，只有点封面
 * 能回 App」。所以本类只做一件事：把点击映射成 PlayerService 的控制指令。
 *
 * <h3>安全性</h3>
 * 只认自己的 action（{@link PlayerWidgetProvider#WIDGET_ACTION}），
 * 且只透传白名单内的五个动作（toggle/prev/next/stop/seek）。
 * 不读文件、不返回数据、不接受任意 action —— 外部应用最多只能
 * 「让播放器切个集」，与任何持有媒体按键权限的应用能力一致，
 * 也和通知栏按钮暴露的能力一致，风险可接受。
 */
public class WidgetClickReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ctx == null || intent == null) return;
        // 严格校验 action，避免被第三方拿来乱发广播
        if (!PlayerWidgetProvider.WIDGET_ACTION.equals(intent.getAction())) return;

        String act = intent.getStringExtra("act");
        if (act == null) return;

        if (PlayerWidgetProvider.WIDGET_SEEK.equals(act)) {
            // 进度条：带目标秒数，透传给 PlayerService（它消费 ACT_SEEK）
            int sec = intent.getIntExtra("sec", -1);
            if (sec < 0) return;
            try {
                Intent i = new Intent(ctx, PlayerService.class);
                i.setAction(PlayerService.ACT_SEEK);
                i.putExtra("sec", sec);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i);
                } else {
                    ctx.startService(i);
                }
            } catch (Throwable ignored) {}
            return;
        }

        String svc = null;
        if (PlayerWidgetProvider.WIDGET_TOGGLE.equals(act)) svc = PlayerService.ACT_TOGGLE;
        else if (PlayerWidgetProvider.WIDGET_PREV.equals(act)) svc = PlayerService.ACT_PREV;
        else if (PlayerWidgetProvider.WIDGET_NEXT.equals(act)) svc = PlayerService.ACT_NEXT;
        else if (PlayerWidgetProvider.WIDGET_STOP.equals(act)) svc = PlayerService.ACT_STOP;
        if (svc != null) PlayerWidgetProvider.dispatch(ctx, svc);
    }
}
