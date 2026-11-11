# 枫兮听书 · 播放体验打磨（v4.12.0）实施计划

## 背景与基线

用户已装 v4.11.0（公版书 + 播客源 + 合规三件套），确认可用。本轮目标：在已有扎实播放能力上做"体验打磨"，范围经用户确认包含 ① 自动连播开关 ② 播放模式选择器 ③ 章节列表增强 ④ 首页继续听增强 ⑤ 悬浮球显示定时状态。

**经只读摸排，以下能力已具备、不重做**：倍速 + 跨会话记忆（`speed_global` 持久化）、定时关闭（倒计时/时段/按集停止，`SleepTimer` + 桌面小组件显示剩余）、断点续听（`progress` 表 + 首页"继续收听" hero）、自动连播（但**不能关**）、单集循环（仅文案、未落地）、章节列表跳集、锁屏/通知/桌面小组件控制。

> 重要发现：`PlayerCore.autoNext`（第 77/190 行）是**死字段**，从未被读取或调用；真正的"播完是否下一集"决策在 `MainActivity.onEnded()`（856–877），由"章节表是否还有下一集"决定。`play_mode` 偏好后端已齐备（`Settings` 97–104 / `apply` 486 / `getSettings` 1848），但前端从未使用、onEnded 也未读取。改造只需补这两端。

## 改动总览

| 项 | 内容 | 主要文件 |
|----|------|----------|
| ① 自动连播开关 | 新增 `auto_next` 偏好 + onEnded 拦截 + 设置/播放页开关 | `Settings.java` `MainActivity.java` `Bridge.java` `app.js` `index.html` |
| ② 播放模式选择器 | 顺序/单曲循环/列表循环/随机，onEnded 按 `play_mode` 分支 | `MainActivity.java` `app.js` `index.html` |
| ③ 章节列表增强 | 续听位置高亮 + 长按从本集开始 | `app.js` `style.css` |
| ④ 首页继续听增强 | 单本 hero → 多本"最近在听"书架 | `app.js` `style.css` |
| ⑤ 悬浮球定时状态 | 悬浮球加定时剩余角标 | `index.html` `app.js` `style.css` |

版本：Manifest `versionCode 141` / `versionName 4.12.0`；CHANGELOG + 更新说明 + release 页 + 完整版 APK 构建。

---

## ① 自动连播开关

**后端**
- `Settings.java`：新增
  ```java
  public static boolean autoNext() { return SP.getBoolean("auto_next", true); }
  public static void setAutoNext(boolean v) { SP.edit().putBoolean("auto_next", v).apply(); }
  ```
  并在 `apply` 的相应段加白名单：`if ("auto_next".equals(key)) { setAutoNext(b(value)); return; }`
- `Bridge.getSettings()`（1834–1857）：补 `o.put("autoNext", Settings.autoNext());`（仿 1848 的 `playMode`）。
- `MainActivity.onEnded()`（856–877）：在"还有下一集 → `gotoChapter(cur+1)`"前，对**顺序模式**加拦截：
  ```java
  if (mode == 0 && !Settings.autoNext()) {
      // 顺序 + 关连播：停在最后一集的末尾（pause），不跳下一集
      core.pause(); syncWidget(); callJs("window.onEnded()"); return;
  }
  ```
  （具体分支见②统一改写 onEnded）
- **不动** `PlayerCore.autoNext` 死字段。

**前端**
- `index.html` 设置页：加一行开关（仿 `setWifiPlay` 写法）
  `<label class="sw"><input id="setAutoNext" type="checkbox" onchange="App.ui.setBool('auto_next',this.checked)">自动连播（播完自动下一集）</label>`
- `app.js` `window.onSettings`（8814）：读 `o.autoNext` → `App.autoNext` 并同步该 checkbox。
- 播放页"更多"菜单（`moreMenu`，约 5052 起）：加一行"自动连播：开/关"，点击切换并 toast。

## ② 播放模式选择器（顺序 / 单曲循环 / 列表循环 / 随机）

**后端**：`play_mode` 偏好已存在，无需新增存储。仅改 `MainActivity.onEnded()` 为统一分支：
```java
public void onEnded() {
    if (SleepTimer.onEpisodeEnd()) { core.pause(); return; }   // 按集停止到点
    List<Chapter> chs = core.chapters();
    int cur = core.index();
    int mode = Settings.playMode();
    if (chs != null && cur >= 0) {
        if (mode == 1) { gotoChapter(cur); return; }            // 单曲循环：重播本集
        boolean hasNext = (cur + 1 < chs.size());
        if (hasNext) {
            if (mode == 2) { gotoChapter(cur + 1); return; }    // 列表循环
            if (mode == 3) { gotoChapter(randNext(cur, chs.size())); return; } // 随机
            if (Settings.autoNext()) { gotoChapter(cur + 1); return; } // 顺序+连播
            core.pause(); syncWidget(); callJs("window.onEnded()"); return; // 顺序+关连播：停末尾
        }
        // 已是最后一集
        if (mode == 2) { gotoChapter(0); return; }              // 列表循环回第一集
        if (mode == 3) { gotoChapter(randNext(cur, chs.size())); return; }
        syncWidget(); callJs("window.onEnded()"); return;        // 顺序/单曲到尾：真播完
    }
    syncWidget(); callJs("window.onEnded()");
}
```
（`randNext` 取 ≠ cur 的随机下标；若只有 1 集则回退到自身。）

