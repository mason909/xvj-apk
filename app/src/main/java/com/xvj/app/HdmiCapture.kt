package com.xvj.app

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import java.io.File

/**
 * HDMI IN 采集管线：RK3588 的 hdmirx 由 Rockchip 相机 HAL 暴露成一台相机（台架报 ID 120）。
 *
 * 台架实测（2026-11-17）：HDMI IN 口收到 4K60 时 `/sys/class/hdmirx/hdmirx/status=connected`，
 * 相机可出 640×360 / 1920×1080 / 3840×2160。注意这块 HAL 的 lens.facing 报的是 BACK，
 * 只有 supportedHardwareLevel 是 EXTERNAL，识别见 findHdmiCameraId。
 *
 * 出流只出一路 3840×2160，交给 HdmiGlRenderer 画到 N 扇窗：HAL 声明的 maxNumOutputStreams=3
 * 每扇窗直连一层输出时第三窗就配置失败；单路输出后窗口数不再受相机限制，多窗共享一份采集缓冲。
 * 裸 NV12（/dev/video20）也不走：相机 HAL 已做好格式与节奏转换，TextureView 还能直接套窗口的
 * 取景/旋转/镜像矩阵（applyWindowTransform）。
 *
 * 帧率：HAL 对外的帧率声明来自 /vendor/etc/external_camera_config.xml 的 FpsList（台架那版
 * 3840×2160 写 25、1920×1080 写 30），静态元数据里能查到的最高档也只有 30，但实测这版 HAL
 * 并没有按声明限流：请求 [30,30] 仍按源信号出 ~59.7fps。要求是采集也保持 4K60，所以这里不写死
 * 帧率，而是请求相机声明的最高一档；声明本身也要在固件里改成 60（台架已覆盖到 /vendor 的
 * overlay 层、断电不丢，量产要烘进固件），否则换一版严格按声明执行的 HAL 会回落到 25/30。
 * 若 HAL 拒绝这个范围，回退一次不带帧率范围的请求。
 *
 * 线程：Surface 台账、相机会话、EGL/SurfaceTexture 都在相机线程上；attach/detach/shutdown
 * 从主线程投递。场景重建会连着一串 attach/detach，重开统一去抖成一次。
 */
class HdmiCapture(context: Context, private val log: (msg: String, level: String) -> Unit) {

