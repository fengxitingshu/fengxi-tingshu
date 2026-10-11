package com.tingshu.app.source;

import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.Chapter;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 聚合器：多源并发搜索 + 归并去重 + 播放失败自动换源。
 * 这是"我的听书"最核心的能力，也是我们命中率的关键。
 */
public final class Aggregator {

    private Aggregator() {}

    // ---------- 三个独立线程池，避免互相饿死 ----------

    private static final ExecutorService SEARCH_POOL =
            Executors.newFixedThreadPool(6, namedFactory("search"));
    /**
     * 详情池：详情现在是「多候选源并发赛跑」，池子太小会让不同书的请求互相排队，
     * 也会让同一本书的几个候选源排不上队。给到 10，够跑开又不至于把手机打爆。
     */
    private static final ExecutorService DETAIL_POOL =
            Executors.newFixedThreadPool(10, namedFactory("detail"));
    private static final ExecutorService SINGLE_POOL =
            Executors.newFixedThreadPool(4, namedFactory("single"));
    /**
     * ★ v3.7.7：分类清单预取池。
     * <p>首页要问遍所有可浏览源的 categories()，而 JS/Python 源首次调用会启动
     * Rhino / Chaquopy 引擎（几百 ms 级）。串行遍历就是 N×几百 ms；这里单独开一个
     * 池子并发问，总耗时降到"最慢的那个源"。
     * <p>尺寸给到 8：源通常不超过十几个，一次铺开即可；不给太大是为了别
     * 同时唤醒太多 Python 源抢占 CPython 那把全局锁。
     */
    private static final ExecutorService CAT_POOL =
            Executors.newFixedThreadPool(8, namedFactory("cat"));

    public static ExecutorService searchPool() {
        return SEARCH_POOL;
    }

    public static ExecutorService detailPool() {
        return DETAIL_POOL;
    }

    public static ExecutorService singlePool() {
        return SINGLE_POOL;
    }

    /** ★ v3.7.7：分类清单预取（discoverSources 并发用） */
    public static ExecutorService catPool() {
        return CAT_POOL;
    }

