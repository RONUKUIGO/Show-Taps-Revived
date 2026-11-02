package show.taps.effect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.util.Log
import show.taps.overlay.GlobalSettings
import show.taps.overlay.MainView
import java.io.File

/**
 * 效果包渲染器：把 [StPack] 的图层画到覆盖层的 Canvas 上。
 *
 * 由 [MainView.onDraw] 调用；[MainView] 只管「触摸状态 + 环形缓冲」，
 * 这里负责「时间 → 曲线 → 绘制」，两者职责分开，包换掉不用动 MainView。
 *
 * 性能约束（10 指 × 120Hz）：
 *   · Paint 按 (blend, style, tint) 缓存，不在 onDraw 里 new
 *   · RectF / FloatArray 复用
 *   · 贴图解码一次并缓存，超过 256px 自动降采样
 */
class StPackRenderer(
    private val pack: StPack,
    private val assetsDir: File?,
) {

    companion object {
        private const val TAG = "StPackRenderer"
        private const val MAX_TEX_PX = 256
    }

    /** 贴图路径缓存：key = 包内相对路径 */
    private val textures = HashMap<String, Bitmap?>()

    /** Paint 缓存：key = blend|style|tint */
    private val paints = HashMap<String, Paint>()

    private val dst = RectF()
    private val pt = FloatArray(2)

    /** 本帧结束后是否还需要下一帧（决定 MainView 要不要 postInvalidate） */
    var needNextFrame: Boolean = false
        private set

    val packName: String get() = pack.name

    fun draw(canvas: Canvas, view: MainView, now: Long): Boolean {
        var need = false
        val dp = view.resources.displayMetrics.density
        val pressed = view.listPressedPoint

        for (slot in pressed.indices) {
            val p = pressed[slot]
            if (p.x < -9000f && p.y < -9000f) continue

            if (p.pressing) {
                val elapsed = now - p.pressTime
                if (elapsed in 0..(pack.pressMaxMs + 50)) {
                    if (drawGroup(canvas, view, pack.press, elapsed, p.x, p.y, slot, dp)) need = true
                }
                if (pack.hasHold) {
                    if (drawGroup(canvas, view, pack.hold, elapsed, p.x, p.y, slot, dp)) need = true
                }
            } else if (p.releaseTime > 0L) {
                val elapsed = now - p.releaseTime
                if (elapsed in 0..(pack.releaseMaxMs + 50)) {
                    if (drawGroup(canvas, view, pack.release, elapsed, p.x, p.y, slot, dp)) need = true
                }
            }
        }

        if (pack.trail.isNotEmpty() && drawTrail(canvas, view, now, dp)) need = true

        needNextFrame = need
        return need
    }

    /* --------------------------- 分组绘制 --------------------------- */

    private fun drawGroup(
        canvas: Canvas,
        view: MainView,
        layers: List<StLayer>,
        elapsedMs: Long,
        nx: Float,
        ny: Float,
        slot: Int,
        dp: Float,
    ): Boolean {
        if (layers.isEmpty()) return false
        view.screenPos(nx, ny, pt)
        val sx = pt[0]
        val sy = pt[1]
        var active = false

        for (layer in layers) {
            val t = elapsedMs - layer.delayMs
            if (t < 0L) {
                active = true // 还没开始，还得重绘
                continue
            }
            val dur = layer.durationMs
            val p: Float
            if (layer.loop) {
                p = if (dur <= 0L) 0f else (t % dur).toFloat() / dur
                active = true
            } else {
                if (t > dur) continue
                p = if (dur <= 0L) 1f else t.toFloat() / dur
                active = true
            }
            drawLayer(canvas, layer, p, t.toFloat(), sx, sy, slot, dp)
        }
        return active
    }

    private fun drawLayer(
        canvas: Canvas,
        layer: StLayer,
        progress: Float,
        elapsedMs: Float,
        sx: Float,
        sy: Float,
        slot: Int,
        dp: Float,
    ) {
        val alpha = layer.alpha.at(progress).coerceIn(0f, 1f)
        if (alpha <= 0.004f) return
        val radiusPx = layer.radius.at(progress) * dp
        if (radiusPx <= 0.01f) return

        val tint = when {
            layer.tintPerFinger -> {
                val colors = GlobalSettings.colorArray
                if (colors.isEmpty()) null else colors[slot % colors.size]
            }
            else -> layer.tint
        }

        when (layer.type) {
            StLayerType.SPRITE -> {
                val bmp = texture(layer.tex) ?: return
                val paint = paintFor(layer.blend, Paint.Style.FILL, tint)
                paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
                val d = radiusPx * 2f
                dst.set(sx - d / 2f, sy - d / 2f, sx + d / 2f, sy + d / 2f)
                val rot = layer.rotateStart + layer.rotateSpeed * (elapsedMs / 1000f)
                if (rot != 0f) {
                    val save = canvas.save()
                    canvas.rotate(rot, sx, sy)
                    canvas.drawBitmap(bmp, null, dst, paint)
                    canvas.restoreToCount(save)
                } else {
                    canvas.drawBitmap(bmp, null, dst, paint)
                }
            }

            StLayerType.RING -> {
                val paint = paintFor(layer.blend, Paint.Style.STROKE, tint)
                paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
                paint.strokeWidth = (layer.width.at(progress) * dp).coerceAtLeast(0.5f)
                val rot = layer.rotateStart + layer.rotateSpeed * (elapsedMs / 1000f)
                if (rot != 0f) {
                    // 圆环本身旋转看不出来，但配 dashed/弧线素材时有用；保持行为一致
                    val save = canvas.save()
                    canvas.rotate(rot, sx, sy)
                    canvas.drawCircle(sx, sy, radiusPx, paint)
                    canvas.restoreToCount(save)
                } else {
                    canvas.drawCircle(sx, sy, radiusPx, paint)
                }
            }

            StLayerType.DOT -> {
                val paint = paintFor(layer.blend, Paint.Style.FILL, tint)
                paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(sx, sy, radiusPx, paint)
            }
        }
    }

    /* --------------------------- 拖尾 --------------------------- */

    private fun drawTrail(canvas: Canvas, view: MainView, now: Long, dp: Float): Boolean {
        if (pack.trailMaxMs <= 0L) return false
        var active = false
        val ring = view.list
        val size = ring.size

        for (layer in pack.trail) {
            val decay = layer.decayMs
            val minGapPx = layer.spacing * dp
            var lastX = Float.NaN
            var lastY = Float.NaN
            var drawn = 0

            for (k in 1..minOf(layer.maxPoints + 8, size)) {
                val idx = ((view.i - k) % size + size) % size
                val item = ring[idx]
                if (item.time == 0L) break
                val age = now - item.time
                if (age < 0L || age > decay) break
                if (item.x < -9000f) continue

                view.screenPos(item.x, item.y, pt)
                val x = pt[0]
                val y = pt[1]

                if (!lastX.isNaN()) {
                    val d = Math.hypot((x - lastX).toDouble(), (y - lastY).toDouble()).toFloat()
                    if (d < minGapPx) continue
                }
                val p = 1f - age.toFloat() / decay
                drawLayer(canvas, layer, p, age.toFloat(), x, y, item.pointerId, dp)
                lastX = x
                lastY = y
                drawn++
                if (drawn >= layer.maxPoints) break
                active = true
            }
        }
        return active
    }

    /* --------------------------- 资源 --------------------------- */

    private fun paintFor(blend: StBlend, style: Paint.Style, tint: Int?): Paint {
        val key = "$blend|$style|${tint ?: 0}"
        return paints.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = style
                strokeCap = Paint.Cap.ROUND
                color = tint ?: Color.WHITE
                if (tint != null) colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                when (blend) {
                    StBlend.PLUS -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            blendMode = BlendMode.PLUS
                        } else {
                            @Suppress("DEPRECATION")
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                        }
                    }
                    StBlend.SCREEN -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            blendMode = BlendMode.SCREEN
                        } else {
                            @Suppress("DEPRECATION")
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                        }
                    }
                    StBlend.NORMAL -> Unit
                }
            }
        }
    }

    private fun texture(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        return textures.getOrPut(path) {
            val dir = assetsDir
            if (dir == null) {
                Log.w(TAG, "no assets dir for tex=$path")
                return@getOrPut null
            }
            // 防目录穿越：只允许包内相对路径
            if (path.contains("..")) {
                Log.w(TAG, "illegal tex path: $path")
                return@getOrPut null
            }
            val f = File(dir, path)
            if (!f.isFile) {
                Log.w(TAG, "tex not found: ${f.absolutePath}")
                return@getOrPut null
            }
            runCatching { decodeScaled(f) }.onFailure {
                Log.w(TAG, "decode failed: $path $it")
            }.getOrNull()
        }
    }

    private fun decodeScaled(f: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        var maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample > MAX_TEX_PX) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(f.absolutePath, opts)
    }
}
