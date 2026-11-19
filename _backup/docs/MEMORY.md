# 枫兮听书 · 项目记忆与决策备忘（MEMORY）

> 本文件独立于代码，记录项目背景、关键决策、法律边界与下一阶段（闲鱼接单）方向。
> 作为后续阶段的上下文速查，避免每次重新交代背景。

## ⚡ 最新进度指针（2026-11-12，跨设备/跨会话请先读这里）

> 下方第 0 节及各 v2.x 里程碑段落是**历史记录**，版本号已过时；当前状态以本节为准。

| 模块 | 当前版本 | 位置 |
|---|---|---|
| 枫兮听书 | **v4.21.42 / code 234** | 本目录 |
| 枫兮影视（含车机化） | **v2.22 / code 33** | `fnplayer/` |
| 下载站 / NAS 锚点 | manifest 驱动 / v2 | `../fx-video-download/`、`../fx-nas-anchor*/` |

- **完整交接卡**：`_backup/docs/会话进度快照-20261112.md` —— 新会话粘贴即可续上，含本次会话全部结论、文件速查、待办、保密边界、电脑端接续步骤。
- **全量进度纪要**：`_backup/PROGRESS.md`（覆盖影视 v2.17→v2.22、听书 v4.21.35→42、WorkBuddy 咨询结论）。
- **同步通道**：GitHub PRIVATE `fengxitingshu/fengxi-tingshu` 的 `main`；电脑端 WorkBuddy `git pull` 后打开本目录即读到本文件。
- **本地完整备份**（含明文机密，⚠️ 绝不上传）：`../枫兮项目-记忆进度完整备份-20261112.tar.gz`。
- **保密**：域名 / sharecode / token / Cookie 一律占位符；keystore、APK、95MB 会话原始记录不入库；PUBLIC 仓库 `fengzhiyu` 不触碰。

## 0. 速览
- **项目**：枫兮听书（FengXi TingShu）—— Android 听书 App，用户自写书源的**框架型工具**
- **当前版本**：v4.21.42（versionCode 234；此字段曾长期停在 v4.21.8，已按上方指针校正），含手机版 + 车机版（`-car`，`CAR_MODE=1` 构建）
- **定位**：**非营利、技术中立**的听书框架；书源由用户自己写，App 不内置、不聚合盗版资源
- **规模**：App 用户 100+ 人；QQ 群计划**千人封顶**即止步

## 1. 技术栈
- 原生层：Java（主）/ 少量 Kotlin，包名 `com.tingshu.app`
- 前端：`assets/www`（app.js + style.css + index.html），经 `Bridge`（`@JavascriptInterface`）与原生通信
- 书源四种：JSON 源 / Python 源（`.py`，内置 CPython）/ JS 插件源（Rhino）/ jar 插件源（`DexClassLoader`）
- 构建：`build.sh`（`CAR_MODE=1` 车机版）；签名密钥本地 `test.keystore` **绝不入库**
- 网络：`Net.java` 统一 HTTP，支持 Cookie 捕获、桌面/移动 UA

## 2. 功能锁 FeatureGate（风控刹车）
- **机制**：远程 JSON（签名行 + payload 行）+ `SHA-256(payload + SALT)` 验签；12h 进程内缓存；根域名经 Base64 解码
- **⚠️ SALT 存于源码**（`FeatureGate.java` / `gatesctl/` / `private/gates-panel.html` / `make-gates.py`），**切勿公开到公开仓库，否则功能锁失效**
- **部署拓扑**：飞牛 FnOS Docker nginx(:9080) 挂载 `/vol1/1000/miyue` → Lucky 整域名反代 → Let's Encrypt 证书（自签 `CN=Lucky` 会被 Java 严格校验拒，必须 ACME 真证书）
- **gates 文件**：两行纯文本（签名行 + JSON 行）放 `index.html`；APK 不校验 Content-Type
- **锁控双形态**：网页 `private/gates-panel.html`（纯 JS SHA-256，已修复 `s-=4` 步长对拍）/ 独立 APK `gatesctl/`（Java `PUT` 写飞牛 WebDAV）
- **当前功能键（默认全开）**：`dav_export` / `webdav_lib` / `dav_backup` / `sync_progress` / `pan` / `custom_src` / `plugin`
- **定位**：功能锁是"随时可远程收回的刹车"，不是风险源；锁的功能本身（WebDAV/同步/网盘/自定义源/插件）都是用户自有数据或技术中立能力

## 3. 法律与合规边界（贯穿全程的核心约束）
- **App 本体：低风险**——100+ 人、非营利、UGC、不变现；版权方优先打变现渠道，不盯小圈子
- **QQ 群**：千人封顶；书源规则（json）可留（技术交流）；整本盗版音频/电子书等"成品资源"发现即清；避风港原则（不知情 + 通知后删 = 不担责）
- **书源/框架定性：技术中立**——只提供"导航 + 能力"，不存内容；服务器标准：直接侵权看谁上传/存储/传播（盗版网站），书源只是规则
- **红旗原则边界**：不内置、不聚合推广盗版源、不变现、不破解付费墙/DRM
- **商用边界**：不变现 + 不聚合 + 不托管 = 稳中立；收款码只做"自愿打赏"（不绑定任何功能/资源/入群）不算商用；"付钱才解锁功能"= 踩线
- **功能锁定位**：机制本身能上架能用（分发走自有发布页，不走应用商店；进商店是另一套资质审核，与锁无关）

## 4. 闲鱼接单方案（下一阶段重点）
- **定位**：个人技术服务收入，与 App 非营利定位**分开运营**（别用"枫兮听书作者"身份接单，避免把 App 干净定位搞脏/被关联）
- **三层服务**：
  1. **引流款**：合规小工具（批量处理 / Excel / 定时 / 自动填表）¥9.9~30，养号攒信誉
  2. **利润款**：接口分析 + 调用封装、登录态维持、动态页自动化 ¥100~500（映射修网盘 sign/stoken 的能力）
  3. **差异款**：App/网站接口逆向（仅公开数据、自己有权访问的）面议
- **技术映射**：Python 爬虫基础 + 已有的签名参数复现 / 抓包 / Android 逆向能力
- **🚫 不碰四样**：破解验证码、绕过付费墙/DRM、批量盗版权内容、撞库盗隐私
- **🔴 红线**：不问用途不接、用途不明不接
- **闲鱼实操**：敏感词软化（用"数据采集/接口开发/自动化工具"替代"爬虫/破解"）；站外导流用"拍下后发联系方式"；先养号 3-5 天；第一单低价换好评
- **注**：接单是合法的个人劳动收入；但单子性质会传导——客户让你爬盗版/破解会员，写了就是帮助侵权

