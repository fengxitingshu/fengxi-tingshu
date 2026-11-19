#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FX 内网一键发布 v3 —— 取代「NAS 锚点 + 外部会话级发布站」那套链路。

数据源只有本地 manifest.json；下载页 index.html 由它渲染，APK 直接打进下载站目录。
三者通过内网 WebDAV 推到 NAS 的 8099 静态目录，推完即生效，没有任何后台进程改写。

常用命令（在仓库根目录执行）：
  # 只更新本机页面 / manifest（不联网，调试用）
  python _backup/download-site/publish.py --no-upload

  # 发布本机已有的最新 APK
  NAS_USER=xxx NAS_PASS=xxx python _backup/download-site/publish.py

  # 构建新版 + 发布一条龙（Windows 主开发环境）
  NAS_USER=xxx NAS_PASS=xxx python _backup/download-site/publish.py --build

  # 把 NAS 容器切成纯静态托管（一次性，需 fnOS SDK；会先备份原文件）
  NAS_USER=xxx NAS_PASS=xxx python _backup/download-site/publish.py --deploy-static

  # 只清理旧版本（不发布）：本地 + NAS 每 App 只留最近 3 版，更早的 APK 自动删
  python _backup/download-site/publish.py --prune
  python _backup/download-site/publish.py --prune --keep 5   # 改成各留 5 版

版本保留：每次发布完会自动把本地与 NAS 上「同一 App、版本号比第 3 新更老」的
APK 安装包删掉（按 App 分组，听书 / 影视各自独立计版本），页面与清单不受影响。

