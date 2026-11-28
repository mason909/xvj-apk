# DMX512 特效通道表（XVJ 灯头规格书）

2026-11-25 定。DMX 通道 1 是切格，通道 2~7 是六个特效；PAD 色彩 COLOR 只走远端，不占 DMX 通道。

一句话：**通道 1 是事件（切格），通道 2~7 是状态（特效），一个通道一个特效；0 一律表示"释放/让权"。**

## 1. 通道表

| 通道 | 特效 | 0 | 1..255 映射 | 类型 |
| --- | --- | --- | --- | --- |
| 1 | 文件夹（格子） | 松手 | 1..30 = 格子 NN，31..255 忽略 | 边沿 |
| 2 | 黑场 `BLACKOUT` | 释放 | alpha = raw/255（1 几乎全亮，255 全黑） | 电平 |
| 3 | 频闪 `STROBE` | 关 | 频率 = 1 + (raw-1)×19/254 Hz（1..20Hz，50% 占空） | 电平 |
| 4 | 旋转 `ROTATE` | 释放 | 1..127 逆时针 ω = raw/127×90°/s；128 释放；129..255 顺时针 ω = (raw-128)/127×90°/s | 电平 |
| 5 | 缩放 `ZOOM` | 释放 | scale = 1.0 + raw/255（1.0..2.0，以窗口中心） | 电平 |
| 6 | LOOP `LOOP` | 释放 | 0..127 不持有；128..255 持有并开（只循环当前素材） | 电平 |
| 7 | 播放速度 `SPEED` | 释放 | 1..128 → 0.25..1.0；129..255 → 1.0..2.0 | 电平 |
| 8..20 | 预留 | — | — | — |

- **8..20 预留**给后续特效（候选：频闪强度、礼物编号、亮度曲线）。**21..512 副板不转发** —— `DmxFrameParser.CHANNELS = 20`，这是硬件上限；真要更多路必须换/改副板固件（厂家依赖），当前 6 个特效装得下，不动。
- 通道表**写死在 APK**，不开放配置（沿用通道 1 的既有口径）。云端只做只读展示。
- 不做「一个值对应一个特效」：频闪是 1..20Hz 连续量，不是 255 档开关。

## 2. 语义：事件 vs 电平

| | 通道 1（切格） | 通道 2..7（特效） |
| --- | --- | --- |
| 触发条件 | 值变化且非零、非首帧 | 值变化且超死区、非首帧 |
| 稳定值 | 不重复触发 | **就是状态本身**，持续施加 |
| 归零 | 松手，无事发生 | **释放**，回基线 |
| 去抖 | `WALLPANEL_REPEAT_DEBOUNCE_MS` | 死区 ±2 LSB + 应用节流 30Hz |

这条划分同时解决了"推子扫过逐值触发"的老副作用：特效通道本来就不该逐值触发，电平天然就是连续量。

## 3. 判决规则（按顺序）

1. **首帧只记基线，不施加**：开机会员灯台可能已经推着频闪，不能一上电就爆闪。首帧把 20 路全记下，之后只看差值。
2. **死区**：|raw - 上次施加| < 2 不施加（灯光台 40Hz 刷新会有末位抖动）。
3. **节流**：同一通道 33ms 内只施加最后一次；通道 1 不走节流。
4. **所有权**：链路 5s 内收过帧 且 该通道当前值非零 ⇒ DMX 持有该特效。持有期间 PAD/云端对该特效的写请求一律回 `BUSY`，不排队、不覆盖（现场口径：有灯光台在打，PAD 那几路置灰）。
5. **断信**：≥3s 收不到帧 ⇒ 所有特效释放回基线（播放不动、格子保持），记一条 `DMX LOST` 日志。频闪卡死不释放是安全问题，优先于"保持最后现场"。
6. **退出到桌面**：与墙板同口径，DMX 触发不了画面；特效一并释放。

## 4. 合成规则（与既有控制面共处）

