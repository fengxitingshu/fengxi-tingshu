package com.github.kittinunf.fuel;

import com.github.kittinunf.fuel.core.Request;
import com.github.kittinunf.fuel.core.RequestFactory;

import java.util.List;

/**
 * Fuel 2.3.1 兼容层：Fuel object。
 *
 * <p>原版是 Kotlin object（编译为 INSTANCE 字段 + 实例方法），插件按
 * {@code Fuel.INSTANCE} 取单例、cast 成 {@code RequestFactory$Convenience} 后
 * 经 DefaultImpls.get$default 构造请求。
 */
public final class Fuel implements RequestFactory.Convenience {

    /** 对应 Kotlin object 的 INSTANCE 字段（插件 field 引用了它，必须 public） */
    public static final Fuel INSTANCE = new Fuel();

    private Fuel() {}

    @Override
    public Request get(String path, List parameters) {
        return RequestFactory.Convenience.DefaultImpls.get(this, path, parameters);
    }
}
