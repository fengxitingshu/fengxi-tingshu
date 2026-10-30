# 枫兮听书 · 书源插件规范（Fx Plugin API v1）

> 版本：1 · 2026-10-29 · 宿主支持的最高 API：**1**
> 适用对象：jar 插件书源作者。目标只有一个——**让"宿主/插件约定不一致"（酷我事件）不再可能发生**。

---

## 0. 一分钟版

一个插件 = 一个 jar（zip 内含 `classes.dex` + `META-INF/MANIFEST.MF`）：

```
MANIFEST.MF
----------
Source-Entry: com.example.myplugin.MySource
Fx-Api-Version: 1
```

入口类实现宿主 `com.tingshu.app.source.Source` 接口，无参构造 public。
宿主加载时做版本协商：插件要求 > 宿主支持 → 导入时明确拒绝，绝不带病运行。

---

## 1. 接口契约

### 1.1 必须实现（`com.tingshu.app.source.Source`）

| 方法 | 签名 | 语义约定 |
|------|------|----------|
| `id()` | `String` | 稳定唯一 id，存库主键，**发布后永不变**。建议 `作者_站点` 形如 `zhangsan_qingting` |
| `name()` | `String` | 显示名，纯文本，不带表情/广告后缀 |
| `desc()` | `String` | 一句话简介 |
| `canSearch()` | `boolean` | 是否支持关键词搜索 |
| `search(kw, page)` | `List<Book>`, page 从 1 起 | **失败返回 null，无结果返回空表**。单页建议 ≤ 20 条 |
| `detail(bookUrl)` | `BookDetail` | `bookUrl` = 你自己 `search()/browse()` 给的 `Book.url`。章节列表必须完整（第 1 集到最后一集） |
| `audioUrl(ch, book)` | `String` | 取直链，可耗时。**失败返回 null，不抛错** |
| `audioHeaders(url)` | `Map<String,String>` | 播放附加请求头（Referer 等）。**不需要就返回 null**（CDN 带多余头会 403） |
| `coverHeaders(url)` | `Map<String,String>` | 封面请求头，不需要返回 null |

### 1.2 可选实现（`BrowsableSource`，目录型源才需要）

```java
public interface BrowsableSource {
    boolean canBrowse();
    List<Category> categories();                    // 分类清单，catId 自己定义
    List<Book> browse(String catId, int page);      // 失败 throw Exception（带人话）
}
```

### 1.3 生命周期

1. **导入时**：`newInstance()` 一次（此后宿主可能复用实例，也可能重新 new——**插件必须无状态**，别在字段里缓存"当前会话"数据）。
2. **运行期**：`search/detail/audioUrl` 都在后台线程调用，可以联网、可以阻塞；但单个调用应在 **20 秒内**返回（超时宿主按失败处理）。
3. **永不回调 UI**：插件拿不到 Context，也别碰 Toast/Dialog。有话要说 → 抛 `Exception("人话")`，宿主负责展示。

### 1.4 错误处理铁律（酷我事件的两条教训）

- **自己的 catch 不要吞**：catch 到异常必须 `throw new RuntimeException(原因)` 或返回 null，让宿主有机会显示真实错误。静默 `catch {}` 会让"你的源是空的"变成用户眼里的"App 有 bug"。
- **依赖缺失要在加载期暴露**：别把宿主类引用藏在只有运行期才走的分支里。`detail()` 里才第一次碰宿主类的写法，导入测不出来。

---

## 2. 版本协商（Fx-Api-Version）

| 场景 | 宿主行为 |
|------|----------|
| MANIFEST 无 `Fx-Api-Version` | 视为 v1，正常加载 |
| `Fx-Api-Version: 1`，宿主支持 1 | 正常加载 |
| `Fx-Api-Version: 2`，宿主支持 1 | **导入即拒绝**，提示"请升级 App"，绝不加载 |

给插件作者的规则：**只要你用到了本规范未定义的行为，就升版本号**。向后兼容的小改动（bug 修复、新增站点）不升；改方法语义、加必选方法、改 URL 约定 → 升大版本，并等宿主支持后再发布。

---

## 3. 宿主提供的运行时（插件可直接 import）

| 宿主类 | 用途 | 注意 |
|--------|------|------|
| `com.tingshu.app.data.Models.*` | `Book` / `BookDetail` / `Chapter` 数据结构 | 字段直接赋值即可 |
| Fuel shim（`com.github.kittinunf.fuel.*`） | HTTP 请求 | `response()` 返回 `Triple<Request, Response, Result>`，**third 是 `Result`（抽象类），`.get()` 拿 bytes**——酷我插件当年就是把它当裸 byte[] 用才 CCE |
| `com.github.kittinunf.result.Result` | 上述返回包装 | `Success`/`Failure` 子类，`get()` 直接解包 |
| JSON：`org.json` | 全部 JSON 处理 | 系统自带，无需打包 |

**禁止**：打包宿主已有类（重复类会加载失败）；引用 `com.tingshu.app.*` 里未在本文档列出的内部类（非公开契约，随时会变）。

---

## 4. 打包与分发（合规红线）

1. **MANIFEST 必须有** `Source-Entry`（省得宿主全量枚举类名）。
2. `minSdk` 与宿主一致（21），`compileOptions` Java 8 即可，不要引入 kotlin-stdlib 之外的重量级依赖。
3. **插件作者对自己的源负责**：只写你自己有权分发内容的站点。宿主（枫兮听书）不审核、不分发、不为任何插件的版权问题背书——这条写进你发布页的免责声明里。
4. 分发渠道自己负责；不要声称"官方源"。

---

## 5. 自检清单（发布前过一遍）

- [ ] MANIFEST 有 `Source-Entry` 与 `Fx-Api-Version: 1`
- [ ] id 全网唯一且不会再改
- [ ] `search` 空结果返回空表、失败返回 null（不返回装着 null 元素的 list）
- [ ] `detail` 章节完整、Chapter.title 可读（含扩展名更稳）
- [ ] `audioHeaders`：CDN 不需要 Referer 时返回 null
- [ ] 全代码无静默 `catch {}`
- [ ] 无状态：连开两次 `detail` 结果一致
- [ ] 宿主私有目录加载测试通过（不能依赖外部存储路径）

## 6. 变更记录

| API 版本 | 内容 | 宿主支持自 |
|----------|------|-----------|
| 1 | 初版：Source 接口 9 方法 + BrowsableSource + Fuel shim 语义 + 版本协商 | v4.17.0 |
