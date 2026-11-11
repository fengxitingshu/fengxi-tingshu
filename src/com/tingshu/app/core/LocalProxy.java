package com.tingshu.app.core;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ★ v4.9.3：极简本地 HTTP 直链代理（只给「网盘直链播放」兜底用）。
 *
 * <h3>为什么需要它</h3>
 * 网盘（夸克 / 115 / 天翼 / UC）的音频直链有两个脾气：
 * <ol>
 *   <li>要带一大堆校验头（Cookie / Referer / UA），少一个就 403；</li>
 *   <li>多数还是一次 302 跳到 CDN，而 Android 的 MediaPlayer / ijk 在
 *       <b>跟随重定向时会把自定义头丢掉</b>——于是「取链明明成功，播放必然失败」。</li>
 * </ol>
 * 这两件事在播放器里没法修，只能在中间加一层：App 自己起一个
 * {@code 127.0.0.1} 的 HTTP 服务，用 {@link HttpURLConnection} 把直链取回来
 * （头由我们带、302 由我们跟），再把字节流原样吐给播放器。
 * 播放器只看到一条普普通通的本地 URL，不带任何头也能播。
 *
 * <h3>边界</h3>
 * <ul>
 *   <li><b>只在播放失败后才启用</b>（PlayerCore 的兜底分支），正常播放零开销、
 *       完全不影响现有链路——代理本身出问题也不会把能播的搞挂。</li>
 *   <li>支持 Range 透传（206 + Content-Range），seek / 拖动进度条照常。</li>
 *   <li>URL 尾部保留原始文件名，播放内核按扩展名选（.wma → FFmpeg）仍有效。</li>
 *   <li>失败 3 次即自我熔断，不再拖慢后续播放。</li>
 * </ul>
 */
public final class LocalProxy {

    /** 目标直链 + 它要求的请求头 */
    private static final class Target {
        final String url;
        final Map<String, String> headers;

        Target(String u, Map<String, String> h) {
            url = u;
            headers = h;
        }
    }

    private static final Map<String, Target> TARGETS = new ConcurrentHashMap<String, Target>();
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    /**
     * ★ v4.21.25：百度网盘的「缓存文件中继」。
     *
     * <p>v4.21.24 的在线续传证明了一件事：百度对非会员下载的限速是
     * 「给一小波流量 → 长时间断流」的循环，播放器内核等不了那么久，
     * 几秒就自己掐线（日志里的 Broken pipe），3 次续传额度根本不够。
     * <b>治本方案</b>：后台线程把文件死磕式下载到本地缓存（断流就重连，
     * 无限次），播放器从<b>本地缓存文件</b>流式取数——上游爱断就断，
     * 本地永远无缝喂。同一集的多次重播（自救②）直接复用已下载部分。
     */
    private static final class Cache {
        final java.io.File file;
        volatile long total = -1;        // 文件总字节数（首个上游响应确定）
        volatile boolean pumpDone = false;   // 已完整下载
        volatile boolean pumpFail = false;   // 重试额度耗尽/无法下载
        volatile long lastProgress = 0;      // 最后一次有新数据的时间戳
        volatile boolean pumpRunning = false; // pump 线程是否在跑
        Cache(java.io.File f) { file = f; }
    }

    /** 按「上游 URL」缓存（同一条直链的重播共用一个缓存文件） */
    private static final Map<String, Cache> CACHES =
            new ConcurrentHashMap<String, Cache>();
    private static final Object CACHE_LK = new Object();

    private static ServerSocket server;
    private static int port = -1;
    private static int failures = 0;
    /**
     * ★ v4.13.0：持久源（WebDAV / SMB 个人音频库）的独立熔断额度。
     *
     * <p>为什么必须分开：网盘直链偶发失败会把 {@link #failures} 打满，
     * 那本是给「网盘兜底」用的保守额度；若 WebDAV 跟着作废，就会出现
     * 「明明能连上 NAS，却起播失败」这种看不懂的现象。两者互不影响。
     */
    private static int persistFailures = 0;

    private LocalProxy() {}

    /** 熔断后不再尝试（避免每次失败都多等一轮连接超时） */
    public static boolean usable() {
        return failures < 3;
    }

    /**
     * 把一条直链挂到本地代理上，返回播放器可直接使用的本地 URL。
     *
     * @return 代理 URL；不可用时返回 null（调用方应回退到原直链）
     */
    public static synchronized String serve(String realUrl, Map<String, String> headers) {
        return serveInternal(realUrl, headers, false);
    }

