# 枫兮项目 · 全量进度纪要

> 人工整理，脱敏。截至 2026-11-12。用于跨设备/跨会话快速续上上下文。

## 一、项目构成

| 模块 | 路径 | 当前版本 | 说明 |
|---|---|---|---|
| 枫兮听书 App | `fengxi-tingshu/` | **v4.21.42 / code 234** | Android 听书框架，Java+WebView，多类型书源 |
| 枫兮影视 App | `fengxi-tingshu/fnplayer/` | **v2.25 / code 36** | TVBox/EasyBox 兼容壳 + 车机化；零内置源；修闪退与源解析；本机工具链构建并已发布 NAS |
| 下载站 | `fx-video-download/` | manifest 驱动 | 自动同步主页 + NAS 锚点轮询 |
| NAS 锚点 | `fx-nas-anchor*/` | v2 | 轮询 manifest 的 md5 变化自动拉新版 |

## 二、影视 App（v2.17 → v2.22）

- **v2.17~v2.21**：重析采集接口，深扒 EasyBox 补全兼容链路——`;md5;` 后缀、AES/hex/base64 解码、图片藏配置、Guard 加密壳 `DexNative`/so、okhttp UA、并行聚合搜索、CrashGuard、ijkplayer 软解重试；修短剧/首页/闪退/搜索/布局/播放失败。
- **v2.22 车机化**（配合深蓝 S05 的 Deepal+ Xposed 桌面模块）：
  1. 竖屏沉浸短剧流：爬虫源卡片统一进 ShortActivity，EpCache 预取下一集零等待，控件渐隐/细进度条/集数气泡；
  2. 悬浮小窗：FloatVideoService + PlaySession 同进程换绑 Surface 无缝移交、拖动吸附、双击回全屏续播；
  3. 车机卡片首页：car.css 按浮窗宽度自适应单/双列大卡、热区 ≥48px。
- **v2.23（code 34，电脑端主开发环境）**：以 EasyBox 竖屏壳为样本重新核查排版与功能；修冷启动白屏、两处同步网络桥、版本号/下载页不同步、弹层残留、海报列数、车机 CSS 死规则；补「我的」聚合入口、空态直达添加订阅、选集正序/倒序；构建脚本参数化适配本机工具链并出签名包（5.9MB，原证书可直接覆盖安装）。详见 `fnplayer/download-site/index.html` 的 v2.23 更新说明与当日工作日志。
- **v2.24（code 35，已发布）**：以 EasyBox 官方 APK 明文前端 + 同作者开源 `enjoy-player` 工程为实证复核，确认 v2.23 排版方向正确、不推倒重来；修「添加源后返回首页仍卡在空态」（`show()` 补加载不再要求空态处于隐藏、`onAddSourceDone` 在首页时立即刷新）；构建时 staging 只含 `www/` 的 assets，**把内置源脚本彻底移出 APK（零内置源）**；版本号四处同步 + 影视下载页更新。出包 6,141,325 B / MD5 `2b2e775b…`，原证书可直接覆盖安装。走 `publish.sh --apk` 推 NAS：清单/页面/APK 三份回读 MD5 一致，两个卡片首页与直链 200，端到端 MD5 一致，`--stable 70` 复查通过；NAS 自动删掉 `fengxi-video-v2.20.apk`，影视留 2.21/2.22/2.24 三版；外网反代首页与清单均已含 v2.24。
- **v2.25（code 36，已发布）**：真机反馈「闪退 + 很多源解析不了」。按 GitHub 现成经验（`FongMi-TV` 官方示例、`TVBoxOS` 配置文档、`tvbox-n2n` 稳定性修复说明）定位：① 闪退主因是 Guard 加密壳 jar 自带 so 被 `System.load` 预加载，ABI 不匹配直接 SIGSEGV，Java catch/Throwable 与 CrashGuard 都拦不住 → 改为**明确拒绝 Guard 壳、不加载任何第三方 native 库**（这类壳本来就需要专有解密环境，不兼容），并让 `listSources()` 只返回摘要（剔 `script`/`ext`/`jar`，IMG+ base64 可达 MB 级），避免大配置把 WebView 撑爆。② 解析失败四个真 bug：纯 type=3 爬虫线路被 `added==0 && liveAdded==0` 误判失败并整体回滚；顶层 `spider: "spider.jar"` / `file://one.json` 相对资源没补全（`base` 只取了 host 根）；type=0 XML 采集站被当成无 jar 的死爬虫源且 `CmsClient` 只会解 JSON；`sliceJson` 用 `lastIndexOf('}')` 把尾部统计脚本一起截进来。分别修为：成功条件计入 `spiderAdded`、保留配置完整 URL 用 `resolveCfgPath` 补全并按需下载 ext、新增 `XmlCms` 把 XML 转成 CMS 等价 JSON、括号平衡扫描截取。③ 另加 `CrashGuard` 崩溃日志落盘 + 「我的 → 崩溃日志」一键复制，主线程崩溃交回系统前也先落盘；探活改独立 daemon 线程池，避免与主 io 池互等。
- **v2.25 验证与发布**：本地用从 Bridge.java 真实源码抽取的 5 个方法 + `XmlCms` 跑 JDK17 单元测试 17/17 通过（XML 分类/选集/实体解码、尾部脚本截取、注释与尾逗号清洗、相对路径补全）；`node --check` 前端 JS 通过；APK 6,141,325 B / MD5 `0034b87e…` / aapt2 code36·2.25 / 原证书 `62668e0d9333…` / 20 条目零重复 / assets/sources 为空 / www 六件套 + 3 个 ijk so。`publish.sh --apk … --stable 70` 全绿：清单+页面+APK 回读 MD5 一致、两个直链 200、端到端 MD5 一致、70 秒复查通过；NAS 删 `fengxi-video-v2.21.apk`，影视留 2.22/2.24/2.25。**未真机测试**（本机无 adb 设备），待用户装包反馈；若仍闪退可复制崩溃日志定位。
- 结论：抖音内容是字节自家账号体系，第三方无法合法替代；Deepal+ 只是桌面壳，不做修改。

