package show.taps.effect

/**
 * 效果包（v1）的运行时模型。
 *
 * 设计原则：**包只能声明，不能执行代码**。所有动画都是「时间 → 数值」的曲线求值，
 * 曲线只有受控的几种缓动 + 关键帧插值，因此导入陌生包不会带来任何执行风险。
 *
 * 时间单位统一 ms，尺寸单位统一 dp（乘 density 得像素）。
 */
data class StPack(
    val name: String,
    val schema: Int,
    /** 图层组：按下 / 按住 / 拖尾 / 抬起 */
    val press: List<StLayer>,
    val hold: List<StLayer>,
    val trail: List<StLayer>,
    val release: List<StLayer>,
) {
    /** 按下动效最长需要画多久（用于决定何时停止重绘） */
    val pressMaxMs: Long = maxOf(0L, press.maxOfOrNull { it.delayMs + it.durationMs } ?: 0L)

    val releaseMaxMs: Long = maxOf(0L, release.maxOfOrNull { it.delayMs + it.durationMs } ?: 0L)

    val hasHold: Boolean = hold.isNotEmpty()

    val trailMaxMs: Long = maxOf(0L, trail.maxOfOrNull { it.decayMs } ?: 0L)

    companion object {
        val EMPTY = StPack("", 1, emptyList(), emptyList(), emptyList(), emptyList())
    }
}

enum class StLayerType { SPRITE, RING, DOT }

enum class StBlend { PLUS, NORMAL, SCREEN }

data class StLayer(
    val type: StLayerType,
    /** SPRITE 专用：素材相对路径（相对包根目录） */
    val tex: String? = null,
    val delayMs: Long = 0L,
    val durationMs: Long = 300L,
    /** 循环播放（按住时的呼吸圈常用） */
    val loop: Boolean = false,
    /** 半径，dp */
    val radius: StCurve = StCurve.single(12f),
    val alpha: StCurve = StCurve.single(1f),
    /** RING 专用：描边宽度，dp */
    val width: StCurve = StCurve.single(1.5f),
    val blend: StBlend = StBlend.PLUS,
    /** 固定着色； = 保留素材原色 */
    val tint: Int? = null,
    /** 用第 N 根手指的颜色上色（ST 的 10 色） */
    val tintPerFinger: Boolean = false,
    val rotateStart: Float = 0f,
    /** 每秒旋转角度 */
    val rotateSpeed: Float = 0f,
    /* ---- TRAIL 专用 ---- */
    val decayMs: Long = 200L,
    /** 相邻两个点至少间隔多少 dp 才画 */
    val spacing: Float = 10f,
    val maxPoints: Int = 16,
)

/** 受控缓动集合（不开放任意表达式） */
enum class StEasing {
    LINEAR, EASE_IN, EASE_OUT, EASE_OUT_QUAD, EASE_OUT_CUBIC, EASE_IN_OUT_QUAD, BACK_OUT;

    fun apply(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> x
            EASE_IN -> x * x
            EASE_OUT -> 1f - (1f - x) * (1f - x)
            EASE_OUT_QUAD -> 1f - (1f - x) * (1f - x)
            EASE_OUT_CUBIC -> {
                val p = 1f - x
                1f - p * p * p
            }
            EASE_IN_OUT_QUAD ->
                if (x < 0.5f) 2f * x * x else 1f - Math.pow((-2f * x + 2f).toDouble(), 2.0).toFloat() / 2f
            BACK_OUT -> {
                val c1 = 1.70158f
                val c3 = c1 + 1f
                1f + c3 * Math.pow((x - 1f).toDouble(), 3.0).toFloat() +
                        c1 * Math.pow((x - 1f).toDouble(), 2.0).toFloat()
            }
        }
    }

    companion object {
        fun parse(name: String?): StEasing = when (name?.lowercase()) {
            null, "", "linear" -> LINEAR
            "easein" -> EASE_IN
            "easeout" -> EASE_OUT
            "easeoutquad" -> EASE_OUT_QUAD
            "easeoutcubic" -> EASE_OUT_CUBIC
            "easeinoutquad", "easeinout" -> EASE_IN_OUT_QUAD
            "backout" -> BACK_OUT
            else -> LINEAR
        }
    }
}

/**
 * 「时间进度 0..1 → 数值」的曲线。三种写法：
 *   固定值   `12`
 *   起止值   `[12, 42]`（配 `curve` 缓动）
 *   关键帧   `[[0,12],[0.2,40],[1,0]]`（分段线性）
 */
class StCurve private constructor(
    private val ks: List<Pair<Float, Float>>,
    private val easing: StEasing,
    private val isRange: Boolean,
) {
    fun at(progress: Float): Float {
        if (ks.isEmpty()) return 0f
        val p = progress.coerceIn(0f, 1f)
        if (isRange) {
            val a = ks.first().second
            val b = ks.last().second
            return a + (b - a) * easing.apply(p)
        }
        if (p <= ks.first().first) return ks.first().second
        for (k in 1 until ks.size) {
            val (x0, y0) = ks[k - 1]
            val (x1, y1) = ks[k]
            if (p <= x1) {
                val span = x1 - x0
                val f = if (span <= 0.00001f) 1f else (p - x0) / span
                return y0 + (y1 - y0) * f
            }
        }
        return ks.last().second
    }

    companion object {
        fun single(v: Float) = StCurve(listOf(0f to v, 1f to v), StEasing.LINEAR, false)

        fun range(from: Float, to: Float, easing: StEasing) =
            StCurve(listOf(0f to from, 1f to to), easing, true)

        fun keyframes(points: List<Pair<Float, Float>>) =
            StCurve(points.sortedBy { it.first }, StEasing.LINEAR, false)
    }
}
