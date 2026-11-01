package show.taps.touch

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku
import show.taps.App
import show.taps.BuildConfig
import show.taps.ITouchCallback
import show.taps.KernelInterface
import show.taps.server.KernelService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 通道：App 用 Shizuku 把 [KernelService] 拉起到 shell(uid 2000) 的独立进程，
 * 该进程只负责读 /dev/input，然后把触摸数据通过 binder 回调送回 App。
 */
object ShizukuTouchSource {

    private const val TAG = "ShizukuSource"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var kernel: KernelInterface? = null

    @Volatile
    private var binding = false

    @Volatile
    private var sink: TouchSink? = null

    private val waiters = mutableListOf<(Boolean) -> Unit>()

    private val touchCallback = object : ITouchCallback.Stub() {
        override fun onSlotState(slot: Int, down: Boolean) {
            sink?.onSlotState(slot, down)
        }

        override fun onSlotMove(slot: Int, x: Float, y: Float) {
            sink?.onSlotMove(slot, x, y)
        }

        override fun onSync() {
            sink?.onSync()
        }
    }

    private val serviceArgs: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(
            ComponentName(App.instance.packageName, KernelService::class.java.name)
        )
            .processNameSuffix("k3x1n")
            .debuggable(true)
            .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "onServiceConnected: $service")
            if (service == null) {
                finishWaiters(false)
                return
            }
            kernel = KernelInterface.Stub.asInterface(service)
            finishWaiters(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected: $name")
            kernel = null
            binding = false
            sink = null
        }
    }

    fun isReady(): Boolean = try {
        Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun isRunning(): Boolean = try {
        kernel?.isRunning == true
    } catch (t: Throwable) {
        false
    }

    /** 阻塞版：供后台线程调用，等待绑定 + start 结果 */
    fun startBlocking(dev: String?, target: TouchSink, timeoutMs: Long = 10_000): Boolean {
        sink = target
        val latch = CountDownLatch(1)
        var ok = false
        start(dev) { result ->
            ok = result
            latch.countDown()
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ok) sink = null
        return ok
    }

    fun start(dev: String?, onResult: (Boolean) -> Unit) {
        ensureBound { bound ->
            if (!bound) {
                onResult(false)
                return@ensureBound
            }
            val ok = try {
                kernel?.start(dev, touchCallback) == true
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                false
            }
            onResult(ok)
        }
    }

    fun stop() {
        runCatching { kernel?.stop() }
            .onFailure { Log.e(TAG, "stop failed", it) }
        sink = null
    }

    private fun ensureBound(onResult: (Boolean) -> Unit) {
        val current = kernel
        if (current != null && runCatching { current.asBinder().isBinderAlive }.getOrDefault(false)) {
            onResult(true)
            return
        }
        synchronized(this) {
            waiters.add(onResult)
            if (binding) return
            binding = true
        }
        try {
            Shizuku.bindUserService(serviceArgs, connection)
        } catch (t: Throwable) {
            Log.e(TAG, "bindUserService failed", t)
            finishWaiters(false)
        }
    }

    private fun finishWaiters(ok: Boolean) {
        binding = false
        val pending = synchronized(this) {
            waiters.toList().also { waiters.clear() }
        }
        main.post { pending.forEach { it(ok) } }
    }
}
