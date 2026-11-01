package show.taps.server

import androidx.annotation.Keep
import show.taps.DevInfo

/**
 * JNI 入口。原生线程只会回调 a()/b()/c() 三个静态方法，
 * 上层通过 [sink] 注入具体实现（AIDL 回调 / socket 写出）。
 *
 * 注意：加载库不再放在 init 里 —— root 通道在 app_process 中运行，
 * 需要由调用方显式指定 .so 的绝对路径（见 [loadFrom]）。
 */
@Keep
object NativeLib {

    interface Sink {
        fun onSlotState(slot: Int, down: Boolean)
        fun onSlotMove(slot: Int, x: Float, y: Float)
        fun onSync()
    }

    @Volatile
    var sink: Sink? = null

    private var loaded = false

    @JvmStatic
    @Synchronized
    fun load() {
        if (!loaded) {
            System.loadLibrary("taps")
            loaded = true
        }
    }

    @JvmStatic
    @Synchronized
    fun loadFrom(path: String) {
        if (!loaded) {
            System.load(path)
            loaded = true
        }
    }

    @JvmStatic
    @Synchronized
    external fun getInputPath(): ArrayList<DevInfo>

    @JvmStatic
    @Synchronized
    external fun start(dev: String?): Boolean

    @JvmStatic
    @Synchronized
    external fun stop()

    @Keep
    @JvmStatic
    fun a(slot: Int, down: Boolean) {
        sink?.onSlotState(slot, down)
    }

    @Keep
    @JvmStatic
    fun b(slot: Int, x: Float, y: Float) {
        sink?.onSlotMove(slot, x, y)
    }

    @Keep
    @JvmStatic
    fun c() {
        sink?.onSync()
    }
}
