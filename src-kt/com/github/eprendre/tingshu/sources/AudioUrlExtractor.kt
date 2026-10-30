package com.github.eprendre.tingshu.sources

/**
 * 兼容原版插件的音频提取器接口。
 *
 * ```kotlin
 * interface AudioUrlExtractor {
 *     fun extract(url: String, autoPlay: Boolean, isCache: Boolean, isDebug: Boolean = false)
 * }
 * ```
 *
 * 用 Kotlin 声明是为了让 [AudioUrlCustomExtractor] 这个 `object` 能正常实现它，
 * 并生成与原版一致的 `AudioUrlExtractor$DefaultImpls` 桥接类（插件 dex 里引用了它）。
 *
 * 默认参数 `isDebug = false` 会额外生成 `extract$default` 静态桥接，
 * 插件以 3 参调用时会走它——原版行为一致。
 */
interface AudioUrlExtractor {

    fun extract(url: String, autoPlay: Boolean, isCache: Boolean, isDebug: Boolean = false)
}
