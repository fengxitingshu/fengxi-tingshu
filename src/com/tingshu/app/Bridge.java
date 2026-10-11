package com.tingshu.app;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.tingshu.app.data.HistoryRepo;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.BookDetail;
import com.tingshu.app.data.Models.History;
import com.tingshu.app.source.Aggregator;
import com.tingshu.app.source.Source;
import com.tingshu.app.source.SourceRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * JS 桥。WebView 与 Java 的唯一接缝。
 *
 * 铁律：
 * 1) @JavascriptInterface 方法在 WebView 的 JS 线程执行，绝不直接操作 MediaPlayer / WebView / UI。
 * 2) 耗时任务丢 Aggregator 的线程池，结果用 ui.post 回主线程再 evaluateJavascript。
 * 3) 传数据一律用 org.json 拼，禁止手工字符串拼接（转义地狱）。
 */
public class Bridge {

    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 由 MainActivity 注入的播放控制实现（第 0 批仍在 Activity 里，第 3 批迁到 Service） */
    private final PlayerControl player;
    /** 宿主 Activity，分享/主题等需要 Context 的能力用 */
    private final android.app.Activity activity;

    /** 播放控制接口，便于后续把实现从 Activity 换成 Service 而不动 Bridge */
    public interface PlayerControl {
        /**
         * @param headers 源要求的播放请求头（Referer 等），可为 null。
         *                缺了它，需要 Referer 的源会 403 播放失败。
         */
        void play(String source, String chapterUrl, String title, String directUrl,
                  Map<String, String> headers);
        /**
         * 装载播放队列（书 + 整张章节表 + 当前集下标）。
         *
         * <p>必须在 {@link #play} 之前调用。PlayerCore 靠这份队列做三件事：
         * <ul>
         *   <li>写播放历史（没有队列 → saveRecord 直接 return，历史为空）</li>
         *   <li>自然播完自动下一集（没有队列 → 找不到下一集，只能停）</li>
         *   <li>通知栏 / 锁屏显示"第几集 / 共几集"</li>
         * </ul>
         *
         * @param idx 当前集下标（0 基）
         */
        void setQueue(Book b, java.util.List<com.tingshu.app.data.Models.Chapter> chs, int idx);
        void pause();
        void resume();
        void toggle();
        /**
         * 幂等开始播放：已在播则忽略（配合「点开播放页自动播放」）。
         * ★ v2.6：改成返回 boolean —— 冷启动时 native 没有播放器，
         *   必须让调用方知道"resume 不了，得重新取链"，否则会静默失败。
         * @return true = 确实在播；false = 没有可复用的播放器
         */
        boolean playIfPaused();
        /**
         * 当前播放器里的书（没在播/已释放则为 null）。
         * 供 resumeSession 判断是否有活会话；不要在这里做重活。
         */
        Book currentBook();
        /** 当前集号（1 基），无则为 0 */
        int currentIndex();
        /** 当前进度秒，无则为 -1 */
        int currentPosSec();
        /** 当前集标题，无则为 "" */
        String currentChapterTitle();
        /** 当前播放列表集数，无则为 0 */
        int currentChapterCount();
        void seek(int seconds);
        void setSpeed(float speed);
        /**
         * ★ v3.1 设置音量增益百分比（100 = 原始音量，范围 50~300）。
         * 见 {@code PlayerCore.setGain} 了解两条实现路径（衰减 / 响度增强）。
         */
        void setGain(int pct);
        /** ★ v3.1 当前音量增益百分比 */
        int gain();
        void stop();
        void next();
        void prev();
        /** 播放器当前是否真的在出声（供 playState 校正 UI 图标） */
        boolean isPlaying();
    }

    public Bridge(WebView web, PlayerControl player) {
        this.web = web;
        this.player = player;
        this.activity = (player instanceof android.app.Activity)
                ? (android.app.Activity) player : null;
    }

    // ==================== 内部工具 ====================

