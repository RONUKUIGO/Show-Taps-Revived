# Show-Taps-Revived

在屏幕上显示触摸位置与轨迹（多指、可配色、可调大小/淡出时间）。

**本项目是 [k3x1n/Android-Show-Taps](https://github.com/k3x1n/Android-Show-Taps) 的复活分支**，
当前版本 **5.1-a16**，目标是让它在 **Android 16（SDK 36）** 上重新跑起来。

* 许可证：**GPL-3.0**（沿用上游）
* 上游长期未更新，Android 16 上已经完全无法启动覆盖层，因此另开仓库维护
* 上游的提交历史、tag（v2.1 / v3.2 / v5.0）都保留在本仓库里

---

## 为什么需要适配：Android 16 把「提权进程画窗口」这条路堵死了

AOSP 16 的 `Session` 构造函数新增了一道校验：

```java
// frameworks/base/services/core/java/com/android/server/wm/Session.java:147
mProcess = mAtmService.mProcessMap.getProcess(mPid);
if (mProcess == null) {
    throw new IllegalStateException("Unknown pid=" + mPid + " uid=" + mUid);
}
```

也就是说：**凡是不在 AMS ProcessMap 里的进程，`openSession()` 直接抛异常 → 一个窗口都建不出来**。
Shizuku 用户服务、`app_process`、root shell 全都属于这一类。

设备端实测（Android 16 / SDK 36，`app_process` 以 root 运行，逐个尝试 addView）：

| 窗口类型 | 2041 | 2039 | 2038 | 2002 | 2036 |
|---|---|---|---|---|---|
| 结果 | ❌ | ❌ | ❌ | ❌ | ❌ |

栈顶统一是 `IWindowManager$Stub$Proxy.openSession` → `IllegalStateException: Unknown pid=…`。
（证据与探针源码见 [`notes/a16-probe.md`](notes/a16-probe.md)、[`notes/WmProbe.java`](notes/WmProbe.java)）

## 改成了什么架构

```
┌──────────── App 进程（AMS 认识的真实进程）────────────┐
│ OverlayService(前台服务) → WindowManager.addView       │
│   TYPE_APPLICATION_OVERLAY + SYSTEM_ALERT_WINDOW       │
│ MainView 绘制轨迹线与圆环                               │
└────────▲──────────────────────────────┬───────────────┘
         │ ITouchCallback (oneway AIDL)  │ TCP 回环 + 一次性 token
┌────────┴──────────────┐   ┌───────────▼──────────────────┐
│ Shizuku 用户服务       │   │ root 守护进程                 │
│ KernelService(uid 2000)│   │ RootServer(app_process as root)│
└───────────────────────┘   └──────────────────────────────┘
        读 /dev/input/eventX        读 /dev/input/eventX
```

* **覆盖层回到 App 自身进程**（唯一可行的位置），提权进程只负责读触摸设备并回传坐标。
* 触摸数据源不变：JNI 把 `EV_ABS` 归一化成 `x/abs_max`、`y/abs_max` 后传上层。
* **双通道**：优先 Shizuku（无需 root），没有 Shizuku 时自动走 `su`/root 直启。
* 顺带去掉了「必须用改过的 android.jar」这个编译期依赖：
  `UnknownContentProvider`（实现隐藏接口 `IContentProvider`）改成
  `java.lang.reflect.Proxy` 动态代理（`server/SettingsHack.kt`）。
  作用不变：让 `Settings.Secure/System/Global` 的静态读取在提权进程里返回默认值
  （否则框架内部读到 `SecurityException: Unable to find app for caller …` 会直接崩）。

## 当前状态（初步恢复功能）

已验证（真机 Android 16 / SDK 36）：

* Shizuku 通道：用户服务以 shell 身份起、挑中 `/dev/input/event7 (goodix_ts)`、事件回传正常
* 覆盖层：`ty=APPLICATION_OVERLAY` 正常显示
* 绘制：注入触摸事件后截图里能定位到圆环与轨迹（像素级核对过位置与直径）
* 5 秒「继续」安全计时：不确认会自动停止
* release 构建（R8 混淆）同样正常

未验证 / 已知限制：

* **root 直启通道**：代码已实现，但测试机的 KernelSU 未授权本 App，`su` 返回 `Permission denied`，未端到端跑通
* 覆盖层**盖不住状态栏**（`TYPE_APPLICATION_OVERLAY` 层级在状态栏之下；A16 上原方案的 2041 已不可用）
* 通知（`POST_NOTIFICATIONS`）未主动申请，前台服务通知可能不显示，服务本身不受影响

## 构建

需要：**JDK 17+、Android SDK 36、NDK 28.2.13676358、CMake 3.22+**。

```bash
cd showtaps
./gradlew assembleDebug      # 或 assembleRelease
```

* 工具链：AGP **9.5.0**（内置 Kotlin，不要再加 `org.jetbrains.kotlin.android`）、Gradle **9.8.2**
* `local.properties` 里写 `sdk.dir=...`
* release 签名：工程里**不放** keystore，只读环境变量
  `B50_KEYSTORE / B50_STORE_PASS / B50_ALIAS / B50_KEY_PASS`；
  用维护者的 `wskeys` 包装器注入（见 `HANDOFF.md §6`）。没有这些变量时 release 走未签名，
  不影响他人克隆编译
* **aarch64 主机**（如手机上的 Linux 沙箱）：官方 NDK 只提供 x86_64 主机工具链，
  需自己交叉编译 `libtaps.so` 放进 `app/src/main/jniLibs/arm64-v8a/`，再用
  `gradle -PusePrebuiltNative assembleDebug` 跳过 CMake。详见 [`HANDOFF.md`](HANDOFF.md)。
  （`jniLibs/` 不入库，避免与 CMake 产物重名冲突）

## 用法

1. 安装后首次打开 → 授予「显示在其他应用上层」权限
2. 准备触摸数据源：装 [Shizuku](https://shizuku.rikka.app/) 并授权本 App；
   或在 root 环境给本 App 授权 root（走 `su` 直启通道）
3. 点「启动」，5 秒内点「继续」→ 之后触摸屏幕即可看到位置与轨迹

> Release 包的签名与上游官方包**不同**，首次从上游版本迁移时需要先卸载原版。

## TODO

* [ ] root 通道端到端验证（KernelSU / Magisk 授权后）
* [ ] 覆盖层能否向上越过状态栏（需要另想办法，或退化为「状态栏下方」）
* [ ] 快捷磁贴开关
* [ ] PNG 图标/自定义光标
* [ ] 通知里的「停止」按钮

## 上游

* 原项目：https://github.com/k3x1n/Android-Show-Taps （GPL-3.0）
* 本仓库：https://github.com/RONUKUIGO/Show-Taps-Revived

---

# Show-Taps-Revived（中文）

在屏幕上显示触摸位置与轨迹。**这是 [k3x1n/Android-Show-Taps](https://github.com/k3x1n/Android-Show-Taps)
的复活分支**，当前版本 **5.1-a16**，让它重新支持 **Android 16（SDK 36）**。

**为什么另开仓库**：上游自 5.0 之后长期未更新，而 Android 16 上原架构已经完全不可用，
因此作为独立分支继续维护；上游提交历史与 tag 都保留着。

**Android 16 的根因**：AOSP 16 的 `Session` 构造函数要求调用 `openSession()` 的 pid 必须存在于
AMS 的 ProcessMap，否则抛 `IllegalStateException("Unknown pid=…")`。
Shizuku 用户服务 / app_process / root shell 都不在其中 → **完全无法创建窗口**。
所以本版本把覆盖层搬回 **App 自身进程**（`TYPE_APPLICATION_OVERLAY` + 悬浮窗权限），
提权进程只负责读 `/dev/input` 并通过回调/回环把坐标送回来。

**已实现**：Shizuku 通道（真机验证）· root/su 直启通道（代码完成，未端到端验证）·
AGP 9.5 / Gradle 9.8.2 / compileSdk 36 / NDK 28.2（16KB page size）· edge-to-edge 适配 ·
去掉「改过的 android.jar」依赖（隐藏接口改用 `java.lang.reflect.Proxy`）。

**已知限制**：覆盖层盖不住状态栏（A16 上 2041 类型已不可用）；root 通道待实测。

构建：`cd showtaps && ./gradlew assembleDebug`（JDK 17+ / SDK 36 / NDK 28.2 / CMake 3.22+）。
细节看 [`HANDOFF.md`](HANDOFF.md)，A16 取证看 [`notes/a16-probe.md`](notes/a16-probe.md)。