凭据优先级：--user/--pass > 环境变量 NAS_USER/NAS_PASS > Windows 凭据管理器
（generic 目标 FX-NAS-WEBDAV）。凭据只在进程内存中使用，不写文件、不打印、不入库。
对外文件、日志、页面零域名回显。
"""
import argparse
import base64
import ctypes
import hashlib
import html
import http.client
import json
import os
import re
import shutil
import ssl
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime
from string import Template

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.abspath(os.path.join(HERE, "..", ".."))
MANIFEST = os.path.join(HERE, "manifest.json")
PAGE = os.path.join(HERE, "index.html")
COMPOSE = os.path.join(HERE, "docker-compose.static.yml")
DAV_NS = "{DAV:}"
UA = "FX-Publish/3.0"
CRED_TARGET = "FX-NAS-WEBDAV"
DEFAULT_REMOTE = "/docker/FX-NAS锚点-傻瓜版/web"
DEFAULT_PROJECT = "/docker/FX-NAS锚点-傻瓜版"
TINGSHU_PKG = "com.tingshu.app"


def log(msg):
    print(msg, flush=True)


def md5_of(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        while True:
            b = f.read(1024 * 1024)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def md5_bytes(data):
    return hashlib.md5(data).hexdigest()


def q(path):
    return urllib.parse.quote(path, safe="/~!$&'()*+,;=:@")


def now():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


# ---------------------------------------------------------------- 凭据

def win_saved_cred():
    """从 Windows 凭据管理器读 generic 凭据；不存在返回空。"""
    if sys.platform != "win32":
        return "", ""
    try:
        from ctypes import wintypes

        class CREDENTIAL(ctypes.Structure):
            _fields_ = [
                ("Flags", wintypes.DWORD), ("Type", wintypes.DWORD),
                ("TargetName", wintypes.LPWSTR), ("Comment", wintypes.LPWSTR),
                ("LastWritten", wintypes.FILETIME),
                ("CredentialBlobSize", wintypes.DWORD),
                ("CredentialBlob", ctypes.POINTER(ctypes.c_char)),
                ("AttributeCount", wintypes.DWORD),
                ("Attributes", ctypes.c_void_p),
                ("TargetAlias", wintypes.LPWSTR),
                ("UserName", wintypes.LPWSTR),
            ]

        adv = ctypes.WinDLL("advapi32", use_last_error=True)
        adv.CredReadW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD,
                                  wintypes.DWORD,
                                  ctypes.POINTER(ctypes.POINTER(CREDENTIAL))]
        adv.CredReadW.restype = wintypes.BOOL
        ptr = ctypes.POINTER(CREDENTIAL)()
        if not adv.CredReadW(CRED_TARGET, 1, 0, ctypes.byref(ptr)):
            return "", ""
        try:
            user = ptr.contents.UserName or ""
            raw = ctypes.string_at(ptr.contents.CredentialBlob,
                                   ptr.contents.CredentialBlobSize)
            try:
                password = raw.decode("utf-16-le")
            except UnicodeDecodeError:
                password = raw.decode("utf-8", "ignore")
            return user, password.rstrip("\x00")
        finally:
            adv.CredFree(ptr)
    except Exception:
        return "", ""


def get_cred(args):
    user = args.user or os.environ.get("NAS_USER", "")
    password = args.password or os.environ.get("NAS_PASS", "")
    if not (user and password):
        user, password = win_saved_cred()
        if user and password:
            log("[凭据] 已从 Windows 凭据管理器读取（不回显）")
    return user, password


def save_cred(args):
    """可选：把凭据加密存进 Windows 凭据管理器，以后发布免输密码。"""
    if sys.platform != "win32":
        log("!! 只有 Windows 支持凭据管理器")
        return 1
    # cmdkey 在中文 Windows 上输出 GBK，不按文本解码，只看退出码
    r = subprocess.run(["cmdkey", "/generic:%s" % CRED_TARGET,
                        "/user:%s" % args.user, "/pass:%s" % args.password],
                       capture_output=True)
    log("[凭据] 保存%s（目标 %s；随时可用 cmdkey /delete:%s 删除）"
        % ("成功" if r.returncode == 0 else "失败", CRED_TARGET, CRED_TARGET))
    return 0 if r.returncode == 0 else 1


# ---------------------------------------------------------------- WebDAV

class Dav:
    def __init__(self, host, port, https, user, password, timeout=120):
        self.host, self.port, self.https = host, port, https
        self.timeout = timeout
        self.auth = "Basic " + base64.b64encode(
            ("%s:%s" % (user, password)).encode("utf-8")).decode("ascii")
        self.conn = None

    def _connect(self):
        if self.conn is None:
            if self.https:
                ctx = ssl.create_default_context()
                ctx.check_hostname = False
                ctx.verify_mode = ssl.CERT_NONE
                self.conn = http.client.HTTPSConnection(
                    self.host, self.port, timeout=self.timeout, context=ctx)
            else:
                self.conn = http.client.HTTPConnection(
                    self.host, self.port, timeout=self.timeout)
        return self.conn

    def request(self, method, path, body=None, extra=None):
        headers = {"Authorization": self.auth, "User-Agent": UA}
        if extra:
            headers.update(extra)
        for attempt in (0, 1):
            c = self._connect()
            try:
                c.request(method, q(path), body=body, headers=headers)
                r = c.getresponse()
                data = r.read()
                return r.status, dict(r.getheaders()), data
            except (http.client.HTTPException, OSError):
                self.conn = None
                if attempt:
                    raise
        return 0, {}, b""

    def propfind(self, path):
        st, _, body = self.request(
            "PROPFIND", path,
            extra={"Depth": "1", "Content-Type": "application/xml"})
        if st != 207:
            return st, []
        out = []
        try:
            for resp in ET.fromstring(body).iter(DAV_NS + "response"):
                href = resp.findtext(DAV_NS + "href") or ""
                p = urllib.parse.unquote(urllib.parse.urlsplit(href).path)
                isdir = resp.find(DAV_NS + "propstat/" + DAV_NS + "prop/" +
                                  DAV_NS + "resourcetype/" + DAV_NS +
                                  "collection") is not None
                out.append((p.rstrip("/") or "/", isdir))
        except ET.ParseError:
            pass
        return st, out

    def get(self, path):
        return self.request("GET", path)

    def put_file(self, path, local):
        with open(local, "rb") as f:
            data = f.read()
        return self.request("PUT", path, body=data,
                            extra={"Content-Type": "application/octet-stream",
                                   "Content-Length": str(len(data))})[0]

    def put_bytes(self, path, data):
        return self.request("PUT", path, body=data,
                            extra={"Content-Type": "application/octet-stream",
                                   "Content-Length": str(len(data))})[0]

    def exists_with_md5(self, path, expect_md5, expect_size):
        st, _, body = self.get(path)
        return (st == 200 and len(body) == expect_size
                and md5_bytes(body) == expect_md5)


def locate_remote(dav, preferred):
    if preferred:
        st, items = dav.propfind(preferred)
        if st == 207:
            return preferred.rstrip("/")
    # 兜底：从 WebDAV 根 BFS 找同时含 manifest.json 与 fengxi-*.apk 的目录
    queue, seen = [("/", 0)], set()
    while queue and len(seen) < 200:
        d, dep = queue.pop(0)
        if d in seen:
            continue
        seen.add(d)
        st, items = dav.propfind(d)
        if st != 207:
            continue
        names = [p.rsplit("/", 1)[-1] for p, _ in items if p != d]
        if "manifest.json" in names and any(n.startswith("fengxi-") and
                                            n.endswith(".apk") for n in names):
            return d
        if dep < 5:
            for p, isdir in items:
                if isdir and p != d:
                    queue.append((p, dep + 1))
    log("!! 没定位到 NAS 下载站目录，请用 --remote 指定")
    return ""


def push(dav, remote, local, name, skip_if_same=True):
    size, md5 = os.path.getsize(local), md5_of(local)
    dest = remote + "/" + name
    if skip_if_same and dav.exists_with_md5(dest, md5, size):
        log("[跳过] %s  NAS 上已是同一份（%d 字节）" % (name, size))
        return True
    log("[上传] %s  %d 字节  md5 %s" % (name, size, md5))
    st = dav.put_file(dest, local)
    if st not in (200, 201, 204):
        log("!! 上传失败 HTTP %d" % st)
        return False
    st, _, body = dav.get(dest)
    if st != 200 or md5_bytes(body) != md5:
        log("!! 回读校验失败：HTTP %d，%d 字节" % (st, len(body)))
        return False
    log("       HTTP %d · 回读 MD5 一致" % st)
    return True


# ---------------------------------------------------------------- 版本信息

def apk_identity(path):
    """返回 (version, versionCode, package)。优先 aapt2 badging，回退源码 Manifest。"""
    version = vcode = package = ""
    candidates = [os.environ.get("AAPT2", ""),
                  r"D:\fengxi-dev\android\build-tools\34\aapt2.exe",
                  "/root/android/android-14/aapt2"]
    for exe in candidates:
        if not exe or not os.path.isfile(exe):
            continue
        try:
            r = subprocess.run([exe, "dump", "badging", path],
                               capture_output=True, text=True, timeout=40,
                               encoding="utf-8", errors="replace")
            first = (r.stdout or "").splitlines()[0] if r.stdout else ""
            m = re.search(r"versionName='([^']+)'", first)
            version = m.group(1) if m else ""
            m = re.search(r"versionCode='(\d+)'", first)
            vcode = int(m.group(1)) if m else 0
            m = re.search(r"name='([^']+)'", first)
            package = m.group(1) if m else ""
            if version and vcode:
                return version, vcode, package
        except Exception:
            pass
    # 回退：源码 Manifest + 文件名
    mf = os.path.join(PROJ, "AndroidManifest.xml")
    if os.path.isfile(mf):
        txt = open(mf, encoding="utf-8").read()
        m = re.search(r'android:versionName="([^"]+)"', txt)
        version = m.group(1) if m else version
        m = re.search(r'android:versionCode="(\d+)"', txt)
        vcode = int(m.group(1)) if m else vcode
        m = re.search(r'package="([^"]+)"', txt)
        package = m.group(1) if m else package
    if not version:
        m = re.search(r"v(\d+(?:\.\d+)+)", os.path.basename(path))
        version = m.group(1) if m else "?"
    return version, vcode, package or TINGSHU_PKG


def build_apk():
    """本机一键构建，产物复制到下载站并按版本命名。"""
    entry = os.path.join(PROJ, ".fengxi-env", "build-here.sh")
    cmd = ["bash", entry] if os.path.isfile(entry) else ["bash", "build.sh"]
    log("[构建] %s" % " ".join(cmd))
    r = subprocess.run(cmd, cwd=PROJ)
    if r.returncode != 0:
        raise SystemExit("!! 构建失败，已中止发布")
    src = os.path.join(PROJ, "TingShu.apk")
    if not os.path.isfile(src):
        raise SystemExit("!! 没找到签名产物 TingShu.apk（证书是否在位？）")
    ver, vcode, pkg = apk_identity(src)
    dst = os.path.join(HERE, "fengxi-tingshu-v%s.apk" % ver)
    shutil.copy2(src, dst)
    log("[构建] v%s / code %s → %s" % (ver, vcode, os.path.basename(dst)))
    return dst


def latest_local_apk():
    apks = [os.path.join(HERE, n) for n in os.listdir(HERE)
            if n.startswith("fengxi-tingshu-") and n.endswith(".apk")]
    return max(apks, key=os.path.getmtime) if apks else ""


# ---------------------------------------------------------------- 版本保留（每 App 只留最近 N 版）

# 匹配 fengxi-tingshu-v4.21.47.apk / fengxi-video-v2.22.apk
_VERSION_RE = re.compile(r"^(.*)-v(\d+(?:\.\d+)*)\.apk$", re.IGNORECASE)
# 绝不动的文件：当前构建产物 / 页面 / 清单 / 脚本（不是历史版本安装包）
_PROTECT = ("TingShu.apk", "index.html", "manifest.json", "anchor.py",
            "publish.py", "docker-compose.static.yml")


def ver_tuple(v):
    """'4.21.47' -> (4,21,47)；解析失败回退空元组（排最后，最先被清）。"""
    try:
        return tuple(int(x) for x in v.split("."))
    except Exception:
        return ()


def group_by_app(names):
    """按 App 前缀分组：-v<ver>.apk 之前的那段作为分组键。"""
    groups = {}
    for n in names:
        m = _VERSION_RE.match(n)
        if not m:
            continue
        groups.setdefault(m.group(1), []).append((m.group(2), n))
    return groups


def to_delete(names, keep=3):
    """返回应删除的文件名集合：每个 App 只保留版本号最大的 keep 个。"""
    drop = set()
    if keep < 0:
        keep = 0
    for prefix, items in group_by_app(names).items():
        # 按版本号降序排，前 keep 个保留，其余删
        items.sort(key=lambda t: ver_tuple(t[0]), reverse=True)
        for _, name in items[keep:]:
            drop.add(name)
    return drop


def prune_local(keep=3):
    names = [n for n in os.listdir(HERE)
             if n.lower().endswith(".apk") and n not in _PROTECT]
    drop = to_delete(names, keep)
    if not drop:
        log("[清理] 本地无需删除（每 App 都已 ≤ %d 版）" % keep)
        return
    for n in sorted(drop):
        try:
            os.remove(os.path.join(HERE, n))
            log("[清理] 本地删除旧包 %s" % n)
        except OSError as e:
            log("!! 本地删除失败 %s：%s" % (n, e))
    kept = sorted(set(names) - drop)
    log("[清理] 本地保留：%s" % (", ".join(kept) if kept else "(无)"))


def prune_remote(dav, remote, keep=3):
    st, items = dav.propfind(remote)
    if st != 207:
        log("!! 远程清理失败：PROPFIND %s HTTP %d" % (remote, st))
        return
    names = [p.rsplit("/", 1)[-1] for p, isdir in items
             if not isdir and p.rsplit("/", 1)[-1].lower().endswith(".apk")]
    drop = to_delete(names, keep)
    if not drop:
        log("[清理] NAS 无需删除（每 App 都已 ≤ %d 版）" % keep)
        return
    for n in sorted(drop):
        dest = remote + "/" + n
        s, _, _ = dav.request("DELETE", dest)
        if s in (200, 204):
            log("[清理] NAS 删除旧包 %s" % n)
        else:
            log("!! NAS 删除失败 %s：HTTP %d" % (n, s))
    kept = sorted(set(names) - drop)
    log("[清理] NAS 保留：%s" % (", ".join(kept) if kept else "(无)"))


def update_manifest(apk, args):
    with open(MANIFEST, encoding="utf-8") as f:
        data = json.load(f)
    ver, vcode, pkg = apk_identity(apk)
    apps = data.get("apps")
    if not isinstance(apps, list):
        apps = []
    target = next((a for a in apps if a.get("package") == pkg), None)
    if target is None:
        target = {"app": "枫兮听书", "package": pkg, "icon": "听", "theme": "t",
                  "subtitle": "网盘有声书 · 解析转存 · 缓存中继 · 后台播放",
                  "features": [], "links": []}
        apps.append(target)
    target.update({
        "version": ver, "versionCode": vcode,
        "file": os.path.basename(apk),
        "size": os.path.getsize(apk), "md5": md5_of(apk),
        "updated": now(),
    })
    if args.notes:
        target["notes"] = args.notes
    data["apps"] = apps
    head = apps[0]
    for k in ("app", "package", "version", "versionCode", "file", "size", "md5"):
        data[k] = head.get(k)
    data["updated"] = now()
    tmp = MANIFEST + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, MANIFEST)
    log("[清单] %s v%s / code %s · %d 字节 · md5 %s" %
        (target.get("app"), ver, vcode, target["size"], target["md5"]))
    return data


# ---------------------------------------------------------------- 页面渲染

CSS = """*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;padding:0}
body{background:#0e1116;color:#e8ecf2;min-height:100vh;
 font:15px/1.8 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;
 padding:24px 16px calc(40px + env(safe-area-inset-bottom))}
