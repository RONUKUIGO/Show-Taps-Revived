package show.taps.effect

/**
 * 当前生效的效果包渲染器（进程内单例）。
 *
 * 为什么不塞进 MainView 字段：覆盖层视图是「停止即销毁、下次重建」，
 * 而渲染器要跨这两次生命周期存活（否则每次开/关都要重新解析 JSON 和解码贴图）。
 * MainView.onDraw 每帧读一次这里；为 null 就退回内置绘制。
 */
object StPackHolder {

    @Volatile
    var current: StPackRenderer? = null

    fun clear() {
        current = null
    }
}