    /**
     * ★ v4.13.0：持久源（WebDAV / SMB 个人音频库）专用。
     *
     * <p>与 {@link #serve} 的唯一区别是走<b>独立的熔断额度</b>
     * （见 {@link #persistFailures}）。语义完全一致。
     */
    public static synchronized String servePersistent(String realUrl,
                                                      Map<String, String> headers) {
        return serveInternal(realUrl, headers, true);
    }

    private static String serveInternal(String realUrl, Map<String, String> headers,
                                        boolean persistent) {
        if (realUrl == null || !realUrl.startsWith("http")) return null;
        if (persistent ? persistFailures >= 3 : failures >= 3) return null;
        try {
            if (server == null || server.isClosed()) {
                server = new ServerSocket(0, 24, InetAddress.getByName("127.0.0.1"));
                port = server.getLocalPort();
                Thread t = new Thread(new Runnable() {
                    public void run() { acceptLoop(); }
                }, "local-proxy");
                t.setDaemon(true);
                t.start();
            }
            String id = "p" + SEQ.getAndIncrement();
            TARGETS.put(id, new Target(realUrl, headers));
            // 控制一下 map 大小（长时间使用不会无限增长）
            if (TARGETS.size() > 64) {
                java.util.Iterator<String> it = TARGETS.keySet().iterator();
                while (it.hasNext() && TARGETS.size() > 48) {
                    String k = it.next();
                    if (!k.equals(id)) it.remove();
                }
            }
            return "http://127.0.0.1:" + port + "/" + id + "/" + fileNameOf(realUrl);
        } catch (Throwable t) {
            if (persistent) persistFailures++;
            else failures++;
            return null;
        }
    }

    /** 从直链里取文件名（保留扩展名给播放内核判断格式） */
    private static String fileNameOf(String url) {
        try {
            String p = url;
            int q = p.indexOf('?');
            if (q > 0) p = p.substring(0, q);
            int slash = p.lastIndexOf('/');
            String name = (slash >= 0 && slash + 1 < p.length()) ? p.substring(slash + 1) : "a";
            // 只留安全字符，避免 URL 里出现奇怪的东西
            name = name.replaceAll("[^0-9A-Za-z._-]", "_");
            return name.length() == 0 ? "a" : name;
        } catch (Throwable t) {
            return "a";
        }
    }

    // ==================== 监听 / 分发 ====================

