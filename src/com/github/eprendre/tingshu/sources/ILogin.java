package com.github.eprendre.tingshu.sources;

/**
 * 兼容原版插件的登录接口（2.5.x 引入）。
 *
 * <pre>
 *   interface ILogin {
 *       fun getLoginUrl(): String
 *       fun isLoginDesktop(): Boolean = false
 *   }
 * </pre>
 *
 * <p>默认实现放在 {@code ILogin$DefaultImpls}，因为插件是用 Kotlin 1.8 编译的
 * （非 {@code -Xjvm-default=all}），默认方法走 DefaultImpls 桥接——
 * 插件 dex 里确实引用了 {@code ILogin$DefaultImpls.isLoginDesktop}，
 * 缺了它 {@code Mting}/{@code Qmtsw} 加载即 {@code NoClassDefFoundError}。
 *
 * <h3>为什么必须提供</h3>
 * Dalvik/ART 在**解析一个类**时会强制解析它的 superclass 与**全部直接接口**。
 * 只要有一个直接接口解析不到，{@code Class.forName} 就抛
 * {@code NoClassDefFoundError}，整个源被丢弃。所以哪怕本 App 自己不做登录，
 * 也必须把接口补齐，让插件类能顺利链接。
 *
 * <p>本 App 不提供登录宿主能力：{@link #getLoginUrl()} 由插件实现（返回站点登录页），
 * 若某源真的需要登录才能用，取链会失败并回落到直链尝试。
 */
public interface ILogin {

    /** 站点登录页地址（插件实现） */
    String getLoginUrl();

    /** 是否走桌面版登录流程；默认 false */
    boolean isLoginDesktop();

    /** Kotlin 编译期生成的默认实现容器，插件直接引用它做 super 调用 */
    final class DefaultImpls {
        private DefaultImpls() {}

        public static boolean isLoginDesktop(ILogin self) {
            return false;
        }
    }
}
