package com.xvj.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * HDMI IN 采集管线：RK3588 的 hdmirx 由 Rockchip 相机 HAL 暴露成一台相机（台架报 ID 120）。
 *
 * 台架实测（2026-11-17）：HDMI IN 口收到 4K60 时 `/sys/class/hdmirx/hdmirx/status=connected`，
 * 相机可出 640×360 / 1920×1080 / 3840×2160（PRIVATE，33ms 一帧）。注意这块 HAL 的
 * lens.facing 报的是 BACK，只有 supportedHardwareLevel 是 EXTERNAL，识别见 findHdmiCameraId。
 * 我们取 1080p30：一片包厢屏够用；4K 采集只多占 dma-buf（台架吃过 dma-buf 泄漏被 lmkd 杀的亏）。
 *
 * 不走 v4l2 /dev/video20：裸 NV12 要 JNI + 自建 GL 上传；相机 HAL 已做好格式与节奏转换，
 * TextureView 又能直接套窗口的取景/旋转/镜像矩阵（applyWindowTransform），同一路流还能写给多块 Surface。
 *
 * 线程：Surface 台账与相机会话只在相机线程上读写；attach/detach/shutdown 从主线程投递。
 * 场景重建会连着一串 attach/detach，重开统一去抖成一次；旧 Surface 等会话关掉后再释放
 * （会话还拿着它的时候释放会让 HAL 报错）。
 *
 * 自动去边：会话里再多一路 640×360 YUV 探针（不在时就退回纯画面），扫源画面四周的黑边，
 * 把真实内容的归一化矩形回给界面拼进取景矩阵 —— 输入源自带黑边（含限量范围抬灰的黑）也铺满窗口。
 * 探针占一路处理流：三扇 HDMI 窗已占满 maxNumOutputStreams 时本路不挂，自动去边暂停。
 */
class HdmiCapture(context: Context, private val log: (msg: String, level: String) -> Unit) {

