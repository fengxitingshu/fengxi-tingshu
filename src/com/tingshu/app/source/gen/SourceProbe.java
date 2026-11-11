package com.tingshu.app.source.gen;

import com.tingshu.app.source.AbsSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ v4.21.39 「输入网址 → 自动生成书源」。
 *
 * <p>输入一个听书网站首页地址，自动探测它的：
 * <ol>
 *   <li><b>站点类型</b>（HTML / JSON API / 需 JS 渲染）</li>
 *   <li><b>搜索接口</b>（表单 → 常见路径模板 → JSON API）</li>
 *   <li><b>章节列表</b>（详情页链接聚类，按 href 目录前缀分组打分）</li>
 *   <li><b>音频直链</b>（裸音频 URL → audio 标签 → JS 变量）</li>
 * </ol>
 * 最终产出 {@link com.tingshu.app.data.SourceConfig} 兼容的 JSON，
 * 经 {@code BridgeSource.importText(json, true, "gen")} 导入即可像普通源一样使用。
 *
 * <p>约束：总预算 45s、请求 ≤15 个；每一步的结果/失败/降级都写入 diag（人话），
 * 最终 ok = 搜索✔ 且 章节✔（音频允许降级）。
 */
public final class SourceProbe {

    /** 进度回调：stage=阶段名，pct=0~100，msg=一句话 */
    public interface Cb { void onProgress(String stage, int pct, String msg); }

    public static final class Result {
        public boolean ok = false;
        public String id = "";
        public String name = "";
        public String configJson = "";
        public String error = "";
        public List<String> diag = new ArrayList<String>();
    }

    private static final long BUDGET_MS = 45_000L;
    private static final int MAX_REQ = 25;
    /** 探测搜索用的关键词：超高频单字，几乎任何听书站都有结果；依次轮换 */
    private static final String[] PROBE_KWS = {"斗", "天", "书"};

    /** 导航停用词：条目标题完全命中即视作导航而非内容 */
    private static final String[] NAV_WORDS = {
            "首页", "主页", "网站", "帮助", "登录", "注册", "登陆", "关于", "联系", "收藏",
            "搜索", "排行", "排行榜", "排名", "分类", "书库", "全本", "完本", "推荐", "最新",
            "公告", "返回", "更多", "申请", "友链", "友情链接", "移动", "客户端", "下载",
            "版权", "免责", "声明", "反馈", "留言", "居中", "设置", "书签", "历史"
    };

    /** 音频直链通用正则（生成到配置里，也用于探测） */
    private static final String AUDIO_DIRECT_RE =
            "(https?://[^\"'\\s<>]+\\.(?:mp3|m4a|aac|ogg|wav))";
    private static final Pattern AUDIO_DIRECT_P =
            Pattern.compile(AUDIO_DIRECT_RE, Pattern.CASE_INSENSITIVE);

