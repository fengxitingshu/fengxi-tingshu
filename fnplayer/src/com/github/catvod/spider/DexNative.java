package com.github.catvod.spider;

/**
 * v2.19：Guard 加密壳 jar 的宿主桥（对标 FongMi/TV 闭源 runtime 里的 DexNative）。
 *
 * <p>饭太硬（ftyguard）/ 王二小（wexguard）等壳 jar 的结构：
 * classes.dex 只是一层 stub —— {@code XxxGuard extends BaseSpiderGuard} 全是
 * 空委托，真实爬虫字节码加密在 {@code assets/*.guard} 里，由壳自带的
 * {@code *guard_v7.so(32位) / *guard_v8.so(64位)} 在运行时解密并创建
 * DexClassLoader。stub 通过本类这座桥取 loader、取真实 Spider 实例、
 * 做 encrypt/decrypt 签名。
 *
 * <p>壳 so 只导出 {@code JNI_OnLoad}，加载时 RegisterNatives 绑定到
 * {@code com/github/catvod/spider/DexNative} —— 所以宿主必须提供这个类，
 * 方法签名必须与 tvbox-silicon（逆向参考实现）一致：
 * <pre>
 * Init.init(ctx)          → DexNative.getLoader(ctx)             → loader
 * BaseSpiderGuard.<init>  → DexNative.getSpider(loader, name)    → 真实 Spider
 * </pre>
 *
 * <p>so 的加载由 SpiderRuntime 在解包壳 jar 时完成（按设备 ABI 选 v7/v8）。
 * 本类只做声明；若 so 未成功加载/绑定，调用会抛 UnsatisfiedLinkError，
 * 由 SpiderRuntime 捕获后给出可读错误。
 */
public final class DexNative {

    private DexNative() {}

    public static native String encrypt(String value);

    public static native String decrypt(String value);

    public static native String native_ting_md5(String value);

    public static native String noxSign(String a, String b, String c);

    /** stub 传 Context，壳返回解密后的 DexClassLoader。 */
    public static native Object getLoader(Object value);

    /** 从壳的 loader 里按类名实例化真实 Spider。 */
    public static native Object getSpider(Object loader, String name);

    public static native int[] calcResult(int[] value);

    /** 壳通用代理：转发 Spider 方法调用到真实实例。 */
    public static native Object[] proxyInvoke(Object loader, Object params);
}
