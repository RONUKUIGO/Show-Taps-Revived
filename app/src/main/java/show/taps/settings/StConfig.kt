package show.taps.settings

import kotlinx.serialization.Serializable

/**
 * 一套 ST（Show Taps）绘制配置。字段全部对应 [show.taps.overlay.GlobalSettings]。
 */
@Serializable
data class StConfig(
    val id: Long = 0L,
    val name: String = "默认配置",
    /** 圆环直径，dp */
    val pointSize: Int = 80,
    /** 轨迹淡出时间，ms */
    val fadeTime: Int = 200,
    /** 圆环描边，dp */
    val circleStroke: Int = 5,
    /** 轨迹线描边，dp */
    val lineStroke: Int = 5,
    /** 整体透明度 0..100 */
    val alpha: Int = 100,
    /** 10 指颜色（ARGB） */
    val colors: List<Int> = DEFAULT_COLORS,
    /** 关联的效果包 id（StPresetStore 里的 PACK）； = 用内置绘制规则 */
    val packId: String? = null,
) {
    companion object {
        val DEFAULT_COLORS: List<Int> = listOf(
            0xFF8B00FF.toInt(), // 紫
            0xFF00FF00.toInt(), // 绿
            0xFFFFA500.toInt(), // 橙
            0xFFFF0000.toInt(), // 红
            0xFF0000FF.toInt(), // 蓝
            0xFFFFFF00.toInt(), // 黄
            0xFF00BFFF.toInt(), // 淡蓝
            0xFFFF00FF.toInt(), // 粉
            0xFFFF6666.toInt(), // 浅红
            0xFFFFB266.toInt(), // 浅橙
        )

        val DEFAULT = StConfig(id = 1L)
    }

    /** 列表页摘要 */
    fun summary(): String =
        if (packId != null) {
            "效果包 · 透明度 ${alpha}%"
        } else {
            "大小 ${pointSize}dp · 淡出 ${fadeTime}ms · 描边 ${circleStroke}/${lineStroke}dp · 透明度 ${alpha}%"
        }
}

@Serializable
data class StConfigSet(
    val configs: List<StConfig> = emptyList(),
    val activeId: Long = 0L,
) {
    val active: StConfig? get() = configs.firstOrNull { it.id == activeId } ?: configs.firstOrNull()
}
