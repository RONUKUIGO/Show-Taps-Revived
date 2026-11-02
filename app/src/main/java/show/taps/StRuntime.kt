package show.taps

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * ST 引擎在宿主 App（当前是 IRX 派生工程）里的运行期状态。
 * 原来在自己的 Application 里，搬进来后改用这个 holder，避免依赖 App 类。
 */
object StRuntime {

    /** 宿主包名（Shizuku 用户服务要用它来构造 ComponentName） */
    @Volatile
    var packageName: String = ""

    /** 覆盖层是否在显示 */
    val running = MutableStateFlow(false)

    fun init(pkg: String) {
        if (packageName.isEmpty()) packageName = pkg
    }
}