## 三、听书 App（v4.21.35 → v4.21.42）

| 版本 | 关键改动 |
|---|---|
| v4.21.36~37 | 自动更新：绑定 HTTPS 反代同步站、比对 manifest、自动下载校验安装；多路径兜底；UI 与日志零回显域名 |
| v4.21.38 | jar 源排障：Adapter 不再吞异常、取链失败不再把网页地址当音频直链（根因实为站点 WAF/机房 IP 限制） |
| v4.21.39 | 输入网址自动生成书源：SourceProbe 判别站点类型、探测搜索表单、章节聚类、音频直链正则、无头渲染、诊断日志 |
| v4.21.40 | 批量验证「自动整理重复源」：失效自动关闭留好源、都好的归入「重复源」分组，不再直接删除 |
| v4.21.41 | 修两 bug：① 插件源删除后被 reload 无条件装回（改按 sourceIds 白名单过滤）；② 预存 50 集不落盘（越界假缓存/无限流/无熔断，全部修复） |
| **v4.21.42** | 修分类翻页被判「重复内容」提前截断：JS 插件页码藏在分类 URL 尾部 `#N`，宿主一直回传第一页地址；改为优先续 nextUrl 链、其次重写 `#N` |

## 四、下载站 / 自动同步链路

- `manifest.json` 是页面内容唯一来源；`sync.py` 构建后刷新 version/versionCode/md5/size；
- NAS 锚点 v2 轮询 manifest，**发现 md5 变化才自动拉新版**并生成主页；
- 故障案例：v4.21.42 曾只改代码未跑发布收尾，manifest md5 未变 → NAS 侧一直显示旧版；拷贝 APK + 跑 sync.py 后恢复。

## 五、WorkBuddy 使用咨询（结论留存）

- 手机连家里电脑：官方「多端协同（连接电脑）」，无需同 WiFi/内网穿透；电脑需常开 + 允许锁屏运行。
- 记忆分层：云端画像跟账号自动共享；本地 MEMORY.md/技能/产物留在具体电脑；手机连电脑模式天然共用同一份。
- 常开主机选型：现成 Windows 直接用；新配选 Windows 小主机或 Mac mini；通用 Linux 无官方桌面端。

## 六、待办 / 注意

- 车机化三项需上车实测（悬浮窗权限、竖滑无黑屏、双击回全屏续播）。
- 自动源生成对需登录/强签名/纯 JS 动态渲染站不保证成功。
- 云端记忆每晚整理一次，非实时；关闭≠清空。

## 七、2026-11-12 会话进度同步到电脑端 WorkBuddy

- 新增 `_backup/docs/会话进度快照-20261112.md`：面向电脑端新会话的「粘贴即用」交接卡，含本次会话全部结论、版本矩阵、关键文件速查、待办、保密边界、电脑端接续步骤。
- 根目录 `MEMORY.md` 顶部新增「⚡ 最新进度指针」章节，把长期停更的 v4.21.8 / v2.13 显式标为历史里程碑，校正为 v4.21.42 / v2.22；`_backup/docs/MEMORY.md` 与之保持一致。
- 顺带消除主 `MEMORY.md` 中残留的明文域名 / sharecode / NAS 地址 / IPv6，统一以归档脱敏基线为准（PRIVATE 仓库也不留明文）。
- 同步通道：GitHub PRIVATE `fengxitingshu/fengxi-tingshu` 的 `main`；电脑端 WorkBuddy `git pull` 后打开项目目录，WorkBuddy 会读到根目录 `MEMORY.md`，再按指针打开快照即可续上。
- **边界说明**：当前会话运行在云端沙箱，无法直接迁移为电脑端会话；可靠桥梁是「MEMORY.md + PRIVATE 仓库」。若电脑端是家里常开主机，手机用官方「连接电脑」连过去后天然共用同一份本地上下文。
