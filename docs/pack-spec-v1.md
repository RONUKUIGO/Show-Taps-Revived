# ST 效果包规范 v1（写法规则）

> 目标：让"风格化触摸显示"可以不改代码扩展。
> 硬约束：**包只能声明，不能执行代码**。所有动画都是「时间 → 曲线 → 数值」，没有 JS、没有表达式、没有 shader。

---

## 1. 包结构

```
my-pack/            ← 导入时打成 zip
  pack.json         ← 必需，唯一入口
  tex/glow.png      ← 可选素材（PNG/WebP/JPG）
  tex/ring.png
```

导入后落在 `filesDir/st_packs/<id>/`，`pack.json` 里的 `tex` 路径都相对于包根目录。

限制：单包 ≤ 32 MB；贴图解码时超过 256 px 自动降采样；`tex` 路径不允许 `..`。

---

## 2. pack.json 顶层

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `name` | string | 未命名效果包 | 卡片标题 |
| `desc` | string | - | 卡片副标题 |
| `schema` | int | 1 | 规范版本，大于引擎支持的值会被拒绝 |
| `engine` | string | `native` | 只接受 `native`（声明式）。填 `web` 会被明确拒绝 |
| `layers` | object | 必需 | 四组之一，见下 |

`layers` 四个组（都可留空，但不能全空）：

| 组 | 何时生效 | 时间轴起点 |
|---|---|---|
| `press` | 按下瞬间 | 按下那一刻，播完即停 |
| `hold` | 一直按住 | 同上，可 `loop` 循环 |
| `trail` | 移动时 | 每个历史点各自的年龄 |
| `release` | 抬起 | 抬起那一刻 |

---

## 3. 图层

公共字段：

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `type` | string | 必需 | `sprite` / `ring` / `dot` |
| `tex` | string | - | sprite 必需，包内相对路径 |
| `delayMs` | int | 0 | 延迟多久开始 |
| `durationMs` | int | 300 | 单次时长 |
| `loop` | bool | false | 循环（`hold` 里最常用） |
| `radius` | 曲线 | 12 | **半径，dp** |
| `alpha` | 曲线 | 1 | 0–1 |
| `width` | 曲线 | 1.5 | 仅 `ring`：描边宽度，dp |
| `blend` | string | `plus` | `plus`（加色，发光）/ `normal` / `screen` |
| `tint` | string | 保留原色 | `#RRGGBB` / `#AARRGGBB` / `auto`（跟手指颜色） |
| `tintPerFinger` | bool | false | 同上，布尔写法 |
| `rotateStart` | float | 0 | 初始旋转角度 |
| `rotateSpeed` | float | 0 | 每秒旋转角度 |

仅 `trail` 组有效：

| 字段 | 默认 | 说明 |
|---|---|---|
| `decayMs` | 200 | 一个点活多久 |
| `spacing` | 10 | 相邻两点至少隔多少 dp 才画 |
| `maxPoints` | 16 | 最多画几个点 |

三种类型：

| type | 画什么 | 主要用途 |
|---|---|---|
| `sprite` | 素材贴图，缩放到 `2×radius` | 光斑、渐变环、碎片、任意图片 |
| `ring` | 程序化圆环（`width` 描边） | 不用素材也能做扩散圈 |
| `dot` | 程序化实心圆 | 内核亮点、纯色拖尾 |

---

## 4. 曲线（三种写法）

| 写法 | 例子 | 含义 |
|---|---|---|
| 固定值 | `"radius": 12` | 恒定 |
| 起止值 | `"radius": [8, 52]` | 从 8 线性/缓动到 52 |
| 关键帧 | `"alpha": [[0,1],[0.4,0.8],[1,0]]` | 分段线性，允许任意形状 |

`curve` 字段给"起止值"用（关键帧自带分段线性，忽略 `curve`）：

`linear` `easeIn` `easeOut` `easeOutQuad` `easeOutCubic` `easeInOutQuad` `backOut`

---

## 5. 最小可用示例（不用任何素材）

```json
{
  "name": "纯程序化涟漪",
  "schema": 1,
  "engine": "native",
  "layers": {
    "press": [
      { "type": "dot",  "radius": [4, 2],   "alpha": [1, 0],      "durationMs": 180 },
      { "type": "ring", "radius": [8, 52],  "width": [2.0, 0.4],
        "alpha": [0.9, 0], "durationMs": 320, "curve": "easeOutCubic" },
      { "type": "ring", "radius": [6, 34],  "width": [1.5, 0.3],
        "alpha": [0.55, 0], "durationMs": 300, "delayMs": 60, "curve": "easeOutQuad" }
    ],
    "hold": [
      { "type": "ring", "radius": [[0,18],[1,26]], "width": 1.2,
        "alpha": [[0,0.5],[1,0]], "durationMs": 900, "loop": true }
    ],
    "trail": [
      { "type": "dot", "radius": [[0,7],[1,0]], "alpha": 0.5,
        "decayMs": 220, "spacing": 12, "maxPoints": 14, "blend": "plus" }
    ],
    "release": [
      { "type": "ring", "radius": [18, 60], "width": [1.4, 0.2],
        "alpha": [0.7, 0], "durationMs": 260, "curve": "easeOutQuad" }
    ]
  }
}
```

---

## 6. 带素材的写法

```json
{
  "name": "自发光双环",
  "schema": 1,
  "engine": "native",
  "layers": {
    "press": [
      { "type": "sprite", "tex": "tex/glow.png", "radius": [20, 34],
        "alpha": [0.5, 0], "durationMs": 200, "blend": "plus" },
      { "type": "sprite", "tex": "tex/ring.png", "radius": [8, 52],
        "alpha": [0.9, 0], "durationMs": 320, "curve": "easeOutCubic",
        "blend": "plus", "tint": "auto" }
    ],
    "trail": [
      { "type": "sprite", "tex": "tex/glow.png", "radius": [[0,12],[1,0]],
        "alpha": 0.4, "decayMs": 200, "spacing": 10, "maxPoints": 14, "blend": "plus" }
    ]
  }
}
```

---

## 7. 校验与报错

导入时逐字段校验，错误带定位，例如：

```
layers.press[1]: 未知 type "spark"（支持 sprite / ring / dot）
layers.press[2].radius: "abc" 不是数字
engine="web" 不受支持（当前只有 native，即声明式图层）
```

贴图缺失不会让整个包失败，只跳过那一层，并在 logcat 里打 `tex not found: …`。

---

## 8. 已知边界（v1 不做）

- 参数面板：`params` 字段先留着，UI 还没接（改参数需重导包）
- 序列帧：`sprite` 目前是单帧；图集 UV 动画留到 v2
- 粒子系统：碎片/星芒要用多个 `sprite` 图层手摆，没有 `spark` 类型
- 声音 / 震动：不涉及
- 代码型包（HTML/JS）：明确不支持，导入会直接报错
