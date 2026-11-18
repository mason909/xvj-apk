package com.xvj.app

import android.Manifest
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
 * 出流只出一路 1920×1080，交给 HdmiGlRenderer 画到 N 扇窗：HAL 声明的 maxNumOutputStreams=3
 * 每扇窗直连一层输出时第三窗就配置失败；单路输出后窗口数不再受相机限制，多窗共享一份采集缓冲。
 * 裸 NV12（/dev/video20）也不走：相机 HAL 已做好格式与节奏转换，TextureView 还能直接套窗口的
 * 取景/旋转/镜像矩阵（applyWindowTransform）。
 *
 * 帧率：HAL 的帧率上限来自 /vendor/etc/external_camera_config.xml 的 FpsList，台架那版把
 * 1920×1080 限在 30；源信号是 4K60，v4l2 直读实测 ~55.6FPS（含拷贝开销）。请求里显式写
 * AE_TARGET_FPS_RANGE=[60,60] 让 HAL 按 60 出流（它解析了 60 档，但静态元数据没跟着报）。
 * 若固件拒绝这个范围，回退一次不带帧率范围的请求。
 *
 * 线程：Surface 台账、相机会话、EGL/SurfaceTexture 都在相机线程上；attach/detach/shutdown
 * 从主线程投递。场景重建会连着一串 attach/detach，重开统一去抖成一次。
 */
class HdmiCapture(context: Context, private val log: (msg: String, level: String) -> Unit) {

    companion object {
        /** 采集分辨率：与相机 HAL 支持的 1920×1080 对齐，TextureView 侧要按同一尺寸设默认缓冲 */
        const val W = 1920
        const val H = 1080
        /** 期望出流帧率：源信号 4K60，1080p60 通路已在台架验过 */
        private val FPS_RANGE = Range(60, 60)
        private const val RETRY_MS = 10_000L
        private const val OPEN_TIMEOUT_MS = 8_000L
        /** 重建窗口时 attach/detach 会连成一串，攒一小会儿再重开 */
        private const val RESTART_DEBOUNCE_MS = 150L
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
    /** 出帧失败日志的冷却时间 */
    private var failLoggedAt = 0L

    fun attach(winId: String, surface: Surface) = handler.post {
        val old = surfaces.put(winId, surface)
        renderer.attachWindow(winId, surface) // 内部会把旧 Surface 上的 EGLSurface 先销毁
        if (old != null && old !== surface) old.release()
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
            // 没有窗在用了：收回相机（在途的那一轮由 onOpened 看到空台账自行收摊）
            if (!opening) {
                generation++
                closeLocked()
            }
            openTargets = emptyList()
            return
        }
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
                    startSessionLocked(cam, openTargets, gen, withFps = fpsAllowed)
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
        val request = try {
            cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                targets.forEach { addTarget(it) }
                if (withFps) {
                    try {
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, FPS_RANGE)
                    } catch (e: Exception) {
                        log("HDMI 输入：帧率范围不被接受，退回默认 30fps 请求", "WARN")
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
                                // 60fps 请求超出固件声明的可用范围时，相机服务会整帧拒绝：
                                // 还没出过帧就退回默认帧率重开，本进程内不再尝试
                                if (withFps && frames == 0L && failure.reason == CaptureFailure.REASON_ERROR) {
                                    fpsAllowed = false
                                    log("HDMI 输入：固件不接受 60fps 请求，改回默认帧率重开", "WARN")
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
                        log("HDMI 输入：出流 ${W}x$H（${surfaces.size} 扇窗，请求 ${if (withFps) "60" else "默认"}fps）", "INFO")
                        setStreaming(true)
                    } catch (e: Exception) {
                        if (withFps) {
                            fpsAllowed = false
                            log("HDMI 输入：60fps 请求被拒（${e.message}），退回默认帧率", "WARN")
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
        val low = fps < 45f
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
