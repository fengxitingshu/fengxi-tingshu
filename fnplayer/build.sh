#!/bin/bash
# 枫兮影视（fnplayer）独立构建链：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 独立包名 com.fengxi.fnplayer；复用主工程的 ijkplayer jar 与三个 .so。
set -e

G=/workspace/fengxi-tingshu/fnplayer
BT=/root/android/android-14
BT8=/tmp/bt35/android-15
ANDROID_JAR=/root/android/android-35/android.jar
JDK=/usr/lib/jvm/java-17-openjdk-amd64/bin
KS=/root/android/test.keystore
IJK_JAR=$G/java-lib/ijkplayer-java.jar
RHINO_JAR=/root/toolchain/rhino-1.7.14.jar
# v2.14：外部 TVBox 爬虫 jar 由宿主提供网络/解析依赖。缺 okhttp3 会在
# homeContent 里抛 NoClassDefFoundError: Lokhttp3/MediaType;（首页解析失败）。
# 必须一起进 dex —— DexClassLoader 的 parent 是本 APK 的 PathClassLoader。
OKHTTP_JAR=$G/java-lib/okhttp3-3.12.13.jar
OKIO_JAR=$G/java-lib/okio-1.17.6.jar
JSOUP_JAR=$G/java-lib/jsoup-1.17.2.jar
GSON_JAR=$G/java-lib/gson-2.10.1.jar
KOTLIN_JAR=$G/java-lib/kotlin-stdlib-1.8.22.jar
OUT=$G/out
VER=2.22

export PATH="$JDK:$PATH"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "==> [1/6] aapt2 compile res（v2.16 自有 FX 图标）+ link（assets + Manifest）"
# v2.16：图标改用 res/mipmap-*/ic_launcher.png（与枫兮听书同款橙 FX 风格），
# 需要先 aapt2 compile 成 .flat 集合，再作为位置参数交给 link 生成 resources.arsc。
"$BT/aapt2" compile --dir "$G/res" -o "$OUT/res.zip"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$G/AndroidManifest.xml" \
  -A "$G/assets" \
  --min-sdk-version 21 \
  --target-sdk-version 28 \
  "$OUT/res.zip"

echo "==> [2/6] javac（含 ijkplayer jar）"
find "$G/src" -name '*.java' > "$OUT/sources.txt"
# 注意：不能用 `javac ... | grep ... || true` —— 管道会吞掉 javac 的失败码，
# 编译报错时 APK 照样打出来（缺类，运行时才崩）。必须显式判退出码。
if ! javac -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR:$BT/core-lambda-stubs.jar" \
  -cp "$ANDROID_JAR:$IJK_JAR:$RHINO_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
    cat "$OUT/javac.log"
    echo "!!! javac 编译失败"
    exit 1
fi
grep -v -E 'bootstrap class path|deprecat' "$OUT/javac.log" || true
N=$(find "$OUT/classes" -name '*.class' | wc -l)
[ "$N" -gt 0 ] || { echo "!!! javac 没有产出 class"; exit 1; }
echo "    class 文件数：$N"

echo "==> [3/6] 展开依赖 jar 的 class（含 okhttp3 / okio / jsoup / gson / kotlin）"
cd "$OUT/classes"
for J in "$IJK_JAR" "$RHINO_JAR" "$OKHTTP_JAR" "$OKIO_JAR" \
         "$JSOUP_JAR" "$GSON_JAR" "$KOTLIN_JAR"; do
    [ -f "$J" ] || { echo "!!! 缺少依赖 jar：$J"; exit 1; }
    unzip -o -q "$J" '*.class' -d . 2>/dev/null || true
done
# multi-release jar 里的 META-INF/versions/9/* 与 module-info.class 不是有效
# Android 类路径，必须剔除，否则 d8 会报错或产出无法加载的类。
find . -path './META-INF/*' -name '*.class' -delete
find . -name 'module-info.class' -delete
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
echo "    总 class 数：$(wc -l < "$OUT/classes.txt")"
[ -f ./okhttp3/MediaType.class ] || { echo "!!! okhttp3 没有进 classes，爬虫源会崩"; exit 1; }
[ -f ./okio/Buffer.class ] || { echo "!!! okio 没有进 classes"; exit 1; }

echo "==> [4/6] d8"
"$BT8/d8" --min-api 21 --release --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  @"$OUT/classes.txt"
[ -f "$OUT/dex/classes.dex" ] || { echo "!!! d8 没有产出 dex"; exit 1; }

echo "==> [5/6] pack（dex + 三个 ijk .so）+ zipalign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cd "$OUT/dex"
zip -q -X "$OUT/unsigned.apk" classes*.dex
cd "$G"
# -0：so 不压缩，保证运行时可直接 mmap 加载；只带三个 ijk so
zip -q -X -0 "$OUT/unsigned.apk" \
  lib/arm64-v8a/libijkffmpeg.so \
  lib/arm64-v8a/libijkplayer.so \
  lib/arm64-v8a/libijksdl.so
"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/zipalign" -c -p -v 4 "$OUT/aligned.apk" >/dev/null 2>&1 || { echo "!!! zipalign 校验失败"; exit 1; }

echo "==> [6/6] sign + verify"
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias fengxi \
  --out "$G/枫兮影视-v$VER.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify "$G/枫兮影视-v$VER.apk" || { echo "!!! 签名校验失败"; exit 1; }
"$BT/aapt2" dump badging "$G/枫兮影视-v$VER.apk" | grep -E '^(package|application-label)' | head -2
ls -lh "$G/枫兮影视-v$VER.apk"
echo "==> BUILD OK"
