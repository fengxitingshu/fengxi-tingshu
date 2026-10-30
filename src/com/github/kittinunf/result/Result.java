package com.github.kittinunf.result;

/**
 * kittinunf/result 3.x 兼容层。
 *
 * <p><b>必须是 class，不能是 interface（v4.15.1 修正）</b>：插件字节码对
 * {@code Result.get()} 用的是 {@code invoke-virtual}（原版 Result 是抽象类），
 * 若宿主声明为 interface，运行时直接抛 IncompatibleClassChangeError，
 * 表现为「jar 源导入成功但列表/搜索全空」。
 *
 * <p>Success / Failure 嵌套子类的二进制名（Result$Success / Result$Failure）
 * 与原版编译产物一致。
 */
public abstract class Result {

    /** 成功时返回载荷；失败时抛异常（原版语义 get() 对 Failure 会抛） */
    public abstract Object get();

    /** 失败原因（Success 上为 null） */
    public abstract Object getError();

    public static class Success extends Result {
        private final Object value;

        public Success(Object value) {
            this.value = value;
        }

        @Override
        public Object get() {
            return value;
        }

        @Override
        public Object getError() {
            return null;
        }
    }

    public static class Failure extends Result {
        private final Object error;

        public Failure(Object error) {
            this.error = error;
        }

        @Override
        public Object get() {
            if (error instanceof Throwable) {
                Throwable t = (Throwable) error;
                throw (t instanceof RuntimeException) ? (RuntimeException) t
                        : new RuntimeException(t);
            }
            throw new RuntimeException(String.valueOf(error));
        }

        @Override
        public Object getError() {
            return error;
        }
    }
}
