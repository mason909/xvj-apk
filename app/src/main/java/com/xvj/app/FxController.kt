package com.xvj.app

import android.os.SystemClock

/**
 * DMX 特效层与远端（PAD/云端）特效动作共用的唯一状态机。
 * 契约见 docs/dmx-fx-channels.md §5，改动先改文档。
 *
 * 只允许在主线程调用（sink 直接改 View/Player）。
 */
enum class FxKind { BLACKOUT, STROBE, ROTATE, ZOOM, LOOP, SPEED }

enum class FxOwner { DMX, REMOTE }

sealed interface FxResult {
    data object Applied : FxResult
    data object NoOp : FxResult
    data object Released : FxResult
    data object RejectedBusy : FxResult
}

interface FxSink {
    fun onBlackout(alpha: Float)
    fun onStrobe(hz: Float)
    fun onRotate(degPerSec: Float)
    fun onZoom(scale: Float)
    fun onLoop(on: Boolean)
    fun onSpeed(rate: Float)
}

/** 通道号 - 1 是帧数组下标；通道 1 是切格事件，不在这张表里 */
val DMX_FX_CHANNELS = mapOf(
    2 to FxKind.BLACKOUT, 3 to FxKind.STROBE, 4 to FxKind.ROTATE,
    5 to FxKind.ZOOM, 6 to FxKind.LOOP, 7 to FxKind.SPEED,
)

