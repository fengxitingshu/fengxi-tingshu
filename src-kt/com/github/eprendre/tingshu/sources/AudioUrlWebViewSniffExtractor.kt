package com.github.eprendre.tingshu.sources

/**
 * 兼容原版插件的「WebView 嗅探取链」取链器。
 *
 * ```kotlin
 * class AudioUrlWebViewSniffExtractor : AudioUrlExtractor {
 *     fun setUp(sniff: Boolean = false, parse: (String) -> String) { ... }
 *     override fun extract(url, autoPlay, isCache, isDebug) { ... }
 * }
 * ```
 *
 * 与 [AudioUrlWebViewExtractor] 的区别：这个版本不带 `js` 参数，
 * 完全依赖引擎的网络嗅探能力抓音频请求。
 *
 * 插件 dex 引用的桥接签名：
 * ```
 * AudioUrlWebViewSniffExtractor.setUp$default(
 *     AudioUrlWebViewSniffExtractor, Z, Function1, I, Object)
 * ```
 *
 * 同样地，本 App 不提供 WebView 宿主，`extract` 显式失败。
 */
class AudioUrlWebViewSniffExtractor private constructor() : AudioUrlExtractor {

    @Volatile
    private var parse: ((String) -> String)? = null

    private val lock = Any()

    /** 插件调用：注册嗅探解析函数。[sniff] 保留原版语义。 */
    fun setUp(sniff: Boolean = false, parse: (String) -> String) {
        synchronized(lock) {
            this.parse = parse
        }
    }

    override fun extract(url: String, autoPlay: Boolean, isCache: Boolean, isDebug: Boolean) {
        throw UnsupportedOperationException(
            "本 App 不提供 WebView 嗅探取链宿主（AudioUrlWebViewSniffExtractor）")
    }

    /** 对应原版 object 的 INSTANCE 字段（插件 field 表里引用了它） */
    companion object {
        @JvmField
        val INSTANCE = AudioUrlWebViewSniffExtractor()
    }
}
