#!/usr/bin/env bash
# 枫兮听书 —— 把本地已有的历史 APK 补传成 GitHub Release（含资产）
#
# 用法：
#   export GH_TOKEN=ghp_xxxxxxxxxxxx
#   bash 补传历史Release.sh v3.7.12 v3.7.13
#
# 说明：
#   - 与 发布Release.sh 的区别：这个是**批量补历史版本**用的。
#     发布Release.sh 用于发布当期新版本，会校验「本版必须是 latest」；
#     补传历史版本时，latest 不该被它们抢走，所以本脚本最后会统一把
#     latest 复位到 apk/ 目录里版本号最高的那一版。
#   - 只认 release/ 和 apk/ 两个目录下的 「枫兮听书-<tag>.apk」。
#   - 资产名用纯 ASCII（GitHub 会把上传名里的非 ASCII 前缀归一化掉，
#     中文名会被削成 "-v3.7.15.apk" 这种残缺形式，所以统一用英文名）。

set -euo pipefail

REPO="fengxitingshu/fengxi-tingshu"
HERE="$(cd "$(dirname "$0")" && pwd)"
API="https://api.github.com/repos/$REPO"

if [[ -z "${GH_TOKEN:-}" ]]; then
  echo "✗ 未设置 GH_TOKEN，请先执行： export GH_TOKEN=ghp_xxxx" >&2
  exit 1
fi
[[ $# -gt 0 ]] || { echo "用法： bash 补传历史Release.sh v3.7.12 v3.7.13" >&2; exit 1; }

BT="${BT:-/root/android/android-14}"
AUTH=(-H "Authorization: Bearer $GH_TOKEN"
      -H "Accept: application/vnd.github+json"
      -H "X-GitHub-Api-Version: 2022-11-28"
      -H "User-Agent: FengXiTingshu")
say() { printf '\n\033[1;33m==> %s\033[0m\n' "$*"; }

for TAG in "$@"; do
  say "处理 $TAG"

  # 定位 APK
  APK=""
  for d in release apk; do
    if [[ -f "$HERE/$d/枫兮听书-${TAG}.apk" ]]; then
      APK="$HERE/$d/枫兮听书-${TAG}.apk"; break
    fi
  done
  [[ -n "$APK" ]] || { echo "  跳过：找不到 枫兮听书-${TAG}.apk" >&2; continue; }

  # 验签（历史包也必须签过名才能上传）
  if [[ -x "$BT/apksigner" ]] && ! "$BT/apksigner" verify "$APK" >/dev/null 2>&1; then
    echo "  !! $APK 未签名，跳过" >&2
    continue
  fi

  NOTES="$HERE/更新说明/${TAG}-更新说明.md"
  BODY_JSON="$(python3 - "$TAG" "$NOTES" <<'PY'
import json,sys,os
tag,notes=sys.argv[1],sys.argv[2]
body=open(notes,encoding='utf-8').read() if os.path.exists(notes) else f"枫兮听书 {tag}\n\n历史版本归档。安装包见本页下方附件。"
print(json.dumps({"tag_name":tag,"name":f"枫兮听书 {tag}","body":body,
                  "draft":False,"prerelease":False,"make_latest":"false"},
                 ensure_ascii=False))
PY
)"

  # 已存在则复用，否则创建
  RID="$(curl -sS "${AUTH[@]}" "$API/releases/tags/$TAG" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("id",""))' 2>/dev/null || true)"
  if [[ -z "$RID" ]]; then
    RID="$(curl -sS -X POST "${AUTH[@]}" -H "Content-Type: application/json" \
      -d "$BODY_JSON" "$API/releases" \
      | python3 -c 'import sys,json;print(json.load(sys.stdin).get("id",""))')"
    [[ -n "$RID" ]] || { echo "  创建失败" >&2; continue; }
    echo "  Release 已创建 id=$RID"
  else
    echo "  Release 已存在 id=$RID"
  fi

  # 清同名旧资产
  curl -sS "${AUTH[@]}" "$API/releases/$RID/assets" \
    | python3 -c 'import sys,json;[print(a["id"],a["name"]) for a in json.load(sys.stdin)]' \
    | while read -r aid aname; do
        [[ "$aname" == *"${TAG}.apk" ]] || continue
        echo "    删除旧资产 $aname"
        curl -sS -X DELETE "${AUTH[@]}" "$API/releases/assets/$aid" >/dev/null
      done

  # 上传（纯 ASCII 资产名）
  AN="fengxi-tingshu-${TAG}.apk"
  UP="https://uploads.github.com/repos/$REPO/releases/$RID/assets?name=$AN"
  RESP="$(curl -sS --max-time 600 -X POST "${AUTH[@]}" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @"$APK" "$UP")"
  echo "$RESP" | python3 -c '
import sys,json
d=json.load(sys.stdin)
if d.get("browser_download_url"):
    print("    ✔ 上传成功  %s  (%.2f MB)" % (d["name"], d["size"]/1048576))
else:
    print("    ✗ 上传失败：", json.dumps(d,ensure_ascii=False)[:300]); sys.exit(1)'
done

# ---- 收尾：把 latest 复位到版本号最高的一版 ----
say "复位 latest 到最高版本"
TOP="$(ls "$HERE/apk" | sed -n 's/^枫兮听书-v\([0-9.]*\)\.apk$/\1/p' \
  | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)"
if [[ -n "$TOP" ]]; then
  RID="$(curl -sS "${AUTH[@]}" "$API/releases/tags/v$TOP" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("id",""))' 2>/dev/null || true)"
  if [[ -n "$RID" ]]; then
    curl -sS -X PATCH "${AUTH[@]}" -H "Content-Type: application/json" \
      -d '{"make_latest":"true"}' "$API/releases/$RID" >/dev/null
    echo "    已把 v$TOP 标为 latest"
  fi
fi
curl -sS "${AUTH[@]}" "$API/releases/latest" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("    当前 /releases/latest =", d.get("tag_name"))'

echo
echo "==> 完成。Releases 页面： https://github.com/$REPO/releases"
