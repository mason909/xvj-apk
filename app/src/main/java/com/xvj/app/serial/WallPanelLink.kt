package com.xvj.app.serial

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileInputStream

/**
 * 墙板串口链路：用 android.system.Os 直接开 /dev/tty 设备（O_RDWR|O_NOCTTY），
 * 不依赖 libserial_port.so。事件与状态回调统一切到主线程。
 *
 * 设备节点需可读写（root 板常见做法 chmod 666，或系统签名应用）。
 * 打开失败或读到一半断掉（USB 转串口晚于 App 枚举、开机时权限还没生效、现场拔插）
 * 都按 5s→10s→20s→30s 退避自己重开：链路是本地硬件，一次失败就放弃等于这台机的墙板永久失灵，
 * 只能重启 App 才恢复，而大屏设备基本不重启。
 *
 * termios 交给 toybox 的 stty（Android 的 /system/bin/stty，AOSP toybox android_miniconfig 里 CONFIG_STTY=y）：
 * 公开 SDK 没有 termios 结构体、Os.ioctl 不认 TCGETS/TCSETS，而仓库那份 jni/SerialPort.c 从没接进构建。
 * stty 自己 tcsetattr 后会回读校验，驱动没吃下的参数以非零退出码报出来，所以这里不猜成功。
 * 设不上（镜像裁了 stty、CDC ACM 假串口不支持某些位）只告警并继续收帧：端口是开着的，
 * 为波特率没设成把整条链路杀掉等于墙板彻底失灵。
 */
