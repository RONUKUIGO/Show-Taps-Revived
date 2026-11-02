package show.taps.touch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 触摸数据源调度：优先 Shizuku（无需 root），否则走 su/root 直启。
 */
object TouchSources {

    private const val TAG = "TouchSources"

    enum class Kind { SHIZUKU, ROOT, NONE }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var kind: Kind = Kind.NONE
        private set

    /** 当前可用的通道；root 检测会阻塞，留给后台线程调用 */
    fun detect(): Kind {
        if (ShizukuTouchSource.isReady()) return Kind.SHIZUKU
        if (RootTouchSource.isAvailable()) return Kind.ROOT
        return Kind.NONE
    }

    val isRunning: Boolean
        get() = when (kind) {
            Kind.SHIZUKU -> ShizukuTouchSource.isRunning()
            Kind.ROOT -> RootTouchSource.isRunning()
            Kind.NONE -> false
        }

    /** 后台线程做通道检测 + 启动，结果回主线程 */
    fun start(context: Context, dev: String?, sink: TouchSink, onResult: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        Thread({
            val result = try {
                when (detect()) {
                    Kind.SHIZUKU -> {
                        kind = Kind.SHIZUKU
                        ShizukuTouchSource.startBlocking(dev, sink)
                    }

                    Kind.ROOT -> {
                        kind = Kind.ROOT
                        RootTouchSource.startBlocking(appContext, dev, sink)
                    }

                    Kind.NONE -> {
                        kind = Kind.NONE
                        Log.e(TAG, "no available source (shizuku/root)")
                        false
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                false
            }
            main.post { onResult(result) }
        }, "touch-start").apply { isDaemon = true }.start()
    }

    fun stop() {
        Thread({
            runCatching { ShizukuTouchSource.stop() }
            runCatching { RootTouchSource.stop() }
        }, "touch-stop").apply { isDaemon = true }.start()
    }
}
