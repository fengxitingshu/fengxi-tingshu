package com.tingshu.app.data;

import com.tingshu.app.core.FeatureGate;
import com.tingshu.app.data.Models.History;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;

/**
 * ★ v4.16.0 多设备无感续听（计划 #21）。
 *
 * <h3>要解决的问题</h3>
 * 手机听一半，换车机/平板继续听，进度各存各的本地库 —— 换设备就得重新找
 * 听到哪。这是听书 App 里最割裂的体验。
 *
 * <h3>方案：进度经 NAS WebDAV 中转</h3>
 * 沿用用户<b>已经配好的 WebDAV 凭据</b>（就是"网络备份"那套），在
 * {@code <备份目录>/fengxi-sync/fengxi-progress.json} 维护一份进度快照：
 * <pre>{"v":1,"dev":"设备标识","books":{"<书key>":{"i":章节,"p":偏移ms,"t":时间戳}}}</pre>
 *
 * <p><b>合并策略：逐条按时间戳取最新，绝不整包覆盖。</b>
 * 两台设备听不同的书，各自写各自的条目互不干扰；同一本书在两台设备都听过，
 * 保留最后更新的那台的进度。这样既不会"后同步的设备把别人的进度冲掉"，
 * 也不需要任何中心服务器 —— 一台 NAS 就是中转站。
 *
 * <h3>为什么不用云端账号</h3>
 * 用户已经有 NAS 和 WebDAV，再引入账号体系是多余的依赖，也意味着
 * 我们要为别人的播放记录负责。走用户自己的 NAS：数据在他手里，我们
 * 不碰。
 *
 * <h3>权限：远程功能锁</h3>
 * 属于试用功能，执行前一律过 {@link FeatureGate#SYNC_PROGRESS}。
 * 远程开关一关，所有设备立即停止同步；<b>本地已听记录不受任何影响</b>
 * —— 关掉的只是"跨设备同步"，不是"续听"本身（避免关锁导致用户数据丢失）。
 */
public final class ProgressSync {

    private ProgressSync() {}

    /** 远端进度文件名 */
    public static final String FILE = "fengxi-progress.json";
    /** 存放子目录（建在备份目录下） */
    public static final String SUB_DIR = "fengxi-sync";

    /** 上传节流：60 秒内不重复上传（手动同步可 force 突破） */
    private static final long MIN_PUSH_INTERVAL = 60L * 1000L;
    /** 触发检查的最小间隔：避免每次写库都开线程 */
    private static final long MIN_KICK_INTERVAL = 30L * 1000L;

    private static volatile long lastPushAt = 0L;
    private static volatile long lastKickAt = 0L;
    private static volatile long lastSyncAt = 0L;
    private static volatile String lastError = "";
    private static volatile boolean busy = false;

    /**
     * 是否允许同步：本地开关 + 远程功能锁 + WebDAV 已配置，三者都满足。
     */
    public static boolean enabled() {
        if (!Settings.progressSyncOn()) return false;
        if (!FeatureGate.allow(FeatureGate.SYNC_PROGRESS)) return false;
        return BackupCred.configured();
    }

    public static long lastSyncAt() { return lastSyncAt; }

    public static String lastError() { return lastError == null ? "" : lastError; }

    /** 远端目录路径（相对 base，不含文件名） */
    private static String subDir() {
        String dir = BackupCred.dir();
        String d = dir == null ? "" : dir.trim();
        while (d.startsWith("/")) d = d.substring(1);
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        return d.length() == 0 ? SUB_DIR : d + "/" + SUB_DIR;
    }

    /** 远端进度文件的完整 URL */
    private static String remoteUrl() {
        String base = BackupCred.url();
        if (base == null || base.trim().length() == 0) return "";
        return WebDavClient.joinUrl(base.trim(), subDir()) + FILE;
    }

