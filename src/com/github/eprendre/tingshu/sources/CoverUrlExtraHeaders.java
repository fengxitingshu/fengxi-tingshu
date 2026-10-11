package com.github.eprendre.tingshu.sources;

/**
 * 兼容原版插件的「封面需要附加请求头」接口。
 *
 * <pre>
 *   interface CoverUrlExtraHeaders {
 *       fun coverHeaders(coverUrl: String, headers: MutableMap&lt;String, String&gt;): Boolean
 *   }
 * </pre>
 *
 * <p>注意第三个参数是 MutableMap，插件会**就地修改**这个 map 并返回 true。
 * 适配层传入一个可变 Map，返回 true 时取用其内容。
 */
public interface CoverUrlExtraHeaders {
    boolean coverHeaders(String coverUrl, java.util.Map<String, String> headers);
}
