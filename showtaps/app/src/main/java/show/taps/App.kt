package show.taps

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import show.taps.overlay.GlobalSettings

class App : Application() {

    companion object {

        private const val TAG = "App"

        @SuppressLint("StaticFieldLeak")
        lateinit var instance: App
            private set

        /** 长度必须是 [GlobalSettings.COLOR_ARRAY_LENGTH] */
        lateinit var colorList: IntArray
            private set

        /** 覆盖层是否正在显示（UI 与前台服务都监听它） */
        val running = MutableStateFlow(false)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
        colorList = getColorList()
    }
}
