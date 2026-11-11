# 「输入网址 → 自动生成书源」实现计划（听书 App v4.21.39）

## Context
用户希望：在源管理里直接输入一个听书网站网址，App 像分析一个源一样自动探测该网站（搜索接口 / 详情页 / 章节列表 / 音频直链），生成一个可用的自定义书源并导入，免去手工写 JSON 配置。

关键架构结论（已调研确认）：工程已有声明式自定义源引擎 —— `data/SourceConfig.java`（search/detail/audio/browse 嵌套配置）+ `source/JsonSource.java`（执行引擎）+ `BridgeSource.importText(json, overwrite, origin)` 三参重载 + `BridgeSource.testCustom(id)`。**新功能 = 自动分析器产出 SourceConfig JSON，复用现有引擎零新运行时。**

## 实现内容

### 1. 新建分析器 `src/com/tingshu/app/source/gen/SourceProbe.java`
入口签名：
```java
public static Result probe(String homeUrl, Cb cb);  // cb.onProgress(stage, pct, msg)
public static final class Result { String configJson; String id; List<String> diag; boolean ok; }
```
探测算法（总预算 45s deadline，请求走 `core/Net.java`，请求数 ≤15）：

1. **站点判别**（pct 10）：`Net.get(homeUrl)`。响应为 JSON → API 型；HTML 空壳（body<2000 或无 `<a href` 且有 JS 加载痕迹）→ 用 WebView 渲染重取。在 `core/js/WebViewRenderer.java` 新增 Java 侧静态封装 `renderUrl(String url, int timeout, boolean desktop)`，复用现有 `newWebView/evalJs/UI.post/latch` 主体（现 `render(cx, scope, ...)` 是 Rhino 宿主签名，Java 侧不能直接调）。
2. **搜索探测**（pct 20-50）：a) 正则找 `<form action>` + `<input name>`（name 含 kw/key/word/search 优先），按 method 构造；b) 无表单则并发试候选模板 `/search?q={kw}`、`/search.php?keyword=`、`/s/{kw}`、`/so.php?q=`、`/api/search?keyword=`（关键词用「斗」）。验证：响应抽 `<a href>` 条目 ≥2 条、href 可 `Utils.abs` 且同 host、标题非空 → 记录 `search.items/title/urlField` 正则；JSON 响应则深度优先找含 ≥2 元素的数组路径（data.*/list.*/result.*），取 name/title + url/id/link 字段。
3. **详情/章节探测**（pct 50-75）：GET 详情页，按块切分打分（`<a href>` 密度 + 含「章|集|话|播|听」+2），取最高分块生成 `detail.items/title/urlField`；集数 ≥5 才算成功；「全集列表/更多」链接再抓一层填 `detail.page`。
4. **音频直链探测**（pct 75-90）：进第一个章节页，依次：裸 mp3/m4a/aac/ogg 正则（含 `unescape`/`abs` 补全）→ `<audio src>` → JS 变量 `"(url|mp3|play_url|audio)"[:=]"..."`（验证后缀/HEAD 可达）→ 响应 JSON 则 `audio.mode=json`；全败 → `audio.mode=page` + 宽泛正则，诊断标注「未直连，已降级，可配合嗅探」。
5. **诊断**：每步 成功/失败/降级 写入 `diag`（人话），最终 ok = 搜索✔ 且 章节✔（音频可降级）。

**SourceConfig 填充**：`id`=host 主体+短 md5 防撞；`name`=首页 `<title>` 去后缀；`base`=origin；`headers` 记录探测所需 UA；`origin="gen"`。降级边界：登录跳转（login/passwd 关键词）终止；明显签名/加密 API 报「无法自动生成」。

### 2. Bridge 新方法（`src/com/tingshu/app/Bridge.java`，仿 autoGenSource → Aggregator 线程池模式）
```java
@JavascriptInterface public void autoGenSource(final String url)
```
后台执行 probe（进度 `callback("onGenProgress",{stage,pct,msg})`）→ 成功走 `BridgeSource.importText(configJson, true, "gen")` → 立即 `BridgeSource.testCustom(id)` → `callback("onGenDone",{ok,id,added,test:{ok,msg},diag:[...]})`；失败也给 onGenDone 带诊断。

### 3. 前端 UI（`assets/www/app.js` + index.html）
- `ui.importMenu()` 加「输入网址自动生成书源」项 → 输入框（复用 impUrl 样式）→ `TS.call('autoGenSource', url)`。
- `window.onGenProgress`：进度 sheet（仿 onVerifyProgress 滚动日志）。
- `window.onGenDone`：成功 → toast + 跳源列表、按 id 高亮新源；test 未过仍保留源并提示；失败 → 弹诊断列表。

### 4. 版本与发布
- `AndroidManifest.xml`：versionCode 231 / 4.21.39。
- `assets/www/app.js` CHANGELOG 头部插入 v4.21.39 条目。
- 构建 `bash /workspace/fengxi-tingshu/build.sh`；产物复制为 `/workspace/fx-video-download/fengxi-tingshu-v4.21.39.apk`，删旧包；更新 `manifest.json` 听书条目（version/versionCode/file/size/md5/updated/notes）。

## 关键复用
- `SourceConfig.from(JSONObject)` / `parseSearch/parseDetail/parseAudio` —— 生成器直接组装同构 JSON。
- `AbsSource.grab/grabBlocks/meta/stripLabel`、`Utils.abs/stripTags/unescape` —— 探测正则抓取。
- `core/Net.get/post`（per-host Cookie/UA 已内置）。
- `BridgeSource.importText(json, true, "gen")`、`testCustom(id)`。
- `WebViewRenderer` 内部 `newWebView/evalJs/UI.post/latch` —— 提取为 renderUrl 封装。

## 验证
1. 构建通过、R8 keep 无新增需求（新类不进 manifest）。
2. 用真实站点沙箱实测探测算法：写一个桌面 JVM harness（仿上一轮 fmfm 复现方法，编译 SourceProbe + stub Net）跑 2-3 个听书站（如 ting15 类 HTML 站），确认生成的 SourceConfig JSON 结构能被 `SourceConfig.from()` 解析、`JsonSource` 语义匹配。
3. 发布后经下载站 manifest 链路端到端核对（curl 验证 manifest 与 APK 直链 200）。
