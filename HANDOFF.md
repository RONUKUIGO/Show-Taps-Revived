# HANDOFF · 接手必读（2026-11-01）

> 状态：**初步恢复功能**。Android 16 上能跑起来、能画、能停；root 通道未端到端验证。
> 版本：`5.1-a16`（versionCode 9），基线是上游 `fbc9097`（tag v5.0 之后唯一提交）。

---

## 1. 一页速览

| 项 | 内容 |
|---|---|
| 上游 | `k3x1n/Android-Show-Taps`（GPL-3.0） |
| 本仓库 | `RONUKUIGO/Show-Taps-Revived` |
| 为什么要分叉 | 上游长期停更；Android 16 上原架构（提权进程画窗口）**已彻底不可用** |
| 关键结论 | AOSP 16 的 `Session` 构造函数要求 `openSession()` 的调用者 pid 在 AMS ProcessMap 里，否则抛 `IllegalStateException("Unknown pid=…")` |
| 解决方式 | 覆盖层搬回 **App 自身进程**（`TYPE_APPLICATION_OVERLAY` + 悬浮窗权限）；提权进程只读 `/dev/input` 并回传 |
| 触摸数据源 | Shizuku 用户服务（uid 2000，优先）；`su` + `app_process`（root 兜底） |
| 验证过的环境 | 小米平板 23117RK66C / Android 16 / SDK 36 / 1440×3200(覆盖 1080×2400) / 本机 Shizuku API 由 **Sui**（zygisk 模块）提供 |

## 2. 目录

```
.
├── README.md                 面向用户：是什么、怎么装、怎么构建
├── HANDOFF.md                本文件
├── notes/
│   ├── a16-probe.md          A16 取证：AOSP 源码 + 设备端探针原始输出
│   └── WmProbe.java          app_process 探针源码（可复现「Unknown pid」）
├── LICENSE                   GPL-3.0（沿用上游，勿改）
└── showtaps/                 Android 工程
    └── app/src/main/
        ├── aidl/show/taps/   KernelInterface.aidl / ITouchCallback.aidl / DevInfo.aidl
        ├── cpp/              taps.cpp（读 /dev/input、ioctl 取 absinfo）、detect_devices.h
        ├── java/show/taps/
        │   ├── MainActivity.kt        权限引导 + 启动/停止 + 设置面板
        │   ├── Pref.kt / Goto.kt / DevInfo.kt / OnSeekBarChangeListener.kt
        │   ├── App.kt                 App.running StateFlow
        │   ├── overlay/               ← 覆盖层（App 进程）
        │   │   ├── OverlayService.kt  前台服务(FGS specialUse) + 5s「继续」安全计时
        │   │   ├── OverlayController.kt addView(TYPE_APPLICATION_OVERLAY)
        │   │   ├── MainView.kt        绘制（rotation 换算、轨迹/圆环）
        │   │   └── GlobalSettings.kt  绘制参数（进程内，无需跨进程同步）
        │   ├── touch/                 ← 触摸数据通道
        │   │   ├── TouchSink.kt       onSlotState/onSlotMove/onSync
        │   │   ├── TouchSources.kt    通道调度：Shizuku → root 兜底
        │   │   ├── ShizukuTouchSource.kt bindUserService + ITouchCallback
        │   │   └── RootTouchSource.kt su -c app_process + 127.0.0.1 回环
        │   └── server/                ← 提权侧（不建窗口！）
        │       ├── KernelService.kt   Shizuku 用户服务
        │       ├── RootServer.kt      root 守护进程（app_process 入口）
        │       ├── NativeLib.kt       JNI 桥（a/b/c 回调 → Sink）
        │       ├── InputDevices.kt    挑选触摸设备
        │       └── SettingsHack.kt    Proxy 版 Settings 中和
        └── res/                      布局/字符串/图标（含通知小图标）
```

## 3. 数据流（为什么这么拆）

```
/dev/input/event7 ──(JNI, 提权进程)──> NativeLib.a/b/c
                                        │
                         ┌──────────────┴───────────────┐
                         │ Shizuku: ITouchCallback(binder)│ root: TCP 行协议 S/M/Y
                         └──────────────┬───────────────┘
                                        ▼
                            MainView.onSlotState/onSlotMove/onSync
                                        ▼
                              postInvalidate → onDraw
```

* 归一化在 JNI 里做：`x = event->value / abs_x.maximum`，`y` 同理 → 上层只认 0..1。
* 一个 `SYN_REPORT` 触发一次 `onSync()` → 一次 `postInvalidate()`。
* 绘制参数（颜色/大小/淡出/透明度）都在 App 进程的 `GlobalSettings`，**不再跨进程同步**。

