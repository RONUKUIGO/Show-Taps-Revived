# Show Taps Revived

在屏幕上显示触摸位置的工具。这一版把界面换成了 **miuix** 风格，并加上了**效果包**机制：
触摸指示器不再只有"圆环 + 轨迹"一种画法，可以导入别人写的效果包（贴图 + 曲线）来换风格。

包名：`com.show.taps.revived`　应用名：Show Taps　许可：GPL-3.0

---

## 这个仓库是什么

它**不是**从零写的新项目，而是三块东西拼起来的：

| 部分 | 来源 | 说明 |
|---|---|---|
| 界面 / 交互骨架 | [InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)（GPL-3.0） | 4 个 tab 的 miuix 页面、设置页、卡片样式、模糊/主题引擎几乎原样搬来 |
| 触摸指示引擎 | [Show Taps](https://github.com/k3x1n/Android-Show-Taps) → [Show-Taps-Revived](https://github.com/RONUKUIGO/Show-Taps-Revived)（GPL-3.0） | 读 `/dev/input` 的 JNI、Shizuku / root 双通道、覆盖层绘制 |
| 本仓库自有代码 | 本仓库 | 效果包引擎（`show/taps/effect/`）、预设库（`show/taps/settings/` + `show/taps/ui/`）、配置编辑页、miuix 适配层 |

为什么会这样拼：Android 16 起覆盖层只能由 App 自身进程绘制（提权进程 `openSession` 会抛
`Unknown pid`），原项目那套"提权进程画窗口"的架构彻底不可用；顺手把 UI 也换成了 miuix，
于是干脆借 InstallerX Revived 的界面骨架来省掉重写。

## 期望

**目标是一件小事：让"触摸显示"可以换皮肤，而不只是换颜色和粗细。**

- 预设（Preset）：纯参数，套用内置绘制规则，零素材
- 效果包（Pack）：`pack.json` + 图片素材 + 时间曲线，声明式描述按下 / 按住 / 拖尾 / 抬起四组动效
- **包只能声明，不能执行代码**：没有 JS、没有 shader、没有表达式求值，导入陌生包不会带来执行风险

效果包规范见 `docs/pack-spec-v1.md`（在 Show-Taps-Revived 仓库的 `design/` 下）。

> 本仓库**不提供任何效果包**。包由使用者自行制作或导入。

## 已知遗留（都是"借 UI"带来的）

1. **InstallerX 的业务逻辑没剥干净**：`data/` `domain/` `di/` 还在，安装器/卸载器页面、安装流程、
   历史记录等都还能跑，只是入口大多被换掉或删掉。想真正瘦身要按 `AGENTS.md` 里的路径删。
2. **文案混杂**：部分字符串仍是 InstallerX 的（通知、权限说明、安装流程），
   中文/英文翻译文件也还带着原项目的条目。
3. **临时不可用的功能**：设置里的「导出设置 / 还原设置」、关于页的「开放源代码许可」「获取更新」，
   目前都置灰，属于占位。
4. **关于页大图标是 1px 透明图**：正式图标还没定。
5. **通知小图标**仍是 InstallerX 的 `ic_notification_logo`，语义不太对。

## 构建

```bash
# 需要 Android SDK + JDK 21
./gradlew :app:assembleStableRelease
# 产物：app/build/outputs/apk/Stable/release/app-Stable-release.apk
```

* 只编一个 flavor（`Stable`）。同时编三个 flavor 会让 sqlite 的 native 库重复加载而失败。
* 默认 `applicationId = com.show.taps.revived`，可用 `-PAPP_ID=xxx` 覆盖（方便共存调试）。
* release 走 R8。`app/proguard-rules.pro` 里有必须的 keep 规则：
  Shizuku 用户服务 / root 守护进程是**按类名反射加载**的，`kotlinx.serialization` 的数据类字段名参与
  JSON 编解码，这两类都不能被混淆或裁剪。
* 没有配置签名时会退回 debug 签名（方便本地验证）。

## 权限说明

| 权限 | 为什么 |
|---|---|
| 显示在其他应用上层（SYSTEM_ALERT_WINDOW） | 画触摸指示层 |
| 前台服务（specialUse） | 让覆盖层所在进程存活 |
| 通知（POST_NOTIFICATIONS） | 前台服务通知（可关，不影响功能） |
| Shizuku 或 root | 读 `/dev/input`；没有它就只能显示自身 App 内的触摸 |
| 无障碍服务（可选，默认关） | 把指示器画到状态栏之上（AOSP 窗口层级 31） |

## 许可

GPL-3.0。界面来自 InstallerX Revived，触摸引擎来自 Show Taps / Show-Taps-Revived，均为 GPL-3.0，
保留原版权声明。
