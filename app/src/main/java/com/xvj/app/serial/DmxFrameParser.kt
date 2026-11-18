package com.xvj.app.serial

/**
 * DMX512 副板（XM-Dmx1 那族 MCU 板）经 UART 送上来的帧：5 字节帧头 + 20 路通道值。
 * 帧头两家固件版本：5A 10 15 00 14 / 5A 10 1A 00 14（见记忆 xvj-device-signal-inputs）。
 * 副板一帧只带 20 路，所以通道号 1~20 与字节位一一对应；第 N 路就是文件夹 NN，映射不在这里做。
 *
 * 与 485 那套不同：DMX 是单向、定长、没有合并总线，所以不必空闲断帧，认帧头就够；
 * 帧头对不上就滑一格重同步（噪声、上电毛刺不该把后一帧吞掉）。
 */
class DmxFrame(val channels: IntArray) {
    /** 日志用：只列非零通道，20 路全打出来没法看 */
    fun summary(): String {
        val sb = StringBuilder()
        for (i in channels.indices) {
            if (channels[i] == 0) continue
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(if (i + 1 < 10) "0" else "").append(i + 1).append('=').append(channels[i])
        }
        return if (sb.isEmpty()) "全零" else sb.toString()
    }
}

class DmxFrameParser {

    private val buf = ByteArray(BUFFER_SIZE)
    private var len = 0

    /** 喂入一个字节（0~255）；凑齐一帧返回，否则 null。 */
    fun feed(b: Int): DmxFrame? {
        if (len >= buf.size) drop(1)   // 脏字节堆满缓冲还没成帧：吐一格给新帧让路
        buf[len++] = b.toByte()
        while (len > 0) {
            if (qi(buf[0]) != HEAD) { drop(1); continue }
            if (len < HEADER_SIZE) return null
            if (!isHeader()) { drop(1); continue }
            if (len < FRAME_SIZE) return null
            val channels = IntArray(CHANNELS)
            for (i in 0 until CHANNELS) channels[i] = qi(buf[HEADER_SIZE + i])
            drop(FRAME_SIZE)
            return DmxFrame(channels)
        }
        return null
    }

    /** 重开串口时清掉半帧残留 */
    fun reset() {
        len = 0
    }

    private fun isHeader(): Boolean =
        qi(buf[1]) == 0x10 && (qi(buf[2]) == 0x15 || qi(buf[2]) == 0x1A) &&
            qi(buf[3]) == 0x00 && qi(buf[4]) == 0x14

    private fun drop(n: Int) {
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
    }

    companion object {
        private const val HEAD = 0x5A
        private const val HEADER_SIZE = 5
        const val CHANNELS = 20
        const val FRAME_SIZE = HEADER_SIZE + CHANNELS
        private const val BUFFER_SIZE = 128

        private fun qi(v: Byte) = v.toInt() and 0xFF
    }
}
