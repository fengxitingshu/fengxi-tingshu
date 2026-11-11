# 枫兮影视 v1：飞牛 NAS 影视播放器（独立 App）

## Context

用户要新做一个视频播放器，参照 EasyBox（TVBox 系**竖屏空壳播放器**：海报墙 + 可配置内容源 + 点播），
第一版先打通**飞牛 NAS fnOS 影视**对接，且**必须支持 IPv6 链接**。此前枫兮听书 App 的视频功能
（v4.20.x）已整体回退，但工程里留下了完整可复用的家底：`gatesctl/` 独立第二 App 骨架、
ijkplayer 的 jar + 三个 .so、处理自签证书/Range/IPv6 的 `LocalProxy`、WebView+Bridge 前端范式。
因此不需要从零搭工程，而是新建一个**独立 APK**，不动现有听书 App。

用户已确认的四项决策：

| 决策 | 选择 |
|---|---|
| 交付形态 | **独立新 App**（独立包名/APK，复用现有构建链与播放器依赖） |
| 飞牛登录 | **混合**：App 内 WebView 登录自动抓凭据为主 + 手动粘贴 Token 兜底 |
| IPv6 场景 | **两种都要**：纯 v6 字面量 `[2408:…]:5666` 与 DDNS 域名（AAAA 双栈） |
| 联调条件 | **不提供真 NAS**，做 App 内自填地址 + 内建连接诊断页，用户手机自测回传日志 |

目标产出：一个可安装的 APK，用户在手机上填自己的飞牛地址、登录一次，就能看到影视库海报墙并点播，
全程 IPv6 可达、错误可诊断。

---

## 一、总体架构

- 新目录 `/workspace/fengxi-tingshu/fnplayer/`，包名 `com.fengxi.fnplayer`，App 名「枫兮影视」。
- **单 MainActivity + WebView 承载全部浏览 UI**（照抄 `MainActivity.java:104-150` 范式），
  前端单 HTML 多视图 + `TS.call` / `window.onXxx` 桥。
- **原生 `VideoActivity`**（SurfaceView + IjkMediaPlayer）负责播放，由 `TS.call('openVideo',…)`
  → `Bridge.openVideo()` → `startActivity` 跳过去（照抄 `Bridge.java:2571 openWebLogin`）。
- 网络与凭据走 Java 层；`LocalProxy`（127.0.0.1）在播放链路统一补 Cookie/Authorization、
  透传 Range、改写 m3u8 分片地址。

**复用清单（复制后按新包名裁剪）**

| 现有文件 | 复用内容 |
|---|---|
| `gatesctl/build.sh`、`gatesctl/AndroidManifest.xml` | 5 步独立构建骨架、Manifest 结构 |
| 主 `build.sh:70,85,356-368,378` | ijkplayer jar 进 classpath、三个 .so 入包、zipalign -p |
| `src/com/tingshu/app/core/Net.java` | `request()`/Resp/Cookie/重定向/短超时（`:211`）、`ipv6Available():131` |
| `src/com/tingshu/app/core/LocalProxy.java` | 本地代理逐跳带头、Range 透传、自签 TLS |
| `src/com/tingshu/app/core/WebDavClient.java:823` | 单连接 trust-all TrustManager + hostname 放行 |
| `src/com/tingshu/app/LoginActivity.java:159` | WebView 登录后 Cookie/Token 自动抓取 |
| `src/com/tingshu/app/player/Engines.java:209-301` | `IjkEngine`：loadLibrariesOnce、setOption、setDataSource(headers)、prepareAsync、setSpeed |
| `assets/www/{index.html,app.js,style.css,bridge.js}` | `.shelf/.bk` 海报墙、`TABS/showOne/goTab`、`.setrow` 表单、`inputSheet`、`TS.call`、图片懒加载 |
| `src/com/tingshu/app/data/BackupCred.java:50`、`Settings.java:17` | 独立 SharedPreferences 存服务器地址/token（不进听书的库） |

---

## 二、文件清单

新建于 `fnplayer/`：

```
build.sh                     # gatesctl 5 步 + assets + lib + ijk jar
AndroidManifest.xml          # 3 个 Activity、INTERNET/ACCESS_NETWORK_STATE/WAKE_LOCK、cleartext
src/com/fengxi/fnplayer/
  MainActivity.java          # WebView + addJavascriptInterface(new Bridge(...),"Android")
  Bridge.java                # @JavascriptInterface：配置/登录/列表/诊断/openVideo
  LoginActivity.java         # 内嵌飞牛影视 WebView 登录 + 抓凭据
  VideoActivity.java         # SurfaceView + 控制条（纯代码建视图，免 res/layout）
  core/Net.java              # 裁剪自主工程
  core/Utils.java            # normalizeBase / host 解析 / md5 / 诊断错误映射
  core/LocalProxy.java       # 裁剪 + m3u8 改写
  data/Config.java           # baseUrl / token / user / trustSsl / 上次媒体库
  fnos/FnClient.java         # 飞牛 API 客户端
  fnos/Authx.java            # authx 签名（仅高级模式用，默认不启用）
  player/IjkHolder.java      # 视频播放封装
assets/www/{index.html,app.js,style.css,bridge.js}
lib/arm64-v8a/{libijkffmpeg.so,libijkplayer.so,libijksdl.so}   # 从主工程复制
java-lib/ijkplayer-java.jar                                     # 从主工程复制
```

