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

    /**
     * ★ v4.15.0 执行请求，返回 {@code Triple<Request, Response, Result<ByteArray>>}。
     *
     * <p>原版 Fuel 的 {@code request.response()} 就是这个三元组；third 是
     * <b>{@code com.github.kittinunf.result.Result}</b>（成功为 Success(bytes)、
     * 失败为 Failure），插件再 {@code Result.get()} 取出响应体字节，
     * 自己 {@code String(bytes, UTF_8)} 解码。
     * v4.15.1 修正：之前 third 直接塞了裸 byte[]，插件 checkcast 到 Result
     * 必然 ClassCastException（被插件 catch 吞掉）——表现为「jar 源导入成功
     * 但列表/搜索全空、无法加载」。
     */
    kotlin.Triple<Request, Object, com.github.kittinunf.result.Result> response();

    /** 最终请求 URL（宿主实现内部用） */
    String url();

    /** 当前请求头快照（宿主实现内部用） */
    Map<String, String> headers();
}
