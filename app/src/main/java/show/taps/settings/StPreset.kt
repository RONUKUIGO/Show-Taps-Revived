package show.taps.settings

import kotlinx.serialization.Serializable

/** 预设（纯参数，套 ST 原绘制规则） / 效果包（需要图片素材 + 新绘制规则） */
@Serializable
enum class StPresetKind { PRESET, PACK }

/**
 * 一条可导入的预设条目。
 *
 * - [StPresetKind.PRESET]：只带一套 [StConfig]，导入后就能直接套用（零素材）。
 * - [StPresetKind.PACK]：带 [packJson]（图层/曲线/素材引用）+ [assetDir]（解压出来的图片），
 *   渲染引擎接入后才能实际作画；当前先做"能导入、能管理、能保存"的可行性验证。
 */
@Serializable
data class StPreset(
    val id: String = "",
    val name: String = "",
    val kind: StPresetKind = StPresetKind.PRESET,
    val desc: String = "",
    val config: StConfig? = null,
    val packJson: String? = null,
    val assetDir: String? = null,
    val importedAt: Long = 0L,
) {
    fun kindLabel(): String = if (kind == StPresetKind.PACK) "效果包" else "预设"

    fun summary(): String = buildString {
        append(kindLabel())
        if (desc.isNotBlank()) append(" · ").append(desc)
        config?.let { append(" · ").append(it.summary()) }
        if (kind == StPresetKind.PACK && assetDir == null) append(" · 无素材")
    }
}
