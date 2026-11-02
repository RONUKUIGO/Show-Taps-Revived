# ===================== ST（Show Taps 引擎）=====================
# Shizuku 用户服务 / root 守护进程是按「类名字符串」由外部进程加载的，
# 混淆或裁剪会直接导致 shizuku:app_process 找不到类 → 通道失效。
-keep class show.taps.server.** { *; }
-keep class show.taps.touch.** { *; }
-keep class show.taps.effect.** { *; }
-keep class show.taps.overlay.TapOverlayAccessibilityService { *; }
-keep interface show.taps.ITouchCallback { *; }
-keep interface show.taps.KernelInterface { *; }
-keep interface show.taps.DevInfo { *; }

# ST 的配置/预设是 kotlinx.serialization 的 @Serializable 数据类，
# 字段名参与 JSON 编解码，不能被裁剪。
-keepclassmembers class show.taps.settings.** { *; }
-keep class show.taps.settings.** { *; }

# ===================== miuix =====================
# miuix 的动态取色路径引用了 materialkolor 的可选类（运行时才在），
# release 的 classpath 上没有它们，R8 会报 Missing class。
-dontwarn com.materialkolor.**
