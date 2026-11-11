#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FX NAS 锚点 —— 跑在飞牛 NAS 上，主动从固定源拉取最新 APK 并生成下载页。

适用场景：
  NAS 没有公网 IPv4（域名是纯 IPv6），沙箱无法从外面连进来「推」文件；
  但 NAS 出方向能访问公网，所以由 NAS 主动「拉」，不需要端口转发 / 穿透 / 密码。

纯 Python 标准库，无需 pip 依赖。可跑在 Docker 里，也可直接 python3 + cron。

环境变量：
  SRC_MANIFEST  必填，manifset.json 的完整 URL（可带 ?sharecode= 等查询串）
  OUT_DIR       输出目录，默认 /data（挂载到 NAS 的 nginx 静态目录）
  INTERVAL      轮询间隔秒数，默认 60
  ONCE          设为 1 时只同步一次就退出（用于手动触发 / 测试）
"""

import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime
from string import Template

OUT_DIR = os.environ.get("OUT_DIR", "/data")
SRC_MANIFEST = os.environ.get("SRC_MANIFEST", "")
INTERVAL = max(10, int(os.environ.get("INTERVAL", "60")))
ONCE = os.environ.get("ONCE", "") == "1"
UA = "FX-NasAnchor/1.0"
SAFE_NAME = re.compile(r"^[A-Za-z0-9_.-]+$")

PAGE = Template("""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>$app $version · 下载</title>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:#0e1116;color:#e8ecf2;min-height:100vh;
 font:15px/1.8 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;
 padding:24px 16px calc(40px + env(safe-area-inset-bottom))}
.wrap{max-width:520px;margin:0 auto}
.card{background:#171c24;border:1px solid #262e3a;border-radius:18px;padding:24px 20px;
 margin-bottom:16px;box-shadow:0 10px 34px rgba(0,0,0,.4)}
