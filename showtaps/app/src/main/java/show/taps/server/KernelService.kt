package show.taps.server

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import show.taps.BuildConfig
import show.taps.ITouchCallback
import show.taps.KernelInterface
import java.io.File
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val TAG = "KernelService"

/**
 * Shizuku 用户服务：以 shell(uid 2000) 身份运行在独立进程里，只负责
 * 「读 /dev/input/eventX → 把归一化坐标回调给 App」，不再创建任何窗口。
 *
 * Android 16 起 AOSP 的 Session 构造函数要求调用 openSession 的 pid 必须存在于
 * AMS 的 ProcessMap 中（否则 IllegalStateException: Unknown pid=…），
 * 所以 app_process 进程已经无法再 addView —— 覆盖层改由 App 进程绘制。
 */
class KernelService(private val context: Context) : KernelInterface.Stub() {

    @Volatile
    private var running = false

    @Volatile
    private var callback: ITouchCallback? = null

    @Volatile
    private var lastActive = SystemClock.uptimeMillis()

    private val sink = object : NativeLib.Sink {

        override fun onSlotState(slot: Int, down: Boolean) {
            send { it.onSlotState(slot, down) }
        }

        override fun onSlotMove(slot: Int, x: Float, y: Float) {
            send { it.onSlotMove(slot, x, y) }
        }

        override fun onSync() {
            send { it.onSync() }
        }

        /** 回调异常（App 进程已死）时自动收摊，避免原生线程持续报错 */
        private inline fun send(block: (ITouchCallback) -> Unit) {
            val cb = callback ?: return
            try {
                block(cb)
            } catch (e: RemoteException) {
                Log.e(TAG, "callback dead, stop reading", e)
                runCatching { stop() }
            } catch (t: Throwable) {
                Log.e(TAG, "callback error", t)
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun fixVivo() {
        // VIVO android 10
        try {
            val field = Class.forName("android.view.VivoViewRootImplImpl")
                .getDeclaredField("SUPPORT_PCSHARE")
            field.isAccessible = true
            field.set(null, false)
        } catch (e: Exception) {
            Log.d(TAG, "fixVivo: ${e.message}")
        }
    }

    @SuppressLint("PrivateApi")
    private fun fixLge() {
        // LG android 11
        try {
            val field = Class.forName("com.lge.os.Build\$VERSION")
                .getDeclaredField("IS_OS_UPGRADED")
            field.isAccessible = true
            field.set(null, false)
        } catch (e: Exception) {
            Log.d(TAG, "fixLge: ${e.message}")
        }
    }

    init {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                Log.e(TAG, "exception: ", e)
                val dir = File("/data/local/tmp/show.taps/")
                dir.mkdirs()
                val file = File(dir, "crash_${System.currentTimeMillis()}.txt")
                file.createNewFile()
                val info = "pid=${Process.myPid()},uid=${Process.myUid()},t_name=${t.name}"
                file.writeText(info + "\n" + Log.getStackTraceString(e))
            } catch (e: Exception) {
                Log.e(TAG, "loge: ", e)
            } finally {
                exitProcess(0)
            }
        }

        Log.d(TAG, "init! sdk=${android.os.Build.VERSION.SDK_INT}")

        val pidPath = File("/data/local/tmp/show.taps/server_pid/")
        if (!pidPath.exists()) {
            pidPath.mkdirs()
        } else {
            if (pidPath.isFile) {
                pidPath.delete()
                pidPath.mkdirs()
            }
            pidPath.listFiles()?.forEach { runCatching { it.delete() } }
        }
        runCatching { File(pidPath, Process.myPid().toString()).createNewFile() }

        SettingsHack.install()
        fixVivo()
        fixLge()

        // 不读触摸时超时退出；被卸载时立即退出
        thread(name = "avoid_forgetting_exit") {
            while (true) {
                SystemClock.sleep(5000)

                if (!running && SystemClock.uptimeMillis() - lastActive >= 120 * 1000) {
                    Log.d(TAG, "idle timeout, exit")
                    exitProcess(0)
                }

                @SuppressLint("SdCardPath")
                if (!File("/data/data/${BuildConfig.APPLICATION_ID}/").exists()) {
                    Log.d(TAG, "app uninstalled, exit")
                    exitProcess(0)
                }
            }
        }
    }

    @Synchronized
    override fun start(dev: String?, cb: ITouchCallback?): Boolean {
        lastActive = SystemClock.uptimeMillis()
        if (running) stop()

        NativeLib.load()
        val path = InputDevices.pick(dev) ?: return false

        callback = cb
        NativeLib.sink = sink
        if (!NativeLib.start(path)) {
            Log.e(TAG, "start: native start failed, path=$path")
            callback = null
            NativeLib.sink = null
            return false
        }
        running = true
        Log.d(TAG, "start: ok, path=$path")
        return true
    }

    override fun isRunning(): Boolean = running

    @Synchronized
    override fun stop() {
        lastActive = SystemClock.uptimeMillis()
        if (running) {
            runCatching { NativeLib.stop() }
        }
        running = false
        NativeLib.sink = null
        callback = null
        Log.d(TAG, "stop")
    }

    override fun exit() {
        stop()
        exitProcess(0)
    }
}
