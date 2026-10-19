#!/bin/bash
# restore-env.sh —— 枫兮听书沙箱环境一键恢复（新会话开局跑一次，全程自动）
# 恢复内容：仓库(浅克隆) + Android build-tools/platform + Kotlin/R8 依赖 + JDK17 + 签名证书
# 预计耗时：网络顺畅 5~8 分钟（主要在下载 ~150MB）
set -e
# Token 不入库：运行时传入  export FENGXI_TOKEN=github_pat_xxx  或  bash restore-env.sh <token>
T="${FENGXI_TOKEN:?请先 export FENGXI_TOKEN=<你的GitHub PAT>}"
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "==> [1/6] 克隆仓库（浅克隆，~50MB）"
if [ ! -d /workspace/fengxi-tingshu/.git ]; then
  git clone --depth 1 --single-branch --branch main \
    "https://x-access-token:${T}@github.com/fengxitingshu/fengxi-tingshu.git" \
    /workspace/fengxi-tingshu
fi
git -C /workspace/fengxi-tingshu config user.name "fengxitingshu"
git -C /workspace/fengxi-tingshu config user.email "fengxitingshu@users.noreply.github.com"
echo "    $(git -C /workspace/fengxi-tingshu log --oneline -1)"

echo "==> [2/6] 签名证书与 mapping（从工作区回填，不重新生成！）"
mkdir -p /root/android
# keystore 备份可能在工作区(.fengxi-env)或脚本同目录
KB="$HERE/.fengxi-env/test.keystore"
[ -f "$KB" ] || KB="$(dirname "$HERE")/.fengxi-env/test.keystore"
[ -f "$KB" ] || KB="/workspace/.fengxi-env/test.keystore"
[ -f "$KB" ] || { echo "!! 找不到 test.keystore 备份——防盗指纹绑定该证书，绝不能重新生成"; exit 1; }
cp "$KB" /root/android/test.keystore

echo "==> [3/6] Android SDK：build-tools 34 + platform 35"
mkdir -p /root/android/android-14 /root/android/android-35
if [ ! -x /root/android/android-14/aapt2 ]; then
  curl -sL --retry 5 --retry-all-errors -m 1800 -o /root/android/bt34.zip \
    "https://dl.google.com/android/repository/build-tools_r34-linux.zip"
  (cd /root/android && unzip -q -o bt34.zip && rm bt34.zip)
fi
if [ ! -f /root/android/android-35/android.jar ]; then
  curl -sL --retry 5 --retry-all-errors -m 1800 -o /root/android/p35.zip \
    "https://dl.google.com/android/repository/platform-35_r02.zip"
  (cd /root/android && unzip -q -o p35.zip && rm p35.zip)
fi

echo "==> [4/6] Kotlin/R8 依赖 jar（Maven Central）"
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

echo "==> [5/6] OpenJDK 17"
if ! ls -d /usr/lib/jvm/java-17-openjdk-* >/dev/null 2>&1; then
  apt-get update -qq >/dev/null 2>&1 || true
  apt-get install -y -qq openjdk-17-jdk-headless >/dev/null 2>&1
fi

echo "==> [6/6] 试构建验证（产物进 release/，构建后自动清理）"
cd /workspace/fengxi-tingshu
if bash build-local.sh >/tmp/build.log 2>&1; then
  tail -2 /tmp/build.log
  cp out/mapping.txt "$HERE/.fengxi-env/mapping-latest.txt" 2>/dev/null || true
  cp TingShu.apk "release/枫兮听书-$(grep versionName AndroidManifest.xml | sed 's/.*"\(.*\)".*/\1/').apk"
  rm -rf TingShu.apk out
  echo "✅ 环境恢复完成，构建验证通过"
else
  tail -20 /tmp/build.log; echo "❌ 构建失败，看上面的日志"; exit 1
fi
