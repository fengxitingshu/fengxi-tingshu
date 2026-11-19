#!/bin/bash
# 枫兮影视（fnplayer）独立构建链：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 独立包名 com.fengxi.fnplayer；复用主工程的 ijkplayer jar 与三个 .so。
#
# v2.23：路径/工具全部参数化，Linux 沙箱默认值与原来完全一致；
#        Windows 主开发环境由 .fengxi-env/build-video.sh 注入 D:/... 路径
#        （CP_SEP=';' + wpath/mkcp + argfile 逐行加引号，解决项目路径含空格）。
set -e

# ---- 可覆盖的路径（默认值 = 原 Linux 沙箱，行为不变） ----
G=${G:-$(cd "$(dirname "$0")" && pwd)}
BT=${BT:-/root/android/android-14}
BT8=${BT8:-/tmp/bt35/android-15}
ANDROID_JAR=${ANDROID_JAR:-/root/android/android-35/android.jar}
TOOL=${TOOL:-/root/toolchain}
JDK=${JDK:-/usr/lib/jvm/java-17-openjdk-amd64/bin}
KS=${KS:-/root/android/test.keystore}
OUT=${OUT:-$G/out}
VER=${VER:-2.25}

# ---- 工具（Windows 用 .exe / shim，由 env.sh 注入） ----
CP_SEP=${CP_SEP:-:}
JAVAC=${JAVAC:-javac}
AAPT2=${AAPT2:-$BT/aapt2}
ZIPALIGN=${ZIPALIGN:-$BT/zipalign}
APKSIGNER=${APKSIGNER:-$BT/apksigner}
D8=${D8:-$BT8/d8}
ZIP=${ZIP:-zip}
UNZIP=${UNZIP:-unzip}
PY=${PY:-python3}
# 本机未回填 test.keystore 时用 SKIP_SIGN=1 只做编译验证，不产出可安装 APK
SKIP_SIGN=${SKIP_SIGN:-0}

# ---- 依赖 jar ----
IJK_JAR=$G/java-lib/ijkplayer-java.jar
# v2.14：外部 TVBox 爬虫 jar 由宿主提供网络/解析依赖。缺 okhttp3 会在
# homeContent 里抛 NoClassDefFoundError: Lokhttp3/MediaType;（首页解析失败）。
# 必须一起进 dex —— DexClassLoader 的 parent 是本 APK 的 PathClassLoader。
RHINO_JAR=${RHINO_JAR:-$TOOL/rhino-1.7.14.jar}
OKHTTP_JAR=$G/java-lib/okhttp3-3.12.13.jar
OKIO_JAR=$G/java-lib/okio-1.17.6.jar
JSOUP_JAR=$G/java-lib/jsoup-1.17.2.jar
GSON_JAR=$G/java-lib/gson-2.10.1.jar
KOTLIN_JAR=$G/java-lib/kotlin-stdlib-1.8.22.jar

# 成品名固定 ASCII，与 download-site/index.html 的链接保持一致（v2.23 统一）。
APK_NAME=fengxi-video-v$VER.apk
APK_OUT=$G/$APK_NAME

export PATH="$JDK:$PATH"

# Windows：把传给 JVM / 原生工具的路径统一成盘符形式（D:/...）。
wpath() {
  if [ "$CP_SEP" != ":" ] && command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}
mkcp() {
  local out="" p
  for p in "$@"; do
    [ -n "$out" ] && out="$out$CP_SEP"
    out="$out$(wpath "$p")"
  done
  printf '%s' "$out"
}

# 本机对 shell 的批量 rm 有保护，构建前先由入口用 Python 清空 OUT。
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "==> [1/6] aapt2 compile res（v2.16 自有 FX 图标）+ link（assets + Manifest）"
# v2.24 合规红线「不内置源」：assets/sources/ 下的 JS 源脚本只作为手动导入素材
# 留在仓库（下载站有等价副本），绝不打进 APK。aapt2 -A 会递归带上整个目录，
# 所以先 staging 一份只含 www/ 的 assets，保持 assets/www/ 路径不变。
ASSET_STAGE="$OUT/assets-stage"
mkdir -p "$ASSET_STAGE"
cp -r "$G/assets/www" "$ASSET_STAGE/"
[ -f "$ASSET_STAGE/www/index.html" ] || { echo "!!! assets staging 失败"; exit 1; }
"$AAPT2" compile --dir "$(wpath "$G/res")" -o "$(wpath "$OUT/res.zip")"
"$AAPT2" link \
  -o "$(wpath "$OUT/base.apk")" \
  -I "$(wpath "$ANDROID_JAR")" \
  --manifest "$(wpath "$G/AndroidManifest.xml")" \
  -A "$(wpath "$ASSET_STAGE")" \
  --min-sdk-version 21 \
  --target-sdk-version 28 \
  "$(wpath "$OUT/res.zip")"

echo "==> [2/6] javac（含 ijkplayer / rhino jar）"
find "$(wpath "$G/src")" -name '*.java' > "$OUT/sources.txt"
# Windows：javac 的 @argfile 按空白分词，项目路径含空格时必须逐行加引号。
[ "$CP_SEP" != ":" ] && sed -i 's/.*/"&"/' "$OUT/sources.txt" || true
# 注意：不能用 `javac ... | grep ... || true` —— 管道会吞掉 javac 的失败码，
# 编译报错时 APK 照样打出来（缺类，运行时才崩）。必须显式判退出码。
if ! "$JAVAC" -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$(mkcp "$ANDROID_JAR" "$BT/core-lambda-stubs.jar")" \
  -cp "$(mkcp "$ANDROID_JAR" "$IJK_JAR" "$RHINO_JAR")" \
  -d "$(wpath "$OUT/classes")" \
  @"$(wpath "$OUT/sources.txt")" > "$OUT/javac.log" 2>&1; then
    cat "$OUT/javac.log"
    echo "!!! javac 编译失败"
    exit 1
