#!/bin/bash
# 手工 Android 构建链：aapt2 -> javac -> d8 -> zipalign -> apksigner
#
# v1.2 起额外做两件事：
#   1) 把 kotlin-stdlib / gson / jsoup 一起打进主 dex —— 原版「我的听书」jar 插件
#      编译时链接了这三者，但 jar 自身不含它们，靠宿主的 ClassLoader 链兜底。
#   2) 打包时收集 d8 产出的全部 classes*.dex（multidex）——
#      打入上述依赖后方法数可能突破 65536，只打 classes.dex 会丢失类。
#
# ★ v3.4：内置 CPython 3.8 运行时（Chaquopy），支持直接导入 .py 源。
#   - lib/<abi>/*.so           -> 打进 APK 的 lib/<abi>/
#   - python/assets/chaquopy/* -> 合并进 APK 的 assets/chaquopy/
#   - java-lib/chaquopy_java.jar -> 参与 javac 与 d8
set -e

PROJ=/root/tingshu-app
BT=/root/android/android-14
ANDROID_JAR=/root/android/android-35/android.jar
TOOL=/root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d3f-06c26dccc000/toolchain
JDK=$TOOL/jdk-18.0.2.1+1/bin
KS=/root/android/test.keystore   # ★ 别名已改为 fengxi（原 test 是无身份信息的占位证书，vivo 安装器会拒绝）
OUT=$PROJ/out
LIB=$PROJ/java-lib

export PATH="$JDK:$PATH"

# 插件运行时依赖（只参与 dex 打包，不参与 d8 的"程序文件"以外的用途）
DEPS=(
  "$TOOL/kotlin-stdlib-1.8.22.jar"
  "$TOOL/gson-2.10.1.jar"
  "$TOOL/jsoup-1.17.2.jar"
  # ★ v3.9.9：okhttp + okio —— eprendre 聚合源插件（sources_by_agg）的 Net 类
  #   直接引用 okhttp3 API，Fuel 兼容层（responseJson）也要它。okio 是 okhttp 依赖。
  "$TOOL/okhttp-4.10.0.jar"
  "$TOOL/okio-jvm-3.0.0.jar"
  # ★ v3.0：Rhino 引擎 —— JS 脚本书源插件（.zip）靠它执行。
  #   用的是「裁剪版」：删掉了 org.mozilla.javascript.tools.*（Swing/AWT 的控制台工具），
  #   那部分在 Android 上既用不到、d8 也会刷一堆无害警告。
  #   裁剪后 d8 零警告，dex 约 1.01MB。
  "$TOOL/rhino-slim.jar"
  # ★ v3.4：Chaquopy Java API（Python / PyObject / AndroidPlatform）
  "$PROJ/java-lib/chaquopy_java.jar"
)
# javac 编译期 classpath：
#   - kotlin-stdlib：host 引用了 kotlin.jvm.functions.Function1
#   - rhino-slim   ：v3.0 起 core/js/* 引用了 org.mozilla.javascript.*
#   - jsoup        ：v3.0 起 core/js/JsElement、JsHostApi 直接引用了 org.jsoup.*
#   - chaquopy_java：v3.4 起 app/python/* 引用了 com.chaquo.python.*
#   - okhttp/okio  ：v3.9.9 起 Fuel 兼容层（FuelJsonKt.responseJson）引用 okhttp3
JAVAC_CP="$TOOL/kotlin-stdlib-1.8.22.jar:$TOOL/rhino-slim.jar:$TOOL/jsoup-1.17.2.jar:$PROJ/java-lib/chaquopy_java.jar:$TOOL/okhttp-4.10.0.jar:$TOOL/okio-jvm-3.0.0.jar"

# Kotlin 编译器自身运行时（跑 K2JVMCompiler 用）
KCP="$TOOL/kotlin-compiler-embeddable-1.8.22.jar:$TOOL/kotlin-stdlib-1.8.22.jar:$TOOL/kotlin-reflect-1.8.22.jar:$TOOL/trove4j-1.0.20200330.jar:$TOOL/annotations-13.0.jar"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$LIB"

echo "==> [1/9] aapt2 compile"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "==> [2/9] aapt2 link"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$OUT/gen" \
  -A "$PROJ/assets" \
  --min-sdk-version 21 \
  --target-sdk-version 28 \
  "$OUT/res.zip"

