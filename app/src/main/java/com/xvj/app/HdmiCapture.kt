package com.xvj.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.io.File

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
    private var reopenAfterOpen = false
    /** 每次重开自增：迟到的 openCamera/onConfigured 回调据此认领自己是不是过期的那一轮 */
    private var generation = 0

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
                    startSessionLocked(cam, openTargets, gen)
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
    private fun startSessionLocked(cam: CameraDevice, targets: List<Surface>, gen: Int) {
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
                        log("HDMI 输入：出流 ${W}x$H（${targets.size} 扇窗）", "INFO")
                        setStreaming(true)
                    } catch (e: Exception) {
                        log("HDMI 输入：出流失败 ${e.message}", "ERROR")
                        closeLocked()
                        scheduleRetry()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen != generation) return
                    // 多路输出不被固件接受时退回单路：第一扇窗能亮，其余保持占位
                    if (targets.size > 1) {
                        log("HDMI 输入：${targets.size} 路输出配置失败，退回单路", "WARN")
                        startSessionLocked(cam, targets.take(1), gen)
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