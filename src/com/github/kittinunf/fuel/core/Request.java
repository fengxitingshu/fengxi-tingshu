package com.github.kittinunf.fuel.core;

import java.util.Map;

/**
 * Fuel 2.3.1 兼容层的 Request 接口（最小面）。
 *
 * <p>原版里 Request 是 Kotlin interface，插件对它一律 invoke-interface。
 * 本 App 只提供插件实际调用的方法；url()/headers() 供宿主
 * FuelJsonKt.responseJson 执行真实请求用，插件不会调用。
 */
public interface Request {

    /** 追加请求头（原版语义：合并进请求，返回自身） */
    Request header(Map<String, String> headers);

    /** 最终请求 URL（宿主实现内部用） */
    String url();

    /** 当前请求头快照（宿主实现内部用） */
    Map<String, String> headers();
}
