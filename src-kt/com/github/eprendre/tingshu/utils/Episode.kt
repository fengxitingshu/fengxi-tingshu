package com.github.eprendre.tingshu.utils

/**
 * 兼容原版插件的章节（集）数据类。
 *
 * ```kotlin
 * data class Episode(val title: String, val url: String) {
 *     var isFree: Boolean = true
 *     @Transient var isCached: Boolean = false
 *     var progress: Int = 0
 *     var coverUrl: String = ""
 *     var transcript: String = ""
 * }
 * ```
 *
 * ## 为什么 v3.3 从 Java 迁到 Kotlin
 *
 * 插件对 `Episode` 只引用**完整 2 参构造器** `<init>(String, String)`，
 * Java 版本来就能满足。迁移的唯一原因是 [BookDetail] 需要在 Kotlin 侧
 * 声明（为了得到默认参数合成构造器），而 Kotlin 编译器编译 BookDetail 时
 * 看不到尚未由 javac 产出的 Java `Episode`。两者放在同一语言里最省事。
 *
 * 字段用 `@JvmField` 暴露成公共字段，保持与原版一致（插件可能直接 `iget`）。
 */
data class Episode(
    @JvmField val title: String,
    @JvmField val url: String
) {
    @JvmField
    var isFree: Boolean = true

    /** 原版标了 @Transient */
    @Transient
    @JvmField
    var isCached: Boolean = false

    @JvmField
    var progress: Int = 0

    @JvmField
    var coverUrl: String = ""

    @JvmField
    var transcript: String = ""

    // ---- Java 侧（TingShuAdapter）走 setter/getter，与原版一致 ----
    fun getTitle(): String = title
    fun getUrl(): String = url

    fun isFree(): Boolean = isFree
    fun setFree(v: Boolean) { isFree = v }

    fun isCached(): Boolean = isCached
    fun setCached(v: Boolean) { isCached = v }

    fun getProgress(): Int = progress
    fun setProgress(v: Int) { progress = v }

    fun getCoverUrl(): String = coverUrl
    fun setCoverUrl(v: String?) { coverUrl = v ?: "" }

    fun getTranscript(): String = transcript
    fun setTranscript(v: String?) { transcript = v ?: "" }
}
