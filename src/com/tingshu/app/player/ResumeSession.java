package com.tingshu.app.player;

import android.content.Context;
import android.content.SharedPreferences;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.Chapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ v4.21.32 方案 B：可恢复播放会话（落盘快照）。
 *
 * <h3>为什么需要它</h3>
 * 方案 B 之前，播放队列（书 + 整张章节表 + 当前集 + 进度）只存在于
 * {@code PlayerCore} 的内存字段里；进程被系统杀掉后，服务由 START_STICKY
 * 重启时这些全没了，只能把界面拉起来让用户重新点。
 *
 * <p>本类把「最后一次在听什么」写成一份轻量 JSON 快照存进独立的
 * SharedPreferences。服务冷启动时读它，配合纯 Java 取链
 * （{@code Aggregator.audioWithFallback} + {@code PanResolver}）即可在
 * <b>没有 WebView / 没有 Activity</b> 的情况下重新起播。
 *
 * <h3>存什么、不存什么</h3>
 * <ul>
 *   <li>存：book（含 variants，换源要用）、整张章节表、集下标、集内进度、
 *       实际使用的源 id、章节定位串、保存时间；</li>
 *   <li><b>不存音频直链</b>：百度网盘 dlink 有时效（几分钟），存了反而会
 *       拿死链去播。恢复时一律重新走取链，慢 1~3 秒但可靠；</li>
 *   <li>不存封面位图：位图不可序列化，服务恢复时由通知栏既有逻辑异步加载。</li>
 * </ul>
 *
 * <p>主动「停止」会 {@link #clear(Context)}，保证语义正确：用户自己停了，
 * 就不该再被小组件一键续上。按 Home / 划掉任务 / 进程被杀都不 clear。
 */
public final class ResumeSession {

    private static final String SP_NAME = "ts_resume_session";
    private static final String KEY = "snapshot";

    private ResumeSession() {}

    /** 恢复出来的会话。{@link #valid()} 为 false 时不可用于起播。 */
    public static final class Snapshot {
        public Book book;
        public List<Chapter> chapters = new ArrayList<Chapter>();
        /** 0 基下标 */
        public int idx;
        /** 集内偏移（毫秒） */
        public long posMs;
        /** 上次实际使用的源 id（自动换源后的结果） */
        public String source = "";
        /** 章节定位串 */
        public String chapterUrl = "";
        /** 快照写入时刻（毫秒） */
        public long savedAt;

        public boolean valid() {
            return book != null && chapters != null && !chapters.isEmpty()
                    && idx >= 0 && idx < chapters.size();
        }
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 写一份完整快照。由 MainActivity 在起播 / 切集 / 进度 tick 时调用。
     *
     * @param chs        整张章节表（自动下一集要用，不能只存当前集）
     * @param idx        0 基下标
     * @param posMs      集内偏移毫秒
     * @param source     实际取链成功的源 id
     * @param chapterUrl 当前章节定位串
     */
    public static void save(Context ctx, Book b, List<Chapter> chs, int idx,
                            long posMs, String source, String chapterUrl) {
        if (ctx == null || b == null || chs == null || chs.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("book", b.toJson());
            JSONArray ca = new JSONArray();
            for (Chapter c : chs) ca.put(c == null ? new JSONObject() : c.toJson());
            o.put("chapters", ca);
            o.put("idx", Math.max(0, idx));
            o.put("posMs", Math.max(0, posMs));
            o.put("source", source == null ? "" : source);
            o.put("chapterUrl", chapterUrl == null ? "" : chapterUrl);
            o.put("savedAt", System.currentTimeMillis());
            sp(ctx).edit().putString(KEY, o.toString()).apply();
        } catch (Throwable ignored) {}
    }

    /** 只推进度（每秒 tick 走这条，避免频繁重写整张章节表）。 */
    public static void touch(Context ctx, long posMs, int idx) {
        if (ctx == null || posMs < 0) return;
        try {
            String raw = sp(ctx).getString(KEY, null);
            if (raw == null) return;
            JSONObject o = new JSONObject(raw);
            o.put("posMs", posMs);
            o.put("idx", Math.max(0, idx));
            o.put("savedAt", System.currentTimeMillis());
            sp(ctx).edit().putString(KEY, o.toString()).apply();
        } catch (Throwable ignored) {}
    }

    /** 读回快照；无快照 / 数据损坏时返回 null。 */
    public static Snapshot get(Context ctx) {
        if (ctx == null) return null;
        try {
            String raw = sp(ctx).getString(KEY, null);
            if (raw == null || raw.length() == 0) return null;
            JSONObject o = new JSONObject(raw);
            Snapshot s = new Snapshot();
            s.book = Book.fromJson(o.optJSONObject("book"));
            JSONArray ca = o.optJSONArray("chapters");
            if (ca != null) {
                for (int i = 0; i < ca.length(); i++) {
                    JSONObject co = ca.optJSONObject(i);
                    if (co == null) { s.chapters.add(new Chapter()); continue; }
                    Chapter c = new Chapter();
                    c.title = co.optString("title", "");
                    c.url = co.optString("url", "");
                    c.index = co.optInt("index", 0);
                    c.directUrl = co.optString("directUrl", "");
                    c.flag = co.optString("flag", "");
                    s.chapters.add(c);
                }
            }
            s.idx = o.optInt("idx", 0);
            s.posMs = o.optLong("posMs", 0);
            s.source = o.optString("source", "");
            s.chapterUrl = o.optString("chapterUrl", "");
            s.savedAt = o.optLong("savedAt", 0);
            return s.valid() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 主动停止 / 换空书时清掉，避免「停了又被小组件续上」。 */
    public static void clear(Context ctx) {
        if (ctx == null) return;
        try { sp(ctx).edit().remove(KEY).apply(); } catch (Throwable ignored) {}
    }

    public static boolean exists(Context ctx) {
        return get(ctx) != null;
    }
}
