package com.xvj.app.serial

/**
 * 485 总线收帧事件。每个事件都带 [frame] = 这一帧的原始字节：
 * 学习绑定的帧指纹、排障时给人看的 hex，全都只从它算 —— 语义随厂家变，字节不会。
 */
sealed class WallPanelEvent {
    abstract val frame: ByteArray

    /** 视翰 QB「编号切换」帧：number = 1~32。解析出来只作日志标签 —— 切哪一格一律由学习表决定 */
    data class NumberSelect(val number: Int, override val frame: ByteArray) : WallPanelEvent() {
        override fun equals(other: Any?) = other is NumberSelect && number == other.number && frame.contentEquals(other.frame)
        override fun hashCode() = 31 * number + frame.contentHashCode()
    }

    /** 视翰 QB 功能帧：family=家族字节，key=按键字节，data=数据字节 */
    data class Command(val family: Int, val key: Int, val data: Int, override val frame: ByteArray) : WallPanelEvent() {
        override fun equals(other: Any?) = other is Command && family == other.family && key == other.key &&
            data == other.data && frame.contentEquals(other.frame)

        override fun hashCode() = ((family * 256 + key) * 256 + data) * 31 + frame.contentHashCode()
    }

    /** 没落进任何已知帧族的整帧（含 C5/VOD 那两族语义未解的）：不猜含义，只当指纹用 */
    data class Unknown(override val frame: ByteArray) : WallPanelEvent() {
        override fun equals(other: Any?) = other is Unknown && frame.contentEquals(other.frame)
        override fun hashCode() = frame.contentHashCode()
    }
}

/**
 * 485 收帧解析器：已知帧族按定长切，未知帧族靠空闲分帧兜底。
 *
 * 上游不是墙板本身而是**中控**，它把点歌机和墙板两家的流量合并进同一根网线
 * （见记忆 xvj-device-signal-inputs），所以成帧必须同时吃"认识的帧族"和"从没见过帧"：
 *   ① 帧头在 [PROFILES] 表里 → 按该族定长切帧 + 校验，两家混着来也切得干净；
 *   ② 帧头不认识 → 攒着，等线路安静过 [gapMs]（2 个字符时间）整段当一帧吐出。
 *      这就是 Modbus/DMX 那套空闲断帧：不需要知道别家帧长，只需要它一帧之内不静默。
 *      代价照实说：中控若把两帧零间隔转发过来，这里会切成一长帧 —— 所以每台机都要能把
 *      实际收到的字节亮出来给人看（[WallPanelFrameLedger]），分帧对不对，眼睛一看就知道。
 *
 * 时间戳由调用方给（串口读线程用 elapsedRealtime），parser 自己不含 Android 依赖，
 * 便于移植成 JS 单测（与前端 wp-selftest 同一套口径）。
 */
class WallPanelParser(private val gapMs: Long = WallPanelParser.DEFAULT_GAP_MS) {

    private val buf = ByteArray(MAX_FRAME)
    private var len = 0
    private var lastByteAt = 0L

    /** 喂入一个字节（0~255）；凑齐一帧返回事件，否则 null。 */
    fun feed(b: Int, nowMs: Long): WallPanelEvent? {
        if (len >= buf.size) return flush()  // 生字节堆满一帧还没断：先吐出去，别让脏字节顶掉新帧
        buf[len++] = b.toByte()
        lastByteAt = nowMs
        return tryParse()
    }

    /** 一轮询周期没新字节时调：静默够久就把正在攒的未知帧吐出，没在攒则 null。 */
    fun onIdle(nowMs: Long): WallPanelEvent? =
        if (len > 0 && nowMs - lastByteAt >= gapMs) flush() else null

    /** 有半帧在攒：链路拿它决定轮询节奏（帧在飞时贴紧些，空闲时慢轮询省电）。 */
    fun busy(): Boolean = len > 0

    /** 清空半帧残留（重开串口时调用）。 */
    fun reset() {
        len = 0
    }

    private fun flush(): WallPanelEvent? {
        if (len == 0) return null
        val frame = buf.copyOf(len)
        drop(len)
        return WallPanelEvent.Unknown(frame)
    }

    private fun tryParse(): WallPanelEvent? {
        while (len > 0) {
            val p = PROFILES[qi(buf[0])] ?: return null   // 未知帧头：交给空闲断帧
            if (len < p.length) return null
            if (!p.valid(buf)) {                          // 帧头认得、校验不过 = 上一字节是噪声，滑动一格重同步
                drop(1)
                continue
            }
            val frame = buf.copyOf(p.length)
            drop(p.length)
            return p.decode(frame)
        }
        return null
    }

    private fun drop(n: Int) {
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
    }

    /** 一个已知帧族 = 帧长 + 校验 + 解码。新增一家只加一条 Profile。 */
    private class Profile(
        val length: Int,
        val valid: (ByteArray) -> Boolean,
        val decode: (ByteArray) -> WallPanelEvent,
    )

    companion object {
        /** 帧长上限：已知帧族最长 6 字节，多出来的是给未知厂家留的（断帧靠静默，不靠长度） */
        const val MAX_FRAME = 64

        /** 速率不知道时的兜底静默间隔（按 9600 8N1 的 2 字符时间取整，宁大勿小） */
        const val DEFAULT_GAP_MS = 3L

        private fun qi(v: Int) = v and 0xFF

        /**
         * 静默断帧阈值 = 2 个字符时间 = 2 ×(1 起始位 + 数据位 + 校验位 + 停止位) / 波特率。
         * 速率没下发时按 9600 估：估小了一帧会被切成两半，估大了只把断帧推迟几毫秒。
         */
        fun gapMsOf(baud: Int?, dataBits: Int, parityBits: Int, stopBits: Int): Long {
            val bits = 1 + dataBits + parityBits + stopBits
            val ms = 2.0 * bits * 1000.0 / (baud ?: 9600)
            return maxOf(1L, Math.ceil(ms).toLong())
        }

        /** 阳光视翰 QB/C5 + 点歌机 VOD 三族；未知帧头不进这张表，走空闲断帧。 */
        private val PROFILES: Map<Int, Profile> = mapOf(
            // F5 05 <按键> <家族> <数据> 00 —— 家族 0x21 是编号切换，其余是功能键；两类的动作都从学习表来
            0xF5 to Profile(
                6,
                { b -> qi(b[1]) == 0x05 && b[5].toInt() == 0 },
                { f ->
                    val family = qi(f[3])
                    val data = qi(f[4])
                    if (family == 0x21) WallPanelEvent.NumberSelect(data, f)
                    else WallPanelEvent.Command(family, qi(f[2]), data, f)
                }
            ),
            // C5 FD 04 <k1> <k2> 00 —— 语义未解，只当指纹
            0xC5 to Profile(
                6,
                { b -> qi(b[1]) == 0xFD && qi(b[2]) == 0x04 && b[5].toInt() == 0 },
                { f -> WallPanelEvent.Unknown(f) }
            ),
            // FE/FD 04 <k1> <k2> 00 —— 点歌机方向（同一根 485 上另一种帧族），同样只当指纹
            0xFE to Profile(
                5,
                { b -> qi(b[1]) == 0x04 && b[4].toInt() == 0 },
                { f -> WallPanelEvent.Unknown(f) }
            ),
            0xFD to Profile(
                5,
                { b -> qi(b[1]) == 0x04 && b[4].toInt() == 0 },
                { f -> WallPanelEvent.Unknown(f) }
            ),
        )
    }
}
