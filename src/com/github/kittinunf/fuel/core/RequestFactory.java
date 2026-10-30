package com.github.kittinunf.fuel.core;

/**
 * Fuel 2.3.1 兼容层：RequestFactory 及其内嵌 Convenience 接口。
 *
 * <p>二进制类名对齐原版字节码：
 * <ul>
 *   <li>{@code RequestFactory$Convenience} —— Kotlin interface 的默认方法宿主</li>
 *   <li>{@code RequestFactory$Convenience$DefaultImpls} —— Kotlin 默认方法合成类，
 *       插件调用 {@code get$default(Convenience, String, List, int, Object)} 走这里</li>
 * </ul>
 * Java 嵌套类的二进制名（Outer$Inner）恰好与 Kotlin 编译产物一致，直接用嵌套写法。
 */
public final class RequestFactory {

    private RequestFactory() {}

    public interface Convenience {

        /** GET 请求（parameters 可为 null，元素为 Pair/Entry 形式的 query 参数） */
        Request get(String path, java.util.List parameters);

        /** Kotlin 默认方法合成宿主（插件按这个名字链接） */
        class DefaultImpls {
            public static Request get(Convenience self, String path, java.util.List parameters) {
                return RequestImpl.create(path, parameters);
            }

            public static Request get$default(Convenience self, String path,
                                              java.util.List parameters, int mask, Object marker) {
                return get(self, path, (mask & 1) != 0 ? null : parameters);
            }
        }
    }
}
