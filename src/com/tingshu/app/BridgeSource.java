package com.tingshu.app;

import android.os.Handler;
import android.webkit.WebView;

import com.tingshu.app.core.Utils;
import com.tingshu.app.data.SourceConfig;
import com.tingshu.app.data.SourceRepo;
import com.tingshu.app.data.SourceStore;
import com.tingshu.app.source.BrowsableSource;
import com.tingshu.app.source.Category;
import com.tingshu.app.source.JsonSource;
import com.tingshu.app.source.Source;
import com.tingshu.app.source.SourceRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 源导入 / 导出的具体实现。纯静态，无 WebView 依赖（只通过参数拿 web/ui）。
 * 与 {@link BridgeData} 同构：Bridge 只做转发，逻辑在这里，保持 Bridge 精简。
 */
public final class BridgeSource {

    private BridgeSource() {}

    /** 通过 ui 线程（弱引用 web）发回调；与 BridgeData.reply 语义一致 */
    private static void reply(WebView web, Handler ui, String fn, JSONObject arg) {
        if (arg == null) arg = new JSONObject();
        final String code = "window." + fn + "(" + arg.toString() + ")";
        final WebView w = web;
        ui.post(new Runnable() {
            public void run() {
                try {
                    w.evaluateJavascript(code, null);
                } catch (Throwable ignored) {}
            }
        });
    }

    // ==================== 内置源状态打包（导出） ====================

    /** 全部源状态（内置 + 自定义），一次性给前端渲染源管理页 */
    public static JSONArray allSourcesJson() {
        JSONArray arr = new JSONArray();
        try {
            List<Source> all = SourceRegistry.all();
            for (Source s : all) {
                JSONObject o = new JSONObject();
                o.put("id", s.id());
                o.put("name", s.name());
                o.put("desc", s.desc());
                o.put("canSearch", s.canSearch());
                o.put("enabled", SourceRepo.isEnabled(s.id()));
                o.put("builtin", SourceRegistry.isBuiltin(s.id()));
                String alias = SourceRepo.alias(s.id());
                o.put("alias", alias == null ? "" : alias);
                // 导入方式（内置源为空），前端据此分组
                String origin = "";
                try {
                    com.tingshu.app.data.SourceConfig sc = SourceStore.byId(s.id());
                    if (sc != null) origin = sc.origin == null ? "" : sc.origin;
                } catch (Throwable ignored) {}
                // ★ v3.3：插件源的 origin 要区分 jar / js —— 前端源管理页据此分两组展示。
                //   JS 插件包的源（JsSourceAdapter）与 jar 插件源（TingShuAdapter）
                //   都是"非内置、非 JsonSource"，原来的写法会把它们一律标成 "jar"，
                //   于是 19 个 JS 源会挤进「插件源」那一组，跟 jar 插件混在一起。
                // ★ v3.7.4：Python 源（PySourceAdapter）同样属于"非内置、非 JsonSource"，
                //   之前会被标成 "jar" 混进 jar 组；而且它们既不在 SourceStore 也不在
                //   插件记录里，导致「删除」点了没反应。这里单独标 "py"，前端开一个分组。
                if (Utils.empty(origin) && !SourceRegistry.isBuiltin(s.id())
                        && SourceRegistry.isFromPlugin(s.id())) {
                    if (s instanceof com.tingshu.app.python.PySourceAdapter) {
                        origin = "py";
                    } else if (s instanceof com.tingshu.app.source.JsSourceAdapter) {
                        origin = "js";
                    } else {
                        origin = "jar";
                    }
                }
                o.put("origin", origin);
                // ★ v3.9.0：本地书源 / 网盘接口 —— 前端归入「自定义源」组。
                //   这两类源不在用户数据库里、删不掉（同内置源语义），
                //   但网盘源的「启用」看 Cookie 是否已配置。
                if (s instanceof com.tingshu.app.source.impl.LocalSource) {
                    o.put("origin", "local");
                    o.put("builtin", true);
                } else if (s instanceof com.tingshu.app.pan.PanSource) {
                    String pid = s.id();
                    o.put("origin", "pan");
                    o.put("builtin", true);
                    o.put("enabled", SourceRepo.isEnabled(pid)
                            && com.tingshu.app.pan.PanResolver.configured(pid));
                    o.put("panReady", com.tingshu.app.pan.PanResolver.configured(pid));
                }
                // JS 源额外的能力位：需要站点验证 / 搜索间隔（前端用来提示用户）
                if (s instanceof com.tingshu.app.source.JsSourceAdapter) {
                    com.tingshu.app.source.JsSourceAdapter ja =
                            (com.tingshu.app.source.JsSourceAdapter) s;
                    o.put("jsPlugin", true);
                    o.put("needsVerification", ja.needsVerification());
                    o.put("verifyDesktop", ja.searchVerificationDesktop());
                    o.put("searchDelay", ja.searchDelaySeconds());
                    o.put("pkg", ja.pkgName() == null ? "" : ja.pkgName());
                }
                arr.put(o);
            }
        } catch (Throwable ignored) {}
        return arr;
    }

    // ==================== 导入 ====================

    /**
     * 导入结果统计对象，供回调展示。
     * added/updated/skipped 三个数告诉用户到底发生了什么，而不是笼统的"成功"。
     */
    private static JSONObject importResult() {
        JSONObject o = new JSONObject();
        try {
            o.put("added", 0);
            o.put("updated", 0);
            o.put("skipped", 0);
            o.put("failed", 0);
            o.put("messages", new JSONArray());
            o.put("builtinChanged", 0);
        } catch (Throwable ignored) {}
        return o;
    }

    private static void addMsg(JSONObject r, String m) {
        if (r == null || Utils.empty(m)) return;
        try {
            JSONArray a = r.optJSONArray("messages");
            if (a == null) {
                a = new JSONArray();
                r.put("messages", a);
            }
            a.put(m);
        } catch (Throwable ignored) {}
    }

    private static void bump(JSONObject r, String key) {
        try {
            r.put(key, r.optInt(key, 0) + 1);
        } catch (Throwable ignored) {}
    }

    /**
     * 导入一段 JSON 文本。
     *
     * 支持三种形态：
     * 1. {sources:[...], disabled:[...], alias:{...}}  —— 完整订阅包
     * 2. [ {...}, {...} ]                              —— 纯源数组
     * 3. { id, name, base, ... }                       —— 单个源
     *
     * @param overwriteSameId 同 id 是否覆盖
     */
    public static JSONObject importText(String json, boolean overwriteSameId) {
        return importText(json, overwriteSameId, "text");
    }

    /**
     * @param origin 导入方式（text/file/url/eprendre/jar），源管理页据此分组展示
     */
    public static JSONObject importText(String json, boolean overwriteSameId, String origin) {
        JSONObject result = importResult();
        if (Utils.empty(json)) {
            addMsg(result, "内容为空");
            bump(result, "failed");
            return result;
        }
        String text = json.trim();

        // 0) ★ v2.7：判断这份源包是不是「枫兮听书自家导出的 v2 格式」。
        //    前端据此给出不同提示（自家包 vs 外部兼容包），
        //    不影响导入行为 —— 导入侧一律单向兼容。
        try {
            result.put("ownPackage", SourceStore.isOwnPackage(text));
        } catch (Throwable ignored) {}

        // 1) 先处理内置源状态（disabled / alias），这部分与自定义源无关
        List<String> disabled = SourceStore.parseDisabled(text);
        Map<String, String> alias = SourceStore.parseAlias(text);
        int changed = 0;
        for (int i = 0; i < disabled.size(); i++) {
            String id = disabled.get(i);
            if (SourceRegistry.isBuiltin(id)) {
                SourceRepo.setEnabled(id, false);
                changed++;
            }
        }
        for (Map.Entry<String, String> e : alias.entrySet()) {
            if (SourceRegistry.isBuiltin(e.getKey())) {
                SourceRepo.setAlias(e.getKey(), e.getValue());
                changed++;
            }
        }
        try {
            result.put("builtinChanged", changed);
        } catch (Throwable ignored) {}

        // 2) 再处理自定义源定义
        List<SourceConfig> list = SourceConfig.parseList(text);
        if (list.isEmpty()) {
            if (changed > 0) {
                SourceRegistry.reload();
                addMsg(result, "已应用内置源设置 " + changed + " 项");
                return result;
            }
            addMsg(result, "没有解析到任何源定义，请检查 JSON 格式");
            bump(result, "failed");
            return result;
        }

        List<SourceConfig> toSave = new ArrayList<SourceConfig>();
        for (int i = 0; i < list.size(); i++) {
            SourceConfig sc = list.get(i);
            if (sc == null) continue;
            String err = sc.validate();
            if (err != null) {
                addMsg(result, "跳过「" + (Utils.empty(sc.name) ? sc.id : sc.name) + "」：" + err);
                bump(result, "failed");
                continue;
            }
            if (sc.conflictsWithBuiltin()) {
                addMsg(result, "跳过「" + sc.name + "」：id 与已占用的保留 id 冲突（" + sc.id + "）");
                bump(result, "skipped");
                continue;
            }
            boolean exists = SourceStore.exists(sc.id);
            if (exists && !overwriteSameId) {
                addMsg(result, "已存在同名源「" + sc.name + "」，未覆盖");
                bump(result, "skipped");
                continue;
            }
            // 已存在的源保留原 origin，避免"订阅导入"覆盖后被标成"粘贴"
            if (!exists || Utils.empty(sc.origin)) sc.origin = origin;
            toSave.add(sc);
            bump(result, exists ? "updated" : "added");
        }

        if (!toSave.isEmpty()) {
            int n = SourceStore.saveAll(toSave);
            if (n == 0) {
                addMsg(result, "写入数据库失败");
            }
            SourceRegistry.reload();
        } else if (changed > 0) {
            SourceRegistry.reload();
        }
        return result;
    }

