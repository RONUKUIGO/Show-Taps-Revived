package show.taps.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * 覆盖层的创建/销毁。必须由 App 自身进程调用：
 * Android 16 起 AOSP 的 Session 构造函数要求调用方 pid 存在于 AMS 的 ProcessMap，
 * app_process 进程（Shizuku 用户服务 / root 守护进程）调 openSession 会直接抛
 * IllegalStateException("Unknown pid=…") —— 本机实测 2041/2039/2038 全部失败。
 */
object OverlayController {

    private const val TAG = "OverlayController"

    private var view: MainView? = null

    val current: MainView? get() = view

    val isShowing: Boolean get() = view != null

    fun show(context: Context): MainView? {
        view?.let { return it }
        return try {
            val created = MainView(context)
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val lp = WindowManager.LayoutParams().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                }
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                        WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                // Android 8.0+ 唯一的应用级覆盖类型；需要「悬浮窗」权限
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                gravity = Gravity.TOP or Gravity.START
                format = PixelFormat.RGBA_8888
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                x = 0
                y = 0
            }

            created.alpha = GlobalSettings.colorAlpha / 100f
            wm.addView(created, lp)
            view = created
            Log.d(TAG, "overlay shown")
            created
        } catch (t: Throwable) {
            Log.e(TAG, "show failed", t)
            null
        }
    }

    fun hide() {
        val created = view ?: return
        view = null
        try {
            val wm = created.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(created)
            Log.d(TAG, "overlay hidden")
        } catch (t: Throwable) {
            Log.e(TAG, "hide failed", t)
        }
    }
}
