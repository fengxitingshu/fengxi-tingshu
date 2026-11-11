package com.fengxi.fnplayer.core;

import java.security.MessageDigest;

/** 通用工具：IPv6 地址规范化、MD5、JSON 转义、错误归类。 */
public final class Utils {

    private Utils() {}

    public static final int DEF_HTTP_PORT = 5666;
    public static final int DEF_HTTPS_PORT = 5667;

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    /**
     * 把用户填的各种形态归一成 {@code scheme://host:port}（不含路径）。
     *
     * <p>支持：
     * <ul>
     *   <li>{@code 192.168.1.10} / {@code 192.168.1.10:5666} / {@code nas.example.com}</li>
     *   <li>{@code [2408:xxxx::1]} / {@code [2408:xxxx::1]:5666}</li>
     *   <li>{@code 2408:xxxx::1}（裸 IPv6，自动补方括号 + 默认端口）</li>
     *   <li>已带 http:// 或 https:// 的完整地址（自动去掉尾随路径）</li>
     * </ul>
     *
     * <p><b>歧义处理</b>：裸 IPv6 里冒号既分隔段又可能像端口，无法可靠区分，
     * 因此裸 IPv6 一律视为「地址 + 默认端口」；需要自定义端口时必须写
     * {@code [地址]:端口}（配置页会提示）。这是避免把 {@code ::1} 误切成端口 1 的关键。
     */
    public static String normalizeBase(String raw) throws Exception {
        if (raw == null || raw.trim().length() == 0) throw new Exception("地址为空");
        String s = raw.trim();
        boolean https = false;
        if (s.startsWith("https://")) { https = true; s = s.substring(8); }
        else if (s.startsWith("http://")) { s = s.substring(7); }
        else if (s.contains("://")) throw new Exception("只支持 http:// 或 https://");

        int slash = s.indexOf('/');
        String authority = slash >= 0 ? s.substring(0, slash) : s;
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        if (authority.length() == 0) throw new Exception("主机名不能为空");

        int defPort = https ? DEF_HTTPS_PORT : DEF_HTTP_PORT;
        int port = defPort;
        String host;

        if (authority.charAt(0) == '[') {
            int rb = authority.indexOf(']');
            if (rb < 0) throw new Exception("IPv6 地址缺少右方括号 ]");
            host = authority.substring(0, rb + 1);      // 保留方括号
            String rest = authority.substring(rb + 1);
            if (rest.length() > 0) {
                if (rest.charAt(0) != ':') throw new Exception("IPv6 端口格式错误（应为 [地址]:端口）");
                String p = rest.substring(1);
                if (p.length() == 0) throw new Exception("端口号为空");
                port = parseInt(p);
            }
        } else {
            int colons = countChar(authority, ':');
            if (colons == 0) {
                host = authority;
            } else if (colons == 1) {
                int c = authority.lastIndexOf(':');
                host = authority.substring(0, c);
                port = parseInt(authority.substring(c + 1));
            } else {
                // 裸 IPv6：整段当作地址，不尝试切端口
                host = "[" + authority + "]";
            }
        }
        if (host.length() == 0) throw new Exception("主机名不能为空");
        if (port <= 0 || port > 65535) throw new Exception("端口超出范围：" + port);
        return (https ? "https" : "http") + "://" + host + ":" + port;
    }

    private static int parseInt(String p) throws Exception {
        try { return Integer.parseInt(p.trim()); }
        catch (Throwable t) { throw new Exception("端口号不是数字：" + p); }
    }

    /** 从归一后的 base 取主机（IPv6 保留方括号），用于 Cookie 隔离键与证书校验。 */
    public static String hostOf(String base) {
        try {
            int i = base.indexOf("://");
            String a = i >= 0 ? base.substring(i + 3) : base;
            int slash = a.indexOf('/');
            if (slash >= 0) a = a.substring(0, slash);
            if (a.startsWith("[")) {
                int rb = a.indexOf(']');
                return rb > 0 ? a.substring(0, rb + 1) : a;
            }
            int c = a.indexOf(':');
            return c >= 0 ? a.substring(0, c) : a;
        } catch (Throwable t) { return base; }
    }

    /** 是否为 IPv6 字面量（含方括号） */
    public static boolean isIpv6Literal(String base) {
        return hostOf(base).startsWith("[");
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    public static String jsonEsc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 把底层异常映射成用户能看懂的中文原因。 */
    public static String explain(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage() == null ? "" : t.getMessage();
        String n = t.getClass().getSimpleName();
        if (m.contains("NetworkOnMainThread")) return "网络请求误跑在主线程（App 内部错误）";
        if (n.contains("UnknownHost") || m.contains("Unable to resolve host"))
            return "域名解析失败（DNS 不通或域名填错）";
        if (n.contains("SSLHandshake") || m.contains("trust anchor") || m.contains("certificate"))
            return "证书不受信（飞牛 HTTPS 多为自签，请开启「信任自签证书」）";
        if (n.contains("ConnectException") || m.contains("Failed to connect") || m.contains("Connection refused"))
            return "连接被拒绝（端口不通 / 服务未启动 / 防火墙拦截）";
        if (n.contains("SocketTimeout") || m.contains("timeout") || m.contains("timed out"))
            return "连接超时（IPv6 不可达或端口未放行）";
        if (n.contains("NoRouteToHost") || m.contains("No route to host"))
            return "网络不可达（IPv6 地址不可达，检查前缀/路由/运营商）";
        if (n.contains("MalformedURL") || m.contains("Invalid") || m.contains("Expected"))
            return "地址格式错误（IPv6 请写 [地址]:端口）";
        return n + (m.length() > 0 ? "：" + m.substring(0, Math.min(m.length(), 100)) : "");
    }

    public static String brief(Throwable t) {
        if (t == null) return "null";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    /**
     * 资源站（CMS）接口地址规范化 —— 与 normalizeBase 不同：保留路径与查询参数
     * （http://x/api.php/provide/vod 不能被剥成 http://x:80，否则 ac=list 全打到首页，
     * 这就是 v2.5「无法读取 box 源」的根因）。只补协议、去尾斜杠。
     */
    public static String normalizeApi(String raw) throws Exception {
        if (raw == null || raw.trim().length() == 0) throw new Exception("地址为空");
        String s = raw.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int sp = s.indexOf("://");
        if (s.substring(sp + 3).indexOf('/') < 0) {
            throw new Exception("接口地址要带路径（如 …/api.php/provide/vod）");
        }
        return s;
    }
}