    companion object {
        /** 采集分辨率：与相机 HAL 支持的 1920×1080 对齐，TextureView 侧要按同一尺寸设默认缓冲 */
        const val W = 1920
        const val H = 1080
        /** 外接相机的多路输出上限（相机 HAL 声明 maxNumOutputStreams 处理流 = 3） */
        private const val MAX_STREAMS = 3
        private const val RETRY_MS = 10_000L
        private const val OPEN_TIMEOUT_MS = 8_000L
        /** 重建窗口时 attach/detach 会连成一串，攒一小会儿再重开 */
        private const val RESTART_DEBOUNCE_MS = 150L

        /** 去边探针：与 1080p 同视角的缩略 YUV，64800 像素扫边足够 */
        private const val PROBE_W = 640
        private const val PROBE_H = 360
        /** 探针分析节奏：去边跟的是几何不是画面，400ms 一帧就够 */
        private const val PROBE_SAMPLE_MS = 400L
        /** 同一结果连续出现几次才采纳/撤回：挡住换源黑场与单帧抖动 */
        private const val PROBE_STABLE = 3
        /** 黑边亮度上限（Y）：限量范围的黑≈16，留噪声余量但低于桌面灰（#212121） */
        private const val BAR_LUMA_MAX = 36
        /** 小于这个宽度的边视为信号毛边，不裁（探针像素） */
        private const val BAR_MIN_PX = 2
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("hdmi-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    /** 主线程读：会话在跑（窗口占位文字的显隐按它切） */
    @Volatile var streaming = false
        private set

    /** 主线程回调：出流开始/停止时各触发一次 */
    var onStreamState: ((Boolean) -> Unit)? = null
    /** 相机线程回调：缺 CAMERA 权限。界面负责去要（root 静默授权或系统弹窗） */
    var onNeedPermission: (() -> Unit)? = null
    /** 主线程回调：源画面真实内容框（自动去边）。归一化 [x,y,w,h]，无黑边时 [0,0,1,1] */
    var onSourceCrop: ((FloatArray) -> Unit)? = null

    /** winId -> TextureView 交上来的 Surface；相机线程独占 */
    private val surfaces = LinkedHashMap<String, Surface>()
    /** 已从台账摘掉、但会话可能还拿着的 Surface：等 closeLocked 之后再释放 */
    private val graveyard = ArrayList<Surface>()
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var retryScheduled = false
    private var restartPending = false
    /** openCamera 在途：期间再调一次会抛「相机已打开」，目标没变就等它落地 */
    private var opening = false
    private var openTargets: List<Surface> = emptyList()
    private var openProbe: Surface? = null
    private var reopenAfterOpen = false
    /** 每次重开自增：迟到的 openCamera/onConfigured 回调据此认领自己是不是过期的那一轮 */
    private var generation = 0

    /** 去边探针与判定状态（相机线程独占） */
    private var probe: ImageReader? = null
    /** 这一版固件不接受"画面+探针"混跑：踩过一次就永久退回纯画面，别每轮重开都再试一遍 */
    private var probeDisabled = false
    private var probeAt = 0L
    private var stableRect: IntArray? = null
    private var candidateRect: IntArray? = null
    private var candidateCount = 0
    private var publishedCrop: FloatArray? = null

    fun attach(winId: String, surface: Surface) = handler.post {
        val old = surfaces.remove(winId)
        surfaces[winId] = surface
        if (old != null && old !== surface) graveyard.add(old)
        log("HDMI 输入：窗口 $winId 接入采集", "INFO")
        scheduleRestart()
    }

    /**
     * 带 Surface 身份校验的退出：全量重建时旧窗的 destroy 回调可能比新窗的 available 晚到，
     * 只按 winId 删会把刚挂上的新 Surface 删掉（之后没人再 attach，窗口永久黑屏）。
     */
    fun detach(winId: String, surface: Surface) = handler.post {
        if (surfaces[winId] !== surface) {
            // 已经被新 Surface 顶掉（attach 侧已进墓地），或本来就没挂上
            if (!graveyard.contains(surface)) surface.release()
            return@post
        }
        surfaces.remove(winId)
        graveyard.add(surface)
        log("HDMI 输入：窗口 $winId 退出采集", "INFO")
        scheduleRestart()
    }

    /** 权限刚拿到 / 配置刚变：没有会话就再开一轮 */
    fun retryNow() = handler.post {
        if (device == null && !opening) restartLocked()
    }

    /** HDMI IN 口当前有没有信号（sysfs 读不到就当有，宁可不拦画面） */
    fun signalPresent(): Boolean = try {
        File("/sys/class/hdmirx/hdmirx/status").readText().trim() == "connected"
    } catch (e: Exception) {
        true
    }

    fun shutdown() {
        handler.post {
            generation++
            closeLocked()
            probe?.close()
            probe = null
            surfaces.values.forEach { it.release() }
            surfaces.clear()
            graveyard.forEach { it.release() }
            graveyard.clear()
        }
        thread.quitSafely()
    }

    private fun scheduleRestart() {
        if (restartPending) return
        restartPending = true
        handler.postDelayed({
            restartPending = false
            restartLocked()
        }, RESTART_DEBOUNCE_MS)
    }

    private fun restartLocked() {
        // 上一轮相机还没开起来：目标没变就等它的回调，变了就等它落地后重开
        val targets = surfaces.values.take(MAX_STREAMS)
        if (opening) {
            if (targets != openTargets) reopenAfterOpen = true
            return
        }
        generation++
        closeLocked()
        // 会话已关，旧 Surface 现在释放才安全
        graveyard.forEach { it.release() }
        graveyard.clear()
        if (targets.isEmpty()) {
            openTargets = emptyList()
            return
        }
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            log("HDMI 输入：没有相机权限，采集未启动", "WARN")
            onNeedPermission?.invoke()
            return
        }
        val id = findHdmiCameraId() ?: run {
            log("HDMI 输入：系统里没有可用的采集相机（无 HDMI IN，或固件没把它接进相机 HAL），10 秒后重试", "WARN")
            scheduleRetry()
            return
        }
        val gen = generation
        // 去边探针也吃一路处理流：已经顶到输出上限（三扇窗）时不挂，优先保画面
        val probeSurface = if (!probeDisabled && targets.size < MAX_STREAMS) ensureProbe()?.surface else null
        openTargets = targets
        openProbe = probeSurface
        opening = true
        reopenAfterOpen = false
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    if (gen != generation) { cam.close(); return }
                    opening = false
                    if (reopenAfterOpen) {
                        reopenAfterOpen = false
                        cam.close()
                        restartLocked()
                        return
                    }
                    if (surfaces.isEmpty()) { cam.close(); return }
                    device = cam
                    startSessionLocked(cam, openTargets, openProbe, gen)
                }

                override fun onDisconnected(cam: CameraDevice) {
                    cam.close()
                    if (gen != generation) return
                    opening = false
                    device = null
                    session = null
                    setStreaming(false)
                    log("HDMI 输入：相机被断开（信号源拔线或固件收回），10 秒后重试", "WARN")
                    scheduleRetry()
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close()
                    if (gen != generation) return
                    opening = false
                    device = null
                    session = null
                    setStreaming(false)
                    log("HDMI 输入：相机错误码 $error，10 秒后重试", "ERROR")
                    scheduleRetry()
                }
            }, handler)
            // 没信号时 HAL 可能迟迟不给 onConfigured：超时主动收回，交给重试循环
            handler.postDelayed({
                if (gen == generation && session == null) {
                    log("HDMI 输入：${OPEN_TIMEOUT_MS / 1000} 秒内没出流，收回重试", "WARN")
                    // 自增让还在途的 openCamera 回调作废，否则它会把刚判断为超时的那台相机接上
                    generation++
                    opening = false
                    closeLocked()
                    graveyard.forEach { it.release() }
                    graveyard.clear()
                    scheduleRetry()
                }
            }, OPEN_TIMEOUT_MS)
        } catch (e: Exception) {
            opening = false
            openTargets = emptyList()
            log("HDMI 输入：打开相机失败 ${e.message}", "ERROR")
            scheduleRetry()
        }
    }

    @Suppress("DEPRECATION")
    private fun startSessionLocked(cam: CameraDevice, windows: List<Surface>, probeSurface: Surface?, gen: Int) {
        val targets = if (probeSurface != null) windows + probeSurface else windows
        val request = try {
            cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                targets.forEach { addTarget(it) }
            }
        } catch (e: Exception) {
            log("HDMI 输入：建请求失败 ${e.message}", "ERROR")
            closeLocked()
            scheduleRetry()
            return
        }
        try {
            cam.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation || device !== cam) { s.close(); return }
                    session = s
                    try {
                        s.setRepeatingRequest(request.build(), null, handler)
                        log("HDMI 输入：出流 ${W}x$H（${windows.size} 扇窗）" + if (probeSurface != null) "，带自动去边" else "", "INFO")
                        setStreaming(true)
                    } catch (e: Exception) {
                        if (probeSurface != null) probeDisabled = true
                        log("HDMI 输入：出流失败 ${e.message}", "ERROR")
                        closeLocked()
                        scheduleRetry()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen != generation) return
                    if (probeSurface != null) {
                        // 有的固件不接受 YUV+PRIVATE 混跑：先摘探针保画面，之后不再挂它
                        probeDisabled = true
                        log("HDMI 输入：去边探针这一路没被固件接受，退回纯画面", "WARN")
                        startSessionLocked(cam, windows, null, gen)
                    } else if (windows.size > 1) {
                        // 多路输出不被固件接受时退回单路：第一扇窗能亮，其余保持占位
                        log("HDMI 输入：${windows.size} 路输出配置失败，退回单路", "WARN")
                        startSessionLocked(cam, windows.take(1), null, gen)
                    } else {
                        log("HDMI 输入：会话配置失败", "ERROR")
                        closeLocked()
                        scheduleRetry()
                    }
                }
            }, handler)
        } catch (e: Exception) {
            log("HDMI 输入：建会话失败 ${e.message}", "ERROR")
            closeLocked()
            scheduleRetry()
        }
    }

    /**
     * 去边探针：按需创建（会话要挂它时才建），生命周期跟整个 HdmiCapture 走。
     * 相机 HAL 三档尺寸都支持 YUV_420_888（640×360/1080p/4K），取最小档，只做缩略扫边。
     */
    private fun ensureProbe(): ImageReader? {
        if (probeDisabled) return null
        probe?.let { return it }
        return try {
            val r = ImageReader.newInstance(PROBE_W, PROBE_H, ImageFormat.YUV_420_888, 2)
            r.setOnImageAvailableListener({ reader -> onProbeFrame(reader) }, handler)
            probe = r
            r
        } catch (e: Exception) {
            log("HDMI 输入：去边探针创建失败 ${e.message}（本次不启用自动去边）", "WARN")
            null
        }
    }

    /** 探针帧回调（相机线程）：限频取最新一帧分析；分析失败不能影响出流主链路 */
    private fun onProbeFrame(reader: ImageReader) {
        val img = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - probeAt < PROBE_SAMPLE_MS) return
            probeAt = now
            analyzeProbe(img)
        } catch (e: Exception) {
        } finally {
            img.close()
        }
    }

    /**
     * 扫黑边：从四条边往里走，整条边（隔 4 个像素采样）都低于 [BAR_LUMA_MAX] 才算黑边。
     * 限量范围把黑抬到 Y≈16、桌面灰在 #212121（Y≈33）以上，阈值取 36 两头都能分开。
     * 结果要连续 [PROBE_STABLE] 次一致才采纳/撤回，换源黑场不会把画面一把推近。
     */
    private fun analyzeProbe(img: Image) {
        val plane = img.planes[0]
        val buf: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = img.width
        val h = img.height
        var left = 0
        while (left < w / 2 && columnDark(buf, rowStride, pixelStride, left, h)) left++
        var right = 0
        while (right < w / 2 && columnDark(buf, rowStride, pixelStride, w - 1 - right, h)) right++
        var top = 0
        while (top < h / 2 && rowDark(buf, rowStride, pixelStride, top, w)) top++
        var bottom = 0
        while (bottom < h / 2 && rowDark(buf, rowStride, pixelStride, h - 1 - bottom, w)) bottom++
        if (left < BAR_MIN_PX) left = 0
        if (right < BAR_MIN_PX) right = 0
        if (top < BAR_MIN_PX) top = 0
        if (bottom < BAR_MIN_PX) bottom = 0
        val cw = w - left - right
        val ch = h - top - bottom
        // 内容小于一成说明整屏就是黑（无信号/全黑画面），没有可裁的目标
        val rect = if (cw >= w / 10 && ch >= h / 10) intArrayOf(left, top, cw, ch) else intArrayOf(0, 0, w, h)

        val stable = stableRect
        when {
            stable != null && near(stable, rect) -> {
                candidateRect = null
                candidateCount = 0
            }
            candidateRect != null && near(candidateRect!!, rect) -> {
                candidateCount++
                if (candidateCount >= PROBE_STABLE) {
                    stableRect = rect
                    candidateRect = null
                    candidateCount = 0
                    publishCrop(rect, w, h)
                }
            }
            else -> {
                candidateRect = rect
                candidateCount = 1
            }
        }
    }

    private fun columnDark(buf: ByteBuffer, rowStride: Int, pixelStride: Int, x: Int, h: Int): Boolean {
        var y = 0
        while (y < h) {
            val idx = y * rowStride + x * pixelStride
            if (idx >= buf.limit()) return false
            if ((buf.get(idx).toInt() and 0xFF) > BAR_LUMA_MAX) return false
            y += 4
        }
        return true
    }

    private fun rowDark(buf: ByteBuffer, rowStride: Int, pixelStride: Int, y: Int, w: Int): Boolean {
        val base = y * rowStride
        var x = 0
        while (x < w) {
            val idx = base + x * pixelStride
            if (idx >= buf.limit()) return false
            if ((buf.get(idx).toInt() and 0xFF) > BAR_LUMA_MAX) return false
            x += 4
        }
        return true
    }

    private fun near(a: IntArray, b: IntArray): Boolean =
        abs(a[0] - b[0]) <= 3 && abs(a[1] - b[1]) <= 3 && abs(a[2] - b[2]) <= 3 && abs(a[3] - b[3]) <= 3

    /** 内容框变了（含恢复全画面）才回界面，1% 以内的抖动不打扰 */
    private fun publishCrop(rect: IntArray, w: Int, h: Int) {
        val crop = floatArrayOf(
            rect[0].toFloat() / w, rect[1].toFloat() / h,
            rect[2].toFloat() / w, rect[3].toFloat() / h
        )
        val old = publishedCrop
        if (old != null &&
            abs(old[0] - crop[0]) < 0.01f && abs(old[1] - crop[1]) < 0.01f &&
            abs(old[2] - crop[2]) < 0.01f && abs(old[3] - crop[3]) < 0.01f) return
        publishedCrop = crop
        val l = Math.round(crop[0] * 100)
        val r = Math.round((1f - crop[0] - crop[2]) * 100)
        val t = Math.round(crop[1] * 100)
        val b = Math.round((1f - crop[1] - crop[3]) * 100)
        log("HDMI 自动去边：去掉 左${l}% 右${r}% 上${t}% 下${b}%", "INFO")
        main.post { onSourceCrop?.invoke(crop) }
    }

    private fun closeLocked() {
        try { session?.stopRepeating() } catch (e: Exception) {}
        try { session?.close() } catch (e: Exception) {}
        session = null
        try { device?.close() } catch (e: Exception) {}
        device = null
        setStreaming(false)
    }

    private fun scheduleRetry() {
        if (retryScheduled) return
        retryScheduled = true
        handler.postDelayed({
            retryScheduled = false
            if (surfaces.isNotEmpty() && device == null && !opening) restartLocked()
        }, RETRY_MS)
    }

    /**
     * 找 HDMI IN 相机。camera2 契约是先看 lens.facing=EXTERNAL，但台架这块 Rockchip HAL
     * 把它报成 BACK，只有 supportedHardwareLevel 是 EXTERNAL（2026-11-17 实测），所以分三级：
     * 外接镜头 → 外接硬件等级 → 本机有 hdmirx 节点且只有一台相机时直接用它。
     */
    private fun findHdmiCameraId(): String? {
        val ids = try { manager.cameraIdList.toList() } catch (e: Exception) { emptyList() }
        var byLevel: String? = null
        for (id in ids) {
            val c = try { manager.getCameraCharacteristics(id) } catch (e: Exception) { null } ?: continue
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL) return id
            if (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) byLevel = id
        }
        if (byLevel != null) return byLevel
        // 固件没按契约报 EXTERNAL 也要能用：hdmirx 节点在、只此一台相机，就是这口
        if (ids.size == 1 && File("/sys/class/hdmirx/hdmirx/status").exists()) return ids[0]
        return null
    }

    private fun setStreaming(v: Boolean) {
        if (streaming == v) return
        streaming = v
        main.post { onStreamState?.invoke(v) }
    }
}