fi
grep -v -E 'bootstrap class path|deprecat' "$OUT/javac.log" || true
N=$(find "$OUT/classes" -name '*.class' | wc -l)
[ "$N" -gt 0 ] || { echo "!!! javac 没有产出 class"; exit 1; }
echo "    class 文件数：$N"

echo "==> [3/6] 展开依赖 jar 的 class（含 okhttp3 / okio / jsoup / gson / kotlin）"
# v2.23：原来用 `unzip '*.class'`，Windows 的 unzip 通配符不匹配包路径下的
# 嵌套 class（只解出根目录 1 个），okhttp3 闸门必然失败。改用 Python zipfile
# 逐条解压，跨平台行为一致；同时跳过 META-INF/versions 与 module-info.class
# （multi-release 类不是有效 Android 类路径，进了 d8 会报错）。
for J in "$IJK_JAR" "$RHINO_JAR" "$OKHTTP_JAR" "$OKIO_JAR" \
         "$JSOUP_JAR" "$GSON_JAR" "$KOTLIN_JAR"; do
    [ -f "$(wpath "$J")" ] || { echo "!!! 缺少依赖 jar：$J"; exit 1; }
done
"$PY" - "$(wpath "$OUT/classes")" \
  "$(wpath "$IJK_JAR")" "$(wpath "$RHINO_JAR")" "$(wpath "$OKHTTP_JAR")" \
  "$(wpath "$OKIO_JAR")" "$(wpath "$JSOUP_JAR")" "$(wpath "$GSON_JAR")" \
  "$(wpath "$KOTLIN_JAR")" <<'PY'
import sys, zipfile
dest = sys.argv[1]
n = 0
for jar in sys.argv[2:]:
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if not name.endswith('.class'):
                continue
            if name.startswith('META-INF/') or name.endswith('module-info.class'):
                continue
            z.extract(name, dest)
            n += 1
print('    展开 class 条目：%d' % n)
PY
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
# ★ 与 javac 的 sources.txt 不同：R8/D8 的 @argfile 按「行」读取、不做 shell
#   分词，含空格的路径直接整行就是一个参数。给它加引号反而会把引号当成
#   文件名的一部分（InvalidPathException: Illegal char <">）。
echo "    总 class 数：$(wc -l < "$OUT/classes.txt")"
[ -f "$OUT/classes/okhttp3/MediaType.class" ] || { echo "!!! okhttp3 没有进 classes，爬虫源会崩"; exit 1; }
[ -f "$OUT/classes/okio/Buffer.class" ] || { echo "!!! okio 没有进 classes"; exit 1; }

echo "==> [4/6] d8"
"$D8" --min-api 21 --release --lib "$(wpath "$ANDROID_JAR")" \
  --output "$(wpath "$OUT/dex")" \
  @"$(wpath "$OUT/classes.txt")"
[ -f "$OUT/dex/classes.dex" ] || { echo "!!! d8 没有产出 dex"; exit 1; }

echo "==> [5/6] pack（dex + 三个 ijk .so）+ zipalign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cd "$OUT/dex"
"$ZIP" -q -X "$(wpath "$OUT/unsigned.apk")" classes*.dex
cd "$(wpath "$G")"
# -0：so 不压缩，保证运行时可直接 mmap 加载；只带三个 ijk so
"$ZIP" -q -X -0 "$(wpath "$OUT/unsigned.apk")" \
  lib/arm64-v8a/libijkffmpeg.so \
  lib/arm64-v8a/libijkplayer.so \
  lib/arm64-v8a/libijksdl.so
"$ZIPALIGN" -p -f 4 "$(wpath "$OUT/unsigned.apk")" "$(wpath "$OUT/aligned.apk")"
"$ZIPALIGN" -c -p -v 4 "$(wpath "$OUT/aligned.apk")" >/dev/null 2>&1 || { echo "!!! zipalign 校验失败"; exit 1; }

echo "==> [6/6] sign + verify"
if [ "$SKIP_SIGN" != "1" ]; then
  # 口令只从环境变量注入（由 .fengxi-env/build-video.sh 从本地密钥说明读取），
  # 绝不写进脚本、日志或输出。
  : "${KS_PASS:?请 export KS_PASS（签名库口令，见 .fengxi-env/密钥说明.txt）}"
  : "${KEY_PASS:?请 export KEY_PASS（密钥口令）}"
  "$APKSIGNER" sign \
    --ks "$(wpath "$KS")" \
    --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
    --ks-key-alias fengxi \
    --out "$(wpath "$APK_OUT")" "$(wpath "$OUT/aligned.apk")"
  "$APKSIGNER" verify "$(wpath "$APK_OUT")" || { echo "!!! 签名校验失败"; exit 1; }
  "$AAPT2" dump badging "$(wpath "$APK_OUT")" | grep -E '^(package|application-label)' | head -2
  ls -lh "$APK_OUT"
  echo "==> BUILD OK: $APK_OUT"
else
  echo "==> SKIP_SIGN=1：跳过签名（仅编译 / 打包 / 页对齐验证，不产出可安装 APK）"
  ls -lh "$OUT/aligned.apk"
  echo "==> COMPILE OK"
fi
