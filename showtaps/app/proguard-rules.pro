# Add project specific ProGuard rules here.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-repackageclasses ''

# 避免 KernelService 构造方法未直接使用的代码被移除
-dontshrink

# ---- Android 16 适配新增：名字被字符串/反射/原生引用的入口必须保留 ----

# su 通道通过字符串 "show.taps.server.RootServer" 启动，类名不能混淆
-keep class show.taps.server.RootServer { *; }

# Shizuku 通过 ComponentName 反射实例化用户服务
-keep class show.taps.server.KernelService { *; }

# AIDL Stub/Proxy 与 Parcelable
-keep class show.taps.KernelInterface { *; }
-keep class show.taps.KernelInterface$Stub { *; }
-keep class show.taps.ITouchCallback { *; }
-keep class show.taps.ITouchCallback$Stub { *; }
-keep class show.taps.DevInfo { *; }

# JNI 通过名字回调 a/b/c，并通过名字查找类
-keep class show.taps.server.NativeLib { *; }

# 反射读取 Settings$ContentProviderHolder 里的字段（系统类，仅保留我们自己的调用不被裁掉）
-keep class show.taps.server.SettingsHack { *; }

-keep class androidx.transition.** {*;}
-keep class kotlinx.coroutines.android.** {*;}

# R8 警告：errorprone 注解引用了 JDK 编译期类型（AGP 9 下才会暴露）
-dontwarn javax.lang.model.element.Modifier
