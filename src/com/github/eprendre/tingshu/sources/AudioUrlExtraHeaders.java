package com.github.eprendre.tingshu.sources;

/**
 * 兼容原版插件的「播放该音频需要附加请求头」接口。
 *
 * <pre>
 *   interface AudioUrlExtraHeaders {
 *       fun headers(audioUrl: String): Map&lt;String, String&gt;
 *   }
 * </pre>
 *
 * <p>适配层读取它，把 Referer / User-Agent 等传给宿主播放器——
 * 这正是修复「需要 Referer 的源播放必然 403」的关键。
 */
public interface AudioUrlExtraHeaders {
    java.util.Map<String, String> headers(String audioUrl);
}
