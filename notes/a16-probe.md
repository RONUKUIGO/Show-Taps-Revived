# Android 16 覆盖层探测记录（2026-11-01，本机 23117RK66C / SDK 36）

## 1. AOSP 源码证据

`frameworks/base/services/core/java/com/android/server/wm/Session.java`（android16-release，第 137-150 行）：

```java
    Session(WindowManagerService service, IWindowSessionCallback callback,
            int callingPid, int callingUid) {
        ...
        synchronized (service.mGlobalLock) {
            mLastReportedAnimatorScale = service.getCurrentAnimatorScale();
            mProcess = service.mAtmService.mProcessMap.getProcess(mPid);
        }
        if (mProcess == null) {
            throw new IllegalStateException("Unknown pid=" + mPid + " uid=" + mUid);
        }
```

`WindowManagerPolicy`/`WMS` 的权限检查都在这一行之后，所以**任何不在 AMS ProcessMap 里的进程
（app_process / Shizuku 用户服务 / root shell）在 Android 16 上都无法 openSession → 无法 addView**。

对照：同一份 `WindowManagerService.java`（android16-release）里没有 "Unknown pid=" 字面量，
说明这是 `Session` 构造函数新增的门槛，不是 ROM 改动。本机 `services.jar` 里也能直接搜到这串字符
（`classes3.dex`），定位一致。

抓取方式（无需下载整棵树）：
```sh
curl -s "https://android.googlesource.com/platform/frameworks/base/+archive/refs/heads/android16-release/services/core/java/com/android/server/wm.tar.gz" | tar xz
grep -rn "Unknown pid=" .
```

## 2. 设备端探针（app_process，root）

源码见本目录 `WmProbe.java`。运行方式：
```sh
cd /data/local/tmp/ws/tmp/wmprobe && CLASSPATH=classes.dex /system/bin/app_process /system/bin WmProbe
```

实测输出（节选）：
```
uid=0 sdk=36
ctx=android.app.ContextImpl@84ceea9 pkg=android
HOLDERFIELD private android.content.IContentProvider android.provider.Settings$ContentProviderHolder.mContentProvider
HOLDERFIELD private final java.lang.Object android.provider.Settings$ContentProviderHolder.mLock
HOLDERFIELD private final android.net.Uri android.provider.Settings$ContentProviderHolder.mUri
SETTINGS ERR java.lang.SecurityException: Unable to find app for caller
             android.app.IApplicationThread$Stub$Proxy@9b8cd74 (pid=36016) when getting content provider settings
ADD type=2041 FAIL java.lang.IllegalStateException: Unknown pid=37481 uid=0
ADD type=2039 FAIL java.lang.IllegalStateException: Unknown pid=37481 uid=0
ADD type=2038 FAIL java.lang.IllegalStateException: Unknown pid=37481 uid=0
ADD type=2002 FAIL java.lang.IllegalStateException: Unknown pid=37481 uid=0
ADD type=2036 FAIL java.lang.IllegalStateException: Unknown pid=37481 uid=0
   （栈顶统一为 IWindowManager$Stub$Proxy.openSession → WindowManagerGlobal.getWindowSession
     → ViewRootImpl.<init> → WindowManagerGlobal.addView）
PROXY installed on Secure / System / Global
AFTER-HACK Settings.Global animator_scale=1.0          ← Proxy 版 Settings 中和有效
AFTER-HACK Settings.Secure android_id=
INPUT_OPEN_OK event0..event12                          ← root 可读全部 input 节点
```

## 3. 由此得到的两个结论

1. **覆盖层必须由 App 进程绘制**（`TYPE_APPLICATION_OVERLAY` + SYSTEM_ALERT_WINDOW）。
2. **Settings 中和仍是必需的**，但可以用 `java.lang.reflect.Proxy` 实现，
   从而摆脱「改过的 android.jar」这个编译期依赖。
   同时确认 `Settings$ContentProviderHolder.mContentProvider` 字段在 Android 16 上依然存在。

## 4. 设备上输入设备的实际情况

```
/dev/input/event7  goodix_ts
  ABS_MT_TRACKING_ID  min 0  max 65535
  ABS_MT_POSITION_X   min 0  max 143999
  ABS_MT_POSITION_Y   min 0  max 319999
  ABS_MT_SLOT         min 0  max 9
  ABS_MT_TOUCH_MAJOR  min 0  max 255
```
（X/Y 是屏像素的 100 倍；`MainView` 用 x/max、y/max 归一化后再乘 measuredWidth/Height）