class WallPanelLink(
    /** 串口节点路径：对外可见，上层靠它判断"房间下发的节点变了没有"（变了要重开链路）。 */
    val path: String = DEFAULT_PATH,
    /** 波特率：null = 不下发速率参数，端口保持它当前的速率。同 path，上层靠它判断要不要重开链路。 */
    val baud: Int? = null,
    /** 数据位 5~8：各中控厂家的固件默认档不一样，8 是最常见的那一档，但不能当成永远成立。 */
    val dataBits: Int = 8,
    /** 校验 none/odd/even：'even' = 有校验位但不取反（stty 的 parenb -parodd）。 */
    val parity: String = PARITY_NONE,
    /** 停止位 1~2 */
    val stopBits: Int = 1,
    private val onEvent: (WallPanelEvent) -> Unit,
    private val onError: (String) -> Unit = {},
    private val onNotice: (String) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())

    // 越界值一律当默认档（云端写库前已 400 拦过，这里只是本机不被脏载荷带跑）。
    // 必须排在 gapMs 之前：字符格式决定断帧阈值，属性按声明顺序初始化，反了就拿到 0。
    private val safeDataBits = if (dataBits in 5..8) dataBits else 8
    private val safeStopBits = if (stopBits == 2) 2 else 1
    private val safeParity = if (parity == PARITY_ODD || parity == PARITY_EVEN) parity else PARITY_NONE

    /** 空闲断帧阈值：由速率和字符格式算出来的"2 个字符时间"，未知厂家帧族全靠它切帧。 */
    private val gapMs = WallPanelParser.gapMsOf(baud, safeDataBits, if (safeParity == PARITY_NONE) 0 else 1, safeStopBits)

    /** 这个 fd 的唯一持有者：close 只发生在 closePort 一处（老写法 input/output 各包同一个 fd，stop 时关两次）。 */
    private var input: FileInputStream? = null

    @Volatile private var wantOpen = false
    @Volatile private var reading = false

    /** 代数：读线程退出时只有仍为现役代数的那条才认领善后，避免关掉刚重开好的串口。 */
    @Volatile private var generation = 0
    private var retryMs = RETRY_MIN_MS
    private var fails = 0

    private val retryTask = Runnable { if (wantOpen) openOnce() }

    /**
     * 要求打开串口收帧；已在开着（含正在退避重试）就幂等返回。
     * 失败不往外报布尔：重试已经排上了，调用方只管调一次，不必自己补一次 start()。
     */
    @Synchronized
    fun start() {
        if (wantOpen) return
        wantOpen = true
        openOnce()
    }

    /** 停止收帧并关闭串口；没打开时安全空操作（幂等，onDestroy 与关开关都直接调）。 */
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
            retry("打开串口失败 $path: ${e.message}")
            return
        }
        val stream = FileInputStream(fd)
        input = stream
        val gen = ++generation
        reading = true
        val parser = WallPanelParser(gapMs)
        Thread({ readLoop(gen, parser) }, THREAD_NAME).start()
        Log.i(TAG, "墙板串口已打开: $path")
        if (fails > 0) {
            onNotice("墙板串口已重新打开: $path（此前失败或中断 $fails 次）")
            fails = 0
            retryMs = RETRY_MIN_MS
        }
    }

    /** 排一次退避重试（翻倍封顶）并把原因报出去，让上层写进日志文件。 */
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
        generation++   // 让还在收尾的老读线程不再认领善后
        try {
            stream.close()
        } catch (_: Exception) {
        }
    }

    /**
     * 收帧前把端口打成 raw：规范模式下二进制帧会被行规程改写
     * （攒到换行才返回、0x0D 转 0x0A、0x11/0x13 被当 XON/XOFF 启停流吃掉）。
     * toybox 的 raw 不含 -echo/cs8/-parenb/-crtscts，这几位得显式给：485 是半双工，
     * 回显会把收到的字节顶回总线撞上墙板自己的发送；两线制没有 CTS 可等。
     * 数据位/校验/停止位按房间下发（各中控厂家的固件档位不同，8N1 只是最常见的那一档，不是恒等式），
     * 这三位的形状还决定空闲断帧阈值，所以不能"猜一个"—— 见 [gapMs]。
     * 在读线程里跑 —— stty 是外部进程，放主线程会卡 UI。
     */
    private fun configurePort(gen: Int) {
        val args = mutableListOf("stty", "-F", path, "raw", "-echo", "cs$safeDataBits")
        args.addAll(parityFlags())
        args.addAll(stopFlags())
        args.addAll(listOf("-crtscts", "cread", "clocal"))
        baud?.let { args.add(it.toString()) }
        val err = runStty(args)
        if (gen != generation) return   // 配置期间端口已被关掉/重开：这条结果说的是上一个 fd，别报给现场
        val msg = if (err == null)
            "墙板串口已设为 raw ${charFormat()} ${baud?.let { "$it baud" } ?: "（本次未改速率）"}, 断帧 ${gapMs}ms: $path"
        else "墙板串口参数未确认生效（$err），链路继续收帧: $path"
        if (err == null) Log.i(TAG, msg) else Log.w(TAG, msg)
        main.post { if (err == null) onNotice(msg) else onError(msg) }
    }

    /** 8N1 / 8E1 / 7O2 那套写法：进日志给人看，也是现场对表的凭据 */
    private fun charFormat(): String = "$safeDataBits$parityLetter$safeStopBits"

    private val parityLetter: String = when (safeParity) {
        PARITY_ODD -> "O"
        PARITY_EVEN -> "E"
        else -> "N"
    }

    /**
     * stty 的校验位参数：无校验要显式 -parenb，odd = parenb + parodd，even = parenb + -parodd。
     * 返回**多个** argv —— 合成一个带空格串的传法是无效的，stty 会把它当成一个看不懂的选项。
     */
    private fun parityFlags(): List<String> = when (safeParity) {
        PARITY_ODD -> listOf("parenb", "parodd")
        PARITY_EVEN -> listOf("parenb", "-parodd")
        else -> listOf("-parenb")
    }

    private fun stopFlags(): List<String> = if (safeStopBits == 2) listOf("cstopb") else listOf("-cstopb")

    /** 跑一次 stty：null = 成功，否则是失败原因。看门狗兜底 —— 进程吊住也不能把读线程永远占着。 */
    private fun runStty(args: List<String>): String? {
        val proc = try {
            ProcessBuilder(args).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return "stty 起不来: ${e.message}"
        }
        // waitFor(timeout) 要 API 26，minSdk 24 用不了；destroy 会关掉管道，卡在 readText 的这边随之抛错退出
        val watchdog = Thread({
            try {
                Thread.sleep(STTY_TIMEOUT_MS)
                proc.destroy()
            } catch (_: InterruptedException) {
            }
        }, "wallpanel-stty-wd").apply { isDaemon = true }
        watchdog.start()
        return try {
            val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = proc.waitFor()
            if (code == 0) null else out.ifEmpty { "stty 退出码 $code" }
        } catch (e: Exception) {
            "stty 超时或读取失败: ${e.message}"
        } finally {
            watchdog.interrupt()
            // 正常收尾时这句是空操作：libcore 的 destroy 在锁内认 hasExited，不会拿已收割的 pid 去 kill
            proc.destroy()
        }
    }

    private fun readLoop(gen: Int, parser: WallPanelParser) {
        val stream = input ?: return
        configurePort(gen)
        val chunk = ByteArray(64)
        var broken: String? = null
        try {
            while (reading) {
                val n = stream.available()
                if (n <= 0) {
                    // 没有新字节 = 线路可能安静下来了，空闲断帧只在这个时机判（帧内的字节到达不给它机会切）
                    parser.onIdle(SystemClock.elapsedRealtime())?.let { event -> main.post { onEvent(event) } }
                    // 半帧还在攒时贴紧一点轮询：断帧阈值可能只有 1ms，慢轮询会把阈值糊过去
                    Thread.sleep(if (parser.busy()) BUSY_POLL_MS else POLL_MS)
                    continue
                }
                val got = stream.read(chunk, 0, minOf(chunk.size, n))
                if (got <= 0) break
                // 一次 read 拿到的这批字节同属一个到达窗口，时间戳就取这一次；逐字节造时间是假精度
                val nowMs = SystemClock.elapsedRealtime()
                for (i in 0 until got) {
                    parser.feed(chunk[i].toInt() and 0xFF, nowMs)?.let { event ->
                        main.post { onEvent(event) }
                    }
                }
            }
        } catch (e: Exception) {
            // stop() 关流也会走到这里，所以只在还该读的时候才算异常中断
            if (reading) broken = "串口读取中断: ${e.message}"
        }
        if (wantOpen && gen == generation) main.post { linkDropped(gen, broken) }
    }

    /** 读线程自己退出了：设备被拔或驱动报错，上层没提示，这里自己把它拉回来。 */
    @Synchronized
    private fun linkDropped(gen: Int, broken: String?) {
        // 代数在锁内再认一次：openOnce 换过代的，说明现役 fd 已经是新开的串口，
        // 这条迟到的收尾通知不能再把它关掉（否则表现为"按号没反应"且没人重开）
        if (!wantOpen || gen != generation || input == null) return
        closePort()
        retry(broken ?: "串口收帧停止: $path")
    }

    companion object {
        private const val TAG = "WallPanelLink"
        const val DEFAULT_PATH = "/dev/ttyUSB0"

        /** 房间能下发的波特率档位（与服务端 WALLPANEL_BAUDS、前端 WP_SERIAL_BAUDS 同一份）；不在档内 = 不改速率 */
        val BAUDS = listOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200)

        /** 校验档位（与服务端 WALLPANEL_PARITIES、前端 WP_SERIAL_PARITIES 同一份） */
        const val PARITY_NONE = "none"
        const val PARITY_ODD = "odd"
        const val PARITY_EVEN = "even"
        val PARITIES = listOf(PARITY_NONE, PARITY_ODD, PARITY_EVEN)

        /** 数据位与停止位档位（同服务端校验） */
        val DATA_BITS = listOf(5, 6, 7, 8)
        val STOP_BITS = listOf(1, 2)

        private const val STTY_TIMEOUT_MS = 3000L
        private const val POLL_MS = 20L

        /** 半帧在攒时的轮询节奏：断帧阈值按 115200 算只有 0.17ms，20ms 一跳会把阈值糊成一整段 */
        private const val BUSY_POLL_MS = 2L
        private const val RETRY_MIN_MS = 5000L
        private const val RETRY_MAX_MS = 30000L
        private const val THREAD_NAME = "wallpanel-serial"
    }
}