    private static final Pattern P_FORM = Pattern.compile(
            "<form[^>]*>(?:(?!</form>).)*</form>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern P_ACTION = Pattern.compile(
            "(?i)action\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern P_METHOD = Pattern.compile(
            "(?i)method\\s*=\\s*[\"']([a-zA-Z]+)[\"']");
    private static final Pattern P_INPUT = Pattern.compile(
            "<input[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_INPUT_NAME = Pattern.compile(
            "(?i)name\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern P_INPUT_TYPE = Pattern.compile(
            "(?i)type\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern P_A = Pattern.compile(
            "<a\\s[^>]*href=\\s*[\"']([^\"'\\s]+)[\"'][^>]*>((?:(?!</a>).)*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern P_TITLE = Pattern.compile(
            "<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final Cb cb;
    private final long deadline;
    private final List<String> diag = new ArrayList<String>();
    private int reqCount = 0;
    /** 当前站点 base（探测期间使用，避免层层传参） */
    private String mBase = "";

    private SourceProbe(Cb cb) {
        this.cb = cb;
        this.deadline = System.currentTimeMillis() + BUDGET_MS;
    }

    // ================================================================ 入口

    public static Result probe(String rawUrl, Cb cb) {
        Result r = new Result();
        if (rawUrl == null || rawUrl.trim().length() == 0) {
            r.error = "请输入网址";
            return r;
        }
        SourceProbe p = new SourceProbe(cb);
        try {
            p.run(rawUrl.trim(), r);
        } catch (Throwable t) {
            r.error = "分析过程出错：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage());
        }
        r.diag = p.diag;
        r.ok = r.configJson.length() > 0;
        return r;
    }

    // ================================================================ 主流程

    private void run(String rawUrl, Result r) {
        String home = normalize(rawUrl);
        if (home == null) {
            r.error = "网址格式不正确（示例：https://www.example.com）";
            return;
        }
        URI uri = uriOf(home);
        if (uri == null || uri.getHost() == null) {
            r.error = "无法解析域名：" + rawUrl;
            return;
        }
        String host = uri.getHost();
        String base = uri.getScheme() + "://" + host
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        mBase = base;

        // ---------- 1. 站点判别 ----------
        prog("站点", 8, "正在访问 " + host + " …");
        String html = httpGet(home, null);
        if (html.length() == 0) {
            r.error = "无法访问该网站（连接失败或超时），请确认网址可达后重试";
            diag.add("✘ 首页请求失败（连接失败/超时）");
            return;
        }
        boolean usedDesktopUa = false;
        if (looksLikeShell(html)) {
            prog("站点", 14, "页面疑似 JS 动态渲染，尝试无头浏览器加载 …");
            String rendered = renderViaWebView(home, 15000);
            if (rendered != null && rendered.length() > 500 && hasLinks(rendered)) {
                html = rendered;
                diag.add("✔ 站点需 JS 渲染，已用无头浏览器取到首页");
            } else {
                diag.add("⚠ 首页疑似 JS 动态渲染且无头加载失败，探测可能受限");
            }
        }
        if (isLoginPage(home, html)) {
            r.error = "该网址需要登录才能访问，自动生成暂不支持登录站点";
            diag.add("✘ 检测到登录页，终止");
            return;
        }
        String siteTitle = cleanSiteTitle(text(grab(html, P_TITLE.pattern())));
        if (siteTitle.length() == 0) siteTitle = host;
        diag.add("✔ 站点类型：HTML 站（" + siteTitle + "）");

        // ---------- 2. 搜索探测 ----------
        prog("搜索", 22, "探测搜索接口 …");
        SearchWin sw = probeSearch(home, base, host, html);
        if (sw == null) {
            r.error = "没有找到可用的搜索接口：尝试了站内搜索表单和常见搜索路径都没有得到结果列表。"
                    + "该站可能靠 JS 动态搜索或接口有签名，无法自动生成。";
            diag.add("✘ 搜索接口探测失败（表单 + 6 个常见模板均无有效结果）");
            return;
        }
        diag.add(sw.diagMsg);
        prog("搜索", 50, "搜索接口已找到，进入书籍详情页 …");

        // ---------- 3. 详情/章节探测 ----------
        DetailWin dw = probeDetail(sw.firstBookUrl, base, host);
        if (dw == null) {
            r.error = "搜索正常，但没能识别出章节列表：详情页里没有发现足够多的同类链接（可能章节靠 JS 加载）。";
            diag.add("✘ 章节列表探测失败（" + sw.firstBookUrl + "）");
            return;
        }
        diag.add(dw.diagMsg);
        prog("章节", 72, "章节列表已找到（" + dw.count + " 集），探测音频直链 …");

        // ---------- 4. 音频直链探测 ----------
        AudioWin au = probeAudio(dw.firstChUrl, sw.firstBookUrl, base, dw.firstChIsDirect);
        diag.add(au.diagMsg);
        prog("生成", 88, "生成源配置 …");

        // ---------- 5. 组装 SourceConfig ----------
        String cfg = buildConfig(host, base, siteTitle, usedDesktopUa, sw, dw, au);
        r.configJson = cfg;
        r.id = grab(cfg, "\"id\":\"([^\"]+)\"");
        r.name = siteTitle;
        prog("完成", 100, "分析完成");
    }

    // ================================================================ 搜索探测

    private static final class Entry {
        String href;      // 原始 href（可能是相对路径）
        String text;      // 链接文字
        Entry(String h, String t) { href = h; text = t; }
    }

    private static final class SearchWin {
        String method = "get";
        String url;        // 查询地址（含 {kw} 占位）
        String body = "";  // POST 表单体
        String type = "html";
        String items, title, urlField;
        String firstBookUrl = "";
        int count;
        String diagMsg;
    }

    private SearchWin probeSearch(String home, String base, String host, String homeHtml) {
        List<String[]> tries = new ArrayList<String[]>(); // {method, url, body}
        // a) 站内搜索表单
        Matcher fm = P_FORM.matcher(homeHtml);
        while (fm.find()) {
            String form = fm.group(0);
            String action = grab(form, P_ACTION.pattern());
            String method = grab(form, P_METHOD.pattern()).toLowerCase();
            if (method.length() == 0) method = "get";
            String kwName = null, extra = "";
            Matcher im = P_INPUT.matcher(form);
            while (im.find()) {
                String inp = im.group(0);
                String name = grab(inp, P_INPUT_NAME.pattern());
                String type = grab(inp, P_INPUT_TYPE.pattern()).toLowerCase();
                if (name.length() == 0 || "submit".equals(type) || "button".equals(type)
                        || "image".equals(type) || "hidden".equals(type)) continue;
                if (name.matches("(?i)(kw|key|keyword|word|search|searchkey|s|q|bookname).*")) {
                    if (kwName == null) kwName = name;
                } else {
                    extra += "&" + urlencode(name) + "=";
                }
            }
            if (kwName == null) continue;
            String target = abs(base + "/", action.length() == 0 ? home : action);
            // 配置里保留 {kw} 占位（JsonSource tpl 会替换为 URL 编码后的关键词），
            // 探测发送时才替换成实际关键词
            String body;
            if ("post".equals(method)) {
                body = urlencode(kwName) + "={kw}" + extra;
            } else {
                target += (target.contains("?") ? "&" : "?")
                        + urlencode(kwName) + "={kw}" + extra;
                body = "";
            }
            tries.add(new String[]{method, target, body});
            if (tries.size() >= 2) break;
        }
        // b) 常见路径模板（url/body 含 {kw} 占位，发送前替换）
        String[][] TPLS = {
                {"get", base + "/search/{kw}", ""},
                {"get", base + "/search?q={kw}", ""},
                {"get", base + "/search.php?keyword={kw}", ""},
                {"get", base + "/search.php?q={kw}", ""},
                {"get", base + "/s/{kw}", ""},
                {"get", base + "/so.php?q={kw}", ""},
                {"get", base + "/search.html?searchtype=all&keyword={kw}", ""},
                {"post", base + "/search.php", "keyword={kw}"},
                {"get", base + "/api/search?keyword={kw}", ""},
        };
        for (String[] t : TPLS) tries.add(t);

        // 依次用多个高频关键词轮换（有的站单个冷僻词命中太少）
        for (String kw : PROBE_KWS) {
            for (String[] t : tries) {
                if (overBudget() || reqCount >= MAX_REQ) return null;
                String method = t[0], url = t[1], body = t[2];
                String urlReal = url.replace("{kw}", urlenc(kw));
                String resp;
                if ("post".equals(method)) resp = httpPost(urlReal, body.replace("{kw}", kw), home);
                else resp = httpGet(urlReal, home);
                if (resp.length() < 200) continue;
                if (isLoginPage(urlReal, resp)) continue;

                // JSON 型？
                if (looksLikeJson(resp)) {
                    SearchWin w = tryJsonSearch(url, method, kw, resp);
                    if (w != null) return w;
                    continue;
                }
                // HTML 型：抽条目
                List<Entry> es = extractEntries(resp, host, base);
                if (es.size() < 1) continue;
                // 内容条目 = 路径深度 ≥2（/a/b/ 形态），排除栏目级导航
                List<Entry> content = contentEntries(es, base);
                if (content.size() < 1) continue;
                String prefix = commonPrefix(content);
                if (prefix == null || prefix.length() == 0) continue;
                // 再验证一次前缀过滤后仍有条目（防巧合）
                List<Entry> hit = filterPrefix(es, prefix);
                if (hit.size() < 1) continue;
                // 书页必须取内容条目（深度 ≥2），否则可能选中 /pbook/ 这类栏目页
                List<Entry> hitContent = filterPrefix(content, prefix);
                Entry firstBook = hitContent.size() > 0 ? hitContent.get(0)
                        : content.get(0);

                SearchWin w = new SearchWin();
                w.method = method;
                w.url = relUrl(mBase, url);
                w.body = body;
                w.firstBookUrl = abs(mBase + "/", firstBook.href);
                w.count = hit.size();
                String pre = Pattern.quote(prefix);
                w.items = "<a\\s[^>]*href=\\s*[\"']" + pre + "(?:[^\"']+)[\"'][^>]*>(?:(?!</a>).)*?</a>";
                w.title = "<a\\s[^>]*href=\\s*[\"']" + pre + "(?:[^\"']+)[\"'][^>]*>\\s*(.*?)\\s*</a>";
                w.urlField = "<a\\s[^>]*href=\\s*[\"'](" + pre + "(?:[^\"']+))[\"'][^>]*>";
                w.diagMsg = "✔ 搜索接口：" + method.toUpperCase() + " " + relUrl(mBase, url)
                        + "（命中 " + hit.size() + " 条，条目目录 " + prefix + "）";
                return w;
            }
        }
        return null;
    }

    /** JSON 搜索响应：深度找含 ≥2 个 {title,url} 对象的数组 */
    private SearchWin tryJsonSearch(String url, String method, String kw, String resp) {
        try {
            JSONObject root = new JSONObject(resp);
            Found found = new Found();
            findArray(root, "", found, 0);
            if (found.path == null || found.arr.length() < 2) return null;
            JSONObject o0 = found.arr.optJSONObject(0);
            if (o0 == null) return null;
            String tf = pickKey(o0, "name", "title", "bookname", "book_name", "bookName");
            String uf = pickKey(o0, "bookUrl", "book_url", "url", "link", "href",
                    "book_id", "bookId", "id");
            if (tf == null || uf == null) return null;

            SearchWin w = new SearchWin();
            w.method = method;
            w.url = relUrl(mBase, url);
            w.body = method.equalsIgnoreCase("post") ? "keyword={kw}" : "";
            w.type = "json";
            w.items = found.path;
            w.title = tf;
            w.urlField = uf;
            w.count = found.arr.length();
            // 书链接：值是完整 URL 还是纯 id？
            String v = String.valueOf(o0.opt(uf));
            if (v.startsWith("http")) {
                w.firstBookUrl = v;
            } else {
                // 纯 id：详情页地址未知，尝试常见详情模板
                w.firstBookUrl = guessDetailById(mBase, v);
                if (w.firstBookUrl == null) return null;
            }
            w.diagMsg = "✔ 搜索接口：JSON " + w.url + "（数组路径 " + found.path + "）";
            return w;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class Found {
        String path;
        JSONArray arr;
    }

    private void findArray(Object node, String path, Found out, int depth) {
        if (depth > 6 || out.path != null) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            if (a.length() >= 2 && a.optJSONObject(0) != null) {
                JSONObject o = a.optJSONObject(0);
                String tf = pickKey(o, "name", "title", "bookname", "bookName");
                String uf = pickKey(o, "url", "link", "href", "bookUrl", "id", "book_id");
                if (tf != null && uf != null) { out.path = path; out.arr = a; }
            }
            for (int i = 0; i < a.length() && out.path == null; i++) {
                findArray(a.opt(i), path + "." + i, out, depth + 1);
            }
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext() && out.path == null) {
                String k = it.next();
                Object v = o.opt(k);
                if (v instanceof JSONArray || v instanceof JSONObject) {
                    findArray(v, path.length() == 0 ? k : path + "." + k, out, depth + 1);
                }
            }
        }
    }

    private static String pickKey(JSONObject o, String... keys) {
        for (String k : keys) if (o.has(k) && !o.isNull(k)) return k;
        return null;
    }

    private String guessDetailById(String base, String id) {
        String[] TPLS = {
                base + "/book/{id}.html", base + "/book/{id}/", base + "/ting/{id}/",
                base + "/audio/{id}.html", base + "/detail/{id}", base + "/bookinfo/{id}.html"
        };
        for (String t : TPLS) {
            if (overBudget() || reqCount >= MAX_REQ) return null;
            String u = t.replace("{id}", id);
            String resp = httpGet(u, base);
            if (resp.length() > 500 && hasLinks(resp)) return u;
        }
        return null;
    }

    // ================================================================ 详情/章节探测

    private static final class DetailWin {
        String items, title, urlField;
        boolean reverse = false;
        String firstChUrl = "";
        boolean firstChIsDirect = false;
        int count;
        String diagMsg;
    }

    private DetailWin probeDetail(String bookUrl, String base, String host) {
        if (bookUrl == null || overBudget()) return null;
        String html = httpGet(bookUrl, base);
        if (html.length() < 300) return null;

        // 全页抽链接 → 只留内容链接（/a/b 形态）→ 按目录前缀聚类 → 打分
        List<Entry> links = rawLinks(html, host, base);
        links = contentEntries(links, base);
        // 排除书页自身的自链接（title 位置的 <a href=本书页>），否则会被当成第一章
        String bookAbs = abs(base + "/", bookUrl);
        for (int i = links.size() - 1; i >= 0; i--) {
            if (abs(base + "/", links.get(i).href).equals(bookAbs)) links.remove(i);
        }
        Map<String, List<Entry>> groups = new LinkedHashMap<String, List<Entry>>();
        for (Entry e : links) {
            String dir = dirOf(e.href, base);
            if (dir == null) continue;
            List<Entry> g = groups.get(dir);
            if (g == null) { g = new ArrayList<Entry>(); groups.put(dir, g); }
            g.add(e);
        }
        String bookPath = dirOf(bookUrl, base);
        String best = null;
        int bestScore = -1;
        List<Entry> bestList = null;
        for (Map.Entry<String, List<Entry>> g : groups.entrySet()) {
            List<Entry> list = g.getValue();
            if (list.size() < 5) continue;
            if (g.getKey().equals(bookPath)) continue; // 同目录=相关推荐居多
            int epWords = 0;
            for (Entry e : list) if (EP_WORD.matcher(e.text).find()) epWords++;
            double frac = (double) epWords / list.size();
            int score = list.size() + (int) (frac * 20)
                    + (bookUrl.contains(g.getKey()) ? 4 : 0);
            if (score > bestScore) { bestScore = score; best = g.getKey(); bestList = list; }
        }
        if (best == null || bestList == null || bestList.size() < 5) return null;

        DetailWin d = new DetailWin();
        d.count = bestList.size();
        d.firstChUrl = abs(base + "/", bestList.get(0).href);
        d.firstChIsDirect = AUDIO_EXT.matcher(bestList.get(0).href).find()
                || AUDIO_EXT.matcher(d.firstChUrl).find();

        // 排序检测：文字带数字时，首条序号 > 末条序号 → 倒序站点
        try {
            Integer a = firstNum(bestList.get(0).text);
            Integer b = firstNum(bestList.get(bestList.size() - 1).text);
            if (a != null && b != null && a > b) d.reverse = true;
        } catch (Throwable ignored) {}

        // 正则用页面里实际的 href 形态（相对/绝对都可能），而不是聚类 key；
        // 并用负向前瞻排除书页自链接（详情页 title 位置的 <a href=本书页>）
        String pre = Pattern.quote(commonPrefix(bestList));
        String relBook = relUrl(base, bookAbs);
        String excl = relBook.length() > 0 ? "(?!" + Pattern.quote(relBook) + ")" : "";
        d.items = "<a\\s[^>]*href=\\s*[\"']" + excl + pre + "(?:[^\"']+)[\"'][^>]*>(?:(?!</a>).)*?</a>";
        d.title = "<a\\s[^>]*href=\\s*[\"']" + excl + pre + "(?:[^\"']+)[\"'][^>]*>\\s*(.*?)\\s*</a>";
        d.urlField = "<a\\s[^>]*href=\\s*[\"']" + excl + "(" + pre + "(?:[^\"']+))[\"'][^>]*>";
        d.diagMsg = "✔ 章节列表：" + bestList.size() + " 集（条目目录 " + pre.replaceAll("[\\\\QE]", "")
                + "）" + (d.reverse ? "，检测到倒序排列已自动反转" : "");
        return d;
    }

    private static final Pattern EP_WORD = Pattern.compile("[章集话回节]|第\\s*\\d+");
    private static final Pattern AUDIO_EXT = Pattern.compile(
            "\\.(mp3|m4a|aac|ogg|wav)(\\?|$)", Pattern.CASE_INSENSITIVE);

    /** 全页抽 <a>，过滤外链/导航词/空文字 */
    private List<Entry> rawLinks(String html, String host, String base) {
        List<Entry> out = new ArrayList<Entry>();
        Matcher m = P_A.matcher(html);
        while (m.find()) {
            String href = m.group(1).trim();
            String txt = text(m.group(2));
            if (href.length() == 0 || href.startsWith("#")
                    || href.startsWith("javascript:") || href.startsWith("mailto:")
                    || href.startsWith("tel:")) continue;
            String absu = abs(base + "/", href);
            String h = hostOf(absu);
            if (h == null || !h.equalsIgnoreCase(host)) continue; // 外链
            if (txt.length() == 0 || txt.length() > 80) continue;
            if (isNavWord(txt)) continue;
            Entry e = new Entry(href, txt);
            out.add(e);
        }
        return out;
    }

    // ================================================================ 音频探测

    private static final class AudioWin {
        String mode = "page";
        String url = "{url}";
        String field = AUDIO_DIRECT_RE;
        int group = 1;
        String diagMsg;
    }

    private AudioWin probeAudio(String chUrl, String bookUrl, String base,
                                boolean firstChIsDirect) {
        AudioWin a = new AudioWin();
        if (firstChIsDirect) {
            a.mode = "direct";
            a.diagMsg = "✔ 音频：章节链接本身即直链（direct 模式）";
            return a;
        }
        if (chUrl == null || overBudget()) {
            a.diagMsg = "⚠ 未探测到音频直链，已生成宽泛规则（播放页找 .mp3），失败时可配合嗅探";
            return a;
        }
        String html = httpGet(chUrl, bookUrl == null ? base : bookUrl);
        if (html.length() > 100) {
            // a) 裸音频直链
            Matcher m = AUDIO_DIRECT_P.matcher(html);
            if (m.find()) {
                String u = abs(base + "/", m.group(1));
                if (u.startsWith("http")) {
                    a.mode = "page";
                    a.url = "{url}";
                    a.field = AUDIO_DIRECT_RE;
                    a.group = 1;
                    a.diagMsg = "✔ 音频直链：播放页含 .mp3 等直链（page 模式正则抓取）";
                    return a;
                }
            }
            // b) audio/source 标签
            String src = grab(html, "(?i)<(?:audio|source)[^>]*src=\\s*[\"']([^\"']+)[\"']");
            if (src.length() > 0) {
                String u = abs(base + "/", unes(src));
                if (u.startsWith("http") && !looksLikePage(u)) {
                    a.mode = "page";
                    a.url = "{url}";
                    a.field = "(?i)<(?:audio|source)[^>]*src=\\s*[\"']([^\"']+)[\"']";
                    a.group = 1;
                    a.diagMsg = "✔ 音频直链：audio 标签 src（page 模式）";
                    return a;
                }
            }
            // c) JS 变量
            String v = grab(html, "(?i)[\"'](?:play_?url|playUrl|mp3|audio_?url|audio|src)"
                    + "[\"']\\s*[:=]\\s*[\"']([^\"']{6,300})[\"']");
            if (v.length() > 0) {
                String u = abs(base + "/", unes(v));
                if (u.startsWith("http")
                        && (AUDIO_EXT.matcher(u).find() || reachableAudio(u, chUrl))) {
                    a.mode = "page";
                    a.url = "{url}";
                    a.field = "(?i)[\"'](?:play_?url|playUrl|mp3|audio_?url|audio|src)"
                            + "[\"']\\s*[:=]\\s*[\"']([^\"']{6,300})[\"']";
                    a.group = 1;
                    a.diagMsg = "✔ 音频直链：播放页 JS 变量（page 模式）";
                    return a;
                }
            }
        }
        a.diagMsg = "⚠ 播放页没找到明确的音频直链（可能靠 JS 动态加载），"
                + "已生成宽泛规则（找 .mp3），播放失败时建议反馈站点样本";
        return a;
    }

    /** 值是不是像一个页面地址而非媒体文件 */
    private static boolean looksLikePage(String u) {
        String p = u.toLowerCase();
        return p.endsWith(".html") || p.endsWith(".htm") || p.endsWith(".php")
                || p.endsWith("/");
    }

    /** HEAD 验证音频可达（尽力而为） */
    private boolean reachableAudio(String u, String referer) {
        if (reqCount >= MAX_REQ) return false;
        reqCount++;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(u).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestProperty("User-Agent", com.tingshu.app.core.Net.UA);
            c.setRequestProperty("Referer", referer);
            String ct = c.getContentType();
            return c.getResponseCode() < 400
                    && (ct == null || ct.contains("audio") || ct.contains("octet")
                        || ct.contains("mpeg"));
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================ 组装配置

    private String buildConfig(String host, String base, String name,
                               boolean usedDesktopUa, SearchWin sw, DetailWin dw, AudioWin au) {
        JSONObject o = new JSONObject();
        try {
            String hostBody = host.startsWith("www.") ? host.substring(4) : host;
            hostBody = hostBody.replaceAll("[^A-Za-z0-9]", "");
            if (hostBody.length() > 24) hostBody = hostBody.substring(0, 24);
            String id = (hostBody.length() == 0 ? "site" : hostBody)
                    + "_" + com.tingshu.app.core.Utils.md5(host).substring(0, 6);
            o.put("id", id);
            o.put("name", name);
            o.put("desc", "网址自动生成（" + host + "）");
            o.put("base", base);

            if (usedDesktopUa) {
                JSONObject h = new JSONObject();
                h.put("User-Agent", com.tingshu.app.core.Net.UA_DESKTOP);
                o.put("headers", h);
            }

            JSONObject s = new JSONObject();
            s.put("method", sw.method);
            s.put("url", sw.url);
            if (sw.body.length() > 0) s.put("body", sw.body);
            s.put("type", sw.type);
            s.put("items", sw.items);
            s.put("title", sw.title);
            s.put("urlField", sw.urlField);
            o.put("search", s);

            JSONObject d = new JSONObject();
            d.put("type", "html");
            d.put("items", dw.items);
            d.put("title", dw.title);
            d.put("urlField", dw.urlField);
            if (dw.reverse) d.put("reverse", true);
            o.put("detail", d);

            JSONObject a = new JSONObject();
            a.put("mode", au.mode);
            if (!"direct".equals(au.mode)) {
                a.put("url", au.url);
                a.put("field", au.field);
                a.put("group", au.group);
            }
            a.put("unescape", true);
            o.put("audio", a);
        } catch (Throwable t) {
            return "";
        }
        return o.toString();
    }

    // ================================================================ 工具

    private void prog(String stage, int pct, String msg) {
        if (cb != null) {
            try { cb.onProgress(stage, pct, msg); } catch (Throwable ignored) {}
        }
    }

    private boolean overBudget() {
        return System.currentTimeMillis() > deadline;
    }

    private String httpGet(String url, String referer) {
        reqCount++;
        try { return com.tingshu.app.core.Net.get(url, referer); }
        catch (Throwable t) { return ""; }
    }

    private String httpPost(String url, String body, String referer) {
        reqCount++;
        try { return com.tingshu.app.core.Net.post(url, body, referer); }
        catch (Throwable t) { return ""; }
    }

    private String renderViaWebView(String url, int timeout) {
        try {
            Class<?> c = Class.forName("com.tingshu.app.core.js.WebViewRenderer");
            Object r = c.getMethod("renderUrl", String.class, int.class, boolean.class)
                    .invoke(null, url, timeout, false);
            return r instanceof String ? (String) r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String normalize(String url) {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        if (u.length() < 12 || u.indexOf('.', 8) < 0) return null;
        try {
            URI uri = new URI(u);
            if (uri.getHost() == null) return null;
            return u;
        } catch (Throwable t) {
            return null;
        }
    }

    private static URI uriOf(String url) {
        try { return new URI(url); } catch (Throwable t) { return null; }
    }

    private static String hostOf(String url) {
        URI u = uriOf(url);
        return u == null ? null : u.getHost();
    }

    private static String dirOf(String href, String base) {
        String absu = abs(base + "/", href);
        int q = absu.indexOf('?');
        String p = q > 0 ? absu.substring(0, q) : absu;
        int slash = p.lastIndexOf('/');
        if (slash <= p.indexOf("://") + 2) return "/"; // 根路径
        return p.substring(0, slash + 1);
    }

    private static String relUrl(String base, String url) {
        if (url.startsWith(base) && url.length() > base.length()) {
            return url.substring(base.length());
        }
        return url;
    }

    /** 内容条目：URL 路径段数 ≥2（如 /ykc/yhcfdgc80h/），排除 /pbook/ 这类栏目级导航 */
    private static List<Entry> contentEntries(List<Entry> es, String base) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : es) {
            String absu = abs(base + "/", e.href);
            // 剥掉 scheme://host，只按纯路径计深度（host 不算段）
            String path;
            int s3 = absu.indexOf("://");
            if (s3 > 0) {
                int slash = absu.indexOf('/', s3 + 3);
                path = slash >= 0 ? absu.substring(slash) : "/";
            } else {
                path = absu;
            }
            int q = path.indexOf('?');
            if (q > 0) path = path.substring(0, q);
            // 按非空路径段数计深度（/pbook/=1 级栏目，/pbook/xxx/=2 级内容）
            String[] segs = path.split("/");
            int depth = 0;
            for (String s : segs) if (s.length() > 0) depth++;
            if (depth >= 2) out.add(e);
        }
        return out;
    }

    private String commonPrefix(List<Entry> es) {
        // 取条目 href 的公共目录前缀：≥2 条取公共目录，单条直接取父目录
        if (es == null || es.size() == 0) return null;
        if (es.size() == 1) {
            String d = dirOf(es.get(0).href, mBase);
            if (d == null || "/".equals(d)) return null;
            return d;
        }
        List<String> dirs = new ArrayList<String>();
        for (Entry e : es) dirs.add(e.href);
        if (dirs.size() < 2) return null;
        String first = dirs.get(0);
        String cand = first.contains("?") ? first.substring(0, first.indexOf('?')) : first;
        for (int cut = cand.length(); cut > 1; cut--) {
            String pre = cand.substring(0, cut);
            if (!pre.endsWith("/") && cand.length() > cut
                    && cand.charAt(cut) != '/') continue; // 只按 / 边界切
            int n = 0;
            for (String h : dirs) if (h.startsWith(pre)) n++;
            if (n >= Math.max(2, dirs.size() / 2)) {
                if (!pre.endsWith("/")) pre = pre + "/";
                return pre.startsWith("/") ? pre : "/" + pre;
            }
        }
        return null;
    }

    private static List<Entry> filterPrefix(List<Entry> es, String prefix) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : es) if (e.href.startsWith(prefix)) out.add(e);
        return out;
    }

