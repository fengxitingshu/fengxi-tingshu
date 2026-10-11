package com.github.eprendre.tingshu.utils

/**
 * 兼容原版插件的书本详情。
 *
 * ```kotlin
 * data class BookDetail(
 *     val playList: List<Episode>,
 *     val intro: String? = "",
 *     val artist: String = "",
 *     val author: String = "",
 *     val episodesCount: Int = 0,
 *     val coverUrl: String = ""
 * ) { val title: String = "" }
 * ```
 *
 * ## 为什么必须用 Kotlin 写（v3.3 从 Java 迁移过来）
 *
 * 原版是 Kotlin data class，带默认参数。编译器除了主构造器
 * `BookDetail(List, String, String, String, I, String)` 之外，还会生成一个
 * **合成的「默认参数」构造器**：
 * ```
 * <init>(List, String, String, String, I, String, I, DefaultConstructorMarker)
 * ```
 * 插件源码里用的是省略写法（如 `BookDetail(playList)`），编译后指向的正是
 * 这个合成构造器 —— **它只存在于 Kotlin 编译产物里，Java 无法声明**
 * （Java 没有「构造器默认参数桥接」这个概念）。
 *
 * 之前用 Java 手写时，只能提供 6 参主构造器 + 1 参重载，插件只要一进详情页
 * 就会 `NoSuchMethodError`。改成 Kotlin 声明后，编译器自动产出与插件引用
 * 完全一致的合成构造器。
 *
 * ## 与主构造器的关系
 *
 * `List<Episode>` 对应原版；`title` 在 body 里是只读属性（默认空串），
 * 因此不在构造器参数中。
 */
data class BookDetail(
    @JvmField val playList: List<Episode>,
    @JvmField val intro: String? = "",
    @JvmField val artist: String = "",
    @JvmField val author: String = "",
    @JvmField val episodesCount: Int = 0,
    @JvmField val coverUrl: String = ""
) {
    /** 原版是 data class body 里的 val title，默认空串 */
    @JvmField
    val title: String = ""

    // ---- Java 侧（TingShuAdapter）用 getter 读，保持与原版一致 ----
    fun getPlayList(): List<Episode> = playList
    fun getIntro(): String = intro ?: ""
    fun getArtist(): String = artist
    fun getAuthor(): String = author
    fun getEpisodesCount(): Int = episodesCount
    fun getCoverUrl(): String = coverUrl
    fun getTitle(): String = title
}
