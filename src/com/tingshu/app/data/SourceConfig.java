package com.tingshu.app.data;

import com.tingshu.app.core.Json;
import com.tingshu.app.core.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义源的配置模型（纯数据 + JSON 解析，不含任何网络/IO）。
 *
 * 设计目标：不写代码就能加一个源。用户在 JSON 里声明「怎么搜、怎么翻页、
 * 怎么从列表项里取书名/链接、怎么从详情页取章节、怎么从章节页取音频直链」，
 * 由 {@code JsonSource} 这个通用引擎解释执行。
 *
 * JSON 结构（数组里每个元素是一个源）：
 * <pre>
 * {
 *   "id": "mysite",                 // 必填，全局唯一，只能含字母数字 _ -
 *   "name": "我的站点",              // 必填，展示名
 *   "desc": "自定义源",              // 选填
 *   "base": "https://example.com",  // 必填，用于相对路径补全
 *   "headers": {"Referer": "..."},  // 选填，全局附加请求头
 *   "search": { ... },              // 选填，缺省则不能搜索
 *   "detail": { ... },              // 必填
 *   "audio":  { ... }               // 必填
 * }
 * </pre>
 *
 * 三段各自的字段见对应内部类的注释。
 */
public final class SourceConfig {

    /** 源 id 合法字符 */
    private static final String ID_OK = "[A-Za-z0-9_\\-]{1,48}";

    public String id = "";
    public String name = "";
    public String desc = "";
    public String base = "";
    /** 全局附加请求头，如 Referer / User-Agent（可空） */
    public JSONObject headers;

    /** 搜索段，为 null 表示该源不支持关键词搜索 */
    public Search search;
    /** 详情段 */
    public Detail detail;
    /** 取链段 */
    public Audio audio;
    /** 分类浏览段，为 null 表示该源不参与「发现」页 */
    public Browse browse;

    /** 原始 JSON（保存时原样存库，方便导出/编辑不丢字段） */
    public String raw = "";

    /**
     * 导入方式，用于源管理页分组：
     * text=粘贴 JSON、file=本地文件、url=订阅链接、eprendre=订阅适配、jar=插件。
     */
    public String origin = "";

    // ---------------------------------------------------------------- 子结构

    /** 搜索配置 */
    public static final class Search {
        /** 请求方式：get / post，默认 get */
        public String method = "get";
        /**
         * URL 模板。占位符：
         * {kw} 关键词(已 URL 编码)  {kwRaw} 关键词原文  {page} 页码
         * 相对路径会拼到 base 上；含 %s 的老写法也支持（按 kw,page 顺序填）。
         */
        public String url = "";
        /** post 时的表单体模板，占位符同上（也可直接写 query 在 url 里） */
        public String body = "";
        /** 响应类型：html / json，默认 html */
        public String type = "html";
        /**
         * 取结果列表：
         * - type=html 时是「匹配整个条目的正则」，要求只有一个捕获组包住条目 HTML
         * - type=json 时是结果数组的点路径，如 data.list
         */
        public String items = "";
        /** 结果项的字段映射，值支持「正则(取 group1)」或「json 点路径」，由 type 决定 */
        public String title = "";
        public String urlField = "";
        public String author = "";
        public String cover = "";
        public String intro = "";
        /** 翻页上限，默认 30 */
        public int maxPages = 30;
        /** 页码是否有下一页的判据正则（匹配到则 totalPages 判定继续） */
        public String hasNext = "";
    }

    /** 详情配置 */
    public static final class Detail {
        /** 章节列表所属页：空表示书本身哪页就是详情页；{url} 是书的 URL */
        public String page = "";
        public String method = "get";
        /** 章节条目正则（html）取 group(1)；或 json 数组点路径（json） */
        public String items = "";
        public String type = "html";
        /** 单条章节的字段 */
        public String title = "";
        public String urlField = "";
        /** 书名/作者/简介（详情页覆盖搜索结果，可空） */
        public String bookTitle = "";
        public String author = "";
        public String intro = "";
        public String cover = "";
        /** 是否按倒序排列（有些站最新在最前，需要翻转） */
        public boolean reverse = false;
        /**
         * 章节分页页数。0（缺省）= 只读第 1 页，保持旧行为不发多余请求。
         * 配 &gt;1 时按 {page} 模板并发拉这几页并合并（v1.9 新增）。
         */
        public int pages = 0;
    }

