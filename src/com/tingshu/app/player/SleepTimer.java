package com.tingshu.app.player;

import android.os.Handler;
import android.os.Looper;

import com.tingshu.app.data.Settings;

/**
 * ★ v3.9.0：定时关闭（含「可选时段关闭」）。
 *
 * <h3>为什么从纯前端挪到原生</h3>
 * 旧实现是播放页里的一段 {@code setInterval}：WebView 页面一旦被销毁
 * （用户回了首页、App 退了后台、系统回收了页面），倒计时就跟着消失，
 * 到点不会暂停 —— 用户睡前定了 30 分钟，半夜却被声音吵醒。
 * 本类把倒计时放进 App 进程常驻（主 Looper 轮询），页面只负责展示与设置。
 *
 * <h3>两种模式</h3>
 * <ul>
 *   <li><b>倒计时</b>：N 分钟后暂停（沿用旧行为，0 = 关闭）；</li>
 *   <li><b>可选时段</b>：额外可设一个时段（如 22:00–06:00，支持跨午夜）。
 *       只有当前时刻落在时段内，倒计时才递减；时段外自动「停表」，
 *       剩余时间原样保留，进了时段继续走。<br>
 *       典型用法：设 30 分钟 + 时段 22:00–06:00 = 「只在夜里听，
 *       躺下后听半小时自动停；白天挂着电台照样听，不会误停」。</li>
 * </ul>
 *
 * <h3>状态持久化</h3>
 * 倒计时分钟数 / 剩余秒数 / 时段起止全部落 SharedPreferences，
 * 进程被杀后重启自动恢复（{@link #restore()}，由 App.onCreate 调用）。
 * 停表期间被杀，剩余时间不变；计时中被杀最多损失一个 tick（1 秒），可接受。
 *
 * <h3>到点动作</h3>
 * 通过 {@link PauseTarget} 回调暂停播放（由 MainActivity 绑定到 PlayerCore），
 * 并发一个系统 Toast —— 不管当前在哪个页面，用户都能看到「定时时间到」。
 */
public final class SleepTimer {

    /** 倒计时分钟数；0 = 关 */
    private static volatile int countdownMin = 0;
    /** 剩余秒数（停表期间保持不变） */
    private static volatile int remainSec = 0;
    /** 时段开始（一天之内的分钟数，0~1439）；-1 = 不设时段 */
    private static volatile int winStart = -1;
    /** 时段结束（分钟数）；-1 = 不设时段。start &gt; end 表示跨午夜 */
    private static volatile int winEnd = -1;
    /**
     * ★ v3.9.7：按集停止剩余集数；0 = 未启用。
     *
     * <p>与分钟倒计时相互独立、可同时生效（谁先到谁触发暂停）。
     * 每自然播完一集由 MainActivity.onEnded 调 {@link #onEpisodeEnd()} 递减，
     * 减到 0 就暂停并不再自动连播。
     */
    private static volatile int episodesLeft = 0;
    /** 是否处于「时段外停表」状态 */
    private static volatile boolean holding = false;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable TICK = new Runnable() {
        @Override public void run() { tick(); }
    };
    private static boolean ticking = false;

    /** 到点暂停回调（MainActivity 绑定到 PlayerCore） */
    public interface PauseTarget { void onSleepPause(); }

    /** 状态变化监听（MainActivity 推给前端 + 小组件） */
    public interface Listener {
        void onSleepState(int countdownMin, int remainSec, int winStart, int winEnd, boolean holding);
    }

    private static volatile PauseTarget target;
    private static volatile Listener listener;

    private SleepTimer() {}

    // ==================== 对外 API ====================

    public static void bindTarget(PauseTarget t) { target = t; }

    public static void setListener(Listener l) { listener = l; }

    /** 设置定时。min &lt;= 0 表示关闭；winStart/winEnd 传 -1 表示不定时段 */
    public static void set(int min, int winStartMin, int winEndMin) {
        if (min <= 0) {
            clear();
            return;
        }
        countdownMin = Math.min(min, 24 * 60);
        remainSec = countdownMin * 60;
        winStart = normWin(winStartMin);
        winEnd = normWin(winEndMin);
        persist();
        // 立即按当前时刻判定一次是否停表，再启动轮询
        holding = !inWindow(nowMin());
        startTick();
        notifyState();
    }

    /** 关闭定时（保留时段设置，方便下次直接开） */
    public static void clear() {
        countdownMin = 0;
        remainSec = 0;
        holding = false;
        episodesLeft = 0;
        stopTick();
        persist();
        notifyState();
    }

    /**
     * ★ v3.9.7：按集停止。n &gt; 0 = 再听 n 集后暂停；n &lt;= 0 = 取消按集停止。
     * 不影响分钟倒计时与时段设置。
     */
    public static void setEpisodes(int n) {
        episodesLeft = Math.max(0, Math.min(n, 99));
        persist();
        notifyState();
    }

