/**
 * XVJ 云控系统 - Android APP (MainActivity)
 * =========================================
 * 架构：Kotlin + AndroidX + ExoPlayer + MQTT
 *
 * 【代码索引】搜索 "【A-XX】" 快速定位
 * ─────────────────────────────────────────────────
 * 【A-01】生命周期 & 初始化（onCreate / loadConfig / setDebugMode / applyDebugUi / onResume / onPause / onDestroy）
 * 【A-02】MQTT 连接（connectMQTT / onMqttConnected / reconnectMQTT）
 * 【A-03】设备注册 & 授权（registerDevice / sendStatus / handleAuthResponse）
 * 【A-04】MQTT 消息处理 & 分发（handleCommand → when(action)）
 * 【A-06】素材同步（requestRoomMaterialSync / runRoomSyncLoop / doRoomMaterialSync / fetchRoomMaterials /
 *         syncSceneFolders / syncFolderWithIds / runDownloadPlan / downloadWithETag / cachedMd5 /
 *         clearMaterialCache / deleteMaterialFile）
 * 【A-07】窗口配置 & 渲染（applySceneConfigs / scenesFingerprint / applyLiveWindowUpdate / createWindowView / playFolderInWindow）
 * 【A-08】场景切换（switchScene / releaseAllWindows）
 * 【A-09】OTA 自更新（checkForUpdate / isTrustedApkUrl / isValidFilepath / verifyApkSignature / downloadAndInstall / installApk / retryPendingInstall）
 * 【A-10】系统 UI（hideSystemUI）
 * 【A-11】工具方法（logToFile / sha256 / generateDeviceFingerprint / calculateMd5）
 * （A-05 已随"单播放器点播"旧链路一起删除，编号保留空位）
 * ─────────────────────────────────────────────────
 * 
 * 核心流程：
 *   1. MQTT 连接（持久连接，复用于所有通信；command/auth 两个订阅都订 QoS 1）
 *   2. 设备注册 → 服务端审核 authorized=1 → 回 xvj/auth/response（带 scenes + debug + rev）
 *   3. sync_room_materials 命令 → requestRoomMaterialSync 按房间去重后进对账：
 *      /api/room-materials-v2/{roomId} 拉全量清单
 *      → 按 Scene A/B 逐文件夹对账（多的删、缺的/变的进下载计划）
 *      → runDownloadPlan 串行下载整批计划，按云端下发的字节数刷底部进度条（仅调试模式可见）
 *   4. 播放循环：applySceneConfigs 按 scenes 配置渲染窗口（结构与屏上一致则直接跳过，见幂等闸门）
 *
 * 配置版本对账契约（rev，改同步前必读）：
 *   - rev = 服务端对房间 scenes 算的 12 位 sha1 指纹，随 auth_result / sync_room_materials / update_windows 下发；
 *     本机只在"这份配置真的落地"之后才记进 appliedRev（prefs 存一份，冷启动第一次心跳就能报）
 *   - 心跳 xvj/device/{id}/status 带 rev + syncing；服务端 reconcileDeviceRev 拿它和房间真值比，
 *     落后就补发同步（同一设备 3 分钟最多一次，syncing=true 时跳过）—— 丢消息、离线改配置全靠这条兜底
 *   - 本机不自己算 rev（算不出服务端那套序列化口径），只负责"落地了哪一版就回传哪一版"
 * 
 * 房间调试模式（debug_mode）：
 *   - 写入只经 setDebugMode()，三个来源：auth_result 与 sync_room_materials 的 debug 字段、
 *     set_debug 指令；HTTP 侧从不设置它（旧注释里的 /api/room-materials 已废弃）
 *   - SharedPreferences 持久化，重启后由 loadConfig() 读回并 applyDebugUi() 刷一次
 *   - true 时 logToFile() 通过 xvj/device/{id}/log 上报到 device_logs 表
 *   - true 时整层"运维 UI"可见（applyDebugUi）：左下角 statusText 的状态文字 + 底部 syncProgressBar
 *     下载进度条。关掉后大屏上只剩播放内容，那十几处 statusText 赋值不必各自判 debug——
 *     隐藏容器即可，文字照常更新；现场要排查就开调试
 * 
 * 素材文件校验契约（改同步前必读）：
 *   - 云端 materials.md5 存的是 **faststart 重封装之后** 的字节摘要（服务端上传时就地重写文件），
 *     设备下载到的也正是这份字节，所以下载成功一次 md5 就对齐了；
 *     不要把 md5 理解成"用户原始上传文件的 md5"，拿本地素材目录去比对会永远对不上
 *   - 一轮同步里 md5 判定先于 ETag：md5 不同才进 downloadWithETag，
 *     而它内部按 **文件名** 取 If-None-Match —— 若服务端 ETag 没变而 md5 变了，
 *     那个条件 GET 会拿回 304 直接 return false，本地仍是旧文件，下一轮再报不一致（表现为"永远同步不完"）
 *   - 因此服务端就地替换素材文件时必须让 ETag/Last-Modified 一起变（nginx 自动重算，正常没问题）
 *   - ETag/Last-Modified 只在文件**流写完并且实到字节 == 云端 size** 之后才写回 prefs：中途断线不会
 *     留下"半截文件 + 已生效 ETag"这种永远修不好的组合。半截文件也不做 Range 续传——素材是同名就地
 *     替换的（faststart 重封装），旧前缀拼新字节必坏，下一轮一律从 0 重下
 * 
 * 重要约定：
 *   - 设备身份 = deviceId：首启用 fingerprint 播种后写进 SharedPreferences 就不再变，
 *     服务端不会改派 uuid（handleAuthResponse 里那段"换 uuid 重订阅"是死分支，见该函数注释）
 *   - 素材落盘在 getFilesDir() 下：无前缀键 "01" → filesDir/01，带幕前缀 "A01" → filesDir/scenea/01、
 *     "B01" → filesDir/sceneb/01（不是旧注释说的 filesDir/videos/xxx，那是单播放器时代的布局）
 *     卸载即清；downloadDir=filesDir/videos 只剩 onCreate 里一次 mkdirs，已是遗留空目录
 *   - 所有网络请求在下载线程执行，UI 更新 post 到 mqttHandler
 */

package com.xvj.app


import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.widget.FrameLayout
import android.widget.TextView
import android.view.Gravity
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import org.json.JSONArray
import com.xvj.app.databinding.ActivityMainBinding
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.util.concurrent.Executors

// CI 固定 keystore（XVJ_DEBUG_KEYSTORE）证书 DER 的 SHA-256；改动签名必须同步更新，否则设备端 OTA 校验会拒绝安装
private const val EXPECTED_CERT_FINGERPRINT = "d2b08c51a0bcca1f2bc29f20fd8ff5f3927725a9ce3d8355b770ef03c5c960df"

