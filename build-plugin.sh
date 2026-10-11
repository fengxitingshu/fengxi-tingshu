#!/bin/bash
# 构建示例插件 jar：javac -> d8 -> jar（含 MANIFEST 的 Source-Entry）
set -e

PROJ=/root/tingshu-app
BT=/root/android/android-14
ANDROID_JAR=/root/android/android-35/android.jar
JDK=/root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d3f-06c26dccc000/toolchain/jdk-18.0.2.1+1/bin
WORK=/tmp/plugindemo
OUT=$PROJ/plugins/DemoPodcastSource.jar

export PATH="$JDK:$PATH"

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/jar/META-INF"

echo "==> [1/4] javac"
# 宿主 Source 接口 / Models 打进 classpath，插件编译期才认得这些类型
HOST_CP="$ANDROID_JAR:$PROJ/out/classes"
javac -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" \
  -cp "$HOST_CP" \
  -d "$WORK/classes" \
  $(find "$PROJ/plugins-src" -name '*.java') 2>&1 | grep -v 'bootstrap class path' || true
echo "    classes: $(find "$WORK/classes" -name '*.class' | wc -l)"

echo "==> [2/4] d8"
find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" --min-api 21 --release --lib "$ANDROID_JAR" --lib "$PROJ/out/classes" \
  --output "$WORK/dex" @"$WORK/classes.txt"

echo "==> [3/4] jar + MANIFEST"
cat > "$WORK/MANIFEST.MF" <<'EOF'
Manifest-Version: 1.0
Source-Entry: com.example.tingshusource.DemoPodcastSource
Source-Version: 1.0
Source-Author: TingShu Demo
EOF
mkdir -p "$PROJ/plugins"
cd "$WORK/dex" && jar cfm "$OUT" "$WORK/MANIFEST.MF" classes.dex
cd "$PROJ"

echo "==> [4/4] verify"
unzip -l "$OUT"
ls -la "$OUT"
echo "==> PLUGIN JAR OK"