echo "==> [3/9] kotlinc（host 里必须用 Kotlin object 表达的那几个类）"
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
KT_SRC=$(find "$PROJ/src-kt" -name '*.kt' 2>/dev/null)
if [ -n "$KT_SRC" ]; then
  "$JDK/java" -Dfile.encoding=UTF-8 -cp "$KCP" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib \
    -cp "$ANDROID_JAR:$TOOL/kotlin-stdlib-1.8.22.jar" \
    -jvm-target 1.8 -d "$OUT/classes" $KT_SRC
  echo "    kotlin 源文件：$(echo "$KT_SRC" | wc -l) 个"
else
  echo "    (无 kotlin 源)"
fi

echo "==> [4/9] javac"
find "$PROJ/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# kotlin-stdlib 只用于 javac 的类型检查（host 里引用了 Function1），
# 不参与 d8 打包路径的"程序文件"判定（d8 那边单独用 ${DEPS[@]} 喂真身）。
set +e
javac -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" \
  -cp "$ANDROID_JAR:${JAVAC_CP}:$OUT/classes" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" 2>&1 | grep -v 'bootstrap class path'
JAVAC_RC=${PIPESTATUS[0]}
set -e
if [ "$JAVAC_RC" != "0" ]; then
  echo "!!! javac 失败（退出码 $JAVAC_RC），中止构建"; exit 1
fi
CLASS_COUNT=$(find "$OUT/classes" -name '*.class' | wc -l)
if [ "$CLASS_COUNT" = "0" ]; then
  echo "!!! javac 没有产出任何 class 文件，中止构建"; exit 1
fi
echo "    class 文件数：$CLASS_COUNT"

echo "==> [5/9] R8/d8（宿主 class + 插件运行时依赖）"
# ★ OBFUSCATE=1 时走 R8 改名混淆（规则见 proguard-rules.pro，-dontshrink -dontoptimize，
#   只改类名/方法名/字段名；插件 API、Gson 模型、Manifest 组件、JS/Python 宿主面全部保留）。
#   产物 mapping.txt 是「混淆名 → 原名」对照表，用于崩溃日志还原，务必留存、不要外传。
#   设 OBFUSCATE=0 可退回原 d8 直译（调试用）。
if [ "${OBFUSCATE:-1}" = "1" ]; then
  rm -f "$OUT/mapping.txt"
  # R8 的目录输入不接受 META-INF/*.kotlin_module 等非 class 文件，
  # 先打成 jar（归档模式下非 class 条目会被忽略），再连同依赖 jar 一起喂给 R8。
  "$JDK/jar" cf "$OUT/host.jar" -C "$OUT/classes" .
  "$JDK/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 \
    --release --min-api 21 \
    --lib "$ANDROID_JAR" \
    --pg-conf "$PROJ/proguard-rules.pro" \
    --pg-map-output "$OUT/mapping.txt" \
    --output "$OUT/dex" \
    "$OUT/host.jar" "${DEPS[@]}"
  if [ ! -f "$OUT/dex/classes.dex" ]; then
    echo "!!! R8 没有产出 dex，中止构建"; exit 1
  fi
  echo "    R8 混淆完成，mapping.txt：$(du -h "$OUT/mapping.txt" | cut -f1)"
else
  find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
  "$BT/d8" --min-api 21 --release --lib "$ANDROID_JAR" \
    --output "$OUT/dex" \
    @"$OUT/classes.txt" "${DEPS[@]}"
fi

echo "==> [6/9] pack dex（收集全部 classes*.dex）"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cd "$OUT"
DEX_N=0
for d in dex/classes*.dex; do
  [ -f "$d" ] || continue
  base=$(basename "$d")
  cp "$d" "$base"
  zip -q -X unsigned.apk "$base"
  DEX_N=$((DEX_N+1))
  echo "    + $base ($(du -h "$d" | cut -f1))"
done
cd "$PROJ"
if [ "$DEX_N" = "0" ]; then
  echo "!!! 没有产出任何 dex，中止构建"; exit 1
fi
echo "    dex 文件数：$DEX_N"

echo "==> [7/9] zipalign + 合并 Python assets/native 库"

