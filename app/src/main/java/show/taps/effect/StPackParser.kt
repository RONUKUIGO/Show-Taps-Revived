package show.taps.effect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * pack.json → [StPack]。
 *
 * 所有错误都带定位信息（`图层 press[1].radius`），导入时直接把信息弹给用户，
 * 免得作者对着「导入失败」猜。
 */
object StPackParser {

    const val SUPPORTED_SCHEMA = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): StPack {
        val root = (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)
            ?: error("pack.json 不是合法 JSON 对象")

        val schema = root.intOr("schema", SUPPORTED_SCHEMA)
        if (schema > SUPPORTED_SCHEMA) {
            error("包声明的 schema=$schema 高于当前引擎支持的 $SUPPORTED_SCHEMA")
        }
        val engine = root.strOr("engine") ?: "native"
        if (engine != "native" && engine != "sprite") {
            error("engine=\"$engine\" 不受支持（当前只有 native，即声明式图层）")
        }

        val layersObj = root["layers"] as? JsonObject
            ?: error("缺少 layers 字段")

        fun group(key: String): List<StLayer> {
            val arr = layersObj[key] as? JsonArray ?: return emptyList()
            return arr.mapIndexedNotNull { index, el ->
                val obj = el as? JsonObject ?: error("layers.$key[$index] 不是对象")
                parseLayer(obj, "layers.$key[$index]")
            }
        }

        val pack = StPack(
            name = root.strOr("name") ?: "未命名效果包",
            schema = schema,
            press = group("press"),
            hold = group("hold"),
            trail = group("trail"),
            release = group("release"),
        )
        if (pack.press.isEmpty() && pack.hold.isEmpty() && pack.trail.isEmpty() && pack.release.isEmpty()) {
            error("layers 里一个图层都没有")
        }
        return pack
    }

    /* --------------------------- 单个图层 --------------------------- */

    private fun parseLayer(o: JsonObject, where: String): StLayer {
        val typeStr = o.strOr("type") ?: error("$where 缺少 type")
        val type = when (typeStr.lowercase()) {
            "sprite" -> StLayerType.SPRITE
            "ring" -> StLayerType.RING
            "dot" -> StLayerType.DOT
            else -> error("$where: 未知 type \"$typeStr\"（支持 sprite / ring / dot）")
        }

        val easing = StEasing.parse(o.strOr("curve"))

        val tex = o.strOr("tex")
        if (type == StLayerType.SPRITE && tex.isNullOrBlank()) {
            error("$where: sprite 图层必须给 tex")
        }

        val duration = o.intOr("durationMs", 300)
        if (duration < 0) error("$where: durationMs 不能为负")

        return StLayer(
            type = type,
            tex = tex,
            delayMs = o.intOr("delayMs", 0).toLong(),
            durationMs = duration.toLong(),
            loop = o.boolOr("loop", false),
            radius = curveOr(o["radius"], 12f, easing, "$where.radius"),
            alpha = curveOr(o["alpha"], 1f, easing, "$where.alpha"),
            width = curveOr(o["width"], 1.5f, easing, "$where.width"),
            blend = when ((o.strOr("blend") ?: "plus").lowercase()) {
                "plus", "add", "additive" -> StBlend.PLUS
                "normal", "src_over", "src-over", "over" -> StBlend.NORMAL
                "screen" -> StBlend.SCREEN
                else -> error("$where: 未知 blend（支持 plus / normal / screen）")
            },
            tint = parseTint(o["tint"], where),
            tintPerFinger = (o.strOr("tint")?.equals("auto", true) == true) ||
                    o.boolOr("tintPerFinger", false),
            rotateStart = o.floatOr("rotateStart", 0f),
            rotateSpeed = o.floatOr("rotateSpeed", 0f),
            decayMs = o.intOr("decayMs", 200).toLong().coerceAtLeast(1L),
            spacing = o.floatOr("spacing", 10f).coerceAtLeast(0f),
            maxPoints = o.intOr("maxPoints", 16).coerceIn(1, 512),
        )
    }

    private fun curveOr(el: JsonElement?, def: Float, easing: StEasing, where: String): StCurve {
        if (el == null) return StCurve.single(def)
        if (el is JsonPrimitive) {
            return StCurve.single(numberOr(el, where) ?: def)
        }
        if (el is JsonArray) {
            if (el.isEmpty()) return StCurve.single(def)
            val first = el.first()
            if (first is JsonPrimitive) {
                // [from, to]
                val from = numberOr(first, where) ?: return StCurve.single(def)
                val to = (el.getOrNull(1) as? JsonPrimitive)?.let { numberOr(it, where) } ?: from
                return StCurve.range(from, to, easing)
            }
            // 关键帧 [[t, v], ...]
            val pts = ArrayList<Pair<Float, Float>>(el.size)
            for ((i, kf) in el.withIndex()) {
                val arr = kf as? JsonArray ?: error("$where[$i] 关键帧应为 [时间, 数值]")
                val t = (arr.getOrNull(0) as? JsonPrimitive)?.let { numberOr(it, "$where[$i]") }
                    ?: error("$where[$i] 缺少时间")
                val v = (arr.getOrNull(1) as? JsonPrimitive)?.let { numberOr(it, "$where[$i]") }
                    ?: error("$where[$i] 缺少数值")
                pts += t.coerceIn(0f, 1f) to v
            }
            return StCurve.keyframes(pts)
        }
        error("$where: 无法识别的曲线写法")
    }

    private fun parseTint(el: JsonElement?, where: String): Int? {
        val p = el as? JsonPrimitive ?: return null
        val raw = p.contentOrNull ?: return null
        if (raw.equals("auto", true)) return null
        if (!p.isString) return raw.toLongOrNull()?.toInt()
        var t = raw.trim()
        if (t.startsWith("#")) t = t.substring(1)
        else if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2)
        if (t.length !in 6..8) error("$where: 颜色 \"$raw\" 不合法（要 #RRGGBB 或 #AARRGGBB）")
        val v = t.toLongOrNull(16) ?: error("$where: 颜色 \"$raw\" 不合法")
        return if (t.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }

    /* --------------------------- 小工具 --------------------------- */

    private fun numberOr(p: JsonPrimitive, where: String): Float? {
        val raw = p.contentOrNull ?: return null
        return raw.toFloatOrNull() ?: error("$where: \"$raw\" 不是数字")
    }

    private fun JsonObject.strOr(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.intOr(key: String, def: Int): Int {
        val p = this[key] as? JsonPrimitive ?: return def
        val raw = p.contentOrNull ?: return def
        return raw.toIntOrNull() ?: raw.toLongOrNull()?.toInt() ?: raw.toFloatOrNull()?.toInt() ?: def
    }

    private fun JsonObject.floatOr(key: String, def: Float): Float {
        val p = this[key] as? JsonPrimitive ?: return def
        return p.contentOrNull?.toFloatOrNull() ?: def
    }

    private fun JsonObject.boolOr(key: String, def: Boolean): Boolean {
        val p = this[key] as? JsonPrimitive ?: return def
        return p.contentOrNull?.toBooleanStrictOrNull() ?: def
    }
}
