package com.github.eprendre.tingshu.sources

/**
 * 兼容原版插件的「直链取链」提取器：章节地址本身就是最终音频地址。
 *
 * <p>插件按 {@code AudioUrlDirectExtractor.INSTANCE} 取单例
 * （引用它的源：BoKanYouSheng）。
 *
 * <p>宿主侧无需回调：TingShuAdapter 拿到该类型时直接把章节地址当音频直链。
 * extract 因此不抛异常、也不产生任何动作。
 */
class AudioUrlDirectExtractor private constructor() : AudioUrlExtractor {

    override fun extract(url: String, autoPlay: Boolean, isCache: Boolean, isDebug: Boolean) {
        // no-op：章节地址即直链，宿主直接使用
    }

    companion object {
        /** 对应原版 object 的 INSTANCE 字段（插件 field 表里引用了它） */
        @JvmField
        val INSTANCE = AudioUrlDirectExtractor()
    }
}
