package com.tingshu.app.data;

import android.content.ContentValues;
import android.database.Cursor;

import com.tingshu.app.core.Utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义源的持久化层（表 custom_source）。
 * 只做存取，不做解析——解析交给 {@link SourceConfig}。
 */
public final class SourceStore {

    private SourceStore() {}

    /** 启动时载入一次，供 SourceRegistry 同步读取；导入/删除后刷新。 */
    private static volatile List<SourceConfig> CACHE = null;

    /** 内存里维护一份，供注册表扫描（避免每次遍历都查库） */
    public static synchronized List<SourceConfig> all() {
        if (CACHE != null) return CACHE;
        List<SourceConfig> out = new ArrayList<SourceConfig>();
        Cursor c = null;
        try {
            c = Db.get().rawQuery(
                    "SELECT config, origin FROM custom_source ORDER BY sort ASC, added_at ASC",
                    null);
            while (c != null && c.moveToNext()) {
                String raw = Db.str(c, "config");
                if (Utils.empty(raw)) continue;
                SourceConfig sc = SourceConfig.parseList(raw).isEmpty()
                        ? SourceConfig.from(com.tingshu.app.core.Json.obj(raw))
                        : SourceConfig.parseList(raw).get(0);
                if (sc != null && !Utils.empty(sc.id)) {
                    sc.raw = raw;
                    sc.origin = Db.str(c, "origin");
                    out.add(sc);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            Db.close(c);
        }
        CACHE = out;
        return out;
    }

    /** 按 id 取一个 */
    public static SourceConfig byId(String id) {
        if (Utils.empty(id)) return null;
        for (SourceConfig sc : all()) {
            if (id.equals(sc.id)) return sc;
        }
        return null;
    }

    /** 是否已存在同 id 的自定义源 */
    public static boolean exists(String id) {
        return byId(id) != null;
    }

    /** 刷新内存缓存 */
    public static void invalidate() {
        CACHE = null;
    }

    /**
     * 保存（存在则覆盖）。保存前保证 raw 是规范化的完整 JSON（便于导出）。
     */
    public static boolean save(SourceConfig sc) {
        if (sc == null || Utils.empty(sc.id)) return false;
        String raw = Utils.empty(sc.raw) ? toJson(sc).toString() : sc.raw;
        ContentValues v = new ContentValues();
        v.put("source_id", sc.id);
        v.put("name", sc.name);
        v.put("desc", sc.desc);
        v.put("config", raw);
        if (!Utils.empty(sc.origin)) v.put("origin", sc.origin);
        long now = System.currentTimeMillis();
        boolean had = exists(sc.id);
        if (!had) {
            v.put("sort", 100);
            v.put("added_at", now);
        }
        long r = Db.get().insert("custom_source", v);
        invalidate();
        return r >= 0;
    }

    /** 批量保存（事务），返回成功写入的条数 */
    public static int saveAll(List<SourceConfig> list) {
        if (list == null || list.isEmpty()) return 0;
        final List<SourceConfig> copy = new ArrayList<SourceConfig>(list);
        final int[] n = new int[]{0};
        Db.get().transaction(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < copy.size(); i++) {
                    SourceConfig sc = copy.get(i);
                    if (sc == null || Utils.empty(sc.id)) continue;
                    ContentValues v = new ContentValues();
                    v.put("source_id", sc.id);
                    v.put("name", sc.name);
                    v.put("desc", sc.desc);
                    v.put("config", Utils.empty(sc.raw) ? toJson(sc).toString() : sc.raw);
                    if (!Utils.empty(sc.origin)) v.put("origin", sc.origin);
                    if (!exists(sc.id)) {
                        v.put("sort", 100);
                        v.put("added_at", System.currentTimeMillis());
                    }
                    if (Db.get().insert("custom_source", v) >= 0) n[0]++;
                }
            }
        });
        invalidate();
        return n[0];
    }

    /** 删除一个自定义源 */
    public static boolean remove(String id) {
        if (Utils.empty(id)) return false;
        int n = Db.get().delete("custom_source", "source_id=?", new String[]{id});
        invalidate();
        return n > 0;
    }

    public static int count() {
        return all().size();
    }

    // ---------------------------------------------------------------- 导出

    /** 导出格式版本。v1 = 兼容阅读系（含 disabled/alias 段）；v2 = 枫兮专属格式。 */
    public static final int EXPORT_VERSION = 2;

    /** 导出文件里的 App 标识，用于区分「枫兮听书的源包」与第三方源包 */
    public static final String APP_TAG = "fengxi-tingshu";

    /**
     * 把自定义源打包成一份可分享的 JSON。
     *
     * <p>★ v2.7：导出格式升到 version 2，并做了三处「去第三方化」：
     * <ol>
     *   <li>不再导出 {@code alias} 段 —— 那 5 个 id（bokan/yuntu/ting15/…）
     *       是历史内置源的标识，v2.4 起本 App 已无内置源，继续导出等于
     *       把别家的源 id 夹带在自家订阅文件里。</li>
     *   <li>写入 {@code app: "fengxi-tingshu"} 标识与 {@code ver}，
     *       别的 App 解析时能立刻看出这不是自己的格式。</li>
     *   <li>每条源写入 {@code _fx: 1} 指纹字段。第三方 App 若照搬解析，
     *       会读到一个它不认识的字段；本 App 导入时靠它识别自家源包。</li>
     * </ol>
     *
     * <p>注意：**导入侧仍完全兼容 v1 / 阅读系格式**（{@code {sources:[]}}、
     * 裸数组、单对象都能吃），这是有意保留的单向兼容 —— 我们能读别人的源，
     * 但导出的东西只属于自己。
     *
     * @param includeBuiltinState 已废弃（v2.4 起无内置源），保留参数仅兼容旧调用
     * @param pretty              是否缩进（导出给用户看时用）
     */
    public static String exportJson(boolean includeBuiltinState, boolean pretty) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (SourceConfig sc : all()) {
            org.json.JSONObject o = toJson(sc);
            if (o == null) continue;
            // 指纹字段：标记「这条来自枫兮听书」
            try {
                o.put("_fx", 1);
            } catch (Throwable ignored) {}
            arr.put(o);
        }

        org.json.JSONObject root = new org.json.JSONObject();
        try {
            root.put("version", EXPORT_VERSION);
            root.put("app", APP_TAG);
            root.put("name", "枫兮听书源订阅");
            root.put("updated", System.currentTimeMillis());
            root.put("count", arr.length());
            root.put("sources", arr);
        } catch (Throwable ignored) {}
        try {
            return pretty ? root.toString(2) : root.toString();
        } catch (Throwable t) {
            return root.toString();
        }
    }

    /**
     * 这份 JSON 是否是「枫兮听书自家导出的源包」。
     * <p>判据：顶层 {@code app == "fengxi-tingshu"}，
     * 或 version &gt;= 2，或首个源条目带 {@code _fx} 指纹。
     */
    public static boolean isOwnPackage(String json) {
        try {
            org.json.JSONObject root = com.tingshu.app.core.Json.obj(json);
            if (root == null) return false;
            if (APP_TAG.equals(root.optString("app", ""))) return true;
            if (root.optInt("version", 0) >= EXPORT_VERSION) return true;
            org.json.JSONArray a = root.optJSONArray("sources");
            if (a != null && a.length() > 0) {
                org.json.JSONObject first = a.optJSONObject(0);
                if (first != null && first.has("_fx")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 导入时读取「内置源启停」名单 */
    public static List<String> parseDisabled(String json) {
        List<String> out = new ArrayList<String>();
        try {
            org.json.JSONObject root = com.tingshu.app.core.Json.obj(json);
            if (root == null) return out;
            org.json.JSONArray a = root.optJSONArray("disabled");
            if (a == null) return out;
            for (int i = 0; i < a.length(); i++) {
                String id = a.optString(i, "");
                if (!Utils.empty(id)) out.add(id);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * 导入时读取「别名」映射。
     *
     * <p>★ v2.7：**只读不写**。本 App 从 v2.4 起已无内置源，v2.7 起导出也不再
     * 产生 alias 段。这里保留解析是为了兼容用户手里旧版的 v1 源包（以及
     * 阅读系第三方源包），读完即忽略，不会在本 App 内产生任何别家标识。
     */
    public static Map<String, String> parseAlias(String json) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            org.json.JSONObject root = com.tingshu.app.core.Json.obj(json);
            if (root == null) return out;
            org.json.JSONObject a = root.optJSONObject("alias");
            if (a == null) return out;
            java.util.Iterator<String> it = a.keys();
            while (it != null && it.hasNext()) {
                String k = it.next();
                String v = a.optString(k, "");
                if (!Utils.empty(k) && !Utils.empty(v)) out.put(k, v);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * 历史内置源的启停状态表。
     *
     * <p>★ v2.7：**仅用于读取旧 v1 源包**。本 App 已无内置源，这里恒返回空表 ——
     * 旧包里的 {@code disabled} 段对我们已无意义（那些 id 在本 App 里根本不存在）。
     * 保留方法签名是为了不破坏旧调用点。
     *
     * @deprecated v2.4 起本 App 无内置源，该方法恒返回空表
     */
    @Deprecated
    private static Map<String, Boolean> enabledMap() {
        return new LinkedHashMap<String, Boolean>();
    }

    /** SourceConfig -> 精简 JSON（导出用，保证字段名与导入一致） */
    public static org.json.JSONObject toJson(SourceConfig sc) {
        if (sc == null) return null;
        // raw 已存在则原样返回，保留用户手写的额外字段
        if (!Utils.empty(sc.raw)) {
            org.json.JSONObject r = com.tingshu.app.core.Json.obj(sc.raw);
            if (r != null) return r;
        }
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("id", sc.id);
            o.put("name", sc.name);
            o.put("desc", sc.desc);
            o.put("base", sc.base);
            if (sc.headers != null) o.put("headers", sc.headers);
            if (sc.search != null) {
                org.json.JSONObject s = new org.json.JSONObject();
                s.put("method", sc.search.method);
                s.put("url", sc.search.url);
                if (!Utils.empty(sc.search.body)) s.put("body", sc.search.body);
                s.put("type", sc.search.type);
                s.put("items", sc.search.items);
                s.put("title", sc.search.title);
                s.put("bookUrl", sc.search.urlField);
                if (!Utils.empty(sc.search.author)) s.put("author", sc.search.author);
                if (!Utils.empty(sc.search.cover)) s.put("cover", sc.search.cover);
                if (!Utils.empty(sc.search.intro)) s.put("intro", sc.search.intro);
                s.put("maxPages", sc.search.maxPages);
                o.put("search", s);
            }
            if (sc.detail != null) {
                org.json.JSONObject d = new org.json.JSONObject();
                if (!Utils.empty(sc.detail.page)) d.put("page", sc.detail.page);
                d.put("type", sc.detail.type);
                d.put("items", sc.detail.items);
                d.put("title", sc.detail.title);
                d.put("chapterUrl", sc.detail.urlField);
                if (!Utils.empty(sc.detail.bookTitle)) d.put("bookTitle", sc.detail.bookTitle);
                if (!Utils.empty(sc.detail.author)) d.put("author", sc.detail.author);
                if (!Utils.empty(sc.detail.intro)) d.put("intro", sc.detail.intro);
                if (sc.detail.reverse) d.put("reverse", true);
                o.put("detail", d);
            }
            if (sc.audio != null) {
                org.json.JSONObject a = new org.json.JSONObject();
                a.put("mode", sc.audio.mode);
                if (!Utils.empty(sc.audio.url)) a.put("url", sc.audio.url);
                a.put("method", sc.audio.method);
                if (!Utils.empty(sc.audio.field)) a.put("field", sc.audio.field);
                if (sc.audio.group != 1) a.put("group", sc.audio.group);
                o.put("audio", a);
            }
        } catch (Throwable ignored) {}
        return o;
    }
}
