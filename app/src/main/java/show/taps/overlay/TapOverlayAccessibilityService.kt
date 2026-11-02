package show.taps.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 唯一的职责：让 App 进程拿到一个 **TYPE_ACCESSIBILITY_OVERLAY 的 WindowToken**。
 *
 * 为什么需要它（Android 16 / AOSP android16-release 源码）：
 *   WindowManagerPolicy#getWindowLayerFromTypeLw
 *     TYPE_APPLICATION_OVERLAY -> 11
 *     TYPE_STATUS_BAR          -> 15
 *     TYPE_NAVIGATION_BAR      -> 24
 *     TYPE_ACCESSIBILITY_OVERLAY -> 31
 *   所以普通悬浮窗永远压在状态栏下面，只有无障碍覆盖层能画到上面。
 *
 * 拿 token 的机制（AccessibilityService.java）：
 *   AccessibilityService#getSystemService(WINDOW_SERVICE) 返回的 WindowManagerImpl
 *   会被框架 setDefaultToken(mWindowToken)（mWindowToken 就是 type=2032 的 token），
 *   WindowManagerImpl#applyTokens 再把它塞进 LayoutParams.token。
 *   WMS#sanitizeWindowType 只认「token.getWindowType() == TYPE_ACCESSIBILITY_OVERLAY」，
 *   所以必须经由无障碍服务拿到这个 WindowManager，本类就是那个入口。
 *
 * 本服务不读屏、不点按、不接管触摸：
 *   accessibility_service_config.xml 里没有 canRequestTouchExplorationMode /
 *   canPerformGestures / canRetrieveWindowContent，事件回调也是空实现。
 */
class TapOverlayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TapA11y"

        @Volatile
        var instance: TapOverlayAccessibilityService? = null
            private set

        /** 无障碍服务是否已被系统绑定（= 无障碍覆盖层模式可用） */
        val isConnected: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "connected")
        // 若覆盖层已经在跑（正常悬浮窗），这里会把它换成无障碍窗口，从而盖住状态栏
        OverlayController.onOverlayHostChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要任何事件；保持空实现，避免额外开销
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "unbound")
        instance = null
        // 窗口的 token 随连接失效，覆盖层需要退回普通悬浮窗
        OverlayController.onOverlayHostChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
