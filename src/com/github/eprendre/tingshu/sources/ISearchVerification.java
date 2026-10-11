package com.github.eprendre.tingshu.sources;

/**
 * 兼容原版插件的搜索验证接口（2.5.7 引入）。
 *
 * <pre>
 *   interface ISearchVerification {
 *       fun getSearchVerificationUrl(keywords: String): String
 *       fun isSearchValidated(): Boolean = false
 *       fun getSearchVerificationUA(): String = getMobileUA()
 *       fun getSearchDelayMs(): Long = 0
 *   }
 * </pre>
 *
 * <p>默认实现放在 {@code ISearchVerification$DefaultImpls}，因为插件是用
 * Kotlin 1.8 编译的（非 -Xjvm-default=all），默认方法走 DefaultImpls 桥接。
 */
public interface ISearchVerification {

    String getSearchVerificationUrl(String keywords);

    boolean isSearchValidated();

    String getSearchVerificationUA();

    long getSearchDelayMs();

    /** Kotlin 编译期生成的默认实现容器，插件可能直接引用它 */
    final class DefaultImpls {
        private DefaultImpls() {}

        public static boolean isSearchValidated(ISearchVerification self) {
            return false;
        }

        public static String getSearchVerificationUA(ISearchVerification self) {
            return com.github.eprendre.tingshu.extensions.MyExtKt.getMobileUA();
        }

        public static long getSearchDelayMs(ISearchVerification self) {
            return 0L;
        }
    }
}