    companion object {
        /** 采集分辨率：4K（HAL 支持的 3840×2160 档），TextureView 侧要按同一尺寸设默认缓冲 */
        const val W = 3840
        const val H = 2160
        /** 帧率告警的默认目标（HAL 没报可用范围时的兜底口径） */
        private const val DEFAULT_TARGET_FPS = 60
        /** 重试退避：3s 起、翻倍、封顶 60s。固定 10s 死循环在 HAL 半死时会把缓冲池一轮轮耗光 */
        private const val RETRY_BASE_MS = 3_000L
        private const val RETRY_MAX_MS = 60_000L
        private const val OPEN_TIMEOUT_MS = 8_000L
        /** 重建窗口时 attach/detach 会连成一串，攒一小会儿再重开 */
        private const val RESTART_DEBOUNCE_MS = 150L
        /**
         * 窗口全空了也别立刻收相机：整屏重建的 detach→attach 之间只隔一两百毫秒，
         * 一收一开就是一次相机会话重建（台架实测每次重建都在 HAL 侧留下 dma-buf，
         * 且 2026-11-23 的整机僵死全发生在重建后的「出流」瞬间）。宽限期内保持出流，
         * 新窗口挂上来直接复用；真的没人用了（退出到桌面/点歌屏全关）再收。
         */
        private const val IDLE_CLOSE_MS = 20_000L
        /** 可用内存低于这个数不开相机会话：4K 采集一开就按码流占缓冲，此处是僵死前最后一脚 */
        private const val MIN_OPEN_MB = 300
        /** 帧率采样窗口与低频告警间隔 */
        private const val FPS_SAMPLE_MS = 5_000L
        private const val FPS_WARN_INTERVAL_MS = 60_000L
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("hdmi-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val renderer = HdmiGlRenderer(log)

    /** 主线程读：会话在跑（窗口占位文字的显隐按它切） */
    @Volatile var streaming = false
        private set

    /** 主线程回调：出流开始/停止时各触发一次 */
    var onStreamState: ((Boolean) -> Unit)? = null
    /** 相机线程回调：缺 CAMERA 权限。界面负责去要（root 静默授权或系统弹窗） */
    var onNeedPermission: (() -> Unit)? = null

    /** winId -> TextureView 交上来的 Surface；相机线程独占 */
    private val surfaces = LinkedHashMap<String, Surface>()
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var retryScheduled = false
    /** 连续失败次数：决定下一次重试的退避间隔，出流成功或窗口回流时清零 */
    private var retryAttempt = 0
    /** 无窗口宽限关相机的排程（attach 会撤销） */
    private var idleCloseScheduled = false
    private var restartPending = false
    /** openCamera 在途：期间再调一次会抛「相机已打开」 */
    private var opening = false
    /** 在途那一轮的目标（永远只有一块输入 Surface，保留是为了判断窗口增减要不要重排） */
    private var openTargets: List<Surface> = emptyList()
    private var reopenAfterOpen = false
    /** 每次重开自增：迟到的 openCamera/onConfigured 回调据此认领自己是不是过期的那一轮 */
    private var generation = 0

    /** 帧率台账：每 5 秒算一次真实出帧率，低帧率低频告警 */
    private var frames = 0L
    private var fpsSince = 0L
    private var fpsLogged = false
    private var fpsWarnedAt = 0L
    /** 本进程内还值不值得请求 60fps（固件整帧拒绝后就不试了） */
    private var fpsAllowed = true
    /** 本次开的相机所声明的最高帧率范围（restartLocked 里按相机能力取，null = 不指定） */
    private var fpsRange: Range<Int>? = null
    /** 出帧失败日志的冷却时间 */
    private var failLoggedAt = 0L

    fun attach(winId: String, surface: Surface) = handler.post {
        val old = surfaces.put(winId, surface)
        renderer.attachWindow(winId, surface) // 内部会把旧 Surface 上的 EGLSurface 先销毁
        if (old != null && old !== surface) old.release()
        cancelIdleClose()
        retryAttempt = 0
        log("HDMI 输入：窗口 $winId 接入采集", "INFO")
        scheduleRestart()
    }

    /**
     * 带 Surface 身份校验的退出：全量重建时旧窗的 destroy 回调可能比新窗的 available 晚到，
     * 只按 winId 删会把刚挂上的新 Surface 删掉（之后没人再 attach，窗口永久黑屏）。
     */
    fun detach(winId: String, surface: Surface) = handler.post {
        renderer.detachWindow(surface)
        surface.release()
        if (surfaces[winId] !== surface) return@post // 已被新 Surface 顶掉
        surfaces.remove(winId)
        log("HDMI 输入：窗口 $winId 退出采集", "INFO")
        scheduleRestart()
    }

    /** 权限刚拿到 / 配置刚变：没有会话就再开一轮 */
    fun retryNow() = handler.post {
        retryAttempt = 0
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
            surfaces.values.forEach { it.release() }
            surfaces.clear()
            renderer.shutdown()
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
        if (surfaces.isEmpty()) {
            // 没有窗在用了：先留 20s 宽限。整屏重建的 detach→attach 只隔一两百毫秒，
            // 一收一开就是一次相机会话重建（HAL 侧留 dma-buf，见 IDLE_CLOSE_MS 注释）。
            if (device != null || session != null || opening) scheduleIdleClose()
            else openTargets = emptyList()
            return
        }
        cancelIdleClose()
        // 已经在出流：窗口增减只是渲染侧的挂/摘，不用重开相机会话
        if (device != null && session != null) return
        val targets = listOfNotNull(ensureInputLocked()).takeIf { it.isNotEmpty() } ?: run {
            scheduleRetry()
            return
        }
        // 上一轮相机还没开起来：目标变了就等它落地后重开
        if (opening) {
            if (targets != openTargets) reopenAfterOpen = true
            return
        }
        // 走到这里说明没有可用会话（或只有半开的设备）：收干净再开
        generation++
        closeLocked()
        // 低内存不开相机：4K 采集一开就按码流占缓冲，2026-11-23 整机僵死就是紧接一次「出流」
        val availMb = availableMemMb()
        if (availMb in 1 until MIN_OPEN_MB) {
            log("HDMI 输入：可用内存仅 ${availMb}MB，暂缓接入采集", "WARN")
            scheduleRetry()
            return
        }
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            log("HDMI 输入：没有相机权限，采集未启动", "WARN")
            onNeedPermission?.invoke()
            return
        }
        val id = findHdmiCameraId() ?: run {
            log("HDMI 输入：系统里没有可用的采集相机（无 HDMI IN，或固件没把它接进相机 HAL），${nextRetryDelayMs() / 1000} 秒后重试", "WARN")
            scheduleRetry()
            return
        }
        val gen = generation
        // 帧率不写死：取这台相机声明的最高档（有 60 就上 60），声明本身靠固件改成 4K60
        fpsRange = pickMaxFpsRange(id)
        openTargets = targets
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
                    startSessionLocked(cam, openTargets, gen, withFps = fpsAllowed && fpsRange != null)
                }

                override fun onDisconnected(cam: CameraDevice) {
                    cam.close()
                    if (gen != generation) return
                    opening = false
                    device = null
                    session = null
                    setStreaming(false)
                    log("HDMI 输入：相机被断开（信号源拔线或固件收回），${nextRetryDelayMs() / 1000} 秒后重试", "WARN")
                    scheduleRetry()
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close()
                    if (gen != generation) return
                    opening = false
                    device = null
                    session = null
                    setStreaming(false)
                    // 错误码 4 = MAX_CAMERAS_IN_USE：多半是上一轮的会话还没在 HAL 里落账，
                    // 固定 10 秒硬撞会在半死状态下反复开会话、一轮轮耗缓冲（2026-11-23 前 73 连撞）
                    log("HDMI 输入：相机错误码 $error，${nextRetryDelayMs() / 1000} 秒后重试（连续第 ${retryAttempt + 1} 次）", "ERROR")
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

    /** 相机输出就这一块 SurfaceTexture，GL 渲染器负责铺到每扇窗 */
    private fun ensureInputLocked(): Surface? {
        renderer.inputSurface?.let { return it }
        if (!renderer.start()) return null
        renderer.surfaceTexture?.setOnFrameAvailableListener({ onInputFrame() }, handler)
        return renderer.inputSurface
    }

    @Suppress("DEPRECATION")
    private fun startSessionLocked(cam: CameraDevice, targets: List<Surface>, gen: Int, withFps: Boolean) {
        val reqFps = if (withFps) fpsRange else null
        val fpsLabel = reqFps?.upper ?: DEFAULT_TARGET_FPS
        val request = try {
            cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                targets.forEach { addTarget(it) }
                if (reqFps != null) {
                    try {
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, reqFps)
                    } catch (e: Exception) {
                        log("HDMI 输入：帧率范围不被接受，退回默认帧率请求", "WARN")
                    }
                }
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
                        s.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureFailed(
                                sess: CameraCaptureSession,
                                req: CaptureRequest,
                                failure: CaptureFailure
                            ) {
                                if (gen != generation) return
                                // 请求的帧率超出固件声明的可用范围时，相机服务会整帧拒绝：
                                // 还没出过帧就退回默认帧率重开，本进程内不再尝试
                                if (reqFps != null && frames == 0L && failure.reason == CaptureFailure.REASON_ERROR) {
                                    fpsAllowed = false
                                    log("HDMI 输入：固件不接受 ${fpsLabel}fps 请求，改回默认帧率重开", "WARN")
                                    closeLocked()
                                    restartLocked()
                                } else {
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - failLoggedAt >= FPS_WARN_INTERVAL_MS) {
                                        failLoggedAt = now
                                        log("HDMI 输入：出帧失败（reason=${failure.reason}）", "WARN")
                                    }
                                }
                            }
                        }, handler)
                        frames = 0
                        fpsSince = SystemClock.elapsedRealtime()
                        fpsLogged = false
                        log("HDMI 输入：出流 ${W}x$H（${surfaces.size} 扇窗，请求 ${if (reqFps != null) "${fpsLabel}" else "默认"}fps，可用 ${availableMemMb()}MB）", "INFO")
                        setStreaming(true)
                    } catch (e: Exception) {
                        if (reqFps != null) {
                            fpsAllowed = false
                            log("HDMI 输入：${fpsLabel}fps 请求被拒（${e.message}），退回默认帧率", "WARN")
                            s.close()
                            session = null
                            startSessionLocked(cam, targets, gen, withFps = false)
                        } else {
                            log("HDMI 输入：出流失败 ${e.message}", "ERROR")
                            closeLocked()
                            scheduleRetry()
                        }
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen != generation) return
                    log("HDMI 输入：会话配置失败", "ERROR")
                    closeLocked()
                    scheduleRetry()
                }
            }, handler)
        } catch (e: Exception) {
            log("HDMI 输入：建会话失败 ${e.message}", "ERROR")
            closeLocked()
            scheduleRetry()
        }
    }

    /** 采集线程：每来一帧就画一次，顺带按 5 秒窗口核对真实出帧率 */
    private fun onInputFrame() {
        renderer.drawFrame()
        frames++
        val now = SystemClock.elapsedRealtime()
        if (now - fpsSince < FPS_SAMPLE_MS) return
        val fps = frames * 1000f / (now - fpsSince)
        // 低于目标帧率 20% 才算异常：目标可能是 25/30/60，别拿固定 45 误报
        val low = fps < (fpsRange?.upper ?: DEFAULT_TARGET_FPS) * 0.8f
        if (!fpsLogged || (low && now - fpsWarnedAt >= FPS_WARN_INTERVAL_MS)) {
            log("HDMI 输入：实际出帧 %.1f FPS".format(fps), if (low) "WARN" else "INFO")
            fpsLogged = true
            if (low) fpsWarnedAt = now
        }
        frames = 0
        fpsSince = now
    }

    private fun closeLocked() {
        try { session?.stopRepeating() } catch (e: Exception) {}
        try { session?.close() } catch (e: Exception) {}
        session = null
        try { device?.close() } catch (e: Exception) {}
        device = null
        setStreaming(false)
    }

    /** 无窗口宽限：整屏重建的 detach→attach 间隙里别收相机（见 IDLE_CLOSE_MS） */
    private val idleCloseRunnable = Runnable {
        idleCloseScheduled = false
        if (surfaces.isEmpty() && (device != null || session != null)) {
            generation++
            closeLocked()
            openTargets = emptyList()
            log("HDMI 输入：无窗口 ${IDLE_CLOSE_MS / 1000} 秒，收回采集", "INFO")
        }
    }

    private fun scheduleIdleClose() {
        if (idleCloseScheduled) return
        idleCloseScheduled = true
        handler.postDelayed(idleCloseRunnable, IDLE_CLOSE_MS)
    }

    private fun cancelIdleClose() {
        if (!idleCloseScheduled) return
        idleCloseScheduled = false
        handler.removeCallbacks(idleCloseRunnable)
    }

    /** 下一次重试的等待：3s 起翻倍、封顶 60s（连续失败次数由 scheduleRetry 递增） */
    private fun nextRetryDelayMs(): Long =
        (RETRY_BASE_MS shl retryAttempt.coerceAtMost(5)).coerceAtMost(RETRY_MAX_MS)

    private fun availableMemMb(): Int = try {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.availMem shr 20).toInt()
    } catch (e: Exception) {
        0
    }

    private fun scheduleRetry() {
        if (retryScheduled) return
        retryScheduled = true
        val delay = nextRetryDelayMs()
        if (retryAttempt < 10) retryAttempt++
        handler.postDelayed({
            retryScheduled = false
            if (surfaces.isNotEmpty() && device == null && !opening) restartLocked()
        }, delay)
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

    /**
     * 相机声明的帧率范围里取上限最高的一档（同上限取下限高的）。
     * 台架这版 HAL 静态元数据最高只写 30，固件把声明改成 60 之后会自动跟着变。
     */
    private fun pickMaxFpsRange(id: String): Range<Int>? = try {
        manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.maxWithOrNull(compareBy({ it.upper }, { it.lower }))
    } catch (e: Exception) {
        null
    }

    private fun setStreaming(v: Boolean) {
        if (streaming == v) return
        streaming = v
        // 真的出流了：退避计数清零，下次故障重新从 3s 起
        if (v) retryAttempt = 0
        main.post { onStreamState?.invoke(v) }
    }
}
