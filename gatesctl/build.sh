#!/bin/bash
# 枫兮锁控（gatesctl）独立构建链：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 极简单 Activity 工程：无 res、无依赖库、纯代码 UI。
set -e

G=/workspace/fengxi-tingshu/gatesctl
BT=/root/android/android-14
ANDROID_JAR=/root/android/android-35/android.jar
JDK=/usr/lib/jvm/java-17-openjdk-amd64/bin
KS=/root/android/test.keystore
OUT=$G/out

export PATH="$JDK:$PATH"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "==> [1/5] aapt2 link（无资源，仅 Manifest）"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$G/AndroidManifest.xml" \
  --min-sdk-version 21 \
  --target-sdk-version 28

echo "==> [2/5] javac"
find "$G/src" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR:$BT/core-lambda-stubs.jar" \
  -cp "$ANDROID_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" 2>&1 | grep -v 'bootstrap class path' || true
N=$(find "$OUT/classes" -name '*.class' | wc -l)
[ "$N" -gt 0 ] || { echo "!!! javac 没有产出 class"; exit 1; }
echo "    class 文件数：$N"

echo "==> [3/5] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --min-api 21 --release --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  @"$OUT/classes.txt"
[ -f "$OUT/dex/classes.dex" ] || { echo "!!! d8 没有产出 dex"; exit 1; }

echo "==> [4/5] pack + zipalign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cd "$OUT/dex"
zip -q -X "$OUT/unsigned.apk" classes.dex
cd "$OUT"
"$BT/zipalign" -p -f 4 unsigned.apk aligned.apk
"$BT/zipalign" -c -p -v 4 aligned.apk >/dev/null 2>&1 || { echo "!!! zipalign 校验失败"; exit 1; }

echo "==> [5/5] sign + verify"
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias fengxi \
  --out "$G/枫兮锁控-v1.0.apk" aligned.apk

"$BT/apksigner" verify "$G/枫兮锁控-v1.0.apk" || { echo "!!! 签名校验失败"; exit 1; }
"$BT/aapt2" dump badging "$G/枫兮锁控-v1.0.apk" | grep -E '^(package|application-label)' | head -2
ls -la "$G/枫兮锁控-v1.0.apk"
echo "==> BUILD OK"
