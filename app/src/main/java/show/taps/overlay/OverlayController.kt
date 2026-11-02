package show.taps.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * 覆盖层的创建/销毁。必须由 App 自身进程调用：
 * Android 16 起 AOSP 的 Session 构造函数要求调用方 pid 存在于 AMS 的 ProcessMap，
 * app_process 进程（Shizuku 用户服务 / root 守护进程）调 openSession 会直接抛
 * IllegalStateException("Unknown pid=…") —— 本机实测 2041/2039/2038 全部失败。
 *
 * 两种宿主（见 [Host]）：
 *   APP           —— TYPE_APPLICATION_OVERLAY(2038)，需要「悬浮窗」权限，窗口层级 11，
 *                    **压在状态栏(15)/导航栏(24)下面**。
 *   ACCESSIBILITY —— TYPE_ACCESSIBILITY_OVERLAY(2032)，由无障碍服务提供 token，层级 31，
 *                    状态栏、导航栏、锁屏对话框之上，仅次于指针/安全覆盖层。
 */
object OverlayController {

    private const val TAG = "OverlayController"

    enum class Host { APP, ACCESSIBILITY }

    private var view: MainView? = null

    /** 记住创建窗口用的 WindowManager（宿主可能是 Service，必须成对使用） */
    private var windowManager: WindowManager? = null

    private var appContext: Context? = null

    /** 用户是否要求「画到状态栏之上」 */
    private var preferAccessibility = false

    @Volatile
    var host: Host = Host.APP
        private set

    val current: MainView? get() = view

    val isShowing: Boolean get() = view != null

    /**
     * @param preferAccessibility 用户打开了「Draw over the status bar」并且无障碍服务已启用时，
     *                            用无障碍覆盖层；否则退回普通悬浮窗。
     */
    fun show(context: Context, preferAccessibility: Boolean = false): MainView? {
        view?.let { return it }

        val app = context.applicationContext
        appContext = app
        this.preferAccessibility = preferAccessibility

        val a11y = if (preferAccessibility) TapOverlayAccessibilityService.instance else null
        if (preferAccessibility && a11y == null) {
            Log.w(TAG, "accessibility overlay requested but service is not connected, fallback to app overlay")
        }

        return try {
            val hostContext: Context = a11y ?: app
            val wm = hostContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val created = MainView(hostContext)

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
                type = if (a11y != null)
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
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
            windowManager = wm
            host = if (a11y != null) Host.ACCESSIBILITY else Host.APP
            Log.d(TAG, "overlay shown, host=$host type=${lp.type} layer=${windowLayerHint(lp.type)}")
            created
        } catch (t: Throwable) {
            Log.e(TAG, "show failed", t)
            view = null
            windowManager = null
            null
        }
    }

    fun hide() {
        val created = view ?: return
        val wm = windowManager
        view = null
        windowManager = null
        try {
            if (wm != null) wm.removeViewImmediate(created)
            Log.d(TAG, "overlay hidden")
        } catch (t: Throwable) {
            Log.e(TAG, "hide failed", t)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val reattachRunnable = Runnable { reattachIfHostChanged() }

    /**
     * 无障碍服务连上/断开时调用。若覆盖层正在显示，先撤掉再用新的宿主重建，
     * 这样用户开启无障碍后无需重启 App 就能立刻盖住状态栏。
     *
     * 为什么要延迟 400ms 再判断：无障碍服务会被系统反复捆绑/解绑（实测每次
     * uiautomator/自动化测试服务注册都会触发 AccessibilityManagerService.updateServicesLocked，
     * 把所有服务解绑再绑回来）。若每次事件都立即重建窗口，会发生 2 秒一次的闪烁。
     * 这里等状态稳定后再对比「期望宿主 vs 当前宿主」，一样就什么都不做。
     */
    fun onOverlayHostChanged() {
        mainHandler.removeCallbacks(reattachRunnable)
        mainHandler.postDelayed(reattachRunnable, 400L)
    }

    private fun reattachIfHostChanged() {
        val ctx = appContext ?: return
        if (view == null) return
        val desired = if (preferAccessibility && TapOverlayAccessibilityService.instance != null) {
            Host.ACCESSIBILITY
        } else {
            Host.APP
        }
        if (desired == host) return
        Log.d(TAG, "host changed $host -> $desired, re-attach overlay")
        hide()
        show(ctx, preferAccessibility)
    }

    /** 仅用于日志：把窗口类型换成 AOSP 层级号，排障时一眼能看出用的是哪种窗口 */
    private fun windowLayerHint(type: Int): String = when (type) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY -> "11 (below status bar)"
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY -> "31 (above status bar)"
        else -> "?"
    }
}
