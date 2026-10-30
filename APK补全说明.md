# 用成品 APK 补全代码 — 说明

> 背景：Git 仓库的代码停在 **v3.8.2（versionCode 48）**，而手上的成品 APK 是
> **v3.8.5（versionCode 51）**，中间差 v3.8.4、v3.8.5 两个版本（无 v3.8.3）。
> 本文记录如何用成品 APK 把仓库代码补回 3.8.5，以及哪些能补、哪些补不了。

成品 APK 信息（已归档到 `release/枫兮听书-v3.8.5.apk`）：

| 项 | 值 |
|----|----|
| 文件大小 | 19,154,732 字节（约 19 MB） |
| SHA-256 | `4faf34356fe3a85c00e943d0fb2fb8e83c1c9ff52a5b6425c4676e52084778a2` |
| 版本 | versionName 3.8.5 / versionCode 51 |
| 构建方式 | **R8 混淆构建**（类名被改成 `a/b/c`、`player.a~x` 等） |

校验：`sha256sum -c release/SHA256SUMS.txt`

---

## 一、先诊断：到底丢了什么

没有盲目覆盖，先做了三层比对：

1. **Java 层完整性**：仓库 `src/` 下 44 个文件、26208 行，括号全部配平，无截断痕迹——
   "丢失"不是源码损坏。
