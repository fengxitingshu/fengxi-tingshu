package com.github.eprendre.tingshu.sources

/**
 * 兼容原版插件的「WebView 取链」取链器。
 *
 * ```kotlin
 * class AudioUrlWebViewExtractor : AudioUrlExtractor {
 *     fun setUp(sniff: Boolean = false, js: String, parse: (String) -> String) { ... }
 *     override fun extract(url, autoPlay, isCache, isDebug) { ... }
 * }
 * ```
 *
 * ## 为什么本 App 只提供「壳」
 *
 * 原版这个取链器靠 WebView 打开章节页、注入 JS、抓取真实音频地址。本 App
 * 没有 WebView 宿主（也不打算引入——它会让 APK 体积和内存都明显上涨），
 * 所以这里只保证**类结构与签名**与插件引用一致，让插件能链接通过，
 * 真正调用时走一个明确失败的空实现（抛异常），插件侧会自动落到它自己的
 * 兜底分支，而不是静默返回错误数据。
 *
 * ## 签名对齐要点
 *
 * 插件 dex 的 method 表里引用了：
 * ```
 * AudioUrlWebViewExtractor.setUp$default(
 *     AudioUrlWebViewExtractor, Z, String, Function1, I, Object)
 * ```
 * 即 Kotlin 为一个 `setUp(sniff: Boolean = false, js: String, parse: (String)->String)`
 * 生成的默认参数桥接。参数里的 `I` 是默认参数掩码、`Object` 是 marker。
 * 这里照原样声明 Kotlin 函数，编译器会自动产出同样的桥接，无需手写。
 *
 * @param sniff 是否启用网络嗅探模式（原版语义）
 * @param js 注入的 JS 脚本
 * @param parse 从嗅探结果解析出音频直链的函数
 */
class AudioUrlWebViewExtractor private constructor() : AudioUrlExtractor {

    /** 插件通过 setUp 注册的解析函数 */
    @Volatile
    private var parse: ((String) -> String)? = null

    private val lock = Any()

    /**
     * 插件调用：注册 WebView 取链参数。
     *
     * 与 [AudioUrlCustomExtractor.setUp] 同形，但多一个 [sniff] 开关与 [js] 脚本。
     */
    fun setUp(sniff: Boolean = false, js: String = "", parse: (String) -> String) {
        synchronized(lock) {
            this.parse = parse
        }
    }

    override fun extract(url: String, autoPlay: Boolean, isCache: Boolean, isDebug: Boolean) {
        // 本 App 无 WebView 宿主：显式失败，让插件/上层落到直链兜底
        throw UnsupportedOperationException(
            "本 App 不提供 WebView 取链宿主（AudioUrlWebViewExtractor）")
    }

    /**
     * 伴生对象，用于产出与原版一致的 {@code INSTANCE} 字段。
     *
     * <p>插件 dex 的 field 表里引用了
     * {@code AudioUrlWebViewExtractor.INSTANCE} —— 原版这里是 Kotlin `object`。
     * 本类用普通 class + 伴生 INSTANCE 提供同名字段，既满足二进制引用，
     * 又避免把「取链器本身」做成全局可变单例（那样更危险）。
     */
    companion object {
        /** 全局单例，对应原版 object 的 INSTANCE 字段 */
        @JvmField
        val INSTANCE = AudioUrlWebViewExtractor()
    }
}