## 5. 已上线主要功能线（版本里程碑）
- v4.15.x~v4.17.0：基础听书、书源框架、酷我源框架支持（修框架非发源）、播放失败自愈、多设备续听（#21）
- v4.17.0：暂停即上传无缝续听（#43）、WebDAV 垃圾文件过滤（#19）、插件版本协商（#26）
- v4.17.1：修复 js/py 源 🔑 网页登录跳转
- v4.18.0：下载任务可视化管理（暂停/继续/取消）、个人音频库视觉分界、功能锁扩容（pan/custom_src/plugin）
- v4.18.1：百度网盘解析重写（现行三步走 + 提取码）、夸克/UC passcode、115 访问码、天翼真实列表；修复首页只显示一个源的冷启动快照 bug
- v4.21.0：按用户反馈整体回退视频/短剧功能（MainActivity/PlayerCore/Engines/Source/JsSourceAdapter 五文件 checkout 回 HEAD，Bridge 删 showVideo，前端删 onVideoMode/openVideo/视频按钮；红果源相关全部移除）；保留 .js 音源直导（LxRuntime/JsCompat/LxSourceAdapter/JsPluginLoader）与崩溃取证/诊断日志；修复 Rhino Messages*.properties 未进 APK 导致脚本报错即 MissingResourceException 的吞错问题（build.sh dex 打包后注入资源）；Rhino 1.8.1+bt35 R8 保留
- v4.21.1：修复单 .js 音源导入报「没有解析到任何源定义」——回退时 MainActivity checkout 回 v4.18.1 基线，丢了 .js 路由分支；补回 .js → importRawJsFile（content:// 落盘 js_plugin_inbox 保留 .js 后缀 → type:"js" → importSourceJs → JsPluginLoader 单 .js 自动包装）。JsPluginLoader/LxRuntime 等运行时未受影响。
- v4.21.2：修复 LxRuntime console 未安装（getProperty 不存在时返回 NOT_FOUND 而非 Undefined.instance，防重复判断恒假）→ 墨澜脚本 953 行 console.log ReferenceError；改为无条件安装 console，initedJson 同步补 NOT_FOUND 判断。
- v4.21.3：单 .js 包显示名改为优先取脚本头注解 @name/@version（此前 Utils.safe 把中文冲成下划线只剩 v2.3.5-时间戳）；LxSourceAdapter 搜索/取链写 PlayerLog 诊断日志（取链失败此前被吞，用户只见无限缓冲）。
- v4.21.4：取链超时综合治理——http 超时收紧 5s/10s（多后端串行拖爆 30s latch）、定时器回调用主作用域+异常写诊断日志（原来全吞）、latch 30s→45s+明确报错文案；JsCompat 新增通用展开降级（Rhino 1.8.1 不支持 f(...xs)/{...x}/[...y]，此前只特判 console.log——洛雪音源15 语法错误即此因）：f(a,...xs)→__lxs 参数分发、{...x}→__oassign、[...y]→concat，桌面端洛雪+墨澜全量解析回归通过。
- v4.21.5：取链卡死真因定位——墨澜酷我后端1是纯拼URL零HTTP，链路 45s 不落定说明微任务滞留无人泵；resolveMusicUrl 改轮询泵（等线程每 2s 重进引擎 processMicrotasks），取链派发/回调/HTTP 全程写诊断日志。
- v4.21.6：定位取链卡死根因——Rhino 微任务队列是 Context 级（每 Context 一条 ArrayDeque），派发/定时器/HTTP 回调散在不同线程不同 Context 各自的队列里互相泵不到；重构 LxRuntime 为单 JS 线程 Actor 模型（lx-js 单线程跑全部脚本、HTTP 4 线程池、定时器只投递），request 改真异步回调（脚本侧 JS 超时从此生效），resolveMusicUrl 派发收口 JS 线程。另：洛雪音源的 103.79.184.97 API 服务器外网不可达（外部服务问题非 App 问题）。
- v4.21.7：确认取链打的是洛雪源的死服务器（103.79.184.97，URL 带 songmid/musicId/platform=kw 是洛雪 HYWmusic 格式，墨澜酷狗后端2 格式不同）——用户点的是洛雪源而非墨澜；洛雪脚本 HTTP 失败后 Promise 永不落定。修复重复导入堆积：importRawJsFile 落盘去时间戳（重导覆盖同一记录+清理旧文件），PluginRepo.dedupeJs() 启动按归一化文件名去重（保最新）；取链/搜索日志带源名，取链失败提示可切换源。
- v4.21.8：取链改 App 原生多后端，彻底弃用 LX 脚本异步链（根因：async/生成器 shim 的 Promise 微任务跨 Context 滞留，连纯拼 URL 零 HTTP 的酷我后端1 都 45s 不落定；且脚本内嵌 103.79.184.97 死服、海堂 APP_UPGRADE_REQUIRED 停服）。LxSourceAdapter.resolveNative：① c.[域名]/music/url POST JSON→url（实测 0.17s 出酷我 CDN 直链）② kuwo 官方 anti.s→https 直链 ③ kwstream 纯拼（Range 探测只读 1KB 兜底）。直链实测 206 audio/mpeg 可下。源文件原样加载不改。另：SourceRegistry 按脚本头 @name+@version（回退 MD5）去重，解决不同文件名装同一脚本导致双份加载。
- v4.20.4：单 .js 音源直导（LX 运行时 + JsCompat 语法降级：async/await→生成器、const→let，墨澜原版脚本原样执行不改动）+ registerSource 即时挂 host（红果等 prelude 系插件可原样导入）+ 移除悬浮"回到视频画面"chip（视频在播点悬浮球直回画面）+ 上下滑切集去提示直达 + Rhino 升级 1.8.1（需 build-tools 35 的 R8/d8，android-14 的 8.2.2-dev 对新字节码 NPE）
- v4.20.3：视频播放器完整化（上下滑切集/长按2倍速/全屏横屏/播放页内选集）+ 播放页按钮行「▶视频」入口四按钮分散对齐 + 设置页「诊断日志」（PlayerLog 导出，查 WMA 等播放问题）+ 红果插件 v5（categoryMenus 零网络直出，修发现页源列表慢）+ 墨澜音乐插件（酷我搜索+流媒体 master 直链；墨澜原版是 LX 取链源无搜索，海堂后端已加签名失效）
- v4.20.2：短剧播放器控制条（点画面呼出：播放暂停/上下集/拖进度/时长，3.5s 自动藏）+ 画面等比缩放防拉伸 + 返回键先收画面不退出 + 修「回到视频画面」chip 反逻辑；v4.20.1：崩溃取证弹窗（闪退日志界面化）+ 修 setBackgroundResource 启动闪退；v4.20.0：视频短剧源支持（红果短剧 JS 插件 + 视频画面层 SysEngine.setSurface + registerSource 返回源对象/video() 回退/isVideoSource）；红果官网搜索接口偶发 503 风控
- v4.19.2：转 MP3「看似未生效」根因修复（SAF/WebDAV 导出扩展名按实际内容 DlTrans.isMp3 判断，不再看 cXXXXX.m4a 文件名）+ 转码失败即时 Toast 显示原因
- v4.19.1：首页缓存直读+静默刷新（browseLoad allowStale、onBrowse 指纹比对免重绘）；转 MP3 不生效根因修复（isMp3 对带 ID3 标签的 m4a 误判→重写文件头判断+FFmpeg 日志收集 lastError+PlayerLog 诊断）；天翼登录页二维码显示不全（桌面 UA→移动 UA 走 H5）
- v4.19.0：下载「取消」立即生效（Net.download 接 Cancel 钩子+取消即出簿）；百度扫盘 errno=-7 根因修复（urlEnc 空格误编码为 + → 改 %20）+ 子目录失败跳过 + api/list 去 app_id 降级重试 + 分页拉全；新增下载格式选择（orig/mp3，DlTrans 反射 mobile-ffmpeg 转 128k MP3，扩展名/MIME 全链路动态化）；build-local.sh 补 ijkplayer/mobile-ffmpeg 依赖
- 已删计划项：#20 SMB、#22 车机兼容；剩余 #24 订阅授权（建议继续挂不动）

