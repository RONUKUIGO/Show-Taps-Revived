package show.taps.overlay

object GlobalSettings {

    const val COLOR_ARRAY_LENGTH = 10

    /** 启动后等待用户点「继续」的时间，超时自动停止，避免极端情况下屏幕上出现无法操作的遮挡 */
    const val WAIT_CONTINUE_TIME = 5000L

    var touchPointSize = 80 /* dp */

    var pathFadeTime = 200 /* ms */

    const val circleFadeTime = 150 /* ms */

    var strokeCircle = 5

    var strokeLine = 5

    /** 整体透明度 0..100 */
    var colorAlpha = 100

    val colorArray = IntArray(COLOR_ARRAY_LENGTH)
}