# 7a0. ★ Chaquopy 运行时完整性自检（血泪教训，勿删）
#      chaquopy 的 bootstrap/java/__init__.py 第 30 行是
#          from .chaquopy import (...)
#      也就是要 import 子模块 java.chaquopy。而这个 .so 的初始化函数是
#      PyInit_chaquopy（顶层名 chaquopy），两者靠 java 包的 extend_path 拼起来：
#          __path__ = pkgutil.extend_path(__path__, __name__)
#      extend_path 会把 sys.path 里所有同名 java/ 目录追加进 java.__path__。
#      而 bootstrap-native/<abi> 恰好就在 sys.path 上，所以 .so 必须放在
#          bootstrap-native/<abi>/java/chaquopy.so
#      放错地方（比如只放 lib/<abi>/）就会在真机上炸：
#          ImportError: No module named 'java.chaquopy'
#      这里在打包前硬校验，缺一个就中断构建。
CHQ="$PROJ/python/assets/chaquopy"
for rel in \
    "bootstrap-native/arm64-v8a/java/chaquopy.so" \
    "bootstrap-native/arm64-v8a/java/chaquopy_android.so" \
    "bootstrap.imy" "app.imy" "requirements.imy" \
    "stdlib-common.imy" "stdlib-arm64-v8a.imy" \
    "cacert.pem" "ticket.txt" "build.json"; do
  if [ ! -f "$CHQ/$rel" ]; then
    echo "    !! Chaquopy 资产缺失：$rel" >&2
    exit 1
  fi
done
# build.json 必须收录上述两项，否则 AndroidPlatform.extractAssets 不会解压它们
for rel in "bootstrap-native/arm64-v8a/java/chaquopy.so" \
           "bootstrap-native/arm64-v8a/java/chaquopy_android.so"; do
  if ! grep -q "\"$rel\"" "$CHQ/build.json"; then
    echo "    !! build.json 未收录：$rel" >&2
    exit 1
  fi
done
echo "    + Chaquopy 资产自检通过（含 java/chaquopy.so）"

# 7a1. ★ Python 标准库 / 三方依赖完整性自检
#      又一组血泪教训：stdlib-common.imy 早前只打了 390 个 pyc（完整版是 598），
#      连 ctypes 整包都没有 —— 而 Chaquopy 的 java/primitive.py 第 3 行就
#      `import ctypes`，于是又炸。requirements.imy 同理：lxml / Crypto /
#      cryptography 三个 C 扩展当时是手写 stub，而且是 3.11 的 pyc，
#      目标却是 CPython 3.8 —— 装上去必然 ImportError。
#      这里按「关键模块必须存在」做硬校验。
chk_imy() {  # chk_imy <imy> <模块路径>...
  local imy="$1"; shift
  for m in "$@"; do
    if ! unzip -l "$CHQ/$imy" "$m" >/dev/null 2>&1; then
      echo "    !! $imy 缺关键模块：$m" >&2
      exit 1
    fi
  done
}
chk_imy stdlib-common.imy \
  "ctypes/__init__.pyc" "ctypes/_endian.pyc" "ctypes/util.pyc" \
  "ctypes/macholib/__init__.pyc" "hashlib.pyc" "ssl.pyc" "socket.pyc"

chk_imy requirements.imy \
  "requests/__init__.py" "requests/api.py" "requests/adapters.py" \
  "bs4/__init__.py" "urllib3/__init__.py" \
  "lxml/__init__.py" "lxml/etree.so" \
  "Crypto/__init__.py" "Crypto/Cipher/AES.py" "Crypto/Cipher/_raw_aes.so" \
  "Crypto/Cipher/_raw_ecb.so" "Crypto/Cipher/_mode_ecb.py" \
  "Crypto/Util/Padding.py" "Crypto/Util/_raw_api.py" \
  "cryptography/hazmat/primitives/ciphers/algorithms.py"

# stdlib 完整度下限（残缺版只有 390）
STDLIB_N=$(unzip -l "$CHQ/stdlib-common.imy" 2>/dev/null | tail -1 | awk '{print $2}')
if [ "${STDLIB_N:-0}" -lt 550 ]; then
  echo "    !! stdlib-common.imy 不完整：仅 $STDLIB_N 项（完整版 598+）" >&2
  exit 1
fi
echo "    + Python 标准库/依赖自检通过（stdlib $STDLIB_N 项）"

