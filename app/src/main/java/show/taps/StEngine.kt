package show.taps

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import show.taps.effect.StPackHolder
import show.taps.effect.StPackParser
import show.taps.effect.StPackRenderer
import show.taps.overlay.GlobalSettings
import show.taps.overlay.OverlayService
import show.taps.settings.StConfig
import show.taps.settings.StConfigStore
import show.taps.settings.StPresetStore
import java.io.File

/**
 * ST 引擎的统一入口：把配置灌进 GlobalSettings，然后起/停覆盖层。
 * 覆盖层本身跑在宿主 App 进程里（原因见 notes/a16-probe.md：A16 起提权进程不能再建窗口）。
 */
object StEngine {

    private const val TAG = "StEngine"

    /** 把一套配置应用到绘制参数上 */
    fun apply(cfg: StConfig) {
        Log.d(TAG, "apply id=${cfg.id} name=${cfg.name} size=${cfg.pointSize} fade=${cfg.fadeTime} alpha=${cfg.alpha}")
        GlobalSettings.touchPointSize = cfg.pointSize
        GlobalSettings.pathFadeTime = cfg.fadeTime
        GlobalSettings.strokeCircle = cfg.circleStroke
        GlobalSettings.strokeLine = cfg.lineStroke
        GlobalSettings.colorAlpha = cfg.alpha
        val colors = GlobalSettings.colorArray
        cfg.colors.forEachIndexed { index, value ->
            if (index < colors.size) colors[index] = value
        }
        // 覆盖层已在显示时，透明度要立刻生效
        show.taps.overlay.OverlayController.current?.alpha = cfg.alpha / 100f

        // 效果包：有 packId 就装载渲染器；没包 / 解析失败 → 退回内置绘制规则
        StPackHolder.current = cfg.packId?.let { id -> loadPackRenderer(id) }
    }

    /** 用「当前生效」的配置启动（不依赖 Compose 里的快照，避免选中态过期） */
    fun startActive(context: Context, devName: String? = null): Boolean {
        StConfigStore.init(context)
        val cfg = StConfigStore.snapshot.active
        if (cfg == null) {
            Log.e(TAG, "startActive: no config")
            return false
        }
        start(context, cfg, devName)
        return true
    }

    fun start(context: Context, cfg: StConfig, devName: String? = null) {
        StRuntime.init(context.packageName)
        apply(cfg)
        OverlayService.start(context, devName)
    }

    fun stop(context: Context) {
        OverlayService.stop(context)
    }

    /** 按预设 id 装载效果包渲染器（失败返回 null，调用方退回内置绘制） */
    private fun loadPackRenderer(packId: String): StPackRenderer? {
        val preset = StPresetStore.byId(packId)
        if (preset == null) {
            Log.w(TAG, "pack not found: $packId")
            return null
        }
        val text = preset.packJson
        if (text.isNullOrBlank()) {
            Log.w(TAG, "pack has no json: $packId")
            return null
        }
        return runCatching {
            val pack = StPackParser.parse(text)
            val dir = preset.assetDir?.let { File(it) }
            Log.d(TAG, "pack loaded: ${pack.name} (press=${pack.press.size} hold=${pack.hold.size} trail=${pack.trail.size} release=${pack.release.size})")
            StPackRenderer(pack, dir)
        }.onFailure { Log.e(TAG, "pack parse failed: ${it.message}") }.getOrNull()
    }

    /** 是否正在显示 */
    val running get() = StRuntime.running

    /** 配置改动后，如果引擎在跑就热更新一次 */
    fun onConfigChanged(cfg: StConfig) {
        if (StRuntime.running.value) apply(cfg)
    }
}

/** 在 Compose 里确保 store 已初始化，并拿到当前配置集合 */
@Composable
fun rememberStConfigSet(): show.taps.settings.StConfigSet {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        // init 要读文件 + 反序列化 JSON（kotlinx.serialization 首次使用还会加载大量类），
        // 在主线程做会直接表现为「第一次进配置页卡 100~200ms」。挪到 IO 线程。
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            StConfigStore.init(context)
            StRuntime.init(context.packageName)
        }
    }
    return StConfigStore.state.collectAsStateWithLifecycle().value
}
