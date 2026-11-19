#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FX 下载站静态服务 v3 —— 纯内网，取代 v2「锚点」。

v2 的做法：每 60 秒去外部会话级发布站拉 manifest → 下载 APK → 重写 index.html。
外部站一停更，页面就永远停在旧版，而且会把本地推上去的页面覆盖回去。

v3 的做法：什么都不拉、什么都不写，只把 OUT_DIR 里的文件静态托管出去。
页面 / manifest / APK 全部由发版本机用 publish.py 通过内网 WebDAV 推送，
推完即生效，没有任何后台进程会改写它们。

用法：
  容器（compose 里 command: python -u /app/anchor.py）
      OUT_DIR=/data HTTP_PORT=8000 python -u /app/anchor.py
  本机预览
      python anchor.py --dir . --port 8899

环境变量：OUT_DIR（默认脚本所在目录）、HTTP_PORT（默认 8899）
命令行参数优先于环境变量，方便同一份脚本在 NAS 和本机两用。
"""
import argparse
import functools
import http.server
import os
import urllib.parse

# 这些后缀强制 attachment，避免手机浏览器尝试内联打开 APK / 源脚本
ATTACH = (".apk", ".js", ".zip", ".bin")


class DownloadHandler(http.server.SimpleHTTPRequestHandler):
    """静态文件服务：APK 强制下载，页面 / manifest 禁用缓存。

    ★ v4.21.44：修掉「主下载按钮点了没反应」——
      1) 下载链接不再依赖 <a download>，服务端自带
         Content-Disposition: attachment; filename="xxx.apk"；
      2) APK 返回真正的安装包 MIME，部分浏览器靠它判断能否交给系统安装器；
      3) 用 HTTP/1.1 长连接，50MB 大包下载中途不易被提前断流。
    """

    protocol_version = "HTTP/1.1"

    def guess_type(self, path):
        if path.lower().endswith(".apk"):
            return "application/vnd.android.package-archive"
        return super().guess_type(path)

    def end_headers(self):
        path = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path)
        low = path.lower()
        if low.endswith(ATTACH):
            name = os.path.basename(path).replace('"', "").replace("\\", "")
            self.send_header("Content-Disposition",
                             'attachment; filename="%s"' % name)
            self.send_header("Cache-Control", "no-store")
        elif low.endswith(("index.html", ".json")) or path in ("/", ""):
            self.send_header("Cache-Control", "no-store, must-revalidate")
        super().end_headers()

    def log_message(self, fmt, *args):
        pass


def main():
    ap = argparse.ArgumentParser(description="FX 下载站静态服务 v3")
    ap.add_argument("--dir", default=None, help="托管目录（默认 OUT_DIR 或脚本所在目录）")
    ap.add_argument("--port", type=int, default=None, help="监听端口（默认 HTTP_PORT 或 8899）")
    args = ap.parse_args()

    root = args.dir or os.environ.get("OUT_DIR") or os.path.dirname(os.path.abspath(__file__))
    root = os.path.abspath(root)
    port = args.port or int(os.environ.get("HTTP_PORT", "0") or "0") or 8899

    if not os.path.isdir(root):
        raise SystemExit("!! 托管目录不存在：%s" % root)
    if not os.path.isfile(os.path.join(root, "index.html")):
        print("[提示] 目录里还没有 index.html，先跑 publish.py 生成下载页")

    handler = functools.partial(DownloadHandler, directory=root)
    srv = http.server.ThreadingHTTPServer(("0.0.0.0", port), handler)
    srv.daemon_threads = True
    print("[FX 下载站 v3] 纯静态托管 %s → 0.0.0.0:%d（不联网、不轮询、不改文件）"
          % (root, port), flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()


if __name__ == "__main__":
    main()
