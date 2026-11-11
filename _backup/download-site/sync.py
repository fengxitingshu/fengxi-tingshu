#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
沙箱侧：构建完 APK 后运行本脚本，自动刷新 manifest.json。
NAS 上的锚点(v2)轮询这个 manifest，发现 md5 变化就自动拉新版。

重要：manifest 的 apps 数组是「页面内容唯一来源」。本脚本只更新其中
匹配到的那一条的文件字段，绝不重建数组，避免把听书等 App 覆盖掉。

用法：python3 sync.py [apk路径]
不传参时自动取本目录里最新的 fengxi-video-*.apk（影视）。
"""
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
AAPT = "/root/android/android-14/aapt"
META = os.path.join(HERE, "meta.json")
MANIFEST = os.path.join(HERE, "manifest.json")


def md5_of(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        while True:
            b = f.read(1024 * 1024)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def latest_apk():
    # 默认只挑影视，避免误把听书当成主 App
    vids = [
        os.path.join(HERE, n) for n in os.listdir(HERE)
        if n.startswith("fengxi-video-") and n.lower().endswith(".apk")
        and os.path.isfile(os.path.join(HERE, n))
    ]
    if vids:
        return max(vids, key=os.path.getmtime)
    cands = [
        os.path.join(HERE, n) for n in os.listdir(HERE)
        if n.lower().endswith(".apk") and os.path.isfile(os.path.join(HERE, n))
    ]
    return max(cands, key=os.path.getmtime) if cands else None


def read_badging(path):
    info = {}
    if not os.path.isfile(AAPT):
        return info
    try:
        # aapt 有时会输出正常结果却返回退出码 1，因此不能用 check_output。
        r = subprocess.run(
            [AAPT, "dump", "badging", path], text=True, timeout=30,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        out = r.stdout or ""
        first = out.splitlines()[0] if out else ""
        for key, pat in (
            ("package", r"name='([^']+)'"),
            ("versionCode", r"versionCode='(\d+)'"),
            ("versionName", r"versionName='([^']+)'"),
        ):
            m = re.search(pat, first)
            if m:
                info[key] = m.group(1)
    except Exception:
        pass
    return info


def new_app_entry(package, name):
    """apps 里原本没有这个包时，给一个最小可用条目（文案可之后手工补）。"""
    if "tingshu" in name.lower() or package == "com.tingshu.app":
        return {"app": "枫兮听书", "package": package, "icon": "听", "theme": "t",
                "subtitle": "网盘有声书 · 解析转存 · 缓存中继 · 后台播放",
                "features": [], "links": []}
    return {"app": "枫兮影视", "package": package, "icon": "影", "theme": "v",
            "subtitle": "飞牛 NAS / TVBox 多源影视播放器",
            "features": [], "links": []}


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else latest_apk()
    if not apk or not os.path.isfile(apk):
        print("!! 找不到 APK")
        sys.exit(1)
    name = os.path.basename(apk)
    badging = read_badging(apk)
    ver = badging.get("versionName") or ""
    if not ver:
        m = re.search(r"v(\d+(?:\.\d+)+)", name)
        ver = m.group(1) if m else "?"
    package = badging.get("package") or ""
    vcode = int(badging.get("versionCode") or 0)
    size, md5 = os.path.getsize(apk), md5_of(apk)
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    data = {}
    if os.path.isfile(MANIFEST):
        try:
            data = json.load(open(MANIFEST, encoding="utf-8"))
        except Exception:
            data = {}
    apps = data.get("apps")
    if not isinstance(apps, list):
        apps = []

    # 只更新匹配到的那一条，保留其余 App 的全部文案与功能列表
    target = next((a for a in apps
                   if a.get("package") == package or a.get("file") == name), None)
    if target is None:
        target = new_app_entry(package, name)
        apps.append(target)
    target.update({"package": package or target.get("package", ""),
                   "version": ver, "versionCode": vcode,
                   "file": name, "size": size, "md5": md5})
    data["apps"] = apps
    data.setdefault("assets", [])

    # 顶层字段 = apps 第一项，供 v1 老锚点兼容读取
    head = apps[0]
    data.update({k: head.get(k) for k in
                 ("app", "package", "version", "versionCode", "file", "size", "md5")})
    data["updated"] = now
    data.setdefault("notes", "")

    tmp = MANIFEST + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, MANIFEST)
    print("已更新 %s：apps 共 %d 个，其余 App 未改动" % (name, len(apps)))


if __name__ == "__main__":
    main()