    /** 抽搜索结果条目（先 a 级再 li 级），过滤导航/外链 */
    private List<Entry> extractEntries(String html, String host, String base) {
        List<Entry> out = new ArrayList<Entry>();
        Matcher m = P_A.matcher(html);
        while (m.find()) {
            String href = m.group(1).trim();
            String txt = text(m.group(2));
            if (href.length() == 0 || href.startsWith("#")
                    || href.startsWith("javascript:") || href.startsWith("mailto:")) continue;
            String absu = abs(base + "/", href);
            String h = hostOf(absu);
            if (h == null || !h.equalsIgnoreCase(host)) continue;
            if (txt.length() < 2 || txt.length() > 60) continue;
            if (isNavWord(txt)) continue;
            if (absu.equals(base) || absu.equals(base + "/")) continue;
            out.add(new Entry(href, txt));
        }
        return out;
    }

    private static boolean isNavWord(String txt) {
        String t = txt.trim();
        for (String w : NAV_WORDS) if (t.equals(w)) return true;
        return false;
    }

    private static boolean hasLinks(String html) {
        return html != null && html.contains("<a") && html.contains("href");
    }

    private static boolean looksLikeJson(String s) {
        String t = s.trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    /** 空壳页检测：几乎无链接、体量小、或有典型 SPA 挂载点 */
    private static final Pattern P_A_TAG = Pattern.compile("<a[\\s>]");

    private static boolean looksLikeShell(String html) {
        if (html.length() < 2000) return true;
        int links = 0;
        Matcher m = P_A_TAG.matcher(html);
        while (m.find() && links < 10) links++;
        if (links < 3) return true;
        return html.contains("id=\"app\"") && links < 10;
    }

    private static boolean isLoginPage(String url, String html) {
        String low = html.toLowerCase();
        return (low.contains("type=\"password\"") || low.contains("name=\"password\""))
                && low.length() < 30000;
    }

    private static String cleanSiteTitle(String t) {
        t = t == null ? "" : t.trim();
        // 去掉常见后缀：xxx - 听书网 / xxx_手机版
        t = t.replaceAll("[-_|｜].*$", "").trim();
        if (t.length() > 24) t = t.substring(0, 24);
        return t;
    }

    private static Integer firstNum(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("\\d+").matcher(s);
        return m.find() ? Integer.parseInt(m.group()) : null;
    }

    private static String urlencode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Throwable t) { return s; }
    }

    private static String urlenc(String s) {
        return urlencode(s);
    }

    private static String unes(String s) {
        return com.tingshu.app.core.Utils.unescape(s);
    }

    // ---- 正则工具（同 AbsSource 语义：DOTALL、group(1)） ----

    private static String grab(String s, String regex) {
        if (s == null || regex == null) return "";
        try {
            Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(s);
            return m.find() ? m.group(1).trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String text(String s) {
        return com.tingshu.app.core.Utils.stripTags(
                com.tingshu.app.core.Utils.unescape(s == null ? "" : s)).trim();
    }

    private static String abs(String base, String url) {
        return com.tingshu.app.core.Utils.abs(base, url);
    }

    /** 供 Bridge 展示用的小工具 */
    public static String md5(String s) {
        return com.tingshu.app.core.Utils.md5(s);
    }
}