/**
 * XVJ 终端播放器
 * - 无界面，纯执行器
 * - 打开App自动全屏播放
 * - 所有配置由云端下发
 * - 支持MQTT远程推送播放
 * - 硬件绑定授权机制
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    /** 单播放器（仅供未授权时的欢迎视频等 playerView 链路）；窗口播放用下面的 windowPlayers */
    private var player: ExoPlayer? = null

    // 配置项
    private val prefs by lazy { getSharedPreferences("xvj_prefs", MODE_PRIVATE) }
    // 素材根 = filesDir（内部存储，免权限）；物理目录形如 <root>/01、<root>/scenea/01
    private var videoFolderPath: String = ""
    private var loopPlay: Boolean = true

    // MQTT配置 - 云端地址
    private var mqttServer = "tcp://47.102.106.237:1883"
    private var mqttClientId = ""
    private var deviceId: String = ""

    // ========== 多窗口系统 ==========
    // TODO: RS485/DMX512 external signal integration — currentSceneId currently has no active update path
    /** 当前场景 ID："A" 或 "B"。渲染/状态上报都不读它，只有 switchScene 会写（而 switchScene 无人调用） */
    private var currentSceneId = "A"
    /** windowId -> ExoPlayer 实例（每个窗口独立播放器）*/
    private val windowPlayers = mutableMapOf<String, ExoPlayer>()
    /** windowId -> View 实例（窗口视图）*/
    private val windowViews = mutableMapOf<String, View>()
    /** windowId -> 内容签名（type|folderId|color|inputIndex），实时更新时判定结构变化用 */
    private val windowContentSigs = mutableMapOf<String, String>()
    /** 兜底窗口 ID：scenes 里两幕都没有任何窗口时，applySceneConfigs 自动建这一个 1920x1080 全屏 SCENE_A 窗口*/
    private val DEFAULT_WINDOW_ID = "win_1"
    /** 窗口容器 FrameLayout（根视图）*/
    private val flSurface: FrameLayout? get() = binding.root as? FrameLayout
    // ==============================
    private var mqttClient: MqttClient? = null
    private val mqttHandler = Handler(Looper.getMainLooper())
    private val statusHandler = Handler(Looper.getMainLooper())
    // 单线程语义：submit 的是"整轮同步任务"，一轮只占 1 个线程；
    // 池给到 4 只是为了让 auth 触发的同步和手动 sync 不互相排队，文件夹内部仍是逐个串行下载。
    private val downloadExecutor = Executors.newFixedThreadPool(4)
    private var statusTimerRunnable: Runnable? = null

    // 遗留：单播放器时代素材放在 filesDir/videos/ 下。现在同步/播放一律用 videoFolderPath（= filesDir 根），
    // 这里只剩 onCreate 里一次 mkdirs，跑起来是个空的遗留目录，别再往它写东西。
    private val downloadDir by lazy { File(filesDir, "videos") }

    // 设备指纹信息
    private var deviceFingerprint: String = ""

    // 运行时版本号（onCreate 读 packageInfo，OTA 比较与指纹用，勿硬编码）
    private var VERSION_CODE: Int = 0

    // 运行时版本名（onCreate 读 packageInfo，随版本上报）
    private var VERSION_NAME: String = ""

    // 待安装的 APK：无"安装未知应用"权限时暂存，授权回来自动续装
    private var pendingInstallApk: File? = null

    // 续装权限复查用的延时队列（见 retryPendingInstall）， onDestroy 时清空
    private val installRetryHandler = Handler(Looper.getMainLooper())

    // 正在处理的 OTA 版本号：同一版本的下载/确认框只允许一条流程，避免启动+连接+重连三路触发刷屏
    private var otaBusyVersion: String? = null

    // ========== 素材同步在飞去重 & 配置版本对账 ==========
    /** 一条同步请求：载荷 + 服务端配置指纹。见 requestRoomMaterialSync */
    private data class SyncRequest(
        val roomId: String,
        val folderMappingsFallback: org.json.JSONObject,
        val scenes: org.json.JSONObject?,
        val rev: String
    )
    /** 正在跑对账的房间；同房间新的一轮只记进 syncPending，不并发下载同一批文件 */
    private val syncInFlight = mutableSetOf<String>()
    /** 在飞期间收到的后指令：每个房间只留最后一条（服务端只会朝一个目标收敛，中间的都没意义） */
    private val syncPending = mutableMapOf<String, SyncRequest>()
    /** syncInFlight / syncPending 的锁；MQTT 回调线程写、下载线程读写 */
    private val syncLock = Any()
    /** 本机已应用的 scenes 结构指纹：同指纹不再全量重建（重建会打断正在播的视频） */
    private var appliedScenesFp: String? = null
    /** 本机已应用的房间配置 rev（心跳回传，服务端拿它和房间真值比对，落后就补发同步）；重启后仍以 prefs 为准 */
    private var appliedRev: String = ""

    companion object {
        private const val TAG = "XVJPlayer"
        /** 窗口容器内亮度遮罩视图的 tag 标识（实时更新时定位/增删遮罩） */
        const val DIM_TAG = "xvj_dim"
        const val APK_URL = "http://47.102.106.237"
        // 订阅用的通配符主题在 connectMQTT() 里按 deviceId 拼具体路径，不再用常量
        private const val AUTH_TOPIC = "xvj/auth/response"
        // ETag/Last-Modified 缓存的 SharedPreferences key 前缀
        private const val PREF_ETAG_PREFIX = "etag_"
        private const val PREF_LM_PREFIX = "lm_"
        // 素材 md5 缓存前缀：值为 "<文件字节数>:<mtime>:<md5>"，尺寸或改动时间变了即失效
        private const val PREF_MD5_PREFIX = "md5_"
    }

    // 【A-11】 工具方法
    // @tag: logToFile 日志记录 log文件 MQTT上报
    // @tag: generateDeviceFingerprint 设备指纹生成
    // @tag: sha256 签名校验指纹计算（注意：算的是证书 DER 的 SHA-256，不是素材 md5）
    // @tag: calculateMd5 素材文件校验
    /**
     * 写入文件日志（xvj.log）
     * 若 debug_mode 开启，同时通过 MQTT 上报到 device_logs 表
     * 结构化日志写入
     * @param msg 日志内容
     * @param level 日志级别: ERROR, WARN, INFO, DEBUG（默认 INFO）
     * @param module 模块: APP(默认), MQTT, AUTH, SYNC, WINDOW, PLAYBACK
     * @param action 动作: LOG(默认), COMMAND, CONFIG, CREATE, START, SELECT, SKIP, DOWNLOAD, ERROR, AUTH
     *
     * 调用示例：
     *   logToFile("应用启动")                        // INFO, APP, LOG
     *   logToFile("连接失败", "ERROR", "MQTT")     // ERROR, MQTT, LOG
     * level/module/action 都是自由字符串，服务端 device_logs 只按 level 白名单过滤查询，
     * 加新值不用改服务端；但前端日志页的 level 下拉只认 ERROR/WARN/INFO/DEBUG。
     */
    private fun logToFile(msg: String, level: String = "INFO", module: String = "APP", action: String = "LOG") {
        try {
            val logFile = File(filesDir, "xvj.log")
            val logLine = "${System.currentTimeMillis()} $level $module $action $msg"
            PrintWriter(FileWriter(logFile, true)).use { it.println(logLine) }

            // 只有debug_mode开启时才MQTT上报日志
            if (prefs.getBoolean("debug_mode", false)) {
                // 身份统一用 deviceId：服务端 device_logs.device_id 取自 topic，前端按 devices.id 查询
                val lid = deviceId.ifEmpty { deviceFingerprint }
                if (lid.isNotEmpty()) {
                    mqttHandler.post {
                        try {
                            val topic = "xvj/device/${lid}/log"
                            val payload = "${System.currentTimeMillis()} $level $module $msg"
                            // 局部 val 捕获：mqttClient 是可变属性，Kotlin 禁止判空后 smart cast
                            val client = mqttClient
                            if (client == null) {
                                Log.e(TAG, "MQTT client is null, cannot send log")
                            } else if (!client.isConnected) {
                                Log.e(TAG, "MQTT not connected, cannot send log")
                            } else {
                                client.publish(topic, payload.toByteArray(), 1, false)
                                Log.d(TAG, "Log sent via MQTT: $msg")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "MQTT log failed: ${e.message}", e)
                        }
                    }
                } else {
                    Log.w(TAG, "Device identity empty, cannot send log")
                }
            } else {
                Log.d(TAG, "Debug mode off, log not sent via MQTT: $msg")
            }
        } catch (e: Exception) { 
            Log.e(TAG, "logToFile failed: ${e.message}", e)
        }
    }

    // 【A-01】 生命周期 & 初始化
    // ==================== 生命周期 & 初始化 ====================
    // onCreate 一次性装配：权限/横屏/常亮/全屏 → 崩溃兜底 handler → 建素材目录 → 读版本号 →
    //   生成指纹 → loadConfig（deviceId/mqttServer/debug_mode/applied_rev）→ restoreCachedScenes →
    //   connectMQTT → checkForUpdate。
    // 画面起点有两级：冷启动先按上次授权态留下的 scenes_json 直接开播（不等网络，见 restoreCachedScenes），
    //   云端 auth_result / sync_room_materials 到了再按真值校正 —— 配置没变时幂等闸门会挡掉重建，不会重头播。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 强制横屏
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 隐藏系统UI - 纯播放模式
        hideSystemUI()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 使用 app 内部存储目录存放视频（不需要权限）
        videoFolderPath = filesDir.absolutePath

        // 全局崩溃捕获：崩溃原因同步写本地并尽力同步 MQTT 上报。
        // 不走 mqttHandler.post（主线程崩溃时队列任务不再执行），直接同步 publish
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashMsg = "APP崩溃: ${throwable.javaClass.simpleName}: ${throwable.message}" +
                    " at " + throwable.stackTrace.take(6).joinToString(" <- ") {
                        "${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                    }
                PrintWriter(FileWriter(File(filesDir, "xvj.log"), true)).use {
                    it.println("${System.currentTimeMillis()} ERROR APP $crashMsg")
                }
                val client = mqttClient
                val lid = deviceId.ifEmpty { deviceFingerprint }
                if (client != null && client.isConnected && lid.isNotEmpty()) {
                    client.publish(
                        "xvj/device/$lid/log",
                        "${System.currentTimeMillis()} ERROR APP $crashMsg".toByteArray(), 1, false
                    )
                }
            } catch (e: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        logToFile("=== XVJ App Starting ===")
        logToFile("Video folder: $videoFolderPath")

        // 确保下载目录存在
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }

        // 预建素材目录（根 + scenea + sceneb 各 01..30，详见 createMaterialFolders）
        createMaterialFolders()

        // 读取真实版本号（与 build.gradle versionCode 一致；旧实现硬编码 161 导致 OTA 误判循环下载）
        try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            VERSION_CODE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt() else pi.versionCode
            VERSION_NAME = pi.versionName ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "读取版本号失败: ${e.message}")
            VERSION_CODE = 0
            VERSION_NAME = ""
        }

        // 生成设备指纹
        deviceFingerprint = generateDeviceFingerprint()
        Log.d(TAG, "Device Fingerprint: $deviceFingerprint")
        logToFile("Fingerprint: $deviceFingerprint")

        // 加载本机配置（deviceId 等），不含窗口/scenes —— 窗口要等云端指令
        loadConfig()

        // 上次是授权态就先按缓存开播，不等 MQTT 往返（见 restoreCachedScenes）
        restoreCachedScenes()

        // 连接MQTT（注册→授权回执里带 scenes 时才第一次 applySceneConfigs）
        connectMQTT()
        checkForUpdate() // 检查更新
    }

    /**
     * 生成设备唯一指纹
     * 使用RK3588的多个硬件标识组合
     */
    private fun generateDeviceFingerprint(): String {
        val sb = StringBuilder()

        // 1. CPU ID (RK3588 Security ID - 最可靠)
        try {
            val cpuId = readCpuId()
            if (cpuId.isNotEmpty()) {
                sb.append("cpu:$cpuId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read CPU ID: ${e.message}")
        }

        // 2. MAC地址
        try {
            val mac = getMacAddress()
            if (mac.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("|")
                sb.append("mac:$mac")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read MAC: ${e.message}")
        }

        // 3. Android ID (可重置但作为辅助)
        try {
            val androidId = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            if (androidId.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("|")
                sb.append("aid:$androidId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read Android ID: ${e.message}")
        }

        // 4. 设备序列号 (作为备用)
        try {
            val serial = Build.SERIAL
            if (serial.isNotEmpty() && serial != "unknown") {
                if (sb.isNotEmpty()) sb.append("|")
                sb.append("serial:$serial")
            }
        } catch (e: Exception) {}

        // 5. 设备型号
        try {
            val model = Build.MODEL
            if (sb.isNotEmpty()) sb.append("|")
            sb.append("model:${model.replace(" ", "_")}")
        } catch (e: Exception) {}

        // 6. 硬件ID (RK3588特有)
        try {
            val hardware = Build.HARDWARE
            if (sb.isNotEmpty()) sb.append("|")
            sb.append("hw:$hardware")
        } catch (e: Exception) {}

        // 返回SHA256哈希作为最终指纹
        val raw = sb.toString()
        return if (raw.isNotEmpty()) {
            sha256(raw)
        } else {
            // 备用：使用时间戳+随机数（最不可靠）
            "fallback_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        }
    }

    /**
     * 读取RK3588 CPU ID
     * 通过读取 /proc/cpuinfo 或 sysfs
     */
    private fun readCpuId(): String {
        // 方法1: 尝试读取 /sys/devices/soc0/unique_id
        try {
            val file = File("/sys/devices/soc0/unique_id")
            if (file.exists()) {
                val id = file.readText().trim()
                if (id.isNotEmpty()) return id
            }
        } catch (e: Exception) {}

        // 方法2: 尝试读取 /proc/cpuinfo 中的 Serial
        try {
            val cpuInfo = RandomAccessFile("/proc/cpuinfo", "r")
            var line: String?
            while (cpuInfo.readLine().also { line = it } != null) {
                if (line!!.contains("Serial")) {
                    val parts = line!!.split(":")
                    if (parts.size >= 2) {
                        val serial = parts[1].trim()
                        cpuInfo.close()
                        return serial
                    }
                }
            }
            cpuInfo.close()
        } catch (e: Exception) {}

        // 方法3: 使用 Build.getSerial() (需要权限)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serial = Build.getSerial()
                if (serial != Build.UNKNOWN) {
                    return serial
                }
            }
        } catch (e: Exception) {}

        return ""
    }

    /**
     * 获取MAC地址
     */
    private fun getMacAddress(): String {
        try {
            // 尝试读取WLAN MAC
            val wlanFile = File("/sys/class/net/wlan0/address")
            if (wlanFile.exists()) {
                val mac = wlanFile.readText().trim()
                if (mac.isNotEmpty() && mac != "00:00:00:00:00:00") {
                    return mac
                }
            }

            // 尝试读取以太网MAC
            val ethFile = File("/sys/class/net/eth0/address")
            if (ethFile.exists()) {
                val mac = ethFile.readText().trim()
                if (mac.isNotEmpty() && mac != "00:00:00:00:00:00") {
                    return mac
                }
            }
        } catch (e: Exception) {}

        return ""
    }

    /**
     * SHA256哈希
     */
    private fun sha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 从 SharedPreferences 读回本机态：loop_play / mqtt_server / debug_mode / deviceId。
     * deviceId 为空时用刚算出的 fingerprint 播种并写回 —— 之后即使指纹因硬件或算法改动变了，
     * 身份仍以 prefs 里那份为准（这正是"换 uuid"分支成为死分支的同一层原因）。
     * mqttClientId 必须与 deviceId 完全一致，broker ACL 用 xvj/device/%c/# 做设备间隔离。
     * 不恢复窗口：scenes_json 的回落发生在同步完成的地方，不在这里。
     */
    private fun loadConfig() {
        // 使用 app 内部存储目录，不需要权限
        if (videoFolderPath.isEmpty()) {
            videoFolderPath = filesDir.absolutePath
        }

        // 本地配置
        loopPlay = prefs.getBoolean("loop_play", true)
        mqttServer = prefs.getString("mqtt_server", "tcp://47.102.106.237:1883") ?: "tcp://47.102.106.237:1883"

        // 恢复调试模式标志（只可能由 MQTT 写入：set_debug 指令，或 auth_result / sync_room_materials 的 debug 字段）
        val debugMode = prefs.getBoolean("debug_mode", false)
        Log.d(TAG, "Debug mode: $debugMode")

        // 使用设备指纹作为ID
        deviceId = prefs.getString("device_id", "") ?: ""
        if (deviceId.isEmpty()) {
            deviceId = deviceFingerprint
            prefs.edit().putString("device_id", deviceId).apply()
        }

        // clientId 必须严格等于 deviceId：broker ACL 用 pattern xvj/device/%c/# 做设备间隔离
        mqttClientId = deviceId

        // 回读上次已应用的配置版本：心跳一上来就能报 rev，服务端据此判断房间真值有没有比本机新
        appliedRev = prefs.getString("applied_rev", "") ?: ""

        Log.d(TAG, "Device ID: $deviceId")

        // 按 prefs 里的 debug_mode 刷一次运维层（启动即决定左下角文字/进度条是否可见）
        applyDebugUi()
    }

    /**
     * 冷启动离线恢复：上一次是授权态就把 scenes_json 直接摆上屏，
     * 不让大屏在"连 MQTT → 注册 → 授权回执"这一两秒（断网时更久）里黑着。
     * 与随后到的 auth_result 不冲突：applySceneConfigs 按结构指纹幂等，配置没变不会二次重建、视频不重头播。
     * ⚠️ 授权真值只在云端：本机若已被废止或改绑，回执落地时会切欢迎视频 / 按新配置重建，最多错这几秒。
     */
    private fun restoreCachedScenes() {
        if (!prefs.getBoolean("last_authorized", false)) return
        val cached = prefs.getString("scenes_json", null) ?: return
        try {
            applySceneConfigs(org.json.JSONObject(cached))
            binding.statusText?.text = "离线恢复上次画面"
            logToFile("冷启动按缓存恢复窗口（待云端确认）", "INFO", "WINDOW", "CONFIG")
        } catch (e: Exception) {
            Log.e(TAG, "冷启动恢复缓存失败: ${e.message}")
            logToFile("冷启动恢复缓存失败: ${e.message}", "ERROR", "WINDOW", "ERROR")
        }
    }

    /**
     * debug_mode 的唯一写入口：落 prefs + 立刻刷新运维层显隐。
     * 三个来源都走这里：auth_result 与 sync_room_materials 载荷的 debug 字段、set_debug 指令。
     * ⚠️ 调用方必须先判"载荷里到底有没有 debug 字段"再进来（optBoolean 把没带当成 false，
     *    而 update_windows 这类载荷本来就不带）—— 判护在各自的调用点，见 handleCommand。
     */
    private fun setDebugMode(debug: Boolean) {
        prefs.edit().putBoolean("debug_mode", debug).apply()
        applyDebugUi()
    }

    /**
     * 按 debug_mode 收放"运维层"= 左下角状态文字（statusText）+ 底部同步进度条（syncProgressBar）。
     * 设备接投影/大屏，观众看得到画面：调试模式关掉时整层 GONE，
     * 因此全文那十几处 statusText.text 赋值不必各自判 debug（隐藏容器就够了，文字照常更新）。
     * 两个视图在 XML 里都默认 gone（安全侧：启动第一帧也不会漏出运维文字），所以这里只管开的一面。
     * 任意线程可调（内部 post 到主线程）。
     */
    private fun applyDebugUi() {
        val debug = prefs.getBoolean("debug_mode", false)
        mqttHandler.post {
            binding.statusText?.let {
                it.visibility = if (debug) View.VISIBLE else View.GONE
                if (debug) it.bringToFront()
            }
            if (!debug) binding.syncProgressBar?.visibility = View.GONE
        }
    }

    // 【A-02】 MQTT 连接
    // @tag: connectMQTT mqtt连接 broker连接
    /**
     * 连接 MQTT Broker
     * - 设置 MqttConnectOptions（持久会话、保活15秒、30秒超时）
     * - 订阅 commandTopic 和 AUTH_TOPIC
     * - 注册设备并请求授权状态同步
     * - 连接成功后触发 checkForUpdate
     * - 支持指数退避重连（5s → 最大300s）
     * 注：两个订阅都订 QoS 1 —— 订阅端的 QoS 才是链路上限，服务端即使按 QoS 1 发布，
     *     只要这里写 0 就仍会降级成"发一次不等确认、断了不补投"。配合 isCleanSession=false，
     *     断线期间服务端以 QoS 1 发的指令由 broker 排队、重连后按序补投（画面最终停在最后一条，
     *     中间态重放一遍也不改变结果：update_windows 幂等、素材同步按房间去重后只对账一次）。
     */
    private fun connectMQTT() {
        downloadExecutor.submit {
            try {
                Log.d(TAG, "Connecting to MQTT: $mqttServer")
                logToFile("MQTT connecting to $mqttServer...")
                mqttClient = MqttClient(mqttServer, mqttClientId, null)
                val commandTopic = "xvj/device/$deviceId/command"
                val options = MqttConnectOptions()
                options.isCleanSession = false
                options.connectionTimeout = 30
                options.keepAliveInterval = 15
                // broker 鉴权：secret 未配置时 BuildConfig 为空串，此时不带凭据（兼容 allow_anonymous 阶段）
                if (BuildConfig.MQTT_DEVICE_PASSWORD.isNotEmpty()) {
                    options.userName = "xvj_device"
                    options.password = BuildConfig.MQTT_DEVICE_PASSWORD.toCharArray()
                    Log.d(TAG, "MQTT: 使用设备凭据连接")
                } else {
                    Log.d(TAG, "MQTT: 未配置设备凭据，匿名连接")
                }
                // P4 fix: 连接成功/失败通过 setCallback + isConnected 标志判断
                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "MQTT Connection Lost: ${cause?.message}")
                        logToFile("MQTT Connection Lost: ${cause?.message}")
                        mqttHandler.post {
                            binding.statusText?.text = "连接断开,重连中..."
                        }
                        // P4 fix: 检查是否真的断了，再用退避策略重连
                        if (mqttClient?.isConnected != true) {
                            onMqttReconnectFailed()
                            reconnectMQTT()
                        }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        try {
                            val payload = String(message?.payload ?: ByteArray(0))
                            Log.d(TAG, "Received: $topic -> $payload")
                            if (topic == AUTH_TOPIC) {
                                handleAuthResponse(payload)
                            } else {
                                handleCommand(payload)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling message: ${e.message}")
                            logToFile("消息处理异常: ${e.message}")
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                try {
                    mqttClient?.connect(options)
                    Log.d(TAG, "MQTT Connected! Subscribed to: $commandTopic")
                    logToFile("MQTT Connected OK!")
                    onMqttConnected()  // P4 fix: 重置退避计数器
                    mqttClient?.subscribe(commandTopic, 1)
                    mqttClient?.subscribe(AUTH_TOPIC, 1)
                    registerDevice()
                    mqttHandler.post {
                        binding.statusText?.text = "云端已连接"
                    }
                    // P4 fix: 连接成功后执行，不要在闭包外裸调
                    checkForUpdate()
                } catch (e: Exception) {
                    Log.e(TAG, "MQTT Connection Error: ${e.message}")
                    logToFile("MQTT Connection Error: ${e.message}")

                    onMqttReconnectFailed()  // P4 fix: 连接失败也触发退避
                    mqttHandler.post {
                        binding.statusText?.text = "连接失败: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MQTT setup error: ${e.message}")
                logToFile("MQTT setup error: ${e.message}")
            }
        }
    }

    // 【A-03】 设备注册 & 授权
    // @tag: registerDevice 设备注册 authorization授权
    /**
     * 注册设备：QoS1 publish 到 xvj/device/register，载荷同时带 device_id 与 fingerprint
     * （服务端用 "id = ? OR fingerprint = ?" 命中即视为同一台设备，所以指纹变化不会另立新档）
     * 随后立即发一次 online 并起 30s 心跳。
     * 授权结果不在这里回，由服务端另发 xvj/auth/response（见 handleAuthResponse）。
     */
    private fun registerDevice() {
        try {
            val registerTopic = "xvj/device/register"
            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("fingerprint", deviceFingerprint)
                put("model", Build.MODEL)
                put("hardware", Build.HARDWARE)
                put("android_version", Build.VERSION.RELEASE)
                put("sdk_int", Build.VERSION.SDK_INT)
                put("mac", getMacAddress())
                put("timestamp", System.currentTimeMillis())
            }
            mqttClient?.publish(registerTopic, payload.toString().toByteArray(), 1, false)
            Log.d(TAG, "Device registration sent: $deviceId")

            // 注册成功后发送在线状态
            sendStatus("online")
            // 启动定时发送状态
            startStatusTimer()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register device: ${e.message}")
            logToFile("注册设备失败: ${e.message}")
        }
    }

    /**
     * 发送设备状态到MQTT
     * 主题 xvj/device/{id}/status，载荷 status/version/version_code/timestamp + rev/syncing：
     *   rev     = 本机已应用的房间配置指纹（appliedRev，从没应用过带 rev 的载荷时不带这个字段）；
     *             服务端【S-06】reconcileDeviceRev 拿它和房间真值比对，落后就补发一次同步 ——
     *             这是 QoS 丢消息 / 设备离线期间改配置的唯一兜底收敛路径。
     *   syncing = 此刻是否有素材对账在飞；为 true 时服务端跳过对账，免得下载中途被再插一刀。
     * 服务端把 status 落到 devices.status、整段原文存 devices.status_data（该列没有读侧），
     * 并因带了 version/version_code 而 UPSERT device_versions —— 后台"版本管理"页看的正是这张表。
     * 未授权设备同样在上报，服务端不区分；心跳本身 30s 一次，不写 operation_logs。
     * id 直接读 prefs 而不是成员变量，故用局部 val 遮蔽了同名的 deviceId 成员。
     */
    private fun sendStatus(status: String) {
        try {
            val deviceId = prefs.getString("device_id", null) ?: return
            val statusTopic = "xvj/device/$deviceId/status"
            val syncing = synchronized(syncLock) { syncInFlight.isNotEmpty() }
            val payload = JSONObject().apply {
                put("status", status)
                put("version", VERSION_NAME)
                put("version_code", VERSION_CODE)
                put("syncing", syncing)
                if (appliedRev.isNotEmpty()) put("rev", appliedRev)
                put("timestamp", System.currentTimeMillis())
            }
            mqttClient?.publish(statusTopic, payload.toString().toByteArray(), 1, false)
            Log.d(TAG, "Status sent: $status (v$VERSION_CODE)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send status: ${e.message}")
            logToFile("发送状态失败: ${e.message}")
        }
    }

    /**
     * 记一笔"本机已应用到配置版本 rev"：内存里给心跳用，prefs 里留一份给重启后的第一次心跳。
     * 只在对应配置真的应用完才调（服务端按此判断要不要补发同步，报早了会把差异吞掉）。
     */
    private fun markAppliedRev(rev: String) {
        if (rev.isEmpty() || rev == appliedRev) return
        appliedRev = rev
        prefs.edit().putString("applied_rev", rev).apply()
        logToFile("配置版本已应用: rev=$rev", "INFO", "SYNC", "CONFIG")
    }

    /** 起 30s 心跳：先撤掉旧的 Runnable 再挂新的，所以重复调用不会叠加定时器 */
    private fun startStatusTimer() {
        statusTimerRunnable?.let { statusHandler.removeCallbacks(it) }
        statusTimerRunnable = object : Runnable {
            override fun run() {
                if (mqttClient?.isConnected == true) {
                    sendStatus("online")
                }
                statusHandler.postDelayed(this, 30000) // 每30秒发送一次
            }
        }
        statusHandler.post(statusTimerRunnable!!)
    }

    /** 撤心跳并补发一次 offline；onDestroy 里它排在 disconnect 之前，顺序反了这条 offline 就发不出去 */
    private fun stopStatusTimer() {
        statusTimerRunnable?.let {
            statusHandler.removeCallbacks(it)
            // 发送离线状态
            sendStatus("offline")
        }
    }

    /**
     * 处理 xvj/auth/response 上的两类载荷：auth_result（授权结果，成功即带 scenes 直接渲染窗口）
     * 与 deauthorize（远程废止，回到未授权态并播欢迎视频）。
     * ⚠️ AUTH_TOPIC 是全设备共享广播，所以开头那道 device_id 校验是唯一的隔离手段：
     *    targetId 非空且既不是本机 deviceId 也不是指纹 → 直接忽略；deauthorize 缺 targetId 也忽略。
     *    targetId 为空串的 auth_result 例外放行（老服务端不回带 id）。
     * 每次注册后服务端都会回一份。冷启动的画面不等这份回执 —— 上次是授权态时 onCreate 会先用
     * scenes_json 缓存开播（restoreCachedScenes），这里只负责按真值校正；last_authorized 就是那道闸门。
     */
    private fun handleAuthResponse(payload: String) {
        try {
            val resp = JSONObject(payload)
            val action = resp.optString("action", "")
            // AUTH_TOPIC 是全设备共享的广播通道，device_id 才是目标；不校验会把别台设备的废止/授权照单执行
            val targetId = resp.optString("device_id", "")
            if (targetId.isNotEmpty() && targetId != deviceId && targetId != deviceFingerprint) {
                Log.d(TAG, "忽略非本机 auth 广播: $action -> $targetId")
                return
            }
            if (action == "deauthorize" && targetId.isEmpty()) {
                Log.w(TAG, "忽略无目标设备的废止广播")
                return
            }
            when (action) {
                "auth_result" -> {
                    val authorized = resp.getBoolean("authorized")
                    val message = resp.optString("message", "")
                    // 提取房间信息
                    val roomId = resp.optString("room_id", "")
                    val folderMappings = resp.optJSONObject("folder_mappings")
                    // 提取 scenes 配置（包含 A/B 两套窗口配置和各自 folder_mappings）
                    val scenes = resp.optJSONObject("scenes")
                    val rev = resp.optString("rev", "")

                    mqttHandler.post {
                        if (authorized) {
                            prefs.edit().putBoolean("last_authorized", true).apply()
                            binding.statusText?.text = "已授权至房间 $roomId"
                            // 停止欢迎视频等旧播放器
                            releasePlayer()
                            // 授权真值只在云端 devices 表；本机只留两样：scenes_json 缓存（冷启动恢复画面用）
                            // 和 last_authorized（能不能用这份缓存），每次 auth_result 覆盖。
                            if (scenes != null) {
                                prefs.edit().putString("scenes_json", scenes.toString()).apply()
                                Log.d(TAG, "授权成功，已保存 scenes: ${scenes.names()}")
                                // 立即应用窗口配置（离线期间云端可能已改过布局）；rev 要等素材对账跑完才记账
                                applySceneConfigs(scenes)
                                // 死分支，保留仅作前向兼容：服务端 sendAuthResponse 只是把 topic 里的
                                // deviceId 原样回显，不会改派新 uuid；而上面的 targetId 校验已经保证
                                // "要么等于本机 id 要么不处理"，所以这里 newDeviceId != deviceId 恒不成立。
                                // 真要让服务端改派身份，得先加"订阅新 topic + 退订旧 topic"的成对逻辑再启用。
                                val newDeviceId = resp.optString("device_id", "")
                                if (newDeviceId.isNotEmpty() && newDeviceId != deviceId) {
                                    deviceId = newDeviceId
                                    prefs.edit().putString("device_id", deviceId).apply()
                                    mqttClientId = deviceId
                                    val newCommandTopic = "xvj/device/$deviceId/command"
                                    mqttClient?.subscribe(newCommandTopic, 1)
                                    Log.d(TAG, "授权后更新订阅: $newCommandTopic")
                                }
                            }
                            // debug 字段服务端 auth_result 一直在带（sendAuthResponse 取房间 config.debug），
                            // 但本机以前只在 sync_room_materials / set_debug 里读 → 首次授权那一场同步里 prefs
                            // 还是上次遗留值，进度条和日志上报会慢一整轮。这里提前落一次，让同场同步按房间真值显示。
                            // 只在载荷确实带该字段时才覆盖（老服务端不带 → 保留本机现值）。
                            if (resp.has("debug")) {
                                setDebugMode(resp.optBoolean("debug", false))
                            }
                            // 触发素材同步（映射真相在 scenes 里，folderMappings 仅作旧服务端兜底）；
                            // rev 随请求走，对账真的跑完才记进 appliedRev 给心跳对账用
                            if (folderMappings != null || scenes != null) {
                                Log.d(TAG, "授权成功: room_id=$roomId")
                                logToFile("开始根据房间配置同步素材...")
                                requestRoomMaterialSync(roomId, folderMappings ?: org.json.JSONObject(), scenes, rev)
                            }
                        } else {
                            prefs.edit().putBoolean("last_authorized", false).apply()
                            binding.statusText?.text = "设备未授权"
                            // 切换到欢迎视频（不停止当前播放）
                            showUnauthorizedAlert(message)
                        }
                    }
                }
                "deauthorize" -> {
                    // 被远程废掉
                    logToFile("本机被废止: $targetId ${resp.optString("message", "")}", "ERROR", "AUTH")
                    mqttHandler.post {
                        // 撤掉冷启动缓存的放行标志：下次开机不再拿这份已失效的画面抢先播
                        prefs.edit().putBoolean("last_authorized", false).apply()
                        binding.statusText?.text = "已废止"
                        showUnauthorizedAlert("设备已被远程废止，请联系管理员")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auth response parse error: ${e.message}")
            logToFile("Auth response 解析失败: ${e.message}")
        }
    }

    /**
     * 显示未授权提示并播放欢迎视频
     */
    private fun showUnauthorizedAlert(message: String) {
        // 直接切换到欢迎视频（不停止当前播放）
        playWelcomeVideo()
        Log.w(TAG, "UNAUTHORIZED: $message")
    }

    /**
     * 未授权/被废止时循环播放 res/raw/welcome：先 releasePlayer + releaseAllWindows 清掉窗口画面，
     * 再把单播放器 player 挂到 binding.playerView 上播 raw 资源。
     * 之后要等授权通过（auth_result 带 scenes）或下一次 sync_room_materials 重新 applySceneConfigs，
     * 画面才会回到窗口模式 —— 本函数自身不做"恢复窗口"这件事。
     */
    private fun playWelcomeVideo() {
        mqttHandler.post {
            try {
                // 先释放旧播放器
                releasePlayer()
                // 清除多窗口（含视图），避免欢迎视频被旧窗口遮挡
                releaseAllWindows()

                // 创建新播放器播放欢迎视频
                player = ExoPlayer.Builder(this@MainActivity).build().apply {
                    binding.playerView.player = this
                    // 使用 Android resource URI 访问 raw 资源
                    val uri = android.net.Uri.parse("android.resource://${packageName}/raw/welcome")
                    val mediaItem = MediaItem.fromUri(uri)
                    setMediaItems(listOf(mediaItem))
                    repeatMode = Player.REPEAT_MODE_ALL
                    playWhenReady = true
                    prepare()
                }
                Log.d(TAG, "开始播放欢迎视频")
            } catch (e: Exception) {
                Log.e(TAG, "播放欢迎视频失败: ${e.message}")
                logToFile("播放欢迎视频失败: ${e.message}")
            }
        }
    }

    // P4 fix: MQTT 重连加指数退避，上限 5 分钟，防止死循环
    private var mqttReconnectDelaySeconds = 5
    private val mqttMaxReconnectDelaySeconds = 300
    private var mqttReconnectAttempts = 0

    private fun reconnectMQTT() {
        val delayMs = (mqttReconnectDelaySeconds * 1000).toLong()
        mqttHandler.postDelayed({
            try { mqttClient?.close() } catch (e: Exception) {}
            connectMQTT()
            // P4 fix: checkForUpdate 移入 postDelayed 闭包内，等待重连窗口再执行
            checkForUpdate()
        }, delayMs)
    }

    // 连接成功时调用（由 MQTT callback 触发），重置退避计数器
    private fun onMqttConnected() {
        mqttReconnectDelaySeconds = 5
        mqttReconnectAttempts = 0
    }

    // 连接失败时调用，逐步增加退避延迟
    private fun onMqttReconnectFailed() {
        mqttReconnectAttempts++
        // 指数退避：5s → 10s → 20s → 40s → 80s → 160s → 300s（上限）
        mqttReconnectDelaySeconds = minOf(mqttReconnectDelaySeconds * 2, mqttMaxReconnectDelaySeconds)
        Log.d(TAG, "MQTT重连失败 #${mqttReconnectAttempts}，${mqttReconnectDelaySeconds}s 后重试")
    }

    // 【A-04】 MQTT 消息处理 & 分发
    // @tag: handleCommand 命令分发 mqtt命令处理

    /**
     * 处理 MQTT 命令（xvj/device/{deviceId}/command）
     * @param json 命令 JSON，必须含 action 字段
     * 实际支持的 action（与服务端发送方一一对应）：
     *   stop                停掉全部播放
     *   config              改 mqtt_server / loop（只影响单播放器 player，不影响窗口播放器）
     *   preset_sync         预设素材同步（folders 数组）→ syncPresetFolders
     *   sync_room_materials 房间素材全量对账（服务端【S-07】/授权链路发出）
     *   update_windows      轻量窗口重排，不下素材（编辑器 live 实时预览）
     *   delete_material     删单个本地素材文件
     *   update              OTA：带 url/version/md5 → downloadAndInstall
     *   set_debug           立即开关 debug_mode（前端没有入口，只能手工 POST /api/devices/:id/command；
     *                       正常途径是 sync_room_materials / auth_result 载荷里的 debug 字段）
     * ⚠️ 没有 "sync"：服务端的 action:'sync' 只是 HTTP 入参，publish 前已改写成 sync_room_materials
     */
    private fun handleCommand(json: String) {
        try {
            val cmd = JSONObject(json)
            val action = cmd.getString("action")
            logToFile("收到命令: $action", "INFO", "MQTT", "COMMAND")

            when (action) {
                "stop" -> {
                    // 停单播放器（欢迎视频那条链）+ 释放全部窗口播放器，画面真正黑下去。
                    // 发送方有两处：废止设备（另有 deauthorize 广播会切欢迎视频）、
                    // DELETE /api/rooms/:id 删房间（补上 releaseAllWindows 后才真的停得住）。
                    // 不清 windowContentSigs：下次 applySceneConfigs 自己清，
                    // 而 applyLiveWindowUpdate 发现 flSurface 已无子视图会判定为结构变化并全量重建。
                    stopPlayback()
                    releaseAllWindows()
                }
                "config" -> {
                    // 更新配置
                    if (cmd.has("mqtt_server")) {
                        mqttServer = cmd.getString("mqtt_server")
                        prefs.edit().putString("mqtt_server", mqttServer).apply()
                    }
                    if (cmd.has("loop")) {
                        loopPlay = cmd.getBoolean("loop")
                        player?.repeatMode = if (loopPlay) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                    }
                }
                "preset_sync" -> {
                    // 接收预设素材同步
                    val folders = cmd.optJSONArray("folders")
                    if (folders != null) {
                        syncPresetFolders(folders)
                    }
                }
                "sync_room_materials" -> {
                    // 接收房间素材同步，下载到本地文件夹
                    val roomId = cmd.optString("room_id", "")
                    val folderMappings = cmd.optJSONObject("folder_mappings")
                    // debug 只在命令确实带这个字段时才覆盖：服务端只在 sync_room_materials / auth_result /
                    // set_debug 三种载荷里带它（update_windows 就不带），用 optBoolean 会把"没带"当成 false，
                    // 手工推的指令漏了字段就会把设备调试模式误关掉
                    val hasDebug = cmd.has("debug")
                    val debug = hasDebug && cmd.optBoolean("debug", false)
                    if (hasDebug) setDebugMode(debug)
                    val fmKeys = java.lang.StringBuilder()
                    folderMappings?.keys()?.let { val k = it; while (k.hasNext()) { fmKeys.append(k.next()).append(",") } }
                    logToFile("sync_room_materials: roomId=$roomId, folderMappings=" + fmKeys.toString() + ", debug=$debug")

                    // 先把窗口配置摆上屏（不等素材下载完，改布局时现场要立刻看到），再进去重后的对账。
                    // 对账结束时还会 apply 一次，但那次带 force：真下载了东西才重建，否则按结构指纹跳过，
                    // 所以一条同步命令不再等于两轮全量重建。scenes_json 的落盘统一在同步轮里做。
                    val scenes = cmd.optJSONObject("scenes")
                    if (scenes != null) {
                        Log.d(TAG, "已收到 scenes: ${scenes.names()}")
                        // applySceneConfigs 含 addView/removeView 等 UI 操作，必须在主线程执行
                        mqttHandler.post { applySceneConfigs(scenes) }
                    } else {
                        // 无 scenes 时尝试从本地缓存恢复（离线场景）
                        val cached = prefs.getString("scenes_json", null)
                        if (cached != null) {
                            try {
                                mqttHandler.post { applySceneConfigs(org.json.JSONObject(cached)) }
                                Log.d(TAG, "从本地缓存恢复 scenes 成功")
                            } catch (e: Exception) {
                                Log.e(TAG, "从本地缓存恢复 scenes 失败: ${e.message}")
                                logToFile("从本地缓存恢复 scenes 失败: ${e.message}")
                            }
                        }
                    }

                    // 合并 Scene A + B，一次同步完成（映射真相在 scenes 里，folderMappings 为兜底）
                    if (folderMappings != null || scenes != null) {
                        val foldersStr = java.lang.StringBuilder()
                        val k = folderMappings?.keys()
                        k?.let { while (it.hasNext()) { foldersStr.append(it.next()).append(",") } }
                        logToFile("准备同步素材: roomId=$roomId, folders=" + foldersStr)
                        requestRoomMaterialSync(roomId, folderMappings ?: org.json.JSONObject(), scenes,
                            cmd.optString("rev", ""))
                    } else {
                        logToFile("folderMappings 与 scenes 均为 null，跳过素材同步")
                    }
                }
                "update_windows" -> {
                    // 实时窗口预览（云端编辑器拖动/调参的轻量推送）：
                    // 只更新现有视图的位置/透明度/亮度/变换，不动播放器不触发素材同步；
                    // 结构变化（增删窗口/内容/显隐/z序）内部自动回退全量重建
                    val scenes = cmd.optJSONObject("scenes")
                    if (scenes != null) {
                        prefs.edit().putString("scenes_json", scenes.toString()).apply()
                        val rev = cmd.optString("rev", "")
                        mqttHandler.post {
                            applyLiveWindowUpdate(scenes)
                            // 服务端只在"素材映射没变、只有窗口变了"时走这条通道，
                            // 所以几何应用完就等于配置已落地，rev 可以直接记账
                            markAppliedRev(rev)
                        }
                    } else {
                        logToFile("update_windows: scenes 为空，忽略", "WARN", "WINDOW", "CONFIG")
                    }
                }
                "delete_material" -> {
                    // 收到删除本地素材命令（前端删除素材时后端通过MQTT下发）
                    val materialId = cmd.optString("material_id", "")
                    val folder = cmd.optString("folder", "01")
                    val filename = cmd.optString("filename", "")
                    if (materialId.isNotEmpty() && filename.isNotEmpty()) {
                        logToFile("delete_material: id=$materialId, folder=$folder, file=$filename")
                        deleteMaterialFile(folder, filename, materialId)
                    }
                }

                "update" -> {
                    // 收到OTA更新推送
                    Log.w(TAG, "收到update命令!")
                    val url = cmd.optString("url", "")
                    val version = cmd.optString("version", "")
                    val serverCode = cmd.optInt("version_code", 0)
                    Log.w(TAG, "OTA: url=$url, version=$version, serverCode=$serverCode, localCode=$VERSION_CODE")

                    // 安全校验：版本号校验 + URL来源验证
                    if (url.isNotEmpty() && serverCode > VERSION_CODE) {
                        // 验证URL来自可信服务器（精确匹配IP，防止绕过）
                        if (!isTrustedApkUrl(url)) {
                            Log.w(TAG, "OTA: 拒绝不可信的APK URL: $url")
                            logToFile("OTA更新被拒绝：URL来源不明")
                            mqttHandler.post {
                                android.widget.Toast.makeText(this, "更新来源不明，已拒绝", android.widget.Toast.LENGTH_LONG).show()
                            }
                            // 验证失败，跳过
                            return
                        }

                        logToFile("收到OTA更新推送: $version")
                        // 用Handler在主线程显示Toast
                        mqttHandler.post {
                            try {
                                android.widget.Toast.makeText(this, "正在下载更新: $version", android.widget.Toast.LENGTH_LONG).show()
                            } catch(e: Exception) {}
                        }
                        // 下载并提示用户安装（云端命令现已携带 md5）
                        downloadAndInstall(url, version, cmd.optString("md5", null))
                    } else if (serverCode <= VERSION_CODE) {
                        Log.d(TAG, "OTA: 当前已是最新版本 ($VERSION_CODE >= $serverCode)")
                    }
                }
                "set_debug" -> {
                    // 手动开关，和 auth_result / sync_room_materials 的 debug 字段同走 setDebugMode。
                    // 它是临时值：下一次载荷会按房间配置覆盖（房间 config.debug 才是真相）。
                    val debug = cmd.optBoolean("debug", false)
                    setDebugMode(debug)
                    Log.d(TAG, "set_debug: debug=$debug")
                    logToFile("调试模式变更: debug=$debug")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command parse error: ${e.message}")
        }
    }

    /**
     * 只把 folders（[{id,name}]）清洗后写进 SharedPreferences 的 preset_folders 键，
     * 不建目录、不下任何素材文件 —— 名字里的"同步"是"同步一份文件夹清单"，容易误解。
     * ⚠️ 整条 preset_sync 链路都是停用的：服务端从未发送 action:'preset_sync'，前端没有入口，
     *    写进去的 preset_folders 键也没有任何地方读它。真正在跑的素材下发是 sync_room_materials。
     */
    private fun syncPresetFolders(folders: org.json.JSONArray) {
        mqttHandler.post {
            binding.statusText?.text = "同步预设素材(${folders.length()}个文件夹)..."
        }

        downloadExecutor.submit {
            try {
                val folderList = mutableListOf<Pair<String, String>>() // folderId to folderName

                for (i in 0 until folders.length()) {
                    try {
                        val item = folders.get(i)
                        if (item is org.json.JSONObject) {
                            val id = item.optString("id", "").take(20)
                            val name = item.optString("name", "").take(100)
                            // 过滤特殊字符
                            val safeId = id.replace(Regex("[^a-zA-Z0-9_-]"), "")
                            val safeName = name.replace(Regex("[^\\w\\s-]"), "")
                            if (safeId.isNotEmpty()) {
                                folderList.add(Pair(safeId, safeName))
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping invalid folder at index $i: ${e.message}")
                    }
                }

                // 保存文件夹配置到本地
                val prefsEditor = prefs.edit()
                val folderJson = org.json.JSONObject()
                folderList.forEach { (id, name) ->
                    folderJson.put(id, name)
                }
                prefsEditor.putString("preset_folders", folderJson.toString())
                prefsEditor.commit() // 同步写入，确保保存成功

                Log.d(TAG, "Preset folders saved: $folderList")

                mqttHandler.post {
                    binding.statusText?.text = "预设素材同步完成"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Preset sync error: ${e.message}")
                mqttHandler.post {
                    binding.statusText?.text = "预设素材同步失败"
                }
            }
        }
    }

    // 【A-06】 素材同步
    // @tag: requestRoomMaterialSync 素材同步 房间同步 对账 去重
    // @tag: syncFolderWithIds 文件夹同步 下载同步
    // @tag: deleteMaterialFile 删除素材 文件删除
    // @tag: runDownloadPlan 下载进度条 进度
    /**
     * 一轮同步里"确定要下载"的单个文件。
     * 把"比对"和"下载"分成两段（计划 → 执行），是因为边比对边下载时分母未知，进度条只能按文件数粗算；
     * 先凑齐 A/B 两幕的整批任务，才能按云端下发的字节数算出真实百分比。
     * @param size 云端 room-materials-v2 现算的磁盘字节数；取不到时为 0，执行阶段按已知任务的中位数折算
     */
    private data class SyncTask(
        val folderId: String,
        val destFile: File,
        val url: String,
        val filename: String,
        val size: Long
    )

    /**
     * 素材同步的唯一入口，按房间做在飞去重：
     * 该房间已有一轮对账在跑时，这次请求只被记成"最后待补跑的那一份"（同房间互相覆盖，中间态没有意义），
     * 当前轮跑完立刻补一轮。并发的两轮会抢同一批文件、进度条来回刷屏，而且后一轮拿的清单更新，
     * 先一轮的下载纯属浪费。服务端对 {id, fingerprint} 去重双发、心跳对账补发，都会打在这里。
     * @param rev 服务端这次的配置指纹（roomSceneDigest），对账跑完才记进 appliedRev 供心跳回传
     */
    private fun requestRoomMaterialSync(
        roomId: String,
        folderMappingsFallback: org.json.JSONObject,
        scenes: org.json.JSONObject?,
        rev: String
    ) {
        val req = SyncRequest(roomId, folderMappingsFallback, scenes, rev)
        var start = false
        synchronized(syncLock) {
            start = syncInFlight.add(roomId)   // add 返回 false == 这个房间已经有一轮在飞
            if (!start) syncPending[roomId] = req
        }
        if (!start) {
            logToFile("房间 $roomId 同步已在飞，本次指令并入待补跑 (rev=$rev)", "INFO", "SYNC", "SKIP")
            return
        }
        mqttHandler.post { binding.statusText?.text = "同步房间素材中..." }
        downloadExecutor.submit { runRoomSyncLoop(req) }
    }

    /** 工作线程：跑一轮 → 看有没有并进来的后指令 → 有就再跑一轮，直到追平（同一时刻每房间只有一个循环在飞） */
    private fun runRoomSyncLoop(first: SyncRequest) {
        var round = first
        while (true) {
            doRoomMaterialSync(round)
            var next: SyncRequest? = null
            synchronized(syncLock) {
                val n = syncPending.remove(round.roomId)
                // rev 已经应用过了 ⇒ 这条是重复触发，不必再跑；rev 为空的（手工指令/老服务端）无从判断，照常跑。
                // appliedRev 由主线程写、这里读，最坏是多补一轮，不会漏。
                if (n != null && (n.rev.isEmpty() || n.rev != appliedRev)) next = n
                if (next == null) syncInFlight.remove(round.roomId)
            }
            val n = next ?: return
            logToFile("对账期间又收到同步指令 (rev=${n.rev})，立即补跑一轮", "INFO", "SYNC", "SKIP")
            round = n
        }
    }

    /**
     * 一轮房间素材对账（一次 HTTP + A/B 两幕逐目录 diff）：
     * 1) 载荷带 scenes 才落 prefs 缓存（null 是"这次没带"，不是"房间没配置"，写成空串会清掉冷启动缓存）；
     *    房间 id 本机不再缓存（原 current_room_id 只写不读，已删）。
     * 2) fetchRoomMaterials 拉 /api/room-materials-v2/{roomId} 的 A01/B01 全量清单；
     *    返回 null（请求失败）时直接中止本轮，绝不进"清空"分支，rev 也不记账（让心跳对账再补一次）。
     * 3) syncSceneFolders("A"/"B") 按 scenes.A/B.folder_mappings 对账：映射有变的下、多的删，
     *    只**收集**待下载任务不真下载；映射真相只在 scenes 里，
     *    folderMappingsFallback 仅给旧服务端兜底，B 幕缺失时按空映射处理。
     * 4) runDownloadPlan 按字节进度逐个下载（调试模式下底部进度条可见），串行、单线程。
     * 5) 回主线程 applySceneConfigs：优先用本次载荷的 scenes，没有才回落 prefs 缓存；
     *    真下载过东西才 force 重建，纯对账交给结构指纹判重，同配置不会打断正在播的视频。
     */
    private fun doRoomMaterialSync(req: SyncRequest) {
        val roomId = req.roomId
        val scenes = req.scenes
        try {
            if (scenes != null) {
                prefs.edit().putString("scenes_json", scenes.toString()).apply()
            }

            val fmA = scenes?.optJSONObject("A")?.optJSONObject("folder_mappings") ?: req.folderMappingsFallback
            val fmB = scenes?.optJSONObject("B")?.optJSONObject("folder_mappings") ?: org.json.JSONObject()

            // 拉取失败返回 null：保持本地现状中止本轮，绝不进入清空分支
            val allMaterials = fetchRoomMaterials(roomId)
            if (allMaterials == null) {
                hideSyncProgress()   // fetchRoomMaterials 内部已把 statusText 改成失败文案，这里只收条
                return
            }

            val plan = ArrayList<SyncTask>()
            plan.addAll(syncSceneFolders("A", fmA, allMaterials))
            plan.addAll(syncSceneFolders("B", fmB, allMaterials))
            runDownloadPlan(plan)
            hideSyncProgress("素材同步完成")

            val downloaded = plan.isNotEmpty()
            mqttHandler.post {
                val scenesToApply = scenes ?: prefs.getString("scenes_json", null)?.let {
                    try { org.json.JSONObject(it) } catch (e: Exception) { null }
                }
                if (scenesToApply != null) {
                    try {
                        applySceneConfigs(scenesToApply, force = downloaded)
                    } catch (e: Exception) {
                        Log.e(TAG, "applySceneConfigs 失败: ${e.message}")
                    }
                }
                // 走到这里配置才真的落地了：素材齐、窗口已按新配置重建，可以给心跳对账报这个 rev
                markAppliedRev(req.rev)
            }
            logToFile("房间素材同步完成: " + roomId)
        } catch (e: Exception) {
            Log.e(TAG, "Room materials sync error: " + e.message)
            logToFile("房间素材同步异常: ${e.message}", "ERROR", "SYNC", "ERROR")
            hideSyncProgress("素材同步失败")
        }
    }

    /**
     * 执行下载计划：串行下载 + 按字节刷新进度（进度只在 debug_mode 下上屏，见 updateSyncProgress）。
     *
     * 分母口径：云端每条素材带 size（服务端 statSync 现算）。size 全为 0（老服务端 / 文件已删）时
     * 回落到"按文件数"计——每个任务按 1 字节算，进度仍是单调的。部分有 size 时，0 大小的任务
     * 按已知任务的中位数折算（不用平均数：一个 2GB 素材会把平均数撑爆、让其余小文件瞬间跑完）。
     *
     * 计划阶段（syncFolderWithIds）已经把 md5 一致的、304 命中的都尽量剔掉了，剩下的是真要下载的；
     * 但 downloadWithETag 仍可能返回 false（文件名 ETag 未变 → 304，见 syncFolderWithIds 的判定顺序），
     * 此时按"整只文件大小"计入已完成字节，否则进度条会卡在最后几个文件上下不来。
     */
    private fun runDownloadPlan(tasks: List<SyncTask>) {
        if (tasks.isEmpty()) {
            // 本地已齐：不显示进度条，并把上一轮可能留下的 100% 细条收掉
            hideSyncProgress()
            logToFile("素材对账完成: 无需下载（映射内素材本地已齐）")
            return
        }

        val known = tasks.map { it.size }.filter { it > 0L }.sorted()
        val fallback = if (known.isEmpty()) 1L else known[known.size / 2]
        val weights = tasks.map { if (it.size > 0L) it.size else fallback }
        val total = weights.fold(0L) { a, b -> a + b }

        // 起头显一次：0% 也要让现场看到"开始同步了"（中位数折算完才知道总字节数）
        updateSyncProgress(0L, total, tasks.size, 0, "准备中")

        var done = 0L
        for (idx in tasks.indices) {
            val t = tasks[idx]
            val w = weights[idx]
            logToFile("准备下载: ${t.folderId}/${t.filename} (${w / 1024}KB)")
            val progressCb: (Long) -> Unit = { downloadedBytes ->
                updateSyncProgress(done + downloadedBytes, total, tasks.size, idx, t.filename)
            }
            downloadWithETag(t.url, t.destFile, t.filename, t.size, progressCb)
            done += w
            updateSyncProgress(done, total, tasks.size, idx, t.filename)
        }
    }

    /**
     * 下载进度的上屏出口（显 + 百分比 + 文案）。
     *
     * 可见范围 = 仅房间调试模式（prefs.debug_mode）：设备接投影/大屏时观众看得到画面，
     * 常态播放页不该冒出进度条；非调试模式直接 return，只留 logToFile。
     * 整层运维 UI 的显隐归 applyDebugUi() 管，这里这次判空只是让下载线程不必多绕一趟主线程。
     *
     * 线程：只在 downloadExecutor 线程被调用（开环 0%、256KB 字节回调、每个文件收尾各一次），
     * 百分比在调用线程算好后连文案一起 post 给 mqttHandler —— 不留任何共享可变状态，
     * 理论上两轮同步并发时最坏只是显示上互相覆盖一下，不会算错或崩。
     *
     * bringToFront 是必需的：窗口容器是 applySceneConfigs 之后 addView 到同一个根 FrameLayout 的，
     * 会盖住 XML 里靠后的 statusText / syncProgressBar。
     */
    private fun updateSyncProgress(doneBytes: Long, totalBytes: Long, totalFiles: Int, taskIndex: Int, filename: String) {
        if (!prefs.getBoolean("debug_mode", false)) return
        val done = doneBytes.coerceAtMost(totalBytes)
        val percent = if (totalBytes <= 0L) 100 else ((done * 100) / totalBytes).toInt().coerceIn(0, 100)
        val text = "同步素材 ${taskIndex + 1}/$totalFiles · $percent% · $filename"
        mqttHandler.post {
            binding.syncProgressBar?.let { bar ->
                bar.visibility = View.VISIBLE
                bar.progress = percent
                bar.bringToFront()
            }
            binding.statusText?.let { tv ->
                tv.text = text
                tv.bringToFront()
            }
        }
    }

    /**
     * 收掉进度条（可选同时改写左下角状态文字）。
     * syncProgressBar 只有一处显（updateSyncProgress）和两处隐（本函数、applyDebugUi 关调试模式），
     * 别处不要再直接碰它。
     * 不带 status 时只收条不动文字（失败文案已由 fetchRoomMaterials 等处自己写好）。
     */
    private fun hideSyncProgress(status: String? = null) {
        mqttHandler.post {
            binding.syncProgressBar?.visibility = View.GONE
            if (status != null) binding.statusText?.text = status
        }
    }

    /**
     * 拉取房间素材清单（/api/room-materials-v2，A01/B01 键）
     * 失败返回 null——与「清单为空」严格区分：只有成功拉取的空清单才允许触发清空
     */
    private fun fetchRoomMaterials(roomId: String): MutableMap<String, org.json.JSONArray>? {
        return try {
            val apiUrl = java.net.URL(APK_URL + "/api/room-materials-v2/" + roomId)
            logToFile("HTTP 请求: $apiUrl")
            val connection = apiUrl.openConnection()
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            val response = connection.inputStream.bufferedReader().readText()
            logToFile("HTTP 响应长度: ${response.length}, 前100字符: ${response.take(100)}")
            val resultJson = org.json.JSONObject(response)
            val allMaterials = mutableMapOf<String, org.json.JSONArray>()
            val keys = resultJson.keys()
            while (keys.hasNext()) {
                val folderId = keys.next()
                if (folderId != "debug") {
                    allMaterials[folderId] = resultJson.getJSONArray(folderId)
                }
            }
            logToFile("获取房间素材成功: " + allMaterials.size + " 个文件夹")
            allMaterials
        } catch (e: Exception) {
            Log.e(TAG, "获取房间素材失败: " + e.message)
            logToFile("获取房间素材失败，中止本轮同步保持本地现状: " + e.message, "ERROR", "SYNC", "ERROR")
            mqttHandler.post { binding.statusText?.text = "素材同步失败（云端不可达）" }
            null
        }
    }

    /**
     * 对账单幕 30 个文件夹：映射有素材 → 比对出待下载任务、删除多余；未映射或空 → 清空本地目录
     * @return 本幕所有文件夹凑出的下载计划（不在此处下载，交给 runDownloadPlan 统一跑进度）
     */
    private fun syncSceneFolders(scenePrefix: String, folderMappings: org.json.JSONObject, allMaterials: MutableMap<String, org.json.JSONArray>): List<SyncTask> {
        val tasks = ArrayList<SyncTask>()
        for (i in 1..30) {
            val folderNum = String.format("%02d", i)
            val prefixedKey = scenePrefix + folderNum  // "A01", "B02"
            // 键格式兼容：统一带前缀（"A01"）；纯编号（"01"）为旧数据兜底
            val materialIds = folderMappings.optJSONArray(prefixedKey) ?: folderMappings.optJSONArray(folderNum)
            if (materialIds != null && materialIds.length() > 0) {
                tasks.addAll(syncFolderWithIds(prefixedKey, materialIds, allMaterials[prefixedKey]))
            } else {
                deleteFolderFiles(prefixedKey)
            }
        }
        return tasks
    }

    /**
     * 按 material IDs 精确比对单个文件夹 —— **只做计划，不下载**
     * @param folderId   文件夹 ID（支持 scene-prefixed 格式，如 "A01"）
     * @param materialIds 要同步的素材 ID 数组
     * @param cloudList  预获取的云端素材列表（可避免重复请求）
     * @return 该文件夹需要下载的任务（由调用方攒齐后交给 runDownloadPlan，进度条才知道总字节数）
     * 流程：比对本地与云端素材，缺的/变的进计划，多的删掉
     * 判定顺序（这决定了"为什么改了文件设备却不更新"）：
     *   1. 本地存在该文件 && 云端 md5 非空 && 本地 md5 相等 → 跳过，连请求都不发
     *   2. 其余情况一律进计划，由 downloadWithETag 先看按文件名存的 ETag，
     *      命中 304 就直接 return false —— 也就是"云端 md5 变了但 ETag 没变"会被静默吃掉，
     *      本地保持旧文件、下一轮同步再重复报不一致。云端就地替换素材字节时必须让 ETag 一起变。
     *   3. 云端 md5 为空（老记录/预设引用）时只靠 ETag 去重，这是有意为之的兜底路径
     */
    private fun syncFolderWithIds(folderId: String, materialIds: org.json.JSONArray, cloudList: org.json.JSONArray?): List<SyncTask> {
        val tasks = ArrayList<SyncTask>()
        try {
            if (cloudList == null || cloudList.length() == 0) {
                Log.d(TAG, "文件夹 " + folderId + " 无云端素材")
                deleteFolderFiles(folderId)
                return tasks
            }

            val idsSet = mutableSetOf<String>()
            for (i in 0 until materialIds.length()) {
                idsSet.add(materialIds.getString(i))
            }

            // 解析 scene-prefixed folder ID（"A01" → scene="a", num="01"）
            val scenePrefixChar = if (folderId.length == 3 && folderId[0].isLetter()) folderId[0] else null
            val folderNum = scenePrefixChar?.let { folderId.removePrefix(it.toString()) } ?: folderId
            val physicalFolder = if (scenePrefixChar != null) {
                File(File(videoFolderPath, "scene" + scenePrefixChar.lowercaseChar()), folderNum)
            } else {
                File(videoFolderPath, folderNum)
            }
            val localFolder = physicalFolder
            if (!localFolder.exists()) localFolder.mkdirs()

            val localFiles = localFolder.listFiles()?.filter {
                it.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "webm")
            } ?: emptyList()

            val shouldExist = mutableSetOf<String>()

            for (i in 0 until cloudList.length()) {
                val item = cloudList.getJSONObject(i)
                val cloudId = item.optString("id", "")
                if (cloudId.isNotEmpty() && idsSet.contains(cloudId)) {
                    val filename = item.getString("filename")
                    val urlPath = item.optString("url", "")
                    shouldExist.add(filename)
                    val localFile = File(localFolder, filename)
                    val downloadUrl = if (urlPath.startsWith("http")) urlPath else APK_URL + urlPath

                    val md5 = item.optString("md5", "")
                    if (localFile.exists() && md5.isNotEmpty()) {
                        val localMd5 = cachedMd5(localFile)
                        if (localMd5 == md5) {
                            Log.d(TAG, "文件已存在且MD5一致: " + filename)
                            continue
                        }
                    }
                    Log.d(TAG, "计划下载: " + filename)
                    // size 由云端 statSync 现算（老服务端没这个字段时 optLong 给 0，执行阶段按中位数折算）
                    tasks.add(SyncTask(folderId, localFile, downloadUrl, filename, item.optLong("size", 0L)))
                }
            }

            for (file in localFiles) {
                if (!shouldExist.contains(file.name)) {
                    Log.d(TAG, "删除不在清单中的文件: " + file.name)
                    logToFile("删除: " + file.name)
                    // 与 deleteMaterialFile/deleteFolderFiles 同一套约定：删本地文件必须一起清缓存，
                    // 否则该素材日后重新加回房间时，downloadWithETag 会拿旧 ETag 换到 304 而永远不重下。
                    clearMaterialCache(file.name)
                    file.delete()
                }
            }
            Log.d(TAG, "文件夹 " + folderId + " 对账完成: " + tasks.size + " 个待下载")
        } catch (e: Exception) {
            Log.e(TAG, "syncFolderWithIds " + folderId + " 失败: " + e.message)
            logToFile("同步文件夹" + folderId + " 失败: " + e.message)
        }
        return tasks
    }

    /**
     * 删除单个本地素材（由 MQTT action:'delete_material' 触发）。
     * 定位方式是 <素材根>/<folderId 解析出的目录>/<filename>，materialId 只进日志不参与查找；
     * ETag/LM 缓存键用的是文件名，所以删文件前必须先 remove 这两个键（见下面的约定）。
     */
    private fun deleteMaterialFile(folderId: String, filename: String, materialId: String) {
        try {
            // 支持场景前缀（"A01" → scenea/01），与服务端 delete_material 下发格式对齐
            val prefixChar = if (folderId.length == 3 && folderId[0].isLetter()) folderId[0].lowercaseChar() else null
            val localFolder = if (prefixChar != null) {
                File(File(videoFolderPath, "scene" + prefixChar), folderId.substring(1))
            } else {
                File(videoFolderPath, folderId)
            }
            if (!localFolder.exists()) {
                Log.d(TAG, "deleteMaterialFile: folder $folderId not exist")
                return
            }
            val file = File(localFolder, filename)
            if (file.exists()) {
                // 清除 ETag/Last-Modified/md5 缓存，避免删后重加时 APK 因 304 跳过下载
                clearMaterialCache(filename)
                val deleted = file.delete()
                Log.d(TAG, "deleteMaterialFile deleted=$deleted folder=$folderId file=$filename")
                logToFile("删除素材文件: folder=$folderId file=$filename deleted=$deleted")
            } else {
                Log.d(TAG, "deleteMaterialFile: file not found folder=$folderId file=$filename")
                logToFile("删除素材文件失败（文件不存在）: folder=$folderId file=$filename")
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteMaterialFile 失败: " + e.message)
        }
    }

    /**
     * 清空指定文件夹下的所有视频文件
     * @param folderId 文件夹 ID（支持 scene-prefixed 格式）
     * 同时清除本地缓存的 ETag/Last-Modified，避免删文件后重加时 APK 因 304 跳过下载
     */
    private fun deleteFolderFiles(folderId: String) {
        try {
            // 与 syncFolderWithIds 落盘目录一致：A01 → scenea/01，纯编号 → 根目录
            val prefixChar = if (folderId.length == 3 && folderId[0].isLetter()) folderId[0].lowercaseChar() else null
            val localFolder = if (prefixChar != null) {
                File(File(videoFolderPath, "scene" + prefixChar), folderId.substring(1))
            } else {
                File(videoFolderPath, folderId)
            }
            if (!localFolder.exists()) return
            val files = localFolder.listFiles()?.filter {
                it.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "webm")
            } ?: emptyList()
            for (file in files) {
                Log.d(TAG, "清空文件夹" + folderId + "，删除: " + file.name)
                logToFile("清空删除: " + file.name)
                // 清除缓存的 ETag/Last-Modified/md5，避免删文件后重加时 APK 因 304 跳过下载
                clearMaterialCache(file.name)
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteFolderFiles " + folderId + " 失败: " + e.message)
        }
    }

    // 【A-10】 系统 UI
    // 曾有过一个 checkStoragePermission()：manifest 从未声明 WRITE/MANAGE_EXTERNAL_STORAGE，
    // 而素材/日志全写在 filesDir 内部存储（本就不需要权限），它既不申请到任何东西又会在
    // Android 11+ 拉起系统"所有文件访问"设置页 —— 已整段删除，全链路无外部存储路径依赖。
    /**
     * 隐藏系统 UI，实现全屏沉浸式播放
     * 设置 IMMERSIVE_STICKY 标志，隐藏导航栏和状态栏
     */
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
        }
    }

    // 【A-01】 生命周期回调 & 单播放器（onCreate 在文件靠前处）
    // player 是"单播放器"残留链路：只有未授权时的欢迎视频（showUnauthorizedAlert → playWelcomeVideo）
    // 和 config 命令的 loop 开关会碰它；窗口播放全部走 windowPlayers，两套播放器互不影响。

    /** 停掉单播放器并释放；不清 statusText 以外的状态，也不动窗口播放器 */
    private fun stopPlayback() {
        Log.d(TAG, "stopPlayback called")
        releasePlayer()
        binding.statusText?.text = "播放已停止"
    }

    /** 释放单播放器并从 playerView 上摘下来（可重复调用） */
    private fun releasePlayer() {
        Log.d(TAG, "releasePlayer called, current player: $player")
        player?.release()
        player = null
        binding.playerView.player = null
    }

    /**
     * 回前台：重新隐藏系统 UI + 恢复单播放器播放 + 续装挂起的 OTA 包。
     * pendingInstallApk 是 installApk 跳"安装未知应用"授权页前存下的：授权回来立即继续安装；
     * 没授权就置空放弃、不再挂起（遗留问题 #42：MIUI 授权页秒退时会走到这里，续装直接丢失）。
     */
    override fun onResume() {
        super.onResume()
        hideSystemUI()
        player?.play()
        // 从"安装未知应用"授权页返回：复查权限后续装挂起的 APK（见 retryPendingInstall）
        if (pendingInstallApk != null) {
            installRetryHandler.removeCallbacksAndMessages(null)
            retryPendingInstall(0)
        }
    }

    /**
     * 退后台只暂停单播放器：窗口播放器（windowPlayers）不暂停，
     * 所以弹出的系统页面消失后画面是连续的，但也意味着被切走时窗口仍在解码占资源。
     */
    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    /** 真销毁时才走：释放全部窗口 + 单播放器，停状态上报定时器，断开 MQTT，关闭下载线程池 */
    override fun onDestroy() {
        super.onDestroy()
        // 撤销挂起的续装轮询（retryPendingInstall），Activity 没了再复查只会崩
        installRetryHandler.removeCallbacksAndMessages(null)
        releaseAllWindows()
        releasePlayer()
        // 停止定时器并发送离线状态
        stopStatusTimer()
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) {}
        downloadExecutor.shutdown()
    }

    /** 终端模式：吃掉返回键，不允许退出 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 禁用返回键 - 终端模式不允许退出
    }

    /** 用户按 Home / 手势离开时，用启动 Intent 把自己重新拉回前台（kiosk 防退出） */
    override fun onUserLeaveHint() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * 预建素材目录：根下 01..30，外加 scenea/01..30 与 sceneb/01..30（三套各 30 个，
     *   不是旧注释写的"20 个 (01-20)"；scene 目录名统一小写）
     * 三种形态各自对应一条解析路径：无前缀 "01" → 根目录、"A01" → scenea/01、"B01" → sceneb/01
     * 只是 mkdirs 占位，好让播放器直接按 A01→scenea/01 找目录时不撞空路径；
     * 素材真正的增删由 syncFolderWithIds 负责，两者对目录的处理是幂等的。
     */
    private fun createMaterialFolders() {
        try {
            val baseDir = File(videoFolderPath)
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            for (i in 1..30) {
                val folderName = String.format("%02d", i)
                val folder = File(baseDir, folderName)
                if (!folder.exists()) {
                    folder.mkdirs()
                    Log.d(TAG, "Created folder: ${folder.absolutePath}")
                }
            }
            // 创建 sceneA/ 和 sceneB/ 子目录树（01-30）
            for (scene in listOf("scenea", "sceneb")) {
                val sceneDir = File(baseDir, scene)
                if (!sceneDir.exists()) sceneDir.mkdirs()
                for (i in 1..30) {
                    val folderName = String.format("%02d", i)
                    val folder = File(sceneDir, folderName)
                    if (!folder.exists()) {
                        folder.mkdirs()
                        Log.d(TAG, "Created scene folder: ${folder.absolutePath}")
                    }
                }
            }
            Log.d(TAG, "Material folders initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create material folders: ${e.message}")
        }
    }

    /**
     * 带 ETag/Last-Modified 条件请求的文件下载
     * @param filename     缓存键用的文件名（etag_/lm_ 前缀 + 它）；调用方传的正是 destFile.name
     * @param expectedSize 云端下发的完整字节数，只用来收尾自检；0 = 未知（老服务端/取不到）
     * @param onProgress   本次写入的字节数回调，每 ≥256KB 一次；null = 不回报
     * @return true = 实际写了文件；false = 服务端说没变，本地原样保留
     *
     * 一次调用只发 1 个条件 GET：304 就原样保留本地文件返回 false，2xx 才落盘。历史版本在这里叠了
     * 最多 3 个 HEAD + 1 个 GET，而每个 HEAD 的判定（带 If-None-Match，304 则跳过）都与最后那个条件
     * GET 完全重复，已合并。
     *
     * 两条和进度条直接相关的规矩：
     *   - ETag/Last-Modified 在**流写完之后**才写回 prefs，且实到字节与云端 size 不符时不写。
     *     早先版本收到响应头就写，于是"下到一半断线"会留下【半截文件 + 已生效的 ETag】，
     *     下一轮条件 GET 拿回 304，那个坏文件永远修不好（进度条也会每次都一秒跳完、素材却播不出来）。
     *   - 不做 Range 断点续传：同名素材在云端是"就地替换"的（faststart 重封装就是同名改字节），
     *     续传会把新字节拼在旧前缀后面变成坏文件。宁可下一轮从 0 重下。
     *
     * 注意：md5 不一致但 ETag 没变时，这里会返回 false 而不下载 —— 语义见 syncFolderWithIds 的判定顺序。
     */
    private fun downloadWithETag(
        urlStr: String,
        destFile: File,
        filename: String,
        expectedSize: Long = 0L,
        onProgress: ((Long) -> Unit)? = null
    ): Boolean {
        val cachedEtag = prefs.getString(PREF_ETAG_PREFIX + filename, null)
        val cachedLm = prefs.getString(PREF_LM_PREFIX + filename, null)
        // 本地文件不在/为空 ⇒ 缓存的 ETag 一律不作数（带了会被 nginx 判成 304，旧文件永远下不回来）
        val force = !destFile.exists() || destFile.length() == 0L
        if (force) Log.d(TAG, "本地文件不存在或为空，强制下载: ${destFile.name}")

        return try {
            val url = java.net.URL(urlStr)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            if (!force) {
                if (cachedEtag != null) conn.setRequestProperty("If-None-Match", cachedEtag)
                if (cachedLm != null) conn.setRequestProperty("If-Modified-Since", cachedLm)
            }

            val realCode = conn.responseCode
            if (realCode == 304) {
                Log.d(TAG, "文件未变化跳过: $filename")
                // 上报一条：否则现场只看得到"准备下载"却没有"下载完成"，无法区分是被跳过还是下坏了
                logToFile("跳过下载(ETag未变): $filename", "INFO", "SYNC", "SKIP")
                conn.disconnect()
                return false
            }

            val newEtag = conn.getHeaderField("ETag")
            val newLm = conn.getHeaderField("Last-Modified")

            var written = 0L
            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    var lastReport = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        val cb = onProgress
                        if (cb != null) {
                            written += bytesRead
                            // 256KB 一节流：5MB 级素材有足够刷新点，又不会把主线程刷爆
                            if (written - lastReport >= 262144L) {
                                lastReport = written
                                cb(written)
                            }
                        }
                    }
                }
            }
            conn.disconnect()

            if (expectedSize > 0L && destFile.length() != expectedSize) {
                // 只告警、不存 ETag、不清理：下一轮因拿不到新 ETag 会重新完整下载
                logToFile("下载字节数与云端 size 不符: $filename 实到${destFile.length()} 期望$expectedSize",
                    "WARN", "SYNC", "DOWNLOAD")
            } else {
                // 写完了、字节数也对得上，才认这个 ETag/Last-Modified（见上面"两条规矩"）
                if (newEtag != null) prefs.edit().putString(PREF_ETAG_PREFIX + filename, newEtag).apply()
                if (newLm != null) prefs.edit().putString(PREF_LM_PREFIX + filename, newLm).apply()
            }
            Log.d(TAG, "下载完成: $filename")
            logToFile("下载完成: $filename (${destFile.length() / 1024}KB)", "INFO", "SYNC", "DOWNLOAD")
            true
        } catch (e: Exception) {
            Log.e(TAG, "下载失败 [${filename}]: ${e.message}")
            logToFile("下载失败 [$filename]: ${e.message}", "ERROR", "SYNC", "DOWNLOAD")
            false
        }
    }

    // 计算文件MD5
    // P3 fix: 流式 MD5 计算，防止大文件 OOM
    private fun calculateMd5(file: File): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "MD5计算失败: ${e.message}")
            ""
        }
    }

    /**
     * 带缓存的素材 md5：一轮对账要把本地每个文件整只读一遍算摘要，几十 GB 的素材目录就是几十 GB 磁盘读，
     * 而服务端每 30s/每次改动都可能推一轮同步 —— 绝大多数轮次里文件根本没动过。
     * 缓存键值本身带 "<字节数>:<mtime>:" 前缀，文件被替换（下载完成、重新落盘）后前缀对不上自动失效，
     * 所以只需要在**删除**文件时跟着 remove（与 ETag/LM 同一套约定，避免同名文件复活后串味）。
     * 只在下载线程调用，无并发；prefs 自身线程安全。
     */
    private fun cachedMd5(file: File): String {
        val stamp = file.length().toString() + ":" + file.lastModified().toString()
        val key = PREF_MD5_PREFIX + file.name
        prefs.getString(key, null)?.let { saved ->
            if (saved.startsWith("$stamp:")) return saved.substring(stamp.length + 1)
        }
        val md5 = calculateMd5(file)
        if (md5.isNotEmpty()) prefs.edit().putString(key, "$stamp:$md5").apply()
        return md5
    }

    /** 素材文件从本机消失时清掉它的三项缓存（ETag / Last-Modified / md5），三处删除点共用 */
    private fun clearMaterialCache(filename: String) {
        prefs.edit()
            .remove(PREF_ETAG_PREFIX + filename)
            .remove(PREF_LM_PREFIX + filename)
            .remove(PREF_MD5_PREFIX + filename)
            .apply()
    }
    

    // 【A-09】 OTA 自更新
    // ==================== OTA 自更新 ====================
    // 两条入口，最后都汇到 downloadAndInstall()：
    //   1) 设备自查：checkForUpdate()，启动/每次连上 MQTT/重连时各调度一次，拉 /api/version/latest
    //   2) 云端推送：handleCommand 的 action:'update'，命令体自带完整 url + version + version_code + md5
    // downloadAndInstall 的门禁顺序：
    //   isTrustedApkUrl（IP 精确匹配）→ 下载 → 体积 >1MB → md5（云端给了才校）→ 签名证书指纹 → 用户确认 → installApk
    // 安装不是静默的：走 FileProvider + 系统安装器，缺"安装未知应用"权限时先跳授权页，回来由 onResume 续装。

    /**
     * 校验 APK 下载地址是否来自可信服务器。
     * 只认硬编码公网 IP，主机名全等才算可信（只看 URI.host，路径/端口不参与判定）；
     * 换成域名或内网部署时，这里要和服务端 APK_DOWNLOAD_BASE 一起改。
     */
    private fun isTrustedApkUrl(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return false
            // 精确匹配IP
            host == "47.102.106.237" || host == "47.102.106.237."
        } catch (e: Exception) {
            false
        }
    }

    /**
     * filepath 安全性校验（防路径穿越）：只允许 .apk 落地路径 + 字母数字 _ . / -，禁 ".." 和 http(s) 前缀。
     *
     * 服务端 /api/version/latest 返回的 filepath 就是 "/apk/xxx.apk" 这种根路径形式，
     * 所以单个前导 "/" 必须放行（曾经的旧规则一律拒绝，导致 checkForUpdate 自查路径恒不可达、
     * 线上 OTA 只剩云端推送 action:'update' 一条腿）。
     * 仍然拒绝 "//host/path"：那是协议相对 URL，拼进 "$APK_URL$filepath" 后 host 会被换掉，
     * 绕过 isTrustedApkUrl 的精确 IP 判定。
     */
    private fun isValidFilepath(filepath: String): Boolean {
        // 拒绝协议相对路径（可把请求指向任意主机）
        if (filepath.startsWith("//")) return false
        // 不允许http/https协议
        if (filepath.startsWith("http://") || filepath.startsWith("https://")) return false
        // 不允许路径穿越
        if (filepath.contains("..")) return false
        // 只允许安全字符（前导 "/" 已在字符集里）
        return filepath.matches(Regex("^[a-zA-Z0-9_./-]+$"))
    }


    /**
     * 校验下载到的 APK 是否由预期证书签名：用 getPackageArchiveInfo(GET_SIGNATURES) 取第一张签名证书 DER，算 SHA-256 指纹，
     * 与文件顶部的 EXPECTED_CERT_FINGERPRINT 比对（那是 CI 固定 keystore 的证书指纹，见 @tag: sha256）。
     * 注意这是"签名证书指纹"，不是素材文件的 md5，两者别混。
     */
    private fun verifyApkSignature(apkFile: File): Boolean {
        return try {
            val pm = packageManager
            // 获取APK信息（包含签名）
            val info = pm.getPackageArchiveInfo(apkFile.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
                ?: return false
            
            val signatures = info.signatures
                ?: return false
            
            if (signatures.isEmpty()) {
                Log.w(TAG, "APK没有签名")
                return false
            }
            
            // 计算签名证书的SHA-256指纹
            val cert = signatures[0].toByteArray()
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(cert)
            val fingerprint = digest.joinToString("") { "%02x".format(it) }
            
            // 测试后门：指纹仍是占位串时直接放行，方便本地未签名/换签名调试。
            // 顶部常量已是真实指纹，该条件现在恒不成立（要临时跳过校验就把常量改回占位串）。
            if (EXPECTED_CERT_FINGERPRINT == "YOUR_CERT_FINGERPRINT_HERE") {
                Log.w(TAG, "APK签名验证未配置，跳过（请在 MainActivity.kt 中配置 EXPECTED_CERT_FINGERPRINT）")
                return true
            }
            Log.d(TAG, "APK签名证书指纹: $fingerprint")
            if (fingerprint == EXPECTED_CERT_FINGERPRINT) {
                Log.d(TAG, "APK签名校验通过")
                return true
            } else {
                Log.e(TAG, "APK签名校验失败: 指纹不匹配")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "APK签名验证异常: ${e.message}")
            return false
        }
    }

    /**
     * 设备侧自查更新：延时 5s 后 GET /api/version/latest，服务端 version_code 比本机大才继续。
     * 调用点有三处（启动、MQTT 连接成功、重连），彼此不去重，所以同版本由 downloadAndInstall
     * 里的 otaBusyVersion 挡住重复下载/重复弹窗。
     * 注意本地判定用 VERSION_CODE（onCreate 从 packageInfo 读），不要写死。
     */
    private fun checkForUpdate() {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                Thread {
                    try {
                        val url = java.net.URL("$APK_URL/api/version/latest")
                        val connection = url.openConnection()
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        val response = connection.inputStream.bufferedReader().readText()

                        val json = org.json.JSONObject(response)
                        if (json.has("version_code")) {
                            val serverCode = json.getInt("version_code")
                            if (serverCode > VERSION_CODE) {
                                val serverVersion = json.optString("version", "")
                                val filepath = json.getString("filepath")
                                // 验证filepath安全性（防止路径穿越和协议相对URL绕过）
                                if (!isValidFilepath(filepath)) {
                                    Log.w(TAG, "OTA: 拒绝不安全的filepath: $filepath")
                                    return@Thread
                                }
                                // 服务端给的是 "/apk/x.apk"，这里归一保证恰好一个斜杠
                                val apkUrl = APK_URL.trimEnd('/') + "/" + filepath.trimStart('/')
                                val md5 = json.optString("md5", null)
                                logToFile("发现新版本: $serverVersion, MD5: $md5, 正在下载...")
                                downloadAndInstall(apkUrl, serverVersion, md5)
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Check update failed: ${e.message}")
                    }
                }.start()
            } catch (e: Exception) {
                Log.e(TAG, "Check update error: ${e.message}")
            }
        }, 5000)
    }

    /**
     * 下载并（经用户确认后）安装 APK。apkUrl 必须是完整地址，不是相对路径。
     * 顺序：isTrustedApkUrl → 下到 cacheDir/xvj-update-<version>.apk（已下好则直接复用不重下）→
     *       体积 <1MB 视为下载失败 → 有 expectedMd5 才比对（不等则删包退出）→
     *       verifyApkSignature 比对证书指纹 → 弹不可取消的确认框 → installApk。
     * md5 为空时（老云端命令没带）就少一道校验，只剩签名指纹兜底。
     * Toast/对话框都 post 到 mqttHandler（主线程），下载本身在新起的 Thread 里。
     * otaBusyVersion：同一条流程只允许一个（启动/连接/重连/推送可能并发触发同一版本）。
     */
    private fun downloadAndInstall(apkUrl: String, version: String, expectedMd5: String? = null) {
        // 安全校验：验证URL来源（精确匹配IP，防止绕过）
        if (!isTrustedApkUrl(apkUrl)) {
            logToFile("APK下载被拒绝：URL来源不明: $apkUrl")
            mqttHandler.post {
                android.widget.Toast.makeText(this, "更新来源不明，已拒绝", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }

        val busy = otaBusyVersion
        if (busy != null) {
            logToFile("OTA: 已有更新流程在处理(版本 $busy)，忽略本次对 $version 的触发")
            return
        }
        otaBusyVersion = version

        // 异步下载APK
        Thread {
            try {
                val apkFile = File(cacheDir, "xvj-update-$version.apk")
                // 复用上次已下好的包：MIUI 授权页秒退会让整次 14MB 下载白跑（见 retryPendingInstall），
                // 二次触发时不必重下。破损/伪造的包由下游体积+md5+签名三道闸拦下并删包，下次自然重下。
                if (apkFile.exists() && apkFile.length() > 1_000_000) {
                    logToFile("OTA: 复用已下载的 ${apkFile.name} (${apkFile.length()} bytes)，跳过下载")
                } else {
                    logToFile("开始下载APK: $apkUrl")
                    mqttHandler.post {
                        try {
                            android.widget.Toast.makeText(this, "正在下载更新: $version", android.widget.Toast.LENGTH_SHORT).show()
                        } catch(e: Exception) {}
                    }

                    val url = java.net.URL(apkUrl)
                    val connection = url.openConnection()
                    connection.connectTimeout = 30000
                    connection.readTimeout = 30000

                    // 如果已存在（哪怕是半截文件），先删除避免残留
                    if (apkFile.exists()) {
                        apkFile.delete()
                    }

                    connection.getInputStream().use { input ->
                        java.io.FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                }

                logToFile("APK就绪: ${apkFile.absolutePath}, 大小: ${apkFile.length()} bytes")

                // 验证APK文件有效性（真实APK通常>1MB）
                if (apkFile.length() < 1_000_000) {
                    logToFile("APK文件过小，可能下载失败")
                    otaBusyVersion = null
                    mqttHandler.post {
                        android.widget.Toast.makeText(this, "更新下载失败：文件异常", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                // MD5校验
                val finalApkFile = apkFile  // 捕获最终变量供lambda使用
                if (!expectedMd5.isNullOrEmpty()) {
                    val actualHash = apkFile.inputStream().use { input ->
                        val digest = java.security.MessageDigest.getInstance("MD5")
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            digest.update(buffer, 0, bytesRead)
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    if (actualHash != expectedMd5) {
                        logToFile("APK MD5校验失败！期望: $expectedMd5, 实际: $actualHash")
                        otaBusyVersion = null
                        mqttHandler.post {
                            android.widget.Toast.makeText(this, "更新校验失败，请重新尝试", android.widget.Toast.LENGTH_LONG).show()
                        }
                        apkFile.delete()
                        return@Thread
                    }
                    logToFile("APK MD5校验通过: $actualHash")
                }

                // 验证APK签名（安全防护）
                if (!verifyApkSignature(apkFile)) {
                    logToFile("APK签名验证失败！")
                    otaBusyVersion = null
                    mqttHandler.post {
                        android.widget.Toast.makeText(this, "更新验证失败：APK签名无效", android.widget.Toast.LENGTH_LONG).show()
                    }
                    apkFile.delete()
                    return@Thread
                }
                logToFile("APK签名验证通过")

                // 使用对话框让用户确认安装
                val installFile = finalApkFile  // 传递文件引用到Dialog
                mqttHandler.post {
                    try {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("更新已下载")
                            .setMessage("版本: $version\n点击确定开始安装")
                            .setPositiveButton("确定") { _, _ ->
                                installApk(installFile)
                            }
                            .setNegativeButton("取消", null)
                            .setCancelable(false)
                            // 确定/取消都会走到这里：解锁后，下一次触发（重连或云端再推）才能重试
                            .setOnDismissListener { otaBusyVersion = null }
                            .show()
                    } catch(e: Exception) {
                        Log.e(TAG, "Show install dialog error: ${e.message}")
                        otaBusyVersion = null
                        // 回退到Toast
                        android.widget.Toast.makeText(this, "更新已下载，请手动安装", android.widget.Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: Exception) {
                logToFile("下载APK失败: ${e.message}")
                otaBusyVersion = null
                mqttHandler.post {
                    try {
                        android.widget.Toast.makeText(this, "更新下载失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    } catch(e: Exception) {}
                }
            }
        }.start()
    }

    /**
     * 安装已下载的 APK（带"安装未知应用"权限闸门）。
     * 无权限时跳系统授权页，授权回来自动续装（onResume）。
     * 未来接入静默安装（root pm install / device-owner PackageInstaller）只需替换本函数实现。
     */
    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()) {
            logToFile("缺少安装未知应用权限，跳转授权页")
            pendingInstallApk = file
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                mqttHandler.post {
                    try {
                        android.widget.Toast.makeText(this, "请允许本应用安装未知应用，授权后自动继续安装", android.widget.Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                logToFile("跳转安装授权页失败: ${e.message}")
                pendingInstallApk = null
            }
            return
        }
        performInstall(file)
    }

    /**
     * 从"安装未知应用"授权页回来后复查权限，允许就立即续装。
     * MIUI/HyperOS 的授权页会在权限真正生效前就回调 onResume（实测 47ms 即返回），
     * 一次判负就清空 pendingInstallApk 等于白丢已下好的包，所以每 1.5s 复查一次、最多约 30s。
     * 调用方（onResume）先 removeCallbacksAndMessages 再起链，保证同时只有一条轮询。
     */
    private fun retryPendingInstall(attempt: Int) {
        val pending = pendingInstallApk ?: return
        if (isFinishing || isDestroyed) {
            pendingInstallApk = null
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = null
            installApk(pending)
            return
        }
        if (attempt >= 20) {
            pendingInstallApk = null
            logToFile("等待安装权限超时(约30s)，放弃续装；已下载的包保留在缓存目录，下次触发会复用")
            return
        }
        installRetryHandler.postDelayed({ retryPendingInstall(attempt + 1) }, 1500)
    }

    /** 发起系统安装器（FileProvider URI） */
    private fun performInstall(file: File) {
        try {
            logToFile("开始安装APK: ${file.absolutePath}, exists=${file.exists()}, size=${file.length()}")
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            logToFile("APK URI: $apkUri")
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            logToFile("安装APK失败: ${e.message}")
            android.widget.Toast.makeText(this, "安装失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ========== 多窗口系统实现 ==========

    // 【A-07】 窗口配置 & 渲染
    /**
     * 全量重建窗口：release 掉全部旧播放器/视图，按 mergeSceneWindows 拿到的 A+B 窗口合集
     * 依 zIndex 升序逐个 createWindowView + playFolderInWindow。
     * @param scenes 完整 scenes JSON（A、B 两套都在里面，不是"当前那一幕"）
     * @param force  true = 结构指纹相同也照建（素材刚下载完，播放器得重新挑文件）；
     *               false（默认）= 与上次应用的是同一份配置就直接返回，不打断正在播的视频。
     *
     * 兜底：两幕一个窗口都没有时，才建 DEFAULT_WINDOW_ID 那个 1920x1080 全屏 SCENE_A 窗口
     *      （不指定 folderId，于是播 A 幕第一个有素材的文件夹）。
     * 必须在主线程调用（含 addView/removeView），所有调用点都已 post 到 mqttHandler。
     */
    private fun applySceneConfigs(scenes: JSONObject?, force: Boolean = false) {
        logToFile("开始应用场景配置", "INFO", "WINDOW", "CONFIG")
        if (scenes == null) {
            Log.w(TAG, "applySceneConfigs: scenes 为空")
            logToFile("scenes 为空，跳过窗口配置", "WARN", "WINDOW", "CONFIG")
            return
        }
        // 幂等闸门：同一条链路常常 apply 两次（指令进来一次、素材对账收尾一次），
        // 冷启动缓存 + auth_result 也是同一份配置。指纹没变就别把视频从头播一遍。
        // 记在末尾：中途抛异常时不算"已应用"，下一份相同载荷还会重试。
        val fp = scenesFingerprint(scenes)
        if (!force && fp == appliedScenesFp) {
            logToFile("scenes 与屏上一致（指纹 $fp），跳过全量重建", "INFO", "WINDOW", "SKIP")
            return
        }

        // 释放旧的窗口播放器
        windowPlayers.values.forEach { it.release() }
        windowPlayers.clear()

        // 移除旧的窗口视图
        windowViews.values.forEach { flSurface?.removeView(it) }
        windowViews.clear()
        windowContentSigs.clear()

        // 收集所有场景的窗口，A 和 B 的 windows 合并后统一渲染
        // 每个窗口播什么由自身 content.type/folderId 决定，与 currentSceneId（RS485 预留）无关
        val allWindows = mergeSceneWindows(scenes)

        // 若所有场景均无窗口，为 Scene A 创建默认全屏窗口
        if (allWindows.length() == 0) {
            val defaultContent = JSONObject().apply { put("type", "SCENE_A") }
            val defaultWin = JSONObject().apply {
                put("id", DEFAULT_WINDOW_ID)
                put("name", "窗口1")
                put("x", 0)
                put("y", 0)
                put("width", 1920)
                put("height", 1080)
                put("zIndex", 1)
                put("content", defaultContent)
            }
            createWindowView(DEFAULT_WINDOW_ID, defaultWin)
            Log.d(TAG, "所有场景无窗口，自动创建默认全屏窗口1 -> SCENE_A")
        } else {
            // 按 zIndex 升序创建所有窗口（A 和 B 的窗口都加载）
            val sorted = sortWindowsByZ(allWindows)
            for (i in 0 until sorted.length()) {
                val w = sorted.getJSONObject(i)
                val winId = w.optString("id", "win_$i")
                createWindowView(winId, w)
            }
        }

        // 每个窗口根据自身 content.type 播放对应文件夹（SCENE_A/SCENE_B 指向各自场景的素材）
        windowPlayers.forEach { (winId, player) ->
            // 找到对应窗口的 content 配置（遍历 allWindows 查找）
            val winObj = (0 until allWindows.length()).map { allWindows.getJSONObject(it) }.find { it.optString("id") == winId }
            val content = winObj?.optJSONObject("content") ?: JSONObject()
            val type = content.optString("type", "").uppercase()

            val folderId: String? = when (type) {
                "SCENE_A", "SCENE_B" -> {
                    // 场景 folder_mappings 的 key 已带场景前缀（A01/B01，见 buildPrefixedScenes）
                    val sceneKey = type.removePrefix("SCENE_")  // "SCENE_A" → "A"
                    val sceneData = scenes.optJSONObject(sceneKey)
                    val mappings = sceneData?.optJSONObject("folder_mappings") ?: JSONObject()
                    val allEntries = mappings.keys().asSequence()
                        .map { fid -> fid to (mappings.get(fid) as? org.json.JSONArray ?: org.json.JSONArray()) }
                        .toList()
                    // 指定了文件夹（content.folderId，纯编号如 "02"）：只播指定目录，
                    // 该目录无素材/未映射时告警跳过，不偷偷换播别的文件夹
                    val requested = content.optString("folderId", "").trim()
                    if (requested.isNotEmpty()) {
                        val entry = allEntries.find { it.first == sceneKey + requested }
                            ?: allEntries.find { it.first == requested }  // 兼容已带前缀的 folderId
                        when {
                            entry == null -> {
                                Log.w(TAG, "窗口 $winId [$type] 指定文件夹 $requested 不在场景 $sceneKey 的映射中，跳过播放")
                                logToFile("窗口 [$type] 指定文件夹 $requested 不在场景 $sceneKey 映射中，跳过播放", "WARN", "PLAYBACK", "SKIP")
                                null
                            }
                            entry.second.length() == 0 -> {
                                Log.w(TAG, "窗口 $winId [$type] 指定文件夹 ${entry.first} 无素材，跳过播放")
                                logToFile("窗口 [$type] 指定文件夹 ${entry.first} 无素材，跳过播放", "WARN", "PLAYBACK", "SKIP")
                                null
                            }
                            else -> {
                                Log.d(TAG, "窗口 $winId [$type] -> 指定文件夹 ${entry.first}")
                                logToFile("窗口 [$type] -> 指定文件夹 ${entry.first}", "INFO", "PLAYBACK", "SELECT")
                                prefixedPhysicalFolder(entry.first, sceneKey)
                            }
                        }
                    } else {
                        // 未指定：自动选场景中第一个有素材的文件夹（旧配置向后兼容）
                        val auto = allEntries.firstOrNull { it.second.length() > 0 }?.first
                        if (auto == null) {
                            Log.d(TAG, "窗口 $winId [$type] 场景 $sceneKey 无素材，跳过播放")
                            logToFile("窗口 [$type] 场景 $sceneKey 无素材，跳过播放（黑屏最常见原因）", "WARN", "PLAYBACK", "SKIP")
                        } else {
                            Log.d(TAG, "窗口 $winId [$type] -> 自动选择文件夹 $auto")
                            logToFile("窗口 [$type] -> 自动选择文件夹 $auto", "INFO", "PLAYBACK", "SELECT")
                        }
                        auto?.let { prefixedPhysicalFolder(it, sceneKey) }
                    }
                }
                "HDMI", "VIDEO_INPUT" -> "01"  // HDMI 输入默认文件夹01
                else -> null
            }
            if (folderId != null && player != null) {
                val folderPath = videoFolderPath.ifEmpty { filesDir.absolutePath }
                playFolderInWindow(winId, folderId, folderPath)
            }
        }
        appliedScenesFp = fp
    }

    /**
     * scenes 的结构指纹：递归按 key 排序后取 SHA-256 前 16 位，忽略 "_" 前缀的运行时临时标记
     * （mergeSceneWindows 会往窗口对象上打 _masterB）。
     * 排序键是为了让同一份配置不管来自素材同步载荷、窗口载荷还是 prefs 缓存反序列化都算出同一个值；
     * 宁可保守（无关字段变了也重建）也不能漏（该重建不重建 = 画面停在旧配置上，修都修不好）。
     */
    private fun scenesFingerprint(scenes: JSONObject): String =
        sha256(canonicalJson(scenes)).substring(0, 16)

    /** JSON 规范化成稳定字符串：对象键按字典序、下划线开头的运行时字段剔掉 */
    private fun canonicalJson(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().filter { !it.startsWith("_") }.sorted()
            .joinToString(",", "{", "}") { "\"" + it + "\":" + canonicalJson(v.opt(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonicalJson(v.opt(it)) }
        else -> v.toString()   // 数字/字符串/true/false/JSONObject.NULL，toString 即可区分
    }

    /** 按 zIndex 升序排列 JSONArray */
    private fun sortWindowsByZ(arr: JSONArray): JSONArray {
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        list.sortBy { it.optInt("zIndex", 0) }
        return JSONArray(list)
    }

    /** 合并 A/B 场景窗口并打上各自 master 亮度标记（scenes.A/B.master.brightness → _masterB） */
    private fun mergeSceneWindows(scenes: JSONObject): JSONArray {
        val allWindows = JSONArray()
        scenes.keys().forEach { sceneKey ->
            val sceneObj = scenes.optJSONObject(sceneKey)
            // 此 scenes 为每次 MQTT/启动时新解析的临时对象，打标不会污染持久化数据
            val sceneMasterB = sceneObj?.optJSONObject("master")?.optDouble("brightness", 1.0) ?: 1.0
            sceneObj?.optJSONArray("windows")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val wObj = arr.getJSONObject(i)
                    wObj.put("_masterB", sceneMasterB)
                    allWindows.put(wObj)
                }
            }
        }
        return allWindows
    }

    /** 窗口内容签名：type/folderId/color/inputIndex 任一变化即视为结构变化（需重建播放器） */
    private fun contentSignature(c: JSONObject): String =
        c.optString("type", "SCENE_A").uppercase() + "|" + c.optString("folderId", "") +
            "|" + c.optString("color", "") + "|" + c.optInt("inputIndex", 0)

    /** 物理文件夹统一带场景前缀：映射键可能是 "01"（纯编号）也可能是 "A01"，这里补齐成 "A01"。
     *  真正的落盘目录由 playFolderInWindow / syncFolderWithIds 再翻成 scenea/01（无前缀的 "01" 才用根目录 01/） */
    private fun prefixedPhysicalFolder(key: String, sceneKey: String): String =
        if (key.startsWith(sceneKey)) key else sceneKey + key

    /**
     * 实时窗口更新（update_windows 轻量路径）：编辑器拖动/调参的亚秒级跟随。
     * 仅几何/视觉属性变化 → 原地改 LayoutParams/alpha/遮罩/矩阵，播放器持续播放不重建；
     * 结构变化（窗口增删/内容签名/显隐/z序）→ 回退 applySceneConfigs 全量重建。
     */
    private fun applyLiveWindowUpdate(scenes: JSONObject) {
        val allWindows = mergeSceneWindows(scenes)
        val desiredSorted = sortWindowsByZ(allWindows).let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optBoolean("enabled", true) }
        }
        val desiredIds = desiredSorted.map { it.optString("id") }

        // 当前 flSurface 上窗口容器的 z 序（后加入者在上层）
        val currentIds = mutableListOf<String>()
        flSurface?.let { fs ->
            for (i in 0 until fs.childCount) {
                val ch = fs.getChildAt(i)
                for ((k, v) in windowViews) if (v === ch) { currentIds.add(k); break }
            }
        }

        var structural = desiredIds != currentIds
        if (!structural) {
            for (w in desiredSorted) {
                val winId = w.optString("id")
                if (windowContentSigs[winId] != contentSignature(w.optJSONObject("content") ?: JSONObject())) {
                    structural = true
                    break
                }
            }
        }
        if (structural) {
            logToFile("实时更新含结构变化，回退全量重建", "INFO", "WINDOW", "CONFIG")
            applySceneConfigs(scenes)
            return
        }

        val dm = resources.displayMetrics
        val sx = dm.widthPixels.toFloat() / 1920f
        val sy = dm.heightPixels.toFloat() / 1080f

        for (w in desiredSorted) {
            val winId = w.optString("id")
            val c = windowViews[winId] as? FrameLayout ?: continue

            val x = Math.round(w.optInt("x", 0) * sx)
            val y = Math.round(w.optInt("y", 0) * sy)
            val wd = Math.round(w.optInt("width", 1920).coerceAtLeast(64) * sx)
            val ht = Math.round(w.optInt("height", 1080).coerceAtLeast(64) * sy)
            val p = c.layoutParams as? FrameLayout.LayoutParams ?: continue
            if (p.leftMargin != x || p.topMargin != y || p.width != wd || p.height != ht) {
                p.leftMargin = x
                p.topMargin = y
                p.width = wd
                p.height = ht
                c.layoutParams = p
            }

            c.alpha = w.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f)

            val effB = (w.optDouble("brightness", 1.0).toFloat() * w.optDouble("_masterB", 1.0).toFloat()).coerceIn(0f, 1f)
            var dim: View? = null
            for (i in 0 until c.childCount) {
                val ch = c.getChildAt(i)
                if (ch.tag == DIM_TAG) { dim = ch; break }
            }
            if (effB < 1f) {
                if (dim == null) {
                    val d = View(this).apply {
                        setBackgroundColor(Color.BLACK)
                        alpha = 1f - effB
                        tag = DIM_TAG
                    }
                    c.addView(d, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                } else {
                    dim.alpha = 1f - effB
                }
            } else {
                dim?.let { c.removeView(it) }
            }

            // 布局稳定后重算截取/旋转/镜像矩阵（取景与 90°/270° 预缩放都依赖视图实际尺寸）
            val cv = c.getChildAt(0)
            if (cv != null) c.post { applyWindowTransform(cv, w) }
        }
        // 屏上现在就是这份配置了：指纹跟着走，否则下一轮素材对账收尾会白重建一次、视频重头播
        appliedScenesFp = scenesFingerprint(scenes)
        Log.d(TAG, "applyLiveWindowUpdate: 轻量应用 ${desiredSorted.size} 个窗口")
    }

    /** 创建单个窗口视图并加入 flSurface */
    private fun createWindowView(winId: String, w: JSONObject) {
        logToFile("开始创建窗口: id=$winId", "INFO", "WINDOW", "CREATE")
        if (flSurface == null) {
            Log.e(TAG, "flSurface 为 null，无法创建窗口")
            logToFile("flSurface 为 null，无法创建窗口", "ERROR", "WINDOW", "CREATE")
            return
        }

        // Arena 对标：enabled=false 的窗口不创建视图、不占用 ExoPlayer 实例
        if (!w.optBoolean("enabled", true)) {
            Log.d(TAG, "窗口 $winId enabled=false，跳过创建")
            logToFile("窗口 $winId 已隐藏(enabled=false)，跳过创建", "INFO", "WINDOW", "CREATE")
            return
        }

        // 分辨率适配：云端编辑器画布固定为 1920x1080，窗口坐标/尺寸按设备真实分辨率轴向等比换算，
        // 保证在 4K、竖屏盒子等非 1080p 设备上布局与设计稿占屏比例一致
        val dm = resources.displayMetrics
        val scaleX = dm.widthPixels.toFloat() / 1920f
        val scaleY = dm.heightPixels.toFloat() / 1080f
        val x = Math.round(w.optInt("x", 0) * scaleX)
        val y = Math.round(w.optInt("y", 0) * scaleY)
        val width = Math.round(w.optInt("width", 1920).coerceAtLeast(64) * scaleX)
        val height = Math.round(w.optInt("height", 1080).coerceAtLeast(64) * scaleY)

        val content = w.optJSONObject("content") ?: JSONObject()
        val type = content.optString("type", "SCENE_A").uppercase()

        val view: View = when (type) {
            "COLOR" -> createColorView(w, content)
            "HDMI"  -> createHdmiView(w, content)
            else    -> createVideoWindowView(winId, w, content)  // VIDEO / 默认
        }

        // Arena 对标属性：透明度直接作用容器；亮度=窗口亮度×所在屏总亮度，用黑色遮罩实现
        // （ExoPlayer 无直接亮度接口，遮罩法对 COLOR/VIDEO/HDMI 三类内容统一适用）
        val opacity = w.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f)
        val brightness = w.optDouble("brightness", 1.0).toFloat().coerceIn(0f, 1f)
        val masterB = w.optDouble("_masterB", 1.0).toFloat().coerceIn(0f, 1f)
        val effB = (brightness * masterB).coerceIn(0f, 1f)

        val container = FrameLayout(this).apply { alpha = opacity }
        container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (effB < 1f) {
            val dim = View(this).apply {
                setBackgroundColor(Color.BLACK)
                alpha = 1f - effB
                tag = DIM_TAG
            }
            container.addView(dim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        val params = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = x
            topMargin = y
            gravity = Gravity.TOP or Gravity.START
        }

        flSurface?.addView(container, params)
        windowViews[winId] = container
        windowContentSigs[winId] = contentSignature(content)
        // 布局完成后应用截取/旋转/镜像（取景与矩阵中心都依赖实际视图尺寸）
        container.post { applyWindowTransform(view, w) }
        val cr = cropRect(w)
        Log.d(TAG, "创建窗口: id=$winId type=$type size=${width}x${height} pos=($x,$y) opacity=$opacity brightness=$effB flip=${w.optString("flip","none")} rot=${w.optInt("rotation",0)} crop=(${cr[0]},${cr[1]},${cr[2]},${cr[3]})")
    }

    /**
     * Arena 对标：窗口内容变换 = 输入截取（InputRect）+ 框内旋转 + 镜像，窗口 x/y/w/h 不变。
     * 截取只能落在真正的 TextureView 上（视频窗口外层还有容器 FrameLayout），故递归查找；
     * 无 TextureView 的内容（COLOR/HDMI 占位 View）退化为 View 属性做旋转/镜像。
     * 每次调用都完整重设变换（含恒等情况），保证编辑器把截取/旋转改回默认时画面能复位。
     */
    private fun applyWindowTransform(view: View, w: JSONObject) {
        val rot = w.optInt("rotation", 0)
        val flip = w.optString("flip", "none")
        val crop = cropRect(w)
        val tv = if (view is TextureView) view else findTextureView(view)
        if (tv != null) {
            val vw = tv.width.toFloat()
            val vh = tv.height.toFloat()
            if (vw <= 0f || vh <= 0f) return
            val cx = vw / 2f
            val cy = vh / 2f
            val m = Matrix()
            // 取景框左上角落到原点，再放大到铺满窗口框（允许变形，与 Arena InputRect→OutputRect 同语义）
            m.setTranslate(-crop[0] * vw, -crop[1] * vh)
            m.postScale(1f / crop[2], 1f / crop[3])
            if (rot == 90 || rot == 270) {
                m.postScale(vh / vw, vw / vh, cx, cy)
            }
            m.postRotate(rot.toFloat(), cx, cy)
            if (flip == "h") m.postScale(-1f, 1f, cx, cy)
            if (flip == "v") m.postScale(1f, -1f, cx, cy)
            tv.setTransform(if (m.isIdentity) null else m)
            return
        }
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        var sx = 1f
        var sy = 1f
        if (rot == 90 || rot == 270) {
            sx = vh / vw
            sy = vw / vh
        }
        if (flip == "h") sx = -sx
        if (flip == "v") sy = -sy
        view.rotation = rot.toFloat()
        view.scaleX = sx
        view.scaleY = sy
    }

    /** 递归取窗口内第一个 TextureView（视频画面承载体） */
    private fun findTextureView(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findTextureView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** 归一化取景区 [x, y, w, h]：缺省/非法一律回退全画面，宽高下限 5% 且不会越出画面（避免零除） */
    private fun cropRect(w: JSONObject): FloatArray {
        val c = w.optJSONObject("crop") ?: return floatArrayOf(0f, 0f, 1f, 1f)
        fun num(name: String, def: Double): Float {
            val v = c.optDouble(name, def)
            return if (v.isNaN()) def.toFloat() else v.toFloat()
        }
        val x = num("x", 0.0).coerceIn(0f, 0.95f)
        val y = num("y", 0.0).coerceIn(0f, 0.95f)
        var wd = num("w", 1.0).coerceIn(0.05f, 1f)
        var ht = num("h", 1.0).coerceIn(0.05f, 1f)
        if (x + wd > 1f) wd = (1f - x).coerceAtLeast(0.05f)
        if (y + ht > 1f) ht = (1f - y).coerceAtLeast(0.05f)
        return floatArrayOf(x, y, wd, ht)
    }

    /** 纯色背景窗口 */
    private fun createColorView(w: JSONObject, content: JSONObject): View {
        val color = content.optString("color", "#000000")
        val name = w.optString("name", "")
        return TextView(this).apply {
            setBackgroundColor(Color.parseColor(color))
            text = name
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
    }

    /** HDMI 输入占位窗口 */
    private fun createHdmiView(w: JSONObject, content: JSONObject): View {
        val inputIdx = content.optInt("inputIndex", 0)
        return TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            text = "HDMI ${inputIdx + 1}"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
    }

    /** 视频播放窗口（每个窗口独立 ExoPlayer，TextureView 承载以支持旋转/镜像矩阵变换）*/
    private fun createVideoWindowView(winId: String, w: JSONObject, content: JSONObject): View {
        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val textureView = TextureView(this)
        container.addView(textureView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
            setVideoTextureView(textureView)
            // 首帧耗时 = 窗口创建 → 第一次 STATE_READY。现场那句"要点一下才出来"要的就是这个数：
            // 高分辨率素材的解码器起流水线 + 读满缓冲池全在这段，以前它是完全没数据的黑盒。
            // 只报第一次 READY（playlist 循环切歌不重复刷）；播放器随窗口重建，计数天然归零。
            val tCreated = android.os.SystemClock.elapsedRealtime()
            var readyLogged = false
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // 播放器级错误（文件损坏/编码不支持/源丢失）此前只进 logcat，后台完全黑盒
                    logToFile("窗口 $winId 播放器错误: ${error.errorCodeName} ${error.message ?: ""}", "ERROR", "PLAYBACK", "ERROR")
                }
                override fun onEvents(p: Player, events: Player.Events) {
                    if (readyLogged || p.playbackState != Player.STATE_READY) return
                    readyLogged = true
                    val costMs = android.os.SystemClock.elapsedRealtime() - tCreated
                    val vs = (p as? ExoPlayer)?.videoSize
                    logToFile("窗口 $winId 首帧就绪: 用时 ${costMs}ms" +
                        (if (vs != null) "，输入 ${vs.width}x${vs.height} ${vs.frameRate.toInt()}fps" else "") +
                        "，已缓冲 ${p.bufferedPercentage}%", "INFO", "PLAYBACK", "READY")
                }
            })
        }
        windowPlayers[winId] = player

        return container
    }

    /**
     * 在指定窗口内播放指定文件夹的视频
     * @param winId      窗口 ID
     * @param folderId   文件夹 ID（如 "01"）
     * @param folderPath 设备上文件夹的绝对路径
     */

    private fun playFolderInWindow(winId: String, folderId: String, folderPath: String) {
        logToFile("开始播放窗口: winId=$winId, folderId=$folderId", "INFO", "PLAYBACK", "START")
        val player = windowPlayers[winId] ?: run {
            Log.w(TAG, "playFolderInWindow: 找不到窗口 $winId 的播放器")
            logToFile("找不到窗口 $winId 的播放器", "ERROR", "PLAYBACK", "START")
            return
        }
        // scene-prefixed folderId：A01 → scenea/01/, B01 → sceneb/01/, 01 → 01/(root)
        // 注意：scenePrefix 只取首字母小写，与 syncFolderWithIds 保持一致
        val scenePrefixRaw = when {
            folderId.startsWith("A") || folderId.startsWith("a") -> "a"
            folderId.startsWith("B") || folderId.startsWith("b") -> "b"
            else -> null
        }
        val pureFolderId = if (scenePrefixRaw != null) folderId.removePrefix(scenePrefixRaw.uppercase()).removePrefix(scenePrefixRaw) else folderId
        val physicalDir = if (scenePrefixRaw != null) {
            java.io.File(folderPath, "scene" + scenePrefixRaw + "/" + pureFolderId)
        } else {
            java.io.File(folderPath, pureFolderId)
        }
        if (!physicalDir.exists()) {
            Log.w(TAG, "playFolderInWindow: 物理文件夹不存在 $physicalDir")
            logToFile("窗口 $winId 播放失败: 物理文件夹不存在 ${physicalDir.name}", "ERROR", "PLAYBACK", "ERROR")
            return
        }
        val videos = physicalDir.listFiles()
            ?.filter { it.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "webm") }
            ?.sortedBy { it.name } ?: return
        if (videos.isEmpty()) {
            Log.w(TAG, "playFolderInWindow: 物理文件夹 $physicalDir 内无视频")
            logToFile("窗口 $winId 播放失败: 文件夹 ${physicalDir.name} 内无视频文件", "WARN", "PLAYBACK", "SKIP")
            return
        }
        val items = videos.map { MediaItem.fromUri(Uri.fromFile(it)) }
        player.setMediaItems(items)
        player.prepare()
        Log.d(TAG, "窗口 $winId 开始播放 $folderId -> ${physicalDir.absolutePath} (${videos.size}个视频)")
        logToFile("窗口 $winId 开始播放 $folderId (${videos.size}个视频)", "INFO", "PLAYBACK", "START")
    }

    // 【A-08】 场景切换
    /**
     * 切换到 A/B 幕：只做"按缓存重渲染"。
     * ⚠️ 当前无人调用——窗口显示哪一幕由各自的 content.type 决定（applySceneConfigs 把 A+B 的 windows
     *    合并渲染），currentSceneId 只是 RS485 预留的状态位，改它不影响画面。
     */
    fun switchScene(sceneId: String) {
        currentSceneId = sceneId.uppercase()
        val cached = prefs.getString("scenes_json", null)
        if (cached != null) {
            try {
                applySceneConfigs(JSONObject(cached))
                Log.d(TAG, "切换到场景 $sceneId")
            } catch (e: Exception) {
                Log.e(TAG, "切换场景失败", e)
            }
        }
    }

    /**
     * 释放所有窗口：播放器 release + 视图从 flSurface 摘掉。
     * 调用点三处 —— onDestroy、playWelcomeVideo 切欢迎视频前（避免旧窗口盖住它）、
     * 以及 action:'stop'（停播 = 画面真的黑下去，不只是停播放器）。
     * 注意不清 windowContentSigs：那由下一次 applySceneConfigs 自己清。
     */
    private fun releaseAllWindows() {
        windowPlayers.values.forEach { it.release() }
        windowPlayers.clear()
        windowViews.values.forEach { flSurface?.removeView(it) }
        windowViews.clear()
        // 屏上已经没有窗口了，"已应用配置"的指纹必须一起作废：
        // 否则下次拿同一份 scenes 来 apply 会被幂等闸门挡掉，画面停在欢迎视频上不去
        appliedScenesFp = null
        Log.d(TAG, "释放所有窗口资源")
    }
}
