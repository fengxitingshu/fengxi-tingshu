#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FX 下载页锚点 v2 —— 多 App 版（页面内容完全由 manifest.json 驱动）

在飞牛 NAS 上常驻：主动从固定源拉取 manifest.json → 逐个下载并校验所有 APK
→ 生成含多个 App 的下载页 → 由容器内置 HTTP 服务对外提供。

与 v1 的区别：
  v1 只认 manifest 顶层的一个 App，页面写死单卡片；
  v2 读 manifest 的 apps 数组，加减 App / 改文案 / 改功能列表都只改 manifest，
     60 秒内自动生效，不用再替换脚本、不用重启容器。
  顶层单 App 字段仍保留，v1 脚本读它也不会崩（向后兼容）。

环境变量：
  SRC_MANIFEST  manifest.json 完整 URL（可带 ?sharecode= 等查询串），必填
  OUT_DIR       输出目录，默认 /data
  INTERVAL      轮询间隔秒数，默认 60
  HTTP_PORT     内置下载页端口，默认 0（不启用）；傻瓜版 compose 里是 8000
  ONCE          设为 1 时只同步一次就退出（测试用）
"""

import functools
import hashlib
import html
import http.server
import json
import os
import re
import sys
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime
from string import Template

OUT_DIR = os.environ.get("OUT_DIR", "/data")
SRC_MANIFEST = os.environ.get("SRC_MANIFEST", "")
INTERVAL = max(10, int(os.environ.get("INTERVAL", "60")))
HTTP_PORT = int(os.environ.get("HTTP_PORT", "0") or "0")
ONCE = os.environ.get("ONCE", "") == "1"
UA = "FX-NasAnchor/2.0"
SAFE_NAME = re.compile(r"^[A-Za-z0-9_.-]+$")

CARD = Template("""  <div class="card">
    <div class="top">
      <div class="icon $theme">$icon</div>
      <div>
        <h2>$app <span>v$version</span></h2>
        <div class="sub">$subtitle</div>
      </div>
    </div>
    <a class="btn $theme" href="$file" download="$file">下载安装包（$size_mb MB）</a>
    <a class="btn ghost" href="$file">备用直链（浏览器直存）</a>
    <div class="meta">Android 5.0+ · 包名 <code>$package</code> · versionCode $vcode</div>
    <h3>能做什么</h3>
    <ul>$features</ul>
    $links
  </div>
""")

PAGE = Template("""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>枫兮 · 应用下载</title>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:#0e1116;color:#e8ecf2;min-height:100vh;
 font:15px/1.8 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;
 padding:24px 16px calc(40px + env(safe-area-inset-bottom))}