    /**
     * 从远端 URL 拉取订阅 JSON 并导入。
     * 支持 http/https，允许用户自己搭个静态文件当订阅地址。
     */
    public static JSONObject importUrl(String url, boolean overwriteSameId) {
        if (Utils.empty(url)) {
            JSONObject r = importResult();
            addMsg(r, "链接为空");
            bump(r, "failed");
            return r;
        }
        // ★ v3.8.1：支持「链接@名称」这种在书源圈子里流传很广的写法，
        //   例如 https://xxx/api/p/os/xml_lrt.json?download=true@____
        //   （@ 后面常常只是个占位符，所以切出来为空时也必须能正常导入）。
        String[] un = splitUrlName(url);
        String u = un[0];
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;
        }
        String body;
        try {
            body = com.tingshu.app.core.Net.get(u, null);
        } catch (Throwable t) {
            // 兜底：切掉 @ 后缀后仍失败，再拿原始整串试一次
            //      （万一切错了，至少还有原样请求这条路）
            String raw = url.trim();
            if (!raw.equals(u)) {
                if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                    raw = "https://" + raw;
                }
                try {
                    body = com.tingshu.app.core.Net.get(raw, null);
                    u = raw;
                } catch (Throwable t2) {
                    JSONObject r = importResult();
                    addMsg(r, "下载失败："
                            + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                    bump(r, "failed");
                    return r;
                }
            } else {
                JSONObject r = importResult();
                addMsg(r, "下载失败："
                        + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                bump(r, "failed");
                return r;
            }
        }
        if (Utils.empty(body)) {
            JSONObject r = importResult();
            addMsg(r, "下载内容为空，请确认链接可直接访问 JSON");
            bump(r, "failed");
            return r;
        }

        // 先识别是不是「原版我的听书」订阅清单：这类文件没有 sources 数组，
        // 只有 entry_package + download_url，指向一个 jar。
        if (isEprendreManifest(body)) {
            return importEprendreManifest(body, u);
        }

        JSONObject r = importText(body, overwriteSameId, "url");
        try {
            r.put("fromUrl", u);
        } catch (Throwable ignored) {}
        return r;
    }

    /**
     * ★ v3.8.1：把「链接@名称」拆成 {链接, 名称}。
     *
     * <p>这个写法在书源分享里很常见（便于一眼看出这批源是什么），
     * 例如 {@code https://xxx/a.json?download=true@____}。
     * 难点是 URL 自己也可能带 @（{@code http://user:pass@host/path}），
     * 所以**不能无脑按 @ 切**。规则：
     * <ol>
     *   <li>取最后一个 @；</li>
     *   <li>@ 之后的部分若含 {@code / ? : #} → 判定它是 URL 内部成分，不切；</li>
     *   <li>@ 之后是空串或纯占位符（下划线/短横/星号/空格）→ 只取链接，名称留空。</li>
     * </ol>
     *
     * @return {链接, 名称}，名称可能为 ""
     */
    private static String[] splitUrlName(String raw) {
        String s = raw == null ? "" : raw.trim();
        int at = s.lastIndexOf('@');
        if (at <= 0) return new String[]{s, ""};
        String head = s.substring(0, at).trim();
        String tail = s.substring(at + 1).trim();
        if (head.length() == 0) return new String[]{s, ""};
        if (tail.indexOf('/') >= 0 || tail.indexOf('?') >= 0
                || tail.indexOf(':') >= 0 || tail.indexOf('#') >= 0) {
            return new String[]{s, ""};       // @ 属于 URL 本身
        }
        // 纯占位符（____ / ---- / **** / 空白）视为没写名字
        String bare = tail.replace("_", "").replace("-", "")
                .replace("*", "").replace(" ", "").trim();
        if (bare.length() == 0) return new String[]{head, ""};
        return new String[]{head, tail};
    }

    // ==================== 原版「我的听书」订阅清单 ====================