2. **前端层比对**：把 APK 里 `assets/www/` 的明文文件（app.js / index.html / style.css /
   bridge.js / reward/*.png）逐一与仓库做 md5 对比——前端三个主文件比成品旧约 4KB，
   停在 3.8.2。
3. **原生桥契约比对**（关键）：APK 是 R8 混淆构建，直接对比类名不可行（PlayerCore、Net
   等类在 APK 里都被改名）。但 `@JavascriptInterface` 的**方法名会被 R8 保留**（WebView
   靠反射按名调用）。于是提取 APK 全部桥方法名，与前端 `TS.call('xxx')` 的调用集合、
   仓库 `@JavascriptInterface` 的实现集合做三方对比，得出**唯一缺口**：

   ```
   前端调用有、成品 APK 有、仓库没有 → widgetDiagLog / widgetDiagClear
   ```

结论：丢失集中在**前端资源（3.8.4/3.8.5 两版的前端代码）+ 小组件诊断桥方法 + 一处倍速
bug 修复**，不是大面积丢失。

## 二、已补全的内容

### 1. 前端资源 → 直接提取，零风险（assets/www/）

APK 内前端是明文，直接取出覆盖：

| 文件 | 大小 | 补回内容 |
|------|------|----------|
| `app.js` | 356,369 B | 小组件诊断页（openWgDiag / wgDiagRefresh / wgDiagClear / wgDiagCopy / onWgDiagLog）、返回键折叠倍速/音量面板、`srcops-main` 窄屏排布、更新日志 3.8.5/3.8.4、`ver:'3.8.5'` |
| `index.html` | 38,421 B | 小组件诊断二级页 `#pageWgDiag`、版本号 3.8.5 |
| `style.css` | 109,670 B | `.srcops-main` 窄屏 CSS |

- `bridge.js`、`reward/*.png` 与成品 md5 完全一致，未动。
- **覆盖后把 P0 自愈钩子合回**（`window.onPlaying` 结尾，app.js 2771/2780 行）：
  `window.onSourceSwitched`（自动换源后同步播放源 + toast）和 `window.onPlayRetry`
  （网络重试提示），与 PlayerCore 自愈状态机配套。语法校验 `node --check` 通过。

### 2. 图片资源（res/）

- 补入 `res/drawable-nodpi/wg_preview.png`（45,024 B，3.8.4 新增的小组件预览图，
  仓库缺失、APK 有）。仓库 `res/src` 暂未引用它，先放回无害。

### 3. 原生层 → 按桥契约等价重写（src/）

**a) `Bridge.java` 补两个桥方法**（唯一原生缺口）：

```java
@JavascriptInterface public void widgetDiagLog()   // DiagLog.dump() → callback("onWgDiagLog",{log,empty})
@JavascriptInterface public void widgetDiagClear() // DiagLog.clear() → 回调空日志
```

基于仓库已有的 `core/DiagLog`（add/dump/size/clear，MAX 200 条）等价重写，全限定名
`com.tingshu.app.core.DiagLog`，行为与成品一致（读取/清空小组件诊断日志）。

**b) `PlayerWidgetProvider.java` 补诊断日志记录**：

- `onUpdate`：`DiagLog.add("[widget] onUpdate ids=" + ids.length)`
- `onReceive`：补生命周期记录（首个小组件添加/启用、移除一个、最后一个移除）

对应 3.8.5 更新说明"小组件不显示时，可复制诊断日志反馈"的能力。

**c) `PlayerService.java` 修倍速动作位 bug**（第 169 行）：

```java
a |= (1L << 30);   // PlaybackState.ACTION_SET_RATE（★ v3.8.4 修正：原来误写 1<<16）
```

`1<<16` 实际是 `ACTION_SET_CAPTIONING_ENABLED`，导致系统媒体控件（锁屏/通知栏/
iQOO 原子播放器）读不到倍速。这是 3.8.4 的真实修复。

三个改动文件均通过括号配平校验。

### 4. 版本与文档对齐

- `AndroidManifest.xml`：versionCode 48→51、versionName 3.8.2→3.8.5（对齐成品）。
- `README.md`：版本表原本滞后且是死链（写 v3.7.14 但 release/ 无此文件），改为真实
  存在的 v3.8.5（最新）/ v3.7.15 / v3.7.13。
- 新建 `更新说明/v3.8.4-更新说明.md`、`更新说明/v3.8.5-更新说明.md`（基于 app.js 内
  CHANGELOG 反推，验证数据标注"以本机为准"）。
- 成品 APK 归档进 `release/` 并登记 SHA-256。

## 三、补不了的部分（需在本机处理）

1. **通知栏品牌图标 `ic_brand_*`**：APK 内 res 的 xml 是编译后的二进制（AXT），
   无法还原明文矢量源；仓库 `res/drawable` 也没有。当前保持系统默认图标
   （`PlayerService.setSmallIcon`）。**需要从设计源导出 `res/drawable/ic_brand_*.xml`
   放入仓库并改 `setSmallIcon` 引用**，这是唯一确定的前端/资源缺口。
2. **混淆原生的逐行还原**：APK 为 R8 混淆构建，除桥方法名外无法可靠反推源码。
   已用"桥契约对比"证明仓库原生实现覆盖了成品全部 106 个前端调用（仓库实现 118 个，
   只多不缺），故不做无差别反编译。
3. **小组件诊断为等价重写**：`widgetDiagLog/Clear` 按现有 `DiagLog` 重写，行为一致，
   但**建议本机装 3.8.5 成品对比一次**：设置页 → 小组件诊断 → 刷新/复制/清空。

## 四、构建验证（本机执行，沙箱无 Android SDK）

```bash
# 开发构建（不混淆，便于对照）
OBFUSCATE=0 bash build.sh

# 发布构建（混淆，产物与成品同规格）
bash build.sh
```

构建后重点回归：

- [ ] 设置页 → 小组件诊断页能打开、能刷新/复制/清空日志
- [ ] 锁屏/通知栏媒体控件能显示当前倍速
- [ ] 播放失败自动换源 + 网络重试（P0-1/P0-2）仍生效（toast"已自动切换音源"/"网络波动，正在自动重试…"）
- [ ] 播放页返回键能折叠倍速/音量面板
- [ ] 窄屏（srcops-main）排布正常

> 版本路线：仓库现为 3.8.5/51，与成品 APK 对齐。下一步开发从 3.8.6 起。