class FxController(
    private val sink: FxSink,
    /** 链路是否 5s 内收过帧；由 MainActivity 注入，控制器自己不碰串口 */
    private val dmxLinkAlive: () -> Boolean = { false },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    companion object {
        const val DEADZONE = 2
        const val THROTTLE_MS = 33L

        /** raw → 物理量。0 一律是基线（释放）。 */
        fun blackoutAlpha(raw: Int): Float = (raw and 0xFF) / 255f

        fun strobeHz(raw: Int): Float =
            if (raw <= 0) 0f else 1f + (raw - 1) * 19f / 254f

        fun rotateDegPerSec(raw: Int): Float = when {
            raw <= 0 || raw == 128 -> 0f
            raw < 128 -> -(raw / 127f) * 90f
            else -> ((raw - 128) / 127f) * 90f
        }

        fun zoomScale(raw: Int): Float = 1f + raw / 255f

        fun loopOn(raw: Int): Boolean = raw >= 128

        fun speedRate(raw: Int): Float = when {
            raw <= 0 -> 1f
            raw <= 128 -> 0.25f + (raw - 1) / 127f * 0.75f
            else -> 1f + (raw - 128) / 127f
        }

        /** 物理量 → raw，PAD/云端用浮点发特效时走这里，保证与 DMX 同一套数值语义 */
        fun rawOf(kind: FxKind, value: Float): Int = when (kind) {
            FxKind.BLACKOUT -> Math.round(value * 255f)
            FxKind.STROBE -> if (value <= 0f) 0 else Math.round(1f + (value - 1f) * 254f / 19f)
            FxKind.ROTATE -> when {
                value == 0f -> 0
                value < 0f -> Math.round(-value / 90f * 127f).coerceIn(1, 127)
                else -> (128 + Math.round(value / 90f * 127f)).coerceIn(129, 255)
            }
            FxKind.ZOOM -> if (value <= 1f) 0 else Math.round((value - 1f) * 255f)
            FxKind.LOOP -> if (value >= 0.5f) 255 else 0
            FxKind.SPEED -> when {
                value <= 0.25f -> 0
                value <= 1f -> Math.round(1f + (value - 0.25f) / 0.75f * 127f)
                else -> (128 + Math.round((value - 1f) * 127f)).coerceAtMost(255)
            }
        }.coerceIn(0, 255)
    }

    /** 当前生效值（raw），snapshot/诊断用 */
    private val state = HashMap<FxKind, Int>()
    /** DMX 各通道最近一次电平：判"DMX 持有"用，归零即让权 */
    private val dmxValue = HashMap<FxKind, Int>()
    /** DMX 去抖基准：上次真正施加的 raw */
    private val dmxApplied = HashMap<FxKind, Int>()
    private val dmxThrottleAt = HashMap<FxKind, Long>()
    private val dmxSeen = HashSet<FxKind>()

    /** 首帧只记基线，不施加 */
    fun apply(kind: FxKind, raw: Int, owner: FxOwner): FxResult {
        val v = raw.coerceIn(0, 255)
        return when (owner) {
            FxOwner.DMX -> applyDmx(kind, v)
            FxOwner.REMOTE -> applyRemote(kind, v)
        }
    }

    private fun applyDmx(kind: FxKind, raw: Int): FxResult {
        dmxValue[kind] = raw
        if (!dmxSeen.contains(kind)) {
            dmxSeen.add(kind)
            dmxApplied[kind] = raw
            return FxResult.NoOp
        }
        val applied = dmxApplied[kind] ?: 0
        // 死区只抹平"作用中"的末位抖动；0（释放）与 128（旋转释放）是安全边界，必须立刻落地，
        // 否则 1Hz 频闪推回 1→0 会被死区吞掉，卡在最低档
        if (raw != 0 && applied != 0 && raw != 128 && applied != 128 &&
            Math.abs(raw - applied) < DEADZONE) return FxResult.NoOp
        val now = clock()
        val last = dmxThrottleAt[kind] ?: 0L
        if (now - last < THROTTLE_MS) return FxResult.NoOp
        dmxApplied[kind] = raw
        dmxThrottleAt[kind] = now
        return land(kind, raw)
    }

    private fun applyRemote(kind: FxKind, raw: Int): FxResult {
        if (holdsByDmx(kind)) return FxResult.RejectedBusy
        if ((state[kind] ?: neutral(kind)) == raw) return FxResult.NoOp
        return land(kind, raw)
    }

    private fun land(kind: FxKind, raw: Int): FxResult {
        val released = raw == 0 || (kind == FxKind.LOOP && raw < 128) ||
            (kind == FxKind.ROTATE && raw == 128)
        state[kind] = if (kind == FxKind.LOOP) (if (raw >= 128) 255 else 0) else raw
        when (kind) {
            FxKind.BLACKOUT -> sink.onBlackout(blackoutAlpha(raw))
            FxKind.STROBE -> sink.onStrobe(strobeHz(raw))
            FxKind.ROTATE -> sink.onRotate(rotateDegPerSec(raw))
            FxKind.ZOOM -> sink.onZoom(zoomScale(raw))
            FxKind.LOOP -> sink.onLoop(loopOn(raw))
            FxKind.SPEED -> sink.onSpeed(speedRate(raw))
        }
        return if (released) FxResult.Released else FxResult.Applied
    }

    private fun holdsByDmx(kind: FxKind): Boolean {
        val v = dmxValue[kind] ?: 0
        if (v == 0 || !dmxLinkAlive()) return false
        return when (kind) {
            FxKind.LOOP -> v >= 128
            FxKind.ROTATE -> v != 128
            else -> true
        }
    }

    /** 断信/退出/全关时调用：所有特效回基线，DMX 基线清空（复联首帧重新只记基线，防一上电爆闪） */
    fun releaseAll() {
        for (kind in FxKind.values()) {
            state[kind] = 0
            emit(kind, 0)
        }
        dmxValue.clear()
        dmxApplied.clear()
        dmxThrottleAt.clear()
        dmxSeen.clear()
    }

    /** 切幕/副屏热插拔/窗口重建后重放覆盖表 */
    fun onWindowsRebuilt() {
        for (kind in FxKind.values()) emit(kind, state[kind] ?: neutral(kind))
    }

    fun snapshot(): Map<FxKind, Int> = state.toMap()

    private fun emit(kind: FxKind, raw: Int) {
        when (kind) {
            FxKind.BLACKOUT -> sink.onBlackout(blackoutAlpha(raw))
            FxKind.STROBE -> sink.onStrobe(strobeHz(raw))
            FxKind.ROTATE -> sink.onRotate(rotateDegPerSec(raw))
            FxKind.ZOOM -> sink.onZoom(zoomScale(raw))
            FxKind.LOOP -> sink.onLoop(loopOn(raw))
            FxKind.SPEED -> sink.onSpeed(speedRate(raw))
        }
    }

    private fun neutral(kind: FxKind): Int = when (kind) {
        FxKind.ZOOM, FxKind.SPEED -> 0 // 0 表示 1.0x，与 DMX 通道释放同口径
        else -> 0
    }
}