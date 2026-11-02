# HANDOFF · Show Taps Revived 1.1.1

> 交接时间：2026-11-02 23:55　状态：**可装、可跑、已提交**，唯一未做的是「用项目钥匙重签」
> 代码正本：`/data/local/tmp/ws/projects/irx-ui/`（沙箱 git，最新提交 `9ba77ed`）
> 上一版交接（2026-11-02 00:45，还叫 irx-ui 样本库）已被本文取代

---

## 0. 一页速览

| 项 | 值 |
|---|---|
| 应用名 | **Show Taps** |
| 包名 | **`com.show.taps.revived`** |
| 版本 | **1.1.1 / versionCode 10101**（规则：`major*10000 + minor*100 + patch`） |
| 入口 Activity | `com.rosan.installer.ui.activity.SettingsActivity`（清单里的 LAUNCHER） |
| 构建产物 | `app/build/outputs/apk/Stable/release/app-Stable-release.apk`（5.9 MB） |
| 归档副本 | `Tools/LSPilot/outputs/apk/show-taps-revived/show-taps-revived-v1.1.1.apk` |
| 签名 | **debug 签名**（未用 `KeySigned_By_Rinkyu`，见 §7） |
| 设备现状 | 已装 1.1.1，可正常启动，无崩溃 |
| 图形栈 | 100% miuix（旧 Material3 已写死弃用） |

---

## 1. 这是什么

三块拼起来的，不是从零写的：

