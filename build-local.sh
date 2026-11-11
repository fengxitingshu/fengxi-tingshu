#!/bin/bash
# build-local.sh —— 沙箱本地构建版（与 build.sh 流程一致，仅路径适配）：
#   PROJ 指向 /workspace/fengxi-tingshu，TOOLCHAIN 在 /root/toolchain，
#   rhino 用官方 1.7.14（无 rhino-slim 裁剪版，多余 tools 类只增体积不影响功能）。
set -e

PROJ=/workspace/fengxi-tingshu
BT=/root/android/android-14
ANDROID_JAR=/root/android/android-35/android.jar
TOOL=/root/toolchain
JDK=$TOOL/jdk/bin
if [ ! -x "$JDK/java" ]; then
  JDK17=$(ls -d /usr/lib/jvm/java-17-openjdk-* 2>/dev/null | head -1)
  if [ -n "$JDK17" ]; then JDK="$JDK17/bin"; else JDK=$(dirname "$(which java)"); fi
fi
KS=/root/android/test.keystore
OUT=$PROJ/out
LIB=$PROJ/java-lib

export PATH="$JDK:$PATH"

DEPS=(
  "$TOOL/kotlin-stdlib-1.8.22.jar"
  "$TOOL/gson-2.10.1.jar"
  "$TOOL/jsoup-1.17.2.jar"
  "$TOOL/okhttp-4.10.0.jar"
  "$TOOL/okio-jvm-3.0.0.jar"
  "$TOOL/rhino-1.7.14.jar"
  "$LIB/chaquopy_java.jar"
  "$LIB/ijkplayer-java.jar"
  "$LIB/mobile-ffmpeg.jar"
)
JAVAC_CP="$TOOL/kotlin-stdlib-1.8.22.jar:$TOOL/rhino-1.7.14.jar:$TOOL/jsoup-1.17.2.jar:$LIB/chaquopy_java.jar:$TOOL/okhttp-4.10.0.jar:$TOOL/okio-jvm-3.0.0.jar:$LIB/ijkplayer-java.jar"

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

echo "==> [3/9] kotlinc"
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

echo "==> [5/9] R8/d8"
rm -f "$OUT/mapping.txt"
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
echo "    R8 混淆完成"

echo "==> [6/9] pack dex"
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
for rel in "bootstrap-native/arm64-v8a/java/chaquopy.so" \
           "bootstrap-native/arm64-v8a/java/chaquopy_android.so"; do
  if ! grep -q "\"$rel\"" "$CHQ/build.json"; then
    echo "    !! build.json 未收录：$rel" >&2
    exit 1
  fi
done
echo "    + Chaquopy 资产自检通过"

LIC_SO="$PROJ/lib/arm64-v8a/libchaquopy_java.so"
if [ ! -f "$LIC_SO" ]; then
  echo "    !! 缺少 libchaquopy_java.so" >&2
  exit 1
fi
LIC_BYTES=$(od -An -tx1 -j$((0x5c1c)) -N4 "$LIC_SO" 2>/dev/null | tr -d ' \n')
if [ "$LIC_BYTES" != "490340f9" ]; then
  echo "    !! libchaquopy_java.so 未打 license 补丁（$LIC_BYTES），执行 patch_license.py" >&2
  python3 "$PROJ/patch_license.py" "$LIC_SO" || exit 1
fi
echo "    + license 检查屏蔽自检通过"

PYP="$PROJ/python/assets/chaquopy"
if [ -d "$PYP" ]; then
  cd "$PROJ/python"
  zip -q -r -X "$OUT/unsigned.apk" assets
  cd "$PROJ"
  echo "    + assets/chaquopy ($(du -sh "$PYP" | cut -f1))"
fi
if [ -d "$PROJ/lib/arm64-v8a" ]; then
  cd "$PROJ"
  zip -q -0 -r -X "$OUT/unsigned.apk" lib
  cd "$PROJ"
  echo "    + lib/arm64-v8a ($(du -sh "$PROJ/lib/arm64-v8a" | cut -f1))"
fi

"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if ! "$BT/zipalign" -c -p -v 4 "$OUT/aligned.apk" >/dev/null 2>&1; then
  echo "    !! zipalign 校验失败" >&2
  exit 1
fi
echo "    + 页对齐校验通过（-p 4096）"

for rel in "assets/chaquopy/bootstrap-native/arm64-v8a/java/chaquopy.so" \
           "assets/chaquopy/bootstrap-native/arm64-v8a/java/chaquopy_android.so" \
           "assets/chaquopy/ticket.txt"; do
  if ! unzip -l "$OUT/aligned.apk" "$rel" >/dev/null 2>&1; then
    echo "    !! APK 内缺少 $rel" >&2
    exit 1
  fi
done
echo "    + APK 资产复核通过"

echo "==> [8/9] sign"
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias fengxi \
  --out "$PROJ/TingShu.apk" "$OUT/aligned.apk"

echo "==> [9/9] verify"
"$BT/apksigner" verify --print-certs "$PROJ/TingShu.apk" | head -4
if ! "$BT/apksigner" verify "$PROJ/TingShu.apk" >/dev/null 2>&1; then
  echo "    !! 签名校验失败" >&2; exit 1
fi
if ! "$BT/apksigner" verify --print-certs "$PROJ/TingShu.apk" 2>/dev/null \
     | grep -q "O=FengXi TingShu Project"; then
  echo "    !! 证书身份不完整" >&2; exit 1
fi
echo "    + 证书身份闸门通过"

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
    print("    !! 以下 Manifest 组件被 R8 改名：", file=sys.stderr)
    for c, t in bad:
        print("       %s  ->  %s" % (c, t), file=sys.stderr)
    sys.exit(1)
PYCHK
if [ $? -ne 0 ]; then exit 1; fi
echo "    + Manifest 组件保名闸门通过"

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
    print("    !! 反射加载的类被 R8 改名：", file=sys.stderr)
    for c, t in bad:
        print("       %s  ->  %s" % (c, t), file=sys.stderr)
    sys.exit(1)
PYCHK2
if [ $? -ne 0 ]; then exit 1; fi
echo "    + 反射类保名闸门通过"

ls -la "$PROJ/TingShu.apk"
echo "==> BUILD OK"
