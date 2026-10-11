package com.tingshu.app;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.tingshu.app.data.Db;
import com.tingshu.app.data.FavRepo;
import com.tingshu.app.data.HistoryRepo;
import com.tingshu.app.data.Models.Book;
import com.tingshu.app.data.Models.FavGroup;
import com.tingshu.app.data.Models.History;
import com.tingshu.app.source.Aggregator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * JS 桥：收藏 / 历史 / 进度 部分。
 *
 * 拆成两个类是因为 Bridge 会长到 700+ 行，按职责切分更好维护。
 * 前端看到的仍是同一个 "Android" 对象（MainActivity 会注册多个实例到同一名字下，
 * 但 WebView 只允许一个 addJavascriptInterface 同名 —— 所以这里由 Bridge 委托调用）。
 *
 * 因此：所有 @JavascriptInterface 方法仍必须定义在 Bridge 里，
 * 本类只提供静态实现，Bridge 转发过来。
 */
public final class BridgeData {

    private BridgeData() {}

    // ==================== 收藏 ====================

    public static void favToggle(WebView web, Handler ui, String bookJson, long groupId) {
        final Book b = parseBook(bookJson);
        if (b == null) {
            reply(web, ui, "onFavChanged", emptyWith("ok", false));
            return;
        }
        final String key = b.key();
        boolean now = false;
        try {
            now = FavRepo.toggle(b, groupId);
        } catch (Throwable ignored) {}
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("key", key);
            o.put("fav", now);
            o.put("count", FavRepo.count());
        } catch (Throwable ignored) {}
        reply(web, ui, "onFavChanged", o);
    }

    public static void favList(WebView web, Handler ui, long groupId) {
        JSONArray arr = new JSONArray();
        try {
            List<Book> list = FavRepo.list(groupId);
            for (Book b : list) arr.put(b.toJson());
        } catch (Throwable ignored) {}
        reply(web, ui, "onFavList", arr);
    }

    public static void favRemove(WebView web, Handler ui, String key) {
        try {
            FavRepo.remove(key);
        } catch (Throwable ignored) {}
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("key", key);
            o.put("count", FavRepo.count());
        } catch (Throwable ignored) {}
        reply(web, ui, "onFavChanged", o);
    }

    /** 查一批书里哪些已收藏，返回 key 数组 */
    public static void favStates(WebView web, Handler ui, String keysJson) {
        JSONArray out = new JSONArray();
        try {
            JSONArray in = new JSONArray(keysJson);
            for (int i = 0; i < in.length(); i++) {
                String k = in.optString(i, "");
                if (k.length() > 0 && FavRepo.isFav(k)) out.put(k);
            }
        } catch (Throwable ignored) {}
        reply(web, ui, "onFavStates", out);
    }

    // ==================== 分组 ====================

    public static void groupList(WebView web, Handler ui) {
        JSONArray arr = new JSONArray();
        try {
            List<FavGroup> gs = FavRepo.groups();
            for (FavGroup g : gs) {
                JSONObject o = g.toJson();
                o.put("count", FavRepo.countInGroup(g.id));
                arr.put(o);
            }
        } catch (Throwable ignored) {}
        reply(web, ui, "onGroupList", arr);
    }

    public static void groupAdd(WebView web, Handler ui, String name) {
        long id = -1;
        try {
            id = FavRepo.addGroup(name);
        } catch (Throwable ignored) {}
        JSONObject o = new JSONObject();
        try {
            o.put("ok", id >= 0);
            o.put("id", id);
        } catch (Throwable ignored) {}
        reply(web, ui, "onGroupChanged", o);
    }

    public static void groupRename(WebView web, Handler ui, long id, String name) {
        try {
            FavRepo.renameGroup(id, name);
        } catch (Throwable ignored) {}
        reply(web, ui, "onGroupChanged", emptyWith("ok", true));
    }

    public static void groupDelete(WebView web, Handler ui, long id) {
        try {
            FavRepo.deleteGroup(id);
        } catch (Throwable ignored) {}
        reply(web, ui, "onGroupChanged", emptyWith("ok", true));
    }

    // ==================== 历史 ====================

    public static void historyList(WebView web, Handler ui, int limit) {
        JSONArray arr = new JSONArray();
        try {
            List<History> list = HistoryRepo.list(limit <= 0 ? 200 : limit);
            for (History h : list) arr.put(h.toJson());
        } catch (Throwable ignored) {}
        reply(web, ui, "onHistoryList", arr);
    }

    public static void historyRemove(WebView web, Handler ui, String key) {
        try {
            HistoryRepo.remove(key);
        } catch (Throwable ignored) {}
        reply(web, ui, "onHistoryChanged", emptyWith("ok", true));
    }

    public static void historyClear(WebView web, Handler ui) {
        try {
            HistoryRepo.clear();
        } catch (Throwable ignored) {}
        reply(web, ui, "onHistoryChanged", emptyWith("ok", true));
    }

    /** 该书最后听到的集号与偏移（点开书时用于提示"上次听到第N集"） */
    public static void historyGet(WebView web, Handler ui, String bookKey) {
        JSONObject o = new JSONObject();
        try {
            History h = HistoryRepo.get(bookKey);
            if (h != null) {
                o = h.toJson();
                // 补上该集的具体偏移
                o.put("posMs", HistoryRepo.posOf(bookKey, h.lastIndex));
            }
        } catch (Throwable ignored) {}
        reply(web, ui, "onHistoryGet", o);
    }

    /** 首页聚合信息：继续收听 + 最近收藏 + 计数 */
    public static void homeInfo(WebView web, Handler ui) {
        JSONObject o = new JSONObject();
        try {
            History latest = HistoryRepo.latest();
            o.put("continue", latest == null ? new JSONObject() : latest.toJson());
            JSONArray favs = new JSONArray();
            List<Book> recent = FavRepo.recent(6);
            for (Book b : recent) favs.put(b.toJson());
            o.put("recentFav", favs);
            o.put("favCount", FavRepo.count());
            o.put("historyCount", HistoryRepo.count());
        } catch (Throwable ignored) {}
        reply(web, ui, "onHomeInfo", o);
    }

    // ==================== 内部 ====================

    /** MainActivity 需要知道当前 Activity 的 WebView，用于回调 */
    static Book parseBook(String json) {
        if (json == null || json.length() == 0) return null;
        try {
            return Book.fromJson(new JSONObject(json));
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject emptyWith(String k, boolean v) {
        JSONObject o = new JSONObject();
        try {
            o.put(k, v);
        } catch (Throwable ignored) {}
        return o;
    }

    static void reply(final WebView web, Handler ui, final String fn, final Object arg) {
        ui.post(new Runnable() {
            public void run() {
                try {
                    web.evaluateJavascript(
                            "window." + fn + "(" + String.valueOf(arg) + ")", null);
                } catch (Throwable ignored) {}
            }
        });
    }
}