| 部分 | 来源 | 位置 |
|---|---|---|
| 界面骨架 | [InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)（GPL-3.0） | `app/src/main/java/com/rosan/installer/**` |
| 触摸引擎 | Show Taps（k3x1n）→ [Show-Taps-Revived](https://github.com/RONUKUIGO/Show-Taps-Revived)（GPL-3.0） | `app/src/main/java/show/taps/{overlay,touch,server}` + `aidl/show/taps/*` + `jniLibs/arm64-v8a/libtaps.so` |
| 自有 | 效果包引擎、预设库、配置/编辑页、miuix 适配 | `show/taps/{effect,settings,ui}` |

**为什么拼**：Android 16 起提权进程（Shizuku 用户服务 / root 的 `app_process`）调用
`IWindowManager.openSession` 会抛 `IllegalStateException("Unknown pid=…")`（AMS ProcessMap 里没有它），
所以"提权进程画窗口"的老架构彻底不可用，覆盖层必须回到 App 自己进程；
既然要重写这一层，就顺手把 UI 换成 miuix，界面骨架直接借 InstallerX Revived。

---

## 2. 目录地图

### 2.1 自有代码（改这里不用管 IRX 逻辑）

```
show/taps/
├── StEngine.kt                 引擎门面：apply(config) → GlobalSettings + 效果包渲染器
├── StRuntime.kt                宿主里的运行期状态（running StateFlow / packageName）
├── effect/                     ★ 效果包引擎
│   ├── StPack.kt               数据模型 + 曲线 + 7 种缓动
│   ├── StPackParser.kt         pack.json → StPack（带定位的报错）
│   ├── StPackRenderer.kt       时间→曲线→Canvas 绘制
│   └── StPackHolder.kt         当前生效渲染器（进程内单例，MainView 每帧读）
├── settings/
│   ├── StConfig.kt             一套绘制配置（含 packId）
│   ├── StConfigStore.kt        配置持久化（filesDir/st_configs.json）
│   ├── StPreset.kt             预设/效果包条目
│   ├── StPresetStore.kt        预设库：导入 json/zip、解压素材、持久化
│   └── StSettings.kt           引擎级开关（目前只有「覆盖状态栏」）
├── overlay/                    覆盖层（App 进程）
│   ├── OverlayService.kt       前台服务 + 通知（带「停止」按钮）
│   ├── OverlayController.kt    双宿主：APP(2038) / ACCESSIBILITY(2032) + 400ms 防抖重挂
│   ├── TapOverlayAccessibilityService.kt  只为拿 2032 的 WindowToken，不读屏不接管触摸
│   ├── MainView.kt             内置绘制 + 有包时让位给 StPackRenderer
│   └── GlobalSettings.kt       内置绘制参数
├── touch/                      TouchSink + Shizuku/root 双通道
└── ui/                         miuix 页面：配置列表 / 配置编辑 / 预设
```

### 2.2 借来的 IRX 代码里，我们动过的文件（共 7 个，交接时重点看）

| 文件 | 改了什么 |
|---|---|
| `data/settings/provider/ThemeStateProviderImpl.kt` | `useMiuix = true` 写死（旧 Material3 弃用） |
| `ui/navigation/MiuixNavWrapper.kt` | 第 3 格「记录」→「预设」；主 tab 图标换 `ic_st_home`；删 configCount 链路 |
| `ui/navigation/InstallerNavContainer.kt` | 配置编辑路由换成 ST 自己的页面 |
| `ui/page/miuix/settings/MiuixSettingsPage.kt` | page2 内容换成预设页；FAB 按页分派（配置页新建 / 预设页导入） |
| `ui/page/miuix/settings/preferred/MiuixPreferredPage.kt` | 删一堆设置项；加「覆盖状态栏」开关；备份置灰 |
| `ui/page/miuix/settings/home/priv/MiuixPrivPage.kt` | 只留 无/ROOT/Shizuku；删两块说明卡 |
| `ui/page/miuix/settings/preferred/about/MiuixAboutPage.kt` | 图标改 1px 透明；源码链接改本仓库；许可/更新置灰 |

---

## 3. 构建 / 安装 / 验收

```bash
cd /data/local/tmp/ws/projects/irx-ui
export ANDROID_HOME=/opt/android-sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64

# release（推荐，性能差一个数量级，见 §6.5）
gradle --no-daemon :app:assembleStableRelease
# debug（仅调试用；会慢很多，别拿它评性能）
gradle --no-daemon :app:assembleStableDebug
```

**必须只编一个 flavor（Stable）**。三个 flavor 一起编会让 sqlite 的 native 库在同一个 JVM 里加载两次 → 失败。

安装（root shell）：

```sh
pm install -r app/build/outputs/apk/Stable/release/app-Stable-release.apk
am start -n com.show.taps.revived/com.rosan.installer.ui.activity.SettingsActivity
```

验收清单（本次已逐项实测）：

1. 桌面/关于页：应用名 **Show Taps**，版本 **1.1.1 (10101)**，关于页大图标位置**空**（1px 透明占位）
2. 底栏：主页（自绘触点图标）/ 配置 / 预设 / 设置
3. 设置页只剩：主题设置、覆盖状态栏、备份与还原（两条「暂不可用」）、关于
4. 可用特权页只剩：无特权 / ROOT / Shizuku，没有说明卡
5. 点「启动」不再弹「5 秒内点继续」
6. 预设页右下角 + 能导入 `test-preset.json` / `test-pack-*.zip`
7. 翻页流畅（release：p50 8ms / p90 11ms / 0% 掉帧）

---

## 4. 效果包引擎

规范文档：`Tools/LSPilot/projects/show-taps/design/pack-spec-v1.md`（**还没进本仓库**，见 §7）

能力（v1）：

* 四组时间轴：`press`（按下）/ `hold`（按住，可 loop）/ `trail`（每个历史点按年龄）/ `release`（抬起）
* 三种图层：`sprite`（贴图，缩放到 2×radius）/ `ring`（程序化圆环）/ `dot`（程序化实心圆）
* 曲线三写法：固定值、`[from,to]`+缓动（linear/easeIn/easeOut/easeOutQuad/easeOutCubic/easeInOutQuad/backOut）、关键帧 `[[t,v],…]`
* 混合：`plus`（加色，做发光）/ `normal` / `screen`；`tint:"auto"` 跟该手指颜色
* 安全边界：只解析 JSON + 图片，**不执行任何代码**；单包 ≤32MB；贴图解码降到 256px；`tex` 路径禁 `..`

接进引擎的链路：`StConfig.packId` → `StEngine.apply` 解析 → `StPackHolder.current` → `MainView.onDraw` 让位。

---

## 5. 已知问题 / 待办（按优先级）

1. **签名**：现在 debug 签名。要用 `KeySigned_By_Rinkyu` 重签（口令在用户手里）；
   换签名后必须 `pm uninstall com.show.taps.revived` 再装，配置/预设会丢。
2. **IRX 业务层没剥**：`data/ domain/ di/` 全在，安装器/卸载器/安装流程/历史记录仍可跑（入口大多删了）。
   仓库体积 73 个提交文件里有大半是 IRX 的。要瘦身得按 `AGENTS.md` 的路径删。
3. **文案混杂**：通知、权限说明、安装流程里还有 InstallerX 的字符串；`values-*` 里多语言条目也还在。
4. **通知小图标**仍是 IRX 的 `ic_notification_logo` → 换 ST 的 `ic_notification`。
5. **关于页大图标**是 1px 透明占位，正式图标待定。
6. **包规范文档要搬进仓库**（`docs/pack-spec-v1.md`）。
7. baseline profile 没生成：沙箱里的 `adb` 是 x86_64 二进制，跑不了 macrobenchmark；要 x86_64 主机。
8. 效果包引擎 v1 没做：参数面板（`params` 只是占位）、序列帧图集、粒子系统。

---

## 6. 踩过的坑（都是结论，不用再试一遍）

### 6.1 A16 的 `openSession` 门槛
任何不在 AMS ProcessMap 的进程（`app_process` / Shizuku 用户服务 / root shell）都无法 addView，
报 `IllegalStateException: Unknown pid=…`，实测 2041/2039/2038/2002/2036 全灭。
→ 覆盖层只能由 App 进程画。

### 6.2 R8 会毁掉 ST 通道（这条最坑）
`show.taps.server.*` 是 Shizuku/root **按类名字符串反射加载**的；
`show.taps.settings.*` 是 kotlinx.serialization 数据类，字段名参与 JSON。
没有 keep 规则时 release 包打不出来（`materialkolor` 缺类）或者打出来也不工作。
→ `app/proguard-rules.pro` 里已 keep，别删。

### 6.3 `MiuixScrollBehavior()` 不能包 `remember{}`
它本身是 `@Composable` 且内部已经 remember。包起来会编译报
`@Composable invocations can only happen from the context of a @Composable function`。

### 6.4 `beyondViewportPageCount = 0` 更差
试过：每次翻页都要重新组合整页（离屏即销毁），p90 反而更烂。保持 `= 1`。

### 6.5 别拿 debug 包评性能
同一测法（gfxinfo + 4 次翻页）：

| | debug | release |
|---|---|---|
| p50 | 24–26 ms | **8 ms** |
| p90 | 53–65 ms | **11 ms** |
| Janky | 3–6 % | **0 %** |

debug 的 `optimization.enable = false`，Compose 全靠解释执行。之前「miuix 界面卡」的结论就是这个误会。

### 6.6 无障碍覆盖层（可选模式）
`TYPE_ACCESSIBILITY_OVERLAY` = AOSP 层级 31（普通悬浮窗 2038 只有 11，状态栏是 15），
要靠 `AccessibilityService` 给出的 token。
* 开关在 设置 → 常规 → 覆盖状态栏（默认关）
* HyperOS 上必须在系统「无障碍」里手动启用；`settings put secure enabled_accessibility_services` 也能生效
  （实测写入后 90 秒仍在，开关无障碍设置页也没被回写），但用户机器上别擅自改这项设置
* 服务声明里**没有** `canRequestTouchExplorationMode` / `canPerformGestures` / `canRetrieveWindowContent`

---

## 7. 设备与数据

| 项 | 值 |
|---|---|
| 设备 | 小米平板 23117RK66C / Android 16 / SDK 36 / 1440×3200（覆盖 1080×2400）/ density 420 |
| root | KernelSU（`su -c` 无弹窗） |
| Shizuku | 由 **Sui**（zygisk 模块）提供，无独立 App |
| 已装 | `com.show.taps.revived` 1.1.1（debug 签名） |
| 旧探针包 | `com.lspilot.irxprobe` 还在，可随时卸载 |
| 配置数据 | `/data/data/com.show.taps.revived/files/st_configs.json`、`st_presets.json`、`st_packs/<id>/` |
| 测试包 | `Tools/LSPilot/inbox/st-test/`（test-preset*.json / test-pack-ba.zip / test-pack-nofx.zip） |

---

## 8. 上游与协议

* 上游 UI：`wxxsfxyzm/InstallerX-Revived`（GPL-3.0）— 本地 git 的 `origin` 还是它，**没有自己的远端**
* 触摸引擎：`k3x1n/Android-Show-Taps` + `RONUKUIGO/Show-Taps-Revived`（GPL-3.0）
* 本仓库：GPL-3.0，两处改动都要保留原版权头（`SPDX-License-Identifier: GPL-3.0-only` 已在文件头）

搬代码时的实际义务（GPLv3，不是可选）：保留版权声明、标明修改、整体仍以 GPL-3.0 分发。