    private static java.util.concurrent.ThreadFactory namedFactory(final String tag) {
        return new java.util.concurrent.ThreadFactory() {
            private int n = 0;
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ts-" + tag + "-" + (++n));
                t.setDaemon(true);
                return t;
            }
        };
    }

    // ==================== 聚合搜索 ====================

    public interface SearchCallback {
        /** 单个源返回时回调（用于增量渲染），可能被调用多次 */
        void onPartial(String sourceId, List<Book> books);
        /** 全部完成（或超时）时回调 */
        void onDone(List<Book> merged);
        /**
         * 每个源的处理结果（无论有无命中都会回调一次），供前端做搜索诊断。
         * @param status 0=命中 1=无结果 2=超时 3=失败
         * @param error  失败原因（status=3 时有值，其余为空串）
         */
        void onSourceResult(String sourceId, int status, List<Book> books, String error);
    }

    /** 搜索诊断状态码 */
    public static final int ST_HIT = 0;
    public static final int ST_EMPTY = 1;
    public static final int ST_TIMEOUT = 2;
    public static final int ST_FAIL = 3;

    /** 单源搜索，不做聚合 */
    public static List<Book> searchOne(String sourceId, String kw, int page) {
        Source s = SourceRegistry.byId(sourceId);
        if (s == null) return new ArrayList<Book>();
        try {
            List<Book> r = s.search(kw, page);
            return r == null ? new ArrayList<Book>() : r;
        } catch (Throwable t) {
            return new ArrayList<Book>();
        }
    }

    /**
     * 聚合搜索：并发搜所有启用的源，超时保护，结果归并去重。
     * @param onlySourceId 非空则只搜该源
     */
    public static List<Book> searchAll(String kw, int page, String onlySourceId,
                                       SearchCallback cb) {
        List<Source> srcs;
        if (onlySourceId != null && onlySourceId.length() > 0) {
            Source s = SourceRegistry.byId(onlySourceId);
            srcs = new ArrayList<Source>();
            if (s != null) srcs.add(s);
        } else {
            srcs = SourceRegistry.searchable();
        }

        if (srcs.isEmpty()) return new ArrayList<Book>();

        final int n = srcs.size();
        // 每个源写自己的下标，天然线程安全（不要多线程 add 同一个 ArrayList）
        @SuppressWarnings("unchecked")
        final List<Book>[] buckets = new List[n];
        final int[] status = new int[n];
        final String[] errs = new String[n];
        final CountDownLatch latch = new CountDownLatch(n);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final Source src = srcs.get(i);
            status[idx] = -1;   // 未完成
            SEARCH_POOL.execute(new Runnable() {
                public void run() {
                    try {
                        List<Book> r = src.search(kw, 1);
                        buckets[idx] = r == null ? new ArrayList<Book>() : r;
                        status[idx] = (buckets[idx].isEmpty() ? ST_EMPTY : ST_HIT);
                    } catch (Throwable t) {
                        buckets[idx] = new ArrayList<Book>();
                        status[idx] = ST_FAIL;
                        errs[idx] = t.getClass().getSimpleName() +
                                (t.getMessage() == null ? "" : (": " + t.getMessage()));
                    } finally {
                        // ★ v2.2 边出边渲染：**谁先回来就先回调谁**，不再等所有源。
                        //   老实现要等 latch 走完（最坏 12s）才在下面的循环里统一 onPartial，
                        //   于是「一个慢源拖死整个列表」——明明三四个源 500ms 就回来了，
                        //   用户却要盯着转圈十几秒。
                        //   这里在 worker 线程里直接回调，只读自己那一格，天然线程安全。
                        if (cb != null) {
                            List<Book> got = buckets[idx];
                            if (got != null && !got.isEmpty()) {
                                try { cb.onPartial(srcs.get(idx).id(), got); }
                                catch (Throwable ignored) {}
                            }
                        }
                        latch.countDown();
                    }
                }
            });
        }

        // 单源超时保护：慢源不拖垮整体
        try {
            latch.await(12, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        // latch 超时后仍未完成的源 → 标记为超时
        for (int i = 0; i < n; i++) {
            if (status[i] < 0) status[i] = ST_TIMEOUT;
        }

        List<Book> all = new ArrayList<Book>();
        for (int i = 0; i < n; i++) {
            List<Book> b = buckets[i];
            String sid = srcs.get(i).id();
            if (b == null || b.isEmpty()) {
                // 没命中的源也要上报，前端才能显示「无结果 / 超时 / 失败」
                if (cb != null) {
                    cb.onSourceResult(sid, status[i], new ArrayList<Book>(),
                            errs[i] == null ? "" : errs[i]);
                }
                continue;
            }
            // 注意：onPartial 已经在 worker 里发过了，这里**不重复发**，
            //       否则前端会把同一批书追加两遍。
            all.addAll(b);
            if (cb != null) {
                cb.onSourceResult(sid, status[i], b, "");
            }
        }

        List<Book> merged = merge(all);
        if (cb != null) cb.onDone(merged);
        return merged;
    }

    // ==================== 归并去重 ====================

    /** 归一化：去空白/标点/书名号，转小写 */
    public static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s《》〈〉()（）\\[\\]【】\\-_.·、,，:：!！?？\"'“”‘’]", "")
                .toLowerCase();
    }

    /** 归并 key：书名 + 作者（无作者用播音） */
    public static String normKey(Book b) {
        String a = b.author != null && b.author.length() > 0 ? b.author : b.artist;
        return norm(b.title) + "|" + norm(a);
    }

    /**
     * 归并去重：同名同作者的书合并成一条，记录所有来源。
     * 保留第一个出现的作为主条目。
     */
    public static List<Book> merge(List<Book> in) {
        LinkedHashMap<String, Book> map = new LinkedHashMap<String, Book>();
        for (Book b : in) {
            if (b == null || b.title == null || b.title.length() == 0) continue;
            String k = normKey(b);
            Book exist = map.get(k);
            if (exist == null) {
                b.sources = new ArrayList<String>();
                b.sources.add(b.source);
                b.variants = new ArrayList<Book>();
                b.variants.add(b);
                map.put(k, b);
            } else {
                if (!exist.sources.contains(b.source)) exist.sources.add(b.source);
                exist.variants.add(b);
                // 补全缺失字段
                if (isBlank(exist.cover) && !isBlank(b.cover)) exist.cover = b.cover;
                if (isBlank(exist.intro) && !isBlank(b.intro)) exist.intro = b.intro;
                if (isBlank(exist.artist) && !isBlank(b.artist)) exist.artist = b.artist;
            }
        }
        return new ArrayList<Book>(map.values());
    }

    private static boolean isBlank(String s) {
        return s == null || s.length() == 0;
    }

    // ==================== 详情 / 取链（带自动换源） ====================

    /**
     * 取详情。若主源失败，自动试同名书的其他源。
     * @return 成功的 BookDetail，book.source 已改成实际可用的源
     */
    /**
     * 拿书的详情（章节表），带自动换源。
     *
     * <p><b>为什么改成并发赛跑</b>：老实现是一个源一个源**串行**试，
     * 而单次 HTTP 的超时是「连接 15s + 读 25s」。只要第一个源卡死，
     * 用户就要瞪着加载圈干等最坏 40 秒——这就是"点一本书半天不出内容"的根因。
     *
     * <p>现在改为：**所有候选源同时发起**，谁先拿到有效章节表就用谁，
     * 整体用 {@link #DETAIL_DEADLINE_MS} 兜底。串行最坏 = 源数 × 40s；
     * 并发最坏 = 一个截止时间，且通常第一个成功的源几百毫秒就回来了。
     */
    public static BookDetail detailWithFallback(Book book) {
        // 命中缓存直接返回：同一本书反复点开/返回不再走网络
        BookDetail cached = detailCacheGet(book);
        if (cached != null) return cached;

        List<Book> tryList = candidates(book);
        if (tryList.isEmpty()) return new BookDetail(book);

        final BookDetail[] slot = new BookDetail[1];
        final CountDownLatch latch = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger pending =
                new java.util.concurrent.atomic.AtomicInteger(tryList.size());

        for (final Book cand : tryList) {
            DETAIL_POOL.execute(new Runnable() {
                public void run() {
                    try {
                        // 已经有源胜出：这个候选不必再连了，
                        // 否则落败的请求会白占线程 + 白耗流量直到超时，
                        // 拖慢「下一本书」的并发赛跑。
                        if (slot[0] != null) return;
                        Source s = SourceRegistry.byId(cand.source);
                        if (s != null) {
                            BookDetail d = s.detail(bookUrlOf(cand));
                            if (d != null && d.chapters != null && !d.chapters.isEmpty()) {
                                if (d.book == null) d.book = cand;
                                synchronized (slot) {
                                    // 第一个成功的胜出；后来的不覆盖
                                    if (slot[0] == null) {
                                        d.book.sources = cand.sources;
                                        d.book.variants = cand.variants;
                                        slot[0] = d;
                                        latch.countDown();
                                    }
                                    return;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        // 所有候选都试完（或已有人胜出）也要解锁，避免死等
                        if (pending.decrementAndGet() <= 0) latch.countDown();
                    }
                }
            });
        }

        try {
            // 被「第一个胜出者」唤醒即返回；没人成功则等到兜底阈值后放弃。
            latch.await(DETAIL_DEADLINE_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {}

        BookDetail got = slot[0];
        if (got == null) got = new BookDetail(book);
        else {
            // 成功结果缓存起来（含 book 的聚合信息），供下次秒开
            detailCachePut(book, got);
        }
        return got;
    }

    /** 详情整体截止时间：超过就当拿不到，交给上层回退，不再无限等 */
    private static final long DETAIL_DEADLINE_MS = 8000;

    // ---------- 详情缓存（内存 LRU + 磁盘持久化，同一本书反复点开不再走网络） ----------

    private static final int DETAIL_CACHE_MAX = 24;
    private static final LinkedHashMap<String, BookDetail> DETAIL_CACHE =
            new LinkedHashMap<String, BookDetail>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BookDetail> e) {
                    return size() > DETAIL_CACHE_MAX;
                }
            };

    /**
     * 详情磁盘缓存：内存缓存重启就没了，而"点开一本书要等联网"最烦人的
     * 恰恰是冷却启动或长时间没点过的那本。落盘后即使重启 App 也能秒开。
     * 目录：<cache>/detail/&lt;md5(key)&gt;.json
     */
    private static final long DETAIL_DISK_TTL_MS = 3L * 24 * 3600 * 1000;   // 3 天

    private static java.io.File detailDiskDir() {
        try {
            android.content.Context c = com.tingshu.app.core.App.ctx();
            if (c == null) return null;
            java.io.File d = new java.io.File(c.getCacheDir(), "detail");
            if (!d.exists() && !d.mkdirs()) return null;
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.io.File detailDiskFile(String key) {
        java.io.File d = detailDiskDir();
        if (d == null) return null;
        return new java.io.File(d, com.tingshu.app.core.Utils.md5(key) + ".json");
    }

    private static String cacheKeyOf(Book b) {
        if (b == null) return "";
        String k = b.key();
        return k == null ? "" : k;
    }

    private static BookDetail detailCacheGet(Book b) {
        String k = cacheKeyOf(b);
        if (k.length() < 2) return null;
        synchronized (DETAIL_CACHE) {
            BookDetail mem = DETAIL_CACHE.get(k);
            if (mem != null) return mem;
        }
        // 内存没有 → 查磁盘（命中后回填内存，下次连 IO 都省了）
        BookDetail disk = detailDiskRead(k);
        if (disk != null) {
            synchronized (DETAIL_CACHE) {
                DETAIL_CACHE.put(k, disk);
            }
        }
        return disk;
    }

    private static void detailCachePut(Book b, BookDetail d) {
        String k = cacheKeyOf(b);
        if (k.length() < 2 || d == null) return;
        synchronized (DETAIL_CACHE) {
            DETAIL_CACHE.put(k, d);
        }
        detailDiskWrite(k, d);
    }

    /**
     * 磁盘读：用后台线程池做，避免在主线程/详情赛跑线程上做 IO。
     * 这里同步读（调用方本来就在后台线程），文件很小（几 KB ~ 几百 KB）。
     */
    private static BookDetail detailDiskRead(String key) {
        try {
            java.io.File f = detailDiskFile(key);
            if (f == null || !f.exists()) return null;
            if (System.currentTimeMillis() - f.lastModified() > DETAIL_DISK_TTL_MS) {
                f.delete();
                return null;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder((int) Math.min(f.length(), 1 << 20));
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
            return detailFromJson(new org.json.JSONObject(sb.toString()));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void detailDiskWrite(final String key, final BookDetail d) {
        try {
            SINGLE_POOL.execute(new Runnable() {
                public void run() {
                    java.io.FileOutputStream fos = null;
                    try {
                        java.io.File f = detailDiskFile(key);
                        if (f == null) return;
                        fos = new java.io.FileOutputStream(f);
                        fos.write(d.toJson().toString().getBytes("UTF-8"));
                        fos.flush();
                    } catch (Throwable ignored) {
                    } finally {
                        if (fos != null) try { fos.close(); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    /** 反序列化 BookDetail（供磁盘缓存回读） */
    private static BookDetail detailFromJson(org.json.JSONObject o) {
        if (o == null) return null;
        BookDetail d = new BookDetail();
        try {
            d.book = Book.fromJson(o.optJSONObject("book"));
            d.intro = o.optString("intro", "");
            org.json.JSONArray ca = o.optJSONArray("chapters");
            if (ca == null || ca.length() == 0) return null;   // 空章节表不缓存
            for (int i = 0; i < ca.length(); i++) {
                JSONObject cj = ca.optJSONObject(i);
                if (cj == null) continue;
                Chapter c = new Chapter();
                c.title = cj.optString("title", "");
                c.url = cj.optString("url", "");
                c.index = cj.optInt("index", i + 1);
                c.directUrl = cj.optString("directUrl", "");
                d.chapters.add(c);
            }
            d.total = d.chapters.size();
        } catch (Throwable t) {
            return null;
        }
        return d;
    }

    /** 前端「下拉刷新」时清掉，强制下次走网络 */
    public static void clearDetailCache() {
        synchronized (DETAIL_CACHE) {
            DETAIL_CACHE.clear();
        }
        try {
            final java.io.File d = detailDiskDir();
            if (d != null) {
                SINGLE_POOL.execute(new Runnable() {
                    public void run() {
                        try {
                            java.io.File[] fs = d.listFiles();
                            if (fs == null) return;
                            for (int i = 0; i < fs.length; i++) fs[i].delete();
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 取音频直链，带自动换源。
     * @param book     目标书（含 variants）
     * @param chapterIdx 想听的集号（1 基）
     * @param detail   已拿到的详情（可为 null，会自动取）
     * @param outUsed  输出参数：实际用的源 id + 章节（长度 2 的数组，用 String 传回）
     */
    public static String audioWithFallback(Book book, int chapterIdx, BookDetail detail,
                                           String[] outUsed) {
        return audioWithFallback(book, chapterIdx, detail, outUsed, null);
    }

    /**
     * ★ v2.9：给「下载」用的取链 —— 强制走网络，跳过本地命中。
     *
     * <p><b>为什么必须单独一个入口</b>：
     * {@link #audioWithFallback} 现在会优先返回本地文件（那是给播放用的）。
     * 如果下载也走它，就会出现荒谬的结果 ——
     * 「我要下载第 5 集」→ 命中缓存 → 直接返回缓存文件的路径 →
     * 下载逻辑把这个本地路径当音频地址去「下载」，
     * 实际上只是把已有文件原地搬了一遍，根本没联网。
     *
     * <p>所以下载必须绕开本地命中，老老实实解析一个真实的网络直链。
     * 取链缓存（TTL 3 分钟的直链缓存）可以复用 —— 那本来就是网络地址。
     */
    public static String audioForDownload(Book book, int chapterIdx, String[] outUsed) {
        return audioWithFallbackNetwork(book, chapterIdx, null, outUsed, null);
    }

    /**
     * 播放用取链（带 headers 输出）。
     *
     * <p>★ v2.9：先做本地命中检查（下载 > 缓存），都没有才走网络。
     * 本地文件是确定存在的实体，比「某次解析出的直链」更可靠（直链有 TTL、会失效）。
     */
    public static String audioWithFallback(Book book, int chapterIdx, BookDetail detail,
                                           String[] outUsed, Map<String, String>[] outHeaders) {
        // ★ v2.9：本地文件优先 —— 下载 > 缓存 > 网络。
        //     下载  = 用户主动要留下的，最可信，且不受缓存淘汰影响；
        //     缓存  = 播放时自动预读的，命中就省一次网络往返；
        //     都没有 = 才走网络取链。
        //   注意：下载到外部目录后（文件已"移动"走），playUrl 会返回 content:// URI，
        //   播放器需用带 Context 的 setDataSource 重载才能解析（已在 PlayerCore 处理）。
        String local = com.tingshu.app.data.DownloadRepo.playUrl(book.key(), chapterIdx);
        if (local == null) {
            String cp = com.tingshu.app.data.CacheRepo.cachedPath(book.key(), chapterIdx);
            if (cp != null) local = "file://" + cp;
        }
        if (local != null) {
            if (outUsed != null && outUsed.length >= 2) {
                outUsed[0] = book.source;   // 本地文件与"哪个源"无关
                outUsed[1] = "";
            }
            if (outHeaders != null && outHeaders.length >= 1) outHeaders[0] = null;
            return local;
        }
        return audioWithFallbackNetwork(book, chapterIdx, detail, outUsed, outHeaders);
    }

    /**
     * 纯网络取链，带自动换源 + 播放请求头（不做任何本地命中检查）。
     *
     * <p>headers 由「实际取链成功的那个源」在返回前现场计算，
     * 因此自动换源后拿到的 headers 一定是换到的新源的（不会张冠李戴）。
     *
     * @param outHeaders 输出参数：长度 ≥1 的数组，outHeaders[0] 回填音频请求头（可为 null）
     */
    public static String audioWithFallbackNetwork(Book book, int chapterIdx, BookDetail detail,
                                                  String[] outUsed,
                                                  Map<String, String>[] outHeaders) {
        // ★ v2.2 播放提速：命中取链缓存直接返回，一次网络都不走。
        //   这是预加载能生效的关键——预加载线程提前算好放进缓存，
        //   用户点「下一集」时在这里秒回，播放器立刻进入 prepare。
        AudioSource hit = audioCacheGet(book, chapterIdx);
        if (hit != null) {
            if (outUsed != null && outUsed.length >= 2) {
                outUsed[0] = hit.source;
                outUsed[1] = "";
            }
            if (outHeaders != null && outHeaders.length >= 1) outHeaders[0] = hit.headers;
            return hit.url;
        }

        List<Book> tryList = candidates(book);

        // 先试主源（如果 detail 已经给出了对应章节，省一次详情请求）
        for (int i = 0; i < tryList.size(); i++) {
            Book cand = tryList.get(i);
            Source s = SourceRegistry.byId(cand.source);
            if (s == null) continue;
            try {
                Chapter ch = null;
                if (i == 0 && detail != null && detail.chapters != null) {
                    ch = findChapter(detail.chapters, chapterIdx);
                }
                if (ch == null) {
                    BookDetail d = s.detail(bookUrlOf(cand));
                    if (d == null || d.chapters == null || d.chapters.isEmpty()) continue;
                    ch = findChapter(d.chapters, chapterIdx);
                    // 集号越界则退到最后一集
                    if (ch == null) ch = d.chapters.get(d.chapters.size() - 1);
                }
                if (ch == null) continue;
                String url = s.audioUrl(ch, cand);
                if (url != null && url.length() > 0) {
                    Map<String, String> hdr = null;
                    // 用「刚刚取链成功的源 s」现算播放头，和 outUsed[0] 天然同一个源
                    try {
                        hdr = s.audioHeaders(url);
                    } catch (Throwable t) {
                        hdr = null;
                    }
                    if (outUsed != null && outUsed.length >= 2) {
                        outUsed[0] = cand.source;
                        outUsed[1] = ch.url;
                    }
                    if (outHeaders != null && outHeaders.length >= 1) outHeaders[0] = hdr;
                    // ★ v2.2：写入取链缓存 + 顺手把连接热起来，供"下一集"秒开
                    audioCachePut(book, chapterIdx, url, cand.source, hdr);
                    warmUp(url, hdr);
                    return url;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 候选源列表：主源在前，然后是同名书的其他源 */
    private static List<Book> candidates(Book book) {
        List<Book> out = new ArrayList<Book>();
        if (book == null) return out;
        out.add(book);
        if (book.variants != null) {
            for (Book v : book.variants) {
                if (v == null || v.source == null) continue;
                if (v.source.equals(book.source)) continue;
                out.add(v);
            }
        }
        return out;
    }

    // ==================== 音频直链解析结果缓存（v2.2 播放提速） ====================

    /**
     * 取链结果缓存：<b>跨集、跨源共享</b>，是"切下一集要等好几秒"的主要解法。
     *
     * <p><b>为什么要缓存"解析结果"而不是缓存音频文件</b>：切集的耗时几乎全花在
     * 「章节页 → 播放页 → 解析出 m3u8/mp3 直链」这一串 HTTP 往返上，
     * 而直链本身是一个几 KB 的字符串。缓存它命中率高、代价近乎为零，
     * 又不像缓存音频文件那样受版权/流量/存储的牵制。
     *
     * <p><b>为什么同时是"下一集预加载"的底座</b>：预加载线程提前把下一集的直链
     * 解析号塞进来，用户点"下一集"时 {@link #audioWithFallback} 第一件事就是
     * 命中这里直接返回，省掉整串网络往返；解析出直链的同时还会
     * {@link #warmUp} 一次，让系统 DNS/TLS 先热起来。
     *
     * <p>失效策略：TTL 3 分钟 + LRU 16 条 + 播放失败时 {@link #invalidateAudio} 主动剔除。
     * 直链通常带时效签名，3 分钟足够覆盖"连播场景"，过期后自然回退到实时解析。
     */
    private static final long AUDIO_TTL_MS = 3L * 60 * 1000;
    private static final int AUDIO_CACHE_MAX = 16;

    /** 一条已解析好的取链结果：直链 + 实际源 + 请求头 */
    public static final class AudioSource {
        public String url;
        public String source;
        public Map<String, String> headers;
        public long at;
    }

    private static final LinkedHashMap<String, AudioSource> AUDIO_CACHE =
            new LinkedHashMap<String, AudioSource>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AudioSource> e) {
                    return size() > AUDIO_CACHE_MAX;
                }
            };

    private static String audioKey(Book b, int idx) {
        String k = cacheKeyOf(b);
        return k + "#" + idx;
    }

    /** 取缓存的取链结果（已过期返回 null 并顺手剔除） */
    public static AudioSource audioCacheGet(Book book, int chapterIdx) {
        String k = audioKey(book, chapterIdx);
        if (k.length() < 3) return null;
        synchronized (AUDIO_CACHE) {
            AudioSource a = AUDIO_CACHE.get(k);
            if (a == null) return null;
            if (System.currentTimeMillis() - a.at > AUDIO_TTL_MS) {
                AUDIO_CACHE.remove(k);
                return null;
            }
            return a;
        }
    }

    /** 写入取链结果缓存 */
    public static void audioCachePut(Book book, int chapterIdx, String url,
                                     String source, Map<String, String> headers) {
        if (url == null || url.length() == 0) return;
        String k = audioKey(book, chapterIdx);
        if (k.length() < 3) return;
        AudioSource a = new AudioSource();
        a.url = url;
        a.source = source;
        a.headers = headers;
        a.at = System.currentTimeMillis();
        synchronized (AUDIO_CACHE) {
            AUDIO_CACHE.put(k, a);
        }
    }

    /**
     * 主动剔除某集的取链缓存。
     * 播放失败（403 / 直链过期）时调用，避免下次又拿着同一个死链去撞墙。
     */
    public static void invalidateAudio(Book book, int chapterIdx) {
        String k = audioKey(book, chapterIdx);
        if (k.length() < 3) return;
        synchronized (AUDIO_CACHE) {
            AUDIO_CACHE.remove(k);
        }
    }

    /** 下拉刷新 / 换源后清空，强制全部重新解析 */
    public static void clearAudioCache() {
        synchronized (AUDIO_CACHE) {
            AUDIO_CACHE.clear();
        }
    }

    /**
     * 让直链对应的 host 先建立连接（DNS + TCP + TLS 握手）。
     *
     * <p>MediaPlayer 自己也要握手一次，但那是"播放器线程"里做的，
     * 用户要等它完成才出声。这里在解析出直链的同一瞬间，用后台线程
     * 先打一个 HEAD 把连接热起来。即使 HEAD 失败也无所谓——
     * 它只是个加速提示，不影响任何功能。
     */
    public static void warmUp(final String url, final Map<String, String> headers) {
        if (url == null || !url.startsWith("http")) return;
        try {
            SINGLE_POOL.execute(new Runnable() {
                public void run() {
                    try {
                        java.net.HttpURLConnection c =
                                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                        c.setRequestMethod("HEAD");
                        c.setConnectTimeout(3000);
                        c.setReadTimeout(3000);
                        c.setInstanceFollowRedirects(true);
                        c.setRequestProperty("User-Agent", com.tingshu.app.core.Net.UA);
                        if (headers != null) {
                            for (Map.Entry<String, String> e : headers.entrySet()) {
                                if (e.getKey() != null && e.getValue() != null) {
                                    c.setRequestProperty(e.getKey(), e.getValue());
                                }
                            }
                        }
                        c.connect();
                        c.getResponseCode();
                        c.disconnect();
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    // ==================== 下一集预加载（v2.2 播放提速） ====================

    /**
     * 预加载线程池：单线程。
     * 预加载是"抢跑"，绝不能和用户主动发起的请求抢占线程池——
     * 所以独立一条线，用户点播时永远有线程可用。
     */
    private static final ExecutorService PRELOAD_POOL =
            Executors.newSingleThreadExecutor(namedFactory("preload"));

    /** 记录最近一次预加载任务，避免同一集被反复预取 */
    private static volatile String PRELOAD_TASK = "";

    /**
     * 预加载下一集的直链（后台静默，失败无感）。
     *
     * <p><b>做什么</b>：提前调用 {@link #audioWithFallback} 把下一集的直链解析好
     * 放进 {@link #AUDIO_CACHE}，并 {@link #warmUp} 热连接。
     * 等用户/连播真的切到那一集时，取链在缓存里秒回，省掉整串 HTTP 往返
     * （实测这一步就是切集卡顿的主因）。
     *
     * <p><b>不做什么</b>：不下载音频文件本身。只缓存一个 URL 字符串，
     * 不占存储、不耗大流量，也不涉及版权内容的落盘。
     *
     * @param book   当前书
     * @param nextIdx 要预取的集号（1 基）
     * @param detail 详情（可为 null，null 时 preload 内部会自己取一次）
     */
    public static void preloadNext(final Book book, final int nextIdx, final BookDetail detail) {
        if (book == null || nextIdx <= 0) return;
        // 已有有效缓存就别白跑一趟（连播时 onStarted 会被反复触发）
        if (audioCacheGet(book, nextIdx) != null) return;

        final String task = audioKey(book, nextIdx);
        if (task.equals(PRELOAD_TASK)) return;
        PRELOAD_TASK = task;

        try {
            PRELOAD_POOL.execute(new Runnable() {
                public void run() {
                    try {
                        // audioWithFallback 内部命中即返回、未命中则解析并写入缓存
                        String url = audioWithFallback(book, nextIdx, detail, null, null);
                        if (url == null || url.length() == 0) {
                            // 预取失败就把任务标记清掉，下次触发时再试
                            if (task.equals(PRELOAD_TASK)) PRELOAD_TASK = "";
                        }
                    } catch (Throwable t) {
                        if (task.equals(PRELOAD_TASK)) PRELOAD_TASK = "";
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    /** 切书 / 停止播放时清掉预加载标记 */
    public static void resetPreload() {
        PRELOAD_TASK = "";
    }

    /** 部分源的 url 字段存的是 bookId，统一在这里判 */
    private static String bookUrlOf(Book b) {
        return b.url != null && b.url.length() > 0 ? b.url : b.bookId;
    }

    private static Chapter findChapter(List<Chapter> list, int idx) {
        for (Chapter c : list) {
            if (c.index == idx) return c;
        }
        return null;
    }
}
