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
import show.taps.App
import show.taps.MainActivity
import show.taps.R
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

        /** 用户点了安全提示里的「继续」 */
        fun confirm() {
            instance?.cancelSafetyTimer()
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var active = false

    private val safetyStop = Runnable {
        Log.w(TAG, "safety timeout, stop overlay")
        toast(R.string.toast_safety_stopped)
        stopOverlay()
    }

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
        cancelSafetyTimer()
        if (active) {
            TouchSources.stop()
            OverlayController.hide()
            active = false
        }
        App.running.value = false
        instance = null
        super.onDestroy()
    }

    /* ------------------- 内部实现 ------------------- */

    private fun startOverlay(dev: String?) {
        if (active) {
            cancelSafetyTimer()
            scheduleSafetyTimer()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "no overlay permission")
            toast(R.string.toast_need_overlay_permission)
            App.running.value = false
            stopSelf()
            return
        }

        val view = OverlayController.show(this)
        if (view == null) {
            toast(R.string.toast_launch_fail)
            App.running.value = false
            stopSelf()
            return
        }

        TouchSources.start(this, dev, view) { ok ->
            if (ok) {
                active = true
                App.running.value = true
                scheduleSafetyTimer()
            } else {
                Log.e(TAG, "touch source start failed")
                toast(R.string.toast_launch_fail)
                stopOverlay()
            }
        }
    }

    private fun stopOverlay() {
        cancelSafetyTimer()
        TouchSources.stop()
        OverlayController.hide()
        active = false
        App.running.value = false
        stopForegroundCompat()
        stopSelf()
    }

    private fun scheduleSafetyTimer() {
        handler.postDelayed(safetyStop, GlobalSettings.WAIT_CONTINUE_TIME)
    }

    private fun cancelSafetyTimer() {
        handler.removeCallbacks(safetyStop)
    }

    private fun toast(resId: Int) {
        mainHandlerToast(resId)
    }

    private fun mainHandlerToast(resId: Int) {
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(applicationContext, resId, Toast.LENGTH_SHORT).show() }
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
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_running))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
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