    /** 分类浏览配置 */
    public static final class Browse {
        /**
         * 分类清单，每项 {"k":"路径段或完整URL","n":"显示名"}。
         * k 若以 http 开头会被当作完整 URL，否则按 url 模板拼。
         */
        public JSONArray cats;
        /** 列表页 URL 模板，占位符 {cat} 分类 k、{page} 页码；留空则用 base+{cat} */
        public String url = "";
        /** 响应类型：html / json，默认 html */
        public String type = "html";
        /** 条目正则（html）或数组点路径（json） */
        public String items = "";
        /** 字段映射，语义同 Search 段 */
        public String title = "";
        public String urlField = "";
        public String author = "";
        public String cover = "";
        public String intro = "";
        /** 是否按倒序排列 */
        public boolean reverse = false;
    }

    /** 取链配置 */
    public static final class Audio {
        /**
         * 模式：
         * - direct  章节 urlField 本身就是音频直链
         * - page    需要再请求一次的中间页，从 HTML 里正则抽直链
         * - json    需要再请求一次的 API，从 JSON 点路径抽直链
         */
        public String mode = "page";
        /** page/json 模式下要请求的 URL 模板，{url} 为章节链接、{id} 为章节 id */
        public String url = "";
        public String method = "get";
        /** page 模式的直链正则 / json 模式的点路径 */
        public String field = "";
        /** 直链正则里直链是第几个捕获组，默认 1 */
        public int group = 1;
        /** 抽到的直链若形如 &amp;xxx; 需不需要 unescape，默认 true */
        public boolean unescape = true;
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 从单个 JSON 对象解析。缺关键字段返回 null。
     */
    public static SourceConfig from(JSONObject o) {
        if (o == null) return null;
        SourceConfig c = new SourceConfig();
        c.id = Json.s(o, "id").trim();
        c.name = Json.s(o, "name").trim();
        c.desc = Json.s(o, "desc").trim();
        c.base = Json.s(o, "base").trim();
        if (c.base.length() == 0) c.base = Json.s(o, "host").trim();
        if (c.desc.length() == 0) c.desc = "自定义源";
        c.headers = o.optJSONObject("headers");
        c.raw = o.toString();

        JSONObject s = o.optJSONObject("search");
        if (s != null) c.search = parseSearch(s);

        JSONObject d = o.optJSONObject("detail");
        if (d != null) c.detail = parseDetail(d);

        // audio 允许放在顶层，兼容更简短的写法
        JSONObject a = o.optJSONObject("audio");
        if (a == null) a = o.optJSONObject("play");
        if (a != null) c.audio = parseAudio(a);

        // browse / categories：分类浏览（发现页）
        JSONObject br = o.optJSONObject("browse");
        if (br == null) br = o.optJSONObject("categories");
        if (br != null) c.browse = parseBrowse(br, o);

        return c;
    }

    /** 解析浏览段。cats 允许写在 browse 里，也允许写在顶层 categories（数组形态） */
    private static Browse parseBrowse(JSONObject b, JSONObject root) {
        Browse x = new Browse();
        x.url = Json.s(b, "url");
        x.type = orDefault(Json.s(b, "type"), "html");
        x.items = Json.s(b, "items");
        x.title = Json.s(b, "title");
        x.urlField = Json.s(b, "urlField");
        if (x.urlField.length() == 0) x.urlField = Json.s(b, "url_field");
        x.author = Json.s(b, "author");
        x.cover = Json.s(b, "cover");
        x.intro = Json.s(b, "intro");
        x.reverse = b.optBoolean("reverse", false);

        JSONArray cats = b.optJSONArray("cats");
        if (cats == null) cats = b.optJSONArray("list");
        // 顶层 categories 是纯数组的写法
        if (cats == null) cats = root.optJSONArray("categories");
        x.cats = cats;
        return x;
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.length() == 0) ? def : v;
    }