## 4. 为什么去掉「改版 android.jar」

上游 `UnknownContentProvider` 直接 `implements android.content.IContentProvider`（@hide），
必须用 [Reginer/aosp-android-jar](https://github.com/Reginer/aosp-android-jar) 替换 SDK 里的
`android.jar` 才能编译。现在改成 `java.lang.reflect.Proxy` 动态代理（`server/SettingsHack.kt`）：

* 编译期零隐藏符号 → 官方 SDK 直接能编；
* 隐藏接口将来加方法也不会编译失败（新方法落到默认返回值）。

它解决的问题没变：提权进程（无 ActivityThread 绑定）里读 Settings 会抛
`SecurityException: Unable to find app for caller … when getting content provider settings`，
而框架内部（如 `ViewRootImpl`）会读 Settings，一抛就崩。装上代理后实测返回默认值。
实测确认 `Settings$ContentProviderHolder.mContentProvider` 字段在 Android 16 上仍然存在。

## 5. 构建与发布

### 正常 x86_64 开发机
```bash
cd showtaps
./gradlew assembleDebug     # CMake + NDK 现场编译 libtaps.so
./gradlew assembleRelease   # 签名走环境变量，见 §6（无变量则未签名）
```

### aarch64 主机（本项目的原始开发环境：手机上的 Ubuntu 沙箱）
官方 NDK 只给 x86_64 主机工具链，所以：

```bash
NDK=/opt/android-sdk/ndk/28.2.13676358
SYSROOT=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot
A=$SYSROOT/usr/lib/aarch64-linux-android/29
RT=$(ls -d $NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/*/lib/linux)

cd showtaps/app/src/main/cpp
clang++-21 --target=aarch64-linux-android29 --sysroot=$SYSROOT \
  -std=c++17 -fPIC -shared -O2 -fvisibility=hidden -fvisibility-inlines-hidden \
  -Wl,-Bsymbolic -Wl,--exclude-libs,ALL -s -fuse-ld=lld-21 -nodefaultlibs \
  -Wl,--version-script=external.map.txt \
  -o ../jniLibs/arm64-v8a/libtaps.so taps.cpp \
  $RT/libclang_rt.builtins-aarch64-android.a -L$A -lc -llog -ldl -lm

cd ../../..            # 回到 showtaps/
gradle -PusePrebuiltNative assembleDebug
```

* 产出的 `.so` 必须 `LOAD` 段 `p_align = 0x4000`（16KB page，Android 15+ 要求）。
  校验：`readelf -lW libtaps.so | grep LOAD`。
* `app/src/main/jniLibs/` **不入库**（否则和 CMake 产物同名冲突，正常构建会报
  "2 files found with path 'lib/arm64-v8a/libtaps.so'"）。
* `local.properties` 里 aarch64 主机还要加 `cmake.dir=/usr`（用系统 cmake/ninja，
  因为 SDK 里的 cmake/ninja 也是 x86-64），并且**不要**在 `externalNativeBuild.cmake`
  里写死 cmake 版本号。
* build-tools 的 `aidl` 也是 x86-64：aarch64 主机上需要用
  `qemu-user-static` + `libc6-amd64-cross` + `libstdc++6-amd64-cross` 包一个 wrapper。
  （`aapt2` 不用管，AGP 9.5 会从 Maven 取 `linux_arm64` 版。）

### 发布
1. 改 `app/build.gradle` 的 `versionCode` / `versionName`
2. `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
3. 用**同一把 keystore** 签名（见 §6），否则老用户无法覆盖安装
4. `gh release create vX.Y-a16 --title ... --notes ... app-release.apk`

## 6. 签名（走项目统一钥匙）

**不要在本工程里放任何 keystore。** 签名走项目规范 `docs/KEYSTORE.md`：
一把钥匙 `KeySigned_By_Rinkyu` 供所有需要签名的项目使用，唯一存放点
`/data/local/tmp/ws/secure/`（root 0700），密码只有加密文件 `.pw.enc`。

`app/build.gradle` 只读 4 个环境变量，由 `wskeys` 注入：

| 环境变量 | 含义 |
|---|---|
| `B50_KEYSTORE` | `.jks` 绝对路径 |
| `B50_STORE_PASS` | 密钥库口令 |
| `B50_ALIAS` | 别名（`KeySigned_By_Rinkyu`） |
| `B50_KEY_PASS` | 私钥口令 |

```bash
cd showtaps
printf '<wskeys 口令>\n' | sh /data/local/tmp/ws/wsk run -- \
    gradle -PusePrebuiltNative assembleRelease
```
（x86_64 开发机上去掉 `-PusePrebuiltNative`。）

签名后必须核验证书指纹：

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
# 期望：CN=Rinkyu, OU=RINIO, O=ROKUNUIGO
# SHA-256 42:60:4E:D2:85:E0:6D:7A:B4:75:F3:DE:3B:82:7E:DF:03:66:73:71:BC:73:E2:D2:CD:D9:AC:2C:A9:D2:65:F9
```

没有环境变量时 release 构建**走未签名**（clone 下来照样能编，方便 PR/CI），
需要可安装的包时再用 `wskeys run` 或者事后 `wskeys sign-apk <in> <out>` 重签。

> 已发布的 `v5.1-a16` 用的就是这把钥匙。以后发版必须继续用它，否则老用户只能卸载重装。

## 7. 验证清单（改动后请重跑）

1. `adb install -r` 装 debug 包 → 打开 App
2. 授予「显示在其他应用上层」；授权 Shizuku（或 root）
3. 点「启动」→ 5 秒内点「继续」→ 手指点屏幕，应看到彩色圆环 + 轨迹
4. 旋转屏幕，圆环位置仍应贴合手指（`MainView.calculate()` 按 rotation 换算）
5. 点「停止」→ 覆盖层消失（`dumpsys window windows | grep APPLICATION_OVERLAY` 应为 0）
6. 用 `logcat -s k3x1n:KernelService:InputDevices:OverlayService:OverlayController` 看链路

没有手指可用的自动化场景，可用 `sendevent` 直接注入到触摸设备节点（会同时被系统当作真实触摸，
挑不敏感坐标）：

```sh
dev=/dev/input/event7
sendevent $dev 3 47 0; sendevent $dev 3 57 8001; sendevent $dev 1 330 1
sendevent $dev 3 53 40000; sendevent $dev 3 54 170000; sendevent $dev 0 0 0
# 截图核对 … 然后释放：
sendevent $dev 3 57 -1; sendevent $dev 1 330 0; sendevent $dev 0 0 0
```
（`ABS_MT_POSITION_X/Y` 的 max 用 `getevent -lp /dev/input/eventX` 查，本机是 143999 / 319999。）

## 8. 实测记录（真机 Android 16 / SDK 36）

| 项 | 结果 |
|---|---|
| Shizuku 通道 | ✅ 用户服务以 shell 身份起（本机 API 由 Sui 提供，授权选「始终(Shell)」） |
| 设备挑选 | ✅ `pick: /dev/input/event7 (goodix_ts) weight=91`；abs: X max=143999 / Y max=319999 / slots=9 |
| 覆盖层 | ✅ `ty=APPLICATION_OVERLAY`、`appop=SYSTEM_ALERT_WINDOW` |
| 绘制（debug） | ✅ 注入 raw(30000–70000, 170000) → 高饱和像素 bbox (151,1163)-(636,1499)；对照图同区 0 |
| 绘制（release） | ✅ 圆心区 (429,969)-(650,1190) 紫色 8484 px（期望直径 ≈80dp×2.625+描边 ≈223）；y<900 零杂色 |
| 5 秒安全计时 | ✅ 不点「继续」5s 自动停；点了之后 9s 仍在 |
| 停止流程 | ✅ 服务归零、覆盖层窗口消失 |
| **root 通道** | ⚠ 未验证：测试机 KernelSU 未授权本 App，`su` 返回 `error=13 Permission denied` |

## 9. 已知问题 / 下一步（按优先级）

1. **root 通道端到端验证**（唯一"写了但没跑过"的主干功能）。测法：KernelSU/Magisk 里给
   Show Taps 开 root → 卸载/停用 Shizuku → 点启动，`logcat -s RootSource:RootServer` 应看到
   `handshake ok` 与 `reading /dev/input/eventX`。
2. **覆盖层盖不住状态栏**：`TYPE_APPLICATION_OVERLAY` 层级在状态栏之下；上游用的
   2041 在 A16 上已不可用。若要盖状态栏，只能换思路（例如全屏画中画、
   或让系统级进程画），目前接受这个限制。
3. `taps.cpp` 与 `detect_devices.h` 的 `LOGD` 宏重复定义（编译 warning，无功能影响）。
4. 通知未申请 `POST_NOTIFICATIONS`，前台服务通知可能不可见。
5. 上游 README 里「替换 android.jar」的说法已经过时（本仓库 README 已改写）。
6. `TYPE_APPLICATION_OVERLAY` 窗口在部分 ROM 上可能被"隐藏悬浮窗"开关拦截，
   需要引导用户去系统设置里放开。
7. 快捷磁贴 / 通知内"停止"按钮 / PNG 光标 —— 上游 TODO，尚未做。
