# 枫兮影视 v2.22 车机化改造（竖屏短剧流 + 悬浮小窗 + 卡片首页）

## Context

用户的车机是**深蓝 S05 + Deepal+ Xposed 桌面模块**（hook `com.deepalhome.launcher`）。视频里「卡片刷抖音/短剧」= Deepal+ 把字节官方「车鱼视听」以卡片/浮窗嵌入桌面。结论：**不改 Deepal+、不写新 App**，把现有 fnplayer 车机化，装进车机后在 Deepal+ 卡片模式/侧边栏中以浮窗运行。用户确认三项全做：①竖屏沉浸短剧流 ②悬浮小窗播放 ③卡片式首页（浮窗适配）。

工程：`/workspace/fengxi-tingshu/fnplayer/`，纯 Java 无 Gradle，`build.sh` 出 APK，targetSdk 28 / minSdk 21，ijkplayer + LocalProxy。版本升 **v2.22 / code 33**。

关键现状：
- `ShortActivity.java`（595 行）已是竖屏短剧页（竖屏锁 + TextureView + onFling 上下滑切集 + 自动连播 + 软解重试），但 spider 源卡片仍走横屏 VideoActivity，且串行取链滑动有黑屏等待。
- 悬浮窗代码为零，需新建（参考听书工程 `FloatBallService.java` 范式）。
- 前端海报卡片 `card()`（app.js L220-234）+ `.shelf` grid（style.css L65-83）可复用；主工程有 `car.css` 参考。

## 阶段 P1：竖屏沉浸短剧流

**文件：`src/com/fengxi/fnplayer/ShortActivity.java`**
- `loadEpisodes()` 加 `spid|` 分支：后台线程调 `Bridge.episodesFor(ctx, guid)`（Bridge.java L483，public static，复用不重写），集条目 guid 为 `direct:<url>`，现有 `playAt()` direct 分支天然支持。
- 抽 `resolveEpisode(int i, boolean forPrefetch, ResolveCb cb)`：统一 jssrc/direct/nas+cms 三分支「解析→probe→回调」；`player/EpCache.java`（或静态内部类）缓存 2 条已解析条目（`{url, headers, probedOk}`，键=集下标，INFLIGHT 单飞防抖），换剧 clear。
- `playAt(i)`：缓存命中→零等待起播；miss→加载态→resolveEpisode；成功后 `prefetchAsync(i+1)` 预取下一集（jssrc/direct 也做 probeCode 试看探测，防付费 404 卡死竖滑）。
- 沉浸化：`showOverlay(boolean)` 控件 180ms 渐隐 + 3s 自动隐藏；细进度条（2dp + 6dp thumb）；中央「第 x 集」气泡 1.2s 渐隐（不加点赞按钮）；首集加载态 = 居中 ProgressBar + 剧名。

**文件：`assets/www/app.js`**
- `shortCardList()` 点击分发（L1056-1067）：删除 `kind==='spider' → openVideo` 分支，统一 `TS.call('openShort', g, t)`。首页卡片分发不动。

## 阶段 P2：卡片式首页（车机浮窗适配）

**新建 `assets/www/car.css`**（不抄主工程 vh 等比方案——那是 1920×720 大屏；浮窗走断点）：
- `app.js` 新增 `applyCarMode()`：`innerWidth < 480` → `body.classList.add('car')`；`applyColumns()` 在 car 态忽略 `cfg.homeColumns`，按宽度 1 列（<420）/2 列裁决；resize 节流监听。MainActivity configChanges 已含 smallestScreenSize，浮窗 resize 不重建。
- car.css 断点：`<420px` 单列横排大卡（.bk-cv 固定 112px 宽、整卡 ≥72px 高、字号 16/12）；`420~479px` 双列小卡（.bk-cv 改 16:9，一屏 3 行）；`body.car` 深色对比强化（覆写 `--card/--line/--txt`）；`.chip/.tabitem/.navico` 热区 ≥48px，tabbar 56→62px；尺寸全用 fr/%/aspect-ratio 防破版。
- `index.html` head 里 style.css 后引入 car.css。

