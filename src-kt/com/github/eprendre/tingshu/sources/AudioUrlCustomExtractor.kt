package com.github.eprendre.tingshu.sources

/**
 * 兼容原版插件的「自定义取链」单例。
 *
 * ```kotlin
 * object AudioUrlCustomExtractor : AudioUrlExtractor {
 *     fun setUp(parse: (String) -> String) { ... }
 *     override fun extract(url, autoPlay, isCache, isDebug) { ... }
 * }
 * ```
 *
 * ## 为什么这个类用 Kotlin 而不是 Java 写
 *
 * 插件源码里的用法是：
 * ```kotlin
 * override fun getAudioUrlExtractor(): AudioUrlExtractor {
 *     AudioUrlCustomExtractor.setUp { url -> ...; 直链 }
 *     return AudioUrlCustomExtractor
 * }
 * ```
 * 这两行都依赖「object 语义」：
 * - `AudioUrlCustomExtractor.setUp{...}` —— 对 object 直接点调（编译成对 `INSTANCE` 的调用）
 * - `return AudioUrlCustomExtractor` —— object 名本身当表达式用
 *
 * 若宿主用 Java 的「普通类 + 静态 INSTANCE 字段」模拟，**运行期**没问题
 * （dex 只认 `sget INSTANCE` + `invoke-virtual setUp`），但 Kotlin **编译期**
 * 会报 "does not have a companion object"。用户若基于宿主 API 重新编译插件就会失败。
 * 所以老老实实用 Kotlin 的 `object` 声明，两边都对。
 *
 * ## 全局单例 → 必须串行化
 *
 * `setUp` 把解析函数写进 object 的成员字段，是**全局可变状态**。两个源并发取链
 * 会互相覆盖 parse 导致取链错乱，所以 [setupAndExtract] 把「装 + 取 + 清空」
 * 包在同一把锁内。
 */
object AudioUrlCustomExtractor : AudioUrlExtractor {

    /** 插件通过 setUp 注册的解析函数 */
    @Volatile
    private var parse: ((String) -> String)? = null

    /** 全局锁：保护 setUp / parse 的原子性 */
    private val lock = Any()

    /**
     * 插件调用：注册「章节地址 -> 音频直链」的解析函数。
     *
     * 参数类型就是 Kotlin 的 `(String) -> String`，JVM 上即
     * `Function1<String, String>`，与插件 dex 里的方法引用精确一致。
     */
    fun setUp(parse: (String) -> String) {
        synchronized(lock) {
            this.parse = parse
        }
    }

    override fun extract(url: String, autoPlay: Boolean, isCache: Boolean, isDebug: Boolean) {
        synchronized(lock) {
            parseLocked(url)
        }
    }

    /**
     * 适配层专用：在锁内完成「装解析函数 + 立刻取链 + 清空」，
     * 彻底避免跨源竞态。返回解析出的音频地址（失败返回空串）。
     */
    @JvmStatic
    fun setupAndExtract(parse: (String) -> String, chapterUrl: String): String {
        synchronized(lock) {
            this.parse = parse
            return try {
                parseLocked(chapterUrl)
            } finally {
                this.parse = null
            }
        }
    }

    /** 调用 parse。必须在 [lock] 内。 */
    private fun parseLocked(url: String): String {
        val f = parse ?: return ""
        return try {
            f(url)
        } catch (t: Throwable) {
            ""
        }
    }
}
