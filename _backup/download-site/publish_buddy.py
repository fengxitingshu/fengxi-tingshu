#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""伴读娘独立发布：复用 publish.py 的 WebDAV/清理/校验，只动 apps 里的伴读娘条目。
用法：
    python publish_buddy.py --note "v1.6.1 · 本次更新说明"
APK 默认取 D:/work Buddy/.../noti-read/out/ 下最新的 伴读娘-v*.apk。
"""
import sys, os, json, shutil, argparse, glob

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import publish as P

BUDDY_PKG = "com.fengxi.notiread"
APK_DIR = r"D:/work Buddy/2026-11-12-00-53-29/noti-read/out"


def latest_buddy_apk():
    cands = glob.glob(os.path.join(APK_DIR, "伴读娘-*.apk"))
    if not cands:
        return ""
    return max(cands, key=os.path.getmtime)


def main():
    ap = argparse.ArgumentParser(description="伴读娘一键发布")
    ap.add_argument("--host", default="192.168.9.20")
    ap.add_argument("--port", type=int, default=5005)
    ap.add_argument("--https", action="store_true")
    ap.add_argument("--page-port", type=int, default=8099)
    ap.add_argument("--user", default="")
    ap.add_argument("--pass", dest="password", default="")
    ap.add_argument("--remote", default=P.DEFAULT_REMOTE)
    ap.add_argument("--apk", default="", help="指定 APK（默认取 noti-read/out 最新）")
    ap.add_argument("--note", default="", help="插入到 features 顶部的版本说明")
    ap.add_argument("--keep", type=int, default=3)
    ap.add_argument("--stable", type=int, default=0)
    args = ap.parse_args()

    apk = args.apk or latest_buddy_apk()
    if not apk or not os.path.isfile(apk):
        P.log("!! 找不到伴读娘 APK：%s" % (apk or APK_DIR))
        return 2
    P.log("[发布] %s" % os.path.basename(apk))

    ver, vcode, pkg = P.apk_identity(apk)
    if pkg != BUDDY_PKG:
        P.log("!! APK 包名不匹配：%s" % pkg)
        return 2
    if not ver:
        ver = "1.6.1"   # badging 不可用时的兜底
    name = "buddy-v%s.apk" % ver

    # 1) 复制进下载站（ASCII 文件名，NAS 中文直链有空响应问题）
    dst = os.path.join(P.HERE, name)
    shutil.copy2(apk, dst)
    P.log("[复制] %s -> 下载站" % name)

    # 2) 更新 manifest 里的伴读娘条目
    with open(P.MANIFEST, encoding="utf-8") as f:
        data = json.load(f)
    target = next((a for a in data["apps"] if a.get("package") == BUDDY_PKG), None)
    if target is None:
        P.log("!! manifest 里没有伴读娘条目")
        return 2
    target.update({
        "version": ver, "versionCode": int(vcode) if str(vcode).isdigit() else target.get("versionCode"),
        "file": name, "size": os.path.getsize(dst), "md5": P.md5_of(dst),
        "updated": P.now(),
        "notes": "%s · %s" % (ver, args.note) if args.note else target.get("notes"),
    })
    if args.note:
        feats = target.setdefault("features", [])
        if feats and args.note in feats[0]:
            pass
        else:
            feats.insert(0, "<b>%s</b>：%s" % (ver, args.note))
    tmp = P.MANIFEST + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, P.MANIFEST)
    P.log("[清单] 伴读娘条目已更新 v%s (versionCode %s)" % (ver, target["versionCode"]))

    # 3) 渲染首页
    P.render_page(data)

    # 4) 上传 NAS
    user, password = P.get_cred(args)
    if not (user and password):
        P.log("!! 缺 WebDAV 账号密码（凭据管理器 FX-NAS-WEBDAV）")
        return 3
    dav = P.Dav(args.host, args.port, args.https, user, password)
    remote = P.locate_remote(dav, args.remote)
    if not remote:
        return 4
    P.log("[目标] %s" % remote)

    files = [("manifest.json", P.MANIFEST), ("index.html", P.PAGE), (name, dst)]
    for fname, local in files:
        if not P.push(dav, remote, local, fname, skip_if_same=fname.endswith(".apk")):
            return 5

    # 5) 旧版清理（每 App 留 keep 版）
    P.prune_local(args.keep)
    P.prune_remote(dav, remote, args.keep)

    # 6) 端到端校验
    P.log("[验证] 从 NAS 侧端到端检查…")
    return 0 if P.verify(args, data, dst) else 6


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