## 阶段 P3：悬浮小窗播放

**播放器移交：方案 A（同进程单例换绑 Surface，零黑屏）+ 方案 B（重取链+seekTo）兜底。**

**新建：**
- `player/PlaySession.java`：静态单例 `{IjkHolder holder, title, playUrl, headers, posMs, portrait, guid, item, epIdx, backActivity}`；`takeover()/clear()`；`holder != null` 表示已移交，Activity onDestroy 的 release 加 `!transferred` 守卫。
- `FloatVideoService.java`（照抄听书 `FloatBallService` 骨架：canDrawOverlays 检查、5s 内 startForeground、NotificationChannel、异常 stopSelf 不崩）：`TYPE_APPLICATION_OVERLAY`(26+)/`TYPE_PHONE`(21-25)，NOT_FOCUSABLE，尺寸 `portrait ? dp(170)×304 : dp(240)×135`（按视频宽高比微调），位置取 Config.floatX/floatY（新增持久化）。手势：单击暂停、双击回全屏（FLAG_ACTIVITY_NEW_TASK 重启对应 Activity + resumePos extra）、拖动 + 左右边缘吸附。TextureView SurfaceAvailable → `holder.setSurface()` 换绑；holder null 或 8s 超时 → 兜底重取链。

**修改：**
- `AndroidManifest.xml`：加 `SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`、`<service android:name=".FloatVideoService" android:exported="false"/>`。
- `player/IjkHolder.java`：加 `setSurface(Surface)`、`keepScreenOn(boolean)`（各 3 行）。
- `VideoActivity`/`ShortActivity`：顶栏加 ▣ 按钮 → `goFloat()`（未授权先 AlertDialog 引导；`PlaySession.takeover` → transferred=true → startService → finish）；`onPrepared` 读 `resumePos`/`resumeEp` 恢复进度；`onCreate` 若浮窗存活先 stopService（双播互斥）；VideoActivity 处于 PiP 时隐藏 ▣。
- `Bridge.java` 新增桥方法：`canDrawOverlays()`（同步 bool）、`openOverlaySettings()`、`openFloat(guid,title)`（2 参，未授权回 `window.onFloatPerm`）、`closeFloat()`、`isFloatShowing()`；Service start/stop 推 `window.onFloatState`。
- 前端：index.html 复用 `.ovl` 加悬浮窗权限引导浮层，app.js 实现 `onFloatPerm` + 「去授权」按钮。

## 阶段 P4：版本与发布

1. `AndroidManifest.xml` L4-5 → versionCode 33 / versionName 2.22；`build.sh` L23 `VER=2.22`；`Bridge.java` L78-79 兜底版本串同步。
2. `bash build.sh` → `枫兮影视-v2.22.apk`。
3. 拷 `/workspace/fx-video-download/fengxi-video-v2.22.apk` → `python3 sync.py` 刷 manifest.json → 手工更新 notes/features（竖屏短剧流+预加载、车机浮窗卡片首页、悬浮小窗）。
4. 回归：横屏 VideoActivity 主路径（取链/手势/PiP）、直播 playDirect、不触碰听书主工程 `/workspace/fengxi-tingshu/src`。

## 验证

- **构建**：每阶段 `bash build.sh` 确认编译零错误。
- **P1**：`adb shell am start -n com.fengxi.fnplayer/.ShortActivity --es guid "direct:<测试mp4>" --es title test` 验手势；导入 hongguo.js 全链路验证上滑无黑屏（Log.d("FxShort") 打点预取命中）；spider 源短剧 tab 点卡片进竖屏页。
- **P2**：Chrome DevTools 400/470/520px 宽验证 1 列/2 列/还原，拖窗验证 resize 自动切换。
- **P3**：`adb shell appops set com.fengxi.fnplayer SYSTEM_ALERT_WINDOW allow` 免授权后：播放中点 ▣ → 浮窗出现画面不断流（打点 transfer ok, pos=…）→ 双击回全屏 seek 恢复 ±2s；构造 holder=null 验证兜底重取链。
- **P4**：下载站 manifest.json 版本/MD5 正确，检测更新链路 200。