.icon{width:64px;height:64px;margin:0 auto 14px;border-radius:17px;
 background:linear-gradient(140deg,#3b82f6,#7c3aed);display:flex;align-items:center;
 justify-content:center;font-size:30px;box-shadow:0 6px 18px rgba(80,110,255,.35)}
h1{font-size:22px;margin:0 0 4px;text-align:center}
.ver{text-align:center;color:#8b96a8;font-size:13px;margin-bottom:14px}
.state{display:block;text-align:center;padding:7px 12px;border-radius:20px;font-size:12.5px;margin:0 auto 4px;width:fit-content}
.state.ok{background:#123024;border:1px solid #2a6b4b;color:#8fe3b4}
.state.warn{background:#2b2012;border:1px solid #6b4a1f;color:#ffd79a}
.btn{display:block;width:100%;text-align:center;text-decoration:none;
 background:linear-gradient(135deg,#3b82f6,#6366f1);color:#fff;font-size:17px;font-weight:600;
 padding:16px;border-radius:13px;box-shadow:0 6px 20px rgba(59,130,246,.35);margin:18px 0 10px}
.btn:active{transform:translateY(1px);opacity:.92}
table{width:100%;border-collapse:collapse;font-size:14px}
td{padding:8px 4px;border-bottom:1px solid #232b37;vertical-align:top}
tr:last-child td{border-bottom:0}
td.k{color:#8792a4;width:38%}td.v{color:#dbe3ee;font-weight:500}
h2{font-size:16px;color:#9fd8ff;margin:0 0 12px;border-left:4px solid #3b82f6;padding-left:10px}
ol{padding-left:20px;margin:8px 0 0;font-size:13.5px;color:#a8b3c2}
ol li{margin:6px 0}
.tip{background:#10201a;border:1px solid #1e4033;border-radius:10px;color:#a7d8c0;
 padding:12px 14px;font-size:13px;margin-top:12px}
.warnbox{background:#241c10;border:1px solid #57401c;border-radius:10px;color:#f0d09a;
 padding:12px 14px;font-size:13px;margin-top:12px}
code{background:#1e2936;padding:2px 6px;border-radius:4px;font-size:12.5px;color:#ffd9a0}
.foot{text-align:center;color:#5c6675;font-size:11.5px;margin-top:22px}
</style>
</head>
<body>
<div class="wrap">
  <div class="card">
    <div class="icon">▶</div>
    <h1>$app</h1>
    <div class="ver">v$version · $package</div>
    <span class="state $state_cls">$state_text</span>
    <a class="btn" href="$file" download="$file">⬇ 下载安装包（$size_mb MB）</a>
    <div style="font-size:12.5px;color:#8b96a8;text-align:center">
      Android 5.0+ · arm64 · 文件 <code>$file</code>
    </div>
  </div>

  <div class="card">
    <h2>当前版本</h2>
    <table>
      <tr><td class="k">版本</td><td class="v">v$version（versionCode $vcode）</td></tr>
      <tr><td class="k">源文件更新</td><td class="v">$updated</td></tr>
      <tr><td class="k">本机同步时间</td><td class="v">$synced_at</td></tr>
      <tr><td class="k">文件大小</td><td class="v">$size_mb MB（$size 字节）</td></tr>
      <tr><td class="k">MD5 校验</td><td class="v"><code>$md5</code></td></tr>
      <tr><td class="k">当前访问地址</td><td class="v"><span id="host">—</span></td></tr>
    </table>
    <div class="tip">本页由 <b>FX NAS 锚点</b> 自动生成。新版本发布后，锚点会在 $interval 秒内自动拉取并替换 APK，刷新本页即是最新版。</div>
    $offline_note
  </div>

  <div class="card">
    <h2>安装步骤</h2>
    <ol>
      <li>点绿色按钮，浏览器把 APK 存进「下载」目录（提示风险时选「仍要下载」）</li>
      <li>到「文件管理 → 下载」找到 <code>$file</code>，点开用「包安装程序」安装</li>
      <li>弹出「未知来源」时允许本浏览器安装应用</li>
      <li>装好后打开 App，填服务器地址 / 导入订阅即可</li>
    </ol>
    <div class="warnbox">若点 apk 直接被别的程序抢开：设置 → 应用管理 → 找到抢注 <code>.apk</code> 的 App → 清除默认值。</div>
  </div>

  <div class="foot">FX NAS 锚点 · 由 NAS 主动同步 · $synced_at</div>
</div>
<script>
  document.getElementById('host').textContent = location.host;
</script>
</body>
</html>
""")


def log(msg):
    print("[%s] %s" % (datetime.now().strftime("%Y-%m-%d %H:%M:%S"), msg), flush=True)


def http_get(url, timeout=20):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def md5_of(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        while True:
            b = f.read(1024 * 1024)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def fetch_manifest():
    if not SRC_MANIFEST:
        raise RuntimeError("未配置 SRC_MANIFEST")
    raw = http_get(SRC_MANIFEST)
    m = json.loads(raw.decode("utf-8"))
    u = urllib.parse.urlsplit(SRC_MANIFEST)
    m["_origin"] = "%s://%s" % (u.scheme, u.netloc)
    m["_query"] = u.query
    return m


def apk_url(m, name):
    if m.get("url"):
        return m["url"]
    url = "%s/%s" % (m["_origin"], urllib.parse.quote(name))
    if m["_query"]:
        url += "?" + m["_query"]
    return url


def download_and_verify(url, target, expect_md5, expect_size):
    tmp = target + ".part"
    h = hashlib.md5()
    total = 0
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=90) as r, open(tmp, "wb") as f:
        while True:
            b = r.read(1024 * 1024)
            if not b:
                break
            f.write(b)
            h.update(b)
            total += len(b)
    got = h.hexdigest()
    if expect_md5 and got != expect_md5:
        os.remove(tmp)
        raise RuntimeError("MD5 不匹配：期望 %s，实际 %s" % (expect_md5, got))
    if expect_size and total != int(expect_size):
        os.remove(tmp)
        raise RuntimeError("大小不匹配：期望 %s，实际 %s" % (expect_size, total))
    os.replace(tmp, target)
    return got, total


def scan_local_apk():
    """源不可达时，用目录里已有的 APK 兜底（取最近修改的一个）。"""
    best = None
    for n in os.listdir(OUT_DIR):
        if not n.lower().endswith(".apk") or not SAFE_NAME.match(n):
            continue
        p = os.path.join(OUT_DIR, n)
        if not os.path.isfile(p):
            continue
        st = os.stat(p)
        if best is None or st.st_mtime > best[1]:
            best = (p, st.st_mtime)
    if not best:
        return None
    p = best[0]
    ver = re.search(r"v(\d+(?:\.\d+)+)", os.path.basename(p))
    return {
        "app": "枫兮影视", "package": "com.fengxi.fnplayer",
        "version": ver.group(1) if ver else "?", "versionCode": "?",
        "file": os.path.basename(p), "size": os.path.getsize(p),
        "md5": md5_of(p), "updated": "（本机文件）",
    }


def render_page(m, ok, note=""):
    size = int(m.get("size") or 0)
    vals = {
        "app": html.escape(str(m.get("app", "App"))),
        "package": html.escape(str(m.get("package", ""))),
        "version": html.escape(str(m.get("version", "?"))),
        "vcode": html.escape(str(m.get("versionCode", "?"))),
        "file": html.escape(str(m.get("file", "app.apk"))),
        "size": size,
        "size_mb": "%.1f" % (size / 1024.0 / 1024.0),
        "md5": html.escape(str(m.get("md5", ""))),
        "updated": html.escape(str(m.get("updated", ""))),
        "synced_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "interval": INTERVAL,
        "state_cls": "ok" if ok else "warn",
        "state_text": "● 已同步最新版本" if ok else "● 源暂不可达，显示上次同步的版本",
        "offline_note": (
            "" if ok else
            '<div class="warnbox">锚点暂时连不上更新源，但本机 APK 仍可正常下载安装。'
            '恢复联网后会自动补同步。原因：%s</div>' % html.escape(note)
        ),
    }
    out = os.path.join(OUT_DIR, "index.html")
    tmp = out + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(PAGE.substitute(vals))
    os.replace(tmp, out)


def sync_once():
    os.makedirs(OUT_DIR, exist_ok=True)
    try:
        m = fetch_manifest()
    except Exception as e:
        log("拉取 manifest 失败：%s" % e)
        fallback = scan_local_apk()
        if fallback:
            render_page(fallback, False, str(e))
            log("已用本机 APK 生成下载页：%s" % fallback["file"])
        else:
            log("本机也没有 APK，暂无下载页")
        return

    name = str(m.get("file", ""))
    if not name or not SAFE_NAME.match(name) or not name.lower().endswith(".apk"):
        log("manifest 里的 file 不合法，跳过：%r" % name)
        return
    target = os.path.join(OUT_DIR, name)
    expect_md5 = str(m.get("md5", "")).lower()

    # 已是最新则跳过下载（以 md5 + 大小为准，重启容器也不会重复拉）
    if (os.path.isfile(target) and expect_md5
            and md5_of(target) == expect_md5
            and os.path.getsize(target) == int(m.get("size") or 0)):
        render_page(m, True)
        log("已是最新：v%s (%s)" % (m.get("version", "?"), name))
        return

    log("发现新版本 v%s，开始下载 %s" % (m.get("version", "?"), name))
    try:
        got_md5, size = download_and_verify(
            apk_url(m, name), target, expect_md5, m.get("size"))
        m["md5"] = got_md5
        m["size"] = size
        render_page(m, True)
        log("同步完成：%s（%d 字节，md5 %s）" % (name, size, got_md5))
    except Exception as e:
        log("下载/校验失败，保留旧版本：%s" % e)
        fallback = scan_local_apk()
        if fallback:
            render_page(fallback, False, str(e))


def main():
    log("FX NAS 锚点启动")
    log("  源：%s" % (SRC_MANIFEST or "（未配置）"))
    log("  输出：%s   间隔：%ds" % (OUT_DIR, INTERVAL))
    if not SRC_MANIFEST:
        log("!! 必须设置 SRC_MANIFEST，退出")
        sys.exit(1)
    while True:
        try:
            sync_once()
        except Exception as e:
            log("本轮异常（下轮重试）：%s" % e)
        if ONCE:
            break
        time.sleep(INTERVAL)


if __name__ == "__main__":
    main()