    private static void acceptLoop() {
        while (true) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(new Runnable() {
                    public void run() { handle(s); }
                }, "local-proxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                // server 被关掉时跳出（正常情况下 App 生命周期内不会）
                if (server == null || server.isClosed()) return;
            }
        }
    }

    private static void handle(Socket s) {
        java.net.HttpURLConnection conn = null;
        try {
            s.setSoTimeout(60000);
            StringBuilder head = new StringBuilder();
            InputStream cin = new BufferedInputStream(s.getInputStream());
            // 只读到空行为止（请求没有 body）
            int prev1 = -1, prev2 = -1, prev3 = -1, c;
            while ((c = cin.read()) != -1) {
                head.append((char) c);
                if (prev1 == '\r' && prev2 == '\n' && prev3 == '\r' && c == '\n') break;
                prev3 = prev2;
                prev2 = prev1;
                prev1 = c;
                if (head.length() > 8192) break;
            }
            String[] lines = head.toString().split("\r\n");
            if (lines.length == 0) return;

            // 请求行：GET /p12/xxx.mp3 HTTP/1.1
            String reqLine = lines[0];
            String[] seg = reqLine.split(" ");
            if (seg.length < 2) return;
            String path = seg[1];
            String id = null;
            if (path.startsWith("/")) {
                String rest = path.substring(1);
                int slash = rest.indexOf('/');
                id = slash > 0 ? rest.substring(0, slash) : rest;
            }
            Target target = id == null ? null : TARGETS.get(id);
            if (target == null) {
                writeSimple(s, 404, "not found");
                return;
            }

            String range = null;
            for (int i = 1; i < lines.length; i++) {
                String ln = lines[i];
                int colon = ln.indexOf(':');
                if (colon <= 0) continue;
                String k = ln.substring(0, colon).trim();
                if (k.equalsIgnoreCase("Range")) range = ln.substring(colon + 1).trim();
            }

            // ★ v4.21.25：百度网盘走「缓存文件中继」（治限速断流）
            if (isBaiduPcs(target.url)) {
                serveBaiduCache(s, target, range);
                return;
            }

            conn = openFollowing(target, range, 0);
            int code = conn.getResponseCode();

            OutputStream out = s.getOutputStream();
            StringBuilder resp = new StringBuilder();
            resp.append("HTTP/1.1 ").append(code).append(' ')
                    .append(code == 206 ? "Partial Content" : (code == 200 ? "OK" : httpMsg(code)))
                    .append("\r\n");
            String ct = conn.getContentType();
            resp.append("Content-Type: ").append(ct == null ? "application/octet-stream" : ct).append("\r\n");
            resp.append("Accept-Ranges: bytes\r\n");

            long len = -1;
            String cr = conn.getHeaderField("Content-Range");
            if (cr != null && cr.length() > 0) {
                resp.append("Content-Range: ").append(cr).append("\r\n");
                len = parseTotal(cr);
            }
            long cl = conn.getContentLength();
            if (cl >= 0 && code == 200) len = cl;
            if (code == 206 && cr != null) {
                // 206：长度就是这一段
                long[] r = parseRange(cr);
                if (r != null) cl = r[1] - r[0] + 1;
            }
            if (cl >= 0) resp.append("Content-Length: ").append(cl).append("\r\n");
            else if (len >= 0) resp.append("Content-Length: ").append(len).append("\r\n");
            resp.append("Connection: close\r\n\r\n");
            out.write(resp.toString().getBytes("UTF-8"));
            out.flush();

            // ★ v4.21.24：断流自动续传中继。
            //   百度对非会员下载的经典套路：先冲一小波流量，然后暂停很久再续，
            //   甚至中途掐断连接。旧版一断就把流掐死，播放器 10~20 秒后断播。
            //   现在中继层记录已转发字节数，上游断流/超时就带 Range 重连续传
            //   （最多 3 次，读超时 60 秒），对播放器完全透明。
            long rangeStart = parseRangeStart(range);
            long written = 0;
            long expect = cl;          // 本次响应承诺的字节数（-1=未知）
            int retries = 0;
            java.io.InputStream in = conn.getInputStream();
            byte[] buf = new byte[64 * 1024];
            while (true) {
                try {
                    int n;
                    boolean eof = false;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                        written += n;
                    }
                    eof = true;
                    // 正常 EOF：如果承诺的字节还没给够，也算断流（按续传处理）
                    if (eof && expect > 0 && written < expect && retries < 3
                            && isBaiduPcs(target.url)) {
                        PlayerLog.log("[proxy] 上游提前结束（已传 " + written + "/" + expect
                                + "），第 " + (retries + 1) + " 次续传");
                    } else if (eof) {
                        break;
                    } else {
                        break;
                    }
                } catch (java.io.IOException ioe) {
                    if (retries >= 3 || !isBaiduPcs(target.url)) throw ioe;
                    PlayerLog.log("[proxy] 上游断流（已传 " + written + "，" + ioe + "），第 "
                            + (retries + 1) + " 次续传");
                }
                // —— 续传 ——
                retries++;
                try { conn.disconnect(); } catch (Throwable ignored) {}
                try { in.close(); } catch (Throwable ignored) {}
                String resumeRange = "bytes=" + (rangeStart + written) + "-";
                Target rt = new Target(target.url, target.headers);
                conn = openOnce(rt, resumeRange, 0);
                int rc = conn.getResponseCode();
                if (rc != 206) {
                    // 续传被拒（返回 200/4xx）：只能放弃，把剩余当 EOF
                    PlayerLog.log("[proxy] 续传被拒 HTTP " + rc + "，停止中继");
                    break;
                }
                java.io.InputStream in2 = conn.getInputStream();
                in = in2;
                // 重新校准 expect
                String rcr = conn.getHeaderField("Content-Range");
                if (rcr != null) {
                    long[] rr = parseRange(rcr);
                    if (rr != null) expect = (rr[1] - rr[0] + 1) + written;
                } else {
                    long rcl = conn.getContentLength();
                    expect = rcl >= 0 ? rcl + written : -1;
                }
                // in 换新流，继续 while 循环读
                continue;
            }
            try { in.close(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            // 播放器提前关连接是常态（换集/seek），不当错误
        } finally {
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) {}
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * ★ v4.21.25：百度网盘缓存中继。
     *
     * <p>后台 pump 线程断流自动重连续传（额度 40 次、间隔 1.5s），
     * 死磕到整文件下载完成；本方法从缓存文件流式读给播放器，
     * 缓存未追上播放位置时短暂等待。对播放器表现为一条连续流。
     */
    private static void serveBaiduCache(Socket s, Target target, String range) {
        try {
            PlayerLog.log("[proxy] 缓存中继接受请求 range=" + range);
            Cache c = cacheFor(target.url);
            startPump(c, target);

            // 等首个上游响应回来（确定 total；403 自愈也在 pump 里做）
            long deadline = System.currentTimeMillis() + 25000;
            long lastBeat = System.currentTimeMillis();
            while (c.total < 0 && !c.pumpFail
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                // ★ 心跳：等待超过 5s 就说明 pump 没产出，打点暴露
                if (System.currentTimeMillis() - lastBeat > 5000
                        && c.total < 0) {
                    lastBeat = System.currentTimeMillis();
                    PlayerLog.log("[proxy] 缓存中继等待上游（"
                            + (deadline - System.currentTimeMillis()) / 1000 + "s 后回退，"
                            + "pump 在跑=" + c.pumpRunning + "）");
                }
            }
            if (c.total < 0) {
                PlayerLog.log("[proxy] 缓存中继 25s 拿不到文件大小（pumpFail="
                        + c.pumpFail + "），回退直连中继");
                HttpURLConnection conn = openFollowing(target, range, 0);
                passthrough(s, conn, range, target);
                return;
            }
            PlayerLog.log("[proxy] 缓存中继就绪 total=" + c.total
                    + " 已缓存=" + c.file.length());

            long start = parseRangeStart(range);
            if (start >= c.total) start = 0;
            long end = c.total - 1;
            long toSend = end - start + 1;

            OutputStream out = s.getOutputStream();
            StringBuilder resp = new StringBuilder();
            if (start > 0) {
                resp.append("HTTP/1.1 206 Partial Content\r\n");
                resp.append("Content-Range: bytes ").append(start)
                        .append('-').append(end).append('/').append(c.total).append("\r\n");
            } else {
                resp.append("HTTP/1.1 200 OK\r\n");
            }
            resp.append("Content-Type: application/octet-stream\r\n");
            resp.append("Accept-Ranges: bytes\r\n");
            resp.append("Content-Length: ").append(toSend).append("\r\n");
            resp.append("Connection: close\r\n\r\n");
            out.write(resp.toString().getBytes("UTF-8"));
            out.flush();

            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(c.file, "r");
            try {
                raf.seek(start);
                long sent = 0;
                byte[] buf = new byte[32 * 1024];
                long stallStart = -1;
                while (sent < toSend) {
                    long fileLen = c.file.length();
                    long have = fileLen - (start + sent);
                    if (have > 0) {
                        stallStart = -1;
                        int n = raf.read(buf, 0,
                                (int) Math.min(buf.length, Math.min(have, toSend - sent)));
                        if (n > 0) {
                            out.write(buf, 0, n);
                            out.flush();
                            sent += n;
                            continue;
                        }
                    }
                    // 缓存还没追上：pump 在下载就等；pump 结束且没数据了才放弃
                    if (c.pumpDone || c.pumpFail) {
                        if (start + sent >= c.file.length()) {
                            PlayerLog.log("[proxy] 缓存到头（已播 " + sent + "/"
                                    + toSend + "），下载未完成");
                            break;
                        }
                        continue;
                    }
                    if (stallStart < 0) {
                        stallStart = System.currentTimeMillis();
                        PlayerLog.log("[proxy] 缓存等待数据（已播 " + sent + "/" + toSend
                                + "，已缓存 " + fileLen + "）");
                    }
                    // 3 分钟都没进展且 pump 也没死 → pump 卡死保护
                    if (System.currentTimeMillis() - stallStart > 180000
                            && System.currentTimeMillis() - c.lastProgress > 180000) {
                        PlayerLog.log("[proxy] 缓存中继 3 分钟无进展，放弃");
                        break;
                    }
                    Thread.sleep(120);
                }
                if (sent == toSend) {
                    PlayerLog.log("[proxy] 缓存中继完成 " + sent + " 字节");
                } else {
                    PlayerLog.log("[proxy] 缓存中继中断（已播 " + sent + "/" + toSend
                            + "，已缓存 " + c.file.length() + "，done=" + c.pumpDone
                            + " fail=" + c.pumpFail + "）");
                }
            } finally {
                try { raf.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            // 播放器主动断开（换集/seek/探测完就关）不算错；其余全部点亮
            String msg = String.valueOf(t);
            if (!msg.contains("Broken pipe") && !msg.contains("Socket closed")
                    && !msg.contains("Connection reset by peer")) {
                PlayerLog.log("[proxy] 缓存中继异常：" + t);
            }
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    /** 取（或建）URL 对应的缓存；最多留 3 个，LRU 清理 */
    private static Cache cacheFor(String url) throws Exception {
        synchronized (CACHE_LK) {
            Cache c = CACHES.get(url);
            if (c != null) return c;
            java.io.File dir = new java.io.File(
                    com.tingshu.app.data.Settings.ctx().getCacheDir(), "pancache");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new Exception("缓存目录创建失败：" + dir.getAbsolutePath());
            }
            // ★ 注意：createTempFile 的前缀必须 ≥3 字符（"px" 会直接抛
            //   IllegalArgumentException: Prefix string too short）
            java.io.File f = java.io.File.createTempFile("pan", ".bin", dir);
            c = new Cache(f);
            CACHES.put(url, c);
            // LRU：超 3 个删最旧的文件
            while (CACHES.size() > 3) {
                String oldest = null;
                long oldestT = Long.MAX_VALUE;
                for (Map.Entry<String, Cache> e : CACHES.entrySet()) {
                    long t = e.getValue().file.lastModified();
                    if (t < oldestT) { oldestT = t; oldest = e.getKey(); }
                }
                if (oldest == null || oldest.equals(url)) break;
                Cache oc = CACHES.remove(oldest);
                try { oc.file.delete(); } catch (Throwable ignored) {}
            }
            return c;
        }
    }

    /** 启动（或复用已存在的）pump 下载线程 */
    private static void startPump(final Cache c, final Target target) {
        synchronized (CACHE_LK) {
            if (c.pumpRunning) return;
            c.pumpRunning = true;
        }
        try {
            Thread t = new Thread(new Runnable() { public void run() {
                PlayerLog.log("[proxy] 缓存下载线程启动");
                int fails = 0;
                try {
                    // ★ v4.21.26：多姿势轮换——百度风控窗口期会直接掐 TCP，
                    //   单一 UA 死磕没用，按重试次数轮换请求头姿势
                    String[][] poses = pumpPoses(target.headers);
                    while (fails < 120) {
                        long pos = c.file.length();
                        if (c.total >= 0 && pos >= c.total) { c.pumpDone = true; return; }
                        String[] pose = poses[fails % poses.length];
                        HttpURLConnection conn = null;
                        try {
                            java.util.HashMap<String, String> hh =
                                    new java.util.HashMap<String, String>();
                            for (String kv : pose) {
                                int c2 = kv.indexOf('|');
                                if (c2 <= 0) continue;
                                hh.put(kv.substring(0, c2).trim(), kv.substring(c2 + 1).trim());
                            }
                            // ★ v4.21.29：一律带 Range（含首请求 bytes=0-）——
                            //   实测 dlink 的 2 字节 Range 探测能过、裸 GET 被拒，
                            //   百度对 dlink 的无 Range 请求处理不同
                            conn = openFollowing(new Target(target.url, hh),
                                    "bytes=" + pos + "-", 0, 20000);
                            int code = conn.getResponseCode();
                            if (code >= 400) {
                                fails++;
                                PlayerLog.log("[proxy] 缓存下载 HTTP " + code
                                        + "（姿势 UA=" + hh.get("User-Agent") + "）"
                                        + " " + readErrBody(conn));
                                continue;
                            }
                        if (c.total < 0) {
                            String cr = conn.getHeaderField("Content-Range");
                            if (cr != null) c.total = parseTotal(cr);
                            else {
                                long cl = conn.getContentLength();
                                if (cl >= 0) c.total = cl;
                            }
                            PlayerLog.log("[proxy] 缓存下载连接成功 HTTP " + code
                                    + " total=" + c.total + " UA=" + hh.get("User-Agent"));
                        }
                        java.io.InputStream in = conn.getInputStream();
                        java.io.FileOutputStream fo =
                                new java.io.FileOutputStream(c.file, pos > 0);
                        long startLen = pos;
                        byte[] buf = new byte[32 * 1024];
                        try {
                            int n;
                            while (true) {
                                n = in.read(buf);
                                if (n > 0) {
                                    fo.write(buf, 0, n);
                                    fo.flush();
                                    c.lastProgress = System.currentTimeMillis();
                                    // 首波出数 / 每 4MB 打一次点
                                    if (startLen == 0 && c.file.length() == n) {
                                        PlayerLog.log("[proxy] 缓存下载出数（首波 " + n + " 字节）");
                                    } else if (c.file.length() / 4194304
                                            > (c.file.length() - n) / 4194304) {
                                        PlayerLog.log("[proxy] 缓存进度 "
                                                + c.file.length() + "/" + c.total);
                                    }
                                } else if (n == 0) {
                                    continue;
                                } else {
                                    // EOF：文件齐了就收工，否则按断流重连
                                    break;
                                }
                            }
                        } finally {
                            try { fo.close(); } catch (Throwable ignored) {}
                            try { in.close(); } catch (Throwable ignored) {}
                        }
                        if (c.total >= 0 && c.file.length() >= c.total) {
                            c.pumpDone = true;
                            PlayerLog.log("[proxy] 缓存下载完成（"
                                    + c.file.length() + " 字节）");
                            return;
                        }
                        // EOF 但没下完 → 断流，重连续传
                        fails++;
                        PlayerLog.log("[proxy] 缓存下载断流（已下 "
                                + c.file.length() + "/" + c.total + "），第 "
                                + fails + " 次重连");
                    } catch (Throwable e) {
                        fails++;
                        PlayerLog.log("[proxy] 缓存下载异常（" + e + "），第 "
                                + fails + " 次重连，姿势 UA="
                                + (pose.length > 0 ? pose[0] : "?"));
                    } finally {
                        try { if (conn != null) conn.disconnect(); }
                        catch (Throwable ignored) {}
                    }
                    Thread.sleep(2000);
                }
            } catch (Throwable ignored) {
            } finally {
                synchronized (CACHE_LK) { c.pumpRunning = false; }
                if (!c.pumpDone) {
                    c.pumpFail = true;
                    PlayerLog.log("[proxy] 缓存下载重试额度耗尽（已下 "
                            + c.file.length() + " 字节）");
                }
            }
        }}, "pan-cache-pump");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t0) {
            // 线程创建失败等极端情况：复位标志并点亮，否则后续请求全部静默空等
            PlayerLog.log("[proxy] 缓存下载线程启动失败：" + t0);
            synchronized (CACHE_LK) { c.pumpRunning = false; }
        }
    }

    /** 缓存下载的轮换姿势：原头 → netdisk;pan.baidu.com → LogStatistic */
    private static String[][] pumpPoses(Map<String, String> base) {
        String baseUa = base != null && base.get("User-Agent") != null
                ? base.get("User-Agent") : "netdisk";
        return new String[][]{
                {"User-Agent|" + baseUa},
                {"User-Agent|netdisk;pan.baidu.com"},
                {"User-Agent|LogStatistic"}
        };
    }

    /** v4.21.24 的在线续传中继（非百度直链仍走这条；也作缓存中继拿不到 total 时的回退） */
    private static void passthrough(Socket s, HttpURLConnection conn,
                                    String range, Target target) {
        try {
            int code = conn.getResponseCode();

            OutputStream out = s.getOutputStream();
            StringBuilder resp = new StringBuilder();
            resp.append("HTTP/1.1 ").append(code).append(' ')
                    .append(code == 206 ? "Partial Content" : (code == 200 ? "OK" : httpMsg(code)))
                    .append("\r\n");
            String ct = conn.getContentType();
            resp.append("Content-Type: ").append(ct == null ? "application/octet-stream" : ct).append("\r\n");
            resp.append("Accept-Ranges: bytes\r\n");

            long len = -1;
            String cr = conn.getHeaderField("Content-Range");
            if (cr != null && cr.length() > 0) {
                resp.append("Content-Range: ").append(cr).append("\r\n");
                len = parseTotal(cr);
            }
            long cl = conn.getContentLength();
            if (cl >= 0 && code == 200) len = cl;
            if (code == 206 && cr != null) {
                // 206：长度就是这一段
                long[] r = parseRange(cr);
                if (r != null) cl = r[1] - r[0] + 1;
            }
            if (cl >= 0) resp.append("Content-Length: ").append(cl).append("\r\n");
            else if (len >= 0) resp.append("Content-Length: ").append(len).append("\r\n");
            resp.append("Connection: close\r\n\r\n");
            out.write(resp.toString().getBytes("UTF-8"));
            out.flush();

            // 断流自动续传中继。
            //   百度对非会员下载的经典套路：先冲一小波流量，然后暂停很久再续，
            //   甚至中途掐断连接。旧版一断就把流掐死，播放器 10~20 秒后断播。
            //   现在中继层记录已转发字节数，上游断流/超时就带 Range 重连续传
            //   （最多 3 次，读超时 60 秒），对播放器完全透明。
            long rangeStart = parseRangeStart(range);
            long written = 0;
            long expect = cl;          // 本次响应承诺的字节数（-1=未知）
            int retries = 0;
            java.io.InputStream in = conn.getInputStream();
            byte[] buf = new byte[64 * 1024];
            while (true) {
                try {
                    int n;
                    boolean eof = false;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                        written += n;
                    }
                    eof = true;
                    // 正常 EOF：如果承诺的字节还没给够，也算断流（按续传处理）
                    if (eof && expect > 0 && written < expect && retries < 3
                            && isBaiduPcs(target.url)) {
                        PlayerLog.log("[proxy] 上游提前结束（已传 " + written + "/" + expect
                                + "），第 " + (retries + 1) + " 次续传");
                    } else if (eof) {
                        break;
                    } else {
                        break;
                    }
                } catch (java.io.IOException ioe) {
                    if (retries >= 3 || !isBaiduPcs(target.url)) throw ioe;
                    PlayerLog.log("[proxy] 上游断流（已传 " + written + "，" + ioe + "），第 "
                            + (retries + 1) + " 次续传");
                }
                // —— 续传 ——
                retries++;
                try { conn.disconnect(); } catch (Throwable ignored) {}
                try { in.close(); } catch (Throwable ignored) {}
                String resumeRange = "bytes=" + (rangeStart + written) + "-";
                Target rt = new Target(target.url, target.headers);
                conn = openOnce(rt, resumeRange, 0);
                int rc = conn.getResponseCode();
                if (rc != 206) {
                    // 续传被拒（返回 200/4xx）：只能放弃，把剩余当 EOF
                    PlayerLog.log("[proxy] 续传被拒 HTTP " + rc + "，停止中继");
                    break;
                }
                java.io.InputStream in2 = conn.getInputStream();
                in = in2;
                // 重新校准 expect
                String rcr = conn.getHeaderField("Content-Range");
                if (rcr != null) {
                    long[] rr = parseRange(rcr);
                    if (rr != null) expect = (rr[1] - rr[0] + 1) + written;
                } else {
                    long rcl = conn.getContentLength();
                    expect = rcl >= 0 ? rcl + written : -1;
                }
                // in 换新流，继续 while 循环读
                continue;
            }
            try { in.close(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            // 播放器提前关连接是常态（换集/seek），不当错误
        } finally {
            try { conn.disconnect(); } catch (Throwable ignored) {}
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    /** 打开上游连接，手动跟随 302/301（关键：每一跳都重新带上校验头） */
    private static HttpURLConnection openFollowing(Target target, String range, int depth)
            throws Exception {
        return openFollowing(target, range, depth, 60000);
    }

    private static HttpURLConnection openFollowing(Target target, String range, int depth,
                                                   int readTimeoutMs) throws Exception {
        HttpURLConnection conn = openOnce(target, range, depth, readTimeoutMs);
        int code = conn.getResponseCode();
        // ★ v4.21.22：百度 dlink 的 CDN 对「请求头组合」非常挑剔 ——
        //   UA 不对、或混入网页 Cookie/Referer，都会 403 text/plain。
        //   这里做自愈：按梯队换姿势重试（每梯队内部照常跟随重定向），
        //   哪个通就用哪个，成功姿势记进播放日志方便诊断页回放。
        if (code == 403 && isBaiduPcs(target.url)) {
            String ck = "";
            try {
                ck = com.tingshu.app.data.Settings.sp()
                        .getString("pan_cookie_pan_baidu", "");
            } catch (Throwable ignored) {}
            String[][] plans = {
                    {"User-Agent|netdisk;pan.baidu.com"},
                    {"User-Agent|LogStatistic"},
                    {"User-Agent|netdisk",
                     "Cookie|" + ck,
                     "Referer|https://pan.baidu.com/disk/home"}
            };
            for (String[] plan : plans) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
                java.util.HashMap<String, String> hh =
                        new java.util.HashMap<String, String>();
                for (String kv : plan) {
                    int c = kv.indexOf('|');
                    if (c <= 0) continue;
                    String k = kv.substring(0, c).trim();
                    String v = kv.substring(c + 1).trim();
                    if (v.length() == 0) continue;
                    hh.put(k, v);
                }
                conn = openOnce(new Target(target.url, hh), range, depth);
                code = conn.getResponseCode();
                PlayerLog.log("[proxy] 百度403自愈 换姿势 UA="
                        + hh.get("User-Agent")
                        + (hh.containsKey("Cookie") ? "+Cookie" : "")
                        + " → HTTP " + code);
                if (code < 400) break;
            }
            if (code >= 400) {
                // 三种姿势都被拒：把 CDN 的错误体留下来（31326/31329/112 各有含义）
                PlayerLog.log("[proxy] 百度自愈全部失败 HTTP " + code + " " + readErrBody(conn));
            }
        }
        return conn;
    }

    /** ★ v4.21.23：读上游错误响应体（前 240 字符，诊断用） */    private static String readErrBody(HttpURLConnection conn) {
        try {
            java.io.InputStream in = conn.getErrorStream();
            if (in == null) return "";
            byte[] b = new byte[240];
            int n = in.read(b);
            try { in.close(); } catch (Throwable ignored) {}
            return n > 0 ? new String(b, 0, n, "UTF-8") : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** ★ v4.21.22：百度 dlink 的 CDN 域名判定 */
    private static boolean isBaiduPcs(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        return u.contains("pcs.baidu.com") || u.contains("baidupcs.com")
                || u.contains("bcebos.com");
    }

    /** openFollowing 的单次尝试（内部手动跟随重定向，每跳重带头） */
    private static HttpURLConnection openOnce(Target target, String range, int depth)
            throws Exception {
        return openOnce(target, range, depth, 60000);
    }

    private static HttpURLConnection openOnce(Target target, String range, int depth,
                                              int readTimeoutMs) throws Exception {
        URL u = new URL(target.url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        applyNasTls(conn, target.url);   // ★ v4.13.0：家庭 NAS 的自签名证书
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (target.headers != null) {
            for (Map.Entry<String, String> e : target.headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                try { conn.setRequestProperty(e.getKey(), e.getValue()); } catch (Throwable ignored) {}
            }
        }
        if (conn.getRequestProperty("User-Agent") == null) {
            conn.setRequestProperty("User-Agent", Net.UA);
        }
        if (range != null) conn.setRequestProperty("Range", range);

        int code = conn.getResponseCode();
        if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308)
                && depth < 5) {
            String loc = conn.getHeaderField("Location");
            if (loc != null && loc.length() > 0) {
                URL next = new URL(u, loc);
                conn.disconnect();
                Target nt = new Target(next.toString(), target.headers);
                return openFollowing(nt, range, depth + 1, readTimeoutMs);
            }
        }
        return conn;
    }

    /**
     * ★ v4.13.0：家庭 NAS（群晖 / 威联通 / 飞牛等）的 HTTPS 几乎都是自签名证书，
     * 严格校验必然握手失败 —— 表现为「浏览器能开 Nas 控制台，App 里却连不上」。
     *
     * <p>默认仍然严格校验；只有在用户明确开启「信任自签名证书」（备份页那个开关，
     * {@code Settings.davTrustAllSsl()}）后才放宽，且<b>只作用于这一条连接</b> ——
     * 不去动全局默认 SSLSocketFactory，那会让整个 App 失去 TLS 保护。
     */
    private static void applyNasTls(HttpURLConnection conn, String url) {
        if (url == null || !url.toLowerCase(Locale.US).startsWith("https")) return;
        if (!(conn instanceof javax.net.ssl.HttpsURLConnection)) return;
        boolean trust;
        try {
            trust = com.tingshu.app.data.Settings.davTrustAllSsl();
        } catch (Throwable t) {
            return;   // Settings 未初始化（极端情况）→ 走严格校验更安全
        }
        if (!trust) return;
        javax.net.ssl.HttpsURLConnection hc = (javax.net.ssl.HttpsURLConnection) conn;
        try {
            javax.net.ssl.TrustManager[] tm = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public void checkClientTrusted(
                                java.security.cert.X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(
                                java.security.cert.X509Certificate[] chain, String authType) {}
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, tm, new java.security.SecureRandom());
            hc.setSSLSocketFactory(sc.getSocketFactory());
            // 自签证书的 NAS 常用 IP / IPv6 直连，主机名校验必然不过
            hc.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                    return true;
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void writeSimple(Socket s, int code, String body) {
        try {
            byte[] b = body.getBytes("UTF-8");
            String h = "HTTP/1.1 " + code + " " + httpMsg(code) + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
            OutputStream o = s.getOutputStream();
            o.write(h.getBytes("UTF-8"));
            o.write(b);
            o.flush();
        } catch (Throwable ignored) {}
    }

    private static String httpMsg(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            default: return "OK";
        }
    }

    private static long parseTotal(String contentRange) {
        try {
            int slash = contentRange.lastIndexOf('/');
            if (slash < 0) return -1;
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** ★ v4.21.24：从播放器的 Range 头（bytes=1000-）解析起始偏移；无 Range 返回 0 */
    private static long parseRangeStart(String range) {
        if (range == null) return 0;
        try {
            int eq = range.indexOf('=');
            int dash = range.indexOf('-');
            if (eq >= 0 && dash > eq) {
                String s = range.substring(eq + 1, dash).trim();
                if (s.length() > 0) return Long.parseLong(s);
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static long[] parseRange(String contentRange) {
        try {
            // bytes 0-1023/123456
            String s = contentRange.toLowerCase(Locale.US).replace("bytes", "").trim();
            int slash = s.indexOf('/');
            if (slash > 0) s = s.substring(0, slash);
            int dash = s.indexOf('-');
            if (dash < 0) return null;
            long a = Long.parseLong(s.substring(0, dash).trim());
            long b = Long.parseLong(s.substring(dash + 1).trim());
            return new long[]{a, b};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 诊断用：当前代理状态（排查时可从日志看） */
    public static String status() {
        return "port=" + port + " targets=" + TARGETS.size() + " failures=" + failures;
    }

    /** 清空历史目标（换书/停止时调用，避免长期占用） */
    public static synchronized void reset() {
        TARGETS.clear();
    }
}