.wrap{max-width:560px;margin:0 auto}
.hd{text-align:center;margin-bottom:24px}
.hd h1{font-size:23px;margin:0 0 4px;letter-spacing:.5px}
.hd p{color:#8b96a8;font-size:13.5px;margin:0}
.card{background:#171c24;border:1px solid #262e3a;border-radius:18px;
 padding:22px 20px;margin-bottom:16px;box-shadow:0 10px 34px rgba(0,0,0,.4)}
.top{display:flex;align-items:center;gap:14px;margin-bottom:6px}
.icon{flex:0 0 58px;width:58px;height:58px;border-radius:16px;display:flex;
 align-items:center;justify-content:center;font-size:25px;font-weight:700;color:#fff}
.icon.v{background:linear-gradient(140deg,#3b82f6,#7c3aed);box-shadow:0 6px 18px rgba(80,110,255,.3)}
.icon.t{background:linear-gradient(140deg,#0ea5a4,#3b82f6);box-shadow:0 6px 18px rgba(14,165,164,.3)}
.top h2{font-size:20px;margin:0 0 2px}
.top h2 span{font-size:13px;color:#8b96a8;font-weight:400}
.top .sub{color:#8b96a8;font-size:12.5px;line-height:1.6}
.btn{display:block;width:100%;text-align:center;text-decoration:none;
 background:linear-gradient(135deg,#3b82f6,#6366f1);color:#fff;font-size:17px;
 font-weight:700;letter-spacing:.3px;padding:16px;border-radius:13px;
 box-shadow:0 6px 20px rgba(59,130,246,.35);margin:16px 0 0}
.btn.t{background:linear-gradient(135deg,#0ea5a4,#3b82f6);box-shadow:0 6px 20px rgba(14,165,164,.3)}
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
/* 折叠区：默认收起，首屏只留两个软件 + 两个大按钮 */
details.fold{margin-top:14px;padding-top:12px;border-top:1px solid #232b37}
details.fold>summary{cursor:pointer;color:#9fd8ff;font-size:13px;font-weight:600;
 list-style:none;display:flex;align-items:center;gap:8px}
details.fold>summary::-webkit-details-marker{display:none}
details.fold>summary::before{content:"▸";font-size:12px;color:#6f9fc9}
details.fold[open]>summary::before{content:"▾"}
details.fold>summary .cnt{color:#7d8899;font-weight:400;font-size:12px}
details.card.fold{margin-top:0;padding-top:16px;border-top:0}
details.fold ul{margin-top:10px}
.kv{width:100%;border-collapse:collapse;font-size:12px;margin-top:10px}
.kv td{padding:6px 4px;border-bottom:1px solid #232b37;vertical-align:top}
.kv tr:last-child td{border-bottom:0}
.kv td.k{color:#8792a4;width:26%}.kv td.v{color:#c6d3e2;word-break:break-all}
.kv code{color:#9fd8ff;font-size:11.5px}
.foot{text-align:center;color:#5c6675;font-size:11.5px;margin-top:26px;line-height:2}"""

CARD = Template("""  <div class="card">
    <div class="top">
      <div class="icon $theme">$icon</div>
      <div>
        <h2>$app <span>v$version</span></h2>
        <div class="sub">$subtitle</div>
      </div>
    </div>
    <a class="btn $theme" href="$file">下载 $app · $size_mb MB</a>
    <a class="btn ghost" href="$file">备用直链（点这个也能下）</a>
    $links
    <details class="fold">
      <summary>版本更新与功能说明 <span class="cnt">共 $feature_count 条 · 点开看</span></summary>
      <ul>$features</ul>
      <div class="meta">Android 5.0+ · 包名 <code>$package</code> · versionCode $vcode</div>
    </details>
  </div>
""")

PAGE_TPL = Template("""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>枫兮 · 应用下载</title>
<style>
$css
</style>
</head>
<body>
<div class="wrap">
  <div class="hd">
    <h1>枫兮 · 应用下载</h1>
    <p>两个应用 · 点下面的大按钮直接下载安装 · 安卓手机 / 车机通用</p>
  </div>
$cards
  <details class="card fold">
    <summary>安装步骤与文件校验 <span class="cnt">点开看</span></summary>
    <ol>
      <li>点上面的下载按钮，APK 存进「下载」目录（提示风险时选「仍要下载」）</li>
      <li>到「文件管理 → 下载」点开 APK，用「包安装程序」安装</li>
      <li>弹出「未知来源」时允许本浏览器安装应用</li>
      <li>影视：打开后填 NAS 地址或导入订阅；听书：登录网盘后粘贴分享链接</li>
    </ol>
    <div class="warnbox"><b>安装提示</b>：两个包都是 targetSdk 28（为放行局域网明文 HTTP），新系统可能提示「为旧版 Android 打造」，属正常，继续安装即可。</div>
    <h3>文件校验（可选）</h3>
    <table class="kv">$checksums</table>
  </details>
  <div class="foot">仅供个人使用 · 枫兮影视 / 枫兮听书<br>纯内网直连 NAS · 更新于 $updated</div>
</div>
</body>
</html>
""")


def rich_text(s):
    """整体转义，只放行 <b>；同时保留原本写作 &lt; 的比较符。"""
    t = str(s).replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    e = html.escape(t)
    return e.replace("&lt;b&gt;", "<b>").replace("&lt;/b&gt;", "</b>")


def render_page(data):
    cards, checks = [], []
    for i, a in enumerate(data.get("apps", [])):
        size = int(a.get("size") or 0)
        theme = a.get("theme") or ("v" if i % 2 == 0 else "t")
        if theme not in ("v", "t"):
            theme = "v"
        features = "".join("<li>%s</li>" % rich_text(x)
                           for x in (a.get("features") or []))
        links = ""
        for lk in (a.get("links") or []):
            href, text = str(lk.get("href", "")), str(lk.get("text", ""))
            if re.match(r"^[A-Za-z0-9_.-]+$", href):
                # 不带 download 属性：部分手机浏览器会把它拦成「无反应」，
                # 交给服务端 Content-Disposition: attachment 统一强制下载。
                links += ('<div class="extra">附：<a class="link" href="%s">%s</a></div>'
                          % (html.escape(href, quote=True), html.escape(text)))
        cards.append(CARD.substitute({
            "feature_count": len(a.get("features") or []),
            "app": html.escape(str(a.get("app", "App"))),
            "version": html.escape(str(a.get("version", "?"))),
            "vcode": html.escape(str(a.get("versionCode", "?"))),
            "package": html.escape(str(a.get("package", ""))),
            "subtitle": html.escape(str(a.get("subtitle", ""))),
            "icon": html.escape(str(a.get("icon") or "▶")),
            "theme": theme,
            "file": html.escape(str(a.get("file", "")), quote=True),
            "size_mb": "%.1f" % (size / 1024.0 / 1024.0),
            "features": features or "<li>安装包已就绪，点上方按钮下载</li>",
            "links": links,
        }))
        checks.append(
            "<tr><td class='k'>%s v%s</td><td class='v'>%s 字节 · MD5 <code>%s</code></td></tr>"
            % (html.escape(str(a.get("app", ""))),
               html.escape(str(a.get("version", ""))), format(size, ","),
               html.escape(str(a.get("md5", "")))))
    out = PAGE_TPL.substitute({
        "css": CSS, "cards": "\n".join(cards), "checksums": "".join(checks),
        "updated": data.get("updated", now()),
    })
    tmp = PAGE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(out)
    os.replace(tmp, PAGE)
    log("[页面] index.html 已由 manifest 渲染（%d 个 App）" % len(cards))


# ---------------------------------------------------------------- 部署 / 验证

def deploy_static(dav, args):
    """一次性：把 NAS 容器切成纯静态托管，并重启生效。"""
    project = args.project.rstrip("/")
    log("[切换] 目标项目目录：%s" % project)
    st, _, old_anchor = dav.get(project + "/anchor.py")
    st2, _, old_compose = dav.get(project + "/docker-compose.yml")
    if st != 200 or st2 != 200:
        log("!! 读不到原脚本 / compose（HTTP %d / %d），已中止" % (st, st2))
        return 1
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    for name, blob in (("anchor.py.v2-%s.bak" % stamp, old_anchor),
                       ("docker-compose.yml.pre-static-%s.bak" % stamp, old_compose)):
        r = dav.put_bytes(project + "/" + name, blob)
        log("[备份] %s  HTTP %d（%d 字节）" % (name, r, len(blob)))
        if r not in (200, 201, 204):
            return 1
    with open(os.path.join(HERE, "anchor.py"), "rb") as f:
        new_anchor = f.read()
    with open(COMPOSE, "rb") as f:
        new_compose = f.read()
    for name, blob in (("anchor.py", new_anchor), ("docker-compose.yml", new_compose)):
        r = dav.put_bytes(project + "/" + name, blob)
        log("[写入] %s  HTTP %d（%d 字节）" % (name, r, len(blob)))
        if r not in (200, 201, 204):
            return 1

    ctl = os.path.join(PROJ, ".fengxi-env", "fnos_ctl.py")
    if not os.path.isfile(ctl):
        log("[切换] 未找到本机容器控制工具，请在飞牛 Docker 里手动重启 fx-download")
        return 0
    env = dict(os.environ)
    env["NAS_HOST"] = args.host
    log("[切换] 重启容器 fx-download …")
    r = subprocess.run([sys.executable, ctl, "--restart", "--name", "fx-download"],
                       env=env, capture_output=True, timeout=120)
    for line in (r.stdout or b"").decode("utf-8", "replace").splitlines():
        if "token" not in line.lower() and "secret" not in line.lower():
            log("   " + line)
    if r.returncode != 0:
        log("!! 重启失败（%s），请手动重启后重试"
            % (r.stderr or b"").decode("utf-8", "replace")[:200])
        return 1
    for _ in range(20):
        time.sleep(2)
        try:
            with urllib.request.urlopen("http://%s:%d/" % (args.host, args.page_port),
                                        timeout=4) as resp:
                if resp.status == 200:
                    log("[切换] 8099 已恢复")
                    return 0
        except Exception:
            pass
    log("!! 8099 未恢复，请检查容器日志")
    return 1


def verify(args, data, apk):
    base = "http://%s:%d" % (args.host, args.page_port)
    ok = True
    try:
        with urllib.request.urlopen(base + "/", timeout=10) as r:
            page = r.read().decode("utf-8", "ignore")
    except Exception as e:
        log("!! 首页打不开：%s" % e)
        return False
    for a in data.get("apps", []):
        ver, name = str(a.get("version")), str(a.get("file"))
        hit = ("v" + ver) in page and name in page
        log("[首页] %s v%s %s" % (a.get("app"), ver, "✓" if hit else "✗"))
        ok &= hit
        try:
            req = urllib.request.Request(base + "/" + urllib.parse.quote(name),
                                         method="HEAD")
            with urllib.request.urlopen(req, timeout=15) as r:
                length = int(r.headers.get("Content-Length", "0"))
            good = r.status == 200 and length == int(a.get("size") or 0)
            log("[直链] %s  HTTP %d  %d 字节 %s" %
                (name, r.status, length, "✓" if good else "✗"))
            ok &= good
        except Exception as e:
            log("[直链] %s 失败：%s" % (name, e))
            ok = False
    # 本次主推 APK：流式 MD5，不落盘
    try:
        h = hashlib.md5()
        total = 0
        with urllib.request.urlopen(base + "/" + urllib.parse.quote(os.path.basename(apk)),
                                    timeout=180) as r:
            while True:
                b = r.read(1024 * 1024)
                if not b:
                    break
                h.update(b)
                total += len(b)
        good = (total == os.path.getsize(apk) and h.hexdigest() == md5_of(apk))
        log("[端到端] %d 字节  md5 %s  %s" %
            (total, h.hexdigest(), "✓ 与本地一致" if good else "✗ 不一致"))
        ok &= good
    except Exception as e:
        log("[端到端] 校验失败：%s" % e)
        ok = False
    if args.stable > 0:
        log("[稳定性] 等 %d 秒后再看首页会不会被后台进程改回去…" % args.stable)
        time.sleep(args.stable)
        try:
            with urllib.request.urlopen(base + "/", timeout=10) as r:
                page2 = r.read().decode("utf-8", "ignore")
            newver = ("v" + str(next(a.get("version") for a in data["apps"]
                                     if a.get("package") == TINGSHU_PKG))) in page2
            log("[稳定性] 首页仍是新版：%s" % ("✓" if newver else "✗ 被改回去了"))
            ok &= newver
        except Exception as e:
            log("[稳定性] 复查失败：%s" % e)
            ok = False
    return ok


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser(description="FX 内网一键发布 v3")
    ap.add_argument("--host", default=os.environ.get("NAS_HOST", "192.168.9.20"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("NAS_PORT", "5005")))
    ap.add_argument("--https", action="store_true")
    ap.add_argument("--page-port", type=int, default=8099)
    ap.add_argument("--user", default="")
    ap.add_argument("--pass", dest="password", default="")
    ap.add_argument("--remote", default=DEFAULT_REMOTE, help="NAS 静态目录")
    ap.add_argument("--project", default=DEFAULT_PROJECT, help="NAS compose 项目目录")
    ap.add_argument("--apk", default="", help="指定 APK（默认取本机最新听书包）")
    ap.add_argument("--build", action="store_true", help="先构建再发布")
    ap.add_argument("--notes", default="", help="更新本次版本说明")
    ap.add_argument("--no-upload", action="store_true", help="只生成本地文件")
    ap.add_argument("--deploy-static", action="store_true", help="一次性切换 NAS 为纯静态")
    ap.add_argument("--save-cred", action="store_true", help="把凭据存入 Windows 凭据管理器")
    ap.add_argument("--keep", type=int, default=3, help="每个 App 在 NAS/本地最多保留几个版本（默认 3）")
    ap.add_argument("--prune", action="store_true", help="只执行旧版本清理（不发布）；联网时一并清理 NAS")
    ap.add_argument("--stable", type=int, default=0, help="发布后等 N 秒复查页面不被覆盖")
    args = ap.parse_args()

    if args.save_cred:
        return save_cred(args)

    if args.prune:
        # 独立清理：本地一定清；不带 --no-upload 时联网清 NAS
        prune_local(args.keep)
        if not args.no_upload:
            user, password = get_cred(args)
            if not (user and password):
                log("!! 缺 WebDAV 账号密码：设 NAS_USER/NAS_PASS 或 --save-cred")
                return 3
            dav = Dav(args.host, args.port, args.https, user, password)
            remote = locate_remote(dav, args.remote)
            if not remote:
                return 4
            prune_remote(dav, remote, args.keep)
        return 0

    apk = build_apk() if args.build else (args.apk or latest_local_apk())
    if not apk or not os.path.isfile(apk):
        log("!! 找不到 APK：%s" % (apk or "（下载站目录里没有 fengxi-tingshu-*.apk）"))
        return 2
    log("[发布] %s" % os.path.basename(apk))

    data = update_manifest(apk, args)
    render_page(data)

    if args.no_upload:
        log("[完成] 只生成本地文件（--no-upload），未联网")
        return 0

    user, password = get_cred(args)
    if not (user and password):
        log("!! 缺 WebDAV 账号密码：设 NAS_USER / NAS_PASS，或 --save-cred 存一次")
        return 3
    dav = Dav(args.host, args.port, args.https, user, password)

    if args.deploy_static:
        rc = deploy_static(dav, args)
        if rc:
            return rc

    remote = locate_remote(dav, args.remote)
    if not remote:
        return 4
    log("[目标] %s" % remote)

    files = [("manifest.json", MANIFEST), ("index.html", PAGE),
             (os.path.basename(apk), apk)]
    for name, local in files:
        if not push(dav, remote, local, name, skip_if_same=name.endswith(".apk")):
            return 5

    # ★ 版本保留：发完新版顺手把旧包清掉（本地 + NAS 都只留 keep 版）
    prune_local(args.keep)
    prune_remote(dav, remote, args.keep)

    log("[验证] 从 NAS 侧端到端检查…")
    return 0 if verify(args, data, apk) else 6


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