.wrap{max-width:560px;margin:0 auto}
.hd{text-align:center;margin-bottom:22px}
.hd h1{font-size:23px;margin:0 0 4px;letter-spacing:.5px}
.hd p{color:#8b96a8;font-size:13.5px;margin:0}
.state{display:block;text-align:center;padding:6px 12px;border-radius:20px;font-size:12.5px;
 margin:0 auto 16px;width:fit-content}
.state.ok{background:#123024;border:1px solid #2a6b4b;color:#8fe3b4}
.state.warn{background:#2b2012;border:1px solid #6b4a1f;color:#ffd79a}
.card{background:#171c24;border:1px solid #262e3a;border-radius:18px;padding:22px 20px;
 margin-bottom:16px;box-shadow:0 10px 34px rgba(0,0,0,.4)}
.top{display:flex;align-items:center;gap:14px;margin-bottom:6px}
.icon{flex:0 0 58px;width:58px;height:58px;border-radius:16px;display:flex;
 align-items:center;justify-content:center;font-size:25px;font-weight:700;color:#fff}
.icon.v,.icon.a{background:linear-gradient(140deg,#3b82f6,#7c3aed);box-shadow:0 6px 18px rgba(80,110,255,.3)}
.icon.t,.icon.b{background:linear-gradient(140deg,#0ea5a4,#3b82f6);box-shadow:0 6px 18px rgba(14,165,164,.3)}
.top h2{font-size:20px;margin:0 0 2px}
.top h2 span{font-size:13px;color:#8b96a8;font-weight:400}
.top .sub{color:#8b96a8;font-size:12.5px;line-height:1.6}
.btn{display:block;width:100%;text-align:center;text-decoration:none;
 background:linear-gradient(135deg,#3b82f6,#6366f1);color:#fff;font-size:16.5px;font-weight:600;
 padding:15px;border-radius:13px;box-shadow:0 6px 20px rgba(59,130,246,.35);margin:16px 0 0}
.btn.t,.btn.b{background:linear-gradient(135deg,#0ea5a4,#3b82f6);box-shadow:0 6px 20px rgba(14,165,164,.3)}
.btn:active{transform:translateY(1px);opacity:.92}
.btn.ghost{background:#1d2531;color:#a9c4e8;box-shadow:none;border:1px solid #2c3a4d;
 font-size:13.5px;padding:11px;margin-top:9px}
.meta{font-size:12px;color:#7d8899;margin-top:11px;line-height:1.9}
.meta code{color:#b9c7d8;font-size:11.5px}
h3{font-size:13px;color:#9fd8ff;margin:18px 0 9px;letter-spacing:.3px;font-weight:600}
ul{padding-left:19px;margin:0;font-size:13.5px;color:#a8b3c2}
li{margin:6px 0}li b{color:#d7e2ee;font-weight:600}
.extra{margin-top:14px;padding-top:12px;border-top:1px solid #232b37;font-size:12.5px;color:#8b96a8}
a.link{color:#7fd1ff;text-decoration:none}
h2.sec{font-size:16px;color:#9fd8ff;margin:0 0 14px;border-left:4px solid #3b82f6;padding-left:10px}
ol{padding-left:20px;margin:0;font-size:13.5px;color:#a8b3c2}ol li{margin:7px 0}
.warnbox{background:#241c10;border:1px solid #57401c;border-radius:10px;color:#f0d09a;
 padding:12px 14px;font-size:13px;margin-top:14px}
.warnbox b{color:#ffdca6}
details{border-top:1px solid #232b37;padding:12px 0 0;margin-top:14px}
summary{cursor:pointer;color:#9fd8ff;font-size:13px;font-weight:600}
.kv{width:100%;border-collapse:collapse;font-size:12px;margin-top:10px}
.kv td{padding:6px 4px;border-bottom:1px solid #232b37;vertical-align:top}
.kv tr:last-child td{border-bottom:0}
.kv td.k{color:#8792a4;width:26%}.kv td.v{color:#c6d3e2;word-break:break-all}
.kv code{color:#9fd8ff;font-size:11.5px}
.foot{text-align:center;color:#5c6675;font-size:11.5px;margin-top:26px;line-height:2}
</style>
</head>
<body>
<div class="wrap">
  <div class="hd">
    <h1>枫兮 · 应用下载</h1>
    <p>点按钮直接下载安装 · 安卓手机 / 车机通用</p>
  </div>
  <span class="state $state_cls">$state_text</span>
$cards
  <div class="card">
    <h2 class="sec">安装步骤</h2>
    <ol>
      <li>点上面的下载按钮，浏览器把 APK 存进「下载」目录（提示风险时选「仍要下载」）</li>
      <li>到「文件管理 → 下载」找到 APK，点开用「包安装程序」安装</li>
      <li>弹出「未知来源」时允许本浏览器安装应用</li>
      <li>影视：打开后填飞牛地址或导入 TVBox 订阅；听书：登录网盘后粘贴分享链接即可</li>
    </ol>
    <div class="warnbox"><b>安装提示</b>：两个包都是 targetSdk 28（为放行局域网明文 HTTP），新系统可能提示「为旧版 Android 打造」，属正常，继续安装即可。若点 APK 被别的 App 抢开，去 设置 → 应用管理 → 找到抢注 <code>.apk</code> 的 App → 清除默认值。</div>
    <details>
      <summary>文件校验（可选）</summary>
      <table class="kv">$checksums</table>
    </details>
  </div>
  <div class="foot">仅供个人使用 · 枫兮影视 / 枫兮听书<br>由 NAS 锚点自动同步 · $synced_at</div>
</div>
</body>
</html>
""")


def log(msg):
    print("[%s] %s" % (datetime.now().strftime("%Y-%m-%d %H:%M:%S"), msg), flush=True)


class DownloadHandler(http.server.SimpleHTTPRequestHandler):
    """内置静态服务：APK / js 强制 attachment 下载，页面禁用缓存。"""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=OUT_DIR, **kwargs)

    def end_headers(self):
        path = self.path.split("?", 1)[0].lower()
        if path.endswith((".apk", ".js", ".zip", ".bin")):
            self.send_header("Content-Disposition", "attachment")
            self.send_header("Cache-Control", "no-store")
        elif path.endswith("index.html") or path in ("/", ""):
            self.send_header("Cache-Control", "no-store, must-revalidate")
        super().end_headers()

    def log_message(self, fmt, *args):
        pass


def ensure_waiting_page():
    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, "index.html")
    if os.path.isfile(out):
        return
    with open(out + ".tmp", "w", encoding="utf-8") as f:
        f.write("<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>"
                "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                "<meta http-equiv='refresh' content='3'><title>正在同步…</title>"
                "<style>body{margin:0;min-height:100vh;display:flex;align-items:center;"
                "justify-content:center;background:#0e1116;color:#e8ecf2;font:16px/1.8 "
                "-apple-system,'PingFang SC',sans-serif;text-align:center;padding:24px}"
                "b{color:#8fe3b4}</style></head><body><div><h2>正在从更新源同步安装包…</h2>"
                "<p>首次启动约需几秒到一分钟，本页会自动刷新。</p>"
                "<p style='color:#8b96a8;font-size:13px'>若长时间停在这里，"
                "用 <b>docker logs fx-download</b> 查看原因。</p></div></body></html>")
    os.replace(out + ".tmp", out)


def start_http():
    if HTTP_PORT <= 0:
        return
    ensure_waiting_page()
    srv = http.server.ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), DownloadHandler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, name="http", daemon=True).start()
    log("内置下载页已监听 0.0.0.0:%d" % HTTP_PORT)


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


def asset_url(m, name):
    url = "%s/%s" % (m["_origin"], urllib.parse.quote(name))
    if m["_query"]:
        url += "?" + m["_query"]
    return url


def get_apps(m):
    """支持两种 manifest：v2 的 apps 数组，或 v1 的顶层单 App。"""
    apps = m.get("apps")
    if isinstance(apps, list) and apps:
        return [dict(a) for a in apps]
    return [{
        "app": m.get("app", "App"), "package": m.get("package", ""),
        "version": m.get("version", "?"), "versionCode": m.get("versionCode", "?"),
        "file": m.get("file", ""), "size": m.get("size", 0), "md5": m.get("md5", ""),
        "subtitle": m.get("notes", ""), "icon": "▶", "theme": "v",
        "features": [], "links": [],
    }]


def download_and_verify(url, target, expect_md5, expect_size):
    tmp = target + ".part"
    h = hashlib.md5()
    total = 0
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=180) as r, open(tmp, "wb") as f:
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


def sync_file(m, name, expect_md5, expect_size):
    """下载单个文件；已是最新则跳过。返回 (是否就绪, 说明)。"""
    if not name or not SAFE_NAME.match(name):
        raise RuntimeError("文件名不合法：%r" % name)
    target = os.path.join(OUT_DIR, name)
    md5 = str(expect_md5 or "").lower()
    if (os.path.isfile(target) and md5
            and md5_of(target) == md5
            and os.path.getsize(target) == int(expect_size or 0)):
        return True, "已是最新"
    url = asset_url(m, name)
    log("下载 %s（%s 字节）" % (name, expect_size))
    got, size = download_and_verify(url, target, md5, expect_size)
    return True, "同步完成 %d 字节 md5 %s" % (size, got)


def scan_local_apks():
    """源不可达时，用本机已有的 APK 生成页面（找不到就返回空）。"""
    found = []
    for n in sorted(os.listdir(OUT_DIR)):
        if not n.lower().endswith(".apk") or not SAFE_NAME.match(n):
            continue
        p = os.path.join(OUT_DIR, n)
        if not os.path.isfile(p):
            continue
        ver = re.search(r"v(\d+(?:\.\d+)+)", n)
        is_tingshu = "tingshu" in n.lower()
        found.append({
            "app": "枫兮听书" if is_tingshu else "枫兮影视",
            "package": "com.tingshu.app" if is_tingshu else "com.fengxi.fnplayer",
            "version": ver.group(1) if ver else "?", "versionCode": "?",
            "file": n, "size": os.path.getsize(p), "md5": md5_of(p),
            "subtitle": "本机文件（更新源暂不可达）",
            "icon": "听" if is_tingshu else "影",
            "theme": "t" if is_tingshu else "v",
            "features": [], "links": [],
        })
    return found


def rich_text(s):
    """先整体转义，再只放行 <b>/</b> 白名单，其余一律当纯文本。"""
    e = html.escape(str(s))
    return e.replace("&lt;b&gt;", "<b>").replace("&lt;/b&gt;", "</b>")


def render_page(apps, ok, note=""):
    cards, checks = [], []
    for i, a in enumerate(apps):
        size = int(a.get("size") or 0)
        features = "".join(
            "<li>%s</li>" % rich_text(x) for x in (a.get("features") or []))
        links = ""
        for lk in (a.get("links") or []):
            href, text = str(lk.get("href", "")), str(lk.get("text", ""))
            if SAFE_NAME.match(href):
                links += ('<div class="extra">附：<a class="link" href="%s" download="%s">%s</a></div>'
                          % (html.escape(href, quote=True), html.escape(href, quote=True),
                             html.escape(text)))
        theme = a.get("theme") or ("v" if i % 2 == 0 else "t")
        vals = {
            "app": html.escape(str(a.get("app", "App"))),
            "version": html.escape(str(a.get("version", "?"))),
            "vcode": html.escape(str(a.get("versionCode", "?"))),
            "package": html.escape(str(a.get("package", ""))),
            "subtitle": html.escape(str(a.get("subtitle", ""))),
            "icon": html.escape(str(a.get("icon") or "▶")),
            "theme": theme if theme in ("v", "t", "a", "b") else "v",
            "file": html.escape(str(a.get("file", "")), quote=True),
            "size_mb": "%.1f" % (size / 1024.0 / 1024.0),
            "features": features or "<li>安装包已就绪，点上方按钮下载</li>",
            "links": links,
        }
        cards.append(CARD.substitute(vals))
        checks.append(
            "<tr><td class='k'>%s v%s</td><td class='v'>%s 字节 · MD5 <code>%s</code></td></tr>"
            % (vals["app"], vals["version"], format(size, ","),
               html.escape(str(a.get("md5", "")))))

    if not cards:
        return
    out = os.path.join(OUT_DIR, "index.html")
    tmp = out + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(PAGE.substitute({
            "cards": "\n".join(cards),
            "checksums": "".join(checks),
            "state_cls": "ok" if ok else "warn",
            "state_text": "● 已同步最新版本" if ok else "● 源暂不可达，显示本机文件",
            "synced_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        }))
    os.replace(tmp, out)


def sync_once():
    os.makedirs(OUT_DIR, exist_ok=True)
    try:
        m = fetch_manifest()
    except Exception as e:
        log("拉取 manifest 失败：%s" % e)
        local = scan_local_apks()
        if local:
            render_page(local, False, str(e))
            log("已用本机 %d 个 APK 生成下载页" % len(local))
        else:
            log("本机也没有 APK，暂无下载页")
        return

    apps = get_apps(m)
    ok = True
    for a in apps:
        name = str(a.get("file", ""))
        if not name.lower().endswith(".apk"):
            log("跳过非法条目：%r" % name)
            ok = False
            continue
        try:
            ready, msg = sync_file(m, name, a.get("md5", ""), a.get("size", 0))
            if not ready:
                ok = False
            elif msg != "已是最新":
                log("%s：%s" % (name, msg))
        except Exception as e:
            log("%s 下载/校验失败，保留旧版本：%s" % (name, e))
            ok = False

    # 附加资源（如短剧源脚本），失败不影响主流程
    for x in (m.get("assets") or []):
        fname = str(x.get("file", ""))
        if not fname or not SAFE_NAME.match(fname) or fname.lower().endswith(".apk"):
            continue
        try:
            sync_file(m, fname, x.get("md5", ""), x.get("size", 0))
        except Exception as e:
            log("附加资源 %s 失败（忽略）：%s" % (fname, e))

    # 只展示本机确实存在的 APK，避免给出 404 按钮
    shown = [a for a in apps if os.path.isfile(os.path.join(OUT_DIR, str(a.get("file", ""))))]
    if not shown:
        log("没有任何可用 APK，暂不生成下载页")
        return
    render_page(shown, ok)
    log("页面就绪：%d 个 App（%s）" % (
        len(shown), "全部最新" if ok else "部分使用旧版本"))


def main():
    log("FX 下载页锚点 v2 启动（多 App）")
    log("  源：%s" % (SRC_MANIFEST or "（未配置）"))
    log("  输出：%s   间隔：%ds   内置网页：%s" % (
        OUT_DIR, INTERVAL, ("端口 %d" % HTTP_PORT) if HTTP_PORT > 0 else "关闭"))
    if not SRC_MANIFEST:
        log("!! 必须设置 SRC_MANIFEST，退出")
        sys.exit(1)
    if not ONCE:
        start_http()
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