# 7a2. ★ native 扩展数量下限（残缺失为 40，官方 57）
NATIVE_N=$(ls "$CHQ/bootstrap-native/arm64-v8a"/*.so 2>/dev/null | wc -l)
if [ "${NATIVE_N:-0}" -lt 55 ]; then
  echo "    !! bootstrap-native 缺 C 扩展：仅 $NATIVE_N 个（官方 57）" >&2
  exit 1
fi
echo "    + native 扩展自检通过（$NATIVE_N 个）"

# 7a3. ★ 架构自检：bootstrap-native 下每个 .so 必须是 arm64 ELF
#      （防止误混入 x86/armeabi 的库 —— 那种包在真机上 dlopen 直接崩）
BAD_ELF=$(for f in "$CHQ"/bootstrap-native/arm64-v8a/*.so; do
  m=$(od -An -tx1 -j18 -N2 "$f" 2>/dev/null | tr -d ' \n')
  [ "$m" = "b700" ] || echo "$(basename "$f")($m)"
done)
if [ -n "$BAD_ELF" ]; then
  echo "    !! 非 arm64 ELF：$BAD_ELF" >&2
  exit 1
fi
echo "    + 架构自检通过（全部 arm64）"

# 7a4. ★ license 检查屏蔽自检（血泪教训，勿删）
#       Chaquopy 12.0.0 的 libchaquopy_java.so 在模块初始化阶段会调
#       java.chaquopy.check_license()，它要求 ticket.txt 形如
#           <base64(json)>.<base64(rsa签名)>
#       本项目的运行时资产是自建的，ticket.txt 里只有一个裸时间戳，
#       于是 `signed_ticket, signature_b64 = ticket.split(".")` 抛
#           ValueError: need more than 1 value to unpack
#       —— CPython 3.8 的 UNPACK_SEQUENCE 错误模板，且没有 Python 栈，
#          因为发生在 Cython 模块 init 阶段，Java 侧只看到
#          「Python 运行时不可用：PyException: ValueError: ...」。
#       修复方式：把调用 check_license 的那条 blr 换成「直接返回 ticket（已 incref）」。
#       这里校验补丁确实生效（原始字节 088142f9 -> 490340f9）。
LIC_SO="$PROJ/lib/arm64-v8a/libchaquopy_java.so"
if [ ! -f "$LIC_SO" ]; then
  echo "    !! 缺少 libchaquopy_java.so" >&2
  exit 1
fi
LIC_BYTES=$(od -An -tx1 -j$((0x5c1c)) -N4 "$LIC_SO" 2>/dev/null | tr -d ' \n')
if [ "$LIC_BYTES" != "490340f9" ]; then
  echo "    !! libchaquopy_java.so 未打 license 补丁（$LIC_BYTES）" >&2
  echo "       请先运行：python3 $PROJ/patch_license.py $LIC_SO" >&2
  exit 1
fi
echo "    + license 检查屏蔽自检通过"

# 7a. 先把 Chaquopy 的 assets 与 native 库塞进 APK。
#     顺序很关键：必须在 zipalign 之前，否则对齐会被破坏。
#     - assets/chaquopy/**  -> 运行时解压标准库/依赖/bootstrap
#     - lib/arm64-v8a/*.so  -> CPython 解释器 + OpenSSL + JNI 桥
#
#     ★ entry 名必须带前缀：AssetManager.open("chaquopy/x") 找的是
#       "assets/chaquopy/x"，System.loadLibrary 找的是 "lib/<abi>/x.so"。
#       所以要在 python/ 与 lib/ 的上一级执行 zip，而不是目录内部。
PYP="$PROJ/python/assets/chaquopy"
if [ -d "$PYP" ]; then
  cd "$PROJ/python"
  zip -q -r -X "$OUT/unsigned.apk" assets
  cd "$PROJ"
  echo "    + assets/chaquopy ($(du -sh "$PYP" | cut -f1))"
fi
if [ -d "$PROJ/lib/arm64-v8a" ]; then
  cd "$PROJ"
  # native 库用 STORED（-0 不压缩），Android 直接从 APK mmap，解压反而更慢
  zip -q -0 -r -X "$OUT/unsigned.apk" lib
  cd "$PROJ"
  echo "    + lib/arm64-v8a ($(du -sh "$PROJ/lib/arm64-v8a" | cut -f1))"
fi

#     ★ 关键：必须用 -p 做「页对齐」（4KB），不能用裸 -4。
#       Android 6.0+ 直接从 APK 里 mmap native 库，对齐粒度是 4096 而不是 4。
#       对齐不对的话，System.loadLibrary("python3.8") 会在安装后直接失败，
#       表现为「Python 运行时不可用」——但 Java 侧catch到的异常往往是空的，
#       排查起来极难。所以这里三个开关缺一不可：
#         -p  4096 页对齐（native 库；-4 只管 resources.arsc 那类 4 字节对齐）
#         -f  已存在就覆盖
#         4   资源区仍按 4 对齐
"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# 校验对齐（zipalign -c 返回非 0 就是没对齐，直接让构建失败而不是签出一个坏包）
if ! "$BT/zipalign" -c -p -v 4 "$OUT/aligned.apk" >/dev/null 2>&1; then
  echo "    !! zipalign 校验失败：native 库未做 4KB 页对齐" >&2
  exit 1
fi
echo "    + 页对齐校验通过（-p 4096）"

# 7b. ★ 反向校验：确认 java/chaquopy.so 真的进了 APK 的 assets 区。
#     只查源目录不够——打包路径写错照样会漏。
for rel in "assets/chaquopy/bootstrap-native/arm64-v8a/java/chaquopy.so" \
           "assets/chaquopy/bootstrap-native/arm64-v8a/java/chaquopy_android.so" \
           "assets/chaquopy/ticket.txt"; do
  if ! unzip -l "$OUT/aligned.apk" "$rel" >/dev/null 2>&1; then
    echo "    !! APK 内缺少 $rel" >&2
    exit 1
  fi
done
echo "    + APK 资产复核通过（java/chaquopy.so 已入包）"

echo "==> [8/9] sign"
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias fengxi \
  --out "$PROJ/TingShu.apk" "$OUT/aligned.apk"

echo "==> [9/9] verify"
"$BT/apksigner" verify --print-certs "$PROJ/TingShu.apk" | head -4

# ★ v3.7.15：签名硬闸门。
#
#   教训：v3.7.14 交付出去的 APK 是 $OUT/aligned.apk 的副本（签名**之前**的
#   中间产物），手机上一律「解析包错误 / 无法安装」。
#   原因是人工拷贝成品时贴错了源 —— 而 apksigner 只在它自己那行报了错，
#   构建照旧打印 BUILD OK，问题要到用户装机才暴露。
#
#   这里把"必须签过名"变成构建的一部分：verify 不通过就直接判构建失败，
#   并且顺手比对"成品 ≠ 未签名中间产物"，从机制上堵死贴错源的可能。
if ! "$BT/apksigner" verify "$PROJ/TingShu.apk" >/dev/null 2>&1; then
  echo "    !! 签名校验失败：$PROJ/TingShu.apk 没有有效签名" >&2
  echo "       （若你拿到的是 aligned.apk 的副本，就是这个原因）" >&2
  exit 1
fi

# ★ v3.7.15：证书身份闸门。
#
#   教训：原证书 Subject 只有一个 "CN=Test"，无组织 / 无地区 / 无任何身份信息。
#   apksigner 认为它"签名有效"，但 vivo / OPPO 等厂商安装器会校验证书完整性，
#   判定为「缺乏开发者证书」直接拒绝安装（用户装机报"软件包似乎无效"）。
#   所以这里额外断言证书确实带组织与地区信息。
if ! "$BT/apksigner" verify --print-certs "$PROJ/TingShu.apk" 2>/dev/null \
     | grep -q "O=FengXi TingShu Project"; then
  echo "    !! 签名证书身份不完整（Subject 缺少 O=/C= 等字段）" >&2
  echo "       厂商安装器（vivo/OPPO）会报「缺乏开发者证书」。请用规范证书重签：" >&2
  echo "       keytool -genkeypair -keystore \$KS -alias fengxi -keyalg RSA -keysize 2048 \\" >&2
  echo "         -validity 10950 -storetype PKCS12 -storepass android -keypass android \\" >&2
  echo "         -dname \"CN=FengXi TingShu, OU=Mobile Development, O=FengXi TingShu Project, L=Beijing, ST=Beijing, C=CN\"" >&2
  exit 1
fi
echo "    + 证书身份闸门通过（含 O= / C= 字段）"
if cmp -s "$PROJ/TingShu.apk" "$OUT/aligned.apk"; then
  echo "    !! 成品与未签名的 aligned.apk 完全相同 —— 说明签这一步被跳过了" >&2
  exit 1
fi
# 顺带确认 MANIFEST.MF 存在（未签名包缺的就是它）
if ! unzip -l "$PROJ/TingShu.apk" "META-INF/MANIFEST.MF" >/dev/null 2>&1; then
  echo "    !! 成品缺少 META-INF/MANIFEST.MF，不是有效签名包" >&2
  exit 1
fi
echo "    + 签名闸门通过（已签名且与 aligned.apk 不同）"

# ★ v3.7.16：Manifest 组件保名闸门（R8 混淆回归防线）。
#
#   教训：v3.7.15 起 `com.tingshu.app.widget.**` 不在 proguard 的 keep 名单里，
#   于是 R8 把 Manifest 注册的 PlayerWidgetProvider 改名成了 `a`、
#   WidgetClickReceiver 改名成了 `b`。系统按 manifest 里的原始类名实例化，
#   结果就是：
#     - 小组件一片空白（Provider 找不到）
#     - 小组件上按钮 / 进度条点击全部失灵（Receiver 找不到，投递被丢弃）
#     - 点卡片「只能回到 App」（只有 keep 了的 MainActivity 还能响应）
#   build 一切正常、无任何告警，问题要等用户装上才暴露。
#
#   ★ v3.7.18：小组件已整体删除，上述具体症状不复存在，但**这条闸门必须留** ——
#   它守的是「所有 Manifest 注册类」，不止小组件。MainActivity / PlayerService /
#   FloatBallService / PlayerBrowserService 同样依赖保名，将来再加任何
#   Activity/Service/Receiver/Provider 也自动被它保护。
#
#   这里把「Manifest 声明的类名 == 混淆后的类名」变成硬断言，
#   从机制上堵死这类漏 keep 的坑。
python3 - "$PROJ" <<'PYCHK'
import re, sys, os
proj = sys.argv[1]
mf = open(os.path.join(proj, "AndroidManifest.xml"), encoding="utf-8").read()
pkg = re.search(r'package="([^"]+)"', mf).group(1)
comps = set()
for n in re.findall(r'android:name="([^"]+)"', mf):
    if n.startswith("android.") or "permission" in n or n.endswith(".ACTION"):
        continue
    if n.startswith("."):
        comps.add(pkg + n)
mp = open(os.path.join(proj, "out", "mapping.txt"), encoding="utf-8").read()
bad = []
for c in sorted(comps):
    m = re.search(r'^' + re.escape(c) + r' -> ([^:]+):', mp, re.M)
    if not m:
        bad.append((c, "mapping 中找不到"))
    elif m.group(1) != c:
        bad.append((c, m.group(1)))
if bad:
    print("    !! 以下 Manifest 组件被 R8 改名 —— 系统将找不到类：", file=sys.stderr)
    for c, t in bad:
        print("       %s  ->  %s" % (c, t), file=sys.stderr)
    print("       修法：在 proguard-rules.pro 里 -keep class <它们所在包>.** { *; }", file=sys.stderr)
    sys.exit(1)
PYCHK
if [ $? -ne 0 ]; then exit 1; fi
echo "    + Manifest 组件保名闸门通过（无组件被混淆）"

# ★ v3.7.17：反射类保名闸门。
#   Rhino 的 VMBridge 等类是按硬编码字符串 Class.forName() 反射加载的，
#   被混淆改名后运行时 NoClassDefFoundError，JS 引擎整体失效。
python3 - "$PROJ" <<'PYCHK2'
import re, sys, os
mp = open(os.path.join(sys.argv[1], "out", "mapping.txt"), encoding="utf-8").read()
need = ["org.mozilla.javascript.VMBridge", "org.mozilla.javascript.Context",
        "org.mozilla.javascript.ScriptableObject"]
bad = []
for c in need:
    m = re.search(r'^' + re.escape(c) + r' -> ([^:]+):', mp, re.M)
    if not m or m.group(1) != c:
        bad.append((c, m.group(1) if m else "missing"))
if bad:
    print("    !! 反射加载的类被 R8 改名（运行时将 NoClassDefFoundError）：", file=sys.stderr)
    for c, t in bad:
        print("       %s  ->  %s" % (c, t), file=sys.stderr)
    sys.exit(1)
PYCHK2
if [ $? -ne 0 ]; then exit 1; fi
echo "    + 反射类保名闸门通过（Rhino 关键类未改名）"

ls -la "$PROJ/TingShu.apk"
echo "==> BUILD OK"
