package com.xvj.app.serial

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileInputStream

/**
 * DMX512 副板串口链路：与 [WallPanelLink] 同一套 Os.open + stty 手法，但字符格式固定 8N1 ——
 * 副板是自带的 MCU 固件（STC15 + 75176），速率按房间下发，帧定长，不需要空闲断帧。
 *
 * 打开失败 / 读到一半断掉照旧 5s→10s→20s→30s 退避自开：副板上电晚于 App、权限晚生效都常见，
 * 一次失败就放弃等于这间房的 DMX 永久失灵。事件与状态回调统一切主线程，与墙板链路同口径。
 *
 * 首帧诊断：开链路 3 秒还没解出一帧，就把攒下的原始字节打进日志。现场最常见的两种错
 * （串口节点选错、副板发的是另一族帧头）都只能靠这串字节分辨，不猜。
 */
class DmxLink(
    /** 串口节点：上层靠它判断"房间下发的节点变了没有" */
    val path: String = DEFAULT_PATH,
    /** 波特率（房间下发档位之一；null = 不改速率） */
    val baud: Int? = DEFAULT_BAUD,
    private val onFrame: (DmxFrame) -> Unit,
    private val onError: (String) -> Unit = {},
    private val onNotice: (String) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())

    /** 这个 fd 的唯一持有者：close 只发生在 closePort 一处 */
    private var input: FileInputStream? = null

    @Volatile private var wantOpen = false
    @Volatile private var reading = false

    /** 代数：老读线程退出时只有仍为现役代数的那条才认领善后 */
    @Volatile private var generation = 0
    private var retryMs = RETRY_MIN_MS
    private var fails = 0

    private val retryTask = Runnable { if (wantOpen) openOnce() }

    @Synchronized
    fun start() {
        if (wantOpen) return
        wantOpen = true
        openOnce()
    }

    @Synchronized
    fun stop() {
        wantOpen = false
        main.removeCallbacks(retryTask)
        closePort()
    }

    @Synchronized
    private fun openOnce() {
        if (!wantOpen || input != null) return
        val fd = try {
            Os.open(path, OsConstants.O_RDWR or OsConstants.O_NOCTTY, 0)
        } catch (e: ErrnoException) {
            retry("打开 DMX 串口失败 $path: ${e.message}")
            return
        }
        val stream = FileInputStream(fd)
        input = stream
        val gen = ++generation
        reading = true
        Thread({ readLoop(gen) }, THREAD_NAME).start()
        Log.i(TAG, "DMX 串口已打开: $path")
        if (fails > 0) {
            onNotice("DMX 串口已重新打开: $path（此前失败或中断 $fails 次）")
            fails = 0
            retryMs = RETRY_MIN_MS
        }
    }

    private fun retry(msg: String) {
        fails++
        Log.w(TAG, msg)
        onError(msg)
        val wait = retryMs
        retryMs = minOf(retryMs * 2, RETRY_MAX_MS)
        main.postDelayed(retryTask, wait)
    }

    @Synchronized
    private fun closePort() {
        reading = false
        val stream = input ?: return
        input = null
        generation++
        try {
            stream.close()
        } catch (_: Exception) {
        }
    }

    /**
     * raw 模式是硬要求：规范模式会把二进制帧按行规程改写（攒换行、0x0D→0x0A、XON/XOFF 吞字节）。
     * toybox 的 raw 不含 -echo/cs8/-parenb/-crtscts，显式给（副板 8N1 是固件恒等式，不随房间配）。
     * 在读线程里跑（stty 是外部进程）。
     */
    private fun configurePort(gen: Int) {
        val args = mutableListOf("stty", "-F", path, "raw", "-echo", "cs8", "-parenb", "-cstopb", "-crtscts", "cread", "clocal")
        baud?.let { args.add(it.toString()) }
        val err = runStty(args)
        if (gen != generation) return
        val msg = if (err == null)
            "DMX 串口已设为 raw 8N1 ${baud?.let { "$it baud" } ?: "（本次未改速率）"}: $path"
        else "DMX 串口参数未确认生效（$err），链路继续收帧: $path"
        if (err == null) Log.i(TAG, msg) else Log.w(TAG, msg)
        main.post { if (err == null) onNotice(msg) else onError(msg) }
    }

    /** 跑一次 stty：null = 成功，否则是失败原因。看门狗兜底，进程吊住也不能把读线程占着。 */
    private fun runStty(args: List<String>): String? {
        val proc = try {
            ProcessBuilder(args).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return "stty 起不来: ${e.message}"
        }
        val watchdog = Thread({
            try {
                Thread.sleep(STTY_TIMEOUT_MS)
                proc.destroy()
            } catch (_: InterruptedException) {
            }
        }, "dmx-stty-wd").apply { isDaemon = true }
        watchdog.start()
        return try {
            val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = proc.waitFor()
            if (code == 0) null else out.ifEmpty { "stty 退出码 $code" }
        } catch (e: Exception) {
            "stty 超时或读取失败: ${e.message}"
        } finally {
            watchdog.interrupt()
            proc.destroy()
        }
    }

    private fun readLoop(gen: Int) {
        val stream = input ?: return
        configurePort(gen)
        val chunk = ByteArray(64)
        val parser = DmxFrameParser()
        val raw = ByteArray(RAW_DIAG_BYTES)
        var rawLen = 0
        var diagDone = false
        val startedAt = SystemClock.elapsedRealtime()
        var broken: String? = null
        try {
            while (reading) {
                val n = stream.available()
                if (n <= 0) {
                    if (!diagDone) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - startedAt >= RAW_DIAG_MS) {
                            diagDone = true
                            val hex = raw.copyOf(rawLen).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                            main.post {
                                onNotice("DMX 串口 ${RAW_DIAG_MS / 1000} 秒未见有效帧，首批字节: ${hex.ifEmpty { "（一个字节都没收到）" }}")
                            }
                        }
                    }
                    Thread.sleep(POLL_MS)
                    continue
                }
                val got = stream.read(chunk, 0, minOf(chunk.size, n))
                if (got <= 0) break
                for (i in 0 until got) {
                    val b = chunk[i].toInt() and 0xFF
                    if (!diagDone && rawLen < raw.size) raw[rawLen++] = chunk[i]
                    val frame = parser.feed(b) ?: continue
                    if (!diagDone) {
                        diagDone = true
                        main.post { onNotice("DMX 首帧已收: ${frame.summary()}") }
                    }
                    main.post { onFrame(frame) }
                }
            }
        } catch (e: Exception) {
            if (reading) broken = "DMX 串口读取中断: ${e.message}"
        }
        if (wantOpen && gen == generation) main.post { linkDropped(gen, broken) }
    }

    @Synchronized
    private fun linkDropped(gen: Int, broken: String?) {
        if (!wantOpen || gen != generation || input == null) return
        closePort()
        retry(broken ?: "DMX 串口收帧停止: $path")
    }

    companion object {
        private const val TAG = "DmxLink"
        const val DEFAULT_PATH = "/dev/ttyS4"
        const val DEFAULT_BAUD = 115200

        /** 与服务端 DMX_BAUDS 同一份；不在档内 = 不改速率 */
        val BAUDS = listOf(9600, 19200, 38400, 57600, 115200)

        private const val STTY_TIMEOUT_MS = 3000L
        private const val POLL_MS = 20L
        private const val RETRY_MIN_MS = 5000L
        private const val RETRY_MAX_MS = 30000L
        private const val THREAD_NAME = "dmx-serial"

        /** 首帧诊断：3 秒内没收成一帧就把前 48 字节亮出来 */
        private const val RAW_DIAG_MS = 3000L
        private const val RAW_DIAG_BYTES = 48
    }
}