    private static Search parseSearch(JSONObject s) {
        Search r = new Search();
        r.method = normMethod(Json.s(s, "method"));
        r.url = Json.pick(s, "url", "api", "path");
        r.body = Json.s(s, "body");
        r.type = normType(Json.s(s, "type"));
        r.items = Json.pick(s, "items", "list", "regex", "selector");
        r.title = Json.pick(s, "title", "name");
        r.urlField = Json.pick(s, "bookUrl", "itemUrl", "link", "href");
        r.author = Json.pick(s, "author", "artist");
        r.cover = Json.pick(s, "cover", "pic", "image");
        r.intro = Json.pick(s, "intro", "desc", "description");
        r.maxPages = Json.i(s, "maxPages", 30);
        if (r.maxPages <= 0 || r.maxPages > 200) r.maxPages = 30;
        r.hasNext = Json.s(s, "hasNext");
        return r;
    }

    private static Detail parseDetail(JSONObject d) {
        Detail r = new Detail();
        r.page = Json.pick(d, "page", "url");
        r.method = normMethod(Json.s(d, "method"));
        r.type = normType(Json.s(d, "type"));
        r.items = Json.pick(d, "items", "chapters", "list", "regex");
        r.title = Json.pick(d, "title", "name");
        r.urlField = Json.pick(d, "chapterUrl", "itemUrl", "link", "href");
        r.bookTitle = Json.pick(d, "bookTitle", "bookName");
        r.author = Json.s(d, "author");
        r.intro = Json.pick(d, "intro", "bookIntro");
        r.cover = Json.pick(d, "cover", "bookCover");
        r.reverse = d.optBoolean("reverse", false);
        return r;
    }

    private static Audio parseAudio(JSONObject a) {
        Audio r = new Audio();
        String m = Json.s(a, "mode").trim().toLowerCase();
        // 没写 mode 时按有没有 url 自动判定
        if (m.length() == 0) {
            m = Json.s(a, "url").length() > 0 ? "page" : "direct";
        }
        r.mode = normMode(m);
        r.url = Json.pick(a, "url", "api", "path");
        r.method = normMethod(Json.s(a, "method"));
        r.field = Json.pick(a, "field", "regex", "selector", "path");
        r.group = Json.i(a, "group", 1);
        if (r.group < 0) r.group = 1;
        r.unescape = a.optBoolean("unescape", true);
        // direct 模式下 field 允许为空；其他模式必须有 field
        return r;
    }

    private static String normMethod(String m) {
        if (m == null) return "get";
        m = m.trim().toLowerCase();
        return "post".equals(m) ? "post" : "get";
    }

    private static String normType(String t) {
        if (t == null) return "html";
        t = t.trim().toLowerCase();
        if ("json".equals(t) || "api".equals(t)) return "json";
        return "html";
    }

    private static String normMode(String m) {
        if (m == null) return "page";
        m = m.trim().toLowerCase();
        if ("direct".equals(m) || "directurl".equals(m) || "src".equals(m)) return "direct";
        if ("json".equals(m) || "api".equals(m)) return "json";
        return "page";
    }

    // ---------------------------------------------------------------- 校验

