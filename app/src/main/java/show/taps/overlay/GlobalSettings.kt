package show.taps.overlay

object GlobalSettings {

    const val COLOR_ARRAY_LENGTH = 10

    var touchPointSize = 80 /* dp */

    var pathFadeTime = 200 /* ms */

    const val circleFadeTime = 150 /* ms */

    var strokeCircle = 5

    var strokeLine = 5

    /** 整体透明度 0..100 */
    var colorAlpha = 100

    val colorArray = IntArray(COLOR_ARRAY_LENGTH)
}
