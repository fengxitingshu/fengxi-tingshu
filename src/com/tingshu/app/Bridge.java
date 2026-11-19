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

import java.util.ArrayList;
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

    /** ★ v4.2.0：上次自动弹存储权限的时间戳（3 秒节流，防连环弹窗） */
    private volatile long lastAutoPermAt = 0L;
    /** ★ v4.2.0：115 扫码轮询取消标志（关弹窗时置位） */
    private volatile boolean panQrStopped = false;

    /** ★ v4.21.48 手动转存：等待「挑目录」结果期间记住用户选了哪几集 */
    private String dlExportKey = null;
    private int[] dlExportIdxs = null;
    private String dlExportTitle = "";

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

    // ==================== 小组件诊断日志（★ v3.8.4） ====================

    /** 读取小组件诊断日志，回传 window.onWgDiagLog({log, empty}) */
    @JavascriptInterface
    public void widgetDiagLog() {
        try {
            String log = com.tingshu.app.core.DiagLog.dump();
            JSONObject o = new JSONObject();
            o.put("log", log == null ? "" : log);
            o.put("empty", com.tingshu.app.core.DiagLog.size() == 0);
            callback("onWgDiagLog", o);
        } catch (Throwable t) {
            try {
                JSONObject o = new JSONObject();
                o.put("log", "");
                o.put("empty", true);
                callback("onWgDiagLog", o);
            } catch (Throwable ignored) {}
        }
    }

    /** 清空小组件诊断日志 */
    @JavascriptInterface
    public void widgetDiagClear() {
        com.tingshu.app.core.DiagLog.clear();
        try {
            JSONObject o = new JSONObject();
            o.put("log", "");
            o.put("empty", true);
            callback("onWgDiagLog", o);
        } catch (Throwable ignored) {}
    }

    // ==================== 崩溃日志取证（★ v4.20.1） ====================

    /**
     * 读取上次会话遗留的崩溃日志，回传 window.onCrashLogs({name, text})。
     *
     * <p>崩溃处理器把完整堆栈写进 files/crash/crash-*.log，但内部存储用户看不到。
     * 这里以 files/crash.seen 的修改时间为"已读水位"：只上报水位之后新产生的
     * 崩溃日志，读完把水位推到现在 —— 保证每次崩溃只弹一次，不骚扰。
     * 首次安装诊断版时水位文件不存在，会立刻上报现存的所有旧崩溃（正是要的）。
     */
    @JavascriptInterface
    public void crashCheck() {
        try {
            java.io.File dir = new java.io.File(activity.getFilesDir(), "crash");
            java.io.File seen = new java.io.File(activity.getFilesDir(), "crash.seen");
            long seenT = seen.exists() ? seen.lastModified() : 0L;
            java.io.File[] all = dir.listFiles();
            java.io.File newest = null;
            if (all != null) {
                for (java.io.File f : all) {
                    if (f.isFile() && f.lastModified() > seenT
                            && (newest == null || f.lastModified() > newest.lastModified())) {
                        newest = f;
                    }
                }
            }
            if (newest != null && newest.length() > 0) {
                java.io.FileInputStream in = new java.io.FileInputStream(newest);
                byte[] buf = new byte[(int) Math.min(newest.length(), 20000)];
                int n = in.read(buf);
                in.close();
                JSONObject o = new JSONObject();
                o.put("name", newest.getName());
                o.put("text", n > 0 ? new String(buf, 0, n, "UTF-8") : "(空日志)");
                callback("onCrashLogs", o);
            }
            if (!seen.exists()) seen.createNewFile();
            seen.setLastModified(System.currentTimeMillis());
        } catch (Throwable ignored) {}
    }

    /**
     * ★ v4.20.3：诊断日志（PlayerLog：取链/转码/播放全过程），
     * 回传 window.onDiagLog({log})。设置页入口，反馈 WMA 等播放问题时用。
     */
    @JavascriptInterface
    public void diagLog() {
        try {
            JSONObject o = new JSONObject();
            o.put("log", com.tingshu.app.core.PlayerLog.dump());
            callback("onDiagLog", o);
        } catch (Throwable ignored) {}
    }

    // ==================== 功能诊断（★ v4.21.20） ====================

    /**
     * 功能诊断：按前端勾选的功能块逐块体检，结果流式回传。
     *
     * <p>ids 形如 "net,panlogin,pansave"。每完成一块回调
     * {@code onFdiagModule({id, ok, ms, lines:[…]})}，全部完成后回调
     * {@code onFdiagDone()}。行首 ✓ / ✗ 决定该块整体 ok —— 单块失败
     * 不影响其它块继续跑。
     */
    @JavascriptInterface
    public void featDiag(final String ids) {
        bg(new Runnable() {
            public void run() {
                String[] list = (ids == null || ids.trim().length() == 0)
                        ? new String[0] : ids.split(",");
                for (String raw : list) {
                    final String id = raw.trim();
                    if (id.length() == 0) continue;
                    JSONObject o = new JSONObject();
                    try {
                        o.put("id", id);
                        long t0 = android.os.SystemClock.elapsedRealtime();
                        java.util.List<String> lines = runFeatDiag(id);
                        long ms = android.os.SystemClock.elapsedRealtime() - t0;
                        JSONArray arr = new JSONArray();
                        boolean fail = false;
                        for (String l : lines) {
                            arr.put(l == null ? "" : l);
                            if (l != null && l.startsWith("✗")) fail = true;
                        }
                        o.put("lines", arr);
                        o.put("ok", !fail);
                        o.put("ms", ms);
                    } catch (Throwable t) {
                        try {
                            o.put("ok", false);
                            o.put("ms", 0);
                            JSONArray arr = new JSONArray();
                            arr.put("✗ 诊断执行异常：" + t.getClass().getSimpleName()
                                    + (t.getMessage() == null ? "" : (": " + t.getMessage())));
                            o.put("lines", arr);
                        } catch (Throwable ignored) {}
                    }
                    callback("onFdiagModule", o);
                }
                callback("onFdiagDone", new JSONObject());
            }
        });
    }

    /** 执行单个功能块的自检，返回逐行输出 */
    private java.util.List<String> runFeatDiag(String id) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if ("net".equals(id)) {
            out.add("· 请求 https://pan.baidu.com …");
            long t0 = System.currentTimeMillis();
            com.tingshu.app.core.Net.Resp r = null;
            try {
                r = com.tingshu.app.core.Net.request(
                        "https://pan.baidu.com/", null, "https://pan.baidu.com/", null, false);
            } catch (Throwable ignored) {}
            long ms = System.currentTimeMillis() - t0;
            if (r == null) out.add("✗ 无响应（网络不可用？）");
            else out.add((r.code >= 200 && r.code < 500 ? "✓" : "✗")
                    + " HTTP " + r.code + "，耗时 " + ms + "ms");
            out.add("· 请求 https://www.baidu.com …");
            t0 = System.currentTimeMillis();
            com.tingshu.app.core.Net.Resp r2 = null;
            try {
                r2 = com.tingshu.app.core.Net.request(
                        "https://www.baidu.com/", null, null, null, false);
            } catch (Throwable ignored) {}
            ms = System.currentTimeMillis() - t0;
            if (r2 == null) out.add("✗ 无响应");
            else out.add((r2.code >= 200 && r2.code < 500 ? "✓" : "✗")
                    + " HTTP " + r2.code + "，耗时 " + ms + "ms");
            out.add("· IPv6：" + (com.tingshu.app.core.Net.ipv6Available()
                    ? "可用" : "不可用（一般不影响使用）"));
        } else if ("engine".equals(id)) {
            JSONObject d = com.tingshu.app.source.SourceRegistry.diagJson();
            int plugins = d.optInt("pluginCount", 0);
            int live = d.optInt("liveSourceCount", 0);
            int total = d.optInt("totalSources", 0);
            out.add("· JS 插件包：" + plugins + " 个");
            out.add("· JS 源存活：" + live + " 个");
            out.add("· 当前源总数：" + total + " 个");
            if (plugins == 0) {
                out.add("✗ 没有已安装的 JS 插件包——去 书源管理→导入 重新装一次");
            } else if (live == 0) {
                out.add("✗ 插件包在但源没加载出来——详情看「关于→源加载诊断」");
            } else {
                out.add("✓ 书源引擎正常");
            }
        } else if ("panlogin".equals(id)) {
            out.addAll(com.tingshu.app.pan.PanResolver.diagPanLogin());
        } else if ("pansave".equals(id)) {
            out.addAll(com.tingshu.app.pan.PanResolver.diagBaiduSave());
        } else if ("player".equals(id)) {
            String log = com.tingshu.app.core.PlayerLog.dump().trim();
            if (log.length() == 0) {
                out.add("· 暂无播放记录——播一集再回来看链路日志（取链/转码/播放全过程）");
            } else {
                String[] all = log.split("\n");
                out.add("· 本次启动共 " + all.length + " 条播放链路记录，最近 10 条：");
                int from = Math.max(0, all.length - 10);
                boolean bad = false;
                for (int i = from; i < all.length; i++) {
                    out.add("    " + all[i]);
                    if (all[i].contains("失败") || all[i].contains("ERROR")) bad = true;
                }
                out.add(bad ? "⚠ 最近记录里有失败——把这一块截图发出来即可定位"
                            : "✓ 最近播放链路没有失败记录");
            }
        } else if ("storage".equals(id)) {
            try {
                java.io.File cache = activity.getCacheDir();
                out.add("· 内部缓存目录：" + cache.getAbsolutePath());
                String[] top = cache.list();
                out.add("· 缓存顶层条目：" + (top == null ? 0 : top.length) + " 个");
                long free = cache.getUsableSpace();
                out.add("· 可用空间：" + (free / 1024 / 1024) + " MB");
                java.io.File probe = new java.io.File(cache, "fdiag.tmp");
                java.io.FileWriter w = new java.io.FileWriter(probe);
                w.write("ok");
                w.close();
                out.add(probe.delete() ? "✓ 目录可写（写入+删除测试通过）"
                                       : "⚠ 可写入但清理测试文件失败");
            } catch (Throwable t) {
                out.add("✗ 存储检测失败：" + t);
            }
        } else {
            out.add("✗ 未知功能块：" + id);
        }
        return out;
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

    /** ★ v4.18.0 功能锁统一拒绝：给前端弹一条 denyMsg 提示 */
    private void denyFeature(String feature) {
        js("window.toast('" + com.tingshu.app.core.FeatureGate.denyMsg(feature) + "')");
    }

    /** ★ v4.18.0 网盘听书功能锁判定（false = 已拒绝并提示，调用方直接 return） */
    private boolean panLocked() {
        if (com.tingshu.app.core.FeatureGate.allow(
                com.tingshu.app.core.FeatureGate.PAN)) return false;
        denyFeature(com.tingshu.app.core.FeatureGate.PAN);
        return true;
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
        Aggregator.lightPool().execute(new Runnable() {   // ★ v3.9.37 本地轻任务
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
                        // ★ v4.21.33：这里原来硬编码 false。方案 B 下服务里的暗核心
                        //   可能正被界面接管且正在出声，网页据此把按钮画成「▶ 播放」，
                        //   用户点一下反而把后台播放暂停了（表现为「要点两次才播」）。
                        //   现在回传真实播放态，前端据此同步图标。
                        boolean realPlaying = false;
                        try { realPlaying = player.isPlaying(); } catch (Throwable ignored) {}
                        o.put("playing", realPlaying);
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
                Throwable chainErr = null;
                try {
                    audio = Aggregator.audioWithFallback(b, chapterIdx, null, out, outHdr);
                } catch (Throwable t) {
                    chainErr = t;
                }
                final String url = audio;
                if (url == null) {
                    // ★ v4.9.4：把取链失败的真实原因带给前端（之前回空 JSON，
                    //   用户只看到「播放失败」，Cookie 失效/直链未返回全被吞）
                    JSONObject e = new JSONObject();
                    String why = Aggregator.lastFailReason();
                    if (why.length() == 0 && chainErr != null) {
                        why = String.valueOf(chainErr.getMessage());
                    }
                    try {
                        e.put("error", why.length() > 0
                                ? why : "所有音源均未取到直链");
                    } catch (Throwable ignored) {}
                    callback("onPlayError", e);
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

    /**
     * ★ v4.21.34：同步返回播放核心的真实状态。
     *
     * <p>用途：前端点播放/暂停按钮的瞬间直接拿真值，不再依赖缓存的
     * App.playing —— 方案 B 下网页状态与服务暗核心可能短暂不一致，
     * 拿缓存状态决定 pause/play 会出现「第一下点了没反应 / 误暂停」。
     * JavascriptInterface 方法本就跑在独立的 JavaBridge 线程，同步返回无阻塞。
     */
    @JavascriptInterface
    public boolean isPlayingNow() {
        try { return playerIsPlaying(); } catch (Throwable t) { return false; }
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

    // ==================== ★ v3.8.1 跳过片头 ====================

    /**
     * 设置「起播自动跳过」的秒数。0 = 关闭。
     *
     * <p>只对**没有续播进度**的集生效：听到一半的集仍然从原位置续上，
     * 不会每次续听都被再推一次片头。播放页「更多 → 跳过片头」调这个。
     */
    @JavascriptInterface
    public void setSkipIntro(final int sec) {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    String bk = com.tingshu.app.player.PlayerCore.currentBookKey();
                    com.tingshu.app.data.Settings.setPerBookSkip("ski_", bk, sec);
                    o.put("ok", true);
                    o.put("sec", com.tingshu.app.data.Settings.skipIntroFor(bk));
                } catch (Throwable t) {
                    try { o.put("ok", false); } catch (Throwable ignored) {}
                }
                callback("onSkipIntro", o);
            }
        });
    }

    /** 读当前跳过片头的秒数（打开「更多」菜单时显示用） */
    @JavascriptInterface
    public void getSkipIntro() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    o.put("ok", true);
                    o.put("sec", com.tingshu.app.data.Settings.skipIntroFor(
                            com.tingshu.app.player.PlayerCore.currentBookKey()));
                } catch (Throwable ignored) {}
                callback("onSkipIntro", o);
            }
        });
    }

    // ---------- ★ v3.8.2 跳过片尾 ----------

    /**
     * 设置「播放到结尾前自动跳过」的秒数。0 = 关闭。
     *
     * <p>与片头的区别：片头按「有没有续播进度」判断，片尾按**播放位置**
     * 判断 —— 当前进度落在结尾 N 秒内就生效（含用户手动拖动过去）。
     * 播放页「更多 → 跳过片尾」调这个。
     */
    @JavascriptInterface
    public void setSkipOutro(final int sec) {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    String bk = com.tingshu.app.player.PlayerCore.currentBookKey();
                    com.tingshu.app.data.Settings.setPerBookSkip("sko_", bk, sec);
                    o.put("ok", true);
                    o.put("sec", com.tingshu.app.data.Settings.skipOutroFor(bk));
                } catch (Throwable t) {
                    try { o.put("ok", false); } catch (Throwable ignored) {}
                }
                callback("onSkipOutro", o);
            }
        });
    }

    /** 读当前跳过片尾的秒数（打开「更多」菜单时显示用） */
    @JavascriptInterface
    public void getSkipOutro() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    o.put("ok", true);
                    o.put("sec", com.tingshu.app.data.Settings.skipOutroFor(
                            com.tingshu.app.player.PlayerCore.currentBookKey()));
                } catch (Throwable ignored) {}
                callback("onSkipOutro", o);
            }
        });
    }

    @JavascriptInterface
    public void stop() {
        ui.post(new Runnable() {
            public void run() { player.stop(); }
        });
    }

    // ==================== ★ v3.9.0 定时关闭（含可选时段） ====================

    /**
     * 设置定时关闭。
     *
     * @param min       倒计时分钟数，&lt;=0 关闭
     * @param winStart  时段开始（一天之内的分钟数 0~1439），-1 不设时段
     * @param winEnd    时段结束（分钟数），-1 不设时段；start &gt; end 为跨午夜
     */
    @JavascriptInterface
    public void setSleep(final int min, final int winStart, final int winEnd) {
        ui.post(new Runnable() {
            public void run() {
                com.tingshu.app.player.SleepTimer.set(min, winStart, winEnd);
            }
        });
    }

    /**
     * ★ v3.9.7：按集停止。n &gt; 0 = 播完 n 集后暂停（1 = 播完本集）；
     * n &lt;= 0 = 取消按集停止。与分钟倒计时相互独立。
     */
    @JavascriptInterface
    public void setSleepEpisodes(final int n) {
        ui.post(new Runnable() {
            public void run() {
                com.tingshu.app.player.SleepTimer.setEpisodes(n);
            }
        });
    }

    /** 读取当前定时状态（打开播放页时同步一次） */
    @JavascriptInterface
    public void getSleep() {
        ui.post(new Runnable() {
            public void run() { pushSleep(); }
        });
    }

    /** 把 SleepTimer 当前状态推给前端（本 Bridge 实例持有的 WebView） */
    public void pushSleep() {
        js("window.onSleep(" + com.tingshu.app.player.SleepTimer.stateJson().toString() + ")");
    }

    @JavascriptInterface
    public void toastMsg(final String msg) {
        js("window.onToast(" + JSONObject.quote(msg) + ")");
    }

    // ==================== 源信息 ====================

    /** 返回所有源（含启停状态），源管理页用 */
    @JavascriptInterface
    public void listSources() {
        // ★ v3.9.37：改走 lightPool —— 这是纯内存操作（毫秒级），之前挤在
        //   singlePool 里被慢网络任务排队，冷启动首页/源管理几十秒出不来。
        Aggregator.lightPool().execute(new Runnable() {
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
                // ★ v4.18.0 功能锁：jar 插件锁关闭 → 拒绝导入
                if (!com.tingshu.app.core.FeatureGate.allow(
                        com.tingshu.app.core.FeatureGate.PLUGIN)) {
                    denyFeature(com.tingshu.app.core.FeatureGate.PLUGIN);
                    return;
                }
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
                // ★ v4.18.0 功能锁：js/py 自定义源锁关闭 → 拒绝导入
                if (!com.tingshu.app.core.FeatureGate.allow(
                        com.tingshu.app.core.FeatureGate.CUSTOM_SRC)) {
                    denyFeature(com.tingshu.app.core.FeatureGate.CUSTOM_SRC);
                    return;
                }
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
                // ★ v4.18.0 功能锁：js/py 自定义源锁关闭 → 拒绝导入
                if (!com.tingshu.app.core.FeatureGate.allow(
                        com.tingshu.app.core.FeatureGate.CUSTOM_SRC)) {
                    denyFeature(com.tingshu.app.core.FeatureGate.CUSTOM_SRC);
                    return;
                }
                JSONObject r = BridgeSource.importPySources(path, fileName);
                callback("onSourceImported", r);
                callback("onSourceList", BridgeSource.allSourcesJson());
                callback("onCustomList", BridgeSource.customListJson());
            }
        });
    }

    /** jar 插件列表 */
    @JavascriptInterface
    public void listPlugins() {
        Aggregator.lightPool().execute(new Runnable() {   // ★ v3.9.37 本地轻任务
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
     * ★ v4.21.39 输入网址 → 自动生成书源。
     *
     * <p>后台跑 {@link com.tingshu.app.source.gen.SourceProbe} 探测
     * （进度走 {@code onGenProgress}），成功即导入（origin=gen）并立刻做一次
     * 真实搜索测试，结果走 {@code onGenDone}（含 ok/id/name/test/diag）。
     */
    @JavascriptInterface
    public void autoGenSource(final String url) {
        Aggregator.singlePool().execute(new Runnable() {
            public void run() {
                com.tingshu.app.source.gen.SourceProbe.Result r =
                        com.tingshu.app.source.gen.SourceProbe.probe(url,
                                new com.tingshu.app.source.gen.SourceProbe.Cb() {
                                    public void onProgress(String stage, int pct, String msg) {
                                        JSONObject p = new JSONObject();
                                        try {
                                            p.put("stage", stage);
                                            p.put("pct", pct);
                                            p.put("msg", msg);
                                        } catch (Throwable ignored) {}
                                        callback("onGenProgress", p);
                                    }
                                });
                JSONObject out = new JSONObject();
                try {
                    out.put("ok", r.ok);
                    JSONArray dg = new JSONArray();
                    for (String d : r.diag) dg.put(d);
                    out.put("diag", dg);
                    if (r.ok) {
                        out.put("id", r.id);
                        out.put("name", r.name);
                        JSONObject imp = BridgeSource.importText(r.configJson, true, "gen");
                        if (imp != null && imp.optBoolean("ok")) {
                            // 导入成功 → 立刻做一次真实搜索测试（同步，一次请求量级）
                            JSONObject test = BridgeSource.testCustom(r.id);
                            out.put("test", test == null ? new JSONObject() : test);
                        } else {
                            out.put("ok", false);
                            out.put("error", imp == null
                                    ? "导入失败" : ("导入失败：" + imp.optString("error")));
                        }
                    } else {
                        out.put("error", r.error);
                    }
                } catch (Throwable t) {
                    try { out.put("ok", false); out.put("error", "处理失败：" + t); } catch (Throwable ignored) {}
                }
                callback("onCustomList", BridgeSource.customListJson());
                callback("onSourceList", BridgeSource.allSourcesJson());
                callback("onGenDone", out);
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
     * @param dedup        整理重复源：失效的自动关闭留好源，都能用的归入「重复源」分组（不删除）
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
     * ★ v4.21.47：分类清单的进程内缓存。
     *
     * <p>categories() 对插件/JS/Python 源来说是**重操作**（反射执行插件代码、
     * 起 Rhino 引擎，有的还会联网取栏目）。冷启动挨个问 96 个源能把首页钉住十几秒。
     * 现在分类只在「用户真正选中某个源」时按需拉一次，拉完缓存在这里。
     *
     * <p>key = 源 id，value = [0]=时间戳 [1]=分类数组。
     * 源列表变化（导入/删除/启停）时会由 invalidateCatCache() 整个清掉。
     */
    private static final java.util.Map<String, Object[]> CAT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, Object[]>();
    /** 分类缓存有效期：10 分钟。过期也先秒回旧值，后台再静默刷新一次。 */
    private static final long CAT_TTL_MS = 10 * 60 * 1000L;
    /** 正在拉分类的源；同一个源不重复并发请求 */
    private static final java.util.Set<String> CAT_LOADING =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 源列表有变动（导入 / 删除 / 启停）时清空分类缓存 */
    public static void invalidateCatCache() {
        CAT_CACHE.clear();
        CAT_LOADING.clear();
    }

    /** List&lt;Category&gt; → 前端 cats 结构 [{k,n}] */
    private static JSONArray catsToJson(List<com.tingshu.app.source.Category> cs) {
        JSONArray a = new JSONArray();
        if (cs == null) return a;
        for (com.tingshu.app.source.Category c : cs) {
            if (c == null) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("k", c.id);
                o.put("n", c.name);
                a.put(o);
            } catch (Throwable ignored) {}
        }
        return a;
    }

    /** 增量回传单个源的分类，前端按 id 合并（不再等一次性大回包） */
    private void pushSourceCategories(String id, JSONArray cats, boolean ok) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("cats", cats == null ? new JSONArray() : cats);
            o.put("ok", ok);
            callback("onSourceCategories", o);
        } catch (Throwable ignored) {}
    }

    /**
     * ★ v4.21.47：首页/发现页的「源清单」——只做轻量枚举，毫秒级回包。
     *
     * <p>旧实现先调 SourceRegistry.browsable()，它对每个源串行执行 canBrowse()；
     * jar 插件的 canBrowse() 会反射执行插件 getCategoryMenus()（有的还联网），
     * 96 个源冷启动就是近百次重操作，任何一个卡住整个回包就不来。回包不来，
     * 前端的 homeSources 一直停在启动快照写入的「单个占位源」，左上角下拉
     * 永远只有一个；同时这些重任务占满线程池，点开就卡、甚至加载不出来。
     *
     * <p>现在：清单只看「启用 + 实现了 BrowsableSource」，绝不执行插件代码；
     * 分类从缓存带出（没有就空数组），用户真正选中某个源时再由
     * sourceCategories(id) 单独按需拉一次，回包走 onSourceCategories 增量合并。
     */
    @JavascriptInterface
    public void discoverSources() {
        Aggregator.lightPool().execute(new Runnable() {
            public void run() {
                JSONArray arr = new JSONArray();
                try {
                    List<Source> all = SourceRegistry.enabled();
                    for (Source s : all) {
                        if (s == null) continue;
                        if (!(s instanceof com.tingshu.app.source.BrowsableSource)) continue;
                        String id = s.id();
                        // 本地源 / 网盘源在前端下拉里单独分段，别混进网络书源清单
                        if ("local".equals(id) || (id != null && id.startsWith("pan_"))) continue;
                        JSONObject o = new JSONObject();
                        o.put("id", id);
                        o.put("name", s.name());
                        JSONArray cats = new JSONArray();
                        Object[] hit = CAT_CACHE.get(s.id());
                        if (hit != null) {
                            @SuppressWarnings("unchecked")
                            List<com.tingshu.app.source.Category> c =
                                    (List<com.tingshu.app.source.Category>) hit[1];
                            cats = catsToJson(c);
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
     * ★ v4.21.47：按需拉「单个源」的分类，结果增量回调 onSourceCategories。
     * 缓存（含过期）先秒回，过期项再后台刷新一次；单个源 8 秒超时，
     * 不会像旧版那样让 96 个源互相拖累。
     */
    @JavascriptInterface
    public void sourceCategories(final String sourceId) {
        if (sourceId == null || sourceId.length() == 0) return;
        Object[] hit = CAT_CACHE.get(sourceId);
        if (hit != null) {
            @SuppressWarnings("unchecked")
            List<com.tingshu.app.source.Category> c =
                    (List<com.tingshu.app.source.Category>) hit[1];
            pushSourceCategories(sourceId, catsToJson(c), true);
            if ((System.currentTimeMillis() - ((Long) hit[0])) < CAT_TTL_MS) return;
        }
        if (!CAT_LOADING.add(sourceId)) return;      // 已在拉，等那一趟的回包即可
        Aggregator.searchPool().execute(new Runnable() {
            public void run() {
                try {
                    final Source s = SourceRegistry.byId(sourceId);
                    if (!(s instanceof com.tingshu.app.source.BrowsableSource)) {
                        pushSourceCategories(sourceId, new JSONArray(), false);
                        return;
                    }
                    java.util.concurrent.Future<List<com.tingshu.app.source.Category>> f =
                            Aggregator.catPool().submit(
                                    new java.util.concurrent.Callable<List<com.tingshu.app.source.Category>>() {
                                        public List<com.tingshu.app.source.Category> call() {
                                            return ((com.tingshu.app.source.BrowsableSource) s).categories();
                                        }
                                    });
                    List<com.tingshu.app.source.Category> c;
                    try {
                        c = f.get(8, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException te) {
                        f.cancel(true);
                        pushSourceCategories(sourceId, new JSONArray(), false);
                        return;
                    }
                    if (c == null) c = new ArrayList<com.tingshu.app.source.Category>();
                    CAT_CACHE.put(sourceId, new Object[]{System.currentTimeMillis(), c});
                    pushSourceCategories(sourceId, catsToJson(c), true);
                } catch (Throwable ignored) {
                    pushSourceCategories(sourceId, new JSONArray(), false);
                } finally {
                    CAT_LOADING.remove(sourceId);
                }
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

    /** ★ v4.12.0：首页「最近在听」书架 —— 取最近 N 本，回推 onHomeRecent */
    @JavascriptInterface
    public void homeRecent(final int limit) {
        bg(new Runnable() {
            public void run() { BridgeData.recentList(web, ui, limit, "onHomeRecent"); }
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

    /**
     * ★ v3.9.0：查询某本书「已收听」的集号列表（1 基）。
     *
     * <p>选集弹层 / 详情页章节列表据此画绿色小对勾。数据源是 progress 表
     * （每听过一集就落一行），不另建存储。
     */
    @JavascriptInterface
    public void listenedMarks(final String bookKey) {
        bg(new Runnable() {
            public void run() { BridgeData.listenedMarks(web, ui, bookKey); }
        });
    }

    @JavascriptInterface
    public void homeInfo() {
        Aggregator.lightPool().execute(new Runnable() {   // ★ v3.9.37 本地轻任务
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
            o.put("autoNext", com.tingshu.app.data.Settings.autoNext());
            // ★ v3.1：音量增益 + 其他声音打断策略
            o.put("gain", com.tingshu.app.data.Settings.gain());
            o.put("audioFocusMode", com.tingshu.app.data.Settings.audioFocusMode());
            // ★ v3.1：上次验证结果摘要（源管理页顶部展示）。
            //   v3.2 起自动验证开关已移除，但摘要仍由手动「批量验证」写入。
            o.put("lastVerifySummary", com.tingshu.app.data.Settings.lastVerifySummary());
            // ★ v4.14.0：进度条锚点动效 + 下载转存（试用）开关
            o.put("barFx", com.tingshu.app.data.Settings.barFx());
            o.put("webdavExport", com.tingshu.app.data.Settings.webdavExportOn());
            // ★ v4.19.0：下载格式（orig / mp3）
            o.put("dlFormat", com.tingshu.app.data.Settings.dlFormat());
            // ★ v4.19.0：完整版（带 FFmpeg 转码组件）标志，设置页提示用
            try {
                o.put("ffLib", com.tingshu.app.player.WmaTrans.libraryAvailable());
            } catch (Throwable ignored) {}
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
                    // v4.21.45：更新源改回内置官方下载站的 manifest.json
                    // （Lucky 反代 HTTPS 主站 → 内网直连兜底），有新版直接给
                    // APK 直链（带强制下载头，点开即下载）。
                    // 自动选路，地址不回显到任何提示里
                    String body = fetchManifestText();
                    if (body == null) {
                        o.put("ok", false);
                        o.put("error", "无法连接更新服务器，请检查网络后重试");
                        callback("onUpdateResult", o);
                        return;
                    }
                    org.json.JSONObject j = new org.json.JSONObject(body);
                    String latest = "", file = "", md5 = "";
                    org.json.JSONArray apps = j.optJSONArray("apps");
                    if (apps != null) {
                        for (int i = 0; i < apps.length(); i++) {
                            org.json.JSONObject a = apps.optJSONObject(i);
                            if (a == null) continue;
                            String f = a.optString("file", "");
                            if (f.startsWith("fengxi-tingshu")
                                    || a.optString("app", "").contains("听书")) {
                                latest = a.optString("version", "");
                                file = f;
                                md5 = a.optString("md5", "");
                                break;
                            }
                        }
                    }
                    // 旧版顶层兼容字段兜底
                    if (latest.length() == 0) latest = j.optString("version", "");
                    if (file.length() == 0) file = j.optString("file", "");
                    if (md5.length() == 0) md5 = j.optString("md5", "");
                    if (latest.length() == 0) {
                        o.put("ok", false);
                        o.put("error", "没有找到发布版本");
                        callback("onUpdateResult", o);
                        return;
                    }
                    o.put("ok", true);
                    o.put("latest", latest);
                    o.put("md5", md5);
                    o.put("url", file.length() > 0
                            ? updUrl(lastUpdBase + file, lastUpdNeedCode) : "");
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

    /**
     * ★ v4.21.45：更新源改回「内置官方更新渠道」。
     *
     * <p>主站是家里 NAS 上 Lucky 反代出来的 HTTPS 下载站，反代后端就是
     * NAS 本机 :8099 的静态目录 —— 页面 / 清单 / APK 与 publish.sh 推上去的
     * 是同一份数据。App 内置地址、自动选路，界面里不显示也不需要用户填写，
     * 避免截图把地址带出去。
     *
     * <p>选路顺序：主站带分享码 → 主站不带码 → 家里内网直连（反代临时
     * 不可用时，连家里 WiFi 仍能更新）。三个源读的是同一份 manifest，
     * 不会出现版本漂移。域名 / 分享码只存在于代码常量里，任何 UI 文案、
     * 日志、提示一律不回显。
     */
    // 更新源地址已脱敏移除（仓库公开，禁止回显域名 / 内网 IP / 分享码）。
    // 需要恢复时从本地私有配置 .fengxi-env 回填，切勿写回公开源码。
    private static final String UPDATE_MANIFEST = "";
    private static final String UPDATE_BASE = "";
    /** 兜底：内网直连地址同样已脱敏移除 */
    private static final String UPDATE_MANIFEST_LAN = "";
    private static final String UPDATE_BASE_LAN = "";
    /**
     * 分享码已脱敏移除。设为空串后 updUrl() 不再追加，更新请求自然失效。
     */
    private static final String UPDATE_SHARECODE = "";

    /** 本次检查更新实际命中的源（决定 APK 直链用哪个前缀） */
    private static volatile String lastUpdBase = UPDATE_BASE;
    /** 命中内网直连时不需要分享码 */
    private static volatile boolean lastUpdNeedCode = true;

    /** 给主站 URL 追加分享码（已有查询串时用 & 连接） */
    private static String updUrl(String url, boolean withCode) {
        if (!withCode || UPDATE_SHARECODE.length() == 0) return url;
        return url + (url.contains("?") ? "&" : "?")
                + "sharecode=" + UPDATE_SHARECODE;
    }

    /**
     * 依次尝试拉 manifest：主站带码 → 主站无码 → 内网直连。
     * 只有「返回体真的是 JSON」才算命中，错误页 / 404 的 HTML 不算。
     * httpGetText 内部已含 https 失败退 http 的重试。
     */
    private static String fetchManifestText() {
        String[] tries = new String[]{
                updUrl(UPDATE_MANIFEST, true),
                UPDATE_MANIFEST,
                UPDATE_MANIFEST_LAN,
        };
        String[] bases = new String[]{UPDATE_BASE, UPDATE_BASE, UPDATE_BASE_LAN};
        boolean[] needCode = new boolean[]{true, true, false};
        for (int i = 0; i < tries.length; i++) {
            String b = httpGetText(tries[i], 8000);
            if (b != null) {
                String t = b.trim();
                if (t.length() > 0 && t.charAt(0) == '{') {
                    lastUpdBase = bases[i];
                    lastUpdNeedCode = needCode[i];
                    return b;
                }
            }
        }
        return null;
    }

    /** 读本地安装包的 versionName */
    private String localVersionName() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0.0.0";
        }
    }

    /** 内置更新源（Lucky 反代）的主机名 —— 只有它才允许自签证书 */
    private static String updateHost() {
        try {
            return new java.net.URL(UPDATE_MANIFEST).getHost();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * ★ v4.21.46：Lucky 反代默认签的是自签名证书（CN=Lucky），系统不信任；
     * 旧版 App 因此连不上主站，只能退到会话级公共镜像，而那里停更在
     * v4.21.42 —— 这就是「检查更新永远检测不到新版」的真正原因。
     *
     * <p>这里只给「内置更新源」这一条连接放宽校验，且严格限定主机名：
     * 其它任何 https 请求仍走系统严格校验；绝不调用 setDefaultSSLSocketFactory
     * 影响全局。安装包下载后还有 manifest 里的 MD5 校验兜底，防止中途被换包。
     */
    private static void trustSelfSignedIfUpdate(java.net.HttpURLConnection conn, String url) {
        if (!(conn instanceof javax.net.ssl.HttpsURLConnection)) return;
        if (!url.startsWith("https://")) return;
        String h;
        try {
            h = new java.net.URL(url).getHost();
        } catch (Throwable t) {
            return;
        }
        String allowed = updateHost();
        if (allowed.length() == 0 || !allowed.equalsIgnoreCase(h)) return;
        try {
            javax.net.ssl.TrustManager[] tm = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, tm, new java.security.SecureRandom());
            javax.net.ssl.HttpsURLConnection hs = (javax.net.ssl.HttpsURLConnection) conn;
            hs.setSSLSocketFactory(sc.getSocketFactory());
            hs.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                    return true;
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 极简 HTTP GET（只用于更新检查这种一次性小请求）。
     * 不引第三方库，超时短，失败返回 null。
     */
    private static String httpGetText(String url, int timeoutMs) {
        // v4.21.45：主站是 HTTPS（Lucky 反代）；证书/端口偶发不稳时
        // 再退到 http 同路径（清单已开 usesCleartextTraffic）。
        String r = httpGetTextOnce(url, timeoutMs);
        if (r == null && url.startsWith("https://")) {
            r = httpGetTextOnce("http://" + url.substring(8), timeoutMs);
        }
        return r;
    }

    private static String httpGetTextOnce(String url, int timeoutMs) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL u = new java.net.URL(url);
            conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "FengxiTingshu");
            trustSelfSignedIfUpdate(conn, url);
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

    // ==================== ★ v4.21.36 自动更新（检测→下载→安装一条龙） ====================

    private static final Object UPDATE_LOCK = new Object();
    private static volatile boolean updating;

    /**
     * 下载 APK 并自动拉起安装。前端「检测更新」发现新版后会自动调这里，
     * 也可以点「立即下载」手动触发。进度通过 onUpdateProgress 回调：
     * <pre>
     *   {phase:'download',percent,mb} → {phase:'verify'} → {phase:'install'}
     * </pre>
     * 出错回 {phase:'error',error}；缺安装授权回 {phase:'permission'}。
     */
    @JavascriptInterface
    public void downloadAndInstall(final String url, final String md5) {
        if (url == null || url.length() == 0) {
            updProgress("error", "下载地址未就绪");
            return;
        }
        synchronized (UPDATE_LOCK) {
            if (updating) return;   // 防重复点击
            updating = true;
        }
        bg(new Runnable() {
            public void run() {
                try {
                    java.io.File dir = new java.io.File(
                            activity.getExternalFilesDir(null), "update");
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File f = new java.io.File(dir, "update.apk");
                    // url 已带分享码（前端下发的就是带码直链），https 不稳再退 http
                    if (!downloadApk(url, f)) {
                        f.delete();
                        if (url.startsWith("https://")) {
                            downloadApk("http://" + url.substring(8), f);
                        }
                    }
                    if (!isApk(f)) {
                        f.delete();
                        updProgress("error", "下载失败：文件不完整或网络不可达，请稍后重试");
                        return;
                    }
                    // 校验 MD5（manifest 里带，防下载被截断或被换包）
                    updProgress("verify", "");
                    if (md5 != null && md5.length() == 32) {
                        String real = fileMd5(f);
                        if (real.length() > 0 && !real.equalsIgnoreCase(md5)) {
                            f.delete();
                            updProgress("error", "安装包校验失败（MD5 不符），请稍后重试");
                            return;
                        }
                    }
                    installApk(f);
                } catch (Throwable t) {
                    updProgress("error", "更新失败：" + t.getClass().getSimpleName());
                } finally {
                    updating = false;
                }
            }
        });
    }

    /** 下载 APK，成功（文件落盘且超过 1MB）返回 true */
    private boolean downloadApk(String url, java.io.File f) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL u = new java.net.URL(url);
            conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "FengxiTingshu");
            trustSelfSignedIfUpdate(conn, url);
            long total = conn.getContentLength();
            java.io.InputStream in = conn.getInputStream();
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            byte[] buf = new byte[16384];
            long got = 0;
            int n, lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                if (total > 0) {
                    int pct = (int) (got * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        JSONObject p = new JSONObject();
                        p.put("phase", "download");
                        p.put("percent", pct);
                        p.put("mb", String.format(java.util.Locale.US, "%.1f/%.1fMB",
                                got / 1048576.0, total / 1048576.0));
                        callback("onUpdateProgress", p);
                    }
                }
            }
            out.flush();
            out.close();
            in.close();
            return f.exists() && f.length() > 1024 * 1024;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
        }
    }

    /** 拉起系统安装器；Android 8+ 需要先有「安装未知应用」授权 */
    private void installApk(java.io.File f) {
        com.tingshu.app.core.UpdateProvider.setApk(f);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                if (!activity.getPackageManager().canRequestPackageInstalls()) {
                    updProgress("permission", "");
                    return;
                }
            } catch (Throwable ignored) {}
        }
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_INSTALL_PACKAGE,
                    android.net.Uri.parse("content://"
                            + com.tingshu.app.core.UpdateProvider.AUTHORITY + "/update.apk"));
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            updProgress("install", "");
        } catch (Throwable t) {
            updProgress("error", "无法拉起安装器：" + t.getClass().getSimpleName());
        }
    }

    /** 前端点「去授权」：打开本应用的「安装未知应用」系统设置页 */
    @JavascriptInterface
    public void openInstallSettings() {
        try {
            android.content.Intent i;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                i = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + activity.getPackageName()));
            } else {
                i = new android.content.Intent(
                        android.provider.Settings.ACTION_SECURITY_SETTINGS);
            }
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Throwable t) {
            js("window.onToast(" + JSONObject.quote("未找到系统设置入口，请到系统设置手动允许安装") + ")");
        }
    }

    private void updProgress(String phase, String extra) {
        try {
            JSONObject p = new JSONObject();
            p.put("phase", phase);
            if (extra != null && extra.length() > 0) p.put("error", extra);
            callback("onUpdateProgress", p);
        } catch (Throwable ignored) {}
    }

    /** 判断文件是不是有效 APK（ZIP 头 PK 且大于 1MB） */
    private static boolean isApk(java.io.File f) {
        if (f == null || !f.exists() || f.length() < 1024 * 1024) return false;
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] h = new byte[2];
            return in.read(h) == 2 && h[0] == 'P' && h[1] == 'K';
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
        }
    }

    /** 算安装包 MD5（与下载站 manifest 比对用） */
    private static String fileMd5(java.io.File f) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("MD5");
            java.io.InputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
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

    // ==================== ★ v4.14.0 转存 WebDAV（试用）+ 按书删除 ====================

    /** 转存状态（选集菜单/设置页初始化用） */
    @JavascriptInterface
    public void webdavExportState() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    o.put("on", com.tingshu.app.data.Settings.webdavExportOn());
                    o.put("davConfigured", com.tingshu.app.data.BackupCred.configured());
                    o.put("davUrl", com.tingshu.app.data.BackupCred.url());
                } catch (Throwable ignored) {}
                js("window.onDavExportState(" + o.toString() + ")");
            }
        });
    }

    // ============ ★ v4.16.0 多设备续听（计划 #21，挂远程功能锁 sync_progress） ============

    /** 多设备续听状态（设置页展示） */
    @JavascriptInterface
    public void progressSyncState() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    o.put("on", com.tingshu.app.data.Settings.progressSyncOn());
                    o.put("davConfigured", com.tingshu.app.data.BackupCred.configured());
                    o.put("davUrl", com.tingshu.app.data.BackupCred.url());
                    o.put("locked", !com.tingshu.app.core.FeatureGate.allow(
                            com.tingshu.app.core.FeatureGate.SYNC_PROGRESS));
                    o.put("lastSync", com.tingshu.app.data.ProgressSync.lastSyncAt());
                    o.put("error", com.tingshu.app.data.ProgressSync.lastError());
                } catch (Throwable ignored) {}
                js("window.onProgressSyncState(" + o.toString() + ")");
            }
        });
    }

    /** 开/关多设备续听（★ 功能锁：远程已收回时不允许开启） */
    @JavascriptInterface
    public void setProgressSync(final boolean on) {
        bg(new Runnable() {
            public void run() {
                if (on && !com.tingshu.app.core.FeatureGate.allow(
                        com.tingshu.app.core.FeatureGate.SYNC_PROGRESS)) {
                    js("window.toast('"
                            + com.tingshu.app.core.FeatureGate.denyMsg(
                            com.tingshu.app.core.FeatureGate.SYNC_PROGRESS) + "')");
                    return;
                }
                com.tingshu.app.data.Settings.setProgressSyncOn(on);
                if (on) com.tingshu.app.data.ProgressSync.syncNow();
                progressSyncState();
            }
        });
    }

    /** 立即同步一次（先拉后推），回报应用了多少条远端进度 */
    @JavascriptInterface
    public void syncProgressNow() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    if (!com.tingshu.app.data.ProgressSync.enabled()) {
                        o.put("ok", false);
                        o.put("msg", !com.tingshu.app.data.BackupCred.configured()
                                ? "请先在网络备份里配置 WebDAV"
                                : (!com.tingshu.app.data.Settings.progressSyncOn()
                                ? "多设备续听已关闭"
                                : com.tingshu.app.core.FeatureGate.denyMsg(
                                com.tingshu.app.core.FeatureGate.SYNC_PROGRESS)));
                    } else {
                        int n = com.tingshu.app.data.ProgressSync.syncNow();
                        String err = com.tingshu.app.data.ProgressSync.lastError();
                        o.put("ok", err.length() == 0);
                        o.put("applied", n);
                        o.put("msg", err.length() > 0
                                ? ("同步失败：" + err)
                                : ("已同步，更新 " + n + " 项进度"));
                    }
                } catch (Throwable ignored) {}
                js("window.onProgressSyncDone(" + o.toString() + ")");
            }
        });
    }

    /** 开/关下载自动转存（★ v4.15.0 功能锁：试用功能可随时远程回收权限） */
    @JavascriptInterface
    public void setWebdavExport(final boolean on) {
        bg(new Runnable() {
            public void run() {
                if (on && !com.tingshu.app.core.FeatureGate.allow(
                        com.tingshu.app.core.FeatureGate.DAV_EXPORT)) {
                    js("TS.toast('" + com.tingshu.app.core.FeatureGate
                            .denyMsg(com.tingshu.app.core.FeatureGate.DAV_EXPORT) + "')");
                    return;
                }
                com.tingshu.app.data.Settings.setWebdavExportOn(on);
                JSONObject o = new JSONObject();
                try {
                    o.put("on", on);
                    o.put("davConfigured", com.tingshu.app.data.BackupCred.configured());
                } catch (Throwable ignored) {}
                js("window.onDavExportState(" + o.toString() + ")");
            }
        });
    }

    /** 立即把本书已下载的集全部转存到 WebDAV（选集菜单触发） */
    @JavascriptInterface
    public void exportDownloadsToWebdav(final String bookJson) {
        bg(new Runnable() {
            public void run() {
                Book b = parseBook(bookJson);
                if (b == null) return;
                if (!com.tingshu.app.core.FeatureGate.allow(
                        com.tingshu.app.core.FeatureGate.DAV_EXPORT)) {
                    js("TS.toast('" + com.tingshu.app.core.FeatureGate
                            .denyMsg(com.tingshu.app.core.FeatureGate.DAV_EXPORT) + "')");
                    return;
                }
                if (!com.tingshu.app.data.BackupCred.configured()) {
                    js("TS.toast('还没有配置 WebDAV 服务器（设置 → 网络备份）')");
                    return;
                }
                com.tingshu.app.core.DownloadManager.exportBookToWebDav(b);
            }
        });
    }

    /** 删除本书全部下载（选集菜单触发），回推数量 */
    @JavascriptInterface
    public void clearBookDownloads(final String bookJson) {
        bg(new Runnable() {
            public void run() {
                Book b = parseBook(bookJson);
                if (b == null) return;
                final int n = com.tingshu.app.data.DownloadRepo.clearBook(activity, b.key());
                JSONObject o = new JSONObject();
                try { o.put("removed", n); } catch (Throwable ignored) {}
                js("window.onBookDownloadsCleared(" + o.toString() + ")");
                downloadList();
            }
        });
    }

    // ==================== ★ v4.15.0 源登录（Cookie）入口 ====================
    //
    // 很多源（酷我/QQ 音乐类接口）不登录拿不到数据或拿不到完整数据。
    // 登录态统一落在 Net 的 Cookie 库（按 host 存、父域回退、随请求自动注入），
    // 这里只提供「设置 / 清除 / 网页登录」三个入口。

    /** 源 id → 登录站点 的映射（持久化，重启后还能判断「该源是否已登录」） */
    private static final String SP_SRC_LOGIN = "src_login_v1";

    private static org.json.JSONObject srcLoginMap() {
        try {
            String raw = com.tingshu.app.data.Settings.sp().getString(SP_SRC_LOGIN, "{}");
            return new org.json.JSONObject(raw == null || raw.length() == 0 ? "{}" : raw);
        } catch (Throwable t) {
            return new org.json.JSONObject();
        }
    }

    private static void saveSrcLoginMap(org.json.JSONObject m) {
        try {
            com.tingshu.app.data.Settings.sp().edit()
                    .putString(SP_SRC_LOGIN, m.toString()).apply();
        } catch (Throwable ignored) {}
    }

    /** 查询某源的登录状态 → window.onSourceLoginState({id, has, host}) */
    @JavascriptInterface
    public void sourceLoginState(final String srcId) {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    o.put("id", srcId);
                    String site = srcLoginMap().optString(srcId, "");
                    o.put("site", site);
                    boolean has = site.length() > 0
                            && com.tingshu.app.core.Net.getCookie(site) != null;
                    o.put("has", has);
                } catch (Throwable ignored) {}
                js("window.onSourceLoginState(" + o.toString() + ")");
            }
        });
    }

    /**
     * 手动写入某源的 Cookie。
     *
     * @param srcId   源 id
     * @param cookie  Cookie 串（从浏览器 F12 → Network → 请求头里复制整行）
     * @param siteUrl 该源的站点地址（决定 Cookie 存到哪个域）
     */
    @JavascriptInterface
    public void setSourceCookie(final String srcId, final String cookie, final String siteUrl) {
        bg(new Runnable() {
            public void run() {
                if (srcId == null || srcId.length() == 0) return;
                if (siteUrl == null || siteUrl.length() == 0 || cookie == null
                        || cookie.trim().length() == 0) {
                    js("TS.toast('Cookie 或站点地址为空')");
                    return;
                }
                com.tingshu.app.core.Net.setCookie(siteUrl, cookie.trim());
                persistCookies();
                org.json.JSONObject m = srcLoginMap();
                try { m.put(srcId, siteUrl); } catch (Throwable ignored) {}
                saveSrcLoginMap(m);
                js("TS.toast('已保存登录 Cookie')");
                js("window.onSourceLoginState(" + loginStateJson(srcId, siteUrl) + ")");
            }
        });
    }

    /** 清除某源的登录 Cookie */
    @JavascriptInterface
    public void clearSourceCookie(final String srcId, final String siteUrl) {
        bg(new Runnable() {
            public void run() {
                if (siteUrl != null && siteUrl.length() > 0) {
                    com.tingshu.app.core.Net.setCookie(siteUrl, "");
                    persistCookies();
                }
                org.json.JSONObject m = srcLoginMap();
                if (srcId != null && m.has(srcId)) { m.remove(srcId); saveSrcLoginMap(m); }
                js("TS.toast('已清除该源的登录状态')");
                js("window.onSourceLoginState(" + loginStateJson(srcId, siteUrl) + ")");
            }
        });
    }

    /** 打开内置 WebView 登录页：用户在网页里登录，回来 Cookie 自动入库 */
    @JavascriptInterface
    public void openWebLogin(final String srcId, final String siteUrl) {
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (siteUrl == null || siteUrl.length() == 0) {
                    js("TS.toast('这个源没有站点地址，请改用粘贴 Cookie 的方式')");
                    return;
                }
                webLoginCallback = new Runnable() {
                    public void run() {
                        js("window.onWebLoginDone(" + loginStateJson(srcId, siteUrl) + ")");
                    }
                };
                android.content.Intent it = new android.content.Intent(
                        activity, com.tingshu.app.LoginActivity.class);
                it.putExtra("srcId", srcId == null ? "" : srcId);
                it.putExtra("siteUrl", siteUrl);
                activity.startActivity(it);
            }
        });
    }

    /** LoginActivity 登录完成回调（静态，由 openWebLogin 注入当前 Bridge） */
    static volatile java.lang.Runnable webLoginCallback;

    private static String loginStateJson(String srcId, String siteUrl) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", srcId);
            o.put("site", siteUrl == null ? "" : siteUrl);
            boolean has = siteUrl != null && siteUrl.length() > 0
                    && com.tingshu.app.core.Net.getCookie(siteUrl) != null;
            o.put("has", has);
        } catch (Throwable ignored) {}
        return o.toString();
    }

    /** Cookie 库落盘（Net 的存储在 SP 的 cookie_store 键） */
    static void persistCookies() {
        try {
            com.tingshu.app.core.Net.persistCookies(com.tingshu.app.data.Settings.sp());
        } catch (Throwable ignored) {}
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

    // ==================== ★ v4.21.48 分集管理 + 手动转存 ====================
    //
    // 设计要点：
    //  - 分集列表不新建表、不改 DB 版本，直接读 download 表按 chapter_idx 排序；
    //  - 回调全部用 onDl* 独立命名，绝不混进下载/缓存共用的 onCache*；
    //  - 转存一律「复制」，原下载文件不动；本地走现场挑的 SAF 目录，
    //    NAS 复用 BackupCred 的 WebDAV 配置与远端去重。

    /** 某本书每一集的索引 → window.onDlEpisodes({key,title,items:[...]}) */
    @JavascriptInterface
    public void downloadEpisodes(final String key) {
        bg(new Runnable() {
            public void run() { pushEpisodes(key); }
        });
    }

    /** 删除单集（含私有文件；外部目录里的文件属用户，不动） */
    @JavascriptInterface
    public void downloadDeleteOne(final String key, final int idx) {
        bg(new Runnable() {
            public void run() {
                try {
                    com.tingshu.app.data.DownloadRepo.deleteOne(activity, key, idx);
                } catch (Throwable ignored) {}
                downloadList();
                pushEpisodes(key);
            }
        });
    }

    /** 批量删除分集：idxJson 是 1 基集号数组，如 "[1,3,5]" */
    @JavascriptInterface
    public void downloadDeleteMany(final String key, final String idxJson) {
        bg(new Runnable() {
            public void run() {
                int[] idxs = parseIntArray(idxJson);
                if (idxs != null) {
                    for (int i = 0; i < idxs.length; i++) {
                        try {
                            com.tingshu.app.data.DownloadRepo.deleteOne(activity, key, idxs[i]);
                        } catch (Throwable ignored) {}
                    }
                }
                downloadList();
                pushEpisodes(key);
            }
        });
    }

    /** 手动转存 → 本地目录：先弹系统目录选择器，结果回 MainActivity */
    @JavascriptInterface
    public void dlExportPickDir(final String key, final String idxJson) {
        final int[] idxs = resolveExportIdxs(key, idxJson);
        if (idxs == null || idxs.length == 0) {
            js("TS.toast('这本书没有可转存的已下载分集')");
            return;
        }
        if (com.tingshu.app.core.DlExport.isRunning()) {
            js("TS.toast('已有转存任务在进行，请等它结束')");
            return;
        }
        dlExportKey = key;
        dlExportIdxs = idxs;
        dlExportTitle = titleOfKey(key);
        ui.post(new Runnable() {
            public void run() {
                try {
                    if (activity instanceof com.tingshu.app.MainActivity) {
                        ((com.tingshu.app.MainActivity) activity).pickExportDir();
                    } else {
                        js("TS.toast('无法打开目录选择器')");
                    }
                } catch (Throwable t) {
                    js("TS.toast('无法打开目录选择器')");
                }
            }
        });
    }

    /** 手动转存 → NAS：直接用已配好的 WebDAV，不需要再挑目录 */
    @JavascriptInterface
    public void dlExportNas(final String key, final String idxJson) {
        final int[] idxs = resolveExportIdxs(key, idxJson);
        if (idxs == null || idxs.length == 0) {
            js("TS.toast('这本书没有可转存的已下载分集')");
            return;
        }
        if (!com.tingshu.app.core.FeatureGate.allow(
                com.tingshu.app.core.FeatureGate.DAV_EXPORT)) {
            js("TS.toast('" + com.tingshu.app.core.FeatureGate
                    .denyMsg(com.tingshu.app.core.FeatureGate.DAV_EXPORT) + "')");
            return;
        }
        if (!com.tingshu.app.data.BackupCred.configured()) {
            js("TS.toast('转存 NAS 需要先在「设置 → 网络备份」里填好 WebDAV；当前还没配置')");
            return;
        }
        com.tingshu.app.core.DlExport.exportNas(activity, key, idxs,
                titleOfKey(key), dlExportHook());
    }

    /**
     * 目录选择器选完 → 由 MainActivity 回调（非 JS 接口）。
     * 真正的复制放到 DlExport 自己的单线程池里，别堵 WebView 线程。
     */
    public void dlExportLocalGo(String treeUri) {
        if (dlExportKey == null || dlExportIdxs == null) return;
        com.tingshu.app.core.DlExport.exportLocal(activity,
                android.net.Uri.parse(treeUri), dlExportKey, dlExportIdxs,
                dlExportTitle, dlExportHook());
    }

    /** 转存进度/结果统一回推，前端分集管理页自己渲染 */
    private com.tingshu.app.core.DlExport.Hook dlExportHook() {
        return new com.tingshu.app.core.DlExport.Hook() {
            public void onProgress(final int done, final int total,
                                   final int ok, final int skip, final int fail) {
                JSONObject o = new JSONObject();
                try {
                    o.put("done", done); o.put("total", total);
                    o.put("ok", ok); o.put("skip", skip); o.put("fail", fail);
                } catch (Throwable ignored) {}
                js("window.onDlExportProgress(" + o.toString() + ")");
            }

            public void onDone(final int ok, final int skip, final int fail,
                               final int total, final String target, final String error) {
                JSONObject o = new JSONObject();
                try {
                    o.put("ok", ok); o.put("skip", skip); o.put("fail", fail);
                    o.put("total", total);
                    o.put("target", target == null ? "" : target);
                    o.put("error", error == null ? "" : error);
                } catch (Throwable ignored) {}
                js("window.onDlExportDone(" + o.toString() + ")");
            }
        };
    }

    /** 组装分集列表并回推（打开分集页 / 删除后刷新都走这里） */
    private void pushEpisodes(String key) {
        JSONObject o = new JSONObject();
        try {
            o.put("key", key == null ? "" : key);
            o.put("title", titleOfKey(key));
            org.json.JSONArray arr = new org.json.JSONArray();
            java.util.List<com.tingshu.app.data.DownloadRepo.Episode> eps =
                    com.tingshu.app.data.DownloadRepo.episodes(key);
            for (com.tingshu.app.data.DownloadRepo.Episode e : eps) {
                JSONObject it = new JSONObject();
                it.put("idx", e.idx);
                it.put("size", e.size);
                it.put("sizeText", com.tingshu.app.data.DownloadRepo.humanSize(e.size));
                it.put("status", e.status);
                it.put("statusText", e.status == com.tingshu.app.data.DownloadRepo.ST_DONE
                        ? "已完成" : (e.status == com.tingshu.app.data.DownloadRepo.ST_FAIL
                        ? "失败" : "进行中"));
                String loc = "App 内";
                String play = null;
                if (e.path != null && e.path.length() > 0
                        && new java.io.File(e.path).exists()) {
                    play = "file://" + e.path;
                } else if (e.savedUri != null && e.savedUri.length() > 0) {
                    loc = "外部目录";
                    play = e.savedUri;
                } else {
                    loc = "文件缺失";
                }
                it.put("loc", loc);
                it.put("play", play == null ? "" : play);
                it.put("savedPath", e.savedPath == null ? "" : e.savedPath);
                arr.put(it);
            }
            o.put("items", arr);
            o.put("count", arr.length());
        } catch (Throwable ignored) {}
        js("window.onDlEpisodes(" + o.toString() + ")");
    }

    // ==================== ★ v4.18.0 下载任务可视化管理 ====================

    /** 当前下载任务清单（进行中/已暂停）→ window.onDownloadTasks */
    @JavascriptInterface
    public void downloadTasks() {
        bg(new Runnable() {
            public void run() {
                js("window.onDownloadTasks(" +
                        com.tingshu.app.core.DownloadManager.tasksJson() + ")");
            }
        });
    }

    /** 暂停一个下载任务（当前集下完即停，剩余集保留在队列里） */
    @JavascriptInterface
    public void downloadPause(final String key) {
        bg(new Runnable() {
            public void run() {
                com.tingshu.app.core.DownloadManager.pause(key);
                downloadTasks();
            }
        });
    }

    /** 恢复一个已暂停的下载任务（从剩余集继续） */
    @JavascriptInterface
    public void downloadResume(final String key) {
        bg(new Runnable() {
            public void run() {
                com.tingshu.app.core.DownloadManager.resume(key);
                downloadTasks();
            }
        });
    }

    /** 取消一个下载任务（剩余集清空；已下好的集不受影响） */
    @JavascriptInterface
    public void downloadCancel(final String key) {
        bg(new Runnable() {
            public void run() {
                com.tingshu.app.core.DownloadManager.cancel(key);
                downloadTasks();
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

    /**
     * ★ v4.21.49 转存目标集解析：
     * 非空数组 {@code [1,3,5]} 原样返回（分集页勾选）；空数组 {@code []}
     * 表示主页面的「转存整本」按钮，展开成这本书所有已下载且源文件还在的集。
     * 其余情况返回 null，由调用方给出提示。
     */
    private static int[] resolveExportIdxs(String key, String idxJson) {
        int[] a = parseIntArray(idxJson);
        if (a != null) return a;
        if (idxJson == null || !idxJson.trim().startsWith("[")) return null;
        java.util.List<com.tingshu.app.data.DownloadRepo.Episode> eps =
                com.tingshu.app.data.DownloadRepo.episodes(key);
        java.util.List<Integer> picked = new java.util.ArrayList<Integer>();
        for (com.tingshu.app.data.DownloadRepo.Episode e : eps) {
            if (e.status != com.tingshu.app.data.DownloadRepo.ST_DONE) continue;
            boolean has = (e.path != null && e.path.length() > 0
                    && new java.io.File(e.path).exists())
                    || (e.savedUri != null && e.savedUri.length() > 0);
            if (has) picked.add(Integer.valueOf(e.idx));
        }
        if (picked.isEmpty()) return null;
        int[] out = new int[picked.size()];
        for (int i = 0; i < out.length; i++) out[i] = picked.get(i).intValue();
        return out;
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

    /** ★ v4.10.10：播放诊断日志（关于页查看/复制） */
    @JavascriptInterface
    public void playerLog() {
        bg(new Runnable() {
            public void run() {
                String txt = com.tingshu.app.core.PlayerLog.dump();
                JSONObject o = new JSONObject();
                try {
                    o.put("log", txt);
                    o.put("empty", txt.trim().length() == 0);
                } catch (Throwable ignored) {}
                callback("onPlayerLog", o);
            }
        });
    }

    /** ★ v4.10.10：清空播放诊断日志 */
    @JavascriptInterface
    public void playerLogClear() {
        com.tingshu.app.core.PlayerLog.clear();
        playerLog();
    }

    // ==================== 播客订阅（★ v4.11.0） ====================

    /**
     * 列出播客订阅。回调 {@code onPodcastSubs({subs:[{u,t,c}]})}。
     * u = RSS 地址，t = 节目名，c = 封面。
     */
    @JavascriptInterface
    public void podcastSubs() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    JSONArray a = new JSONArray();
                    for (org.json.JSONObject s : com.tingshu.app.source.impl.PodcastSource.loadSubs()) {
                        a.put(s);
                    }
                    o.put("subs", a);
                } catch (Throwable ignored) {}
                callback("onPodcastSubs", o);
            }
        });
    }

    /**
     * 添加播客订阅：先拉一次 RSS 验证并取节目名/封面，成功才入库。
     * 回调 {@code onPodcastAdded({ok, title, existed, error})}。
     */
    @JavascriptInterface
    public void podcastAdd(final String url) {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                String u = url == null ? "" : url.trim();
                try {
                    o.put("ok", false);
                } catch (Throwable ignored) {}
                if (u.length() == 0 || !(u.startsWith("http://") || u.startsWith("https://"))) {
                    try { o.put("error", "请填写 http(s):// 开头的 RSS 地址"); } catch (Throwable ignored) {}
                    callback("onPodcastAdded", o);
                    return;
                }
                try {
                    com.tingshu.app.core.RssParser.Feed f = com.tingshu.app.core.RssParser.fetch(u);
                    String title = f.title.length() > 0 ? f.title : u;
                    boolean existed = !com.tingshu.app.source.impl.PodcastSource.addSub(u, title, f.cover);
                    o.put("ok", true);
                    o.put("title", title);
                    o.put("existed", existed);
                } catch (Throwable t) {
                    try {
                        o.put("error", t.getMessage() == null ? "订阅失败（地址不可达或不是 RSS）" : t.getMessage());
                    } catch (Throwable ignored) {}
                }
                callback("onPodcastAdded", o);
            }
        });
    }

    /**
     * 删除播客订阅。回调 {@code onPodcastDeleted({ok, url, left})}。
     */
    @JavascriptInterface
    public void podcastDel(final String url) {
        bg(new Runnable() {
            public void run() {
                boolean ok = com.tingshu.app.source.impl.PodcastSource.delSub(url);
                JSONObject o = new JSONObject();
                try {
                    o.put("ok", ok);
                    o.put("url", url == null ? "" : url);
                    o.put("left", com.tingshu.app.source.impl.PodcastSource.loadSubs().size());
                } catch (Throwable ignored) {}
                callback("onPodcastDeleted", o);
            }
        });
    }

    // ==================== 个人音频库：WebDAV 服务器（★ v4.13.0） ====================

    /**
     * 列出已配置的服务器，顺带把备份页那台的配置带出去（一键导入用）。
     * 回调 {@code onWebdavLibs({libs:[{n,u,p,a,w,t}], bkHas, bkUrl, bkUser, bkDir, bkAuth})}。
     */
    @JavascriptInterface
    public void webdavLibs() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    JSONArray a = new JSONArray();
                    for (JSONObject s : com.tingshu.app.source.impl.WebdavSource.loadLibs()) {
                        a.put(s);
                    }
                    o.put("libs", a);
                    o.put("bkHas", com.tingshu.app.data.BackupCred.configured());
                    o.put("bkUrl", com.tingshu.app.data.BackupCred.url());
                    o.put("bkUser", com.tingshu.app.data.BackupCred.user());
                    o.put("bkDir", com.tingshu.app.data.BackupCred.dir());
                    o.put("bkAuth", com.tingshu.app.data.BackupCred.authType());
                } catch (Throwable ignored) {}
                callback("onWebdavLibs", o);
            }
        });
    }

    /**
     * 保存一台服务器（idx &lt; 0 为新增，否则更新第 idx 台）。
     *
     * <p><b>先真连一次，通了才入库</b>——否则用户会攒下一堆连不上的配置，
     * 排查时根本分不清是哪台的问题。
     * 回调 {@code onWebdavSaved({ok, created, error, tip, libs:[...]})}。
     */
    @JavascriptInterface
    public void webdavSave(final String json, final int idx) {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try { o.put("ok", false); } catch (Throwable ignored) {}
                JSONObject lib;
                try {
                    lib = new JSONObject(json == null ? "{}" : json);
                } catch (Throwable t) {
                    try { o.put("error", "参数解析失败"); } catch (Throwable ignored) {}
                    callback("onWebdavSaved", o);
                    return;
                }
                String url = lib.optString("u", "").trim();
                if (url.length() == 0) {
                    try { o.put("error", "请先填写服务器地址"); } catch (Throwable ignored) {}
                    callback("onWebdavSaved", o);
                    return;
                }
                // ★ IPv6 必须用方括号包起来，这是新手最容易卡住的一步
                if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                    try {
                        o.put("error", "地址要以 http:// 或 https:// 开头\n"
                                + "IPv6 请用方括号：http://[2001:db8::1]:5005/dav/");
                    } catch (Throwable ignored) {}
                    callback("onWebdavSaved", o);
                    return;
                }
                try { lib.put("u", url); } catch (Throwable ignored) {}

                String user = lib.optString("a", "");
                String pass = lib.optString("w", "");
                String path = lib.optString("p", "");
                String auth = lib.optString("t",
                        com.tingshu.app.data.WebDavClient.AUTH_AUTO);

                com.tingshu.app.data.WebDavClient.Result r =
                        com.tingshu.app.source.impl.WebdavSource.testLib(
                                url, user, pass, path, auth);
                if (!r.ok) {
                    StringBuilder sb = new StringBuilder();
                    if (r.error != null && r.error.length() > 0) sb.append(r.error);
                    else sb.append("连不上服务器（HTTP ").append(r.code).append(')');
                    if (r.wwwAuth != null && r.code == 401) {
                        sb.append("\n服务端要求认证方式：")
                          .append(r.wwwAuth.toLowerCase().contains("digest") ? "Digest" : "Basic");
                    }
                    try { o.put("error", sb.toString()); } catch (Throwable ignored) {}
                    callback("onWebdavSaved", o);
                    return;
                }

                boolean created = false;
                boolean ok;
                if (idx >= 0) {
                    ok = com.tingshu.app.source.impl.WebdavSource.updateLib(idx, lib);
                } else {
                    created = com.tingshu.app.source.impl.WebdavSource.addLib(lib);
                    ok = true;
                }
                // 服务器清单变了：categories() 输出随之改变，必须让首页分类缓存失效，
                // 否则用户会看到「保存成功但首页没有这台服务器」的假象。
                try { invalidateCatCache(); } catch (Throwable ignored) {}
                try {
                    o.put("ok", ok);
                    o.put("created", created);
                    JSONArray a = new JSONArray();
                    for (JSONObject s : com.tingshu.app.source.impl.WebdavSource.loadLibs()) {
                        a.put(s);
                    }
                    o.put("libs", a);
                    // Digest 能列目录播不了歌，提前讲清楚比事后一头雾水强
                    if (r.wwwAuth != null
                            && r.wwwAuth.toLowerCase().contains("digest")) {
                        o.put("tip", "这台服务器使用 Digest 认证：列目录正常，"
                                + "但播放暂不支持（静态请求头对 Digest 无效），"
                                + "建议在 NAS 上改用 Basic 认证。");
                    }
                } catch (Throwable ignored) {}
                callback("onWebdavSaved", o);
            }
        });
    }

    /** 删除第 idx 台服务器。回调 {@code onWebdavDeleted({ok, libs:[...]})}。 */
    @JavascriptInterface
    public void webdavDel(final int idx) {
        bg(new Runnable() {
            public void run() {
                boolean ok = com.tingshu.app.source.impl.WebdavSource.delLib(idx);
                try { invalidateCatCache(); } catch (Throwable ignored) {}
                JSONObject o = new JSONObject();
                try {
                    o.put("ok", ok);
                    JSONArray a = new JSONArray();
                    for (JSONObject s : com.tingshu.app.source.impl.WebdavSource.loadLibs()) {
                        a.put(s);
                    }
                    o.put("libs", a);
                } catch (Throwable ignored) {}
                callback("onWebdavDeleted", o);
            }
        });
    }

    /** 把备份页那台 WebDAV 一键带进来。回调 onWebdavSaved(...)（同样会先验证）。 */
    @JavascriptInterface
    public void webdavImportBackup() {
        JSONObject lib = new JSONObject();
        try {
            lib.put("n", "我的 NAS");
            lib.put("u", com.tingshu.app.data.BackupCred.url());
            lib.put("p", "");
            lib.put("a", com.tingshu.app.data.BackupCred.user());
            lib.put("w", com.tingshu.app.data.BackupCred.pass());
            lib.put("t", com.tingshu.app.data.BackupCred.authType());
        } catch (Throwable ignored) {}
        webdavSave(lib.toString(), -1);
    }

    // ★ v4.11.0：首启合规弹窗「不同意」时由前端调既有 exitApp()（见 Bridge 尾部，
    //   只 finish 界面不杀进程），故这里不再重复定义。

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

    // ==================== ★ v3.8.0 备份与恢复 ====================
    //
    // 两种通道：
    //   · 本地备份 —— 用户自选一个目录（SAF），导出一个 .fxbak 包；从本地文件恢复。
    //   · 网络备份 —— WebDAV（坚果云 / Nextcloud / 群晖 NAS / 威联通 / Alist…），
    //                 上传 / 列出 / 下载 / 删除。
    //
    // 备份内容 = 书源（JSON 源 + jar/JS/Python 插件）+ 收藏与分组 +
    //            播放历史与进度 + 全部设置 + 搜索历史。**不含音频文件**。
    //
    // ★ 恢复是破坏性操作，流程上强制「先自动备份当前数据，再整体覆盖」，
    //   且 DB 走单个事务（失败自动回滚），见 BackupRestore。
    //
    // ★ localStorage 的处理：Java 读不到 WebView 的 DomStorage，必须用
    //   evaluateJavascript 异步取回。备份时「先取 localStorage，再打包」，
    //   恢复时反向（native 恢复完 → 回调前端写）。本类用一个 2 秒超时兜底，
    //   拿不到就置空继续，绝不因为 localStorage 卡住整个备份。

    /** 备份页进页面拉一次的总览 */
    @JavascriptInterface
    public void backupStatus() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    o.put("ok", true);
                    o.put("counts", com.tingshu.app.data.BackupRepo.stats());
                    o.put("hasDav", com.tingshu.app.data.BackupCred.configured());
                    o.put("davUrl", com.tingshu.app.data.BackupCred.url());
                    o.put("davDir", com.tingshu.app.data.BackupCred.dir());
                    o.put("autoBackups", com.tingshu.app.data.BackupRepo.autoBackupList());
                    o.put("versionName", versionName());
                    o.put("trustSsl", com.tingshu.app.data.Settings.davTrustAllSsl());
                } catch (Throwable t) {
                    try { o.put("ok", false); o.put("error", String.valueOf(t.getMessage())); }
                    catch (Throwable ignored) {}
                }
                callback("onBackupStatus", o);
            }
        });
    }

    /** 界面标签：当前版本名（用于生成默认备份文件名） */
    private String versionName() {
        try {
            return com.tingshu.app.core.App.ctx().getPackageManager()
                    .getPackageInfo(com.tingshu.app.core.App.ctx().getPackageName(), 0)
                    .versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private int versionCodeInt() {
        try {
            return com.tingshu.app.core.App.ctx().getPackageManager()
                    .getPackageInfo(com.tingshu.app.core.App.ctx().getPackageName(), 0)
                    .versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 打开系统文件夹选择器挑「本地备份存放目录」（交给 MainActivity 弹 SAF） */
    @JavascriptInterface
    public void pickBackupDir() {
        ui.post(new Runnable() {
            public void run() {
                try {
                    ((MainActivity) activity).pickBackupDir();
                } catch (Throwable t) {
                    js("window.onBackupDir({ok:false,cancel:false,error:'无法打开文件夹选择器'})");
                }
            }
        });
    }

    /** 打开系统文件选择器挑一个 .fxbak 备份包 */
    @JavascriptInterface
    public void pickBackupFile() {
        ui.post(new Runnable() {
            public void run() {
                try {
                    ((MainActivity) activity).pickBackupFile();
                } catch (Throwable t) {
                    js("window.onBackupFilePicked({ok:false,cancel:false,error:'无法打开文件选择器'})");
                }
            }
        });
    }

    /**
     * 备份到本地（SAF 目录）。
     *
     * <p>流程：取 localStorage（异步，2 秒超时）→ 打包到私有临时文件 →
     * {@code SafOut.createFile} + {@code SafOut.copyInto} 搬到用户选的目录 →
     * 校验字节数 → 删临时文件。
     */
    @JavascriptInterface
    public void backupLocal(final String treeUriStr, final String fileName) {
        // 先异步取 localStorage，拿到（或超时）后再进后台打包
        collectLocalStorage(2000, new LocalStorageCb() {
            public void onResult(final JSONObject ls) {
                bg(new Runnable() {
                    public void run() {
                        JSONObject o = new JSONObject();
                        java.io.File tmp = null;
                        try {
                            android.content.Context c = com.tingshu.app.core.App.ctx();
                            android.net.Uri tree = android.net.Uri.parse(treeUriStr);
                            String name = (fileName == null || fileName.length() == 0)
                                    ? ("fengxi-" + System.currentTimeMillis()
                                        + com.tingshu.app.data.BackupCodec.EXT)
                                    : fileName;

                            // 1) 打包
                            tmp = com.tingshu.app.data.BackupRepo.buildPackage(
                                    false, ls, versionName(), versionCodeInt());

                            // 2) 建目标文件
                            //    ★ 关键：SafOut.createFile 的第二个参数必须是
                            //      「document URI」而不是「tree URI」。
                            //      ACTION_OPEN_DOCUMENT_TREE 给回来的是 tree URI
                            //      （形如 content://.../tree/xxx，path 只有 2 段），
                            //      而 createFile 内部 findChild 会调
                            //      DocumentsContract.getDocumentId(parent) —— 对 tree
                            //      URI 取不到 documentId（要 ≥4 段），异常被吞后返回
                            //      null；随后 createDocument 以 tree URI 为父目录，
                            //      多数 provider 直接失败。这就是「本地备份写不出去」
                            //      的根因。正确做法是先用 buildDocumentUriUsingTree
                            //      把 tree URI 换成它自己的 document URI。
                            android.net.Uri dir =
                                    android.provider.DocumentsContract.buildDocumentUriUsingTree(
                                            tree,
                                            android.provider.DocumentsContract
                                                    .getTreeDocumentId(tree));
                            android.net.Uri target = com.tingshu.app.core.SafOut
                                    .createFile(c, dir, name, "application/octet-stream");
                            if (target == null) {
                                // 兜底：目录根写不进去时，退到子目录再试一次
                                //      （部分 ROM 的 Downloads 根目录不允许直接建文件）
                                android.net.Uri sub = com.tingshu.app.core.SafOut
                                        .ensureDir(c, tree, "枫兮听书备份");
                                if (sub != null) {
                                    target = com.tingshu.app.core.SafOut
                                            .createFile(c, sub, name, "application/octet-stream");
                                }
                            }
                            if (target == null) throw new IllegalStateException("无法在所选目录创建文件（可换一个文件夹重试）");

                            // 3) 流式搬过去
                            long wrote = com.tingshu.app.core.SafOut.copyInto(c, tmp, target);
                            if (wrote < 0) throw new IllegalStateException("写入失败（可能无写入权限）");

                            // 4) 校验字节数
                            long real = com.tingshu.app.core.SafOut.sizeOf(c, target);
                            if (real >= 0 && real != tmp.length()) {
                                throw new IllegalStateException("写入不完整（"
                                        + real + " / " + tmp.length() + " 字节）");
                            }

                            o.put("ok", true);
                            o.put("name", name);
                            o.put("size", tmp.length());
                            o.put("counts", com.tingshu.app.data.BackupRepo.stats());
                        } catch (Throwable t) {
                            try {
                                o.put("ok", false);
                                o.put("error", String.valueOf(t.getMessage()));
                            } catch (Throwable ignored) {}
                        } finally {
                            try { if (tmp != null) tmp.delete(); } catch (Throwable ignored) {}
                        }
                        callback("onBackupDone", o);
                    }
                });
            }
        });
    }

    /** 从本地文件恢复（content:// URI） */
    @JavascriptInterface
    public void restoreLocal(final String uriStr) {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                java.io.File tmp = null;
                try {
                    android.content.Context c = com.tingshu.app.core.App.ctx();
                    android.net.Uri uri = android.net.Uri.parse(uriStr);

                    // ★ 用流式读取，**不能用 MainActivity.readTextFromUri**（它有 2MB 上限）
                    java.io.InputStream in;
                    try {
                        in = c.getContentResolver().openInputStream(uri);
                    } catch (SecurityException se) {
                        // ★ v3.9.1：绝大多数"恢复失败"其实是这一步 ——
                        //   选文件时拿到的 content:// 授权是临时的，Activity 一被
                        //   重建就没了。这里不再丢一句干巴巴的"失败"，
                        //   而是明确告诉用户重新选一次文件。
                        throw new IllegalStateException(
                                "没有该备份文件的读取权限了，请重新选择一次备份文件再恢复");
                    }
                    if (in == null) {
                        throw new IllegalStateException(
                                "打不开这个备份文件，请重新选择一次（若文件已被移动或删除，请改为从新位置选择）");
                    }

                    java.io.File work = new java.io.File(c.getFilesDir(), "incoming");
                    if (!work.exists()) work.mkdirs();
                    tmp = new java.io.File(work, "incoming-" + System.currentTimeMillis()
                            + com.tingshu.app.data.BackupCodec.EXT);
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
                    try {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        long total = 0;
                        final long MAX = 64L * 1024 * 1024;
                        while ((n = in.read(buf)) > 0) {
                            total += n;
                            if (total > MAX) throw new IllegalStateException("备份包超过 64MB");
                            fo.write(buf, 0, n);
                        }
                        fo.flush();
                    } finally {
                        try { in.close(); } catch (Throwable ignored) {}
                        try { fo.close(); } catch (Throwable ignored) {}
                    }

                    o = com.tingshu.app.data.BackupRestore.restore(tmp);
                } catch (Throwable t) {
                    try {
                        if (o == null) o = new JSONObject();
                        o.put("ok", false);
                        o.put("error", String.valueOf(t.getMessage()));
                    } catch (Throwable ignored) {}
                } finally {
                    try { if (tmp != null) tmp.delete(); } catch (Throwable ignored) {}
                }
                // 恢复里的 localStorage 要交给前端写
                pushLocalStorageThen(o, "onRestoreDone");
            }
        });
    }

    // ---------- WebDAV 配置 ----------

    @JavascriptInterface
    public void davConfig() {
        bg(new Runnable() {
            public void run() {
                callback("onDavConfig", com.tingshu.app.data.BackupCred.load());
            }
        });
    }

    /** ★ v3.9.43：信任自签名证书开关（家庭 NAS 的 HTTPS 全是自签，需用户主动开启） */
    @JavascriptInterface
    public void setDavTrustSsl(final boolean on) {
        com.tingshu.app.data.Settings.setDavTrustAllSsl(on);
        ui.post(new Runnable() {
            public void run() {
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("ok", true);
                    o.put("on", on);
                    callback("onDavTrustSsl", o);
                } catch (Throwable ignored) {}
            }
        });
    }

    @JavascriptInterface
    public void davSave(final String url, final String user, final String pass, final String dir, final String auth) {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    com.tingshu.app.data.BackupCred.save(url, user, pass, dir, auth);
                    o.put("ok", true);
                    o.put("url", com.tingshu.app.data.BackupCred.url());
                    o.put("dir", com.tingshu.app.data.BackupCred.dir());
                } catch (Throwable t) {
                    try { o.put("ok", false); o.put("error", String.valueOf(t.getMessage())); }
                    catch (Throwable ignored) {}
                }
                callback("onDavSaved", o);
            }
        });
    }

    @JavascriptInterface
    public void davTest() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    String url = com.tingshu.app.data.BackupCred.url();
                    String user = com.tingshu.app.data.BackupCred.user();
                    String pass = com.tingshu.app.data.BackupCred.pass();
                    String auth = com.tingshu.app.data.BackupCred.authType();
                    if (url == null || url.length() == 0) {
                        o.put("ok", false);
                        o.put("error", "还没有配置服务器地址");
                    } else {
                        long t0 = System.currentTimeMillis();
                        // ★ v3.9.11 兼容坚果云：根目录 403 时自动降级
                        //   测备份目录 → 再不行做一次真实写入探测
                        com.tingshu.app.data.WebDavClient.Result r =
                                com.tingshu.app.data.WebDavClient.smartTest(url, user, pass,
                                        com.tingshu.app.data.BackupCred.dir(), auth);
                        long ms = System.currentTimeMillis() - t0;
                        o = com.tingshu.app.data.WebDavClient.toJson(r);
                        o.put("latencyMs", ms);
                        // 207 Multi-Status = 认证通过且是合法 WebDAV 根
                        if (r.ok || r.code == 207) {
                            o.put("ok", true);
                            o.put("error", "");
                        }
                    }
                } catch (Throwable t) {
                    try { o.put("ok", false); o.put("error", String.valueOf(t.getMessage())); }
                    catch (Throwable ignored) {}
                }
                callback("onDavTested", o);
            }
        });
    }

    /** 上传备份到 WebDAV（★ v4.15.0 功能锁：备份上行受试用开关控制，恢复不受限） */
    @JavascriptInterface
    public void davBackup(final String fileName) {
        collectLocalStorage(2000, new LocalStorageCb() {
            public void onResult(final JSONObject ls) {
                bg(new Runnable() {
                    public void run() {
                        if (!com.tingshu.app.core.FeatureGate.allow(
                                com.tingshu.app.core.FeatureGate.DAV_BACKUP)) {
                            JSONObject blocked = new JSONObject();
                            try { blocked.put("ok", false);
                                  blocked.put("error", com.tingshu.app.core.FeatureGate
                                      .denyMsg(com.tingshu.app.core.FeatureGate.DAV_BACKUP)); }
                            catch (Throwable ignored) {}
                            callback("onDavBackupDone", blocked);
                            return;
                        }
                        JSONObject o = new JSONObject();
                        java.io.File tmp = null;
                        try {
                            String base = com.tingshu.app.data.BackupCred.url();
                            String user = com.tingshu.app.data.BackupCred.user();
                            String pass = com.tingshu.app.data.BackupCred.pass();
                            String dir = com.tingshu.app.data.BackupCred.dir();
                            String auth = com.tingshu.app.data.BackupCred.authType();
                            if (base == null || base.length() == 0) {
                                throw new IllegalStateException("还没有配置服务器地址");
                            }

                            String name = (fileName == null || fileName.length() == 0)
                                    ? ("fengxi-" + System.currentTimeMillis()
                                        + com.tingshu.app.data.BackupCodec.EXT)
                                    : fileName;

                            // 1) 本地打包
                            tmp = com.tingshu.app.data.BackupRepo.buildPackage(
                                    false, ls, versionName(), versionCodeInt());

                            // 2) 确保远端目录存在
                            // ★ v3.9.41：建目录失败不再直接中断 —— 坚果云禁止
                            //   第三方应用 MKCOL（对已存在目录也回 403），目录
                            //   已在服务器上时 PUT 本来就能成功；PUT 才是关键。
                            com.tingshu.app.data.WebDavClient.Result dr =
                                    com.tingshu.app.data.WebDavClient.ensureDir(base, user, pass, dir, auth);
                            String dirWarn = dr.ok ? "" :
                                    ("建目录被服务器拒绝（" + dr.error + "）；"
                                     + "若目录已在坚果云里建好可忽略此提示，"
                                     + "否则请先在坚果云网页端创建该文件夹。");

                            // 3) 上传
                            String target = com.tingshu.app.data.WebDavClient.joinUrl(base, dir) + name;
                            com.tingshu.app.data.WebDavClient.Result ur =
                                    com.tingshu.app.data.WebDavClient.put(target, user, pass, tmp, auth);
                            if (!ur.ok) {
                                throw new IllegalStateException("上传失败：" + ur.error
                                        + (dr.ok ? "" : ("。" + dirWarn)));
                            }

                            o.put("ok", true);
                            o.put("name", name);
                            o.put("size", tmp.length());
                            o.put("remote", target);

                            // ★ v3.9.49：维护远端清单（GET 旧→合并→PUT 新）。
                            //   恢复列表在 PROPFIND 被链路拦成 501 的环境下，
                            //   靠这个清单文件兜底（GET/PUT 标准方法畅通）。
                            //   清单失败绝不影响备份本身。
                            try {
                                long fsize = tmp.length();
                                long nowAt = System.currentTimeMillis();
                                org.json.JSONArray arr = new org.json.JSONArray();
                                com.tingshu.app.data.WebDavClient.Result mg =
                                        com.tingshu.app.data.WebDavClient.getManifest(
                                                base, user, pass, dir, auth);
                                if (mg.ok && mg.bytes != null) {
                                    try {
                                        org.json.JSONObject mj = new org.json.JSONObject(
                                                new String(mg.bytes, "UTF-8"));
                                        org.json.JSONArray old = mj.optJSONArray("files");
                                        if (old != null) {
                                            for (int i = 0; i < old.length(); i++) {
                                                org.json.JSONObject e = old.optJSONObject(i);
                                                if (e == null) continue;
                                                // 同名条目由本次备份覆盖
                                                if (name.equals(e.optString("name"))) continue;
                                                arr.put(e);
                                            }
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                org.json.JSONObject cur = new org.json.JSONObject();
                                cur.put("name", name);
                                cur.put("size", fsize);
                                cur.put("at", nowAt);
                                arr.put(cur);
                                // 只保留最近 20 条，防清单无限膨胀
                                org.json.JSONArray trimmed = new org.json.JSONArray();
                                for (int i = Math.max(0, arr.length() - 20); i < arr.length(); i++) {
                                    trimmed.put(arr.get(i));
                                }
                                org.json.JSONObject mo = new org.json.JSONObject();
                                mo.put("ver", 1);
                                mo.put("files", trimmed);
                                com.tingshu.app.data.WebDavClient.putManifest(
                                        base, user, pass, dir, auth,
                                        mo.toString().getBytes("UTF-8"));
                            } catch (Throwable ignored) {}
                        } catch (Throwable t) {
                            try { o.put("ok", false); o.put("error", String.valueOf(t.getMessage())); }
                            catch (Throwable ignored) {}
                        } finally {
                            try { if (tmp != null) tmp.delete(); } catch (Throwable ignored) {}
                        }
                        callback("onDavBackupDone", o);
                    }
                });
            }
        });
    }

    /** 列出远端备份 */
    @JavascriptInterface
    public void davList() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    String base = com.tingshu.app.data.BackupCred.url();
                    String user = com.tingshu.app.data.BackupCred.user();
                    String pass = com.tingshu.app.data.BackupCred.pass();
                    String dir = com.tingshu.app.data.BackupCred.dir();
                    String auth = com.tingshu.app.data.BackupCred.authType();
                    if (base == null || base.length() == 0) {
                        o.put("ok", false);
                        o.put("error", "还没有配置服务器地址");
                        o.put("items", new org.json.JSONArray());
                    } else {
                        // ★ v3.9.42：改用 listWithErr —— 列表失败（网络/权限/路径）
                        //   不再被吞成空列表，恢复界面会直接显示失败原因
                        com.tingshu.app.data.WebDavClient.Result lr =
                                com.tingshu.app.data.WebDavClient.listWithErr(base, user, pass, dir, auth);
                        if (!lr.ok) {
                            // ★ v3.9.49：PROPFIND 被链路拦成 501 时的兜底 ——
                            //   GET 远端清单文件（标准方法，畅通）。
                            // ★ v3.9.50：清单不存在（404）时给出明确引导 ——
                            //   「先备份一次」是唯一前置步骤，别让用户对着
                            //   501 诊断码发呆。
                            int mgCode = -1;
                            try {
                                com.tingshu.app.data.WebDavClient.Result mg =
                                        com.tingshu.app.data.WebDavClient.getManifest(
                                                base, user, pass, dir, auth);
                                mgCode = mg.code;
                                if (mg.ok && mg.bytes != null) {
                                    org.json.JSONObject mj = new org.json.JSONObject(
                                            new String(mg.bytes, "UTF-8"));
                                    org.json.JSONArray fl = mj.optJSONArray("files");
                                    org.json.JSONArray marr = new org.json.JSONArray();
                                    java.text.SimpleDateFormat mfmt = new java.text.SimpleDateFormat(
                                            "MM-dd HH:mm", java.util.Locale.getDefault());
                                    if (fl != null) {
                                        for (int i = 0; i < fl.length(); i++) {
                                            org.json.JSONObject e = fl.optJSONObject(i);
                                            if (e == null) continue;
                                            String nm = e.optString("name", "");
                                            if (nm.length() == 0) continue;
                                            if (!nm.toLowerCase(java.util.Locale.ROOT).endsWith(
                                                    com.tingshu.app.data.BackupCodec.EXT)) continue;
                                            JSONObject it = new JSONObject();
                                            it.put("name", nm);
                                            it.put("size", e.optLong("size", -1));
                                            long at = e.optLong("at", 0);
                                            it.put("at", at);
                                            it.put("atText", at > 0
                                                    ? mfmt.format(new java.util.Date(at)) : "");
                                            marr.put(it);
                                        }
                                    }
                                    o.put("ok", true);
                                    o.put("dir", dir);
                                    o.put("items", marr);
                                    o.put("via", "manifest");
                                    callback("onDavList", o);
                                    return;
                                }
                            } catch (Throwable ignored) {}
                            o.put("ok", false);
                            o.put("error", "读取备份列表失败：" + lr.error
                                    + (mgCode == 404
                                        ? "。远端还没有清单文件：请先点一次「备份到网盘」，"
                                          + "成功后恢复列表即可用（清单走 GET/PUT，"
                                          + "不受 PROPFIND 拦截影响）"
                                        : (mgCode > 0
                                            ? "。清单文件读取也失败（HTTP " + mgCode + "）"
                                            : "")));
                            o.put("items", new org.json.JSONArray());
                        } else {
                            org.json.JSONArray arr = new org.json.JSONArray();
                            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                                    "MM-dd HH:mm", java.util.Locale.getDefault());
                            for (com.tingshu.app.data.WebDavClient.DavFile f : lr.files) {
                                if (f == null || f.dir) continue;
                                if (!f.name.toLowerCase(java.util.Locale.ROOT).endsWith(
                                        com.tingshu.app.data.BackupCodec.EXT)) continue;
                                JSONObject it = new JSONObject();
                                it.put("name", f.name);
                                it.put("size", f.size);
                                it.put("at", f.lastModified);
                                it.put("atText", f.lastModified > 0
                                        ? fmt.format(new java.util.Date(f.lastModified)) : "");
                                arr.put(it);
                            }
                            o.put("ok", true);
                            o.put("dir", dir);
                            o.put("items", arr);
                        }
                    }
                } catch (Throwable t) {
                    try {
                        o.put("ok", false);
                        o.put("error", String.valueOf(t.getMessage()));
                        if (!o.has("items")) o.put("items", new org.json.JSONArray());
                    } catch (Throwable ignored) {}
                }
                callback("onDavList", o);
            }
        });
    }

    /** 从 WebDAV 下载并恢复 */
    @JavascriptInterface
    public void davRestore(final String remoteName) {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                java.io.File tmp = null;
                try {
                    android.content.Context c = com.tingshu.app.core.App.ctx();
                    String base = com.tingshu.app.data.BackupCred.url();
                    String user = com.tingshu.app.data.BackupCred.user();
                    String pass = com.tingshu.app.data.BackupCred.pass();
                    String dir = com.tingshu.app.data.BackupCred.dir();
                    String auth = com.tingshu.app.data.BackupCred.authType();
                    if (base == null || base.length() == 0) {
                        throw new IllegalStateException("还没有配置服务器地址");
                    }
                    String safe = new java.io.File(remoteName == null ? "" : remoteName).getName();
                    if (safe.length() == 0) throw new IllegalStateException("文件名无效");

                    java.io.File work = new java.io.File(c.getFilesDir(), "incoming");
                    if (!work.exists()) work.mkdirs();
                    tmp = new java.io.File(work, "dav-" + System.currentTimeMillis()
                            + com.tingshu.app.data.BackupCodec.EXT);

                    String url = com.tingshu.app.data.WebDavClient.joinUrl(base, dir) + safe;
                    com.tingshu.app.data.WebDavClient.Result dr =
                            com.tingshu.app.data.WebDavClient.getToFile(url, user, pass, tmp, auth);
                    if (!dr.ok) throw new IllegalStateException("下载失败：" + dr.error);

                    o = com.tingshu.app.data.BackupRestore.restore(tmp);
                } catch (Throwable t) {
                    try {
                        o.put("ok", false);
                        o.put("error", String.valueOf(t.getMessage()));
                    } catch (Throwable ignored) {}
                } finally {
                    try { if (tmp != null) tmp.delete(); } catch (Throwable ignored) {}
                }
                pushLocalStorageThen(o, "onRestoreDone");
            }
        });
    }

    /** 删除远端备份 */
    @JavascriptInterface
    public void davDelete(final String remoteName) {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    String base = com.tingshu.app.data.BackupCred.url();
                    String user = com.tingshu.app.data.BackupCred.user();
                    String pass = com.tingshu.app.data.BackupCred.pass();
                    String dir = com.tingshu.app.data.BackupCred.dir();
                    String auth = com.tingshu.app.data.BackupCred.authType();
                    String safe = new java.io.File(remoteName == null ? "" : remoteName).getName();
                    if (safe.length() == 0) throw new IllegalStateException("文件名无效");

                    String url = com.tingshu.app.data.WebDavClient.joinUrl(base, dir) + safe;
                    com.tingshu.app.data.WebDavClient.Result r =
                            com.tingshu.app.data.WebDavClient.delete(url, user, pass, auth);
                    o.put("ok", r.ok);
                    o.put("name", safe);
                    if (!r.ok) o.put("error", r.error);
                    // ★ v3.9.49：删除成功后同步从清单里移除该条目（GET→过滤→PUT）
                    if (r.ok) {
                        try {
                            com.tingshu.app.data.WebDavClient.Result mg =
                                    com.tingshu.app.data.WebDavClient.getManifest(
                                            base, user, pass, dir, auth);
                            if (mg.ok && mg.bytes != null) {
                                org.json.JSONObject mj = new org.json.JSONObject(
                                        new String(mg.bytes, "UTF-8"));
                                org.json.JSONArray old = mj.optJSONArray("files");
                                org.json.JSONArray kept = new org.json.JSONArray();
                                if (old != null) {
                                    for (int i = 0; i < old.length(); i++) {
                                        org.json.JSONObject e = old.optJSONObject(i);
                                        if (e == null) continue;
                                        if (safe.equals(e.optString("name"))) continue;
                                        kept.put(e);
                                    }
                                }
                                mj.put("files", kept);
                                com.tingshu.app.data.WebDavClient.putManifest(
                                        base, user, pass, dir, auth,
                                        mj.toString().getBytes("UTF-8"));
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    try { o.put("ok", false); o.put("error", String.valueOf(t.getMessage())); }
                    catch (Throwable ignored) {}
                }
                callback("onDavDeleted", o);
            }
        });
    }

    // ---------- 恢复前自动备份 ----------

    @JavascriptInterface
    public void autoBackupList() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    o.put("ok", true);
                    o.put("items", com.tingshu.app.data.BackupRepo.autoBackupList());
                } catch (Throwable t) {
                    try { o.put("ok", false); } catch (Throwable ignored) {}
                }
                callback("onAutoBackupList", o);
            }
        });
    }

    /** 回退到某个恢复前自动备份 */
    @JavascriptInterface
    public void autoBackupRestore(final String name) {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    java.io.File f = com.tingshu.app.data.BackupRepo.autoBackupFile(name);
                    if (f == null) throw new IllegalStateException("找不到该自动备份");
                    // 注意：这里恢复的包本身就是自动备份，恢复流程仍会再自动备份一次
                    //      （多一层保险，代价是几 KB，可接受）
                    o = com.tingshu.app.data.BackupRestore.restore(f);
                } catch (Throwable t) {
                    try {
                        o.put("ok", false);
                        o.put("error", String.valueOf(t.getMessage()));
                    } catch (Throwable ignored) {}
                }
                pushLocalStorageThen(o, "onRestoreDone");
            }
        });
    }

    // ---------- localStorage 桥 ----------

    private interface LocalStorageCb { void onResult(JSONObject ls); }

    /**
     * 异步取前端 localStorage 的 4 个 {@code fx_} 键。
     *
     * <p><b>为什么要超时</b>：{@code evaluateJavascript} 的回调依赖 WebView
     * 主线程消息循环。如果此刻 WebView 正好在忙（大页面渲染/GC），
     * 回调可能迟迟不来。没有超时就会「点备份没反应」。
     * 超时后按「没有 localStorage」继续，用户数据主体照样备份成功。
     */
    private void collectLocalStorage(final long timeoutMs, final LocalStorageCb cb) {
        final boolean[] done = new boolean[]{false};
        final LocalStorageCb once = new LocalStorageCb() {
            public void onResult(JSONObject ls) {
                synchronized (done) {
                    if (done[0]) return;
                    done[0] = true;
                }
                cb.onResult(ls);
            }
        };

        ui.post(new Runnable() {
            public void run() {
                try {
                    final String js = "(function(){try{var k=['fx_session_v1',"
                            + "'fx_last_home_src_v1','fx_chap_view_v1','fx_browse_v1'];"
                            + "var o={};for(var i=0;i<k.length;i++){var v=localStorage.getItem(k[i]);"
                            + "if(v!==null)o[k[i]]=v;}return JSON.stringify(o);}catch(e){return '{}';}})()";
                    web.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
                        public void onReceiveValue(String value) {
                            once.onResult(parseLsJson(value));
                        }
                    });
                } catch (Throwable t) {
                    once.onResult(new JSONObject());
                }
            }
        });

        // 超时兜底
        ui.postDelayed(new Runnable() {
            public void run() {
                once.onResult(new JSONObject());
            }
        }, timeoutMs);
    }

    /** evaluateJavascript 回传的是「带引号的 JSON 字符串」，要剥一层 */
    private JSONObject parseLsJson(String raw) {
        try {
            if (raw == null) return new JSONObject();
            String s = raw.trim();
            if (s.length() == 0 || "null".equals(s)) return new JSONObject();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                s = s.substring(1, s.length() - 1);
                s = s.replace("\\\"", "\"").replace("\\\\", "\\")
                        .replace("\\n", "\n").replace("\\/", "/");
            }
            JSONObject o = com.tingshu.app.core.Json.obj(s);
            return o == null ? new JSONObject() : o;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /**
     * 恢复完成后：若结果里带了 localStorage，先让前端写进去，再回调最终函数。
     * <p>顺序很重要 —— 前端写 localStorage 是同步的，写完后信息才是完整的。
     */
    private void pushLocalStorageThen(final JSONObject result, final String finalFn) {
        JSONObject ls = null;
        try { ls = result.optJSONObject("localStorage"); } catch (Throwable ignored) {}
        if (ls != null && ls.length() > 0) {
            try { result.remove("localStorage"); } catch (Throwable ignored) {}
            js("window.onRestoreLocalStorage(" + ls.toString() + ")");
        }
        callback(finalFn, result);
    }

    // ==================== ★ v3.9.0 自定义源：本地书源 + 网盘接口 ====================

    /**
     * 扫描本机音频（MediaStore），按文件夹分书。
     *
     * <p>没有存储读取权限时回传 {@code needPermission:true}，前端据此
     * 调 {@link #requestStoragePerm()} 申请，批了再扫一次。
     */
    @JavascriptInterface
    public void localScan() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    List<Book> books =
                            com.tingshu.app.source.impl.LocalSource.scan(true);
                    String err = com.tingshu.app.source.impl.LocalSource.lastError();
                    o.put("ok", true);
                    o.put("count", books.size());
                    o.put("needPermission", "no-permission".equals(err));
                    o.put("error", err == null ? "" : err);
                    // ★ v3.9.1：顺带回传当前生效的扫描目录（"" = 全机）
                    o.put("root", com.tingshu.app.source.impl.LocalSource.rootDir());
                } catch (Throwable t) {
                    try {
                        o.put("ok", false);
                        o.put("error", String.valueOf(t.getMessage()));
                    } catch (Throwable ignored) {}
                }
                // ★ v4.2.0：权限不足时**自动弹出系统授权框**（用户点扫描不用再
                //   多按一次「去授权」）。带 3 秒节流，避免权限回调没回来期间
                //   反复点扫描导致连环弹窗；拒绝后走原有 needPermission 提示。
                if (o.optBoolean("needPermission") && activity != null
                        && (activity instanceof com.tingshu.app.MainActivity)) {
                    final long now = System.currentTimeMillis();
                    if (now - lastAutoPermAt > 3000L) {
                        lastAutoPermAt = now;
                        ui.post(new Runnable() {
                            public void run() {
                                try {
                                    ((com.tingshu.app.MainActivity) activity)
                                            .requestStoragePermission();
                                } catch (Throwable ignored) {}
                            }
                        });
                    }
                }
                callback("onLocalScan", o);
            }
        });
    }

    /**
     * ★ v3.9.1：Python 源自检（解释器版本 / sys.path / 关键模块导入结果）。
     *
     * <p>.py 源用不了时，光看一句"Python 运行时不可用"根本没法定位：
     * 到底是解释器没起来、还是缺 lxml / Crypto 这类三方模块。这个接口把
     * Python 侧 self_test 的结果原样回传，诊断页一看就知道卡在哪一步。
     */
    @JavascriptInterface
    public void pySelfTest() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = com.tingshu.app.python.PythonHost.selfTest(
                        com.tingshu.app.core.App.ctx());
                callback("onPySelfTest", o == null ? new JSONObject() : o);
            }
        });
    }

    /**
     * ★ v3.9.1：重新加载所有 .py 源。
     *
     * <p>用于「源管理 → Python 源」分组里的「重新加载」：解释器起来得比
     * 源表登记晚时，手工催一次补登记（正常情况下这个补登记是自动的）。
     */
    @JavascriptInterface
    public void pyReload() {
        bg(new Runnable() {
            public void run() {
                try {
                    com.tingshu.app.source.SourceRegistry.reload();
                } catch (Throwable ignored) {}
                ui.post(new Runnable() {
                    public void run() { listSources(); }
                });
            }
        });
    }

    /**
     * 打开系统目录选择器，给本地书源自选扫描根目录。
     *
     * <p>结果经 {@code window.onLocalDir({ok,path,name,cancel})} 回传：
     * path 为空说明这台机器的 ROM 用了私有 documentId、还原不出真实路径，
     * 前端会退化成"手动粘贴路径"（走 {@link #localSetDir}）。
     */
    @JavascriptInterface
    public void localPickDir() {
        ui.post(new Runnable() {
            public void run() {
                try {
                    if (activity instanceof com.tingshu.app.MainActivity) {
                        ((com.tingshu.app.MainActivity) activity).pickLocalDir();
                    } else {
                        js("window.onLocalDir({ok:false,cancel:false,error:'no-activity'})");
                    }
                } catch (Throwable t) {
                    js("window.onLocalDir({ok:false,cancel:false,error:'fail'})");
                }
            }
        });
    }

    /**
     * 设置本地书源的扫描根目录。
     *
     * @param dir 绝对路径；空串 = 扫全机（恢复默认）
     */
    @JavascriptInterface
    public void localSetDir(final String dir) {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    String v = com.tingshu.app.source.impl.LocalSource.setRootDir(dir);
                    o.put("ok", true);
                    o.put("root", v);
                    // 换了目录就重扫一次，让用户立刻看到结果
                    try {
                        com.tingshu.app.source.impl.LocalSource.scan(true);
                    } catch (Throwable ignored) {}
                    o.put("count", com.tingshu.app.source.impl.LocalSource.cached().size());
                    o.put("needPermission", "no-permission".equals(
                            com.tingshu.app.source.impl.LocalSource.lastError()));
                } catch (Throwable t) {
                    try {
                        o.put("ok", false);
                        o.put("error", String.valueOf(t.getMessage()));
                    } catch (Throwable ignored) {}
                }
                callback("onLocalDir", o);
            }
        });
    }

    /** 本地书列表（用扫描缓存，不触发重扫；自定义源页展示用） */
    @JavascriptInterface
    public void localBooks() {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    JSONArray arr = new JSONArray();
                    for (Book b : com.tingshu.app.source.impl.LocalSource.cached()) {
                        arr.put(b.toJson());
                    }
                    o.put("ok", true);
                    o.put("books", arr);
                    // ★ v3.9.1：当前生效的扫描目录（"" = 全机）
                    o.put("root", com.tingshu.app.source.impl.LocalSource.rootDir());
                } catch (Throwable t) {
                    try { o.put("ok", false); } catch (Throwable ignored) {}
                }
                callback("onLocalBooks", o);
            }
        });
    }

    /** 申请存储读取权限（本地书源扫描用；API 33+ 用 READ_MEDIA_AUDIO） */
    @JavascriptInterface
    public void requestStoragePerm() {
        ui.post(new Runnable() {
            public void run() {
                try {
                    if (activity instanceof com.tingshu.app.MainActivity) {
                        ((com.tingshu.app.MainActivity) activity).requestStoragePermission();
                    } else {
                        js("window.onLocalPerm({ok:false,error:'no-activity'})");
                    }
                } catch (Throwable t) {
                    js("window.onLocalPerm({ok:false,error:'fail'})");
                }
            }
        });
    }

    /** 保存网盘 Cookie（platform = pan_ali / pan_baidu / …） */
    @JavascriptInterface
    public void panSave(final String platform, final String cookie) {
        if (panLocked()) return;   // ★ v4.18.0 功能锁
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    com.tingshu.app.pan.PanResolver.saveCookie(platform, cookie);
                    o.put("ok", true);
                    o.put("platform", platform);
                    o.put("ready", com.tingshu.app.pan.PanResolver.configured(platform));
                } catch (Throwable t) {
                    try {
                        o.put("ok", false);
                        o.put("error", String.valueOf(t.getMessage()));
                    } catch (Throwable ignored) {}
                }
                callback("onPanSaved", o);
            }
        });
    }

    /** 清除网盘 Cookie */
    @JavascriptInterface
    public void panClear(final String platform) {
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    com.tingshu.app.pan.PanResolver.saveCookie(platform, "");
                    o.put("ok", true);
                    o.put("platform", platform);
                } catch (Throwable t) {
                    try { o.put("ok", false); } catch (Throwable ignored) {}
                }
                callback("onPanSaved", o);
            }
        });
    }

    // ==================== ★ v4.2.0 网盘登录（免粘贴 Cookie） ====================

    /**
     * 115 扫码登录：取二维码 → 后台轮询 → 确认后自动拿 Cookie 落库。
     *
     * <p>回调 {@code onPanQr}：
     * <ul>
     *   <li>{ok:true, qr:"https://..."} —— 前端渲染二维码</li>
     *   <li>{ok:true, state:0/1, msg:".."} —— 等待扫码 / 已扫码</li>
     *   <li>{ok:true, done:true, platform} —— 登录成功，Cookie 已存</li>
     *   <li>{ok:true, expired:true, msg} —— 二维码过期，前端提示重开</li>
     *   <li>{ok:false, error} —— 网络/接口错误</li>
     * </ul>
     * 轮询最长 120 秒（60 次 × 2s），期间调 {@link #panQrStop()} 可随时取消。
     */
    @JavascriptInterface
    public void panQrStart(final String platform) {
        if (panLocked()) return;   // ★ v4.18.0 功能锁
        if (!"pan_115".equals(platform)) {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", false);
                o.put("platform", platform);
                o.put("error", "该平台暂不支持扫码登录");
            } catch (Throwable ignored) {}
            callback("onPanQr", o);
            return;
        }
        panQrStopped = false;
        bg(new Runnable() {
            public void run() {
                String uid = "", time = "", sign = "";
                try {
                    JSONObject t = com.tingshu.app.pan.PanLogin.qr115Token();
                    uid = t.optString("uid", "");
                    time = String.valueOf(t.optLong("time", 0L));
                    sign = t.optString("sign", "");
                    String qr = t.optString("qrcode", "");
                    if (qr.length() == 0) {
                        qr = "https://qrcodeapi.115.com/api/1.0/mac/1.0/qrcode?uid=" + uid;
                    }
                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("platform", platform);
                    o.put("qr", qr);
                    callback("onPanQr", o);
                } catch (Throwable e) {
                    callback("onPanQr", errObj(platform, String.valueOf(e.getMessage())));
                    return;
                }
                // 轮询扫码状态（最长 120 秒）
                for (int i = 0; i < 60 && !panQrStopped; i++) {
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    if (panQrStopped) return;
                    int st;
                    try {
                        st = com.tingshu.app.pan.PanLogin.qr115Status(uid, time, sign);
                    } catch (Throwable e) {
                        continue;   // 单次网络抖动不终止，下一轮再试
                    }
                    if (st == 1) {
                        JSONObject o = new JSONObject();
                        try {
                            o.put("ok", true);
                            o.put("platform", platform);
                            o.put("state", 1);
                            o.put("msg", "已扫码，请在手机上确认登录");
                        } catch (Throwable ignored) {}
                        callback("onPanQr", o);
                        continue;
                    }
                    if (st < 0) {
                        JSONObject o = new JSONObject();
                        try {
                            o.put("ok", true);
                            o.put("platform", platform);
                            o.put("expired", true);
                            o.put("msg", "二维码已过期，请重新打开扫码登录");
                        } catch (Throwable ignored) {}
                        callback("onPanQr", o);
                        return;
                    }
                    if (st >= 2) {
                        try {
                            String ck = com.tingshu.app.pan.PanLogin.qr115Cookie(uid);
                            com.tingshu.app.pan.PanResolver.saveCookie(platform, ck);
                            JSONObject o = new JSONObject();
                            o.put("ok", true);
                            o.put("platform", platform);
                            o.put("done", true);
                            callback("onPanQr", o);
                        } catch (Throwable e) {
                            // 确认了但取 Cookie 失败（偶发在刚扫码瞬间）——
                            // 提示后再试一轮，仍失败才报错
                            if (i < 58) continue;
                            callback("onPanQr", errObj(platform,
                                    "已确认但取 Cookie 失败：" + e.getMessage()));
                        }
                        return;
                    }
                    // st == 0：继续等
                }
                if (!panQrStopped) {
                    JSONObject o = new JSONObject();
                    try {
                        o.put("ok", true);
                        o.put("platform", platform);
                        o.put("expired", true);
                        o.put("msg", "等待超时，请重新打开扫码登录");
                    } catch (Throwable ignored) {}
                    callback("onPanQr", o);
                }
            }
        });
    }

    /**
     * v4.5.0：扫描已登录网盘内的有声书（DFS 找含音频的文件夹）。
     *
     * <p>回调 {@code onPanScan}：{ok:true, platform, books:[{name, files:[…]}]}
     * 或 {ok:false, error}。阿里云盘网页接口有签名风控，返回 ok:false 说明原因。
     */
    @JavascriptInterface
    public void panScan(final String platform) {
        if (panLocked()) return;   // ★ v4.18.0 功能锁
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    java.util.List<com.tingshu.app.pan.PanResolver.DiskBook> books =
                            com.tingshu.app.pan.PanResolver.scanDisk(platform);
                    JSONArray arr = new JSONArray();
                    for (com.tingshu.app.pan.PanResolver.DiskBook b : books) {
                        JSONObject bj = new JSONObject();
                        bj.put("name", b.name);
                        JSONArray fs = new JSONArray();
                        for (com.tingshu.app.pan.PanResolver.PanFile f : b.files) {
                            JSONObject fj = new JSONObject();
                            fj.put("id", f.id);
                            fj.put("name", f.name);
                            fj.put("size", f.size);
                            fj.put("dir", f.dir);
                            fj.put("token", f.token == null ? "" : f.token);
                            fs.put(fj);
                        }
                        bj.put("files", fs);
                        arr.put(bj);
                    }
                    o.put("ok", true);
                    o.put("platform", platform);
                    o.put("books", arr);
                } catch (Throwable t) {
                    try {
                        o.put("ok", false);
                        o.put("platform", platform);
                        o.put("error", String.valueOf(t.getMessage()));
                    } catch (Throwable ignored) {}
                }
                callback("onPanScan", o);
            }
        });
    }

    /** 停止扫码轮询（关弹窗 / 换二维码） */
    @JavascriptInterface
    public void panQrStop() {
        panQrStopped = true;
    }

    /**
     * v4.4.0：网盘「网页登录」——内嵌 WebView 打开网盘官方登录页，
     * 用户正常登录后自动抓 Cookie 保存，成功后回调 onPanSaved。
     */
    @JavascriptInterface
    public void panWebLogin(final String platform) {
        if (panLocked()) return;   // ★ v4.18.0 功能锁
        activity.runOnUiThread(new Runnable() {
            public void run() {
                com.tingshu.app.pan.PanWebLogin.show(activity, platform, new Runnable() {
                    public void run() {
                        JSONObject o = new JSONObject();
                        try {
                            o.put("ok", true);
                            o.put("platform", platform);
                            o.put("ready", true);
                        } catch (Throwable ignored) {}
                        callback("onPanSaved", o);
                    }
                });
            }
        });
    }

    /**
     * 天翼云盘账号密码登录（RSA 加密提交，成功后自动取 COOKIE_LOGIN_USER）。
     *
     * <p>回调 {@code onPanPwdLogin}：{ok:true, platform} 或 {ok:false, error}。
     */
    @JavascriptInterface
    public void panPwdLogin(final String platform, final String user, final String pwd) {
        if (panLocked()) return;   // ★ v4.18.0 功能锁
        if (!"pan_tianyi".equals(platform)) {
            callback("onPanPwdLogin", errObj(platform, "该平台暂不支持密码登录"));
            return;
        }
        final String u = (user == null) ? "" : user.trim();
        final String p = (pwd == null) ? "" : pwd;
        if (u.length() == 0 || p.length() == 0) {
            callback("onPanPwdLogin", errObj(platform, "账号和密码都不能为空"));
            return;
        }
        bg(new Runnable() {
            public void run() {
                try {
                    String ck = com.tingshu.app.pan.PanLogin.login189(u, p);
                    com.tingshu.app.pan.PanResolver.saveCookie(platform, ck);
                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("platform", platform);
                    callback("onPanPwdLogin", o);
                } catch (Throwable e) {
                    callback("onPanPwdLogin",
                            errObj(platform, String.valueOf(e.getMessage())));
                }
            }
        });
    }

    private JSONObject errObj(String platform, String error) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("platform", platform == null ? "" : platform);
            o.put("error", error == null ? "" : error);
        } catch (Throwable ignored) {}
        return o;
    }

    /**
     * 解析网盘分享链接 → 文件清单。
     *
     * <p>失败时回传 {@code ok:false + error}（Cookie 失效 / 接口改版 /
     * 链接无效，错误信息由 PanResolver 按平台给出，不糊弄）。
     */
    @JavascriptInterface
    public void panParse(final String platform, final String shareUrl) {
        if (panLocked()) return;   // ★ v4.18.0 功能锁
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    o.put("ok", true);
                    o.put("platform", platform);
                    o.put("shareUrl", shareUrl == null ? "" : shareUrl);
                    JSONArray files = new JSONArray();
                    for (com.tingshu.app.pan.PanResolver.PanFile f :
                            com.tingshu.app.pan.PanResolver.parseShare(platform, shareUrl)) {
                        JSONObject fj = new JSONObject();
                        fj.put("id", f.id);
                        fj.put("name", f.name);
                        fj.put("size", f.size);
                        fj.put("dir", f.dir);
                        // ★ token 必须原样带回：115 的 pickcode、夸克的 share_fid_token
                        //   都在这里，下载时 panPlay 要用（阿里是全局 share_token，
                        //   下载时现取，不依赖这个字段）
                        fj.put("token", f.token == null ? "" : f.token);
                        // ★ v4.10.0：父目录链（"鬼吹灯/第1卷"）——前端靠它把
                        //   文件夹整本入库/点开选集
                        fj.put("path", f.path == null ? "" : f.path);
                        files.put(fj);
                    }
                    o.put("files", files);
                } catch (Throwable t) {
                    try {
                        o.put("ok", false);
                        o.put("platform", platform);
                        o.put("error", String.valueOf(t.getMessage()));
                    } catch (Throwable ignored) {}
                }
                callback("onPanFiles", o);
            }
        });
    }

    /**
     * 播放网盘文件（勾选的 filesJson 走既有播放管线）。
     *
     * <p>与 {@link #playQueue} 同构：先 setQueue（写历史/自动下一集/
     * 通知栏都靠它），再经 Aggregator 取链播放。直链每次现取、不落库
     * （网盘直链有时效），换源自愈对网盘源同样生效。
     */
    @JavascriptInterface
    public void panPlay(final String platform, final String shareUrl, final String filesJson) {
        if (panLocked()) return;   // ★ v4.18.0 功能锁
        bg(new Runnable() {
            public void run() {
                try {
                    List<com.tingshu.app.pan.PanResolver.PanFile> picked =
                            new ArrayList<com.tingshu.app.pan.PanResolver.PanFile>();
                    JSONArray arr = new JSONArray(filesJson == null ? "[]" : filesJson);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject fj = arr.optJSONObject(i);
                        if (fj == null) continue;
                        com.tingshu.app.pan.PanResolver.PanFile f =
                                new com.tingshu.app.pan.PanResolver.PanFile();
                        f.id = fj.optString("id", "");
                        f.name = fj.optString("name", "");
                        f.size = fj.optLong("size", 0);
                        f.dir = fj.optBoolean("dir", false);
                        f.token = fj.optString("token", "");
                        if (!f.dir) picked.add(f);
                    }
                    if (picked.isEmpty()) {
                        callback("onPlayError", new JSONObject());
                        return;
                    }
                    // ★ v4.6.0 修复串书：盘内书原来用书名原文当 id，书名里含
                    //   「/」时 replaceAll(".*/","") 会把不同书截成同一个 id
                    //   （「三体/上」「三体/下」都变「disk_上」级联撞 key）——
                    //   历史/续播/直链缓存跟着串，表现就是「播的是别的书」。
                    //   现在对 id 做消毒：非法字符全部换成 _，不同书必不同 id。
                    boolean disk = shareUrl != null && shareUrl.startsWith("__disk__|");
                    String bookName = disk
                            ? shareUrl.substring("__disk__|".length())
                            : com.tingshu.app.pan.PanResolver.nameOf(platform) + " · 分享";
                    // ★ v4.7.1：盘内书 id 统一走 PanSource.diskBookId（含 "disk_" 前缀），
                    //   与 PanSource.detail 读章节缓存的 key 完全一致——
                    //   v4.7.0 写入/读取两边各算各的，key 对不上 → 不能选集。
                    //   非盘内（分享链接）书仍用 "share_" 前缀。
                    final Book b = new Book(platform,
                            disk ? com.tingshu.app.pan.PanSource.diskBookId(shareUrl)
                                 : "share_" + (shareUrl == null ? ""
                                       : shareUrl.replaceAll(".*/", "")
                                                .replaceAll("[^0-9A-Za-z\\u4e00-\\u9fa5_-]", "_")),
                            bookName);
                    b.url = com.tingshu.app.pan.PanSource.PREFIX + shareUrl;
                    b.intro = disk ? "来自网盘扫描" : (shareUrl == null ? "" : shareUrl);

                    final List<com.tingshu.app.data.Models.Chapter> chs =
                            new ArrayList<com.tingshu.app.data.Models.Chapter>();
                    int i = 1;
                    for (com.tingshu.app.pan.PanResolver.PanFile f : picked) {
                        com.tingshu.app.data.Models.Chapter ch =
                                new com.tingshu.app.data.Models.Chapter();
                        ch.title = f.name;
                        ch.url = com.tingshu.app.pan.PanSource.encChapter(
                                platform, shareUrl, f.id, f.token);
                        ch.index = i++;
                        chs.add(ch);
                    }
                    // ★ v4.7.0：盘内书把整份章节表写进缓存（内存+落盘），
                    //   选集/历史续播/详情页才有章节数据（否则 detail 拿不到 → 不能选集）
                    if (disk) {
                        com.tingshu.app.pan.PanSource.putDiskChapters(b.bookId, chs);
                    }
                    final BookDetail d = new BookDetail(b);
                    d.chapters = chs;
                    d.total = chs.size();

                    ui.post(new Runnable() {
                        public void run() {
                            player.setQueue(b, chs, 0);
                            // ★ v4.8.0：把章节表同步给前端（数据形状同详情页
                            //   onDetail）——之前 App.chapters 只有从播放记录
                            //   进详情页才会填，直接点播放进播放页时选集页
                            //   永远「还没有章节数据」。现在播放即同步。
                            JSONObject dd = new JSONObject();
                            try {
                                JSONObject bo = new JSONObject();
                                bo.put("title", bookName);
                                bo.put("source", platform);
                                bo.put("url", b.url);
                                dd.put("book", bo);
                                JSONArray ca = new JSONArray();
                                for (com.tingshu.app.data.Models.Chapter c : chs) {
                                    JSONObject co = new JSONObject();
                                    co.put("title", c.title);
                                    co.put("url", c.url);
                                    co.put("index", c.index);
                                    ca.put(co);
                                }
                                dd.put("chapters", ca);
                            } catch (Throwable ignored) {}
                            callback("onDetail", dd);
                        }
                    });

                    // ★ v4.6.0 修复「播的不是网盘资源」：网盘书不再走聚合
                    //   audioWithFallback（按候选源顺序试，异常时静默换），
                    //   而是**只走本网盘源**取直链——网盘书换到互联网源毫无意义，
                    //   失败就如实报错。直链缓存/自愈不受影响（取链缓存 key
                    //   以书 id 隔离，上面已消毒）。
                    final String[] out = new String[2];
                    @SuppressWarnings("unchecked")
                    final Map<String, String>[] outHdr = new Map[1];
                    String urlTmp = null;
                    Throwable chainErr = null;
                    try {
                        com.tingshu.app.pan.PanSource ps =
                                new com.tingshu.app.pan.PanSource(platform);
                        com.tingshu.app.data.Models.Chapter ch0 = chs.get(0);
                        urlTmp = ps.audioUrl(ch0, b);
                        out[0] = platform;
                        out[1] = ch0.url;
                        if (urlTmp != null && urlTmp.length() > 0) {
                            try { outHdr[0] = ps.audioHeaders(urlTmp); } catch (Throwable ignored) {}
                        }
                    } catch (Throwable t) {
                        chainErr = t;
                    }
                    if (urlTmp == null || urlTmp.length() == 0) {
                        JSONObject e = new JSONObject();
                        try {
                            e.put("error", chainErr == null
                                    ? "网盘直链获取失败（Cookie 失效或文件被删？）"
                                    : "网盘取链失败：" + chainErr.getMessage());
                        } catch (Throwable ignored) {}
                        callback("onPlayError", e);
                        return;
                    }
                    final String url = urlTmp;
                    final String usedSrc = platform;
                    final Map<String, String> hdr = outHdr[0];
                    final String title = chs.get(0).title;
                    ui.post(new Runnable() {
                        public void run() {
                            player.play(usedSrc, null, title, url, hdr);
                            JSONObject o = new JSONObject();
                            try {
                                o.put("url", url);
                                o.put("title", title);
                                o.put("source", usedSrc);
                                o.put("sourceName", BridgeSource.sourceNameOf(usedSrc));
                                o.put("index", 1);
                                o.put("total", chs.size());
                                o.put("playing", true);
                            } catch (Throwable ignored) {}
                        callback("onPlaying", o);
                    }
                });
                } catch (Throwable t) {
                    // ★ v4.9.4：外层兜底也别丢原因
                    JSONObject e = new JSONObject();
                    try { e.put("error", String.valueOf(t.getMessage())); } catch (Throwable ignored) {}
                    callback("onPlayError", e);
                }
            }
        });
    }

    /**
     * ★ v4.9.3：打开盘内书 —— 只准备章节、不起播。
     *
     * <p>首页网盘书架点书改成「进选集页」而不是直接播第一集。盘内书的章节
     * 不在网盘详情接口里（那是分享链接才有的能力），只能由扫描结果还原：
     * 这里把整份文件表编码成 chapter.url 并写入章节缓存，
     * 详情页（{@link com.tingshu.app.pan.PanSource#detail}）就能读回章节列表。
     *
     * <p>回传 {@code onPanDiskBook {ok, book, chapters, total}}，前端据此
     * 直接渲染详情页（无需再等一次 detail 请求）。
     */
    @JavascriptInterface
    public void panDiskBook(final String platform, final String bookName, final String filesJson) {
        panDiskBook(platform, bookName, filesJson, "");
    }

    /**
     * ★ v4.9.4：支持「解析分享链接」的书直接进选集 —— shareUrl 传真实分享链接
     * （非 __disk__| 前缀）时，章节按分享链接编码，播放走分享下载链路
     * （quarkDownload），而不是盘内文件的 diskDownloadUrl —— 用户可能只想听
     * 别人分享的书、自己网盘里并没有这些文件。
     */
    @JavascriptInterface
    public void panDiskBook(final String platform, final String bookName,
                            final String filesJson, final String shareUrl) {
        if (panLocked()) return;   // ★ v4.18.0 功能锁
        bg(new Runnable() {
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    List<com.tingshu.app.pan.PanResolver.PanFile> picked =
                            new ArrayList<com.tingshu.app.pan.PanResolver.PanFile>();
                    JSONArray arr = new JSONArray(filesJson == null ? "[]" : filesJson);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject fj = arr.optJSONObject(i);
                        if (fj == null) continue;
                        com.tingshu.app.pan.PanResolver.PanFile f =
                                new com.tingshu.app.pan.PanResolver.PanFile();
                        f.id = fj.optString("id", "");
                        f.name = fj.optString("name", "");
                        f.size = fj.optLong("size", 0);
                        f.dir = fj.optBoolean("dir", false);
                        f.token = fj.optString("token", "");
                        if (!f.dir) picked.add(f);
                    }
                    if (picked.isEmpty()) throw new Exception("这本书没有音频文件");

                    // share 段：盘内书 = __disk__|书名；解析书 = 真实分享链接
                    boolean isShare = shareUrl != null && shareUrl.length() > 0
                            && !shareUrl.startsWith("__disk__|");
                    String share = isShare ? shareUrl : "__disk__|" + bookName;
                    // bookId 必须与 panPlay / PanSource.detail 的算法完全一致，
                    // 否则详情页读不到这里写进去的章节缓存。
                    final Book b = isShare
                            ? new Book(platform,
                                    "share_" + share.replaceAll(".*/", "")
                                            .replaceAll("[^0-9A-Za-z\\u4e00-\\u9fa5_-]", "_"),
                                    bookName)
                            : new Book(platform,
                                    com.tingshu.app.pan.PanSource.diskBookId(share), bookName);
                    b.url = com.tingshu.app.pan.PanSource.PREFIX + share;
                    b.intro = isShare ? shareUrl : "来自网盘扫描";

                    List<com.tingshu.app.data.Models.Chapter> chs =
                            new ArrayList<com.tingshu.app.data.Models.Chapter>();
                    int i = 1;
                    for (com.tingshu.app.pan.PanResolver.PanFile f : picked) {
                        com.tingshu.app.data.Models.Chapter ch =
                                new com.tingshu.app.data.Models.Chapter();
                        ch.title = f.name;
                        ch.url = com.tingshu.app.pan.PanSource.encChapter(
                                platform, share, f.id, f.token);
                        ch.index = i++;
                        chs.add(ch);
                    }
                    com.tingshu.app.pan.PanSource.putDiskChapters(b.bookId, chs);

                    o.put("ok", true);
                    o.put("book", b.toJson());
                    JSONArray ca = new JSONArray();
                    for (com.tingshu.app.data.Models.Chapter c : chs) {
                        JSONObject co = new JSONObject();
                        co.put("title", c.title);
                        co.put("url", c.url);
                        co.put("index", c.index);
                        ca.put(co);
                    }
                    o.put("chapters", ca);
                    o.put("total", chs.size());
                } catch (Throwable t) {
                    try {
                        o.put("ok", false);
                        o.put("error", String.valueOf(t.getMessage()));
                    } catch (Throwable ignored) {}
                }
                callback("onPanDiskBook", o);
            }
        });
    }

    /**
     * ★ v3.9.1：退出 App（首页返回确认框点「确定」/ 连按两次返回时调用）。
     *
     * <p>只 finish 界面，<b>不杀进程</b>：正在后台播放的话，通知栏与播放服务
     * 应该继续（用户关的是界面，不是"停止播放"）。要停播放请走通知栏的「停止」。
     */
    @JavascriptInterface
    public void exitApp() {
        ui.post(new Runnable() {
            public void run() {
                try {
                    if (activity == null) return;
                    try {
                        activity.finishAffinity();
                    } catch (Throwable t) {
                        activity.finish();
                    }
                } catch (Throwable ignored) {}
            }
        });
    }
}
