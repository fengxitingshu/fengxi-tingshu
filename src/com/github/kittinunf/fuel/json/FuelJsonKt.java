package com.github.kittinunf.fuel.json;

import com.github.kittinunf.fuel.core.FuelError;
import com.github.kittinunf.fuel.core.Request;
import com.github.kittinunf.result.Result;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Fuel 2.3.1 fuel-json 模块兼容层：responseJson 扩展（最小面）。
 *
 * <p>原版签名（Kotlin）：
 * <pre>
 *     fun Request.responseJson(): Triple&lt;Result&lt;FuelJson, FuelError&gt;, Request?, Response?&gt;
 * </pre>
 * 插件用法固定为：{@code Fuel.get(url).responseJson().first.get().obj()}。
 * 这里把异步回调版简化为同步执行（okhttp 直接发请求），对插件的调用面完全透明。
 */
public final class FuelJsonKt {

    private static okhttp3.OkHttpClient client;

    private FuelJsonKt() {}

    public static kotlin.Triple responseJson(Request request) {
        try {
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(request.url());
            Map<String, String> hs = request.headers();
            if (hs != null) {
                for (Map.Entry<String, String> e : hs.entrySet()) {
                    if (e.getKey() != null) rb.header(e.getKey(), e.getValue() == null ? "" : e.getValue());
                }
            }
            if (hs == null || !hs.containsKey("User-Agent")) {
                rb.header("User-Agent", com.github.eprendre.tingshu.extensions.MyExtKt.getMobileUA());
            }

            okhttp3.Response resp = client().newCall(rb.build()).execute();
            String body = resp.body() == null ? "" : resp.body().string();
            // Fuel 的 Result 只包"业务数据"；HTTP 非 2xx 也算失败（与原版 Behavior 语义一致）
            if (!resp.isSuccessful()) {
                return new kotlin.Triple(
                        new Result.Failure(new FuelError("HTTP " + resp.code())), request, null);
            }
            JSONObject obj = new JSONObject(body);
            return new kotlin.Triple(new Result.Success(new FuelJson(obj)), request, null);
        } catch (Throwable t) {
            return new kotlin.Triple(new Result.Failure(new FuelError(t)), request, null);
        }
    }

    private static synchronized okhttp3.OkHttpClient client() {
        if (client == null) {
            client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build();
        }
        return client;
    }
}
