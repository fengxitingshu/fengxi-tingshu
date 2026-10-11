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
