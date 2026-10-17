package com.github.kittinunf.result;

/**
 * kittinunf/result 3.x 兼容层（最小面）。
 *
 * <p>插件只调用 {@code Result.get()}（成功路径取值）；Success/Failure 作为
 * 嵌套类，二进制名 Result$Success / Result$Failure 与原版一致。
 */
public interface Result {

    /** 成功时返回载荷；失败时抛异常（原版语义 get() 对 Failure 会抛） */
    Object get();

    class Success implements Result {
        private final Object value;

        public Success(Object value) {
            this.value = value;
        }

        @Override
        public Object get() {
            return value;
        }
    }

    class Failure implements Result {
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

        /** 失败原因（插件一般不调用，仅为完整性） */
        public Object getError() {
            return error;
        }
    }
}
