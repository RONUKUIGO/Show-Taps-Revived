package show.taps.settings

import android.content.Context

/**
 * ST 引擎在宿主里的少量全局开关（用 SharedPreferences，数据量极小）。
 * 以后如果要加"引擎级"选项（例如覆盖状态栏、默认通道），都放这里，不要塞进单套配置。
 */
object StSettings {

    private const val PREF = "st_settings"
    private const val KEY_A11Y_OVERLAY = "use_accessibility_overlay"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 是否用无障碍覆盖层窗口（TYPE_ACCESSIBILITY_OVERLAY，层级 31）画到状态栏之上 */
    fun useAccessibilityOverlay(context: Context): Boolean =
        prefs(context).getBoolean(KEY_A11Y_OVERLAY, false)

    fun setUseAccessibilityOverlay(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_A11Y_OVERLAY, value).apply()
    }
}
