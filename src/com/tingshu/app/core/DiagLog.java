package com.tingshu.app.core;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * ★ v3.7.15-fix：运行期诊断环形缓冲。
 *
 * <p>为什么需要它：手工构建的包没有崩溃上报，源加载失败以前是**静默吞掉**的
 * ——用户看到「JS 源怎么没了」，我们只能靠猜。这里把关键节点（源加载失败、
 * 插件解包失败、Python 运行时不可用等）记进一个内存环形缓冲，
 * 前端「源加载诊断」页一次性读出来，截个图就能定位。
 *
 * <p>刻意**不落盘**：诊断只是当下会话的现场，重启即清，
 * 不给用户制造"日志越来越大"的负担；真要长期留存，崩溃走 {@link Crash}。
 */
public final class DiagLog {

    /** 最多保留 200 条（远超一次启动的关键事件数，且内存占用可忽略） */
    private static final int MAX = 200;

    private static final ArrayDeque<String> BUF = new ArrayDeque<String>(MAX);
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private DiagLog() {}

    /** 追加一条（线程安全，永不抛） */
    public static synchronized void add(String msg) {
        try {
            if (BUF.size() >= MAX) BUF.pollFirst();
            BUF.addLast("[" + FMT.format(new Date()) + "] " + msg);
        } catch (Throwable ignored) {}
    }

    /** 全部条目，换行拼接（给前端直接显示） */
    public static synchronized String dump() {
        try {
            if (BUF.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String s : BUF) {
                sb.append(s).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 条目数 */
    public static synchronized int size() {
        return BUF.size();
    }

    public static synchronized void clear() {
        BUF.clear();
    }
}
