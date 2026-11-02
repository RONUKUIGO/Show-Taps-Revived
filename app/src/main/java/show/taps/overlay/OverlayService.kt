package show.taps.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import show.taps.StRuntime
import show.taps.settings.StSettings
import show.taps.touch.TouchSources

/**
 * 覆盖层的宿主：前台服务，保证 App 进程存活（覆盖层必须由 App 进程绘制）。
 * 只做三件事：起通知、让 [OverlayController] 挂上 View、让 [TouchSources] 开始读触摸。
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"

        private const val CHANNEL_ID = "show_taps"
        private const val NOTIFICATION_ID = 0x5401

        const val ACTION_START = "show.taps.action.START"
        const val ACTION_STOP = "show.taps.action.STOP"
        const val EXTRA_DEV = "dev"

        @Volatile
        var instance: OverlayService? = null
            private set

        val isActive: Boolean
            get() = instance?.active == true

        fun start(context: Context, dev: String?) {
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEV, dev)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    @Volatile
    private var active = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundSafely()
                startOverlay(intent.getStringExtra(EXTRA_DEV))
            }

            ACTION_STOP -> stopOverlay()

            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (active) {
            TouchSources.stop()
            OverlayController.hide()
            active = false
        }
        StRuntime.running.value = false
        instance = null
        super.onDestroy()
    }

    /* ------------------- 内部实现 ------------------- */

    private fun startOverlay(dev: String?) {
        if (active) return
        // 无障碍覆盖层由系统发的 token 授权，不需要「悬浮窗」权限；
        // 普通悬浮窗仍必须检查 SYSTEM_ALERT_WINDOW。
        val a11yOverlay = StSettings.useAccessibilityOverlay(this) &&
                TapOverlayAccessibilityService.isConnected
        if (!a11yOverlay && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "no overlay permission (a11y=$a11yOverlay)")
            toastText("缺少悬浮窗权限")
            StRuntime.running.value = false
            stopSelf()
            return
        }

        val view = OverlayController.show(this, StSettings.useAccessibilityOverlay(this))
        if (view == null) {
            toastText("启动失败")
            StRuntime.running.value = false
            stopSelf()
            return
        }

        TouchSources.start(this, dev, view) { ok ->
            if (ok) {
                active = true
                StRuntime.running.value = true
            } else {
                Log.e(TAG, "touch source start failed")
                toastText("启动失败")
                stopOverlay()
            }
        }
    }

    private fun stopOverlay() {
        TouchSources.stop()
        OverlayController.hide()
        active = false
        StRuntime.running.value = false
        stopForegroundCompat()
        stopSelf()
    }

    private fun toastText(text: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
        }
    }

    /* ------------------- 通知 ------------------- */

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ST 触摸指示",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 通知里的「停止」：把 ACTION_STOP 直接投回本服务（服务已在前台，属于
        // 「已运行服务再收一条 startService」，不受 Android 8+ 后台服务启动限制）
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (OverlayController.host == OverlayController.Host.ACCESSIBILITY) {
            getString(com.rosan.installer.R.string.st_notif_running_a11y)
        } else {
            "ST 正在显示触摸位置"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.rosan.installer.R.drawable.ic_notification_logo)
            .setContentTitle("ST 触摸指示")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, getString(com.rosan.installer.R.string.st_notif_stop), stopIntent)
            .build()
    }

    private fun startForegroundSafely() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "stopForeground failed", t)
        }
    }
}
