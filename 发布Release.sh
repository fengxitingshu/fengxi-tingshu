#!/usr/bin/env bash
# 枫兮听书 —— 一键在 GitHub 创建 Release 并上传 APK 本体
#
# 用法：
#   export GH_TOKEN=ghp_xxxxxxxxxxxx      # 需要 repo 权限（私有库还要有 workflow 权限不必须）
#   bash 发布Release.sh v3.7.9
#
# 说明：
#   - Token 只需勾选 repo 权限即可（fine-grained token 需 Contents: Read and write）
#   - 脚本会自动读取同名的更新说明文件作为 Release 正文
#   - 若 Release 已存在，会改为「上传/覆盖资产」

set -euo pipefail

TAG="${1:-v3.7.9}"
REPO="fengxitingshu/fengxi-tingshu"
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="$HERE/apk/枫兮听书-${TAG}.apk"
NOTES="$HERE/更新说明/${TAG}-更新说明.md"
API="https://api.github.com/repos/$REPO"

if [[ -z "${GH_TOKEN:-}" ]]; then
  echo "✗ 未设置 GH_TOKEN，请先执行： export GH_TOKEN=ghp_xxxx" >&2
  exit 1
fi
[[ -f "$APK" ]] || { echo "✗ 找不到 APK：$APK" >&2; exit 1; }

# ★ v3.7.15：发布前强制校验签名。
#
#   v3.7.14 曾把未签名的 aligned.apk 副本当成成品发出去，用户装机报
#   「解析包错误」。发布脚本是最后一道关，这里必须拦住：
#   没签名 / 缺 MANIFEST.MF 的包一律不准上传。
BT="${BT:-/root/android/android-14}"
if [[ ! -x "$BT/apksigner" ]]; then
  echo "✗ 找不到 apksigner（$BT/apksigner），无法校验签名" >&2
  exit 1
fi
if ! "$BT/apksigner" verify "$APK" >/dev/null 2>&1; then
  echo "✗ $APK 签名校验不通过 —— 拒绝发布" >&2
  echo "  常见原因：误把 out/aligned.apk（签名前的中间产物）当成成品拷贝过来。" >&2
  exit 1
fi
echo "✔ 签名校验通过：$(basename "$APK")"

AUTH=(-H "Authorization: Bearer $GH_TOKEN"
      -H "Accept: application/vnd.github+json"
      -H "X-GitHub-Api-Version: 2022-11-28"
      -H "User-Agent: FengXiTingshu")

say() { printf '\n\033[1;33m==> %s\033[0m\n' "$*"; }

# 1) 检查是否已有同名 release
say "查询 Release $TAG"
RELEASE_ID="$(curl -sS "${AUTH[@]}" "$API/releases/tags/$TAG" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("id",""))' 2>/dev/null || true)"

if [[ -z "$RELEASE_ID" ]]; then
  say "创建 Release $TAG"
  BODY_JSON="$(python3 - "$TAG" "$NOTES" <<'PY'
import json,sys,os
tag,notes=sys.argv[1],sys.argv[2]
body=open(notes,encoding='utf-8').read() if os.path.exists(notes) else f"枫兮听书 {tag}"
print(json.dumps({"tag_name":tag,"name":f"枫兮听书 {tag}","body":body,
                  "draft":False,"prerelease":False},ensure_ascii=False))
PY
)"
  RELEASE_ID="$(curl -sS -X POST "${AUTH[@]}" \
    -H "Content-Type: application/json" \
    -d "$BODY_JSON" "$API/releases" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("id",""))')"
  [[ -n "$RELEASE_ID" ]] || { echo "✗ 创建 Release 失败" >&2; exit 1; }
  echo "    Release id = $RELEASE_ID"
else
  echo "    已存在，id = $RELEASE_ID，改为上传资产"
fi

# 2) 既有同名资产先删掉（避免重名冲突）
say "清理同名旧资产"
curl -sS "${AUTH[@]}" "$API/releases/$RELEASE_ID/assets" \
  | python3 -c 'import sys,json;[print(a["id"],a["name"]) for a in json.load(sys.stdin)]' \
  | while read -r aid aname; do
      [[ "$aname" == "枫兮听书-${TAG}.apk" ]] || continue
      echo "    删除旧资产 $aname (id=$aid)"
      curl -sS -X DELETE "${AUTH[@]}" "$API/releases/assets/$aid" >/dev/null
    done

# 3) 上传 APK 本体
say "上传 $(basename "$APK") （$(du -h "$APK" | cut -f1)）"
UP_URL="https://uploads.github.com/repos/$REPO/releases/$RELEASE_ID/assets?name=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$(basename "$APK")")"
RESP="$(curl -sS -X POST "${AUTH[@]}" -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @"$APK" "$UP_URL")"
echo "$RESP" | python3 -c '
import sys,json
d=json.load(sys.stdin)
if d.get("browser_download_url"):
    print("    ✔ 上传成功")
    print("      下载地址：",d["browser_download_url"])
    print("      大小：",round(d["size"]/1048576,2),"MB")
else:
    print("    ✗ 上传失败：",json.dumps(d,ensure_ascii=False)[:400]); sys.exit(1)'

# 4) 输出 Release 页面
say "完成"
echo "    Release 页面： https://github.com/$REPO/releases/tag/$TAG"