| 目标 | 合成方式 |
| --- | --- |
| 黑场层 | 有效 alpha = max(墙板全关 ? 1 : 0, DMX 通道 2 alpha)；频闪在此基础上做 50% 占空调制。全关始终盖得住 |
| 循环 | 特效 LOOP 持有 ⇒ `REPEAT_MODE_ONE`；释放后回 `loopPlay ? ALL : OFF`（config `loop`） |
| 旋转/缩放 | 配置变换（crop→scale→rotate→flip）之后再叠特效矩阵，绕窗口中心；窗口重建后按覆盖表重放 |
| 作用域 | 特效作用于**该机所有素材窗**（两块屏），不受格子 `play_settings.dmx` 闸限制 —— 那个闸管的是"这格允许被切"，不是特效能到哪 |
| PAD 色彩 | COLOR 不占 DMX 通道；1/2/4/5/6 为不透明纯色覆盖，3 为 TextureView 去色，0 回原色；主副屏同步，窗口重建后重放 |

## 5. 设备侧接口（唯一落点）

```kotlin
enum class FxKind { BLACKOUT, STROBE, ROTATE, ZOOM, LOOP, SPEED, COLOR }
enum class FxOwner { DMX, REMOTE }          // REMOTE = PAD / 云端

sealed interface FxResult {
    data object Applied : FxResult          // 值变化已落地
    data object NoOp : FxResult             // 死区/重复/首帧
    data object Released : FxResult         // 归零释放
    data object RejectedBusy : FxResult     // 该特效被 DMX 持有，远端写入被拒
}

/** 由 MainActivity 实现，特效语义到视图/播放器的唯一翻译层 */
interface FxSink {
    fun onBlackout(alpha: Float)   // 0f..1f
    fun onStrobe(hz: Float)        // 0f = 停
    fun onRotate(degPerSec: Float) // 0f = 停
    fun onZoom(scale: Float)       // 1f = 原样
    fun onLoop(on: Boolean)
    fun onSpeed(rate: Float)       // 1f = 原速
    fun onColor(code: Int)         // 0 原色；1..6 固定色彩线序，只由 REMOTE 调用
}

class FxController(private val sink: FxSink) {
    fun apply(kind: FxKind, raw: Int, owner: FxOwner): FxResult
    fun releaseAll()
    fun onWindowsRebuilt()                 // 切幕/副屏热插拔后重放覆盖表
    fun snapshot(): Map<FxKind, Int>       // 诊断页 / 回执
}

// 通道表（下标 = 通道号 - 1，通道 1 仍是切格，不进这张表）
val DMX_FX_CHANNELS = mapOf(
    2 to FxKind.BLACKOUT, 3 to FxKind.STROBE, 4 to FxKind.ROTATE,
    5 to FxKind.ZOOM,     6 to FxKind.LOOP,   7 to FxKind.SPEED,
)
```

`handleDmxFrame` 改成两条路：

```
通道 1 值变化 → onDmxTrigger（现有切格链路，不动）
通道 2..7 值变化 → fx.apply(kind, raw, FxOwner.DMX)
```

PAD/云端的 `fx` 动作走同一个 `apply(kind, raw, FxOwner.REMOTE)`，与 485/DMX 共用一套状态机，不存在第二份实现。

## 6. 日志与诊断

- 每条通道**变化**记一行：`DMX 特效 STROBE 3→9（1.6Hz）`；死区内的值不记（40Hz 会刷屏）。
- 首帧、释放、断信、远端被拒各一条，走现有 `logToFile` 的 `DMX` 类目。
- `snapshot()` 供诊断页和心跳可选上报；DMX 本身无回读，值真值在灯光台。

## 7. 落地与验证

随特效层一起实现（见 `xvj-pad/docs/architecture.md` §10.2）。验证沿用台架 FIFO 假副板注帧：

1. 逐通道单打：值 1/64/128/192/255 各注 2s，看日志与画面一致；
2. 首帧不炸：开机先注一帧带频闪 200，确认不闪；
3. 断信：停注 3s，确认特效全回基线、格子没动；
4. 抢占：DMX 持有时 PAD 写同特效回 `BUSY`；
5. 重建：持有时切幕，确认效果在重建后仍在（覆盖表重放）；
6. 负载：4K60 + 频闪 20Hz + 旋转，看帧率与 `MemAvail`（频闪改 alpha 不应额外吃解码）。