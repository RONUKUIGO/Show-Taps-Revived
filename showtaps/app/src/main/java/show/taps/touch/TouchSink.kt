package show.taps.touch

/** 触摸数据的消费端（App 进程内，通常是绘制用的 MainView） */
interface TouchSink {
    /** @param x,y 归一化坐标 0..1（设备原始触摸轴 / abs max） */
    fun onSlotState(slot: Int, down: Boolean)
    fun onSlotMove(slot: Int, x: Float, y: Float)
    /** 一个 SYN_REPORT 结束，可以刷新画面 */
    fun onSync()
}
