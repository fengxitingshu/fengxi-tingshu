# v4.13.0「个人音频库」—— WebDAV + SMB 双协议

> 目标：让 App 能播**你自己的音频**（NAS / 家庭存储 / 自建服务）。
> 这是唯一不可替代、零版权风险的工具价值阵地——别人也能听播客和公版书，
> 但"你 NAS 里那堆音频只有这个 App 最好播"，这才是护城河。

---

## 一、好消息：基础设施已就绪（不需要重造）

本轮不是从零写协议，而是**把已有能力接到"音频源"上**：

| 已有资产 | 位置 | 状态 |
|---|---|---|
| **WebDAV 协议层**（PROPFIND / Basic+Digest / 自签证书 / IPv6 / 零第三方依赖） | `src/com/tingshu/app/data/WebDavClient.java`（810 行，v3.8.0 起） | ✅ 已在跑，现服务于「备份与恢复」 |
| **本地播放代理**（上游请求头透传 + Range 206 + 保留扩展名） | `src/com/tingshu/app/core/LocalProxy.java` | ✅ 已在跑，现作网盘播放兜底 |
| **源 SPI**（id/categories/browse/detail/audioUrl/audioHeaders） | `Source.java` / `BrowsableSource.java` / `AbsSource.java` | ✅ 参考实现 `PodcastSource`（v4.11.0） |
| **浏览桥**（第二参天然支持任意路径 → 逐级钻取） | `Bridge.java:1561 browse(sourceId, path, page)`；前端 `TS.call('browse', id, cat, page)` @ app.js:472 | ✅ 无需改签名 |
| **凭据存储**（备份页 WebDAV 配置） | `data/BackupCred.java` + `Bridge.davSave/davConfig/davTest` | ✅ 可一键带入 |

关键技术点已被前人踩平并写进注释：
- `HttpURLConnection` 拒绝 `PROPFIND`（方法白名单）→ 已用反射改 `method` 字段破解
- 坚果云双斜杠 410 → 已实现 `encodeDavUrl` 逐段 RFC3986 编码
- 自签证书 → 已由 `Settings.davTrustAllSsl()` 开关受控（默认严格）

---

## 二、架构：一套 UI + 一条播放链路，两种协议

```
[WebdavSource] ─┐
                ├─→ Source SPI ─→ Aggregator ─→ PlayerCore ─→ LocalProxy ─→ 播放器
[SmbSource]    ─┘                                   ↑
                                        统一本地代理（Range + 认证在此隔离）
```

**为什么必须走"标准 Source SPI"而不是单独做个文件浏览器**：
走 SPI 就能自动白嫖全链路能力——书架收藏、**断点续听**、章节列表（含 v4.12 刚做的续听高亮 + 长按跳集）、
播放模式/连播开关、定时关闭 + 悬浮球角标、下载、桌面小组件、首页「最近在听」书架。
单做浏览器这些全得重写。

**播放为什么必须走 LocalProxy**（而非直链 + header）：
- 认证（Basic/将来的 Digest 质询）在 Java 层解决，播放器只连 `127.0.0.1` 明文
- 自签名 HTTPS 可受控信任；IPv6 `[::1]:port` 格式由我们处理
- 已有 Range 206 透传 → **拖进度条正常**
- 保留原始扩展名 → WMA 仍能触发已有的 FFmpeg 转码链路（完整版才有）

---

## 三、本期 v4.13.0：WebDAV 个人音频库（零依赖，立即可用）

### 产品形态
- 每个服务器 = 首页一个源（如「我的 NAS」），可添加**多个**服务器/多条记录
- 进服务器 → 列出入口目录下的：
  - **子目录 = 一本书**（书名 = 目录名）→ 点进去是章节列表（该目录音频文件）
  - 本层有散装音频文件 → 聚合成「散装音频」这本书
- 想看更深的目录：把该服务器的入口路径配深一点即可（如 `/有声书/历史`）

### 代码改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `core/Upstream.java`（新建） | 上游抽象：`open(start,end)→InputStream`、`length()`、`name()`。为 SMB 预留接入位 |
| 2 | `core/LocalProxy.java` | 内部改为按 Upstream 处理（`serve(url,headers)` 行为完全不变）；① 加 HTTPS 自签信任（复用 `davTrustAllSsl()`，同 WebDavClient 那套 TrustManager）② **熔断计数与网盘兜底分离**（现状 `failures` 全局共享 ≥3 就作废，会误伤音频库） |
| 3 | `source/impl/WebdavSource.java`（新建） | 照抄 `PodcastSource` 模式：<br>`ID="webdav"`；服务器清单存 Settings `dav_libs_v1`（JSON: name/url/basePath/user/pass/auth/trustAll）<br>`categories()`：每服务器一个 Category + 「管理服务器」（前端拦截）<br>`browse(catId,page)`：catId 内嵌路径 → `WebDavClient.listWithErr` → 子目录=Book（`d:` 前缀）、散装音频=Book（`f:` 前缀）<br>`detail(bookUrl)`：PROPFIND → Chapter 列表，文件名智能排序（中文数字/补零，复用现有排序逻辑）<br>`audioUrl` = `joinUrl` + `encodeDavUrl`；`audioHeaders` = Basic 头 |
| 4 | `Bridge.java` | `webdavLibs()` / `webdavSave(json)`（内含 `smartTest` 验证，失败不入库）/ `webdavDel(id)` / `webdavImportBackup()`（从 `BackupCred` 一键带入） |
| 5 | `SourceRegistry.java` | `register(new WebdavSource())` 一行（**注意：必须写在 DEPS 数组定义之后**，吸取 v4.10.11 教训） |
| 6 | `assets/www/index.html` + `app.js` | 服务器管理面板（添加/编辑/删除/测试连接/从备份导入）；「管理服务器」分类拦截（照抄 CAT_MANAGE） |
| 7 | `player/PlayerCore.java` | 新增 `isWebdavSource()` → **起播就走代理**（而非等播失败再兜底） |

