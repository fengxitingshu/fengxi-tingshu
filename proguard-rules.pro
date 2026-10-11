# 枫兮听书 R8 混淆规则（配合 build.sh 的 R8 步骤）
#
# 策略：-dontshrink -dontoptimize，只做「改名」混淆 —— 类名/方法名/字段名变 a.b.c，
# 但不删除任何类、不做优化变形，最大限度保证运行时行为与混淆前一致。
#
# 必须保留名字的面（都是反射/外部契约点）：
#   1. Manifest 注册的四大组件（系统按名字实例化）
#   2. @JavascriptInterface 桥（WebView 按方法名调用）
#   3. 插件 API：jar 插件是外部编译的，按类名/方法名链接宿主接口
#   4. JS 插件宿主 API：Rhino 运行时按方法名反射解析
#   5. Python 宿主类：Chaquopy 侧按类名调用
#   6. Gson 模型：无 @SerializedName，JSON key 就是字段名，改了字段名会毁掉本地存档
#   7. 运行时依赖 jar（kotlin/gson/jsoup/chaquopy）：外部插件 jar 按名字链接它们

# ---------- 1. Manifest 组件 ----------
-keep class com.tingshu.app.MainActivity { *; }
-keep class com.tingshu.app.core.App { *; }
-keep class com.tingshu.app.player.PlayerService { *; }
-keep class com.tingshu.app.player.FloatBallService { *; }
-keep class com.tingshu.app.player.PlayerBrowserService { *; }
# ★ v3.7.16 关键修复：小组件相关类必须保名。
#   AppWidgetProvider / BroadcastReceiver 都是"Manifest 注册类"，
#   系统按 manifest 里的**原始类名**实例化；一旦被 R8 改名，
#   系统就找不到类 —— 表现为：
#     - 小组件加不上 / 一片空白（Provider 找不到）
#     - 小组件上的按钮、进度条点击全部无反应（Receiver 找不到，
#       PendingIntent 投递被系统丢弃）
#     - 点击卡片"只能回到 App"（MainActivity 恰好在 keep 名单里，所以只有它还能响应）
#   这个坑在 v3.7.15 就存在（当时 PlayerWidgetProvider 被改名为 a），
#   直到 v3.7.16 排查"小组件全失灵"时才定位到。
-keep class com.tingshu.app.widget.** { *; }

# ---------- 2. WebView JS 桥 ----------
-keep class com.tingshu.app.Bridge { *; }
-keep class com.tingshu.app.BridgeData { *; }
-keepclasseswithmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------- 3/4/5. 插件 API / JS 宿主 / Python 宿主 / 源引擎 ----------
-keep class com.tingshu.app.plugin.** { *; }
-keep class com.tingshu.app.python.** { *; }
-keep class com.tingshu.app.core.js.** { *; }
-keep class com.tingshu.app.source.** { *; }
-keep class com.github.eprendre.tingshu.** { *; }

# ★ v3.7.17 关键修复：Rhino 引擎本身必须保名。
#   Rhino 内部有多处按硬编码字符串反射加载自己的类，最典型的是 VMBridge：
#       Class.forName("org.mozilla.javascript.VMBridge")
#   R8 把 VMBridge 改名成 W2 后，运行时反射找不到原始类 →
#   NoClassDefFoundError: org.mozilla.javascript.W2 → 整个 JS 引擎初始化失败
#   → 所有 JS 插件源加载不出来。
#   这正是 v3.7.15 用户报「JS 源消失」的最底层根因（当时只是加了日志让失败可见）。
#   Rhino 混淆后 dex 只小几百 KB，收益远抵不上风险，整体 keep。
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# ---------- 6. Gson 数据模型（字段名即 JSON key）----------
-keep class com.tingshu.app.data.** { *; }

# ---------- 7. 运行时依赖（外部插件 jar 按名链接）----------
-keep class kotlin.** { *; }
-keep class kotlin.reflect.** { *; }
-keep class org.jetbrains.annotations.** { *; }
-keep class com.google.gson.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.chaquo.python.** { *; }

# ---------- 通用 ----------
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable
-dontshrink
-dontoptimize
-dontwarn
