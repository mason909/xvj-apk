package com.xvj.app.serial

/**
 * 阳光视翰墙板 QB 总线收帧解析器（协议来源：墙板/QB.HQB 键位映射文件逆向，2026-11）。
 * 纯字节状态机，无 Android 依赖，便于移植单测；与 [WallPanelLink] 配套使用。
 *
 * QB 帧 6 字节：F5 05 <按键> <家族> <数据> 00
 *   家族 0x21 → 编号切换，数据字节 = NN（1~32），对应「切文件夹编号」语义
 *   家族 0x26/0x25/0x22 等 → 功能键，原样上抛由调用方解释
 * C5 帧 6 字节：C5 FD 04 <k1> <k2> 00 → Raw 上抛（语义待抓包确认）
 * VOD 帧 5 字节：FE/FD 04 <k1> <k2> 00 → Raw 上抛（点歌机总线方向）
 *
 * 未知帧头或校验失败时逐字节滑动重同步，容忍线路噪声与粘包。
 */
sealed class WallPanelEvent {
    /** 编号键按下：number = 1~32（超范围属线路噪声，由调用方过滤） */
    data class NumberSelect(val number: Int) : WallPanelEvent()

    /** QB 功能帧：family=家族字节，key=按键字节，data=数据字节 */
    data class Command(val family: Int, val key: Int, val data: Int) : WallPanelEvent()

    /** 未归类定长帧（C5 / VOD 方向）：原始字节上抛 */
    data class Raw(val bytes: ByteArray) : WallPanelEvent() {
        override fun equals(other: Any?) = other is Raw && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
}

class WallPanelParser {

    private val buf = ByteArray(MAX_FRAME)
    private var len = 0

    /** 喂入一个字节（0~255）；凑齐一帧时返回解析事件，否则返回 null。 */
    fun feed(b: Int): WallPanelEvent? {
        if (len >= buf.size) len = 0 // 正常流程到不了（最长帧 6B，坏帧即滑动丢弃），防御性兜底
        buf[len++] = b.toByte()
        return tryParse()
    }

    /** 清空半帧残留（重开串口时调用）。 */
    fun reset() {
        len = 0
    }

    private fun tryParse(): WallPanelEvent? {
        while (len > 0) {
            val need = frameLen(buf[0].toInt() and 0xFF)
            if (need == 0) {
                drop(1)
                continue
            }
            if (len < need) return null
            if (!valid()) {
                drop(1)
                continue
            }
            val event = decode(need)
            drop(need)
            return event
        }
        return null
    }

    private fun frameLen(marker: Int) = when (marker) {
        0xF5, 0xC5 -> 6
        0xFE, 0xFD -> 5
        else -> 0
    }

    private fun valid(): Boolean = when (buf[0].toInt() and 0xFF) {
        0xF5 -> buf[1].toInt() and 0xFF == 0x05 && buf[5].toInt() == 0
        0xC5 -> buf[1].toInt() and 0xFF == 0xFD && buf[2].toInt() and 0xFF == 0x04 && buf[5].toInt() == 0
        else -> buf[1].toInt() and 0xFF == 0x04 && buf[4].toInt() == 0
    }

    private fun decode(need: Int): WallPanelEvent = when {
        buf[0].toInt() and 0xFF == 0xF5 && buf[3].toInt() and 0xFF == 0x21 ->
            WallPanelEvent.NumberSelect(buf[4].toInt() and 0xFF)
        buf[0].toInt() and 0xFF == 0xF5 ->
            WallPanelEvent.Command(buf[3].toInt() and 0xFF, buf[2].toInt() and 0xFF, buf[4].toInt() and 0xFF)
        else -> WallPanelEvent.Raw(buf.copyOf(need))
    }

    private fun drop(n: Int) {
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
    }

    private companion object {
        const val MAX_FRAME = 6
    }
}
