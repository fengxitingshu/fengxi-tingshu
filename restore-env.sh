#!/bin/bash
# restore-env.sh —— 枫兮听书环境一键恢复（支持三种来源）
#   模式1 同沙箱换窗口：/workspace/fengxi-tingshu 已存在 → 跳过下载，直接重建
#   模式2 新环境有 token：export FENGXI_TOKEN=<PAT> → 克隆仓库
#   模式3 无网/无 token：把 枫兮听书-恢复包.tar.gz 放在本脚本同目录 → 解包
# 用法：bash restore-env.sh
#   或  export FENGXI_TOKEN=github_pat_xxx && bash restore-env.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
DST=/workspace/fengxi-tingshu
T="${FENGXI_TOKEN:-${1:-}}"

echo "=====> [1/6] 确定工程来源"
if [ -f "$DST/build.sh" ] && [ -d "$DST/src" ]; then
  echo "  模式1：已存在完整工程，跳过下载，直接重建"
elif [ -n "$T" ]; then
  echo "  模式2：用 GitHub PAT 浅克隆 main"
  rm -rf "$DST"
  git clone --depth 1 --single-branch --branch main \
    "https://x-access-token:${T}@github.com/fengxitingshu/fengxi-tingshu.git" "$DST"
else
  PKG="$HERE/枫兮听书-恢复包.tar.gz"
  [ -f "$PKG" ] || PKG="/workspace/枫兮听书-恢复包.tar.gz"
  if [ -f "$PKG" ]; then
    echo "  模式3：从本地恢复包解包（$PKG）"
    mkdir -p "$DST"
    tar xzf "$PKG" -C /workspace
  else
    echo "!! 无法确定来源，请任选其一："
    echo "   a) export FENGXI_TOKEN=<你的GitHub PAT> 再运行本脚本"
    echo "   b) 把 枫兮听书-恢复包.tar.gz 放在本脚本同目录或 /workspace/"
    echo "   c) 确保 /workspace/fengxi-tingshu 已存在（同沙箱换窗口）"
    exit 1
  fi
fi

echo "=====> [2/6] 签名证书回填（⚠ 绝不重新生成！）"
mkdir -p /root/android
KB=""
for p in "$DST/.fengxi-env/test.keystore" "$HERE/.fengxi-env/test.keystore" "/workspace/.fengxi-env/test.keystore"; do
  [ -f "$p" ] && KB="$p" && break
done
if [ -z "$KB" ]; then
  echo "!! 找不到 test.keystore（防盗指纹绑定该证书，绝不能重建）。"
  echo "   请把你离线保存的 test.keystore 放到 /workspace/.fengxi-env/ 后重试。"
  exit 1
fi
cp "$KB" /root/android/test.keystore
echo "   已回填：$KB"

echo "=====> [3/6] Android SDK：build-tools 34 + platform 35"
mkdir -p /root/android/android-14 /root/android/android-35
if [ ! -x /root/android/android-14/aapt2 ]; then
  curl -sL --retry 5 --retry-all-errors -m 1800 -o /root/android/bt34.zip \
    "https://dl.google.com/android/repository/build-tools_r34-linux.zip"
  (cd /root/android && unzip -q -o bt34.zip && rm -f bt34.zip)
fi
if [ ! -f /root/android/android-35/android.jar ]; then
  curl -sL --retry 5 --retry-all-errors -m 1800 -o /root/android/p35.zip \
    "https://dl.google.com/android/repository/platform-35_r02.zip"
  (cd /root/android && unzip -q -o p35.zip && rm -f p35.zip)
fi

echo "=====> [4/6] Kotlin/R8 依赖 jar（Maven Central）"
mkdir -p /root/toolchain && cd /root/toolchain
M=https://repo1.maven.org/maven2
for a in \
  "org/jetbrains/kotlin/kotlin-compiler-embeddable/1.8.22/kotlin-compiler-embeddable-1.8.22.jar" \
  "org/jetbrains/kotlin/kotlin-stdlib/1.8.22/kotlin-stdlib-1.8.22.jar" \
  "org/jetbrains/kotlin/kotlin-reflect/1.8.22/kotlin-reflect-1.8.22.jar" \
  "org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar" \
  "org/jetbrains/annotations/13.0/annotations-13.0.jar" \
  "com/google/code/gson/gson/2.10.1/gson-2.10.1.jar" \
  "org/jsoup/jsoup/1.17.2/jsoup-1.17.2.jar" \
  "com/squareup/okhttp3/okhttp/4.10.0/okhttp-4.10.0.jar" \
  "com/squareup/okio/okio-jvm/3.0.0/okio-jvm-3.0.0.jar" \
  "org/mozilla/rhino/1.7.14/rhino-1.7.14.jar" ; do
  f=$(basename "$a"); [ -s "$f" ] || curl -sL --retry 5 --retry-all-errors -m 900 -o "$f" "$M/$a"
done

echo "=====> [5/6] OpenJDK 17"
if ! ls -d /usr/lib/jvm/java-17-openjdk-* >/dev/null 2>&1; then
  apt-get update -qq >/dev/null 2>&1 || true
  apt-get install -y -qq openjdk-17-jdk-headless >/dev/null 2>&1
fi

echo "=====> [6/6] 试构建验证"
cd "$DST"
if [ -x build.sh ]; then BUILDER="bash build.sh"; else BUILDER="bash build-local.sh"; fi
if $BUILDER >/tmp/build.log 2>&1; then
  tail -3 /tmp/build.log
  mkdir -p release
  VN=$(grep versionName AndroidManifest.xml | sed 's/.*"\(.*\)".*/\1/')
  cp TingShu.apk "release/枫兮听书-${VN}.apk" 2>/dev/null || true
  echo "✅ 环境恢复完成，构建验证通过；产物：release/枫兮听书-${VN}.apk  与  $DST/TingShu.apk"
else
  tail -25 /tmp/build.log; echo "❌ 构建失败，看上面日志"; exit 1
fi
