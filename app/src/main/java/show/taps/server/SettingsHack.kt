package show.taps.server

import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

private const val TAG = "SettingsHack"

/**
 * 让 Settings.Secure / System / Global 的静态读取直接返回默认值。
 *
 * 为什么要这么做：提权进程（Shizuku 用户服务 / app_process）没有 ActivityThread 绑定，
 * ContentResolver 找不到 providers，Android 16 上实测抛：
 *   SecurityException: Unable to find app for caller ... when getting content provider settings
 * 框架内部（如 ViewRootImpl）会读 Settings，一旦抛异常整个链路就崩。
 *
 * 历史上的实现是「实现隐藏接口 android.content.IContentProvider」，
 * 那要求使用改过的 android.jar（Reginer/aosp-android-jar）才能编译。
 * Android 16 适配改为 java.lang.reflect.Proxy 动态代理：
 *   1. 不再依赖隐藏 API 的编译期符号 → 可以直接用官方 SDK 编译；
 *   2. 接口将来新增方法也不会导致编译失败（全部落到默认返回值）。
 * 已在本机（Android 16 / SDK 36）实测：代理装上后 getFloat 正常返回默认值。
 */
object SettingsHack {

    private val defaultHandler = InvocationHandler { proxy, method, args ->
        if (method.declaringClass == Any::class.java) {
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                else -> "UnknownContentProvider"
            }
        } else {
            when (method.name) {
                // 全部当作「允许」，避免框架做权限查询时被判失败
                "checkUriPermission" -> PackageManager.PERMISSION_GRANTED
                "refresh" -> true
                "canonicalize", "uncanonicalize" -> args?.getOrNull(1)
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> 0.toChar()
        else -> null
    }

    /** @return 是否至少装成功一个（部分 ROM 可能改了字段名） */
    fun install(): Boolean {
        var installed = false
        try {
            val holderClass = Class.forName("android.provider.Settings\$ContentProviderHolder")
            val providerField = holderClass.getDeclaredField("mContentProvider").apply { isAccessible = true }
            val iface = Class.forName("android.content.IContentProvider")
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), defaultHandler)

            for (clazz in listOf(Settings.Secure::class.java, Settings.System::class.java, Settings.Global::class.java)) {
                try {
                    val field = clazz.getDeclaredField("sProviderHolder").apply { isAccessible = true }
                    val holder = field.get(null) ?: continue
                    providerField.set(holder, proxy)
                    installed = true
                    Log.d(TAG, "install: hook ${clazz.simpleName} ok")
                } catch (t: Throwable) {
                    Log.e(TAG, "install: ${clazz.simpleName} failed: $t")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "install: unsupported rom? $t")
        }
        return installed
    }
}
