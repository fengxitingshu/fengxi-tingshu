package com.github.eprendre.tingshu.extensions;

import com.tingshu.app.core.Net;

/**
 * 兼容原版插件的顶层扩展函数集合（Kotlin 里 {@code MyExt.kt} 编译出的类名是
 * {@code MyExtKt}，全是 static 方法）。
 *
 * <p>插件源码 {@code import com.github.eprendre.tingshu.extensions.getMobileUA}
 * 就是引用这里。常见的是给「搜索验证」接口做默认 UA。
 *
 * <p>只实现插件**实际会调用**的一小部分；其余（WebView 相关、RxJava 相关）
 * 本 App 不提供 WebView 宿主，调用即抛异常——这样插件会走自己的异常兜底分支，
 * 而不是静默拿到错误数据。
 */
public final class MyExtKt {

    private MyExtKt() {}

    /** 手机版 UA */
    public static String getMobileUA() {
        return Net.UA;
    }

    /** 桌面版 UA */
    public static String getDesktopUA() {
        return Net.UA_DESKTOP;
    }

    /** 兼容：部分插件会读它判断是否 PC 模式 */
    public static boolean isDesktop() {
        return false;
    }

    // ==================== ★ v3.3 补齐插件引用到的其余扩展函数 ====================
    //
    // 这些在原版里由宿主 App 提供（弹 Toast、读全局 Cookie、给 jsoup 连接统一
    // 设 UA/超时）。插件 dex 的 method 表里引用了它们，不补的话插件执行到
    // 对应分支会 NoSuchMethodError —— 补齐后至少能安全降级（空实现）。

    /**
     * 给 jsoup 的 Connection 统一配置 UA / 超时 / 重定向等。
     *
     * <p>原版签名：{@code fun Connection.config(followRedirects: Boolean = true)}，
     * <b>只有 followRedirects 一个默认参数</b>——这是从插件 dex 的实际引用
     * {@code config$default(Connection, Z, I, Object)} 反推出来的（4 参版），
     * 多写一个 timeoutMs 会让 {@code $default} 签名变 5 参、对不上。
     *
     * <p>超时用宿主 Net 的默认值，不再作为参数暴露。
     */
    public static org.jsoup.Connection config(org.jsoup.Connection conn,
                                               boolean followRedirects) {
        if (conn == null) return null;
        try {
            conn.userAgent(Net.UA);
            conn.timeout(10000);
            conn.followRedirects(followRedirects);
            conn.ignoreContentType(true);
            conn.ignoreHttpErrors(true);
        } catch (Throwable ignored) {}
        return conn;
    }

    /**
     * Kotlin 默认参数桥接：{@code config(conn)} 的调用会落在这里。
     * 签名必须与插件引用一致：{@code (Connection, Z, I, Object)}。
     */
    public static org.jsoup.Connection config$default(org.jsoup.Connection conn,
                                                      boolean followRedirects,
                                                      int mask, Object marker) {
        if ((mask & 2) != 0) followRedirects = true;
        return config(conn, followRedirects);
    }
    /**
     * 读取宿主保存的全局 Cookie（形如 {@code "k=v; k2=v2"}）。
     *
     * <p>本 App 不做登录态托管，统一返回空串——插件拿到空串会走未登录分支，
     * 比抛异常更友好。
     */
    public static String getCookie(String url) {
        return "";
    }

    /** 更新「正在加载剧集」的提示（原版宿主 UI 行为）。本 App 无此 UI，空实现。 */
    public static void notifyLoadingEpisodes(String text) {
        // no-op：宿主 UI 在网页层，不需要原版的原生提示
    }

    /** 弹一个 Toast（原版宿主行为）。本 App 无原生 Toast，空实现。 */
    public static void showToast(String text) {
        // no-op
    }
}
