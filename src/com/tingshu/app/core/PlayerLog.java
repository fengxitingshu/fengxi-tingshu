package com.tingshu.app.core;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * ★ v4.10.10：播放诊断日志（环形缓冲，最近 300 条）。
 * 记录播放全流程关键事件（入口/格式判定/转码阶段/自救分支/失败原因），
 * 在「我的 → 关于 → 播放诊断日志」页可查看/复制，专治「没有任何提示」的失败。
 * 只存内存，不上传、不落盘（退出即清）。
 */
public final class PlayerLog {

    private static final ArrayDeque<String> BUF = new ArrayDeque<String>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private static final int MAX = 300;

    private PlayerLog() {}

    public static synchronized void log(String msg) {
        if (msg == null) msg = "";
        if (msg.length() > 300) msg = msg.substring(0, 300) + "…";
        BUF.addLast(FMT.format(new Date()) + "  " + msg);
        while (BUF.size() > MAX) BUF.removeFirst();
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = BUF.iterator();
        while (it.hasNext()) sb.append(it.next()).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() { BUF.clear(); }
}