    /**
     * 校验配置完整性，返回人类可读的错误信息；通过则返回 null。
     * 这是给「导入」用的，错误要能直接展示给用户。
     */
    public String validate() {
        if (Utils.empty(id)) return "缺少 id 字段";
        if (!id.matches(ID_OK)) return "id 只能含字母、数字、下划线、短横线，且不超过 48 字符：" + id;
        if (Utils.empty(name)) return "缺少 name 字段";
        if (Utils.empty(base)) return "缺少 base 字段（站点根地址）";
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return "base 必须以 http:// 或 https:// 开头：" + base;
        }
        if (detail == null) return "缺少 detail 段（章节列表配置）";
        if (Utils.empty(detail.items)) return "detail.items 不能为空（章节条目正则/路径）";
        if (audio == null) return "缺少 audio 段（取链配置）";
        if (!"direct".equals(audio.mode)) {
            if (Utils.empty(audio.field)) return "audio.field 不能为空（直链正则/路径）";
            if (Utils.empty(audio.url)) {
                // 有 field 无 url 时，允许回落到章节页本身
                audio.url = "{url}";
            }
        }
        if (search != null) {
            if (Utils.empty(search.url)) return "search.url 不能为空";
            if (Utils.empty(search.items)) return "search.items 不能为空（结果条目正则/路径）";
            if (Utils.empty(search.title)) return "search.title 不能为空（书名取值）";
            if (Utils.empty(search.urlField)) return "search.urlField 不能为空（书链接取值）";
        }
        return null;
    }

    /**
     * 是否为「平台保留 id」。
     *
     * <p>★ v2.4：本 App 已改为纯空壳工具、不再内置任何源，但下面这几个 id
     * 仍是历史内置源的标识 —— 保留拦截是为了防止用户导入的源与将来
     * 可能恢复的官方推荐源撞 id（撞了会导致源管理页分组错乱、
     * 已停用状态串味）。除此之外任意 id 都可自由导入。
     */
    public boolean conflictsWithBuiltin() {
        return "bokan".equals(id) || "yuntu".equals(id) || "ting15".equals(id)
                || "pingshu5".equals(id) || "vbus".equals(id);
    }

    // ---------------------------------------------------------------- 集合解析

    /**
     * 解析一个源数组或 {sources:[...]} 包装体，返回其中的配置列表（跳过无效项）。
     *
     * <p>★ v2.7：导入侧是**单向兼容**的 —— 刻意做得尽量宽容，能吃下三种来源：
     * <ol>
     *   <li><b>枫兮听书自家 v2 包</b>：{@code {version:2, app:"fengxi-tingshu", sources:[]}}，
     *       每条源带 {@code _fx:1} 指纹。指纹字段解析时被忽略（{@code from()} 只读
     *       已知字段），导出时会重新打上。</li>
     *   <li><b>旧版 v1 包</b>：{@code {version:1, sources:[], disabled:[], alias:{}}}，
     *       {@code disabled}/{@code alias} 段被读取后忽略（本 App 已无内置源）。</li>
     *   <li><b>第三方 / 阅读系源包</b>：裸数组、{@code {sources:[]}}、{@code {list:[]}}、
     *       乃至单个源对象直接放在根上，都能解析。</li>
     * </ol>
     *
     * <p><b>但反向不存在</b>：本 App 导出的 v2 包带着 {@code app} 标识、
     * {@code version:2} 和 {@code _fx} 指纹，且大量使用自家扩展字段
     * （{@code browse} 段、{@code {cat}}/{@code {src}} 占位符、相对路径 + base 补全）。
     * 第三方 App 按自己的规则去解析，会认不出这些字段而失败 —— 这是设计意图，
     * 我们不生产「能被别家直接吃掉」的源包。
     *
     * <p>另外这里对 JSON 做了「宽松清洗」：第三方源包里常有注释、尾随逗号、
     * 未转义的控制字符，{@link Json#clean} 会先修一遍再解析。
     */
    public static List<SourceConfig> parseList(String json) {
        List<SourceConfig> out = new ArrayList<SourceConfig>();
        if (Utils.empty(json)) return out;
        JSONObject root = Json.obj(json);
        if (root == null) {
            // 也许本身就是个数组
            try {
                JSONArray a = new JSONArray(Json.clean(json).trim());
                for (int i = 0; i < a.length(); i++) {
                    SourceConfig c = from(a.optJSONObject(i));
                    if (c != null) out.add(c);
                }
            } catch (Throwable ignored) {}
            return out;
        }
        // 包装体：sources / list / data 三种都认（第三方源包写法不一）
        JSONArray a = root.optJSONArray("sources");
        if (a == null) a = root.optJSONArray("list");
        if (a == null) a = root.optJSONArray("data");
        if (a == null) {
            // 单个源直接放在根上
            SourceConfig c = from(root);
            if (c != null && !Utils.empty(c.id)) out.add(c);
            return out;
        }
        for (int i = 0; i < a.length(); i++) {
            SourceConfig c = from(a.optJSONObject(i));
            if (c != null && !Utils.empty(c.id)) out.add(c);
        }
        return out;
    }
}
