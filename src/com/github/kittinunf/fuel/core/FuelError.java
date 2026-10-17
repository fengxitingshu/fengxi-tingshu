package com.github.kittinunf.fuel.core;

/** Fuel 请求失败载体（最小实现：只做异常包装，插件成功路径不会触碰它） */
public class FuelError extends Exception {

    public FuelError(Throwable cause) {
        super(cause == null ? "fuel error" : cause.getMessage(), cause);
    }

    public FuelError(String message) {
        super(message);
    }

    @Override
    public String toString() {
        Throwable c = getCause();
        return "FuelError: " + (c == null ? "unknown" : c.toString());
    }
}
