package com.tingshu.app.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tingshu.app.data.CacheRepo;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;
import com.tingshu.app.data.Settings;
import com.tingshu.app.source.Aggregator;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 离线缓存调度器。
 *
 * 设计要点：
 * 1) 单线程串行下载，避免多路并发把站点/带宽打爆。
 * 2) 每集先 resolve 出真实音频地址（走 Aggregator.audioWithFallback 自动换源），再流式落盘。
 * 3) 同一集重复入队会被去重。
 * 4) 下载完成写 cache 表；失败标 ST_FAIL，下次可重试。
 * 5) 支持 WiFi-only：非 WiFi 时入队但等待（由调用方决定是否入队）。
 */
public final class CacheManager {

    public interface Listener {
        void onProgress(Book book, int done, int total, int curIdx);
        void onDone(Book book, int done, int total);
        void onError(Book book, int idx, String msg);
        /**
         * ★ v2.8：单集缓存结束（成功或失败）。
         * 章节列表每行的下载按钮靠它把「下载中」落定成「已下载 / 失败」。
         * 整本缓存时也会逐集触发（与 onProgress 并行，各管各的语义）。
         */
        void onChapterDone(Book book, int idx, boolean ok);
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ts-cache");
            t.setDaemon(true);
            return t;
        }
    });

    private static final Set<String> QUEUED = Collections.synchronizedSet(new HashSet<String>());
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile Context CTX;
    private static volatile Listener LST;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private CacheManager() {}

    public static void init(Context c) {
        CTX = c.getApplicationContext();
    }

    public static void setListener(Listener l) {
        LST = l;
    }

    private static String jobKey(String bookKey, int idx) {
        return bookKey + "#" + idx;
    }

    /**
     * 缓存某本书的全部章节。
     * @param book 书籍（含 variants，换源用）
     */
    public static void cacheBook(final Book book) {
        if (book == null || CTX == null) return;
        POOL.execute(new Runnable() {
            public void run() {
                try {
                    // 1) 先拿到完整章节表
                    BookDetail d = Aggregator.detailWithFallback(book);
                    if (d == null || d.chapters == null || d.chapters.isEmpty()) {
                        finalErr(book, -1, "无法获取章节列表");
                        return;
                    }
                    final List<Chapter> chs = d.chapters;
                    final int total = chs.size();
                    int done = CacheRepo.doneCount(book.key());

                    // 2) 逐集下载
                    int okCount = 0;
                    for (int i = 0; i < total; i++) {
                        Chapter ch = chs.get(i);
                        int humanIdx = ch.index > 0 ? ch.index : (i + 1);
                        if (CacheRepo.isCached(book.key(), humanIdx)) {
                            okCount++;
                            progress(book, okCount, total, humanIdx);
                            continue;
                        }
                        boolean ok = downloadOne(book, humanIdx, ch);
                        if (ok) okCount++;
                        progress(book, okCount, total, humanIdx);
                    }

                    // 3) 容量上限
                    CacheRepo.enforceLimit(CTX, Settings.cacheLimitMb() * 1024L * 1024L);

                    final int fc = okCount, ft = total;
                    UI.post(new Runnable() {
                        public void run() {
                            Listener l = LST;
                            if (l != null) l.onDone(book, fc, ft);
                        }
                    });
                } catch (Throwable t) {
                    finalErr(book, -1, t.getClass().getSimpleName());
                }
            }
        });
    }

    /** 缓存单集（手动：章节列表下载按钮 / 整本缓存） */
    public static void cacheChapter(final Book book, final int humanIdx) {
        cacheChapter(book, humanIdx, false);
    }

    /** 自动预读的集间间隔：不给间隔的话 50 集连发会把限流站打挂，后面全部失败 */
    private static final long AUTO_PACE_MS = 1200L;
    /** 自动预读熔断阈值：连续失败达此数暂停预读（源在限流/挂了），5 分钟后自动重试 */
    private static final int AUTO_FAIL_LIMIT = 5;
    private static final java.util.concurrent.atomic.AtomicInteger AUTO_FAIL_STREAK =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static volatile long AUTO_LAST_FAIL_AT = 0L;

    /**
     * 缓存单集。
     * @param auto true = 自动预读（任务前插间隔 + 失败熔断计数；手动下载不受此限）
     */
    public static void cacheChapter(final Book book, final int humanIdx, final boolean auto) {
        if (book == null || CTX == null) return;
        final String jk = jobKey(book.key(), humanIdx);
        if (!QUEUED.add(jk)) return;
        POOL.execute(new Runnable() {
            public void run() {
                if (auto) {
                    // ★ v4.21.41 熔断：连续失败说明源在限流/挂了，暂停预读，
                    //   避免把剩余的集全部撞死、白烧流量（这就是「预存 50 集
                    //   却没真正预存」的主因之一：前几集失败后后面跟着全失败）
                    if (AUTO_FAIL_STREAK.get() >= AUTO_FAIL_LIMIT) {
                        QUEUED.remove(jk);
                        return;
                    }
                    // ★ v4.21.41 限流间隔：自动预读的每一集之间歇一下
                    try {
                        Thread.sleep(AUTO_PACE_MS);
                    } catch (InterruptedException e) {
                        QUEUED.remove(jk);
                        return;
                    }
                }
                boolean ok = false;
                try {
                    if (!CacheRepo.isCached(book.key(), humanIdx)) {
                        Chapter ch = new Chapter();
                        ch.index = humanIdx;
                        ch.title = "第" + humanIdx + "集";
                        ok = downloadOne(book, humanIdx, ch);
                    } else {
                        ok = true;   // 已经在缓存里，视为成功
                    }
                } finally {
                    QUEUED.remove(jk);
                }
                if (auto) {
                    if (ok) {
                        AUTO_FAIL_STREAK.set(0);
                    } else {
                        AUTO_FAIL_STREAK.incrementAndGet();
                        AUTO_LAST_FAIL_AT = System.currentTimeMillis();
                    }
                }
                // ★ v3.7.18：单集缓存**也要**跑容量上限。
                //   原 bug：`enforceLimit` 只在 `cacheBook()`（整本缓存）末尾调过一次，
                //   而日常真正在跑的是这条路 —— `autoCacheAhead()` 每播一集就
                //   预读 3 集，走的全是 `cacheChapter()` → `downloadOne()`。
                //   结果是：一边不停往下存、一边从来没人清理，缓存只涨不落，
                //   单文件约 10~15MB，听一下午就能把存储顶到几百 MB。
                //   这是用户报「App 占了 500 多 M」的直接原因之一。
                //   现在每集缓存完都顺手清一次，上限立即生效。
                try {
                    CacheRepo.enforceLimit(CTX,
                            Settings.cacheLimitMb() * 1024L * 1024L);
                } catch (Throwable ignored) {}
                // ★ v2.8：单集也要回推结束事件，章节行的下载按钮才知道该亮 ✓ 还是 ↻
                chapterDone(book, humanIdx, ok);
            }
        });
    }

    /** 下载一集，返回是否成功 */
    private static boolean downloadOne(Book book, int humanIdx, Chapter stub) {
        try {
            String usedSrc = null;
            String[] out = new String[1];
            // 取真实音频地址（会自动换源）
            String audio = Aggregator.audioWithFallback(book, humanIdx, null, out);
            usedSrc = out[0];
            if (audio == null || audio.length() == 0) {
                CacheRepo.markStatus(book.key(), humanIdx, CacheRepo.ST_FAIL);
                finalErr(book, humanIdx, "取链失败");
                return false;
            }

            com.tingshu.app.source.Source src = com.tingshu.app.source.SourceRegistry.byId(usedSrc);
            java.util.Map<String, String> hdr = null;
            String referer = null;
            if (src != null) {
                hdr = src.audioHeaders(audio);
            }
            if (hdr != null && hdr.containsKey("Referer")) referer = hdr.get("Referer");

            File dest = CacheRepo.chapterFile(CTX, book.key(), humanIdx);
            CacheRepo.markStatus(book.key(), humanIdx, CacheRepo.ST_DOWNLOADING);

            Net.DlResult r = Net.download(audio, referer, hdr, dest, null, null);
            if (r.ok && dest.exists() && dest.length() > 4096) {
                CacheRepo.markDone(book.key(), humanIdx, dest.getAbsolutePath(),
                        dest.length(), audio);
                return true;
            } else {
                CacheRepo.markStatus(book.key(), humanIdx, CacheRepo.ST_FAIL);
                finalErr(book, humanIdx, r.error == null ? "下载失败" : r.error);
                return false;
            }
        } catch (Throwable t) {
            CacheRepo.markStatus(book.key(), humanIdx, CacheRepo.ST_FAIL);
            finalErr(book, humanIdx, t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 自动缓存：播放到第 idx 集时，把后面 N 集排队。
     *
     * <p>★ v4.21.41 修复「预存 50 集没有真正预存」：
     * <ul>
     *   <li><b>越界保护</b>：以前不查总集数，超出末集的号在取链时会「退到最后一集」，
     *       把最后一集的音频错标成第 N 集（缓存列表看着有、内容全是错的）</li>
     *   <li><b>限流间隔 + 熔断</b>：50 集连发无间隔会触发站点限流，从某一集起
     *       全部失败；现在集间歇 1.2s，连续 5 次失败暂停预读，5 分钟后自动恢复</li>
     * </ul>
     */
    public static void autoCacheAhead(Book book, int idx, int ahead) {
        autoCacheAhead(book, idx, ahead, 0);
    }

    /**
     * 自动缓存（带总集数钳制）。totalChapters &gt; 0 时超出总集数直接停；
     * 0 = 未知总数，保持旧行为。
     */
    public static void autoCacheAhead(Book book, int idx, int ahead, int totalChapters) {
        if (book == null || ahead <= 0) return;
        if (!Settings.autoCacheEnabled()) return;
        if (Settings.wifiCacheOnly() && !isWifi()) return;
        // 熔断恢复：距上次失败超过 5 分钟视为源已恢复，重新放行预读
        if (AUTO_FAIL_STREAK.get() > 0
                && System.currentTimeMillis() - AUTO_LAST_FAIL_AT > 5 * 60 * 1000L) {
            AUTO_FAIL_STREAK.set(0);
        }
        if (AUTO_FAIL_STREAK.get() >= AUTO_FAIL_LIMIT) return;   // 熔断中，不入队
        for (int i = 1; i <= ahead; i++) {
            int t = idx + i;
            if (t <= 0) continue;
            if (totalChapters > 0 && t > totalChapters) break;   // 超出末集，直接停
            if (CacheRepo.isCached(book.key(), t)) continue;
            cacheChapter(book, t, true);
        }
    }

    /** 后台预取当前集的后续（不阻塞主线程） */
    public static boolean isWifi() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    CTX.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected()
                    && ni.getType() == android.net.ConnectivityManager.TYPE_WIFI;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 回调（都切回主线程） ====================

    private static void progress(final Book b, final int done, final int total, final int idx) {
        UI.post(new Runnable() {
            public void run() {
                Listener l = LST;
                if (l != null) l.onProgress(b, done, total, idx);
            }
        });
    }

    private static void finalErr(final Book b, final int idx, final String msg) {
        UI.post(new Runnable() {
            public void run() {
                Listener l = LST;
                if (l != null) l.onError(b, idx, msg);
            }
        });
    }

    /** ★ v2.8：单集结束事件（切回主线程） */
    private static void chapterDone(final Book b, final int idx, final boolean ok) {
        UI.post(new Runnable() {
            public void run() {
                Listener l = LST;
                if (l != null) l.onChapterDone(b, idx, ok);
            }
        });
    }

    /** 缓存管理页数据 */
    public static List<Object[]> listCached() {
        List<Object[]> out = new ArrayList<Object[]>();
        List<com.tingshu.app.data.Models.CacheItem> items = CacheRepo.books();
        for (com.tingshu.app.data.Models.CacheItem it : items) {
            out.add(new Object[]{it.key, it.chapterIdx, it.size, it.updatedAt});
        }
        return out;
    }
}