    /**
     * 读远端进度 JSON。远端还没有（404）或解析失败 → 返回空对象，不算错误
     * （首次使用本来就没有这个文件）。
     */
    private static JSONObject readRemote() {
        try {
            WebDavClient.Result r = WebDavClient.getText(remoteUrl(),
                    BackupCred.user(), BackupCred.pass(), BackupCred.authType());
            if (r != null && r.ok && r.body != null && r.body.length() > 0) {
                try { return new JSONObject(r.body); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return new JSONObject();
    }

    /**
     * 上传本地进度（合并式，不整包覆盖）。
     *
     * @param force true 跳过节流（手动点"立即同步"时用）
     */
    public static boolean push(boolean force) {
        if (!enabled()) return false;
        long now = System.currentTimeMillis();
        if (!force && now - lastPushAt < MIN_PUSH_INTERVAL) return true;
        synchronized (ProgressSync.class) {
            if (busy) return false;
            busy = true;
        }
        try {
            String base = BackupCred.url();
            String u = BackupCred.user(), p = BackupCred.pass(), a = BackupCred.authType();

            // 目录不存在时先建（WebDAV 不允许一次建多级，ensureDir 逐级 MKCOL）
            WebDavClient.ensureDir(base, u, p, subDir(), a);

            JSONObject remote = readRemote();
            JSONObject books = remote.optJSONObject("books");
            if (books == null) books = new JSONObject();

            List<History> hs = HistoryRepo.list(500);
            for (History h : hs) {
                if (h == null || h.book == null) continue;
                String k = h.book.key();
                if (k == null || k.length() < 2) continue;
                JSONObject old = books.optJSONObject(k);
                long oldTs = old != null ? old.optLong("t", 0L) : 0L;
                if (h.updatedAt >= oldTs) {          // 本地更新（或远端没有）才写入
                    JSONObject o = new JSONObject();
                    try {
                        o.put("i", h.lastIndex);
                        o.put("p", h.lastPosMs);
                        o.put("t", h.updatedAt);
                    } catch (Throwable ignored) {}
                    try { books.put(k, o); } catch (Throwable ignored) {}
                }
            }

            JSONObject out = new JSONObject();
            try {
                out.put("v", 1);
                out.put("dev", Settings.deviceId());
                out.put("books", books);
            } catch (Throwable ignored) {}

            WebDavClient.Result r = WebDavClient.put(remoteUrl(), u, p,
                    out.toString().getBytes("UTF-8"), a);
            if (r != null && (r.ok || r.code == 201 || r.code == 204)) {
                lastPushAt = System.currentTimeMillis();
                lastSyncAt = lastPushAt;
                lastError = "";
                return true;
            }
            lastError = r == null ? "上传失败" : nz(r.error);
            return false;
        } catch (Throwable t) {
            lastError = String.valueOf(t.getMessage());
            return false;
        } finally {
            busy = false;
        }
    }

    /**
     * 拉取远端进度并合并到本地。仅当远端条目比本地新才覆盖。
     *
     * @return 实际应用的条数
     */
    public static int pull() {
        if (!enabled()) return 0;
        synchronized (ProgressSync.class) {
            if (busy) return 0;
            busy = true;
        }
        try {
            JSONObject books = readRemote().optJSONObject("books");
            if (books == null) return 0;
            int applied = 0;
            Iterator<String> it = books.keys();
            while (it != null && it.hasNext()) {
                String k = it.next();
                JSONObject o = books.optJSONObject(k);
                if (o == null) continue;
                int idx = o.optInt("i", 0);
                long pos = o.optLong("p", 0L);
                long ts = o.optLong("t", 0L);
                if (ts <= 0) continue;
                if (HistoryRepo.applyRemote(k, idx, pos, ts)) applied++;
            }
            lastSyncAt = System.currentTimeMillis();
            lastError = "";
            return applied;
        } catch (Throwable t) {
            lastError = String.valueOf(t.getMessage());
            return 0;
        } finally {
            busy = false;
        }
    }

    /** 全量同步：先拉（用别人的最新进度）后推（把自己的传上去） */
    public static int syncNow() {
        int n = pull();
        push(true);
        return n;
    }

    /**
     * 进度落库后的顺带触发（异步 + 双重节流，绝不影响播放主线程）。
     * 播放时每 5 秒会写一次进度，这里 30 秒才检查一次、60 秒才真上传一次。
     */
    public static void kick() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        if (now - lastKickAt < MIN_KICK_INTERVAL) return;
        lastKickAt = now;
        try {
            new Thread(new Runnable() {
                public void run() {
                    try { push(false); } catch (Throwable ignored) {}
                }
            }, "fx-sync").start();
        } catch (Throwable ignored) {}
    }

    /**
     * ★ v4.17.0 暂停即上传（无缝续听的关键一环）。
     *
     * <p>用户主动按下暂停（暂停键 / 车机暂停 / 播放页 toggle），意味着
     * "我这一段听到这里，接下来可能换设备"——这是最明确的换机信号，
     * 不该再等 60 秒节流。立即（异步）把进度推上 NAS，另一台设备
     * 下次打开 App（或切回前台）拉一次，就接上了。
     *
     * <p>安全边界：异步线程 + busy 互斥（与 kick/手动同步并发时谁先
     * 拿到锁谁做），绝不阻塞 UI 线程；FeatureGate/开关在 push 内部
     * 再查一遍，远程关锁后这里自然失效。
     */
    public static void onPause() {
        if (!enabled()) return;
        try {
            new Thread(new Runnable() {
                public void run() {
                    try { push(true); } catch (Throwable ignored) {}
                }
            }, "fx-sync-pause").start();
        } catch (Throwable ignored) {}
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
