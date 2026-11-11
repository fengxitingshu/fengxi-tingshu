#!/usr/bin/env python3
# 枫兮影视下载服务：APK 强制 attachment，忽略 sharecode 查询参数。
import os
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.abspath(__file__))
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8899

# .bin 传输后缀 → 交付时的中文文件名（与下载页按钮的 download 属性一致）
BIN_CN_NAME = {
    "fx-nas-anchor-simple.bin": "FX-NAS锚点-傻瓜版.zip",
    "fx-nas-anchor.bin": "fx-nas-anchor.zip",
    "fx-anchor-v2-upgrade.bin": "FX-锚点v2升级包.zip",
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _serve(self, with_body):
        path = urllib.parse.unquote(urllib.parse.urlparse(self.path).path)
        if path in ("", "/"):
            path = "/index.html"
        full = os.path.abspath(os.path.join(ROOT, path.lstrip("/")))
        if not (full == ROOT or full.startswith(ROOT + os.sep)):
            self.send_error(403)
            return
        if not os.path.isfile(full):
            self.send_error(404)
            return

        name = os.path.basename(full)
        size = os.path.getsize(full)
        if name.endswith(".apk"):
            ctype = "application/vnd.android.package-archive"
        elif name.endswith(".html"):
            ctype = "text/html; charset=utf-8"
        elif name.endswith(".js"):
            ctype = "application/javascript; charset=utf-8"
        elif name.endswith(".css"):
            ctype = "text/css; charset=utf-8"
        else:
            ctype = "application/octet-stream"

        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(size))
        self.send_header("Cache-Control", "no-store")
        if name.endswith((".apk", ".js", ".zip", ".bin")):
            # .bin 是为绕 WAF 拦 .zip 直链而用的传输后缀，交付时还原成 .zip。
            # 与页面 download 属性保持同名，避免手机浏览器忽略 download 时存成别的文件名。
            if name.endswith(".bin"):
                ascii_name = name[:-4] + ".zip"
                cn_name = BIN_CN_NAME.get(name)
                if cn_name:
                    self.send_header(
                        "Content-Disposition",
                        "attachment; filename=\"%s\"; filename*=UTF-8''%s"
                        % (ascii_name, urllib.parse.quote(cn_name)),
                    )
                else:
                    self.send_header(
                        "Content-Disposition",
                        'attachment; filename="%s"' % ascii_name,
                    )
            else:
                self.send_header(
                    "Content-Disposition", 'attachment; filename="%s"' % name
                )
        self.end_headers()
        if with_body:
            with open(full, "rb") as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    try:
                        self.wfile.write(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        break

    def do_GET(self):
        self._serve(True)

    def do_HEAD(self):
        self._serve(False)

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
