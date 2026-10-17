package com.github.kittinunf.fuel.json;

import org.json.JSONObject;

/**
 * Fuel 2.3.1 fuel-json 模块兼容层：FuelJson。
 *
 * <p>插件只调用 {@code obj()}（返回 JSONObject）；构造由宿主
 * FuelJsonKt.responseJson 完成。
 */
public class FuelJson {

    private final JSONObject content;

    public FuelJson(JSONObject content) {
        this.content = content;
    }

    public JSONObject obj() {
        return content;
    }

    @Override
    public String toString() {
        return String.valueOf(content);
    }
}
