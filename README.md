# 枫兮听书 FengXi TingShu

聚合多书源的 Android 听书 App。WebView 前端 + Java/Kotlin 原生层，支持四种书源类型：
**JSON 源 / Python 源（.py，内置 CPython 运行时）/ JS 插件源（Rhino）/ jar 插件源**。

> 本仓库**不含签名密钥**。`test.keystore` 仅存在于本地构建机，永远不会提交。

## 功能

- 聚合搜索：多源并发搜索、按来源分组筛选、搜索诊断
- 书城浏览：分类发现、分页加载、源/分类记忆（下次打开自动恢复）
- 播放：后台 Service + 通知栏控制器 + MediaSession（适配 iQOO 原子播放器）、倍速 0.5~3.0、音量增益、定时关闭、播完自动下一集
- 播放页：封面旋转/暂停保持角度、背景取色虚化、进度跟随、收藏
- 书源管理：分组、批量启停/删除、源验证与失效自动关停、去重
- 离线缓存：单集下载缓存
- 其他：深色模式（跟随系统/白天/夜晚）、悬浮球、IPv6 检测、更新公告

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
src/  src-kt/        Java/Kotlin 原生层（Bridge 桥、多源聚合、播放内核、Python/JS 引擎）
assets/www/          WebView 前端（index.html / app.js / style.css / bridge.js）
python/              Chaquopy Python 运行时资产
plugins/ plugins-src/ jar 插件源示例与构建
build.sh             一键构建（aapt2 → kotlinc/javac → R8 → 签名）
proguard-rules.pro   R8 混淆规则
```

## 许可

保留所有版权。未经授权不得商用或二次分发。