第一版**不做 res/**：主题用 `@android:style/Theme.NoTitleBar.Fullscreen`，播放页纯代码建视图，
图标用系统默认（`android:icon` 指向系统 drawable），后续再补品牌资源。

---

## 三、分阶段实施

**阶段 0 — 骨架跑通**
复制 gatesctl 骨架 → 改包名/输出名 → 加 `-A assets`、ijk jar/so 打包段 →
写一个最小 WebView 首页 + `TS.call` 回显，编译出能装能开的空壳 APK。

**阶段 1 — 连接配置与 IPv6 诊断**
`Config` + 配置页（服务器地址、HTTP/HTTPS、信任自签证书开关、Token 手填框）+ 「连接诊断」页。
`Utils.normalizeBase()` 处理裸 v6 自动补方括号、尾斜杠、默认端口 5666/5667；
`Utils.host()` 解析主机时保留方括号内冒号（否则 Cookie 隔离键/证书校验会错）。
诊断分 5 段逐级输出并计时：URL 解析 → DNS/TCP(5666/5667) → TLS → `GET /v/` → `GET /v/api/v1/mdb/list`。
错误映射成中文：「地址格式错 / 网络不通（含 v6 不可达、端口未放行）/ 证书不受信 / 接口无响应 / token 失效(code=-2)」，
支持一键复制日志。

**阶段 2 — 登录（混合）**
- 主路径：`LoginActivity` WebView 打开 `{base}/v/` 让用户正常登录（飞牛前端自己算 authx），
  登录后同时抓 `CookieManager.getCookie()` 的 `Trim-MC-token` 和注入 JS 读 localStorage 的
  `Authorization`，写入 `Config`；`onReceivedSslError` 在 trustSsl 开启时 `handler.proceed()`，
  `MIXED_CONTENT_ALWAYS_ALLOW`。
- 兜底：配置页「粘贴 Token」，用户浏览器 F12 复制任意 XHR 的 `Authorization` 值。
- 高级可选：`Authx.sign()` 实现完整签名 + 手填 `secret_string`/`api_key`（默认隐藏，
  仅当探测发现列表接口强制 authx 时引导）。
- `FnClient.probeNoAuthx()`：不带 authx 请求 `mdb/list`，据此决定是否需要高级签名。

**阶段 3 — 内容浏览（原生 UI + WebView 降级）**
`FnClient.mdbList()` → 媒体库列表；`items(guid,page)` → 影片/剧集列表（实现时从
`fntv-electron` 的 `src/modules/fn_api/api.ts` / `fntv-client-multiplatform` 提取确切的
`/v/api/v1/...` 列表、详情、剧集路径）→ 前端 `.shelf/.bk` 海报墙 + 详情页。
**降级保底**：若列表 API 因 authx/版本差异调不通，自动切到「内嵌 WebView 浏览飞牛影视网页」模式
（网页天然带签名，海报墙由网页提供），只在点击播放时由注入 JS 提取 `mediaGuid` 并接管到原生播放器。
这样即使 API 逆向不完整，第一版也一定能用。

**阶段 4 — 原生播放**
`VideoActivity` + SurfaceView：`surfaceCreated` 里 `mp.setDisplay(holder)`（相对音频版唯一新增调用）。
`IjkHolder`：`loadLibrariesOnce(null)`、`mediacodec=1` 硬解（花屏降级 0 软解）、`soundtouch=1`、
`setDataSource(ctx, Uri, headers)` 带 Cookie/UA、`prepareAsync`。
播放地址 `/v/media/{guid}/preset.m3u8`（HLS）或直链；**默认经 LocalProxy**，补 Cookie 并
把 m3u8 里的相对/绝对分片 URL 改写成本地代理地址，避免分片丢 Cookie 403。
最小控制：播放/暂停、seek、倍速、选集（换 guid 重启）、`onCompletion → window.onVideoEnded()`；
`onDestroy` 释放 mp 并关代理条目。

**阶段 5 — 打磨打包**
深色竖屏 UI、海报墙骨架屏、播放错误提示（带诊断信息回传）、CHANGELOG 页；
编译 → 归档 `release/枫兮影视-v1.0.apk` → 弹分享面板给用户。

---

## 四、关键技术点

- **IPv6**：`http://[2408:xxxx::1]:5666` 字面量必须带方括号才符合 RFC 3986 / Java `URL`；
  不设 `java.net.preferIPv6Addresses`（避免污染 WebView），域名 AAAA 交给系统双栈解析；
  Manifest `usesCleartextTraffic="true"` + targetSdk 28 保证 5666 明文可达；
  诊断里用 `InetAddress.getAllByName()` 分别列出 v4/v6 地址，明确告知「解析到了但连不上」。
- **HTTPS 自签**：只对**单条** HttpsURLConnection 装 trust-all TrustManager + 放行 hostname
  （照搬 `LocalProxy.applyNasTls()` / `WebDavClient.java:823`），不动全局 SSLSocketFactory；
  `trustSsl` 默认关闭并在 UI 标注风险。
- **authx 签名**（`Authx.sign`）：nonce(6 位) + timestamp(ms) → body_hash
  （GET：params 按 key 排序 urlencode 后 md5；非 GET：JSON `sort_keys` + 紧凑序列化后 md5，
  空 `{}` 也参与）→ 待签串 `secret + " " + path + " " + nonce + " " + ts + " " + body_hash + "_" + api_key`
  → md5 = `sign` → 头 `authx: nonce=…&timestamp=…&sign=…`；后续带 `Authorization: <token>`。
- **播放鉴权**：Cookie `Trim-MC-token=<token>; lastLoginUsername=<user>`（fntv-proxy 已验证的路径）；
  LocalProxy 统一补头，HLS 分片改写是必须的。
- **诊断分类**要精确到步，用户手机自测时只需把日志截图发回即可定位。

---

## 五、构建脚本要点

复制 `gatesctl/build.sh`（aapt2 link → javac → d8 → zipalign → apksigner），改动：
- `G` 指向 `fnplayer/`，输出 `枫兮影视-v1.0.apk`；d8 用 `BT8=/tmp/bt35/android-15` 的 d8 更稳。
- `aapt2 link` 加 `-A "$G/assets"`；javac 与 d8 的 classpath 加 `java-lib/ijkplayer-java.jar`。
- 打包段新增 `zip -0 -r` 只合入 `lib/arm64-v8a/` 下**三个** libijk*.so（剔除 Chaquopy / mobile-ffmpeg），
  `zipalign -p -f 4`。
- 走 d8 直译不混淆，**去掉**主工程的 R8、Manifest 保名闸门、证书/反射闸门
  （那些绑定 `com.tingshu.app.*`）；KS 沿用 `fengxi` 别名。

---

## 六、MVP 边界

**做**：单源飞牛、v6 字面量 + 域名、混合登录、媒体库/影片列表 + 海报墙、HLS/直链点播、
进度/倍速/选集、连接诊断页、API 不通时 WebView 浏览降级。

**不做**：多源/订阅源插件体系（但 `FnClient` 接口按可替换源设计，为后续留口）、authx 自动反混淆、
搜索、完整剧集树、字幕/音轨、播放进度回传飞牛、下载缓存、投屏、悬浮窗、TV 横屏布局、
`/v/media/play/info` 转码后端。

---

## 七、验证方案（无真 NAS）

1. **本地 mock 飞牛服务**（沙箱内 Python，HTTP 5666 / 自签 HTTPS 5667）：实现
   `/v/api/v1/login`、`/v/api/v1/mdb/list`、影片列表、`/v/media/{guid}/preset.m3u8`（指向本地测试 mp4）、
   静态海报；绑定 `::1` 专门验证 `http://[::1]:5666` 字面量路径。
2. **桌面纯 JVM 单测**：`Authx.sign` 与 Python 参考实现逐字节比对（GET 排序、紧凑 JSON、空 `{}`）；
   `normalizeBase` 覆盖裸 v6 / 方括号 / 域名 / 尾斜杠 / 自定义端口；诊断错误映射表。
3. **APK 内建诊断页**：逐项结果 + 耗时 + 原始异常，一键复制。
4. **用户手机自测**：装 APK → 诊断页填 `http://[IPv6]:5666` 或域名 → 看哪步红 →
   网页登录或粘贴 Token → 海报墙点播 → 把诊断/播放日志截图发回，按日志迭代。

---

## 八、风险与需用户配合确认

以下均**未经真机/真 NAS 实测**，靠内建诊断页 + 用户日志收敛：

1. 列表类 API 是否接受纯 Cookie/Authorization 而免 authx —— 决定了原生海报墙能否直接成立；
   不成立则自动降级到 WebView 浏览模式，或引导粘贴 Token / 手填签名参数。
2. 影片列表、剧集、海报字段的确切路径与返回结构（需实现时查第三方客户端源码）。
3. `Trim-MC-token` Cookie 名、HLS 分片是否继承 Cookie —— 靠 LocalProxy 补头 + playlist 改写兜底。
4. WebView 登录后凭据落在 Cookie 还是 localStorage —— 两处都抓。
5. 家宽 IPv6 的可达性/运营商封锁、端口放行 —— 诊断页明确区分「DNS 解析」「TCP 连通」「接口响应」三段。
6. ijkplayer 视频硬解兼容性（花屏降级软解）；trustSsl 默认关闭并标注风险。
