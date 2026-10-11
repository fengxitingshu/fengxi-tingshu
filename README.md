# 枫兮听书 FengXi TingShu

聚合多书源的 Android 听书 App。WebView 前端 + Java/Kotlin 原生层，支持四种书源类型：
**JSON 源 / Python 源（.py，内置 CPython 运行时）/ JS 插件源（Rhino）/ jar 插件源**。

> 本仓库**不含签名密钥**。`test.keystore` 仅存在于本地构建机，永远不会提交。

## 下载

最新版 APK 直接放在仓库 `release/` 目录，点开即可下载：

| 版本 | 文件 | 大小 | SHA-256（前 16 位） |
|------|------|------|---------------------|
| **v3.7.14**（最新） | [`release/枫兮听书-v3.7.14.apk`](release/枫兮听书-v3.7.14.apk) | 17.5 MB | `7bc91686d272402f` |
| v3.7.13 | [`release/枫兮听书-v3.7.13.apk`](release/枫兮听书-v3.7.13.apk) | 17.5 MB | `0e21b9c965ef4f0b` |

历史版本见 [Releases](../../releases) 与 git 标签（`v3.7.13` / `v3.7.12` / …）。
校验：`sha256sum -c release/SHA256SUMS.txt`

> App 内「关于 → 软件更新 → 检查更新」会自动比对 GitHub 上的最新版本号，有新版直接给下载入口。

## 功能

- 聚合搜索：多源并发搜索、按来源分组筛选、搜索诊断
- 书城浏览：分类发现、分页加载、源/分类记忆（下次打开自动恢复）
- 播放：后台 Service + 通知栏控制器 + MediaSession（适配 iQOO 原子播放器）、倍速 0.5~3.0、音量增益、定时关闭、播完自动下一集
- 播放页：封面旋转/暂停保持角度、背景取色虚化、进度跟随、收藏
- 书源管理：分组、长按弹菜单（启停/测试连通/重命名/删除）、源验证与失效自动关停、去重
- 桌面小组件：液态玻璃半透明主题、封面显示、播放信息跑马灯、三键直接控制（上/播/下），点封面/书名才进 App
- 离线缓存：单集下载缓存
- 详情页：章节列表 1/2/3 列切换、按 200 集分段浏览（千集长列表不卡）
- 播放页：选集同样支持 1/2/3 列与 200 集分段，点按钮下拉选择（与详情页偏好互通）
- 其他：深色模式（跟随系统/白天/夜晚）、悬浮球、IPv6 检测、更新公告、检查更新

## 构建

手工构建链（无需 Gradle），需要 JDK 17+、Android SDK build-tools（aapt2/d8/R8）、platform android-35：

```bash
bash build.sh        # 默认开启 R8 改名混淆（proguard-rules.pro）
OBFUSCATE=0 bash build.sh   # 关闭混淆（调试用）
```

产物：`TingShu.apk`（含 arm64-v8a 原生库与 Chaquopy Python 运行时资产）。

混淆映射表输出在 `out/mapping.txt`（混淆名 → 原名），用于崩溃日志还原，**不要随包分发**。

## 书源

支持四种书源，详见 `assets/www/` 内的「导入源」说明：

| 类型 | 形态 | 适合 |
|------|------|------|
| JSON 源 | 声明式规则 | 简单站点 |
| Python 源 | `.py` 脚本，继承 `base.spider.Spider` | 复杂站点（推荐） |
| JS 插件源 | `registerSource` + Rhino | 中等复杂度 |
| jar 插件源 | 实现 `Source` 接口 | 与宿主同构建链的深度定制 |

## 目录结构

```
release/             最新版 APK 本体与 SHA256 校验和
src/  src-kt/        Java/Kotlin 原生层（Bridge 桥、多源聚合、播放内核、Python/JS 引擎）
assets/www/          WebView 前端（index.html / app.js / style.css / bridge.js）
python/              Chaquopy Python 运行时资产
plugins/ plugins-src/ jar 插件源示例与构建
build.sh             一键构建（aapt2 → kotlinc/javac → R8 → 签名）
proguard-rules.pro   R8 混淆规则
```

## 许可

保留所有版权。未经授权不得商用或二次分发。