**前端**
- `index.html` 设置页：加一个下拉（仿 `setFf`）`<select id="setPlayMode" onchange="App.ui.setNumber('play_mode',this.value)">` 四个选项 0 顺序 / 1 单曲循环 / 2 列表循环 / 3 随机。
- `app.js` `window.onSettings`：读 `o.playMode` → `App.playMode` 并设该 select；加 `paintPlayMode()` 刷新播放页按钮文案。
- 播放页"更多"菜单：加一行"播放模式：顺序/单曲循环/列表循环/随机"，点击循环切换并 toast 当前模式（模仿倍速面板的"更多→点开"交互）。
- 删除/改写 `app.js:5104` 处"单集循环会重播本集"的孤立文案，改为指向新的播放模式。

## ③ 章节列表增强

**现状**：`chsListHtml`（4333）已用 `isListened(no)` 画绿色 ✓（数据来自 `progress` 表，自动派生，不手动改）。已听标记保留。

**新增**
- **续听位置高亮**：在 `chsListHtml` 中，若章节下标/集号等于当前书的续听位置（`App.book` 的 history `lastIndex`，复用详情页"续听 第N集"同一来源），给该 `.chitem` 加 `chresume` 类并追加"续听"小标签，区别于 ✓ 已听。需在打开书籍/进入章节列表时把 resume 下标存入 `App`（如 `App.resumeIdx`）。
- **长按从本集开始**：`.chitem` 增加 `ontouchstart/ontouchend` 长按判定（参照现有 `homeSrcPress` 长按模式），长按弹出 `ctxMenu`，提供"从这集开始收听"（调 `App.ui.pickChapter(i)`）与"取消"。不引入手动"标记已听"（那要写 `progress` 表，性价比低）。
- `style.css`：补 `.chitem.chresume`（左侧高亮条 / "续听"标签）与长按菜单样式。

## ④ 首页继续听增强

**现状**：`paintHomeContinue`（1755）只在 `#homeContinue` 渲染**单本** hero。

**增强**：改为"最近在听"多本书架——
- 调用已有 `TS.call('historyList', N)`（Bridge 1663 → `HistoryRepo` 取最近 N 本，带 cover/title/lastIndex/lastChapTitle）。
- 渲染最近 N（默认 4）本为 `bookCard`（1802 通用卡片），横向 shelf；每本点击 → `App.ui.openHistoryItem(key)` 续听。
- 仅 1 本时回退为现有单本 hero 样式，保持兼容。
- `style.css`：`.hero` 改为 `.shelf` 横滑行（复用现有 `bookcard` 网格样式）。

## ⑤ 悬浮球显示定时状态

**现状**：迷你播放条 = 播放悬浮球 `#player`（`index.html` 1020–1033，含 `#ballRing/#pCover/#pFabIcon/#pTitle`）。桌面小组件已显示定时剩余（changelog 10726），但 App 内悬浮球没有。

**新增**
- `index.html` `#player` 内加 `<span class="sleepbadge" id="sleepBadge"></span>`（置于 `#pFabIcon` 右上角）。
- `app.js` `window.onSleep`（5536）与 `paintSleepBtn`（5572）：当 `App.sleep` 活跃（`remain>0` 或 `eps>0`）时，按现有 `chip` 逻辑显示角标文字（如"⏱23分"或"⏱剩3集"），否则隐藏。
- `style.css`：`.sleepbadge` 小胶囊，定位悬浮球右上。

---

## 关键文件清单
- `src/com/tingshu/app/data/Settings.java`（新增 autoNext 偏好 + apply 白名单）
- `src/com/tingshu/app/Bridge.java`（`getSettings` 补 autoNext；play_mode 已存在）
- `src/com/tingshu/app/MainActivity.java`（重写 onEnded 统一分支：自动连播 + 四种播放模式）
- `assets/www/app.js`（`window.onSettings` 读取 autoNext/playMode + paintPlayMode；moreMenu 两项；chsListHtml 续听高亮 + 长按；paintHomeContinue 多本；onSleep 角标）
- `assets/www/index.html`（设置页 auto_next 开关 + play_mode 下拉；#player 加 sleepBadge）
- `assets/www/style.css`（chresume / 长按菜单 / 书架 / sleepbadge 样式）
- `AndroidManifest.xml`（versionCode 141 / 4.12.0）
- `assets/www/app.js` CHANGELOG + `更新说明/v4.12.0-更新说明.md` + `release/index.html` + 完整版 APK 构建

## 风险与注意
1. **onEnded 是单一决策点**：自动连播 + 四种播放模式全部集中在此改写，需逐模式自测（顺序连播/顺序停尾/单曲重播/列表回环/随机/最后一集）。
2. **不碰 `PlayerCore.autoNext` 死字段**，用 `Settings.autoNext()` 新建偏好，避免误改遗留逻辑。
3. 新增 `@JavascriptInterface` 若采用简单路线则走现有 `setSetting` 白名单（play_mode 已在；auto_next 加一行），R8 不会误删；若新增独立接口需确认 proguard keep（现有接口已自动保留）。
4. 续听位置高亮与首页多本书架都依赖现有 `HistoryRepo` 数据，无新存储 schema。
5. 构建仍走 `build.sh` 完整版（WMA 转码已含），APK 文件名带版本号防缓存。

## 验证要点（交付前）
- 顺序模式：默认自动连播；关掉后播完停在本集末尾不跳。
- 单曲循环：播完重播本集；列表循环：最后一集回到第一集；随机：跳转到非当前集。
- 章节列表出现"续听"高亮且长按能"从这集开始"。
- 首页"最近在听"显示最近 N 本且可逐本续听。
- 悬浮球在设定定时关闭后出现剩余角标；关闭后消失。