    /** 回主线程执行 JS */
    private void js(final String code) {
        ui.post(new Runnable() {
            public void run() {
                try {
                    web.evaluateJavascript(code, null);
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 回调 JS 函数，参数是一个 JSON 字符串（已转义） */
    private void callback(String fn, JSONObject arg) {
        if (arg == null) arg = new JSONObject();
        js("window." + fn + "(" + arg.toString() + ")");
    }

    private void callback(String fn, JSONArray arg) {
        if (arg == null) arg = new JSONArray();
        js("window." + fn + "(" + arg.toString() + ")");
    }

    /** 回调 JS 函数，参数是裸布尔（供 onPlayState(true/false) 这类接口用） */
    private void callbackBool(String fn, boolean arg) {
        js("window." + fn + "(" + arg + ")");
    }

    // ==================== 账号（接口预留） ====================
    // 本期只做接口占位：前端已经按这套契约写好，
    // 后续接入注册/登录服务时，只要在这里填充实现并回调 onAccount* 即可，
    // 前端一行都不用改。

    /** 查询当前登录状态；未接入服务时回「未登录」 */
    @JavascriptInterface
    public void accountInfo() {
        ui.post(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    // 服务接入后：读取本地缓存 token / 用户资料填到这里
                    o.put("user", JSONObject.NULL);
                    o.put("available", false);
                } catch (Throwable ignored) {}
                callback("onAccountInfo", o);
            }
        });
    }

    /** 预留：登录 */
    @JavascriptInterface
    public void accountLogin() {
        ui.post(new Runnable() {
            public void run() { callback("onAccountUnavailable", new JSONObject()); }
        });
    }

    /** 预留：注册 */
    @JavascriptInterface
    public void accountRegister() {
        ui.post(new Runnable() {
            public void run() { callback("onAccountUnavailable", new JSONObject()); }
        });
    }

    /** 预留：退出登录 */
    @JavascriptInterface
    public void accountLogout() {
        ui.post(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    o.put("ok", true);
                    o.put("user", JSONObject.NULL);
                    o.put("message", "已退出登录");
                } catch (Throwable ignored) {}
                callback("onAccountChanged", o);
            }
        });
    }

    // ==================== 搜索 ====================

    @JavascriptInterface
    public void search(final String kw, final int page) {
        // 记录搜索历史（只在第 1 页记，翻页不重复计数）
        if (page <= 1) {
            com.tingshu.app.data.SearchRepo.add(kw);
        }
        Aggregator.searchPool().execute(new Runnable() {
            public void run() {
                try {
                    // 按源收集原始分组数据：搜索页「按来源筛选」需要
                    final java.util.Map<String, JSONArray> bySource =
                            new java.util.LinkedHashMap<String, JSONArray>();
                    // 每个源的处理结果：搜索诊断用（命中/无结果/超时/失败）
                    final List<JSONObject> diag = new java.util.ArrayList<JSONObject>();
                    final List<Book> merged = Aggregator.searchAll(kw, page, null,
                            new Aggregator.SearchCallback() {
                        public void onPartial(String sourceId, List<Book> books) {
                            if (books == null || books.isEmpty()) return;
                            JSONArray arr = new JSONArray();
                            for (Book b : books) arr.put(b.toJson());
                            bySource.put(sourceId, arr);
                            // ★ v2.2 边出边渲染：立刻推给前端，慢源不再拖住快源。
                            //   前端 onSearchPartial 会把这批书追加进列表，
                            //   用户几百毫秒就能看到第一批结果。
                            try {
                                JSONObject o = new JSONObject();
                                o.put("source", sourceId == null ? "" : sourceId);
                                o.put("sourceName", BridgeSource.sourceNameOf(sourceId));
                                o.put("books", arr);
                                callback("onSearchPartial", o);
                            } catch (Throwable ignored) {}
                        }
                        public void onDone(List<Book> m) {}
                        public void onSourceResult(String sourceId, int status,
                                                   List<Book> books, String error) {
                            diag.add(diagItem(sourceId, status,
                                    books == null ? 0 : books.size(), error));
                        }
                    });
                    JSONArray arr = new JSONArray();
                    for (Book b : merged) arr.put(b.toJson());
                    callback("onSearchResult", arr);
                    JSONObject grp = new JSONObject();
                    for (java.util.Map.Entry<String, JSONArray> e : bySource.entrySet()) {
                        grp.put(e.getKey(), e.getValue());
                    }
                    callback("onSearchBySource", grp);
                    JSONArray dj = new JSONArray();
                    for (JSONObject o : diag) dj.put(o);
                    callback("onSearchDiagnostics", dj);
                } catch (Throwable t) {
                    callback("onSearchResult", new JSONArray());
                    callback("onSearchBySource", new JSONObject());
                    callback("onSearchDiagnostics", new JSONArray());
                }
            }
        });
    }

    /** 组装一条搜索诊断记录 */
    private JSONObject diagItem(String sourceId, int status, int count, String error) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", sourceId == null ? "" : sourceId);
            o.put("name", BridgeSource.sourceNameOf(sourceId));
            o.put("status", status);
            o.put("count", count);
            o.put("error", error == null ? "" : error);
        } catch (Throwable ignored) {}
        return o;
    }

    /** 只在指定源里搜 */
    @JavascriptInterface
    public void searchInSource(final String kw, final String sourceId, final int page) {
        Aggregator.searchPool().execute(new Runnable() {
            public void run() {
                try {
                    final List<Book> merged = Aggregator.searchAll(kw, page, sourceId, null);
                    JSONArray arr = new JSONArray();
                    for (Book b : merged) arr.put(b.toJson());
                    callback("onSearchResult", arr);
                } catch (Throwable t) {
                    callback("onSearchResult", new JSONArray());
                }
            }
        });
    }

    // ==================== 搜索历史 ====================

    /** 取搜索历史（新的在前）。前端在搜索页空态时展示，点击直接重搜。 */
    @JavascriptInterface
    public void searchHist() {
        Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                JSONArray arr;
                try {
                    arr = com.tingshu.app.data.SearchRepo.list();
                } catch (Throwable t) {
                    arr = new JSONArray();
                }
                callback("onSearchHist", arr);
            }
        });
    }

    /** 删一条搜索历史 */
    @JavascriptInterface
    public void searchHistDel(final String kw) {
        Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                try {
                    com.tingshu.app.data.SearchRepo.remove(kw);
                } catch (Throwable ignored) {}
                try {
                    callback("onSearchHist", com.tingshu.app.data.SearchRepo.list());
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 清空搜索历史 */
    @JavascriptInterface
    public void searchHistClear() {
        Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                try {
                    com.tingshu.app.data.SearchRepo.clear();
                } catch (Throwable ignored) {}
                callback("onSearchHist", new JSONArray());
            }
        });
    }

    // ==================== 详情 ====================

    /**
     * 取详情。前端把整条 Book JSON 传过来（含 variants），
     * 这样主源挂了能自动试同名书的其他源。
     */
    @JavascriptInterface
    public void detailFromJson(final String bookJson) {
        final Book b = parseBook(bookJson);
        if (b == null) {
            callback("onDetail", new JSONObject());
            return;
        }
        Aggregator.detailPool().execute(new Runnable() {
            public void run() {
                BookDetail d;
                try {
                    d = Aggregator.detailWithFallback(b);
                } catch (Throwable t) {
                    d = new BookDetail(b);
                }
                callback("onDetail", d.toJson());
            }
        });
    }

    /** 清掉详情内存缓存（下拉刷新时调，强制下次重新联网拿章节） */
    @JavascriptInterface
    public void clearDetailCache() {
        try {
            Aggregator.clearDetailCache();
        } catch (Throwable ignored) {}
    }

    /** 兼容旧的三参数签名（保留，防止遗漏调用点） */
    @JavascriptInterface
    public void detail(final String source, final String url, final String title) {
        Book b = new Book();
        b.source = source;
        b.url = url;
        b.title = title;
        detailFromJson(b.toJson().toString());
    }

    /**
     * 恢复上次播放会话（App 冷启动 / 前端状态丢失时用）。
     *
     * 只回传「上次听的哪本书、哪一集、进度多少」，**不自动播放**——
     * 由前端决定是挂起悬浮球（等用户点）还是直接续播。
     * 回传 onResumeSession(JSONObject)：
     * <pre>
     *   { has: true, book: {...}, index: 1基集号, posMs, chapTitle, chapterCount, playing }
     *   { has: false }   // 没有历史
     * </pre>
     * 若 native 侧播放器里已经有书在播（比如 Activity 重建），优先用活的那个。
     */
    @JavascriptInterface
    public void resumeSession() {
        Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    // 1) 播放器里还有活的书 —— 最准
                    Book live = null;
                    try { live = player.currentBook(); } catch (Throwable ignored) {}
                    if (live != null) {
                        int idx = 1, pos = 0, cnt = 0;
                        String ct = "";
                        try { idx = Math.max(1, player.currentIndex()); } catch (Throwable ignored) {}
                        try { pos = Math.max(0, player.currentPosSec()) * 1000; } catch (Throwable ignored) {}
                        try { cnt = player.currentChapterCount(); } catch (Throwable ignored) {}
                        try { ct = String.valueOf(player.currentChapterTitle()); } catch (Throwable ignored) {}
                        o.put("book", live.toJson());
                        o.put("has", true);
                        o.put("index", idx);
                        o.put("posMs", pos);
                        o.put("chapTitle", ct);
                        o.put("chapterCount", cnt);
                        o.put("playing", false);
                        o.put("live", true);
                        callback("onResumeSession", o);
                        return;
                    }
                    // 2) 退回最近一条收听历史
                    History h = HistoryRepo.latest();
                    if (h != null && h.book != null) {
                        o.put("index", Math.max(1, h.lastIndex));
                        o.put("posMs", Math.max(0, h.lastPosMs));
                        o.put("chapTitle", h.lastChapTitle == null ? "" : h.lastChapTitle);
                        o.put("chapterCount", h.chapterCount);
                        o.put("book", h.book.toJson());
                        o.put("has", true);
                        o.put("playing", false);
                        o.put("fromHistory", true);
                        callback("onResumeSession", o);
                        return;
                    }
                    o.put("has", false);
                } catch (Throwable t) {
                    try { o.put("has", false); } catch (Throwable ignored) {}
                }
                callback("onResumeSession", o);
            }
        });
    }

    /** 解析前端传来的 Book JSON（含 variants / sources） */
    private static Book parseBook(String json) {
        if (json == null || json.length() == 0) return null;
        try {
            return Book.fromJson(new JSONObject(json));
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 播放 ====================

    /**
     * 播放某一集（推荐路径）。
     * 前端传整本书 JSON + 集号，native 侧负责取链，主源失败自动换源。
     *
     * <p><b>保留仅为兼容老前端</b>：新前端请走 {@link #playQueue}，
     * 因为这里没有章节表，PlayerCore 无法写历史、也无法自动连播。
     *
     * @param bookJson 整条 Book JSON（含 variants / sources）
     * @param chapterIdx 1 基集号
     */
    @JavascriptInterface
    public void playFromJson(final String bookJson, final int chapterIdx) {
        playQueue(bookJson, null, chapterIdx - 1);
    }

    /**
     * 播放某一集（当前主路径）。
     *
     * <p>与 {@link #playFromJson} 的唯一区别：**额外带一张完整章节表**，
     * 交给 PlayerCore 作为播放队列。有了队列才能：
     * <ul>
     *   <li>写播放历史（见 PlayerCore.saveRecord）</li>
     *   <li>自然播完自动下一集</li>
     *   <li>通知栏显示"第几集 / 共几集"</li>
     * </ul>
     *
     * @param bookJson     整条 Book JSON
     * @param chaptersJson 章节数组 JSON（可为 null，退化为单集播放）
     * @param idx0         当前集下标，<b>0 基</b>
     */
    @JavascriptInterface
    public void playQueue(final String bookJson, final String chaptersJson, final int idx0) {
        final Book b = parseBook(bookJson);
        if (b == null) {
            callback("onPlayError", new JSONObject());
            return;
        }

        // 解析章节表；解析不出来就退化成"只有当前这一集"
        final java.util.List<com.tingshu.app.data.Models.Chapter> chs =
                new java.util.ArrayList<com.tingshu.app.data.Models.Chapter>();
        try {
            if (chaptersJson != null && chaptersJson.length() > 2) {
                org.json.JSONArray arr = new org.json.JSONArray(chaptersJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject cj = arr.optJSONObject(i);
                    if (cj == null) continue;
                    com.tingshu.app.data.Models.Chapter ch =
                            new com.tingshu.app.data.Models.Chapter();
                    ch.title = cj.optString("title", "");
                    ch.url = cj.optString("url", "");
                    ch.index = cj.optInt("index", i + 1);
                    ch.directUrl = cj.optString("directUrl", "");
                    chs.add(ch);
                }
            }
        } catch (Throwable ignored) {}

        int idx = Math.max(0, idx0);
        if (idx >= chs.size()) idx = chs.size() - 1;
        final int useIdx = idx;

        // 1 基集号：优先用章节自带的 index，否则用下标 +1
        int humanIdx = (useIdx >= 0 && useIdx < chs.size() && chs.get(useIdx).index > 0)
                ? chs.get(useIdx).index : useIdx + 1;
        final int chapterIdx = humanIdx;

        // ★ 关键：先把队列交给播放器，再取链。顺序不能反 ——
        //   playUrl 成功后 onStarted→saveRecord 会立刻读这份队列写历史。
        ui.post(new Runnable() {
            public void run() {
                player.setQueue(b, chs, useIdx);
            }
        });

        Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                String audio = null;
                String[] out = new String[2];
                @SuppressWarnings("unchecked")
                Map<String, String>[] outHdr = new Map[1];
                try {
                    audio = Aggregator.audioWithFallback(b, chapterIdx, null, out, outHdr);
                } catch (Throwable ignored) {}
                final String url = audio;
                if (url == null) {
                    callback("onPlayError", new JSONObject());
                    return;
                }
                final String usedSrc = out[0] != null ? out[0] : b.source;
                final Map<String, String> hdr = outHdr[0];
                final String title = chapterTitle(chapterIdx, b);
                ui.post(new Runnable() {
                    public void run() {
                        player.play(usedSrc, null, title, url, hdr);
                        JSONObject o = new JSONObject();
                        try {
                            o.put("url", url);
                            o.put("title", title);
                            o.put("source", usedSrc);
                            o.put("sourceName", BridgeSource.sourceNameOf(usedSrc));
                            o.put("index", chapterIdx);
                            o.put("total", chs.size());
                            o.put("playing", true);
                        } catch (Throwable ignored) {}
                        callback("onPlaying", o);
                    }
                });
            }
        });
    }

    /**
     * 查询播放器真实状态。
     *
     * <p>用途：前端切页 / 回前台时，图标可能因为错过某次事件而残留，
     * 主动拉一次真值校正，避免"在播却显示三角"。
     */
    @JavascriptInterface
    public void playState() {
        ui.post(new Runnable() {
            public void run() {
                boolean playing = false;
                try { playing = playerIsPlaying(); } catch (Throwable ignored) {}
                callbackBool("onPlayState", playing);
            }
        });
    }

    /** 由 MainActivity 覆写更精确；默认看 core 是否在播 */
    private boolean playerIsPlaying() {
        try {
            return player.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String chapterTitle(int idx, Book b) {
        return (b != null && b.title != null && b.title.length() > 0)
                ? (b.title + " 第" + idx + "集") : ("第" + idx + "集");
    }

    /**
     * 播放某一集（已知章节定位串的快速路径）。
     * 前端已经知道章节 url 时用这个，省一次详情请求。
     */
    @JavascriptInterface
    public void play(final String source, final String chapterUrl, final String title) {
        Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                String audio = null;
                Map<String, String> hdr = null;
                try {
                    Source s = SourceRegistry.byId(source);
                    if (s != null) {
                        audio = s.audioUrl(
                                new com.tingshu.app.data.Models.Chapter(0, title, chapterUrl), null);
                        if (audio != null && audio.length() > 0) {
                            try {
                                hdr = s.audioHeaders(audio);
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
                final String url = audio;
                final Map<String, String> headers = hdr;
                if (url == null) {
                    callback("onPlayError", new JSONObject());
                    return;
                }
                ui.post(new Runnable() {
                    public void run() {
                        player.play(source, chapterUrl, title, url, headers);
                        JSONObject o = new JSONObject();
                        try {
                            o.put("url", url);
                            o.put("title", title);
                            o.put("source", source);
                            o.put("sourceName", BridgeSource.sourceNameOf(source));
                        } catch (Throwable ignored) {}
                        callback("onPlaying", o);
                    }
                });
            }
        });
    }

    /** 直接播已知直链（详情页已拿到 url 的场景） */
    @JavascriptInterface
    public void playUrl(final String directUrl, final String title) {
        ui.post(new Runnable() {
            public void run() {
                player.play(null, null, title, directUrl, null);
                JSONObject o = new JSONObject();
                try {
                    o.put("url", directUrl);
                    o.put("title", title);
                } catch (Throwable ignored) {}
                callback("onPlaying", o);
            }
        });
    }

    @JavascriptInterface
    public void pause() {
        ui.post(new Runnable() {
            public void run() { player.pause(); }
        });
    }

    @JavascriptInterface
    public void resume() {
        ui.post(new Runnable() {
            public void run() { player.resume(); }
        });
    }

    @JavascriptInterface
    public void toggle() {
        ui.post(new Runnable() {
            public void run() { player.toggle(); }
        });
    }

    /**
     * 显式开始播放（幂等：已在播时什么都不做），用于「点开播放页自动播放」。
     *
     * <p>★ v2.6：把**真实结果**回传前端。冷启动时 native 侧没有播放器，
     * 原来这里静默失败 —— 前端于是永远停在"转圈"状态，用户看到的就是
     * 「点了悬浮球放不出来」。现在回一个 `onPlayNeedFetch`，前端会改走
     * `playChapter` 完整取链流程把它救回来。
     */
    @JavascriptInterface
    public void play() {
        ui.post(new Runnable() {
            public void run() {
                boolean ok = false;
                try { ok = player.playIfPaused(); } catch (Throwable ignored) {}
                if (!ok) {
                    // 没有可复用的播放器（进程被杀 / 播放器已释放）→ 让前端重新取链
                    js("window.onPlayNeedFetch && window.onPlayNeedFetch()");
                }
            }
        });
    }

    @JavascriptInterface
    public void next() {
        ui.post(new Runnable() {
            public void run() { player.next(); }
        });
    }

    @JavascriptInterface
    public void prev() {
        ui.post(new Runnable() {
            public void run() { player.prev(); }
        });
    }

    @JavascriptInterface
    public void seek(final int seconds) {
        ui.post(new Runnable() {
            public void run() { player.seek(seconds); }
        });
    }

    @JavascriptInterface
    public void setSpeed(final float speed) {
        ui.post(new Runnable() {
            public void run() { player.setSpeed(speed); }
        });
    }

    /**
     * ★ v3.1 设置音量增益（声音增幅/降幅）。
     *
     * <p>入参是百分比整数：100 = 原始音量，50 = 降一半，200 = 增幅到 200%。
     * 播放页那根滑杆直接把这个数递过来，不做单位换算——dB 对用户不好理解。
     */
    @JavascriptInterface
    public void setGain(final int pct) {
        ui.post(new Runnable() {
            public void run() { player.setGain(pct); }
        });
    }

    /** ★ v3.1 读当前增益值（播放页初始化滑杆位置用） */
    @JavascriptInterface
    public void getGain() {
        JSONObject o = new JSONObject();
        try {
            o.put("gain", player.gain());
        } catch (Throwable ignored) {}
        callback("onGain", o);
    }

    @JavascriptInterface
    public void stop() {
        ui.post(new Runnable() {
            public void run() { player.stop(); }
        });
    }

    @JavascriptInterface
    public void toastMsg(final String msg) {
        js("window.onToast(" + JSONObject.quote(msg) + ")");
    }

    // ==================== 源信息 ====================

    /** 返回所有源（含启停状态），源管理页用 */
    @JavascriptInterface
    public void listSources() {
        Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                callback("onSourceList", BridgeSource.allSourcesJson());
            }
        });
    }

    /**
     * ★ v3.7.9：查询 Python 运行时状态。
     *
     * <p>背景：冷启动时 Python 解释器在后台解压 assets（13MB），这段时间
     * .py 源还没登记进源表。前端需要区分两种情况再决定提示文案：
     * <ul>
     *   <li>{@code state="loading"} —— 正在后台初始化，稍等就好（不要吓唬用户说"不支持"）；</li>
     *   <li>{@code state="ready"}   —— 已就绪；</li>
     *   <li>{@code state="failed"}  —— 启动失败，附 {@code error} 原因；</li>
     *   <li>{@code state="idle"}    —— 还没开始（理论上不会出现）。</li>
     * </ul>
     */
    @JavascriptInterface
    public void pythonStatus() {
        JSONObject o = new JSONObject();
        try {
            boolean ok = com.tingshu.app.python.PythonHost.available();
            String state;
            if (ok) state = "ready";
            else if (com.tingshu.app.python.PythonHost.starting()) state = "loading";
            else if (com.tingshu.app.python.PythonHost.lastError() != null
                    && com.tingshu.app.python.PythonHost.lastError().length() > 0) state = "failed";
            else state = "idle";
            o.put("ok", ok);
            o.put("state", state);
            o.put("error", com.tingshu.app.python.PythonHost.lastError());
            o.put("pending", com.tingshu.app.source.SourceRegistry.pythonPending());
        } catch (Throwable ignored) {}
        callback("onPythonStatus", o);
    }

    // ==================== 源导入 / 导出 ====================
    // 全部丢 singlePool：解析 JSON、写库、拉网络都可能慢。
    // 前端回调统一走 onSourceImported / onSourceExported / onCustomList / onSourceTested。

    /** 导入：粘贴的 JSON 文本 */
    @JavascriptInterface
    public void importSourceText(final String json, final boolean overwrite) {
        bg(new Runnable() {
            public void run() {
                JSONObject r = BridgeSource.importText(json, overwrite);
                try {
                    r.put("from", "text");
                } catch (Throwable ignored) {}
                callback("onSourceImported", r);
                // 导入完顺带把最新列表推给前端
                if (r.optInt("added", 0) > 0 || r.optInt("updated", 0) > 0) {
                    callback("onSourceList", BridgeSource.allSourcesJson());
                }
            }
        });
    }

    /** 导入：网络订阅链接 */
    @JavascriptInterface
    public void importSourceUrl(final String url, final boolean overwrite) {
        bg(new Runnable() {
            public void run() {
                JSONObject r = BridgeSource.importUrl(url, overwrite);
                try {
                    r.put("from", "url");
                } catch (Throwable ignored) {}
                callback("onSourceImported", r);
                if (r.optInt("added", 0) > 0 || r.optInt("updated", 0) > 0) {
                    callback("onSourceList", BridgeSource.allSourcesJson());
                }
            }
        });
    }

    /** 导入：从本地文件读出来的 JSON 文本（文件选择由 MainActivity 完成） */
    @JavascriptInterface
    public void importSourceLocal(final String json, final String fileName, final boolean overwrite) {
        bg(new Runnable() {
            public void run() {
                // 本地文件也可能是「原版我的听书」订阅清单（只有 download_url），
                // 那种文件不是源配置，直接按 JSON 解析会「没有解析到任何源定义」。
                JSONObject r;
                if (BridgeSource.isEprendreManifest(json)) {
                    r = BridgeSource.importEprendreManifest(json, "");
                } else {
                    r = BridgeSource.importText(json, overwrite);
                }
                try {
                    r.put("from", "file");
                    r.put("file", fileName == null ? "" : fileName);
                } catch (Throwable ignored) {}
                callback("onSourceImported", r);
                if (r.optInt("added", 0) > 0 || r.optInt("updated", 0) > 0) {
                    callback("onSourceList", BridgeSource.allSourcesJson());
                    callback("onCustomList", BridgeSource.customListJson());
                    callback("onPluginList", BridgeSource.pluginListJson());
                }
            }
        });
    }

    /** 让宿主 Activity 打开系统文件选择器（结果回 onSourceFilePicked；.jar 走插件导入，.json 走文本导入） */
    @JavascriptInterface
    public void pickSourceFile() {
        ui.post(new Runnable() {
            public void run() {
                if (activity instanceof com.tingshu.app.MainActivity) {
                    ((com.tingshu.app.MainActivity) activity).pickSourceFile();
                } else {
                    js("window.onToast(" + JSONObject.quote("当前环境不支持选择文件") + ")");
                }
            }
        });
    }

    /** 导入：jar 插件源（DexClassLoader 动态加载） */
    @JavascriptInterface
    public void importSourceJar(final String jarPath, final String fileName) {
        bg(new Runnable() {
            public void run() {
                JSONObject r = BridgeSource.importJar(jarPath, fileName);
                callback("onSourceImported", r);
                callback("onSourceList", BridgeSource.allSourcesJson());
                callback("onCustomList", BridgeSource.customListJson());
                callback("onPluginList", BridgeSource.pluginListJson());
            }
        });
    }

    /**
     * ★ v3.0：导入 JS 脚本书源插件包（.zip，跑在 Rhino 引擎上）。
     *
     * <p>与 {@link #importSourceJar} 是并列关系，前端按文件类型分流过来。
     * 分开两个接口而不是塞成一个：两边失败原因、提示语、装载耗时都差很多
     * （JS 包要解包 + 执行脚本，一个 ShunSources 包会一次性注册 19 个源），
     * 合成一个的话错误信息就没法精确。
     */
    @JavascriptInterface
    public void importSourceJs(final String zipPath, final String fileName) {
        bg(new Runnable() {
            public void run() {
                JSONObject r = BridgeSource.importJsPlugin(zipPath, fileName);
                callback("onSourceImported", r);
                callback("onSourceList", BridgeSource.allSourcesJson());
                callback("onCustomList", BridgeSource.customListJson());
                callback("onPluginList", BridgeSource.pluginListJson());
            }
        });
    }

    /**
     * ★ v3.4：导入 Python 源（单个 .py 或含 .py 的 .zip）。
     *
     * <p>与 {@link #importSourceJs}（Rhino 跑 JS）、{@link #importSourceJar}（DexClassLoader）
     * 并列的第三条通道：App 内置真实 CPython 解释器，直接加载运行原版
     * 「阅读/影视」体系的 {@code .py} 源，不做任何转译 —— 源作者怎么写就怎么跑，
     * 覆盖率与行为一致性都最好。
     *
     * <p>为什么要 bg：首次调用要启动解释器（解 bootstrap + 建 sys.path），
     * 冷启动几百毫秒；且源里的网络请求是同步阻塞的，绝不能放主线程。
     */
    @JavascriptInterface
    public void importPySources(final String path, final String fileName) {
        bg(new Runnable() {
            public void run() {
                JSONObject r = BridgeSource.importPySources(path, fileName);
                callback("onSourceImported", r);
                callback("onSourceList", BridgeSource.allSourcesJson());
                callback("onCustomList", BridgeSource.customListJson());
            }
        });
    }

    /** jar 插件列表 */
    @JavascriptInterface
    public void listPlugins() {        bg(new Runnable() {
            public void run() {
                callback("onPluginList", BridgeSource.pluginListJson());
            }
        });
    }

    /** 删除 jar 插件 */
    @JavascriptInterface
    public void removePlugin(final String filePath) {
        bg(new Runnable() {
            public void run() {
                boolean ok = BridgeSource.removePlugin(filePath);
                JSONObject o = new JSONObject();
                try {
                    o.put("filePath", filePath);
                    o.put("ok", ok);
                } catch (Throwable ignored) {}
                callback("onPluginChanged", o);
                callback("onSourceList", BridgeSource.allSourcesJson());
                callback("onPluginList", BridgeSource.pluginListJson());
            }
        });
    }

    /** 启停 jar 插件（该插件的所有源一起生效） */
    @JavascriptInterface
    public void setPluginEnabled(final String filePath, final boolean enabled) {
        bg(new Runnable() {
            public void run() {
                BridgeSource.setPluginEnabled(filePath, enabled);
                JSONObject o = new JSONObject();
                try {
                    o.put("filePath", filePath);
                    o.put("enabled", enabled);
                } catch (Throwable ignored) {}
                callback("onPluginChanged", o);
                callback("onSourceList", BridgeSource.allSourcesJson());
                callback("onPluginList", BridgeSource.pluginListJson());
            }
        });
    }

    /** 全部源名映射（id -> 显示名），给搜索分组/详情/播放页标注来源用 */
    @JavascriptInterface
    public void sourceNames() {
        bg(new Runnable() {
            public void run() {
                callback("onSourceNames", BridgeSource.sourceNamesJson());
            }
        });
    }

    /** 导出为 JSON 文本（前端拿去做剪贴板/分享） */
    @JavascriptInterface
    public void exportSources(final boolean includeBuiltin) {
        bg(new Runnable() {
            public void run() {
                final String json = BridgeSource.exportJson(includeBuiltin, true);
                JSONObject o = new JSONObject();
                try {
                    o.put("json", json);
                    o.put("count", com.tingshu.app.data.SourceStore.count());
                } catch (Throwable ignored) {}
                callback("onSourceExported", o);
            }
        });
    }

    /** 导出并直接调系统分享面板（生成"订阅文件"发给别人） */
    @JavascriptInterface
    public void shareSources(final boolean includeBuiltin) {
        bg(new Runnable() {
            public void run() {
                final String json = BridgeSource.exportJson(includeBuiltin, true);
                ui.post(new Runnable() {
                    public void run() {
                        try {
                            java.io.File f = new java.io.File(
                                    com.tingshu.app.core.App.ctx().getCacheDir(),
                                    "tingshu-sources.json");
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                            fo.write(json.getBytes("UTF-8"));
                            fo.flush();
                            fo.close();
                            android.content.Intent it = new android.content.Intent(
                                    android.content.Intent.ACTION_SEND);
                            it.setType("application/json");
                            it.putExtra(android.content.Intent.EXTRA_SUBJECT, "枫兮听书源订阅");
                            it.putExtra(android.content.Intent.EXTRA_TEXT,
                                    "枫兮听书源订阅（共 " + com.tingshu.app.data.SourceStore.count() + " 个自定义源）");
                            android.net.Uri uri = android.net.Uri.fromFile(f);
                            it.putExtra(android.content.Intent.EXTRA_STREAM, uri);
                            startChooser(it);
                        } catch (Throwable t) {
                            js("window.onToast(" + JSONObject.quote("导出分享失败") + ")");
                        }
                    }
                });
            }
        });
    }

    /** 自定义源列表 */
    @JavascriptInterface
    public void listCustom() {
        bg(new Runnable() {
            public void run() {
                callback("onCustomList", BridgeSource.customListJson());
            }
        });
    }

    /** 删除自定义源 */
    @JavascriptInterface
    public void removeCustom(final String id) {
        bg(new Runnable() {
            public void run() {
                boolean ok = BridgeSource.removeCustom(id);
                JSONObject o = new JSONObject();
                try {
                    o.put("id", id);
                    o.put("ok", ok);
                } catch (Throwable ignored) {}
                callback("onCustomChanged", o);
                callback("onSourceList", BridgeSource.allSourcesJson());
            }
        });
    }

    /**
     * ★ v3.7.4：按 id 删除任意类型的源（JSON / jar 插件 / JS 插件 / Python）。
     *
     * <p>{@link #removeCustom} 只认 SourceStore 里的 JSON 配置，对插件源与
     * Python 源无效。批量源管理需要一个"按 id 什么都能删"的入口，故新增本方法。
     * 回调与 removeCustom 完全一致（onCustomChanged + onSourceList 整表），
     * 前端可直接复用同一套回调逻辑。
     */
    @JavascriptInterface
    public void removeSourceById(final String id) {
        bg(new Runnable() {
            public void run() {
                boolean ok = BridgeSource.removeSourceById(id);
                JSONObject o = new JSONObject();
                try {
                    o.put("id", id);
                    o.put("ok", ok);
                } catch (Throwable ignored) {}
                callback("onCustomChanged", o);
                callback("onSourceList", BridgeSource.allSourcesJson());
            }
        });
    }

    /** 测试自定义源 */
    @JavascriptInterface
    public void testCustom(final String id) {
        bg(new Runnable() {
            public void run() {
                callback("onSourceTested", BridgeSource.testCustom(id));
            }
        });
    }

    /**
     * ★ v3.1：批量验证书源（可选去重 + 失效自动关停）。
     *
     * <p>这是源管理里最重的一个操作：19 个 JS 源跑一遍要 10~40 秒（限流站还要等间隔），
     * 所以**必须**两条回调分开给：
     * <ul>
     *   <li>{@code onVerifyProgress}：每验完一个就回一次，前端画进度条 / 滚动日志</li>
     *   <li>{@code onVerifyDone}：全部结束才回，带汇总结果，前端弹结果面板</li>
     * </ul>
     * 只给最后那个的话，用户点完按钮会以为卡死了。
     *
     * <p>进度回包字段刻意做得很窄（done/total/id/name/level），
     * 因为这条链路最频繁 —— total 个源就是 total 次跨语言调用，
     * 塞进去整个 JSONObject 会让 WebView 的 JS 线程一直忙着 GC。
     *
     * @param scope        "all" 全部源 / "enabled" 仅已启用 / "group:xxx" 某个分组
     * @param autoCloseDead 验证为失效的源自动停用
     * @param dedup        同域名重复的源自动删除（保留优先级更高的）
     */
    @JavascriptInterface
    public void verifySources(final String scope, final boolean autoCloseDead,
                              final boolean dedup) {
        Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                JSONObject r = BridgeSource.verifyAll(scope, autoCloseDead, dedup,
                        new BridgeSource.VerifyCb() {
                            public void onProgress(int done, int total, String id,
                                                   String name, String level) {
                                JSONObject p = new JSONObject();
                                try {
                                    p.put("done", done);
                                    p.put("total", total);
                                    p.put("id", id);
                                    p.put("name", name);
                                    p.put("level", level);
                                } catch (Throwable ignored) {}
                                callback("onVerifyProgress", p);
                            }
                        });
                // 验证过程会删源 / 关源，两边列表都得让前端重画
                callback("onSourceList", BridgeSource.allSourcesJson());
                callback("onCustomList", BridgeSource.customListJson());
                callback("onPluginList", BridgeSource.pluginListJson());
                callback("onVerifyDone", r);
            }
        });
    }

    /** 调整源顺序（delta = -1 上移 / +1 下移） */
    @JavascriptInterface
    public void moveSource(final String id, final int delta) {
        bg(new Runnable() {
            public void run() {
                callback("onSourceList", BridgeSource.moveSource(id, delta));
            }
        });
    }

    /** 给源改名（别名） */
    @JavascriptInterface
    public void renameSource(final String id, final String alias) {
        bg(new Runnable() {
            public void run() {
                callback("onSourceList", BridgeSource.renameSource(id, alias));
            }
        });
    }

    @JavascriptInterface
    public void setSourceEnabled(final String sourceId, final boolean enabled) {
        Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                com.tingshu.app.data.SourceRepo.setEnabled(sourceId, enabled);
                JSONObject o = new JSONObject();
                try {
                    o.put("id", sourceId);
                    o.put("enabled", enabled);
                } catch (Throwable ignored) {}
                callback("onSourceChanged", o);
            }
        });
    }

    /** 测试源连通性 */
    @JavascriptInterface
    public void testSource(final String sourceId) {
        Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                boolean ok = false;
                String msg = "";
                Source s = SourceRegistry.byId(sourceId);
                if (s == null) {
                    msg = "源不存在";
                } else {
                    try {
                        if (s.canSearch()) {
                            List<Book> r = s.search("小说", 1);
                            ok = r != null;
                            msg = ok ? ("正常，返回 " + r.size() + " 条") : "无响应";
                        } else {
                            String html = com.tingshu.app.core.Net.get(s.id().equals("pingshu5")
                                    ? "http://www.pingshu5.net/pbook/" : "https://vbus.cc/recommend/1", null);
                            ok = html != null && html.length() > 1000;
                            msg = ok ? "站点可达（该源仅支持浏览）" : "无响应";
                        }
                    } catch (Throwable t) {
                        msg = "失败：" + t.getClass().getSimpleName();
                    }
                }
                JSONObject o = new JSONObject();
                try {
                    o.put("id", sourceId);
                    o.put("ok", ok);
                    o.put("msg", msg);
                } catch (Throwable ignored) {}
                callback("onSourceTested", o);
            }
        });
    }

    // ==================== 发现（浏览分类） ====================

    /**
     * ★ v3.7.7：分类清单的进程内缓存。
     *
     * <p>categories() 对 JS/Python 源来说要起引擎，是最贵的一步；而分类清单
     * 实际上**几乎不变**（站点栏目名不会十分钟改一次）。所以第一次问完就存下来，
     * TTL 内再次打开首页直接用缓存，秒出。
     *
     * <p>key = 源 id，value = [0]=时间戳 [1]=分类数组。
     * 源列表变化（导入/删除/启停）时会由 invalidateCatCache() 整个清掉。
     */
    private static final java.util.Map<String, Object[]> CAT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, Object[]>();
    /** 分类缓存有效期：10 分钟。够短，站点改版也能很快反映；够长，切换页面不会重问。 */
    private static final long CAT_TTL_MS = 10 * 60 * 1000L;

    /** 源列表有变动（导入 / 删除 / 启停）时清空分类缓存 */
    public static void invalidateCatCache() {
        CAT_CACHE.clear();
    }

    @JavascriptInterface
    public void discoverSources() {
        // ★ v3.7.7：两处提速 ——
        //   1) 串行改并发：原来 for 循环逐个问 categories()，JS/Python 源首次调用
        //      要启动 Rhino / Chaquopy 引擎（几百 ms），10 个源就是好几秒。
        //      这些调用彼此独立，丢给 catPool 并行跑，总耗时 ≈ 最慢的那个源。
        //   2) 加 10 分钟进程内缓存：分类清单基本不变，第二次打开首页直接秒出，
        //      只有真正过期的源才会重新问。
        Aggregator.searchPool().execute(new Runnable() {
            public void run() {
                JSONArray arr = new JSONArray();
                try {
                    List<Source> list = SourceRegistry.browsable();
                    // 先筛出真正可浏览的（保持顺序稳定 —— 前端按这个顺序渲染）
                    final java.util.List<com.tingshu.app.source.BrowsableSource> bs =
                            new java.util.ArrayList<com.tingshu.app.source.BrowsableSource>();
                    for (Source s : list) {
                        if (s instanceof com.tingshu.app.source.BrowsableSource) {
                            bs.add((com.tingshu.app.source.BrowsableSource) s);
                        }
                    }
                    if (bs.isEmpty()) {
                        callback("onDiscoverSources", arr);
                        return;
                    }

                    // ---- 第一轮：能命中缓存的直接取，命不中的收集起来并发问 ----
                    final List<List<com.tingshu.app.source.Category>> results =
                            new java.util.ArrayList<List<com.tingshu.app.source.Category>>();
                    final java.util.List<Integer> missIdx =
                            new java.util.ArrayList<Integer>();
                    long now = System.currentTimeMillis();
                    for (int i = 0; i < bs.size(); i++) {
                        Object[] hit = CAT_CACHE.get(bs.get(i).id());
                        if (hit != null && (now - ((Long) hit[0])) < CAT_TTL_MS) {
                            @SuppressWarnings("unchecked")
                            List<com.tingshu.app.source.Category> c =
                                    (List<com.tingshu.app.source.Category>) hit[1];
                            results.add(c);
                        } else {
                            results.add(null);
                            missIdx.add(i);
                        }
                    }

                    // ---- 第二轮：只对未命中的源并发取分类 ----
                    if (!missIdx.isEmpty()) {
                        final java.util.concurrent.CountDownLatch latch =
                                new java.util.concurrent.CountDownLatch(missIdx.size());
                        for (int k = 0; k < missIdx.size(); k++) {
                            final int idx = missIdx.get(k);
                            Aggregator.catPool().execute(new Runnable() {
                                public void run() {
                                    try {
                                        List<com.tingshu.app.source.Category> c =
                                                bs.get(idx).categories();
                                        results.set(idx, c);
                                        CAT_CACHE.put(bs.get(idx).id(),
                                                new Object[]{System.currentTimeMillis(), c});
                                    } catch (Throwable ignored) {
                                        results.set(idx, null);
                                    } finally {
                                        latch.countDown();
                                    }
                                }
                            });
                        }
                        // 超时兜底 6s：某个源卡死也不至于把首页拖住
                        try {
                            latch.await(6, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {}
                    }

                    for (int i = 0; i < bs.size(); i++) {
                        JSONObject o = new JSONObject();
                        o.put("id", bs.get(i).id());
                        o.put("name", bs.get(i).name());
                        JSONArray cats = new JSONArray();
                        List<com.tingshu.app.source.Category> cs = results.get(i);
                        if (cs != null) {
                            for (com.tingshu.app.source.Category c : cs) {
                                JSONObject co = new JSONObject();
                                co.put("k", c.id);
                                co.put("n", c.name);
                                cats.put(co);
                            }
                        }
                        o.put("cats", cats);
                        arr.put(o);
                    }
                } catch (Throwable ignored) {}
                callback("onDiscoverSources", arr);
            }
        });
    }

    /**
     * 拉某个源某个分类的一页列表。
     * 走 SourceRegistry 分派，任何实现了 BrowsableSource 的源（含插件）都能用。
     */
    @JavascriptInterface
    public void browse(final String sourceId, final String path, final int page) {
        // ★ v3.7.5：与 discoverSources 同理 —— 首页/发现页唯一的网络请求，
        //   走 searchPool 后不再被 singlePool 里的 DB 写、源状态读写堵住。
        Aggregator.searchPool().execute(new Runnable() {
            public void run() {
                JSONArray arr = new JSONArray();
                String err = "";
                try {
                    Source s = SourceRegistry.byId(sourceId);
                    if (s == null) {
                        err = "源不存在或已停用";
                    } else if (!(s instanceof com.tingshu.app.source.BrowsableSource)) {
                        err = "该源不支持分类浏览";
                    } else {
                        List<Book> list =
                                ((com.tingshu.app.source.BrowsableSource) s).browse(path, page);
                        if (list != null) {
                            for (Book b : list) arr.put(b.toJson());
                        }
                    }
                } catch (Throwable t) {
                    err = "加载失败：" + t.getClass().getSimpleName() +
                            (t.getMessage() == null ? "" : (": " + t.getMessage()));
                }
                try {
                    JSONObject o = new JSONObject();
                    o.put("source", sourceId);
                    o.put("path", path);
                    o.put("page", page);
                    o.put("books", arr);
                    o.put("error", err);
                    callback("onBrowse", o);
                } catch (Throwable ignored) {}
            }
        });
    }

    // ==================== 收藏 / 分组 / 历史 ====================
    // WebView 同名接口只能注册一个对象，所以这些全部转发到 BridgeData。
    // 统一丢 singlePool，避免阻塞 WebView 的 JS 线程（SQLite 写可能慢）。

    private void bg(final Runnable r) {
        Aggregator.singlePool().execute(r);
    }

    @JavascriptInterface
    public void favToggle(final String bookJson, final long groupId) {
        bg(new Runnable() {
            public void run() { BridgeData.favToggle(web, ui, bookJson, groupId); }
        });
    }

    @JavascriptInterface
    public void favList(final long groupId) {
        bg(new Runnable() {
            public void run() { BridgeData.favList(web, ui, groupId); }
        });
    }

    @JavascriptInterface
    public void favRemove(final String key) {
        bg(new Runnable() {
            public void run() { BridgeData.favRemove(web, ui, key); }
        });
    }

    @JavascriptInterface
    public void favStates(final String keysJson) {
        bg(new Runnable() {
            public void run() { BridgeData.favStates(web, ui, keysJson); }
        });
    }

    @JavascriptInterface
    public void groupList() {
        bg(new Runnable() {
            public void run() { BridgeData.groupList(web, ui); }
        });
    }

    @JavascriptInterface
    public void groupAdd(final String name) {
        bg(new Runnable() {
            public void run() { BridgeData.groupAdd(web, ui, name); }
        });
    }

    @JavascriptInterface
    public void groupRename(final long id, final String name) {
        bg(new Runnable() {
            public void run() { BridgeData.groupRename(web, ui, id, name); }
        });
    }

    @JavascriptInterface
    public void groupDelete(final long id) {
        bg(new Runnable() {
            public void run() { BridgeData.groupDelete(web, ui, id); }
        });
    }

    @JavascriptInterface
    public void historyList(final int limit) {
        bg(new Runnable() {
            public void run() { BridgeData.historyList(web, ui, limit); }
        });
    }

    @JavascriptInterface
    public void historyRemove(final String key) {
        bg(new Runnable() {
            public void run() { BridgeData.historyRemove(web, ui, key); }
        });
    }

    @JavascriptInterface
    public void historyClear() {
        bg(new Runnable() {
            public void run() { BridgeData.historyClear(web, ui); }
        });
    }

    @JavascriptInterface
    public void historyGet(final String bookKey) {
        bg(new Runnable() {
            public void run() { BridgeData.historyGet(web, ui, bookKey); }
        });
    }

    @JavascriptInterface
    public void homeInfo() {
        bg(new Runnable() {
            public void run() { BridgeData.homeInfo(web, ui); }
        });
    }

    // ==================== 存储占用（★ v3.7.18） ====================
    //
    //  用户反馈「App 全部占用 500 多 M」，此前 App 里**没有任何地方能看到
    //  各部分的真实占用** —— 设置页只有一个「缓存上限」下拉，看不见现在用了多少。
    //  这里补两个接口：
    //    · storageInfo()    返回分区占用（B），前端换算成 MB 展示
    //    · clearAllCache()  清掉"可再生成"的部分（音频缓存 + 详情缓存 + 封面/WebView 缓存）
    //  注意**不清** js_plugins / python_sources / chaquopy / 数据库 ——
    //  那些是书源与用户数据，清了等于丢配置。

    @JavascriptInterface
    public void storageInfo() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    android.content.Context c = com.tingshu.app.core.App.ctx();

                    // 1) 音频缓存（离线缓存 + 预读落下来的）
                    long audio = 0;
                    try {
                        audio = com.tingshu.app.data.CacheRepo.totalSize();
                    } catch (Throwable ignored) {}
                    o.put("audioCache", audio);

                    // 2) 应用私有目录各分区
                    o.put("files", dirSize(c.getFilesDir()));
                    o.put("cache", dirSize(c.getCacheDir()));
                    try {
                        java.io.File[] ext = c.getExternalCacheDirs();
                        long e = 0;
                        if (ext != null) for (java.io.File f : ext) e += dirSize(f);
                        o.put("extCache", e);
                    } catch (Throwable ignored) { o.put("extCache", 0); }

                    // 3) 细分：书源插件 / Python 源 / Chaquopy 运行时
                    o.put("jsPlugins", dirSize(new java.io.File(c.getFilesDir(), "js_plugins")));
                    o.put("pySources", dirSize(new java.io.File(c.getFilesDir(), "python_sources")));
                    o.put("chaquopy", dirSize(new java.io.File(c.getFilesDir(), "chaquopy")));

                    // 4) 整包占用（含 lib / dex / assets，用户最直观的那个数）
                    long apk = 0;
                    try {
                        android.content.pm.PackageInfo pi = c.getPackageManager()
                                .getPackageInfo(c.getPackageName(), 0);
                        if (pi != null) apk = new java.io.File(pi.applicationInfo.sourceDir).length();
                    } catch (Throwable ignored) {}
                    o.put("apk", apk);

                    // 5) 上限值（前端对照展示"已用 x / 上限 y"）
                    o.put("limitMb", com.tingshu.app.data.Settings.cacheLimitMb());
                    o.put("ok", true);
                } catch (Throwable t) {
                    try { o.put("ok", false); } catch (Throwable ignored) {}
                }
                callback("onStorageInfo", o);
            }
        });
    }

    /** 清空"可再生成"的缓存（音频缓存 / 详情缓存 / 封面与 WebView 缓存） */
    @JavascriptInterface
    public void clearAllCache() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    android.content.Context c = com.tingshu.app.core.App.ctx();
                    long before = 0;
                    try {
                        before = com.tingshu.app.data.CacheRepo.totalSize()
                                + dirSize(c.getCacheDir());
                    } catch (Throwable ignored) {}

                    // 音频缓存（filesDir/cache_audio）
                    try { com.tingshu.app.data.CacheRepo.clearAll(c); } catch (Throwable ignored) {}
                    // 详情页缓存（Aggregator 的 detail 缓存）
                    try { com.tingshu.app.source.Aggregator.clearDetailCache(); } catch (Throwable ignored) {}
                    // 封面 + WebView 缓存（cacheDir）
                    try { deleteDir(c.getCacheDir()); } catch (Throwable ignored) {}
                    try { android.webkit.WebView w = web;
                          if (w != null) { w.clearCache(true); } } catch (Throwable ignored) {}

                    long after = 0;
                    try {
                        after = com.tingshu.app.data.CacheRepo.totalSize()
                                + dirSize(c.getCacheDir());
                    } catch (Throwable ignored) {}
                    o.put("freed", Math.max(0, before - after));
                    // 清完立刻回一份新的占用，前端不用再请求一次
                    o.put("ok", true);
                } catch (Throwable t) {
                    try { o.put("ok", false); } catch (Throwable ignored) {}
                }
                callback("onCacheCleared", o);
            }
        });
    }

    /** 递归统计目录占用（含子目录） */
    private static long dirSize(java.io.File d) {
        if (d == null || !d.exists()) return 0;
        if (d.isFile()) return d.length();
        long n = 0;
        java.io.File[] fs = d.listFiles();
        if (fs == null) return 0;
        for (java.io.File f : fs) n += dirSize(f);
        return n;
    }

    /** 递归删除目录内容（保留目录本身） */
    private static void deleteDir(java.io.File d) {
        if (d == null || !d.exists()) return;
        java.io.File[] fs = d.listFiles();
        if (fs != null) {
            for (java.io.File f : fs) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
    }

    // ==================== 设置 ====================

    /** 读全部设置给前端 */
    @JavascriptInterface
    public void getSettings() {
        JSONObject o = new JSONObject();
        try {
            com.tingshu.app.data.Settings s = null;   // 纯静态类，仅为对齐命名
            o.put("speed", com.tingshu.app.data.Settings.speed());
            o.put("ffSeconds", com.tingshu.app.data.Settings.ffSeconds());
            o.put("wifiPlayOnly", com.tingshu.app.data.Settings.wifiPlayOnly());
            o.put("playOnFull", com.tingshu.app.data.Settings.playOnFull());
            o.put("wifiCacheOnly", com.tingshu.app.data.Settings.wifiCacheOnly());
            o.put("autoCacheEnabled", com.tingshu.app.data.Settings.autoCacheEnabled());
            o.put("autoCacheCount", com.tingshu.app.data.Settings.autoCacheCount());
            o.put("cacheLimitMb", com.tingshu.app.data.Settings.cacheLimitMb());
            o.put("playMode", com.tingshu.app.data.Settings.playMode());
            // ★ v3.1：音量增益 + 其他声音打断策略
            o.put("gain", com.tingshu.app.data.Settings.gain());
            o.put("audioFocusMode", com.tingshu.app.data.Settings.audioFocusMode());
            // ★ v3.1：上次验证结果摘要（源管理页顶部展示）。
            //   v3.2 起自动验证开关已移除，但摘要仍由手动「批量验证」写入。
            o.put("lastVerifySummary", com.tingshu.app.data.Settings.lastVerifySummary());
        } catch (Throwable ignored) {}
        callback("onSettings", o);
    }

    /**
     * 写单个设置项。值统一用字符串传，按 key 决定类型。
     * 白名单制：只允许已知 key，避免前端乱写。
     */
    @JavascriptInterface
    public void setSetting(final String key, final String value) {
        bg(new Runnable() {
            public void run() {
                try {
                    com.tingshu.app.data.Settings.apply(key, value);
                } catch (Throwable ignored) {}
            }
        });
    }

    // ==================== ★ v2.8 悬浮球 ====================

    /**
     * 查询悬浮球当前状态，回推 onFloatBallState。
     * <pre>
     *   { enabled: 开关是否打开, canDraw: 系统是否已授权悬浮窗, supported: 系统是否支持 }
     * </pre>
     * 前端据此决定：开关打勾与否、要不要提示去授权。
     */
    @JavascriptInterface
    public void floatBallState() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    o.put("enabled", com.tingshu.app.data.Settings.floatBallEnabled());
                    o.put("canDraw", com.tingshu.app.player.FloatBallService.canDraw(activity));
                    // API 23 以下无需权限，视为支持
                    o.put("supported", android.os.Build.VERSION.SDK_INT >= 23);
                } catch (Throwable ignored) {}
                js("window.onFloatBallState && window.onFloatBallState(" + o.toString() + ")");
            }
        });
    }

    /**
     * 开关悬浮球。
     *
     * <p>打开时若还没授权，会拉起系统「显示在其他应用上层」授权页；
     * 用户返回后由 MainActivity.onResume → floatBallState() 复查并回推结果。
     */
    @JavascriptInterface
    public void setFloatBall(final boolean on) {
        bg(new Runnable() {
            public void run() {
                try {
                    com.tingshu.app.data.Settings.setFloatBallEnabled(on);
                    if (!on) {
                        com.tingshu.app.player.FloatBallService.hide(activity);
                    } else if (!com.tingshu.app.player.FloatBallService.canDraw(activity)) {
                        requestOverlayPermission();
                    }
                } catch (Throwable ignored) {}
                // 无论如何都回推一次最新状态，前端据此刷新开关
                floatBallState();
            }
        });
    }

    /** 拉起系统悬浮窗授权页（API 23+） */
    private void requestOverlayPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 23) return;
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + activity.getPackageName()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Throwable ignored) {}
    }

    // ==================== ★ v3.7.9 关于页：打开链接 / 检查更新 ====================

    /**
     * 用系统浏览器打开一个外部链接。
     *
     * <p>用于「关于 → GitHub 仓库 / 问题反馈」这类入口 —— 这些页面在
     * WebView 里打开体验很差（没有下载、没有登录态、还可能被源嗅探逻辑干扰），
     * 交给系统浏览器最稳妥。
     *
     * <p>只允许 http/https，防止被前端（或某个书源脚本）诱导打开 intent:// 之类的
     * 危险 scheme 去干别的事。
     */
    @JavascriptInterface
    public void openUrl(final String url) {
        if (url == null) return;
        final String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) return;
        ui.post(new Runnable() {
            public void run() {
                try {
                    android.content.Intent i = new android.content.Intent(
                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u));
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(i);
                } catch (Throwable t) {
                    js("window.onToast(" + JSONObject.quote("没有可打开链接的应用") + ")");
                }
            }
        });
    }

    /**
     * ★ v3.7.9：检查是否有新版本。
     *
     * <p>查的是 GitHub Releases 的 latest 接口（仓库已有发布流程，放这里最自然）：
     * <pre>
     *   https://api.github.com/repos/&lt;owner&gt;/&lt;repo&gt;/releases/latest
     * </pre>
     * 返回 JSON 里 {@code tag_name}（如 {@code v3.7.9}）与 {@code html_url}（发布页）。
     * 与本地 versionName 做版本号比较（三段数字逐位比），有新版才提示。
     *
     * <p>网络失败 / 仓库还没有 release 都**不能**当成错误弹给用户 ——
     * 没网是常态，安静地回一句"检查失败"即可，别在关于页糊一个红框。
     */
    @JavascriptInterface
    public void checkUpdate() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                String local = localVersionName();
                try {
                    o.put("local", local);
                } catch (Throwable ignored) {}
                try {
                    String body = httpGetText(UPDATE_API, 8000);
                    if (body == null || body.length() == 0) {
                        o.put("ok", false);
                        o.put("error", "网络请求失败");
                        callback("onUpdateResult", o);
                        return;
                    }
                    org.json.JSONObject j = new org.json.JSONObject(body);
                    String tag = j.optString("tag_name", "");
                    String page = j.optString("html_url", "");
                    if (tag.length() == 0) {
                        o.put("ok", false);
                        o.put("error", "没有找到发布版本");
                        callback("onUpdateResult", o);
                        return;
                    }
                    String latest = tag.startsWith("v") || tag.startsWith("V")
                            ? tag.substring(1) : tag;
                    o.put("ok", true);
                    o.put("latest", latest);
                    o.put("tag", tag);
                    o.put("url", page);
                    o.put("hasNew", compareVer(latest, local) > 0);
                    callback("onUpdateResult", o);
                } catch (Throwable t) {
                    try {
                        o.put("ok", false);
                        o.put("error", t.getClass().getSimpleName());
                    } catch (Throwable ignored) {}
                    callback("onUpdateResult", o);
                }
            }
        });
    }

    /** 版本更新检查地址（GitHub Releases latest） */
    private static final String UPDATE_API =
            "https://api.github.com/repos/fengxitingshu/fengxi-tingshu/releases/latest";

    /** 读本地安装包的 versionName */
    private String localVersionName() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0.0.0";
        }
    }

    /**
     * 极简 HTTP GET（只用于更新检查这种一次性小请求）。
     * 不引第三方库，超时短，失败返回 null。
     */
    private static String httpGetText(String url, int timeoutMs) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL u = new java.net.URL(url);
            conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "FengxiTingshu");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
        }
    }

    /**
     * 版本号比较：a &gt; b 返回正数，相等 0，a &lt; b 返回负数。
     * 只比前三段数字（3.7.9 这种），段数不齐按 0 补 —— 后缀（-beta）忽略。
     */
    private static int compareVer(String a, String b) {
        int[] va = verParts(a);
        int[] vb = verParts(b);
        for (int i = 0; i < 3; i++) {
            if (va[i] != vb[i]) return va[i] - vb[i];
        }
        return 0;
    }

    private static int[] verParts(String s) {
        int[] out = new int[]{0, 0, 0};
        if (s == null) return out;
        String t = s.trim();
        // 去掉 -beta / +build 这类后缀
        int dash = t.indexOf('-');
        if (dash >= 0) t = t.substring(0, dash);
        int plus = t.indexOf('+');
        if (plus >= 0) t = t.substring(0, plus);
        String[] seg = t.split("\\.");
        for (int i = 0; i < 3 && i < seg.length; i++) {
            try {
                out[i] = Integer.parseInt(seg[i].replaceAll("[^0-9]", ""));
            } catch (Throwable ignored) {}
        }
        return out;
    }

    // ==================== 缓存 ====================

    /** 缓存整本书 */
    @JavascriptInterface
    public void cacheBook(final String bookJson) {
        bg(new Runnable() {
            public void run() {
                final Book b = parseBook(bookJson);
                if (b == null) return;
                ui.post(new Runnable() {
                    public void run() {
                        js("window.onToast(" + JSONObject.quote("开始缓存《" + b.title + "》") + ")");
                    }
                });
                com.tingshu.app.core.CacheManager.cacheBook(b);
            }
        });
    }

    /**
     * ★ v2.8：缓存单集（章节列表每行的下载按钮）。
     *
     * @param bookJson 书籍 JSON
     * @param humanIdx 1 基集号（前端传 ch.index || i+1，与 CacheRepo 的键一致）
     */
    @JavascriptInterface
    public void cacheChapter(final String bookJson, final int humanIdx) {
        bg(new Runnable() {
            public void run() {
                final Book b = parseBook(bookJson);
                if (b == null || humanIdx <= 0) return;
                // 立刻回推一次「下载中」，让按钮图标即时变化，不必等下载线程排到。
                // （js() 内部已切主线程，这里不必再包一层 ui.post）
                JSONObject o = new JSONObject();
                try {
                    o.put("key", b.key());
                    o.put("index", humanIdx);
                    o.put("pending", true);
                } catch (Throwable ignored) {}
                js("window.onChapterCacheStatus(" + o.toString() + ")");

                com.tingshu.app.core.CacheManager.cacheChapter(b, humanIdx);
            }
        });
    }

    /**
     * ★ v2.8：批量查某本书所有集的缓存状态，回推 onChapterCacheStatusMap。
     *
     * <p>回推格式：{ key: "源:书名", map: { "1": 2, "2": 2, "3": 3 } }
     * 其中 map 的值是 CacheRepo 的 ST_* 常量（0 待定 / 1 下载中 / 2 完成 / 3 失败）。
     * 不在 map 里的集号 = 从未缓存，前端按「未下载」处理。
     *
     * <p>★ v2.9：章节行的图标语义已改为「下载」，所以这里查的是 download 表。
     */
    @JavascriptInterface
    public void cacheStatus(final String bookJson) {
        bg(new Runnable() {
            public void run() {
                final Book b = parseBook(bookJson);
                if (b == null) return;
                JSONObject o = new JSONObject();
                try {
                    o.put("key", b.key());
                    JSONObject map = new JSONObject();
                    java.util.Map<Integer, Integer> m =
                            com.tingshu.app.data.DownloadRepo.statusMap(b.key());
                    for (java.util.Map.Entry<Integer, Integer> e : m.entrySet()) {
                        map.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    o.put("map", map);
                } catch (Throwable ignored) {}
                js("window.onChapterCacheStatusMap(" + o.toString() + ")");
            }
        });
    }

    // ==================== ★ v2.9 下载（与缓存分家） ====================

    /**
     * 下载单集。
     *
     * @param humanIdx 1 基集号
     */
    @JavascriptInterface
    public void downloadChapter(final String bookJson, final int humanIdx) {
        bg(new Runnable() {
            public void run() {
                final Book b = parseBook(bookJson);
                if (b == null || humanIdx <= 0) return;
                // 立刻回推「下载中」，让按钮图标即时变化
                JSONObject o = new JSONObject();
                try {
                    o.put("key", b.key());
                    o.put("index", humanIdx);
                    o.put("pending", true);
                } catch (Throwable ignored) {}
                js("window.onChapterCacheStatus(" + o.toString() + ")");
                com.tingshu.app.core.DownloadManager.downloadChapter(b, humanIdx);
            }
        });
    }

    /**
     * 批量下载指定集（勾选多集）。
     *
     * @param idxJson 1 基集号数组的 JSON，如 "[1,3,5]"
     */
    @JavascriptInterface
    public void downloadChapters(final String bookJson, final String idxJson) {
        bg(new Runnable() {
            public void run() {
                final Book b = parseBook(bookJson);
                if (b == null) return;
                int[] idxs = parseIntArray(idxJson);
                if (idxs == null || idxs.length == 0) return;
                for (int i : idxs) {
                    JSONObject o = new JSONObject();
                    try {
                        o.put("key", b.key());
                        o.put("index", i);
                        o.put("pending", true);
                    } catch (Throwable ignored) {}
                    js("window.onChapterCacheStatus(" + o.toString() + ")");
                }
                com.tingshu.app.core.DownloadManager.downloadChapters(b, idxs);
            }
        });
    }

    /** 下载整本 */
    @JavascriptInterface
    public void downloadBook(final String bookJson) {
        bg(new Runnable() {
            public void run() {
                final Book b = parseBook(bookJson);
                if (b == null) return;
                ui.post(new Runnable() {
                    public void run() {
                        js("window.onToast(" + JSONObject.quote("开始下载《" + b.title + "》") + ")");
                    }
                });
                com.tingshu.app.core.DownloadManager.downloadBook(b);
            }
        });
    }

    /** 下载清单（下载管理页）：按书聚合 + 总占用 */
    @JavascriptInterface
    public void downloadList() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    org.json.JSONArray arr = new org.json.JSONArray();
                    java.util.List<com.tingshu.app.data.Models.CacheItem> books =
                            com.tingshu.app.data.DownloadRepo.books();
                    for (com.tingshu.app.data.Models.CacheItem it : books) {
                        JSONObject e = new JSONObject();
                        e.put("key", it.key);
                        e.put("title", titleOfKey(it.key));
                        e.put("count", it.chapterIdx);   // books() 里复用为集数
                        e.put("size", it.size);
                        e.put("sizeText", com.tingshu.app.data.DownloadRepo.humanSize(it.size));
                        e.put("updatedAt", it.updatedAt);
                        arr.put(e);
                    }
                    long total = com.tingshu.app.data.DownloadRepo.totalSize();
                    o.put("books", arr);
                    o.put("totalSize", total);
                    o.put("totalText", com.tingshu.app.data.DownloadRepo.humanSize(total));
                    o.put("totalCount", com.tingshu.app.data.DownloadRepo.totalCount());
                    o.put("dirName", com.tingshu.app.data.Settings.downloadTreeName());
                    o.put("hasDir", com.tingshu.app.data.Settings.hasDownloadTree());
                } catch (Throwable ignored) {}
                js("window.onDownloadList(" + o.toString() + ")");
            }
        });
    }

    /** 删除某本书的下载（只删 App 内那份；外部文件属用户自己的，不动） */
    @JavascriptInterface
    public void downloadDelete(final String key) {
        bg(new Runnable() {
            public void run() {
                try {
                    com.tingshu.app.data.DownloadRepo.deleteBook(activity, key);
                } catch (Throwable ignored) {}
                downloadList();
            }
        });
    }

    /** 清空全部下载 */
    @JavascriptInterface
    public void downloadClearAll() {
        bg(new Runnable() {
            public void run() {
                try {
                    com.tingshu.app.data.DownloadRepo.clearAll(activity);
                } catch (Throwable ignored) {}
                downloadList();
            }
        });
    }

    /** 打开系统文件夹选择器，挑下载存放目录 */
    @JavascriptInterface
    public void pickDownloadDir() {
        ui.post(new Runnable() {
            public void run() {
                try {
                    if (activity instanceof com.tingshu.app.MainActivity) {
                        ((com.tingshu.app.MainActivity) activity).pickDownloadDir();
                    } else {
                        js("window.onDownloadDir({ok:false,cancel:false,error:'fail'})");
                    }
                } catch (Throwable t) {
                    js("window.onDownloadDir({ok:false,cancel:false,error:'fail'})");
                }
            }
        });
    }

    /** 清除自定义下载目录，回到「仅 App 内」 */
    @JavascriptInterface
    public void clearDownloadDir() {
        bg(new Runnable() {
            public void run() {
                try {
                    com.tingshu.app.data.Settings.setDownloadTreeUri("");
                    com.tingshu.app.data.Settings.setDownloadTreeName("");
                } catch (Throwable ignored) {}
                JSONObject o = new JSONObject();
                try {
                    o.put("ok", true);
                    o.put("uri", "");
                    o.put("name", "");
                } catch (Throwable ignored) {}
                js("window.onDownloadDir(" + o.toString() + ")");
            }
        });
    }

    /** 当前下载目录状态（设置页初始化用） */
    @JavascriptInterface
    public void downloadDirState() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    o.put("ok", true);
                    o.put("uri", com.tingshu.app.data.Settings.downloadTreeUri());
                    o.put("name", com.tingshu.app.data.Settings.downloadTreeName());
                    o.put("hasDir", com.tingshu.app.data.Settings.hasDownloadTree());
                } catch (Throwable ignored) {}
                js("window.onDownloadDir(" + o.toString() + ")");
            }
        });
    }

    /** 从 "源:书名" 里取出书名（下载列表展示用） */
    private static String titleOfKey(String key) {
        if (key == null) return "";
        int i = key.indexOf(':');
        return i >= 0 && i + 1 < key.length() ? key.substring(i + 1) : key;
    }

    /** 解析 "[1,3,5]" → int[]；失败返回 null */
    private static int[] parseIntArray(String json) {
        if (json == null) return null;
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            int n = a.length();
            if (n <= 0) return null;
            int[] out = new int[n];
            for (int i = 0; i < n; i++) out[i] = a.optInt(i, 0);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 缓存管理页：列出已缓存的书 */
    @JavascriptInterface
    public void cacheList() {
        bg(new Runnable() {
            public void run() {
                JSONArray arr = new JSONArray();
                try {
                    List<com.tingshu.app.data.Models.CacheItem> items =
                            com.tingshu.app.data.CacheRepo.books();
                    for (com.tingshu.app.data.Models.CacheItem it : items) {
                        JSONObject o = new JSONObject();
                        o.put("key", it.key);
                        o.put("title", titleOfBook(it.key));
                        long mb = it.size / 1024L / 1024L;
                        o.put("info", it.chapterIdx + " 集 · " + (mb < 1 ? "<1" : mb) + " MB");
                        arr.put(o);
                    }
                } catch (Throwable ignored) {}
                try {
                    callback("onCacheList", arr);
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 缓存管理页：从收藏/历史里找书名 */
    private String titleOfBook(String key) {
        try {
            com.tingshu.app.data.Models.Book b =
                    com.tingshu.app.data.FavRepo.findBook(key);
            if (b != null && b.title != null && b.title.length() > 0) return b.title;
        } catch (Throwable ignored) {}
        try {
            com.tingshu.app.data.Models.History h =
                    com.tingshu.app.data.HistoryRepo.get(key);
            if (h != null && h.book != null && h.book.title != null) return h.book.title;
        } catch (Throwable ignored) {}
        int i = key == null ? -1 : key.indexOf(':');
        return i >= 0 ? key.substring(i + 1) : String.valueOf(key);
    }

    @JavascriptInterface
    public void cacheDelete(final String key) {
        bg(new Runnable() {
            public void run() {
                com.tingshu.app.data.CacheRepo.deleteBook(
                        com.tingshu.app.core.App.ctx(), key);
                ui.post(new Runnable() {
                    public void run() { js("window.onCacheListReload&&window.onCacheListReload()"); }
                });
                cacheList();
            }
        });
    }

    @JavascriptInterface
    public void cacheClear() {
        bg(new Runnable() {
            public void run() {
                com.tingshu.app.data.CacheRepo.clearAll(
                        com.tingshu.app.core.App.ctx());
                cacheList();
            }
        });
    }

    // ==================== 设备信息 ====================

    @JavascriptInterface
    public void deviceInfo() {
        JSONObject o = new JSONObject();
        try {
            o.put("sdk", android.os.Build.VERSION.SDK_INT);
            // 倍速是 API 23+，低版本前端隐藏入口
            o.put("canSpeed", android.os.Build.VERSION.SDK_INT >= 23);
            o.put("model", android.os.Build.MODEL);
            // 版本号直接从安装包读，避免和 AndroidManifest 里写的对不上
            String vn = "2.1";
            try {
                vn = activity == null ? vn
                        : activity.getPackageManager()
                            .getPackageInfo(activity.getPackageName(), 0).versionName;
            } catch (Throwable ignored) {}
            o.put("version", vn);
            o.put("theme", com.tingshu.app.data.Settings.theme());
            boolean v6 = com.tingshu.app.core.Net.ipv6Available();
            o.put("ipv6", v6);
            o.put("netText", v6 ? "IPv4 / IPv6 双栈" : "IPv4（当前网络无 IPv6）");
        } catch (Throwable ignored) {}
        callback("onDeviceInfo", o);
    }

    // ==================== 剪贴板 ====================

    /**
     * 复制一段文本到系统剪贴板。
     *
     * <p>用于「关于 → 加群」这类需要用户拿着文本去别处粘贴的场景。
     * 全用平台 API（android.content.ClipboardManager，API 11+），
     * 不引入 androidx，与工程「零第三方依赖」的基调一致。
     * 必须在主线程访问剪贴板，所以这里 post 到 ui。
     */
    @JavascriptInterface
    public void copyText(final String text) {
        if (text == null || text.length() == 0) return;
        ui.post(new Runnable() {
            public void run() {
                try {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm == null) return;
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("枫兮听书", text));
                } catch (Throwable ignored) {}
            }
        });
    }

    // ==================== 分享 ====================

    /** 分享一首书（纯文本，不含链接，避免依赖外部可访问地址） */
    @JavascriptInterface
    public void shareBook(final String bookJson) {
        bg(new Runnable() {
            public void run() {
                final Book b = parseBook(bookJson);
                if (b == null) return;
                ui.post(new Runnable() {
                    public void run() {
                        try {
                            StringBuilder sb = new StringBuilder();
                            sb.append("【").append(b.title).append("】");
                            if (b.artist != null && b.artist.length() > 0)
                                sb.append("  演播：").append(b.artist);
                            if (b.author != null && b.author.length() > 0)
                                sb.append("  作者：").append(b.author);
                            sb.append('\n').append("来源：").append(sourceNameOf(b.source));
                            sb.append('\n').append('\n').append("—— 来自「枫兮听书」");

                            android.content.Intent it = new android.content.Intent(
                                    android.content.Intent.ACTION_SEND);
                            it.setType("text/plain");
                            it.putExtra(android.content.Intent.EXTRA_SUBJECT, b.title);
                            it.putExtra(android.content.Intent.EXTRA_TEXT, sb.toString());
                            startChooser(it);
                        } catch (Throwable t) {
                            js("window.onToast(" + JSONObject.quote("分享失败") + ")");
                        }
                    }
                });
            }
        });
    }

    /** 分享一句自定义文本 */
    @JavascriptInterface
    public void shareText(final String text) {
        ui.post(new Runnable() {
            public void run() {
                try {
                    android.content.Intent it = new android.content.Intent(
                            android.content.Intent.ACTION_SEND);
                    it.setType("text/plain");
                    it.putExtra(android.content.Intent.EXTRA_TEXT, text);
                    startChooser(it);
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 统一走这里，避免 Activity 已销毁时 startActivity 崩溃 */
    private void startChooser(android.content.Intent it) {
        try {
            if (activity == null || activity.isFinishing()) return;
            if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return;
            activity.startActivity(android.content.Intent.createChooser(it, "分享到"));
        } catch (Throwable t) {
            js("window.onToast(" + JSONObject.quote("没有可用的分享应用") + ")");
        }
    }

    /** 切主题（system / light / dark） */
    @JavascriptInterface
    public void setTheme(final String mode) {
        bg(new Runnable() {
            public void run() {
                com.tingshu.app.data.Settings.setTheme(mode);
            }
        });
        ui.post(new Runnable() {
            public void run() {
                boolean dark = "dark".equals(mode);
                if ("system".equals(mode)) {
                    // activity 可能为 null（宿主不是 Activity 的场景），此时退回系统 UiMode
                    if (activity != null) {
                        try {
                            int m = activity.getResources().getConfiguration().uiMode
                                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                            dark = (m == android.content.res.Configuration.UI_MODE_NIGHT_YES);
                        } catch (Throwable ignored) {}
                    } else {
                        try {
                            android.content.Context app = com.tingshu.app.core.App.ctx();
                            if (app != null) {
                                int m = app.getResources().getConfiguration().uiMode
                                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                                dark = (m == android.content.res.Configuration.UI_MODE_NIGHT_YES);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                js("(function(){document.documentElement.className='" +
                        (dark ? "dark" : "") + "';})()");
                // WebView 底色跟着切，避免滚动/加载瞬间闪出异色
                try { if (web != null) web.setBackgroundColor(dark ? 0xFF000000 : 0xFFFFFFFF); }
                catch (Throwable ignored) {}
            }
        });
    }

    private String sourceNameOf(String id) {
        try {
            com.tingshu.app.source.Source s = com.tingshu.app.source.SourceRegistry.byId(id);
            if (s != null) return s.name();
        } catch (Throwable ignored) {}
        return id == null ? "" : id;
    }

    // ==================== 崩溃日志 ====================

    @JavascriptInterface
    public void crashLog() {
        bg(new Runnable() {
            public void run() {
                String txt = "";
                try {
                    txt = com.tingshu.app.core.Crash.read(
                            com.tingshu.app.core.App.ctx());
                } catch (Throwable ignored) {}
                JSONObject o = new JSONObject();
                try {
                    o.put("log", txt);
                    o.put("empty", txt.length() == 0);
                } catch (Throwable ignored) {}
                callback("onCrashLog", o);
            }
        });
    }

    @JavascriptInterface
    public void crashClear() {
        bg(new Runnable() {
            public void run() {
                try {
                    com.tingshu.app.core.Crash.clear(com.tingshu.app.core.App.ctx());
                } catch (Throwable ignored) {}
                crashLog();
            }
        });
    }

    // ==================== 源加载诊断（★ v3.7.15-fix） ====================

    /**
     * 源加载自检：JS 插件包是否在库里、目录/manifest 是否还在、
     * 实际注册出了几个源、重试加载报什么错。
     *
     * <p>用途：用户反馈「之前的 JS 源似乎没有了」时，让他打开这个页面截图，
     * 比让他连电脑抓 logcat 现实得多。
     */
    @JavascriptInterface
    public void sourceDiag() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    o = com.tingshu.app.source.SourceRegistry.diagJson();
                } catch (Throwable t) {
                    try {
                        o.put("error", t.getClass().getSimpleName() + ": "
                                + (t.getMessage() == null ? "" : t.getMessage()));
                    } catch (Throwable ignored) {}
                }
                try {
                    o.put("runtimeLog", com.tingshu.app.core.DiagLog.dump());
                } catch (Throwable ignored) {}
                callback("onSourceDiag", o);
            }
        });
    }

    /** 清空运行期诊断缓冲 */
    @JavascriptInterface
    public void sourceDiagClear() {
        bg(new Runnable() {
            public void run() {
                try {
                    com.tingshu.app.core.DiagLog.clear();
                } catch (Throwable ignored) {}
                sourceDiag();
            }
        });
    }
}