## 6. 构建与发布
- 命令：`bash build.sh`（手机版）；`CAR_MODE=1 bash build.sh`（车机版）；产物 `TingShu.apk`
- 发布页：`release/index.html`（更新到当前版本）；`release/make-gates.py` 生成 `gates.txt`
- 分发：自有发布页，不走应用商店

## 7. 重要约定
- 签名密钥、`.fengxi-env`、`release/` APK 等**绝不入库**（`.gitignore` 已覆盖）
- 功能锁 SALT 仅存源码，**仓库须 private**
- 不变现 / 千人封顶 / 用户自写源 —— 安全线三件套
- 下一阶段：**闲鱼接单赚外快（合规技术服务）**，与 App 品牌隔离

## 8. 独立 App：枫兮影视（fnplayer/，2026-11-05 新建）
- 目录 `fnplayer/`，包名 `com.fengxi.fnplayer`，独立 APK（约 4.4MB），**与听书 App 完全隔离**。参照 EasyBox（TVBox 系竖屏空壳播放器），第一版打通飞牛 NAS(fnOS) 影视 + IPv6。
- 构建：`fnplayer/build.sh`（复制 gatesctl 5 步骨架 + `-A assets` + ijkplayer jar 的 class 一并进 dex + 三个 libijk*.so `zip -0` + zipalign -p；d8 用 BT8，无 R8/保名闸门）。**坑**：`javac | grep || true` 会吞掉编译失败码，已改为显式判退出码。
- 结构：MainActivity(WebView) / Bridge(@JavascriptInterface，网络走单线程 IO 池) / LoginActivity(内嵌飞牛网页登录，抓 Cookie `Trim-MC-token` + localStorage token) / VideoActivity(SurfaceView+IjkHolder，纯代码建视图) / `fnos/FnClient`+`Authx` / `core/Net`+`LocalProxy`+`Utils` / `data/Config` / `assets/www` 四件套（竖屏海报墙 + 设置 + 诊断页）。
- 飞牛 API：`/v/api/v1/mediadb/list`（兼容 mdb/list）、`item/list`(POST)、`episode/list`、`play/play`、`/v/media/{guid}/preset.m3u8`；前缀兼容 `/v` 与无前缀。鉴权同时带 `Authorization`、Cookie(`authorization`/`Trim-MC-token`/`lastLoginUsername`)、可选 `authx` 签名（密钥由用户手填，默认不启用）。
- IPv6：`Utils.normalizeBase` 裸 v6 自动补方括号 + 默认端口 5666/5667，自定义端口必须写 `[v6]:port`；诊断 5 段（地址解析→DNS/地址族→TCP→HTTP/TLS→影视接口）。`LocalProxy` 改写 m3u8 分片与 AES 密钥 URI 为 `/seg?u=`，逐跳补 Cookie、透传 Range。
- 验证：`/tmp/fntest` 桌面 mock（Python 绑 `::1:5666`）+ 26 项测试全过：IPv6 字面量、authx 与 Python 参考逐字节比对、媒体库/影片/剧集、诊断 5 段、HLS 改写与 Cookie 透传。
- **未实测（用户手机自测）**：真实飞牛列表 API 是否免 authx、影片/海报字段名、`Trim-MC-token` Cookie 名、ijk 视频硬解兼容性。降级：首页/诊断页「改用网页浏览」内嵌飞牛 Web 端；硬解出错自动切软解重连一次。
- **v1.1（versionCode 2）**：诊断第 3 步 TCP 失败不再直接 return，改为自动扫 5666/5667/8000/8001/443/80 并给改址建议；纯 IPv6 且全端口不通时输出飞牛 IPv6 防火墙专项排查清单（路由器 IPv6 防火墙放行 5667、NAS 端口设置、DDNS 前缀变化等）。`FnClient.tcpProbe` 遍历所有解析地址逐个连；前端 `.diagrow .m` 加 `white-space:pre-wrap` 显示多行。
- **分发坑（vivo）**：聊天里的 APK 文件卡片点开只给「vivo 健康 / vivo 文档 / 浏览器」三个打开方式，**没有应用安装器**，装不上。设备分享面板（workbuddy-device）会话中曾连接失败/工具消失。**可用方案**：发布静态下载页（手机浏览器打开→点按钮下载→系统弹安装）。已发布 `https://[REDACTED]/`，站点目录 `fnplayer/download-site/`。注意带 sharecode 的访问控制会拦裸资源（403），页面用 JS 把 `location.search` 附加到下载链接。
- **2026-11-05 用户首次连通实测**：`https://[更新域名]:5667` → 地址解析 OK、DNS 解析 IPv4=0 IPv6=1（`2408:821b:8a22:ab31:62be:b4ff:fe02:f790`）→ **TCP 5667「连接被拒绝」**。当时判断为 NAS/路由器 IPv6 入站未放行。
- **v1.2（versionCode 3，2026-11-05）——「跟网页走」自动选路；上条结论已修正**：用户补充「网页版可以登录没问题」+ 浏览器飞牛登录页截图，推翻「整体 IPv6 不通」的判断。真因：域名解析到纯 AAAA（NAS 公网 v6），但浏览器登录页走的是**另一条路**（443 反代 / 飞牛官方 DDNS 中继 / IPv4 中转），App 却死磕 `IPv6:5667` 裸 TCP 直连；34ms RST 只说明「这一条直连路径没服务」，不代表 Web 服务不可达。**另查出一个发布事故：下载页一直放的是 v1.0**（v1.1 只归档在 release/、从未同步），用户装的始终是 v1.0，端口扫描功能根本没到手机上。
  - 新增 `core/Reach.java`：候选地址（原地址→`https://host`(443)→`http://host`(80)→5667/5666/8000/8001/5666）→ 4 线程并行发**真实 HTTP GET /v/**（自签证书一律放宽），任何 HTTP 响应码都算「有服务」；飞牛页面优先；成功即 `Config.setBase()` 写回 + https 时 `setTrustSsl(true)` + 进程内缓存 30min。
  - `FnClient.diagnose()` 第 3 步由「裸 TCP 一票否决」改为「网页可达性（自动选路）」，裸 TCP 降级为参考信息；第 4/5 步用选中地址；`FnClient()` 默认构造优先取 `Reach.cached()`。
  - `LoginActivity`：加载前先异步 `Reach.heal()` 再 loadUrl（避免地址填错导致登录页白屏）；`onPageFinished` 用 `Reach.originOf(url)` 把 WebView 实际生效的 origin 回填配置；Cookie 按实际 URL 抓。
  - `Bridge`：`version=1.2`、`effectiveBase`；`saveConfig` 清自愈缓存；`loadLibraries` 网络层失败时自动 heal 重试并回调 `onBaseHealed` 刷新前端输入框；诊断结束同样回传。
  - 前端/下载页：设置页、关于页、诊断页文案更新，新增 `onBaseHealed`；`download-site/index.html` 与 APK **同步到 v1.2**（`fengxi-video-v1.2.apk`），旧 v1.0 文件已删。
  - 验证：`/tmp/fntest/ReachTest.java` + `reach_mock.py`（模拟「直连端口全不通、网页在 8000」）17 项全过；换真实 `/tmp/fntest-json.jar` 后诊断 5 段全 OK。**坑**：桌面测试必须把真实 org.json jar 放在 android.jar 前面，否则 android.jar 里的 `Stub!` 空壳会让 JSON 解析假失败（第 5 步误报「响应不是预期 JSON」）。
  - **结论修正**：以后遇到「网页能登录、App 连不上」，先让用户复制浏览器完整 URL（含 scheme 与端口）或让 App 自动选路，**不要直接归因 IPv6 防火墙**。裸 TCP 只能证明单条路径。
  - **发布纪律（写死）**：`build.sh` 出新版本后，必须**同时**把 APK 复制进 `download-site/` 并改 index.html 的文件名/版本，否则用户下载的还是旧包。
- **v1.3（versionCode 4，2026-11-05）—— 原生账号密码登录 + 签名公式修正**：用户 v1.2 实测自动选路成功（网页在 `https://[更新域名]` 443 中继，`✓ HTTP 200 飞牛页面`，地址已自动写回），只剩第 5 步 API 全 404。**深挖社区开源实现后查出一个关键错误：此前 Authx 签名公式是错的**（空格连接 `secret+" "+path+...+"_"+api_key`），而 fnv.py（quark-auto-save PR#106）与 fnos-tv（hong-ke）两个**独立**实现都用**全下划线** `"_".join([secret, path, nonce, timestamp, body_hash, api_key])`；之前的"Python 交叉比对通过"是自己写的参考自己验，等于没验证（教训：交叉验证必须用独立第三方实现）。
  - **重大简化：secret/api_key 是飞牛前端的公开常量**，fnos-tv 直接硬编码：secret=`NDzZTVxnRKP8Z0jXg1VAMonaG8akvh`，api_key=`16CCEB3D-AB42-077D-36A1-F355324E4237`（随前端 JS 下发给每个浏览器，非机密）。已内置为 `Authx.DEFAULT_SECRET/DEFAULT_API_KEY`，authx 默认启用，用户不再需要 F12 挖密钥（手填仍可覆盖）。
  - **新增原生账号密码登录**：`POST /v/api/v1/login` body `{"username","password","app_name":"trimemedia-web"}` → `data.token`（fnv.py 同款），设置页新增密码框 +「账号密码登录（推荐）」按钮（`Bridge.loginNative` → `window.onNativeLogin`）；诊断第 5 步失败且填了账号密码时**自动登录重试并存 Token**。WebView 网页登录降级为备用。
  - **probe() 重写为路径矩阵**（trail 随诊断输出）：`GET /v/api/v1/user/info`（fnos-tv 的登录态校验端点）、`GET /v/api/v1/mdb/list`（fnv.py）、`GET /v/api/v1/mediadb/list`（fnos-tv-web），`/v` 前缀与无前缀都试；404=路径不存在 / 401/403 或 code=-2=需登录 / code=0=通，一眼定位。`Authorization` 裸 token（fnv.py/fnos-tv 一致）。
  - `Reach` 候选加 `http://host:8005`（社区：飞牛影视独立端口）。
  - 验证：`/tmp/fntest/ReachTest.java` 22 项全过，含签名与 Python 参考逐字节比对（新公式）、mock 鉴权（无 token code=-2、错密码 code=-1）、端到端「填错端口 9999 + 未登录 → 诊断自动选路 8000 → 自动登录 → 读到 1 个媒体库 → Token 入库」。Mock 升级为带最小鉴权（`/tmp/fntest/reach_mock.py`）。
  - **下一步预判**：真机诊断第 5 步若仍 404 → 中继没转发 `/v/api/`，让用户在设置直接填账号密码点登录（登录接口若也 404，则中继只代理了门户页，需要走 8005 直连或内网）；若登录成功但播放 403，检查 m3u8 直链的 Cookie/Referer（LocalProxy 已透传）。
- **v1.4（versionCode 5，2026-11-05）—— 修复 code=5000 invalid sign**：用户 v1.3 实测诊断 5 步全绿（`mdb/list` code=0），但首页影片列表报 `code=5000 invalid sign`。根因：**POST 请求「发送的 body」与「签名的 body」不是同一个字符串**——签名用 `Authx.compactSortedJson`（key 排序+紧凑），发送用 `body.toString()`（org.json 底层 HashMap，顺序随机）；fnv.py 的做法是 `json.dumps(sort_keys=True, separators=(',',':'))` 序列化后**签名和发送都用这一个字符串**（服务端按收到原文算 md5）。修复：`FnClient.call()` 对非 GET 统一先 `compactSortedJson` 再发/再签。GET 空参数两边都是空串所以诊断碰巧通过——这解释了「诊断全绿但首页挂」。验证：`CanonTest`（items body / login body 与 Python `json.dumps(sort_keys, separators, ensure_ascii=False)` 逐字节一致，含中文与引号转义）+ `ReachTest` 22/22 回归。诊断 5 步全绿≠POST 链路通，测试要覆盖带 body 的请求。
  - **v1.4 第二修**：主页 `MainActivity` 的 WebView 原来没有 `WebViewClient`/`onReceivedSslError`——自签 HTTPS 下海报 `<img>` 跨源子资源会全部静默失败（只有 LoginActivity 放行了 SSL）。已加 WebViewClient，仅对 `Config.base()` 的主机放行自签证书（`isNasHost()`），其他站点照常拒。
- **v1.5（versionCode 6，2026-11-05）—— 报错可视化**：用户称「更新最新版还是一样」但无法确认装的是哪版（Bridge version 字符串还停在 1.3、App 内无版本显示）。v1.5：① `resultJson` 失败时带 `raw`（服务器原始响应前 300 字符），首页报错原文显示（`hi-s` 样式加 `white-space:pre-wrap`）；② 设置页「关于」显示动态版本 `v{version}（versionCode {n}）`；③ Bridge version/versionCode 与 manifest 同步为手写字符串（**坑**：Bridge.java 里的版本号是硬编码，升版时容易漏，已与 manifest 一起改）。待用户回传：设置页版本号 + 首页报错原文（若还是 invalid sign，raw 会给出服务器原话）。
- **v1.6（versionCode 7，2026-11-05）—— 全接口对齐 fnos-tv-web**：找到 `thshu/fnos-tv-web`（飞牛影视第三方网页客户端完整源码，签名/请求与官方前端一致），一次性对齐所有接口：
  - **签名语义确认**：服务器按**收到的 body 原文**算 md5（fnos-tv-web 发插入序 JSON 且签同一串；fnv.py 发 sort_keys 紧凑串且签同一串——两种都能过，只要「发的=签的」。GET 是 md5(decodeURIComponent(sortedParams))）。
  - **海报**：`{base}/v/api/v1/sys/img/92/17/{poster}?w=200`（92/17 是官方前端写死的路径段）。`Bridge.toItem` 已按此构造，pick 字段补 `posters`/`backdrops`（详情接口用复数）。
  - **播放四步链**（`FnClient.playUrl` 重写）：`POST play/info {item_guid}` → `item.parent_guid`（空=电影直接用，非空=TV 换成该 guid）→ `GET stream/list/{guid}?before_play=1`（files/video_streams/audio_streams；文件选 path 不匹配 `\d+-\d+-\S+` 的本地文件）→ `POST play/quality {media_guid}` 取 `data[0].resolution` → `POST play/play {media_guid,video_guid,video_encoder,resolution,bitrate,startTimestamp,audio_encoder:"aac",audio_guid,subtitle_guid:"",channels:2}` → `data.play_link`（相对路径，`base + link` 即可）。旧 m3u8 直链猜测降级为 fallback。
  - **items() 对齐**：官方首屏不传 `page`（只有 page_size=48），仅翻页时附加。
  - 验证：`reach_mock.py` 补齐 play/info、stream/list、play/quality、play/play 四个端点（含「files[0] 是远程挂载」的陷阱），`ReachTest` 23/23 通过，播放链正确选出本地文件并换到 play_link。
  - **方法论教训**：之前靠零散搜索拼接口是猜，找到「完整复刻官方前端的同类项目」后所有接口一次对齐——优先找整站复刻项目而不是零散片段。
- **v1.7（versionCode 8，2026-11-05）—— 播放链全程可视**：用户视频反馈：详情页已正常（签名/列表/详情通），但播放报 `what=-10000`（打不开流），且取链死在哪一环不可见（trail 只进 Logcat）。v1.7：① `FnClient.lastPlayTrail` 静态字段暴露取链过程；② `VideoActivity.prepareAndPlay` 增加**播前 Range 探测**（`bytes=0-1`），非 2xx/3xx/416 不启动播放器，直接把「探测状态 + 取链过程 + 直链」显示在播放页；③ `onError` 附带 trail；④ `startTimestamp` 改回数字（官方传数字不是字符串）。**编译坑**：VideoActivity 用了 `HttpURLConnection`/`Net` 忘了 import，build.sh 正确拦截。待用户回传播放页截图即可定位最后一环（大概率：play/play 参数差异 / play_link 需要 authx / 302 后 Cookie 失效）。
- **v1.8（versionCode 9，2026-11-05）—— 播放器大改版（用户确认 v1.7 已能播放）**：参照 EasyBox/TVBox 系播放器标配，`VideoActivity` 全量重写：
  - **画面比例**：适配（默认，按 `onVideoSizeChanged` 真实尺寸 contain）/ 16:9 / 4:3 / 填充，SurfaceView 按 `applyRatio()` 重排——修掉之前 MATCH_PARENT 拉伸（「比例不对」的根因）。`IjkHolder.Listener` 加 `onVideoSize(w,h)`。
  - **手势**（GestureDetector）：单击=控制栏显隐（4s 自动隐藏）、双击=暂停、水平滑=快进快退（±100s 满屏，松手 seek，中央 HUD 预览）、左半屏竖滑=亮度（`screenBrightness`）、右半屏竖滑=音量（AudioManager）、长按=3x 倍速松开恢复。
  - **UI**：系统 Button 全部换圆角半透明 TextView（`mkCtrl`），顶/底栏渐变黑（GradientDrawable TOP_BOTTOM），进度条橙色 tint，时间 HH:MM:SS 自适应。
  - **横屏全屏沉浸**：默认 `SENSOR_LANDSCAPE` + IMMERSIVE_STICKY + 刘海 SHORT_EDGES，可切竖屏；manifest 已有 configChanges 旋转不重建。
  - **功能**：倍速 0.5~3x 循环、清晰度切换（`FnClient.lastQualityRes` + `playUrl(guid, forceRes)` 跳过 quality 步）、下一集/选集（`episodes(itemGuid)` 预取，`Bridge.openVideo` 加第三个 item 参数，app.js 详情页传父条目 guid）、播放结束自动连播、锁屏（点屏出解锁按钮）、返回键锁屏拦截。
  - 回归 23/23；**坑**：新字段 `curQuality` 忘声明编译失败两轮（重写大文件后先自查字段表）。
- **v1.9（versionCode 10，2026-11-05）—— 播放按钮无效 + 海报全挂双修**：用户反馈「点播放没反应、海报墙封面全没有」。
  - **播放没反应的确定性根因**：详情页 `btnPlay` 调 `TS.call('openVideo', guid, title)` 只传 2 参，而 `Bridge.openVideo` 已改为 3 参 `(guid,title,item)`——**WebView JS 桥按「方法名+参数个数」匹配，个数不对直接静默失败**（选集那路传了 3 参所以能播）。修复：JS 补第 3 参 + Bridge 加 `openVideo(String,String)` 2 参重载双保险。教训：**改 @JavascriptInterface 方法签名必须同步所有 JS 调用点，且加旧签名重载兜底**。
  - **海报全挂的根因**：`sys/img` 接口需要 Cookie/Token，但原生账号密码登录的 Token 只存在 Config——**WebView 的 `<img>` 请求既带不上 Authorization 头、Cookie 里也没有**（fnos-tv-web 是网页登录有 Cookie，所以它裸 `<img>` 能用，不能照抄）。修复：`Bridge.posterUrl()` 把海报全部改走 **LocalProxy**（带 playHeaders 的 Cookie+Authorization），LocalProxy 容量 32→256（海报数量多）；`posters` 字段为 JSON 对象时先解析取值（`firstString` 深度优先找文件名）。
  - 附带：原生登录成功后把 `Trim-MC-token` / `authorization` / `lastLoginUsername` 同步写进 WebView CookieManager（内嵌网页浏览模式也受益）。
  - 回归 23/23；产物同步下载页。
- **v2.0（versionCode 11，2026-11-05）—— 界面大改版（用户提供两张参考 App 截图：某影视 App 的「设置中心」与「播放详情页」）**：
  - **设置中心**（`pageSettings` 顶部新增分组行式布局，`sgroup/srow/s-v/tgl` 样式）：播放器组（渲染方式 SurfaceView↔TextureView、解码方式 优先硬解↔软解码、长按倍速 2/2.5/3 循环、默认画面缩放 4 档循环、自动连播开关、后台播放开关）+ 通用组（海报墙列数 1/2/3 循环、观看历史清空）。点行即生效并 `saveConfig` 落盘（`persist()` 以 cfg 为底合并 patch）。Config 新增对应 8 个字段 + `pushHistory/clearHistory`（去重置顶、最多 50 条）。
  - **详情页重排**（`dt2/actrow/act/epgrid/ep2` 样式）：海报+标题+meta（类型主题色/补充灰字）+描述 → 立即播放主按钮 → 分享/简介操作行 → 选集数字网格（`ep2.on` 主题色边框）。分享=复制文本；简介=展开全文。
  - **首页「继续观看」**：`hisw` 横滑卡片（`hcard`），播放历史点击进详情；`Config.pushHistory` 在 `startPlayer` 时记录。
  - **原生配合**：`IjkHolder.init(Context, Surface)` 重载支持 TextureView；`VideoActivity` 按 `Config.textureRender()` 选渲染视图（`videoSurface` 统一交给播放器）、`preferSoft` 直接软解、`defaultRatio` 初始比例、`longPressSpeed` 长按倍速、`autoNext` 连播、`backgroundPlay` 切后台不暂停；Bridge `getConfig/saveConfig` 扩展 8 个设置键 + `clearHistory`。
  - 回归 23/23；产物同步下载页。
- **v2.1（versionCode 12，2026-11-05）—— 播放页照迅雷播放器重做**（用户提供迅雷播放界面截图，要求布局/功能基本一致）：
  - **顶栏**：`‹` 返回图标 + 文件名（左对齐）+ 右侧「字」字幕（暂无外挂字幕，点击提示）、「⧉」画中画（`enterPip()`，API26+，manifest 加 `supportsPictureInPicture`，宽高比按视频尺寸取 4~239 区间）、「⋯」更多菜单（画面比例/竖屏全屏/清晰度/选集——原顶栏三按钮收进菜单）。
  - **左侧竖排 sideBar**：🔒 锁屏（原顶栏迁来）、📷 截图（`texView.getBitmap()` 存 `getExternalFilesDir/Screenshots`；SurfaceView 模式提示先切 TextureView）。
  - **中央大播放键 bigPlay**：暂停时显示（`syncPlayUi()` 统一同步底部播放键 `❚❚/▶` 与大键显隐；onPrepared/onEnded/togglePlay/onResume/onPause 都调）。
  - **底栏**：时间—细进度条—时长 + 第二行「▶ / ⏭ / 去黑边 / 倍速 / 音效 / 播放列表」纯文字功能键（`mkText`）。去黑边=适配↔填充裁剪切换；倍速改单选面板（原循环点击）；音效=播放器音量增益 100/150/200%（`IjkHolder.setVolume`，不影响系统音量）；播放列表=pickEpisode。清晰度收进「⋯」。
  - onPause：画中画模式不暂停（`isInPictureInPictureMode` API24+ 判断）。
  - 回归 23/23；产物同步下载页。
## v2.4（源分层 + JS 源引擎 + 红果短剧）
- 源管理三层：源管理(设置入口) → 源分类(NAS源/资源站/短剧源) → 源设置(点击行展开)；NAS 的服务器地址/账号密码/签名密钥全部收进源设置。
- 飞牛不再内置：listSources 时自动迁移旧 base 配置为一个 NAS 源实例（ensureSourceSeed）。
- JsSource.java：Rhino 1.7.14 精简引擎（registerSource + host.getString/getJson/getBytes/cache + 全局 items/int/str/float）。★ Rhino 的 NativeJSON.parse(cx,scope,json,null) 传 null reviver 会 NPE → 必须用 cx.evaluateString("("+json+")")。
- 源脚本用「阅读/prelude」格式；红果短剧源（assets/sources/hongguo.js）预置，自动接入短剧栏（categoryMenus→榜单tab、categoryPage→列表、bookDetail→剧集、video→360p mp4 直链，已桌面 Rhino 全链路实测）。
- 源导入：importSource(text) 支持 URL（PK 头 zip → manifest.json+scripts）或直接粘贴脚本。
- 短剧页去掉点赞/分享，只留播放；guid 格式 jssrc|<源id>|<bookUrl>。
- build.sh 加 RHINO_JAR 编译 + unzip class 进 dex；v2.4=versionCode 15。
## v2.5（修红果解析 + 本地/TVBox 导入 + 主页源下拉 + 亮暗主题）
- ★ 根因教训：JsSource.call 里 `new JSONTokener(s)` 不解析，必须 `.nextValue()`——上一版红果源静默返回空 tabs（不报错），桌面 HGTest 没走这条转换所以没测出来。测试必须覆盖"JS→org.json"转换层。
- 本地导入：MainActivity.pickImportFile（ACTION_OPEN_DOCUMENT→GET_CONTENT 降级）→ onActivityResult → bridge.handleImportBytes（PK=zip 源包 / registerSource=JS / { =TVBox 自动识别）→ onImportDone 回调。
- TVBox 解析（parseTvbox）：仓库形态 {urls} → 弹层选择；配置形态 sites(type=1→CMS 资源站,0/3/4 跳过计数)+lives→直播源；stripJsonComments 单趟处理 //与块注释+尾逗号（不破坏字符串，已真数据验证）；fetchTextFollow 手动跟跨协议重定向（resp.location）。
- 主页左上角源下拉（srcsel，仅 nas/cms）；播放页边距 18→6、文字键 20→14 使按钮贴边；亮暗主题：style.css @media(prefers-color-scheme:light) 变量覆盖 + MainActivity 按 uiMode 设 WebView 底色；v2.5=versionCode 16。
## v2.6（顶栏改版 + 直播 txt 格式 + TVBox 直接入分类 + 字幕名称兜底）
- 顶栏：标题绝对居中（.navtitle position:absolute+translate）；源选择器移进顶栏（#homeSrcsel，仅首页 display），返回键 back() 先关下拉菜单。
- 直播：fetchLive 同步桥调用会堵死 → loadLiveAsync 异步回调 onLiveText；解析支持双格式（m3u #EXTINF / TVBox txt `分组,#genre#`+`频道,url`，多备选 # 分隔取第一个）；分组渲染限量 300。
- TVBox：parseTvboxAsync 异步，仓库形态自动逐个解析（≤10 份）直接进分类，结果 onTvboxDone；parseSitesAndLives 提取为公共方法（源 id 用 SRC_SEQ 原子序列防撞）。★ parseSitesAndLives 里 src.put 抛 JSONException，方法要声明 throws。
- 字幕：m3u8/HLS 流无整文件特征 → 直接名称匹配；hash 失败不再笼统报错（lastHashError 具体化：HEAD 403 容错、Range 回退、文件太小提示）；名称兜底用 SubtitleCat（search → subs/*.html 详情页 → 优先 -zh-CN.srt/-zh/-chs/-cht/-en），实测全链路 200。v2.6=versionCode 17。
## v2.7（box 源根因 + 提速 + 三处下拉 + 去红果内置）
- ★ box 源读不了的根因：Utils.normalizeBase 只留 scheme://host:port，把 CMS 接口 /api.php/provide/vod 路径剥掉 → ac=list 打到网站首页。新增 Utils.normalizeApi（保路径，只补协议去尾斜杠）；addSource/updateSource/parseSitesAndLives 对 cms 类型改用 normalizeApi。已存的旧 cms 源 api 已被剥坏，需要用户在源设置里重贴接口地址。
- ★ 「所有源加载异常缓慢」根因：每次 new FnClient() 都重跑 probe() 路径矩阵（最多 6 请求×6s）且 io 是单线程池排队。FnClient.shared() 按 连接参数 缓存实例（SHARED_MAX=8 清空防膨胀），selectSource/login/clear 时 forgetShared()。
- 下拉统一组件：fillSrcMenu/bindSrcDropdown/closeAllSrcMenus；首页顶栏、源管理「当前源」、直播页三处；.srcsel-menu 改 position:fixed top:58px 居中弹层（防溢出）；document click 空白收起；返回键先关菜单。
- 顶栏去 navStatus「未连接」字样；直播页顶部加直播源下拉（renderLives 填充，点选即 loadLiveUrl）。
- 直播解析增强：BOM 去除、全角逗号→半角、txt `分组,#genre#` + `频道,url`（多备选 # 取第一）。
- 红果内置已删：assets/sources/hongguo.js 移除，ensureSourceSeed 改为清除旧 s_hongguo 记录；短剧空态文案不再提域名。
- 前端提示/占位符中具体域名全部去除。v2.7=versionCode 18。
## v2.8（直播/红果播放根因 + TVBox 选线路 + 直播源管理进下拉）
- ★★ 播放根因一：libijkffmpeg.so（Bilibili 官方 CI 编译，--disable 协议里无 openssl）**不支持 https**——红果直链、多数直播/资源站 https 流全报 what=-10000 extra=0，而 NAS 是 http 所以一直正常。so 里 strings 可查证："https protocol not found, recompile FFmpeg with openssl…"。修复：**https 直链一律走 LocalProxy.serve()**（Java 层 https 拉流，ijkplayer 只碰 http://127.0.0.1）；ShortActivity.startPlayer 与 VideoActivity direct 分支都处理。桌面实测：MP4 Range 206 透传、m3u8 重写 /seg?u=（含二级 playlist）全通。
- ★★ 播放根因二：Bridge.playDirect putExtra("direct",url) 而 VideoActivity 只读 "guid" → 直播点播放永远「缺少影片 ID」直接 finish。修复：playDirect 改 putExtra("guid","direct:"+url) + live 标记。跨 Activity extra 键名必须在接收端 grep 验证。
- 直播源质量：央视频源（mpimg.cn .txt→m3u）频道是 video://https://www.yangshipin.cn/... **网页链接不是流**，永远不可播 → parseLive m3u 频道行只收 ^(https?|rtmps?|rtsps?|udp|rtp)://，其余丢弃；renderChannels 点击对非流地址 toast 提示。m3u 行也去 $ 扩展参数。
- 直播播放器优化：IjkHolder.setLiveMode()（probesize/analyzeduration 500000、fflags nobuffer、infbuf、packet-buffering 0），VideoActivity startPlayer 加 live 参数。
- LocalProxy playlist 判定增强：URL 含 .m3u8 之外再看响应 Content-Type（mpegurl/m3u）——直播源 m3u8 常带 query 无后缀。
- TVBox 仓库改「选择线路」：parseTvboxAsync 遇仓库不再自动解析，存为 type="box" 源（lines 字段=[{name,url}]）→ onTvboxDone type=repo → 前端直接弹「选择线路」弹层（#linePickOvl，.ovl 复用）；selectBoxLine(id,url,name) 下载线路→parseSitesAndLives（新增 newIds 收集）→线路名记回 selectedLine→自动 selectSource(第一个新站) 对接首页；直播源进直播下拉。源分类新增 box「TVBox 源」（不可手填，只走导入；点击行=弹线路选择）。
- 直播源管理（列表/删除/添加表单）全部收进 liveSrcMenu 下拉（行内 × 删除 + 底部内嵌表单），直播页删掉底部管理卡片。renderLives 只管下拉。
- ShortActivity：onError 先软解重试一次（softTried，playAt 重走）再报错；startPlayer 加 https 代理与 useSoftDecoder 保持。
- 饭太硬.net 对桌面 curl 全 UA 返回 JPEG 反爬（真机 HttpURLConnection 正常，勿用桌面 curl 判死刑）。v2.8=versionCode 19。
## v2.9（中文域名线路 + 短剧竖滑/比例/进度条/分页 + 去诊断）
- ★ TVBox 线路解析失败根因：线路 URL 大量中文域名（qiqiv 14 条里 5 条：饭太硬.net/肥猫.net 等），Java 不做 IDN——且坑有两层：① new URI(中文域名) 不抛异常但 getHost() 返回 null；② 必须手动正则拆 scheme/host/port/path 后 IDN.toASCII + 路径中文逐段 URLEncoder。修复后 punycode 实连 code=200（Bridge.idnUrl，fetchTextFollow 每跳都转）。
- ★ ShortActivity「上下滑动无效」根因：root.setOnTouchListener 的 onTouch 返回 gd.onTouchEvent(e)，SimpleOnGestureListener.onDown 默认 false → DOWN 未消费 → MOVE/UP 不再派发，Fling 收不到。修：onDown 返回 true + root.setClickable(true) + onTouch 恒 return true，fling 阈值 90dp→60dp。
- 短剧比例：TextureView 铺满会拉伸；onVideoSize 保存 w/h，fitVideo() 按 fit 居中重设 LayoutParams。
- 短剧进度条：SeekBar+时间（Handler 500ms tick），IjkHolder 已有 curMs/durMs/seekTo。
- ★ 红果「只能播前几集」= 官网付费墙：vid_list 全量（102 集）但 /video/public/<vid>/360p.mp4 只有免费试看集（该剧前 3 集）206、第 4+ 集 404，付费集播放页与 API 均无直链，无法绕过。修法：JsSource.video 取链后 Net.getBytes(1KB) probeCode，>=400 直接提示「第 X 集无地址（付费内容）」，不再等 ijk 报 -10000。
- 短剧分页：红果榜单每榜只有 9 部；/category/real-drama?page=N 每页 24 部 total 800/34 页，hydration JSON 里 series_id/series_name/series_cover 三数组顺序一致可按序配对。hongguo.js v2：categoryMenus 加 6 个分类 tab（http URL），categoryPage 对 http URL 直接 getString 解析。Bridge.loadShortPage(tabUrl,page)：page>1 且 http 时拼 ?page=/&page=；回调 onShortPage 追加。前端 .content 滚动到底 400px 内自动 loadShortPage。
- 连接诊断整块移除（用户确认失效）；「内嵌网页浏览」保留，入口移到设置页 srDiag。
- 播放器刘海避让：左右从「完整圆角半径」减为 corner/3（≈8~16px），顶/底保持 cutout+圆角。
- 下拉菜单再防溢出：.srcsel-menu max-height:62vh + overflow-y:auto。
- ★ 发布平台静态站拦 zip（403 HTML）——附件下载一律用 apk/js 等常规后缀。
- v2.9=versionCode 20。新源脚本 download-site/hongguo-v2.js（assets/sources/hongguo.js 已同步）。
## v2.10（下拉样式修断 + 选线路异步化 + 短剧闪退）
- ★ v2.9 编辑 style.css 时把 .srcsel-menu 规则提前闭合，background/border/圆角声明变成游离碎片 → 菜单无底色看不清 + 解析错乱。教训：Edit 换 CSS 规则时 old/new 都要含完整花括号，改完用 grep 检查大括号配对。
- 下拉菜单定稿：position:fixed top:58px left:12px width:min(320px,calc(100vw-60px))（与按钮左对齐、限宽），sm-n/sm-d 单行 ellipsis（长地址不再撑爆）。
- ★ selectBoxLine 同步网络跑在 WebView JavaBridge 线程 = 「多次操作选源卡死」根因（20s 超时期间全部 TS.call 排队、toast 不弹）。改 io.execute 异步 + window.onBoxLineDone 回调；回调里统一刷新 renderHomeSrc/renderLives/renderSrcCat/loadHome，返回 firstLive（lives 最后一个 id）直播页自动选中加载。所有 JavascriptInterface 方法禁止同步网络。
- ★ ShortActivity 闪退根因：v2.9 buildBottomBar 里 ui.post(tick)，但 ui 在 onCreate 手势段才初始化（更晚）→ NPE。Handler 初始化必须在任何使用之前（onCreate 开头）。
- v2.10=versionCode 21。
## v2.11（顶栏源选择器贴左上 + 爬虫源列出 + 直播下拉根因 + 主题设置）
- ★ 顶栏源按钮位置根因：.srcsel{margin:2px 2px 10px} 定义在 .navsrc{margin-left:auto} 之后，同优先级下 margin 简写覆盖 margin-left:auto → 按钮挤在标题旁。修：.nav .navsrc{margin:0 auto 0 0}（高优先级、贴左）+ pill max-width:30vw 省略。教训：同类 class 基础规则放区块前面，覆盖规则用复合选择器提优先级。
- ★ 直播下拉不显示直播源根因：boot 里 bindSrcDropdown('liveSrcBtn','liveSrcMenu') 复用了影视源填充 fillSrcMenu——liveSrcMenu 永远被填成影视源列表。修：bindSrcDropdown 加第 4 参 fillFn，live 传 renderLives。
- TVBox 爬虫源（type=3，api=http jar）不再跳过：存 type="spider"（cat 资源站），首页下拉灰显+「（爬虫）」后缀+点击 toast、selectSource 拒绝、源管理新增 spider 分类（无编辑器，点击提示）；selectBoxLine 的 newIds 只收 cms（自动选中不会选到 spider）。cms/spider 按 name+api+type 去重（重复解析同一线路不再堆积）。注意 new URI(中文域名) getHost() 返回 null 的坑已在 idnUrl 处理。
- 主题模式（设置→通用）：Config.theme（auto/light/dark）→ saveConfig 有 theme 字段时 setTheme+applyWebBg；前端 readCfg 时 applyTheme 写 <html data-theme>；CSS 三态：默认深色、@media light :root:not([data-theme=dark])、:root[data-theme=light]（组件字面色两份选择器）。MainActivity.applyWebBg() 同步 WebView 底色。
- parseLive 非流频道（video:// 网页链接）丢弃计数，全无流时提示具体原因。
- ★ Bridge.getConfig 的 version 字段 v2.10 时忘了同步（停在 2.9）——版本号四处同步：Manifest/Bridge getConfig/build.sh/关于页。
- v2.11=versionCode 22。
## v2.12（源选择器出顶栏 + 爬虫源全格式 + cms 短剧对接 + 直播图标）
- ★ 顶栏源按钮位置（用户三次反馈）：pill 在 .nav 内无论靠左靠右都会和绝对居中标题挤压（长源名 + 标题 42% 区间重叠）。根治：homeSrcsel 整体移出 header，放 pageHome 内容区第一行（.srcsel 内容区样式本来就是为它设计的），.nav 只留居中标题。删掉 v2.11 的 .nav .navsrc 覆盖规则。
- ★ TVBox「无效源」：小米线路 43 个站 api 是 csp_类名（非 http）没进 spider 收录（v2.11 条件 api.startsWith("http")）。放宽：type==3 或 api 非空 → spider 收档，jar 地址取站点 jar 字段或顶层 spider 字段；只有无 name/api 的才 skipped。
- cms 短剧源自动对接：findShortCmsSource() 找 name 含「短剧」的 cms 源（不限当前源）→ new CmsClient(api,trustSsl) 读「短剧」分类，fallback 里 items(tid,page) 支持分页。★ 顺带修了个老 bug：loadShortListFallback 里 r = c.items(...) 参数重赋值不影响调用方 r → 回调空；改成逐字段复制 r.ok/error/list。
- 直播源 icon：parseSitesAndLives lives JSONObject 的 icon 字段存档；renderLives 菜单项只显示名称（隐藏 url），有 icon 用 .sm-ico 图，无则 .sm-badge 首字徽标；频道列表 renderChannels 支持 m3u tvg-logo（.ch-ico/.ch-badge）。
- v2.12=versionCode 23。
## v2.13（源选择居中弹窗 + 内置 spider 运行时 + 解析容错）
- ★ 源选择 UI 定稿（用户第四次反馈位置问题，直接参考成熟 App 改交互）：删掉所有 .srcsel-menu 下拉，首页/源管理按钮点开 #srcPickOvl 居中弹窗（标题「请选择首页数据源」+ 搜索框 #srcPickSearch + .pk-btn 全宽按钮列表、当前源主题色高亮）；直播页 #livePickOvl 同款（pk-row 图标行 + 删除 + 底部添加表单常驻）。back() 优先关 .ovl:not(.hide)。
- ★★ 内置 TVBox spider 运行时：新建 com.github.catvod.crawler.Spider 桩（同包路径抽象类，方法签名对齐 FongMi/takagen99：homeContent/categoryContent(tid,pg,filter,HashMap)/detailContent(List)/playerContent(flag,id,List)）+ spider/SpiderRuntime（DexClassLoader 按 jar 下载缓存到 cacheDir/spider/，csp_X → com.github.catvod.spider.X 兜底原始类名；反射 Init.init(Context)；子类覆写了哪个 init 就调哪个）。Bridge 首页四接口加 spider 分支：homeContent 的 class[] 当分类、categoryContent 当列表、detailContent 当详情；parseSpiderEpisodes 把 vod_play_url 多组播放源全展开（$$$ 组 # 集 $ 名），http 直链 guid=direct:url、非直链 guid=spp:urlencode(flag$id)。VideoActivity 加 spp: 分支：playerContent → parse=1 时 SpiderRuntime.sniff() 请求解析页正则嗅探 m3u8/mp4 → header JSON 转 Map 传播放器/代理/探测。selectSource 不再拒绝 spider。
- ★ 解析容错：fetchTextFollow 改多 UA 依次重试（Net.UA → okhttp/4.9.3 → Chrome Mobile；内容无 { 换 UA 再试）+ Net.getBytes 后 UTF-8/GBK 嗅探（U+FFFD 计数比较）；extractJsonText 截取第一个 { 到最后收尾符（★ 初版 Math.min 逻辑写反，数组 ] 会截掉结尾 }，必须 e=max(e,e2)，桌面 T.java 先验证再进 Bridge）；parseTvbox/parseTvboxAsync/selectBoxLine 下载后统一过 extractJsonText。
- ★ parseSitesAndLives 加 cfgUrl 参数：type=1 的相对 api（"api.php/provide/vod"、"/api.php"、".//x"）用 completeRelative(基于配置 URL) 补全；未标 type 但 api 像 CMS（CmsClient.looksLikeCms）也收 cms；剩余非空 api 归 spider。
- 桌面验证：/tmp/fxtest/T.java（completeRelative 5 例 + extractJsonText 2 例）；curl 验证下载站 index/apk 200。饭太硬等反爬站对桌面 curl 返回 JPEG 假响应的坑依旧，真机为准。
- 发布纪律执行：download-site/{fengxi-video-v2.13.apk,index.html 同步 v2.13 更新说明}，发布为应用覆盖同链接 verified。v2.13=versionCode 24。
