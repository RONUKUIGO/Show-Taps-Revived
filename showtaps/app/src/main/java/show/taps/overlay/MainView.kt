package show.taps.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import androidx.core.hardware.display.DisplayManagerCompat
import show.taps.touch.TouchSink

/**
 * 触摸轨迹绘制层。运行在 App 自身进程里（Android 16 起提权进程已无法 addView）。
 */
class MainView(context: Context) : View(context), TouchSink {

    companion object {
        private const val TAG = "MainView"
    }

    // 用于画点
    data class Point(var pointerId: Int, var time: Long, var x: Float, var y: Float, var deviceId: Int)

    // 用户绘制按下的状态, 下标是id
    data class PressedPoint(
        var pressing: Boolean,
        var x: Float,
        var y: Float,
        var deviceId: Int,
        var touchRadius: Float,
        var releaseTime: Long
    )

    // 用于连线, 下标是id (0、1、2、3、4···)
    data class LastPoint(var head: Boolean, var x: Float, var y: Float)

    val list = Array(2048) { Point(0, 0L, -9999f, -9999f, 0) }
    val listPressedPoint = Array(10) { PressedPoint(false, -9999f, -9999f, 0, 0f, 0) }
    private val lastPoint = Array(10) { LastPoint(false, 0f, 0f) }

    @Volatile
    var i = 0

    private val circlePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    @Volatile
    var orientation = Surface.ROTATION_0

    private var posRes = FloatArray(2)

    /* ---------------- TouchSink ---------------- */

    override fun onSlotState(slot: Int, down: Boolean) {
        if (slot < 0 || slot >= listPressedPoint.size) return
        val pressed = listPressedPoint[slot]
        if (pressed.pressing == down) return
        pressed.pressing = down
        if (!down) {
            pressed.releaseTime = SystemClock.uptimeMillis()
            list[i].x = -10000f
        }
    }

    override fun onSlotMove(slot: Int, x: Float, y: Float) {
        val index = i
        val item = list[index]
        item.x = x
        item.y = y
        item.pointerId = slot
        item.time = SystemClock.uptimeMillis()

        if (slot >= 0 && slot < listPressedPoint.size) {
            listPressedPoint[slot].x = x
            listPressedPoint[slot].y = y
        }

        i = if (index + 1 >= list.size) 0 else index + 1
    }

    override fun onSync() {
        postInvalidate()
    }

    /* ---------------- 生命周期 ---------------- */

    private fun refreshOrientation() {
        val display = DisplayManagerCompat.getInstance(context).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        orientation = display.rotation
        Log.d(TAG, "orientation = $orientation")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshOrientation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshOrientation()
    }

    override fun dispatchConfigurationChanged(newConfig: Configuration?) {
        super.dispatchConfigurationChanged(newConfig)
        refreshOrientation()
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        // 覆盖层是 FLAG_NOT_TOUCHABLE，理论上收不到触摸；真收到说明标志位失效，别崩 App
        Log.e(TAG, "unexpected touch event: $event")
        return false
    }

    /* ---------------- 绘制 ---------------- */

    private fun Canvas.drawLine(item: Point) {
        if (lastPoint[item.pointerId].head) {
            if (item.x != -10000f) {
                lastPoint[item.pointerId].head = false
                lastPoint[item.pointerId].x = item.x
                lastPoint[item.pointerId].y = item.y
            }
        } else {
            if (item.x != -10000f) {
                calculate(item.x, item.y)
                val sx = posRes[0]
                val sy = posRes[1]
                calculate(lastPoint[item.pointerId].x, lastPoint[item.pointerId].y)
                val ex = posRes[0]
                val ey = posRes[1]
                drawLine(sx, sy, ex, ey, circlePaint)

                lastPoint[item.pointerId].head = false
                lastPoint[item.pointerId].x = item.x
                lastPoint[item.pointerId].y = item.y
            } else {
                lastPoint[item.pointerId].head = true
            }
        }
    }

    private fun calculate(x: Float, y: Float) {
        when (orientation) {
            Surface.ROTATION_90 -> {
                posRes[0] = y * measuredWidth
                posRes[1] = (1 - x) * measuredHeight
            }

            Surface.ROTATION_180 -> {
                posRes[0] = (1 - x) * measuredWidth
                posRes[1] = (1 - y) * measuredHeight
            }

            Surface.ROTATION_270 -> {
                posRes[0] = (1 - y) * measuredWidth
                posRes[1] = x * measuredHeight
            }

            else -> {
                posRes[0] = x * measuredWidth
                posRes[1] = y * measuredHeight
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        var requirePostInvalidate = false

        val dp = resources.displayMetrics.density
        val uptimeMillis = SystemClock.uptimeMillis()

        val r = dp * GlobalSettings.touchPointSize / 2
        val colors = GlobalSettings.colorArray

        circlePaint.strokeWidth = dp * GlobalSettings.strokeLine

        try {
            for (lastPointItem in lastPoint) {
                lastPointItem.head = true
            }

            val pathFadeTime = GlobalSettings.pathFadeTime
            for (j in list.indices) {
                val index = (j + i + 1) % list.size
                val item = list[index]

                val dt = uptimeMillis - item.time
                if (dt > pathFadeTime || pathFadeTime == 0) {
                    continue
                }

                requirePostInvalidate = true

                if (item.pointerId >= lastPoint.size) {
                    continue
                }

                if (pathFadeTime > 0) {
                    circlePaint.color = colors[item.pointerId % colors.size]
                    canvas.drawLine(item)
                }
            }

            circlePaint.strokeWidth = dp * GlobalSettings.strokeCircle

            val circleFadeTime = GlobalSettings.circleFadeTime
            for (j in listPressedPoint.indices) {
                val pressed = listPressedPoint[j]
                var alpha = 255
                if (!pressed.pressing) {
                    val t = uptimeMillis - pressed.releaseTime
                    if (t > circleFadeTime) {
                        continue
                    }
                    alpha = 255 - (t * 255 / circleFadeTime).toInt()
                    requirePostInvalidate = true
                }

                circlePaint.color = colors[j % colors.size]
                circlePaint.alpha = alpha

                if (r != 0f) {
                    calculate(pressed.x, pressed.y)
                    canvas.drawCircle(posRes[0], posRes[1], r, circlePaint)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDraw: ", e)
        }

        if (requirePostInvalidate) {
            postInvalidate()
        }
    }
}