    /**
     * ★ v3.9.7：每集自然播完时调用（MainActivity.onEnded）。
     *
     * @return true = 剩余集数已用完，调用方应暂停播放、**不要**自动连播；
     *         false = 未启用或还有剩余，调用方按原逻辑继续。
     */
    public static boolean onEpisodeEnd() {
        if (episodesLeft <= 0) return false;
        episodesLeft--;
        if (episodesLeft > 0) {
            persist();
            notifyState();
            return false;
        }
        persist();
        notifyState();
        try {
            if (Settings.ctx() != null) {
                android.widget.Toast.makeText(Settings.ctx(),
                        "定时关闭：已播完设定的集数，已暂停", android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Throwable ignored) {}
        return true;
    }

    /** 进程重启后恢复（App.onCreate 调用） */
    public static void restore() {
        try {
            countdownMin = Settings.sp().getInt("sleep_min", 0);
            remainSec = Settings.sp().getInt("sleep_remain", 0);
            winStart = Settings.sp().getInt("sleep_win_start", -1);
            winEnd = Settings.sp().getInt("sleep_win_end", -1);
            episodesLeft = Settings.sp().getInt("sleep_eps", 0);
            if (countdownMin > 0 && remainSec > 0) {
                holding = !inWindow(nowMin());
                startTick();
                notifyState();
            } else if (countdownMin > 0) {
                // 上次到点已 fire 但没清干净 —— 直接关掉，别留幽灵定时
                clearQuiet();
            } else if (episodesLeft > 0) {
                // ★ v3.9.7：只有按集停止在生效 —— 恢复状态即可（无需计时器）
                notifyState();
            }
        } catch (Throwable ignored) {}
    }

    public static int countdownMin() { return countdownMin; }
    public static int remainSec() { return remainSec; }
    public static int winStart() { return winStart; }
    public static int winEnd() { return winEnd; }
    public static boolean holding() { return holding; }
    public static int episodesLeft() { return episodesLeft; }
    public static boolean active() { return (countdownMin > 0 && remainSec > 0) || episodesLeft > 0; }

    /** 小组件 / 通知栏用的短文案：关 → ""；停表 → "⏱ 停表"；计时 → "⏱ 23分" */
    public static String chipText() {
        if (!active()) return "";
        String t;
        if (countdownMin > 0 && remainSec > 0) {
            if (holding) { t = "⏱ 停表"; }
            else {
                int m = (remainSec + 59) / 60;
                if (m >= 60) {
                    int hh = m / 60, mm = m % 60;
                    t = "⏱ " + hh + "时" + (mm > 0 ? mm + "分" : "");
                } else {
                    t = "⏱ " + Math.max(1, m) + "分";
                }
            }
        } else {
            t = "⏱ " + episodesLeft + "集后停";
        }
        if (episodesLeft > 0 && countdownMin > 0 && remainSec > 0 && !holding) {
            t += "·" + episodesLeft + "集";
        }
        return t;
    }

    /** 前端 onSleep 回调的 JSON（Bridge / MainActivity 共用） */
    public static org.json.JSONObject stateJson() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("min", countdownMin);
            o.put("remain", remainSec);
            o.put("winStart", winStart);
            o.put("winEnd", winEnd);
            o.put("holding", holding);
            o.put("eps", episodesLeft);
            o.put("chip", chipText());
        } catch (Throwable ignored) {}
        return o;
    }

    // ==================== 内部 ====================

    private static void tick() {
        if (!active()) { stopTick(); return; }
        boolean inWin = inWindow(nowMin());
        if (!inWin) {
            // 时段外：停表（剩余时间不动）
            if (!holding) { holding = true; persist(); notifyState(); }
        } else {
            if (holding) { holding = false; persist(); notifyState(); }
            remainSec--;
            if (remainSec <= 0) {
                fire();
                return;
            }
            // 落库节流：每 15 秒 + 最后 10 秒每秒写，兼顾恢复精度与 IO
            if (remainSec % 15 == 0 || remainSec <= 10) persist();
            notifyState();
        }
        H.postDelayed(TICK, 1000);
    }

    /** 到点：暂停播放 + 系统 Toast（用户在哪个页面都能看见） */
    private static void fire() {
        clearQuiet();
        try {
            PauseTarget t = target;
            if (t != null) t.onSleepPause();
        } catch (Throwable ignored) {}
        try {
            if (Settings.ctx() != null) {
                android.widget.Toast.makeText(Settings.ctx(),
                        "定时时间到，已暂停", android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Throwable ignored) {}
    }

    private static void clearQuiet() {
        countdownMin = 0;
        remainSec = 0;
        holding = false;
        stopTick();
        persist();
        notifyState();
    }

    private static void startTick() {
        if (ticking) return;
        ticking = true;
        H.postDelayed(TICK, 1000);
    }

    private static void stopTick() {
        ticking = false;
        H.removeCallbacks(TICK);
    }

    /** 时段判定。start &lt; end：普通区间；start &gt; end：跨午夜；任一为 -1 或起止相同：不设时段（恒真） */
    private static boolean inWindow(int now) {
        if (winStart < 0 || winEnd < 0 || winStart == winEnd) return true;
        if (winStart < winEnd) return now >= winStart && now < winEnd;
        return now >= winStart || now < winEnd;   // 跨午夜，如 22:00 → 06:00
    }

    private static int nowMin() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }

    private static int normWin(int v) {
        if (v < 0 || v > 24 * 60) return -1;
        return v;
    }

    private static void persist() {
        try {
            Settings.sp().edit()
                    .putInt("sleep_min", countdownMin)
                    .putInt("sleep_remain", remainSec)
                    .putInt("sleep_win_start", winStart)
                    .putInt("sleep_win_end", winEnd)
                    .putInt("sleep_eps", episodesLeft)
                    .apply();
        } catch (Throwable ignored) {}
    }

    private static void notifyState() {
        try {
            Listener l = listener;
            if (l != null) {
                l.onSleepState(countdownMin, remainSec, winStart, winEnd, holding);
            }
        } catch (Throwable ignored) {}
    }
}