### 已知限制（会明确提示用户，不静默失败）
- **Digest 认证不支持播放**（静态 Authorization 头对 Digest 无效）：MVP 支持 **Basic / 无认证**（覆盖绝大多数 NAS、Alist、坚果云、Nextcloud）。检测到 Digest → 明确提示"建议改用 Basic 或 http"，并写入诊断日志。
- 后续可在 LocalProxy 内做一次 401 质询协商来支持 Digest。

---

## 四、下期 v4.13.1：SMB（同一轮紧随做，视验证结果决定同版或拆版）

### 为什么 SMB 必须引入 jar
手写 NTLM/SMB2 签名协议不现实（工作量大、各家 NAS 兼容性坑深）。选型已验证：

| 候选 | 许可证 | 体积 | 依赖 | 结论 |
|---|---|---|---|---|
| **smbj 0.15.0** | **Apache-2.0**（商业化最友好） | 667KB | slf4j-api + asn-one + mbassador + bcprov(runtime, 仅 SMB3 加密需要) | **首选** |
| jcifs-ng 2.1.9 | LGPL-2.1（需在「开源许可」页声明+附源码链接） | ~1MB | slf4j + bcprov + servlet-api | 备选（兼容 SMB1 老设备） |

Maven Central 与华为云镜像均已验证可达（HTTP 200）。

### 实施要点
1. **jar 集成走已有的 `DEPS+=(jar)` 通道**（与 mobile-ffmpeg 同路径，v4.10.11 修过那个坑）；bcprov 暂不引入，`-dontwarn` 规避，真机遇 SMB3 加密再补
2. `data/smb/SmbClient.java`：连接 / 列目录 / 读文件（用 `SmbRandomAccessFile` 的 seek → 天然支持 Range）
3. `source/impl/SmbSource.java`：与 WebdavSource 共用同一套服务器管理 UI，`bookId` 带协议前缀区分
4. 实现 `SmbUpstream` → 复用 LocalProxy 全部 Range/代理逻辑
5. 关于页「开源许可」补充 Apache-2.0 (smbj) 等声明

### 风险提示
**smbj 在 Android 上的运行时兼容性无法在沙箱验证**（无 Android 环境）。若集成后真机崩溃 → 退回 jcifs-ng。这就是 SMB 单独立的一条包段的原因。

---

## 五、风险登记表

| 风险 | 应对 |
|---|---|
| Digest 播放不了 | MVP 支持 Basic/无认证，明确提示不静默失败 |
| 自签 HTTPS | LocalProxy 加信任开关（默认严格 + 明确风险提示） |
| smbj Android 兼容性 | 拆独立发版，失败退 jcifs-ng |
| 中文/空格目录名 | 复用 `encodeDavUrl`（已含坚果云 410 修复） |
| IPv6 地址 | `http://[2001:db8::1]:5005/...` 方括号形式，Java 原生支持 |
| 大目录 PROPFIND 慢 | 沿用现有超时（连接 12s / 读 30s），失败明确报错 |
| R8 混淆吃掉新类/接口 | 照 mobile-ffmpeg 加 keep 规则，构建后 dexdump 验证 |

---

## 六、执行顺序

1. **Upstream 抽象 + LocalProxy 改造**（含自签信任 + 熔断隔离）
2. **WebdavSource + Bridge 接口 + SourceRegistry 注册**
3. **前端服务器管理面板**
4. **PlayerCore 接入起播走代理**
5. 版本 4.13.0/142 → CHANGELOG → 构建 → dexdump/apksigner 验证 → release 发布
6. **SMB**（jar 集成 → SmbClient → SmbSource → 同版或 v4.13.1）

## 七、验收方式
- 沙箱起一个本地 WebDAV 服务（Python），跑通 PROPFIND 列目录 + Range 拉取来验证协议侧正确性
- 构建后用 `dexdump` / `aapt2` / `apksigner` 确认新类、版本号、签名
- 交付后请真机验证：添加服务器 → 测试连接 → 看到书 → 播放 + 拖进度条 → 首页续听