    /**
     * 判断是不是原版 eprendre 订阅清单。
     *
     * <p>该格式长得像：
     * <pre>
     *   {
     *     "version": 6,
     *     "entry_package": "sources_by_tingyou",
     *     "download_url": "http://xxx/sources_by_tingyou_dex.jar",
     *     "update_msg": "...",
     *     "support_url": ""
     *   }
     * </pre>
     * 与我们的订阅包（含 sources 数组）完全不同：它不描述源本身，
     * 只说「去哪儿下载装着源的 jar」。
     */
    public static boolean isEprendreManifest(String json) {
        if (Utils.empty(json)) return false;
        try {
            org.json.JSONObject o = com.tingshu.app.core.Json.obj(json);
            if (o == null) return false;
            String dl = o.optString("download_url", "").trim();
            if (dl.length() == 0) return false;
            // 有 download_url 且没有 sources 数组 → 认为是指向 jar 的清单
            if (o.optJSONArray("sources") != null) return false;
            if (o.optJSONArray("list") != null) return false;
            String pkg = o.optString("entry_package", "").trim();
            return pkg.length() > 0 || dl.endsWith(".jar") || dl.endsWith(".bin");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 处理原版订阅清单：下载 jar → 落地到私有目录 → 走 {@link #importJar} 注册。
     *
     * <p>这是「用户拿着原版听书的订阅链接来导入」的正解——文件本身不是源配置，
     * 直接按 JSON 解析必然报「没有解析到任何源定义」，这正是用户遇到的失败原因。
     */
    public static JSONObject importEprendreManifest(String manifestJson, String fromUrl) {
        JSONObject r = importResult();
        try {
            r.put("from", "eprendre");
            r.put("fromUrl", fromUrl == null ? "" : fromUrl);
        } catch (Throwable ignored) {}

        org.json.JSONObject o = com.tingshu.app.core.Json.obj(manifestJson);
        if (o == null) {
            addMsg(r, "订阅文件不是合法 JSON");
            bump(r, "failed");
            return r;
        }
        String pkg = o.optString("entry_package", "").trim();
        String dl = o.optString("download_url", "").trim();
        String updateMsg = o.optString("update_msg", "").trim();
        if (Utils.empty(dl)) {
            addMsg(r, "订阅文件缺少 download_url，无法定位插件包");
            bump(r, "failed");
            return r;
        }
        // 相对地址按清单所在地址补全
        dl = com.tingshu.app.core.Utils.abs(fromUrl, dl);
        try {
            r.put("downloadUrl", dl);
            r.put("entryPackage", pkg);
            r.put("updateMsg", updateMsg);
        } catch (Throwable ignored) {}

        // 文件名：优先用 URL 最后一段，退化用 entry_package
        String fileName = dl;
        int slash = dl.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < dl.length()) fileName = dl.substring(slash + 1);
        int q = fileName.indexOf('?');
        if (q > 0) fileName = fileName.substring(0, q);
        if (Utils.empty(fileName) || fileName.indexOf('.') < 0) {
            fileName = (Utils.empty(pkg) ? "plugin" : pkg) + ".jar";
        }
        if (!fileName.endsWith(".jar")) {
            // .bin/.dex 之类统一改名成 jar（内部就是 zip + classes.dex，DexClassLoader 认扩展名）
            int dot = fileName.lastIndexOf('.');
            fileName = (dot > 0 ? fileName.substring(0, dot) : fileName) + ".jar";
        }

        // 下载到插件目录
        java.io.File dir;
        try {
            android.content.Context c = com.tingshu.app.core.App.ctx();
            if (c == null) {
                addMsg(r, "应用上下文未就绪，无法保存插件");
                bump(r, "failed");
                return r;
            }
            dir = new java.io.File(c.getFilesDir(), "plugins");
            if (!dir.exists()) dir.mkdirs();
        } catch (Throwable t) {
            addMsg(r, "创建插件目录失败：" + t.getClass().getSimpleName());
            bump(r, "failed");
            return r;
        }
        java.io.File out = new java.io.File(dir, fileName);

        long len;
        try {
            com.tingshu.app.core.Net.DlResult dres =
                    com.tingshu.app.core.Net.download(dl, fromUrl, null, out, null, null);
            if (dres == null || !dres.ok) {
                addMsg(r, "插件包下载失败：" +
                        (dres == null ? "无响应" : nzMsg(dres.error)));
                bump(r, "failed");
                return r;
            }
            len = dres.size;
        } catch (Throwable t) {
            addMsg(r, "插件包下载失败：" +
                    (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            bump(r, "failed");
            return r;
        }
        if (len <= 0) {
            try { out.delete(); } catch (Throwable ignored) {}
            addMsg(r, "插件包下载为空（0 字节），地址可能失效");
            bump(r, "failed");
            return r;
        }

        JSONObject jr = importJar(out.getAbsolutePath(), fileName);
        // 合并统计
        int added = jr.optInt("added", 0);
        int updated = jr.optInt("updated", 0);
        int failed = jr.optInt("failed", 0);
        int skipped = jr.optInt("skipped", 0);
        try {
            r.put("added", added);
            r.put("updated", updated);
            r.put("skipped", skipped);
            r.put("failed", failed);
            r.put("file", fileName);
            r.put("size", len);
        } catch (Throwable ignored) {}
        JSONArray msgs = jr.optJSONArray("messages");
        if (msgs != null) {
            for (int i = 0; i < msgs.length(); i++) {
                addMsg(r, msgs.optString(i, ""));
            }
        }
        if (added + updated == 0) bump(r, "failed");
        return r;
    }

    private static String nzMsg(String s) {
        return s == null || s.length() == 0 ? "未知错误" : s;
    }

    // ==================== 导出 ====================

    /** 导出全部自定义源 + 内置启停状态为 JSON 字符串 */
    public static String exportJson(boolean includeBuiltinState, boolean pretty) {
        try {
            return SourceStore.exportJson(includeBuiltinState, pretty);
        } catch (Throwable t) {
            return "{}";
        }
    }

    // ==================== 自定义源列表 / 删除 / 测试 ====================

    /** 自定义源列表（供 UI 单列一组展示） */
    public static JSONArray customListJson() {
        JSONArray arr = new JSONArray();
        try {
            List<Source> cs = SourceRegistry.customs();
            for (Source s : cs) {
                JSONObject o = new JSONObject();
                o.put("id", s.id());
                o.put("name", s.name());
                o.put("desc", s.desc());
                o.put("canSearch", s.canSearch());
                o.put("enabled", SourceRepo.isEnabled(s.id()));
                o.put("builtin", false);
                o.put("kind", "config");
                if (s instanceof JsonSource) {
                    SourceConfig sc = ((JsonSource) s).config();
                    o.put("base", sc == null ? "" : sc.base);
                }
                arr.put(o);
            }
        } catch (Throwable ignored) {}
        return arr;
    }

    /**
     * 插件列表（jar 插件 + v3.0 起的 JS 脚本书源插件包）。
     *
     * <p>前端用 {@code kind} 区分两类：{@code "jar"} 走 DexClassLoader，
     * {@code "js"} 走 Rhino 引擎。两者的展示形态一致（都是"一个包 → N 个源"），
     * 所以共用一个列表，只在卡片上打个标签。
     */
    public static JSONArray pluginListJson() {
        JSONArray arr = new JSONArray();
        try {
            List<com.tingshu.app.data.PluginRepo.Plugin> ps =
                    com.tingshu.app.data.PluginRepo.all();
            for (int i = 0; i < ps.size(); i++) {
                com.tingshu.app.data.PluginRepo.Plugin p = ps.get(i);
                JSONObject o = new JSONObject();
                o.put("filePath", p.filePath);
                o.put("fileName", p.fileName);
                o.put("sourceIds", p.sourceIds == null ? "" : p.sourceIds);
                o.put("enabled", p.enabled);
                // ★ v3.0 修复：这里原来写死 "jar"，导致 JS 插件包在源管理页
                //   被当成 jar 插件展示（图标/文案都不对）。
                o.put("kind", p.isJs() ? "js" : "jar");
                // JS 插件包的 manifest 信息（包名/版本/作者）—— 从包内 manifest.json 现读，
                // 让卡片能显示「ShunSources v2.1.0」而不是一个随机目录名。
                if (p.isJs()) {
                    JSONObject meta = jsPkgMeta(p.filePath);
                    o.put("pkgName", meta.optString("name", ""));
                    o.put("pkgVersion", meta.optString("version", ""));
                    o.put("pkgAuthor", meta.optString("author", ""));
                    o.put("pkgDesc", meta.optString("description", ""));
                }
                // 插件里的源实例（若已注册）给出名字
                JSONArray names = new JSONArray();
                String[] ids = (p.sourceIds == null ? "" : p.sourceIds).split(",");
                for (int k = 0; k < ids.length; k++) {
                    Source s = SourceRegistry.byId(ids[k].trim());
                    if (s != null) {
                        JSONObject si = new JSONObject();
                        si.put("id", s.id());
                        si.put("name", s.name());
                        si.put("desc", s.desc());
                        si.put("canSearch", s.canSearch());
                        // JS 源的验证提示，前端直接在卡片列表里标出来
                        if (s instanceof com.tingshu.app.source.JsSourceAdapter) {
                            com.tingshu.app.source.JsSourceAdapter ja =
                                    (com.tingshu.app.source.JsSourceAdapter) s;
                            si.put("needsVerification", ja.needsVerification());
                            si.put("searchDelay", ja.searchDelaySeconds());
                        }
                        names.put(si);
                    }
                }
                o.put("sources", names);
                arr.put(o);
            }
        } catch (Throwable ignored) {}
        return arr;
    }

    /**
     * 读 JS 插件包目录里的 manifest.json，取 name/version/author/description。
     *
     * <p>为什么要读磁盘而不是用已注册的源反查：JS 插件的源里只存了包名，
     * 版本号/作者/说明只有 manifest 有；而用户在源管理页想看的恰恰是"装的是哪个包"。
     * 失败一律返回空对象，不影响列表渲染。
     */
    private static JSONObject jsPkgMeta(String dirPath) {
        JSONObject o = new JSONObject();
        try {
            if (Utils.empty(dirPath)) return o;
            File f = new File(dirPath, "manifest.json");
            if (!f.exists()) return o;
            String body = readFileText(f);
            if (Utils.empty(body)) return o;
            JSONObject m = com.tingshu.app.core.Json.obj(body);
            if (m == null) return o;
            o.put("name", m.optString("name", ""));
            o.put("version", m.optString("version", ""));
            o.put("author", m.optString("author", ""));
            o.put("description", m.optString("description", ""));
        } catch (Throwable ignored) {}
        return o;
    }

    /** 读一个小文本文件（manifest.json 通常 <1KB） */
    private static String readFileText(File f) {
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 256 * 1024) break;   // manifest 不该这么大，防意外
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 导入一个 jar 插件：加载 → 注册 → 持久化。
     * 已存在同路径的 jar 会重新加载覆盖（版本升级场景）。
     */
    public static JSONObject importJar(String jarPath, String fileName) {
        JSONObject r = importResult();
        if (Utils.empty(jarPath)) {
            addMsg(r, "文件路径为空");
            bump(r, "failed");
            return r;
        }
        try {
            r.put("from", "jar");
            r.put("file", fileName == null ? "" : fileName);
        } catch (Throwable ignored) {}

        // 与内置源/已有插件重复 id 的处理：允许覆盖（插件升级），但记下来
        com.tingshu.app.core.JarLoader.JarResult res =
                com.tingshu.app.core.JarLoader.load(jarPath, pluginOptDir());
        if (!res.ok) {
            addMsg(r, res.error);
            bump(r, "failed");
            return r;
        }

        // 检查与内置源冲突：插件源 id 若与内置相同则拒绝（防止插件劫持内置源）
        List<Source> accepted = new ArrayList<Source>();
        for (Source s : res.sources) {
            if (SourceRegistry.isBuiltin(s.id())) {
                addMsg(r, "跳过「" + s.name() + "」：id 与内置源冲突（" + s.id() + "）");
                bump(r, "skipped");
            } else {
                accepted.add(s);
            }
        }
        if (accepted.isEmpty()) {
            addMsg(r, "没有可用的源");
            bump(r, "failed");
            return r;
        }

        // 已存在的同 id 配置源会被插件覆盖（显示在插件组）
        int added = SourceRegistry.registerExternal(accepted);
        boolean had = com.tingshu.app.data.PluginRepo.exists(jarPath);
        com.tingshu.app.data.PluginRepo.add(jarPath, fileName);
        List<String> ids = new ArrayList<String>();
        for (Source s : accepted) ids.add(s.id());
        com.tingshu.app.data.PluginRepo.setSourceIds(jarPath, ids);
        SourceRepo.saveOrderKeep(ids);

        try {
            r.put("added", added);
            r.put("updated", had ? accepted.size() : 0);
        } catch (Throwable ignored) {}
        StringBuilder sb = new StringBuilder();
        for (Source s : accepted) {
            sb.append("已装载「").append(s.name()).append("」")
              .append(s.canSearch() ? "" : "（仅分类浏览）");
        }
        addMsg(r, sb.toString());
        return r;
    }

    private static File pluginOptDir() {
        try {
            android.content.Context c = com.tingshu.app.core.App.ctx();
            if (c != null) return c.getDir("pluginopt", 0);
        } catch (Throwable ignored) {}
        return new File("/tmp");
    }

    /**
     * ★ v3.0：导入一个 JS 脚本书源插件包（.zip）。
     *
     * <p>与 {@link #importJar} 结构完全对齐（load → 冲突检查 → registerExternal
     * → PluginRepo.add → setSourceIds → 返回统计），差别只在：
     * <ul>
     *   <li>走 {@link com.tingshu.app.core.js.JsPluginLoader#load}（Rhino 引擎）</li>
     *   <li>落地的是**目录**而非文件，PluginRepo 记的 filePath 就是那个目录</li>
     *   <li>kind 记 {@link com.tingshu.app.data.PluginRepo#KIND_JS}</li>
     * </ul>
     *
     * <p>一个包通常会注册出很多源（ShunSources 一次出 19 个），全部登记在同一个
     * filePath 下，卸载时整包一起走 {@link #removePlugin}。
     */
    public static JSONObject importJsPlugin(String zipPath, String fileName) {
        JSONObject r = importResult();
        if (Utils.empty(zipPath)) {
            addMsg(r, "文件路径为空");
            bump(r, "failed");
            return r;
        }
        try {
            r.put("from", "js");
            r.put("file", fileName == null ? "" : fileName);
        } catch (Throwable ignored) {}

        // 解包 + 加载（同包重复导入会覆盖旧目录，天然支持升级）
        com.tingshu.app.core.js.JsPluginLoader.JsResult res;
        try {
            res = com.tingshu.app.core.js.JsPluginLoader.load(zipPath, null);
        } catch (Throwable t) {
            addMsg(r, "插件加载异常：" + (t.getMessage() == null
                    ? t.getClass().getSimpleName() : t.getMessage()));
            bump(r, "failed");
            return r;
        }
        if (res == null || !res.ok) {
            addMsg(r, res == null ? "插件加载失败" : res.error);
            bump(r, "failed");
            return r;
        }

        // 包信息回给前端（导入结果弹窗里显示"装的是哪个包、什么版本"）
        try {
            r.put("pkgName", res.pkgName);
            r.put("pkgVersion", res.pkgVersion);
            r.put("pkgAuthor", res.pkgAuthor);
        } catch (Throwable ignored) {}

        // 与内置源冲突则拒绝（同 importJar 的防劫持逻辑）
        List<Source> accepted = new ArrayList<Source>();
        for (Source s : res.sources) {
            if (SourceRegistry.isBuiltin(s.id())) {
                addMsg(r, "跳过「" + s.name() + "」：id 与内置源冲突（" + s.id() + "）");
                bump(r, "skipped");
            } else {
                accepted.add(s);
            }
        }
        if (accepted.isEmpty()) {
            addMsg(r, "插件里没有可用的源");
            bump(r, "failed");
            return r;
        }

        int added = SourceRegistry.registerExternal(accepted);
        String dir = res.dir;
        boolean had = com.tingshu.app.data.PluginRepo.exists(dir);
        com.tingshu.app.data.PluginRepo.add(dir, fileName, com.tingshu.app.data.PluginRepo.KIND_JS);
        List<String> ids = new ArrayList<String>();
        for (Source s : accepted) ids.add(s.id());
        com.tingshu.app.data.PluginRepo.setSourceIds(dir, ids);
        SourceRepo.saveOrderKeep(ids);

        try {
            r.put("added", had ? 0 : added);
            r.put("updated", had ? accepted.size() : 0);
        } catch (Throwable ignored) {}

        // 提示文案：把"这个包一共装进来几个源、其中几个只能浏览、几个要验证"讲清楚
        int browsable = 0, verify = 0;
        StringBuilder names = new StringBuilder();
        for (Source s : accepted) {
            if (!s.canSearch()) browsable++;
            if (s instanceof com.tingshu.app.source.JsSourceAdapter
                    && ((com.tingshu.app.source.JsSourceAdapter) s).needsVerification()) {
                verify++;
            }
            if (names.length() < 200) {
                if (names.length() > 0) names.append("、");
                names.append(s.name());
            }
        }
        String head = "已装载插件包「" + res.pkgName + "」"
                + (res.pkgVersion.length() > 0 ? " v" + res.pkgVersion : "")
                + "，共 " + accepted.size() + " 个书源";
        addMsg(r, head);
        addMsg(r, "书源清单：" + names + (names.length() >= 200 ? " 等" : ""));
        if (browsable > 0) {
            addMsg(r, "其中 " + browsable + " 个源不支持关键词搜索，只能用分类浏览");
        }
        if (verify > 0) {
            addMsg(r, "有 " + verify + " 个源在搜索前需要站点验证，"
                    + "首次搜索若失败，请在弹出的验证页里过一下验证码再重试");
        }
        return r;
    }

    /**
     * ★ v3.4：导入 Python 源（.py 单文件，或含多个 .py 的 .zip 包）。
     *
     * <p>与 {@link #importJsPlugin} 结构对齐：落地 → 逐个加载 → 登记 → 热重载。
     * 差别在于落地位置是 {@code <filesDir>/python_sources/}（扁平，文件名即源 id），
     * 加载走 {@link com.tingshu.app.python.PythonHost}（真实 CPython 3.8）。
     *
     * <p>兼容两种形态：
     * <ul>
     *   <li>{@code xxx.py}        —— 直接存一个源</li>
     *   <li>{@code xxx.zip}       —— 解出里面所有 .py（支持目录层级）</li>
     * </ul>
     *
     * <p>Python 源常见的是「一个文件一个源」，但也有人把一批源打进 zip，
     * 所以两种都收。单个源加载失败只跳过它，不整体失败。
     */
    public static JSONObject importPySources(String path, String fileName) {
        JSONObject r = importResult();
        if (Utils.empty(path)) {
            addMsg(r, "文件路径为空");
            bump(r, "failed");
            return r;
        }
        try {
            r.put("from", "python");
            r.put("file", fileName == null ? "" : fileName);
        } catch (Throwable ignored) {}

        android.content.Context ctx;
        try {
            ctx = com.tingshu.app.core.App.ctx();
        } catch (Throwable t) {
            ctx = null;
        }
        if (ctx == null) {
            addMsg(r, "无法获取应用上下文");
            bump(r, "failed");
            return r;
        }

        // ★ v3.7.9：这里必须**阻塞等**解释器就绪，不能像以前那样调 available()
        //   一票否决 —— 因为 available() 现在改成非阻塞了（首次调用只点火不等），
        //   用户刚装完 App 就来导入 .py，会因为"后台还在解压"而被误报
        //   "Python 运行时不可用"。导入是用户主动操作，等几秒是合理的。
        //   本方法整体跑在 bg()（后台线程）里，阻塞不会卡 UI。
        if (!com.tingshu.app.python.PythonHost.ensureStarted(ctx)) {
            String why = com.tingshu.app.python.PythonHost.lastError();
            addMsg(r, "Python 运行时不可用"
                    + (why == null || why.isEmpty()
                        ? "（解释器启动失败，请重启 App 后重试；若仍失败请反馈机型）"
                        : "：" + why));
            bump(r, "failed");
            return r;
        }

        File dir;
        try {
            dir = com.tingshu.app.python.PythonHost.sourcesDir(ctx);
        } catch (Throwable t) {
            addMsg(r, "无法创建源目录");
            bump(r, "failed");
            return r;
        }

        String low = fileName == null ? "" : fileName.toLowerCase();
        List<File> pyFiles = new ArrayList<File>();

        try {
            if (low.endsWith(".zip")) {
                pyFiles = unzipPyFiles(new File(path), dir);
                if (pyFiles.isEmpty()) {
                    addMsg(r, "压缩包里没有找到 .py 文件");
                    bump(r, "failed");
                    return r;
                }
            } else {
                // 单文件
                File dst = new File(dir, sanitizePyName(fileName, path));
                copyFile(new File(path), dst);
                pyFiles.add(dst);
            }
        } catch (Throwable t) {
            addMsg(r, "解包失败：" + (t.getMessage() == null
                    ? t.getClass().getSimpleName() : t.getMessage()));
            bump(r, "failed");
            return r;
        }

        // 逐个加载
        List<String> loaded = new ArrayList<String>();
        List<String> displayNames = new ArrayList<String>();
        // ★ 按「源自称的名字」去重：同一个包里常混着同一源的多个拷贝
        //   （例如 书.zip 里 [书]ximalaya.py 与 [书]喜马拉雅.py 都返回 getName()=="ximalaya"，
        //     [书]去读书.py 与 [书]阅读助手.py 都叫「阅读助手」）。
        //   都装进去会在源管理里出现同名条目，用户分不清该启用哪个。
        java.util.Set<String> seenNames = new java.util.HashSet<String>();
        for (File f : pyFiles) {
            String fn = f.getName();
            if (!fn.endsWith(".py")) continue;
            String pid = "py_" + fn.substring(0, fn.length() - 3);
            if (SourceRegistry.isBuiltin(pid)) {
                addMsg(r, "跳过「" + fn + "」：与内置源 id 冲突");
                bump(r, "skipped");
                continue;
            }
            String disp;
            try {
                disp = com.tingshu.app.python.PythonHost.loadSource(pid, f.getAbsolutePath());
            } catch (Throwable t) {
                disp = null;
            }
            if (disp == null) {
                addMsg(r, "「" + fn + "」加载失败："
                        + com.tingshu.app.python.PythonHost.lastError());
                bump(r, "failed");
                continue;
            }
            // 显示名撞车 → 卸载刚装的这个，跳过（保留先装上的那份）
            String dedupKey = disp.trim().toLowerCase();
            if (!dedupKey.isEmpty() && !seenNames.add(dedupKey)) {
                try {
                    com.tingshu.app.python.PythonHost.unloadSource(pid);
                } catch (Throwable ignored) {}
                addMsg(r, "跳过「" + fn + "」：与「" + disp + "」是同一书源的重复文件");
                bump(r, "skipped");
                continue;
            }
            loaded.add(pid);
            displayNames.add(disp.isEmpty() ? fn : disp);
            bump(r, "ok");
            // ★ 跨次去重：这个书源之前以别的文件名装过（例如先传了单个 .py、
            //   后来又传了整包 zip），把旧的那份卸掉，只留这次的新版本，
            //   否则源管理里会出现两个同名条目、启用了哪个都不确定。
            if (!disp.isEmpty()) {
                com.tingshu.app.python.PythonHost.unloadByName(disp, pid);
            }
        }

        SourceRegistry.reload();

        try {
            r.put("added", loaded.size());
            r.put("updated", 0);
            r.put("sourceIds", new org.json.JSONArray(loaded));
        } catch (Throwable ignored) {}

        if (loaded.isEmpty()) {
            addMsg(r, "没有成功加载任何 Python 源");
            if (((Number) safeGet(r, "ok", 0)).intValue() == 0) bump(r, "failed");
            return r;
        }

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < displayNames.size(); i++) {
            if (names.length() > 0) names.append("、");
            names.append(displayNames.get(i));
        }
        addMsg(r, "已装载 " + loaded.size() + " 个 Python 书源：" + names);
        addMsg(r, "Python 源由内置 CPython 3.8 直接执行，请到「源管理」里启用后使用");
        return r;
    }

    private static Object safeGet(JSONObject o, String k, Object d) {
        try {
            return o.has(k) ? o.get(k) : d;
        } catch (Throwable t) {
            return d;
        }
    }

    /** 把 zip 里的 .py 解到目标目录（扁平化，重名加前缀避免互相覆盖） */
    private static List<File> unzipPyFiles(File zip, File dir) throws Exception {
        List<File> out = new ArrayList<File>();
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip);
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            int guard = 0;
            while (en.hasMoreElements() && guard++ < 5000) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name == null) continue;
                // 防路径穿越
                if (name.contains("..")) continue;
                int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                String base = slash >= 0 ? name.substring(slash + 1) : name;
                if (!base.toLowerCase().endsWith(".py")) continue;
                if (base.startsWith("__")) continue;   // __init__ 之类跳过
                File dst = new File(dir, sanitizePyName(base, null));
                // 同名冲突：加序号
                int n = 2;
                while (dst.exists()) {
                    String stem = base.substring(0, base.length() - 3);
                    dst = new File(dir, stem + "_" + (n++) + ".py");
                }
                java.io.InputStream in = zf.getInputStream(e);
                try {
                    copyStream(in, dst);
                } finally {
                    try { in.close(); } catch (Throwable ignored) {}
                }
                out.add(dst);
            }
        } finally {
            try { zf.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /** 归一名：只保留安全字符，避免中文/空格导致 Python 侧路径问题 */
    private static String sanitizePyName(String name, String fallbackPath) {
        String base = name;
        if (Utils.empty(base)) {
            base = fallbackPath == null ? "source.py" : new File(fallbackPath).getName();
        }
        // ★ 先剥掉扩展名再净化，最后统一补回 —— 点号不在白名单里，
        //   若把带 .py 的整串丢进净化循环，扩展名会被冲成 "_py"，
        //   文件就变成没有后缀的普通文件，Python 侧 importlib 直接报
        //   「无法为文件创建模块规格」，整个 zip 批量导入会大面积失败。
        if (base.toLowerCase().endsWith(".py")) base = base.substring(0, base.length() - 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String s = sb.toString();
        // 去掉首尾下划线（中文名净化后常剩一串下划线，首字符是 _ 的模块名虽然合法，
        // 但会让 Python 侧「模块名与文件名对不上」，干脆统一成无下划线开头）
        while (s.startsWith("_")) s = s.substring(1);
        while (s.endsWith("_")) s = s.substring(0, s.length() - 1);
        // 全被净化掉（纯中文/纯符号名）就退回一个稳定名，避免空模块名
        if (s.isEmpty()) s = "src_" + Math.abs((name == null ? "" : name).hashCode());
        if (Character.isDigit(s.charAt(0))) s = "s_" + s;
        return s + ".py";
    }

    private static void copyStream(java.io.InputStream in, File dst) throws Exception {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(dst);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } finally {
            try { fos.close(); } catch (Throwable ignored) {}
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(src);
        try {
            copyStream(fis, dst);
        } finally {
            try { fis.close(); } catch (Throwable ignored) {}
        }
    }

    /** 删除一个 jar 插件（含源启停记录与 jar 文件本身），并热重载注册表 */
    public static boolean removePlugin(String filePath) {
        if (Utils.empty(filePath)) return false;
        boolean ok = com.tingshu.app.data.PluginRepo.remove(filePath);
        if (ok) SourceRegistry.reload();
        return ok;
    }

    /** 启停一个 jar 插件（停用后它的源从注册表移除） */
    public static void setPluginEnabled(String filePath, boolean enabled) {
        com.tingshu.app.data.PluginRepo.setEnabled(filePath, enabled);
        SourceRegistry.reload();
    }

    /** 删除自定义源（同时清掉它的启停记录，避免残留） */
    public static boolean removeCustom(String id) {
        if (Utils.empty(id)) return false;
        if (SourceRegistry.isBuiltin(id)) return false;
        boolean ok = SourceStore.remove(id);
        try {
            SourceRepo.forget(id);
        } catch (Throwable ignored) {}
        SourceRegistry.reload();
        return ok;
    }

    /**
     * ★ v3.7.4：按 id 彻底删除一个源，自动判断它属于哪一类。
     *
     * <p>为什么需要它：{@link #removeCustom} 只会删 SourceStore 里的 JSON 配置，
     * 对插件源和 Python 源完全无效。而 Python 源（{@code py_} 前缀）在源管理里
     * 看起来和普通源一样，用户点「删除」却没有任何反应 —— 这是 v3.4 起就存在的缺陷。
     *
     * <p>分派规则：
     * <ul>
     *   <li>{@code py_} 前缀 → 卸载 Python 模块 + 删掉磁盘上的 .py 文件</li>
     *   <li>{@code JsonSource} → SourceStore 记录</li>
     *   <li>其它已注册源（jar / js 插件）→ 摘除它的源 id</li>
     *   <li>已不在注册表里 → 兜底清一遍 SourceStore，避免残留禁用记录</li>
     * </ul>
     * 内置源一律拒绝。
     */
    public static boolean removeSourceById(String id) {
        if (Utils.empty(id)) return false;
        if (SourceRegistry.isBuiltin(id)) return false;

        // ① Python 源：模块 + 磁盘文件都要清掉，否则下次冷启动又被扫回来
        if (id.startsWith("py_")) {
            boolean ok = false;
            try {
                com.tingshu.app.python.PythonHost.unloadSource(id);
                ok = true;
            } catch (Throwable ignored) {}
            try {
                android.content.Context ctx = com.tingshu.app.core.App.ctx();
                if (ctx != null) {
                    String fn = id.substring(3) + ".py";
                    java.io.File f = new java.io.File(
                            com.tingshu.app.python.PythonHost.sourcesDir(ctx), fn);
                    if (f.exists() && f.isFile()) ok = f.delete() || ok;
                }
            } catch (Throwable ignored) {}
            try {
                SourceRepo.forget(id);
            } catch (Throwable ignored) {}
            SourceRegistry.reload();
            return ok;
        }

        // ② 其它：按注册表里的真实类型分派
        Source s = SourceRegistry.byId(id);
        if (s instanceof JsonSource) {
            boolean ok = SourceStore.remove(id);
            try {
                SourceRepo.forget(id);
            } catch (Throwable ignored) {}
            SourceRegistry.reload();
            return ok;
        }
        if (s != null) {
            return removeSourceCompletely(s);
        }

        // ③ 兜底：注册表里已经没有它了，清干净记录即可
        boolean ok = SourceStore.remove(id);
        try {
            SourceRepo.forget(id);
        } catch (Throwable ignored) {}
        SourceRegistry.reload();
        return ok;
    }

    /** 测试自定义源 / jar 插件源：跑一次真实搜索，返回可读结论 */
    public static JSONObject testCustom(String id) {
        JSONObject o = new JSONObject();
        boolean ok = false;
        String msg;
        try {
            Source s = SourceRegistry.byId(id);
            if (s == null) {
                msg = "源不存在，请重新导入";
            } else if (SourceRegistry.isBuiltin(id)) {
                // ★ v3.7.14：内置源判断必须放在 JsonSource 之前 —— 内置源里也有
                //   JsonSource 实现，原来那个顺序会把内置源当成"自定义 JSON 源"去跑
                //   selfTest()，给出驴唇不对马嘴的结论。
                msg = "请用内置源的测试按钮";
            } else if (s instanceof JsonSource) {
                String r = ((JsonSource) s).selfTest();
                ok = r != null && r.startsWith("搜索正常");
                msg = r;
            } else if (s instanceof com.tingshu.app.python.PySourceAdapter) {
                // ★ v3.7.14：Python 源单开一路。
                //
                //   原来它掉进下面"jar 插件源"的兜底分支里：先试 search("三国")，
                //   失败后再用反射去取一个叫 BASE 的静态字段当网址 —— 而
                //   PySourceAdapter 根本没有 BASE 字段，于是回落到
                //   https://example.com，必然"无响应"。
                //   用户看到的"测试连通无效"就是这么来的：结论永远是错的。
                //
                //   现在直接问 Python 侧：拿这个源跑一次真实搜索，
                //   以"能不能搜出东西"作为连通结论。
                com.tingshu.app.python.PySourceAdapter pa =
                        (com.tingshu.app.python.PySourceAdapter) s;
                if (!com.tingshu.app.python.PythonHost.available()) {
                    msg = "Python 运行时未就绪，请稍后重试";
                } else if (!pa.canSearch()) {
                    msg = testByBrowse(s);
                } else {
                    List<com.tingshu.app.data.Models.Book> r = pa.search("评书", 1);
                    int n = r == null ? 0 : r.size();
                    if (n > 0) {
                        ok = true;
                        msg = "连通正常，搜索命中 " + n + " 条";
                    } else {
                        // 收紧措辞：拿不到结果要么是站点变了，要么是这个关键词没货，
                        // 都给用户一个"能自己判断"的提示，而不是含糊的"接口可访问"。
                        msg = "站点已响应，但没搜出结果（可能被限流或关键词无货）";
                    }
                }
            } else {
                // jar / JS 插件源：通用测试
                if (s.canSearch()) {
                    List<com.tingshu.app.data.Models.Book> r = s.search("三国", 1);
                    int n = r == null ? 0 : r.size();
                    ok = n > 0;
                    msg = ok ? ("搜索正常，命中 " + n + " 条") : "接口可访问但未解析出结果";
                } else {
                    msg = testByBrowse(s);
                }
            }
        } catch (Throwable t) {
            msg = "测试失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
        try {
            o.put("id", id);
            o.put("ok", ok);
            o.put("msg", msg);
        } catch (Throwable ignored) {}
        return o;
    }

    /**
     * ★ v3.7.14：不能搜索的源（只能浏览分类）改测「分类页能否拿到内容」。
     *
     * <p>旧实现是拿反射去抠一个叫 BASE 的静态字段拼 URL 请求 example.com，
     * 对绝大多数源都不成立，只会稳定地报"无响应"。
     */
    private static String testByBrowse(Source s) {
        if (!(s instanceof BrowsableSource)) return "该源不支持测试";
        try {
            List<Category> cs = ((BrowsableSource) s).categories();
            if (cs == null || cs.isEmpty()) return "站点可达，但没解析出分类";
            return "站点可达，发现 " + cs.size() + " 个分类";
        } catch (Throwable t) {
            return "站点不可达：" + (t.getMessage() == null
                    ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    // ==================== ★ v3.1 源批量验证 ====================
    //
    // 需求：一次性验一批源 → 失效的自动关掉 → 重复的（同域名）只留优先级高的。
    //
    // 与 testCustom 的区别：那个是「用户点一个源、想看一句人话」，
    // 这个是「后台批量跑、要结构化结论」。所以这里返回 level 枚举而不是文案。

    /** level 取值 */
    public static final String LV_OK = "ok";         // 能搜、有结果
    public static final String LV_BROWSE = "browse"; // 搜不了但分类页可达
    public static final String LV_VERIFY = "verify"; // 站点要验证码，不算死
    public static final String LV_DEAD = "dead";     // 确实不可用
    public static final String LV_SKIP = "skip";     // 内置源，不参与验证

    /**
     * 验证单个源。
     *
     * <p>探测顺序（从强到弱）：
     * <ol>
     *   <li>JSON 源 → 直接用它的 {@code selfTest()}（它自己最清楚怎么算通）</li>
     *   <li>声明了搜索 → 真跑一次 {@code search("测试", 1)}</li>
     *   <li>搜不动但能分类浏览 → {@code categories()} 拿分类再 {@code browse} 一页</li>
     *   <li>都不行 → 判死</li>
     * </ol>
     *
     * <p>★ 关键判断：JS 源里有一批站点是**反爬型的**（乐听吧/米听书/囧贼/一夜幻听），
     * 它们脚本里带 {@code searchVerificationUrl}，裸请求必然返空。
     * 这类源码里已经声明了「我需要验证」，所以失败要标 {@link #LV_VERIFY} 而不是
     * {@link #LV_DEAD} —— 否则自动验证会把 4 个本来能用的源全关掉。
     */
    public static JSONObject verifySource(Source s) {
        JSONObject o = new JSONObject();
        String level = LV_DEAD;
        String msg = "";
        int hits = 0;
        try {
            if (s == null) {
                put(o, "level", LV_DEAD);
                put(o, "msg", "源不存在");
                return o;
            }
            final String id = s.id();
            final String host = SourceRegistry.hostOf(s);
            put(o, "id", id);
            put(o, "name", s.name());
            put(o, "host", host);

            if (SourceRegistry.isBuiltin(id)) {
                put(o, "level", LV_SKIP);
                put(o, "ok", true);
                put(o, "msg", "内置源，跳过验证");
                return o;
            }

            // 1) JSON 源：交给它自己的自检
            if (s instanceof JsonSource) {
                String r = ((JsonSource) s).selfTest();
                boolean ok = r != null && r.startsWith("搜索正常");
                level = ok ? LV_OK : (isSoftFail(r) ? LV_VERIFY : LV_DEAD);
                msg = r;
                put(o, "level", level);
                put(o, "ok", ok || LV_VERIFY.equals(level));
                put(o, "msg", msg);
                return o;
            }

            // 2) 能搜就搜
            if (s.canSearch()) {
                try {
                    List<com.tingshu.app.data.Models.Book> r = s.search("测试", 1);
                    if (r != null && !r.isEmpty()) {
                        put(o, "level", LV_OK);
                        put(o, "ok", true);
                        put(o, "hits", r.size());
                        put(o, "msg", "搜索正常，命中 " + r.size() + " 条");
                        return o;
                    }
                } catch (Throwable t) {
                    msg = briefErr(t);
                }
            }

            // 3) 搜不动 → 试分类浏览（单田芳评书网这类"仅分类"的源只能这样验）
            if (s instanceof BrowsableSource) {
                BrowsableSource bs = (BrowsableSource) s;
                try {
                    if (bs.canBrowse()) {
                        List<Category> cats = bs.categories();
                        if (cats != null && !cats.isEmpty()) {
                            List<com.tingshu.app.data.Models.Book> r =
                                    bs.browse(cats.get(0).id, 1);
                            if (r != null && !r.isEmpty()) {
                                put(o, "level", LV_BROWSE);
                                put(o, "ok", true);
                                put(o, "hits", r.size());
                                put(o, "msg", "搜索不可用，但分类页可达（"
                                        + cats.size() + " 个分类）");
                                return o;
                            }
                        }
                    }
                } catch (Throwable t) {
                    if (msg.length() == 0) msg = briefErr(t);
                }
            }

            // 4) 判死；但声明过"需要验证"的源留一线生机
            boolean needsVerify = false;
            try {
                needsVerify = (s instanceof com.tingshu.app.source.JsSourceAdapter)
                        && ((com.tingshu.app.source.JsSourceAdapter) s).needsVerification();
            } catch (Throwable ignored) {}

            if (needsVerify) {
                put(o, "level", LV_VERIFY);
                put(o, "ok", true);
                put(o, "msg", "该源需要站点验证，未通过前搜不到结果（不算失效）");
            } else {
                put(o, "level", LV_DEAD);
                put(o, "ok", false);
                put(o, "msg", msg.length() == 0 ? "无响应或解析不到内容" : msg);
            }
        } catch (Throwable t) {
            put(o, "level", LV_DEAD);
            put(o, "ok", false);
            put(o, "msg", briefErr(t));
        }
        return o;
    }

    /** selfTest 的文案里含这些字样说明"不是源坏了"，别判死 */
    private static boolean isSoftFail(String r) {
        if (r == null) return false;
        return r.contains("需要验证") || r.contains("验证码")
                || r.contains("未配置搜索") || r.contains("仅支持浏览");
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Throwable ignored) {}
    }

    private static String briefErr(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    /** 批量验证的进度回调（可为 null） */
    public interface VerifyCb {
        /** @param done 已完成数 / total 总数 / name 当前源名 / level 当前源的结论 */
        void onProgress(int done, int total, String id, String name, String level);
    }

    /**
     * ★ v3.1 批量验证主流程。
     *
     * <p>顺序很重要：**先去重 → 再验证 → 最后关停**。
     * <ul>
     *   <li>先去重：重复的源少验一个，省一次网络往返（19 个 JS 源里重复的不少）</li>
     *   <li>后关停：只关真的验过且判死的，不误伤刚去重留下的那个</li>
     * </ul>
     *
     * <p>★ 串行执行：一个源一次。并发跑会把网络和 JS scope 同时打爆
     * （所有 JS 源共享一个 Rhino scope，本来就有锁，并发只会互相等）。
     *
     * @param scope         "all" / "enabled" / "group:xxx"
     * @param autoCloseDead 失效的自动停用
     * @param dedup         同域名重复的自动删除（保留优先级高的）
     * @param cb            进度回调，可为 null
     */
    public static JSONObject verifyAll(String scope, boolean autoCloseDead,
                                       boolean dedup, VerifyCb cb) {
        JSONObject r = importResult();
        put(r, "from", "verify");
        List<String> closed = new ArrayList<String>();
        List<String> removed = new ArrayList<String>();

        List<Source> list;
        try {
            list = pickForVerify(scope);
        } catch (Throwable t) {
            addMsg(r, "取源列表失败：" + briefErr(t));
            bump(r, "failed");
            return r;
        }
        if (list.isEmpty()) {
            addMsg(r, "没有需要验证的源");
            return r;
        }

        // ---- 1. 先去重 ----
        int deduped = 0;
        if (dedup) {
            try {
                List<Source> dupes = findDuplicates(list);
                for (Source d : dupes) {
                    if (removeSourceCompletely(d)) {
                        deduped++;
                        removed.add(d.name() + "（" + SourceRegistry.hostOf(d) + "）");
                        list.remove(d);
                    }
                }
                if (deduped > 0) {
                    addMsg(r, "发现 " + deduped + " 个重复源（同域名），已删除并保留优先级更高的");
                }
            } catch (Throwable t) {
                addMsg(r, "去重失败：" + briefErr(t));
            }
        }
        put(r, "deduped", deduped);

        // ---- 2. 逐个验证 ----
        int total = list.size();
        int done = 0;
        int ok = 0, browse = 0, verify = 0, dead = 0, skip = 0, closedN = 0;
        List<String> deadNames = new ArrayList<String>();
        List<String> verifyNames = new ArrayList<String>();

        for (int i = 0; i < list.size(); i++) {
            Source s = list.get(i);
            JSONObject vr;
            try {
                vr = verifySource(s);
            } catch (Throwable t) {
                vr = new JSONObject();
                put(vr, "level", LV_DEAD);
                put(vr, "msg", briefErr(t));
            }
            String lv = vr.optString("level", LV_DEAD);
            done++;

            if (LV_OK.equals(lv)) ok++;
            else if (LV_BROWSE.equals(lv)) browse++;
            else if (LV_VERIFY.equals(lv)) {
                verify++;
                verifyNames.add(s.name());
            } else if (LV_SKIP.equals(lv)) skip++;
            else {
                dead++;
                deadNames.add(s.name());
                // 失效的直接关（仅当用户勾了自动关闭，且它本来是启用状态）
                if (autoCloseDead && SourceRepo.isEnabled(s.id())) {
                    try {
                        SourceRepo.setEnabled(s.id(), false);
                        closedN++;
                        closed.add(s.name());
                    } catch (Throwable ignored) {}
                }
            }

            if (cb != null) {
                try {
                    cb.onProgress(done, total, s.id(), s.name(), lv);
                } catch (Throwable ignored) {}
            }

            // ★ 站点限流：JS 源自己声明了 searchDelaySeconds 的，按它的要求歇一下。
            //   不给间隔的话，像乐听吧这种限流站点会连续返空，把能用的源误判成死。
            int delay = 0;
            try {
                if (s instanceof com.tingshu.app.source.JsSourceAdapter) {
                    delay = ((com.tingshu.app.source.JsSourceAdapter) s).searchDelaySeconds();
                }
            } catch (Throwable ignored) {}
            if (delay > 0 && i < list.size() - 1) {
                try {
                    Thread.sleep(Math.min(delay, 10) * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // ---- 3. 汇总 ----
        put(r, "checked", done);
        put(r, "ok", ok);
        put(r, "browse", browse);
        put(r, "verify", verify);
        put(r, "dead", dead);
        put(r, "skip", skip);
        put(r, "closed", closedN);
        put(r, "removedList", toArr(removed));
        put(r, "deadList", toArr(deadNames));
        put(r, "verifyList", toArr(verifyNames));

        StringBuilder sb = new StringBuilder();
        sb.append("验证完成：共 ").append(done).append(" 个，正常 ").append(ok + browse);
        if (verify > 0) sb.append("，需验证 ").append(verify);
        if (dead > 0) sb.append("，失效 ").append(dead);
        if (closedN > 0) sb.append("，已关闭 ").append(closedN);
        if (deduped > 0) sb.append("，去重 ").append(deduped);
        addMsg(r, sb.toString());
        put(r, "summary", sb.toString());

        if (dead > 0) {
            StringBuilder d = new StringBuilder("失效源：");
            for (int i = 0; i < deadNames.size() && i < 12; i++) {
                if (i > 0) d.append("、");
                d.append(deadNames.get(i));
            }
            if (deadNames.size() > 12) d.append(" 等");
            addMsg(r, d.toString());
        }
        if (verify > 0) {
            StringBuilder v = new StringBuilder("需验证源（未关闭）：");
            for (int i = 0; i < verifyNames.size() && i < 12; i++) {
                if (i > 0) v.append("、");
                v.append(verifyNames.get(i));
            }
            addMsg(r, v.toString());
        }

        return r;
    }
    private static JSONArray toArr(List<String> l) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < l.size(); i++) a.put(l.get(i));
        return a;
    }

    /** 按 scope 挑出要验证的源 */
    private static List<Source> pickForVerify(String scope) {
        List<Source> out = new ArrayList<Source>();
        List<Source> all = SourceRegistry.all();
        String sc = scope == null ? "all" : scope;
        for (Source s : all) {
            if (s == null || s.id() == null) continue;
            if (SourceRegistry.isBuiltin(s.id())) continue;   // 内置源不验（现在也没有）
            if ("enabled".equals(sc)) {
                if (!SourceRepo.isEnabled(s.id())) continue;
            } else if (sc.startsWith("group:")) {
                String g = sc.substring(6);
                if (!g.equals(originOf(s))) continue;
            }
            out.add(s);
        }
        return out;
    }

    /** 源的导入方式（与前端 groupOf 的分组 key 对齐） */
    private static String originOf(Source s) {
        try {
            if (s instanceof JsonSource) {
                com.tingshu.app.data.SourceConfig cfg =
                        com.tingshu.app.data.SourceStore.byId(s.id());
                String o = cfg == null ? "" : cfg.origin;
                return Utils.empty(o) ? "other" : o;
            }
            if (s instanceof com.tingshu.app.source.JsSourceAdapter) return "js";
            return "jar";
        } catch (Throwable t) {
            return "other";
        }
    }

    /**
     * 找出「指向同一站点」的多余源。
     *
     * <p>保留优先级（高者留）：
     * <pre>自定义 JSON 源 &gt; JS 插件源 &gt; jar 插件源</pre>
     * 理由：JSON 源是用户手工加的、可控性最强；插件源整包更新时会覆盖掉用户的删除。
     * 同级并列时保留「先注册的」那个（ALL 是 LinkedHashMap，顺序即装载顺序）。
     *
     * @return 应该被删掉的源（保留的那批不在里面）
     */
    private static List<Source> findDuplicates(List<Source> list) {
        List<Source> out = new ArrayList<Source>();
        java.util.Map<String, Source> best = new java.util.HashMap<String, Source>();
        for (int i = 0; i < list.size(); i++) {
            Source s = list.get(i);
            String host = SourceRegistry.hostOf(s);
            if (Utils.empty(host)) continue;          // 拿不到域名的（如 "https://example.com"）不参与
            if ("example.com".equals(host)) continue; // 反射兜底值，不是真域名
            Source cur = best.get(host);
            if (cur == null) {
                best.put(host, s);
            } else if (priorityOf(s) > priorityOf(cur)) {
                out.add(cur);      // 原来那个降级为待删
                best.put(host, s);
            } else {
                out.add(s);        // 新的这个不如已有的，删新的
            }
        }
        return out;
    }

    /** 源优先级：越大越该保留 */
    private static int priorityOf(Source s) {
        if (s instanceof JsonSource) return 30;
        if (s instanceof com.tingshu.app.source.JsSourceAdapter) return 20;
        return 10;
    }

    /** 彻底删掉一个源：JSON 源走 SourceStore，插件源走 PluginRepo 的源 id 摘除 */
    private static boolean removeSourceCompletely(Source s) {
        if (s == null || s.id() == null) return false;
        try {
            if (s instanceof JsonSource) {
                boolean ok = SourceStore.remove(s.id());
                SourceRepo.forget(s.id());
                SourceRegistry.reload();
                return ok;
            }
            // 插件源：不能删整个包（包里还有别的源），只把它的 id 从插件记录里摘掉，
            // 再从注册表移除。插件重新导入时会带着它回来，这是可接受的。
            removePluginSourceId(s.id());
            SourceRegistry.reload();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把某个源 id 从所有插件记录的 source_ids 里摘掉 */
    private static void removePluginSourceId(String id) {
        try {
            List<com.tingshu.app.data.PluginRepo.Plugin> ps =
                    com.tingshu.app.data.PluginRepo.all();
            for (int i = 0; i < ps.size(); i++) {
                com.tingshu.app.data.PluginRepo.Plugin p = ps.get(i);
                String ids = p.sourceIds == null ? "" : p.sourceIds;
                if (ids.indexOf(id) < 0) continue;
                List<String> keep = new ArrayList<String>();
                String[] parts = ids.split(",");
                for (int k = 0; k < parts.length; k++) {
                    String t = parts[k].trim();
                    if (t.length() > 0 && !t.equals(id)) keep.add(t);
                }
                com.tingshu.app.data.PluginRepo.setSourceIds(p.filePath, keep);
            }
        } catch (Throwable ignored) {}
    }

    // ==================== 源顺序 ====================

    /** 上移/下移一个源，返回新的顺序数组 */
    public static JSONArray moveSource(String id, int delta) {
        try {
            List<Source> all = SourceRegistry.all();
            List<String> ids = new ArrayList<String>();
            for (Source s : all) ids.add(s.id());
            int i = ids.indexOf(id);
            if (i >= 0 && delta != 0) {
                int j = i + delta;
                if (j >= 0 && j < ids.size()) {
                    String t = ids.get(i);
                    ids.set(i, ids.get(j));
                    ids.set(j, t);
                    SourceRepo.saveOrder(ids);
                }
            }
        } catch (Throwable ignored) {}
        return allSourcesJson();
    }

    /** 重命名源（别名） */
    public static JSONArray renameSource(String id, String alias) {
        try {
            SourceRepo.setAlias(id, alias);
        } catch (Throwable ignored) {}
        return allSourcesJson();
    }

    /** 回调包装，方便 Bridge 一行转发 */
    public static void replySources(WebView web, Handler ui, String fn) {
        reply(web, ui, fn, new JSONObject());
    }

    /** 源显示名：别名优先，再是源自带名，最后兜底 id */
    public static String sourceNameOf(String id) {
        if (Utils.empty(id)) return "";
        try {
            String a = SourceRepo.alias(id);
            if (!Utils.empty(a)) return a;
            Source s = SourceRegistry.byId(id);
            if (s != null && !Utils.empty(s.name())) return s.name();
        } catch (Throwable ignored) {}
        return id;
    }

    /** 全部源的名字映射，给前端缓存 */
    public static JSONObject sourceNamesJson() {
        JSONObject o = new JSONObject();
        try {
            List<Source> all = SourceRegistry.all();
            for (Source s : all) {
                String a = SourceRepo.alias(s.id());
                o.put(s.id(), Utils.empty(a) ? s.name() : a);
            }
        } catch (Throwable ignored) {}
        return o;
    }
}
