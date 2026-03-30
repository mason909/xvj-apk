/**
 * XVJ 云控系统 - Android APP (MainActivity)
 * =========================================
 * 架构：Kotlin + AndroidX + ExoPlayer + MQTT
 *
 * 【代码索引】搜索 "【A-XX】" 快速定位
 * ─────────────────────────────────────────────────
 * 【A-01】生命周期 & 初始化（onCreate / onDestroy）
 * 【A-02】MQTT 连接（connectMQTT / onMqttConnected）
 * 【A-03】设备注册 & 授权（registerDevice / handleAuthResponse）
 * 【A-04】MQTT 消息处理 & 分发（handleCommand → when(action)）
 * 【A-05】视频播放（playFromUrl / playFromCloud / playLocalVideo）
 * 【A-06】素材同步（syncRoomMaterials / syncFolderWithIds / deleteMaterialFile）
 * 【A-07】窗口配置 & 渲染（applySceneConfigs / createWindowView / playFolderVideos）
 * 【A-08】场景切换（switchScene）
 * 【A-09】本地文件扫描（scanLocalVideos / loadVideosFromFolder）
 * 【A-10】权限 & 系统 UI（checkStoragePermission / hideSystemUI）
 * 【A-11】工具方法（logToFile / sha256 / generateDeviceFingerprint）
 * ─────────────────────────────────────────────────
 * 
 * 核心流程：
 *   1. MQTT 连接（持久连接，复用于所有通信）
 *   2. 设备注册 → authorized=1 → 等待房间分配
 *   3. syncRoomMaterials() → /api/room-materials/{roomId}
 *      → 下载文件夹 → 精确同步本地文件
 *   4. 播放循环：按 folder_mappings 顺序播放
 * 
 * 房间调试模式（debug_mode）：
 *   - 由 /api/room-materials 响应中的 debug 字段控制
 *   - SharedPreferences 持久化
 *   - true 时 logToFile() 通过 MQTT 上报日志到 device_logs 表
 * 
 * 重要约定：
 *   - device_id 注册前为 fingerprint，注册后为 uuid
 *   - 本地文件存 getFilesDir()，与服务器素材解耦
 *   - 所有网络请求在下载线程执行，UI 更新 post 到 mqttHandler
 */

package com.xvj.app


import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.widget.FrameLayout
import android.widget.TextView
import android.view.Gravity
import android.graphics.Color
import android.net.Uri
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

// 上线前替换为实际 SHA-256 指纹；未配置时跳过验证，方便测试
private const val EXPECTED_CERT_FINGERPRINT = "YOUR_CERT_FINGERPRINT_HERE"

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
    private var player: ExoPlayer? = null
    private var videoList: MutableList<File> = mutableListOf()

    // 配置项
    private val prefs by lazy { getSharedPreferences("xvj_prefs", MODE_PRIVATE) }
    // 使用 app 内部存储目录，确保可写
    private var videoFolderPath: String = ""
    private var loopPlay: Boolean = true
    private var autoPlay: Boolean = true

    // MQTT配置 - 云端地址
    private var mqttServer = "tcp://47.102.106.237:1883"
    private var mqttClientId = ""
    private var deviceId: String = ""

    // ========== 多窗口系统 ==========
    /** 当前活跃场景 ID："A" 或 "B" */
    private var currentSceneId = "A"
    /** windowId -> ExoPlayer 实例（每个窗口独立播放器）*/
    private val windowPlayers = mutableMapOf<String, ExoPlayer>()
    /** windowId -> View 实例（窗口视图）*/
    private val windowViews = mutableMapOf<String, View>()
    /** 默认窗口 ID（场景A默认播放文件夹01）*/
    private val DEFAULT_WINDOW_ID = "win_1"
    private val DEFAULT_FOLDER = "01"
    /** 窗口容器 FrameLayout（根视图）*/
    private val flSurface: FrameLayout? get() = binding.root as? FrameLayout
    // ==============================
    private var mqttClient: MqttClient? = null
    private val mqttHandler = Handler(Looper.getMainLooper())
    private val statusHandler = Handler(Looper.getMainLooper())
    // 4线程下载池，支持并行素材同步
    private val downloadExecutor = Executors.newFixedThreadPool(4)
    private var statusTimerRunnable: Runnable? = null

    // 下载的视频缓存目录
    private val downloadDir by lazy { File(filesDir, "videos") }

    // 设备指纹信息
    private var deviceFingerprint: String = ""

    companion object {
        private const val TAG = "XVJPlayer"
        const val VERSION = "1.2.0"
        const val VERSION_CODE = 159
        const val APK_URL = "http://47.102.106.237"
        private const val MQTT_TOPIC = "xvj/device/+/command"
        private const val AUTH_TOPIC = "xvj/auth/response"
        // ETag/Last-Modified 缓存的 SharedPreferences key 前缀
        private const val PREF_ETAG_PREFIX = "etag_"
        private const val PREF_LM_PREFIX = "lm_"
    }

    // 文件日志
    private fun logToFile(msg: String) {
        try {
            val logFile = File(filesDir, "xvj.log")
            PrintWriter(FileWriter(logFile, true)).use { it.println("${System.currentTimeMillis()} $msg") }

            // 只有debug_mode开启时才MQTT上报日志
            if (prefs.getBoolean("debug_mode", false)) {
                val _msg = msg
                val fid = deviceFingerprint
                if (fid.isNotEmpty()) {
                    mqttHandler.post {
                        try {
                            val topic = "xvj/device/${fid}/log"
                            val payload = "${System.currentTimeMillis()} $_msg"
                            mqttClient?.publish(topic, payload.toByteArray(), 1, false)
                        } catch (e: Exception) { Log.e(TAG, "MQTT log failed: ${e.message}") }
                    }
                }
            }
        } catch (e: Exception) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 检查并申请存储权限
        checkStoragePermission()

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

        logToFile("=== XVJ App Starting ===")
        logToFile("Video folder: $videoFolderPath")

        // 确保下载目录存在
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }

        // 创建20个素材文件夹 (01-20)
        createMaterialFolders()

        // 生成设备指纹
        deviceFingerprint = generateDeviceFingerprint()
        Log.d(TAG, "Device Fingerprint: $deviceFingerprint")
        logToFile("Fingerprint: $deviceFingerprint")

        // 加载配置（包含 scenes 缓存恢复，设备开机直接进入窗口1）
        loadConfig()

        // 连接MQTT（窗口系统由 sync_room_materials 触发，或从本地缓存恢复）
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

        // 7. APK版本号 — 不同版本号=不同设备，防止升级后被旧记录覆盖
        sb.append("|vc:$VERSION_CODE")

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

    private fun loadConfig() {
        // 使用 app 内部存储目录，不需要权限
        if (videoFolderPath.isEmpty()) {
            videoFolderPath = filesDir.absolutePath
        }

        // 本地配置
        loopPlay = prefs.getBoolean("loop_play", true)
        autoPlay = prefs.getBoolean("auto_play", true)
        mqttServer = prefs.getString("mqtt_server", "tcp://47.102.106.237:1883") ?: "tcp://47.102.106.237:1883"

        // 恢复调试模式标志（MQTT 命令或 HTTP API 设置的值）
        val debugMode = prefs.getBoolean("debug_mode", false)
        Log.d(TAG, "Debug mode: $debugMode")

        // 使用设备指纹作为ID
        deviceId = prefs.getString("device_id", "") ?: ""
        if (deviceId.isEmpty()) {
            deviceId = deviceFingerprint
            prefs.edit().putString("device_id", deviceId).apply()
        }

        mqttClientId = "xvj_device_$deviceId"

        Log.d(TAG, "Device ID: $deviceId")

// 【A-02】 MQTT 连接初始化 // private fun connectMQTT()
        Log.d(TAG, "MQTT Server: $mqttServer")
        logToFile("DeviceID: $deviceId, MQTT: $mqttServer")

        // 启动时从本地缓存恢复 scenes 配置（离线多窗口）
        val cachedScenes = prefs.getString("scenes_json", null)
        if (cachedScenes != null) {
            try {
                applySceneConfigs(org.json.JSONObject(cachedScenes))
                Log.d(TAG, "启动时从本地缓存恢复 scenes 成功")
            } catch (e: Exception) {
                Log.e(TAG, "启动时恢复 scenes 失败: ${e.message}")
            }
        }
    }

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
                // P4 fix: 连接成功/失败通过 setCallback + isConnected 标志判断
                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "MQTT Connection Lost: ${cause?.message}")
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
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                try {
                    mqttClient?.connect(options)
                    Log.d(TAG, "MQTT Connected! Subscribed to: $commandTopic")
                    logToFile("MQTT Connected OK!")
                    onMqttConnected()  // P4 fix: 重置退避计数器
                    mqttClient?.subscribe(commandTopic, 0)
                    mqttClient?.subscribe(AUTH_TOPIC, 0)
                    registerDevice()
                    mqttHandler.post {
                        binding.statusText?.text = "云端已连接"
                    }
                    // P4 fix: 连接成功后执行，不要在闭包外裸调
                    checkForUpdate()
                    requestAuthSync()
                } catch (e: Exception) {
                    Log.e(TAG, "MQTT Connection Error: ${e.message}")
                    logToFile("MQTT Connection Error: ${e.message}")

// 【A-03】 设备注册 // private fun registerDevice()
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

    /**
     * 向云端注册设备
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
        }
    }

    /**
     * 发送设备状态到MQTT
     */
    private fun sendStatus(status: String) {
        try {
            val deviceId = prefs.getString("device_id", null) ?: return
            val statusTopic = "xvj/device/$deviceId/status"
            val payload = JSONObject().apply {
                put("status", status)
                put("timestamp", System.currentTimeMillis())
            }
            mqttClient?.publish(statusTopic, payload.toString().toByteArray(), 1, false)
            Log.d(TAG, "Status sent: $status")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send status: ${e.message}")
        }
    }

    /**
     * 启动定时发送状态
     */
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

// 【A-03b】 处理授权响应 // private fun handleAuthResponse()

    /**
     * 停止定时发送状态
     */
    private fun stopStatusTimer() {
        statusTimerRunnable?.let {
            statusHandler.removeCallbacks(it)
            // 发送离线状态
            sendStatus("offline")
        }
    }

    /**
     * 处理授权响应
     */
    private fun handleAuthResponse(payload: String) {
        try {
            val resp = JSONObject(payload)
            when (resp.getString("action")) {
                "auth_result" -> {
                    val authorized = resp.getBoolean("authorized")
                    val message = resp.optString("message", "")
                    // 提取房间信息
                    val roomId = resp.optString("room_id", "")
                    val folderMappings = resp.optJSONObject("folder_mappings")
                    // 提取 scenes 配置（包含 A/B 两套窗口配置和各自 folder_mappings）
                    val scenes = resp.optJSONObject("scenes")

                    mqttHandler.post {
                        if (authorized) {
                            binding.statusText?.text = "已授权至房间 $roomId"
                            // 停止欢迎视频等旧播放器
                            releasePlayer()
                            // 保存授权状态和房间信息
                            prefs.edit()
                                .putBoolean("authorized", true)
                                .putString("room_id", roomId)
                                .apply()
                            // 保存 scenes 配置（包含 A/B 的 folder_mappings）
                            if (scenes != null) {
                                prefs.edit().putString("scenes_json", scenes.toString()).apply()
                                Log.d(TAG, "授权成功，已保存 scenes: ${scenes.names()}")
                            }
                            // 保存 folder_mappings 并合并 A+B 一次同步
                            if (folderMappings != null) {
                                prefs.edit().putString("room_folder_mappings", folderMappings.toString()).apply()
                                Log.d(TAG, "授权成功: room_id=$roomId, folder_mappings=$folderMappings")
                                logToFile("开始根据房间配置同步素材...")
                                syncRoomMaterialsAllScenes(roomId, folderMappings, scenes)
                            }
                        } else {
                            binding.statusText?.text = "设备未授权"
                            // 切换到欢迎视频（不停止当前播放）
                            showUnauthorizedAlert(message)
                        }
                    }
                }
                "deauthorize" -> {
                    // 被远程废掉
                    mqttHandler.post {
                        binding.statusText?.text = "已废止"
                        prefs.edit()
                            .putBoolean("authorized", false)
                            .remove("room_id")
                            .remove("folder_mappings")
                            .apply()
                        showUnauthorizedAlert("设备已被远程废止，请联系管理员")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auth response parse error: ${e.message}")
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
     * 播放欢迎视频（循环）
     */
    private fun playWelcomeVideo() {
        mqttHandler.post {
            try {
                // 先释放旧播放器
                releasePlayer()

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
            // P4 fix: checkForUpdate 和 requestAuthSync 移入 postDelayed 闭包内，等待连接建立后再执行
            checkForUpdate()
            requestAuthSync()
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


// 【A-04】 MQTT 消息分发 // private fun handleCommand() → when(action)
    // 请求同步授权状态
    private fun requestAuthSync() {
        try {
            val topic = "xvj/auth/request"
            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("fingerprint", deviceFingerprint)
            }
            mqttClient?.publish(topic, payload.toString().toByteArray(), 1, false)
            Log.d(TAG, "请求授权状态同步")
        } catch (e: Exception) {
            Log.e(TAG, "请求授权同步失败: ${e.message}")
        }
    }

    private fun handleCommand(json: String) {
        try {
            val cmd = JSONObject(json)
            val action = cmd.getString("action")

            when (action) {
                "play" -> {
                    val url = cmd.optString("url", "")
                    val videoId = cmd.optString("id", "")
                    if (url.isNotEmpty()) {
                        playFromUrl(url)
                    } else if (videoId.isNotEmpty()) {
                        playFromCloud(videoId)
                    }
                }
                "play_local" -> {
                    // P1 fix: 路径遍历防护 — 严格校验 folder + filename
                    val folder = cmd.optString("folder", "01")
                    val filename = cmd.optString("filename", "")
                    if (filename.isNotEmpty() && isValidFilepath(filename)) {
                        val safeFolder = folder.trim().take(2).replace(Regex("[^0-9]"), "01")
                        playLocalVideo(safeFolder, filename.trim())
                    } else {
                        Log.w(TAG, "play_local 拒绝非法参数: folder=$folder, filename=$filename")
                    }
                }
                "push_file" -> {
                    // P1 fix: 路径遍历防护 — 严格校验 folder + filename
                    val folder = cmd.optString("folder", "01")
                    val filename = cmd.optString("filename", "")
                    val url = cmd.optString("url", "")
                    if (filename.isNotEmpty() && isValidFilepath(filename) && url.isNotEmpty()) {
                        val safeFolder = folder.trim().take(2).replace(Regex("[^0-9]"), "01")
                        downloadAndPlay(safeFolder, filename.trim(), url)
                    } else {
                        Log.w(TAG, "push_file 拒绝非法参数: folder=$folder, filename=$filename, url=$url")
                    }
                }
                "stop" -> {
                    stopPlayback()
                }
                "pause" -> {
                    player?.pause()
                }
                "resume" -> {
                    player?.play()
                }
                "next" -> {
                    player?.seekToNext()
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
                "sync" -> {
                    val roomId = prefs.getString("room_id", "") ?: ""
                    val mappingsStr = prefs.getString("room_folder_mappings", "{}") ?: "{}"
                    if (roomId.isNotEmpty()) {
                        syncRoomMaterials(roomId, org.json.JSONObject(mappingsStr))
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
                    // 保存 debug 标志（来自 MQTT 命令或后续 HTTP API，MQTT 先收到就先保存）
                    val debug = cmd.optBoolean("debug", false)
                    prefs.edit().putBoolean("debug_mode", debug).apply()
                    val fmKeys = java.lang.StringBuilder()
                    folderMappings?.keys()?.let { val k = it; while (k.hasNext()) { fmKeys.append(k.next()).append(",") } }
                    logToFile("sync_room_materials: roomId=$roomId, folderMappings=" + fmKeys.toString() + ", debug=$debug")

                    // 解析并持久化 scenes（A/B 两套窗口配置）
                    val scenes = cmd.optJSONObject("scenes")
                    if (scenes != null) {
                        prefs.edit().putString("scenes_json", scenes.toString()).apply()
                        Log.d(TAG, "已保存 scenes 到本地: ${scenes.names()}")
                        applySceneConfigs(scenes)
                    } else {
                        // 无 scenes 时尝试从本地缓存恢复（离线场景）
                        val cached = prefs.getString("scenes_json", null)
                        if (cached != null) {
                            try {
                                applySceneConfigs(org.json.JSONObject(cached))
                                Log.d(TAG, "从本地缓存恢复 scenes 成功")
                            } catch (e: Exception) {
                                Log.e(TAG, "从本地缓存恢复 scenes 失败: ${e.message}")
                            }
                        }
                    }

                    // 合并 Scene A + B 的 folder_mappings，一次调用，避免覆盖问题
                    if (folderMappings != null) {
                        val foldersStr = java.lang.StringBuilder()
                        val k = folderMappings.keys()
                        while (k.hasNext()) { foldersStr.append(k.next()).append(",") }
                        logToFile("准备同步素材: roomId=$roomId, folders=" + foldersStr)
                        syncRoomMaterialsAllScenes(roomId, folderMappings, scenes)
                    } else {
                        logToFile("folderMappings 为 null，跳过素材同步")
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

// 【A-05】 视频播放 // private fun playFromUrl() | playLocalVideo() | downloadAndPlay()
                                android.widget.Toast.makeText(this, "正在下载更新: $version", android.widget.Toast.LENGTH_LONG).show()
                            } catch(e: Exception) {}
                        }
                        // 下载并提示用户安装
                        downloadAndInstall(url, version)
                    } else if (serverCode <= VERSION_CODE) {
                        Log.d(TAG, "OTA: 当前已是最新版本 ($VERSION_CODE >= $serverCode)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command parse error: ${e.message}")
        }
    }

    private fun playFromUrl(url: String) {
        Log.d(TAG, "Playing from URL: $url")
        mqttHandler.post {
            releasePlayer()
            player = ExoPlayer.Builder(this).build().apply {
                binding.playerView.player = this
                val mediaItem = MediaItem.fromUri(url)
                setMediaItems(listOf(mediaItem))
                repeatMode = if (loopPlay) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                playWhenReady = true
                prepare()
            }
            binding.statusText?.text = "播放中"
        }
    }

    private fun playFromCloud(videoId: String) {
        Log.d(TAG, "Playing from cloud: $videoId")
        mqttHandler.post {
            binding.statusText?.text = "正在下载素材..."
        }

        downloadExecutor.submit {
            try {
                val apiUrl = "http://47.102.106.237/api/materials/$videoId"
                val connection = java.net.URL(apiUrl).openConnection()
                connection.connectTimeout = 10000
                val response = connection.getInputStream().bufferedReader().readText()
                val json = JSONObject(response)
                val url = json.getString("url")
                val videoUrl = "http://47.102.106.237$url"

                playFromUrl(videoUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Play from cloud error: ${e.message}")
                mqttHandler.post {
                    binding.statusText?.text = "素材下载失败"
                }
            }
        }
    }

    /**
     * 播放本地视频
     * @param folder 文件夹名 (01-20)
     * @param filename 文件名
     */
    private fun playLocalVideo(folder: String, filename: String) {
        val videoFile = File(videoFolderPath, "$folder/$filename")
        Log.d(TAG, "Playing local: ${videoFile.absolutePath}, exists=${videoFile.exists()}, size=${if(videoFile.exists()) videoFile.length() else 0}")

        if (!videoFile.exists()) {
            Log.w(TAG, "File not found: ${videoFile.absolutePath}, waiting for sync...")
            mqttHandler.post {
                binding.statusText?.text = "等待素材同步..."
            }
            return
        }

        mqttHandler.post {
            try {
                releasePlayer()
                player = ExoPlayer.Builder(this).build().apply {
                    binding.playerView.player = this
                    val mediaItem = MediaItem.fromUri(android.net.Uri.parse("file://${android.net.Uri.encode(videoFile.absolutePath, null)}"))
                    setMediaItems(listOf(mediaItem))
                    repeatMode = if (loopPlay) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                    playWhenReady = true
                    prepare()
                }
                binding.statusText?.text = "播放: $folder/$filename"
            } catch (e: Exception) {
                Log.e(TAG, "ExoPlayer init error: ${e.message}")
                binding.statusText?.text = "播放器初始化失败: ${e.message}"
            }
        }
    }

    /**
     * 下载文件并播放
     * @param folder 文件夹名 (01-20)
     * @param filename 文件名
     * @param url 云端URL
     */
    private fun downloadAndPlay(folder: String, filename: String, url: String) {
        val serverUrl = if (url.startsWith("http")) url else "http://47.102.106.237$url"
        val localFile = File(videoFolderPath, "$folder/$filename")

        Log.d(TAG, "Downloading: $serverUrl -> ${localFile.absolutePath}")
                Log.d(TAG, "videoFolderPath=$videoFolderPath, exists=${File(videoFolderPath).exists()}")
        mqttHandler.post {
            binding.statusText?.text = "正在下载素材..."
        }

        downloadExecutor.submit {
            try {
                // 确保文件夹存在
                val folderDir = File(videoFolderPath, folder)
                if (!folderDir.exists()) {
                    folderDir.mkdirs()
                }

                // 下载文件
                val connection = java.net.URL(serverUrl).openConnection()
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                val input = connection.getInputStream()
                val output = java.io.FileOutputStream(localFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                output.close()
                input.close()

                Log.d(TAG, "Download complete: ${localFile.absolutePath} (${localFile.length()} bytes)")

                // 下载完成后播放（完整 try-catch 确保异常不漏出）
                try {
                    mqttHandler.post {
                        binding.statusText?.text = "下载完成，播放: $filename"
                    }
                    playLocalVideo(folder, filename)
                } catch (e: Exception) {
                    Log.e(TAG, "playLocalVideo exception: ${e.message}")
                    mqttHandler.post {
                        binding.statusText?.text = "播放失败: ${e.message}"
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}")
                mqttHandler.post {
                    binding.statusText?.text = "素材下载失败: ${e.message}"
                }
            }
        }
    }

    // 同步预设素材文件夹
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

// 【A-06b】 房间素材同步入口 // private fun syncRoomMaterialsAllScenes()
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

    // 同步房间素材到本地文件夹

// 【A-06c】 房间素材同步核心 // private fun syncRoomMaterials()

    // 同步房间素材主流程：调用 /api/room-materials/:roomId 一次性获取所有文件夹的素材，再精确同步
    // 合并 Scene A + B 的 folder_mappings，一次同步，避免两次调用覆盖 prefs
    private fun syncRoomMaterialsAllScenes(roomId: String, folderMappingsA: org.json.JSONObject, scenes: org.json.JSONObject?) {
        val merged = org.json.JSONObject()
        // 先放入 Scene A 的映射
        folderMappingsA.keys().forEach { key -> merged.put(key, folderMappingsA.get(key)) }
        // 再合并 Scene B（若有），B 的 key 不会和 A 重叠
        scenes?.optJSONObject("B")?.optJSONObject("folder_mappings")?.let { fmB ->
            fmB.keys().forEach { key -> merged.put(key, fmB.get(key)) }
        }
        Log.d(TAG, "合并后 folder_mappings: ${merged.keys().asSequence().toList()}")
        syncRoomMaterials(roomId, merged)
    }

    private fun syncRoomMaterials(roomId: String, folderMappings: org.json.JSONObject) {
        mqttHandler.post {
            binding.statusText?.text = "同步房间素材中..."
        }

        downloadExecutor.submit {
            try {
                prefs.edit()
                    .putString("current_room_id", roomId)
                    .putString("room_folder_mappings", folderMappings.toString())
                    .apply()

                // 一次性获取该房间所有素材（合并 materials + preset_materials）
                // v2: 使用 /api/room-materials-v2/ 同时查 materials + preset_materials 表
                val allMaterials = mutableMapOf<String, org.json.JSONArray>() // folderId -> materials[]
                try {
                    val apiUrl = java.net.URL(APK_URL + "/api/room-materials-v2/" + roomId)
                    logToFile("HTTP 请求: $apiUrl")
                    val connection = apiUrl.openConnection()
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    val response = connection.inputStream.bufferedReader().readText()
                    logToFile("HTTP 响应长度: ${response.length}, 前100字符: ${response.take(100)}")
                    val resultJson = org.json.JSONObject(response)
                    // 解析成 { "01": [{id,filename,url...}], "02": [...] } 结构
                    val keys = resultJson.keys()
                    while (keys.hasNext()) {
                        val folderId = keys.next()
                        if (folderId != "debug") {
                            allMaterials[folderId] = resultJson.getJSONArray(folderId)
                        }
                    }
                    logToFile("获取房间素材成功: " + allMaterials.size + " 个文件夹")
                } catch (e: Exception) {
                    Log.e(TAG, "获取房间素材失败: " + e.message)
                    logToFile("获取房间素材失败: " + e.message)
                }

                // 遍历30个文件夹
                for (i in 1..30) {
                    val folderId = String.format("%02d", i)
                    val materialIds = folderMappings.optJSONArray(folderId)
                    if (materialIds != null && materialIds.length() > 0) {
                        val cloudList = allMaterials[folderId]
                        syncFolderWithIds(folderId, materialIds, cloudList)
                    } else {
                        deleteFolderFiles(folderId)
                    }
                }

                mqttHandler.post {
                    binding.statusText?.text = "素材同步完成"
                    // 素材同步完成后，应用场景配置并开始默认播放
                    val scenesJson = prefs.getString("scenes_json", null)
                    if (scenesJson != null) {
                        try {
                            applySceneConfigs(org.json.JSONObject(scenesJson))
                        } catch (e: Exception) {

// 【A-06d】 下载单个文件夹素材 // private fun syncFolderWithIds()
                            Log.e(TAG, "applySceneConfigs 失败: ${e.message}")
                        }
                    }
                }
                logToFile("房间素材同步完成: " + roomId)
            } catch (e: Exception) {
                Log.e(TAG, "Room materials sync error: " + e.message)
                mqttHandler.post {
                    binding.statusText?.text = "素材同步失败"
                }
            }
        }
    }

    // 按 material IDs 精确同步单个文件夹（传入预获取的云端素材列表，避免重复请求）
    private fun syncFolderWithIds(folderId: String, materialIds: org.json.JSONArray, cloudList: org.json.JSONArray?) {
        try {
            if (cloudList == null || cloudList.length() == 0) {
                Log.d(TAG, "文件夹 " + folderId + " 无云端素材")
                deleteFolderFiles(folderId)
                return
            }

            val idsSet = mutableSetOf<String>()
            for (i in 0 until materialIds.length()) {
                idsSet.add(materialIds.getString(i))
            }

            val localFolder = File(videoFolderPath, folderId)
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
                        val localMd5 = calculateMd5(localFile)
                        if (localMd5 == md5) {
                            Log.d(TAG, "文件已存在且MD5一致: " + filename)
                            continue
                        }
                    }
                    Log.d(TAG, "下载: " + filename)
                    logToFile("下载: " + filename)
                    downloadFile(downloadUrl, localFile)
                }
            }


// 【A-06e】 删除本地素材文件 // private fun deleteMaterialFile()
            for (file in localFiles) {
                if (!shouldExist.contains(file.name)) {
                    Log.d(TAG, "删除不在清单中的文件: " + file.name)
                    logToFile("删除: " + file.name)
                    file.delete()
                }
            }
            Log.d(TAG, "文件夹 " + folderId + " 同步完成")
        } catch (e: Exception) {
            Log.e(TAG, "syncFolderWithIds " + folderId + " 失败: " + e.message)
            logToFile("同步文件夹" + folderId + " 失败: " + e.message)
        }
    }

    // 删除指定文件夹下的指定素材文件
    private fun deleteMaterialFile(folderId: String, filename: String, materialId: String) {
        try {
            val localFolder = File(videoFolderPath, folderId)
            if (!localFolder.exists()) {
                Log.d(TAG, "deleteMaterialFile: folder $folderId not exist")
                return
            }
            val file = File(localFolder, filename)
            if (file.exists()) {
                // 清除 ETag/Last-Modified 缓存，避免删后重加时 APK 因 304 跳过下载
                prefs.edit()

// 【A-06f】 清空文件夹 // private fun deleteFolderFiles()
                    .remove(PREF_ETAG_PREFIX + filename)
                    .remove(PREF_LM_PREFIX + filename)
                    .apply()
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

    private fun deleteFolderFiles(folderId: String) {
        try {
            val localFolder = File(videoFolderPath, folderId)
            if (!localFolder.exists()) return
            val files = localFolder.listFiles()?.filter {
                it.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "webm")
            } ?: emptyList()
            for (file in files) {
                Log.d(TAG, "清空文件夹" + folderId + "，删除: " + file.name)

// A-07a】 扫描并播放本地视频 // private fun playFolderVideos()
                logToFile("清空删除: " + file.name)
                // 清除缓存的 ETag，避免删文件后重加时 APK 因 304 跳过下载
                prefs.edit()
                    .remove(PREF_ETAG_PREFIX + file.name)
                    .remove(PREF_LM_PREFIX + file.name)
                    .apply()
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteFolderFiles " + folderId + " 失败: " + e.message)
        }
    }

    // 下载并播放指定文件夹
    // 播放本地文件夹的视频
    private fun playFolderVideos(folderId: String) {
        Log.d(TAG, "playFolderVideos called: folderId=$folderId")
        
        // 已经在主线程执行，直接处理
        // 先释放旧播放器
        releasePlayer()
        
        // 确保文件夹存在
        val folderDir = File(videoFolderPath, folderId)
        Log.d(TAG, "Video folder path: ${folderDir.absolutePath}")
        
        if (!folderDir.exists()) {
            folderDir.mkdirs()
            Log.d(TAG, "Created folder: ${folderDir.absolutePath}")
        }
        
        if (!folderDir.exists() || !folderDir.isDirectory) {
            binding.statusText?.text = "无法创建文件夹: $folderId"
            Log.e(TAG, "Cannot create folder: $folderId")
            return
        }
        
        val videos = folderDir.listFiles()?.filter { 
            it.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "webm")
        }?.sortedBy { it.name } ?: emptyList()
        
        Log.d(TAG, "Found videos: ${videos.size}")
        
        if (videos.isEmpty()) {
            binding.statusText?.text = "文件夹为空: $folderId"
            Log.w(TAG, "Folder is empty: $folderId")
            return
        }
        
        videoList.clear()
        videoList.addAll(videos)
        
        if (videoList.isNotEmpty()) {
            // 直接创建新播放器播放
            Log.d(TAG, "Creating new player for folder $folderId")
            player = ExoPlayer.Builder(this).build().apply {
                binding.playerView.player = this
                val mediaItems = videoList.map { MediaItem.fromUri(android.net.Uri.fromFile(it)) }
                setMediaItems(mediaItems)
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = true
                prepare()
            }
            binding.statusText?.text = "播放文件夹$folderId: ${videoList.size}个视频"
            Log.d(TAG, "Player created, 开始播放文件夹$folderId")
        }
    }

    // 检查存储权限
    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 需要 MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10 需要动态申请
            val permissions = arrayOf(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            )
            val needRequest = permissions.any {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (needRequest) {
                requestPermissions(permissions, 100)
            }
        }
    }

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

    private fun scanLocalVideos() {
        val dir = File(videoFolderPath)

        if (!dir.exists() || !dir.isDirectory) {
            scanDefaultPaths()
            return
        }

        loadVideosFromFolder(dir)
    }

    private fun scanDefaultPaths() {
        val defaultPaths = listOf(
            "/storage/emulated/0/DCIM",
            "/storage/emulated/0/Movies",
            "/storage/emulated/0/Video",
            "/storage/emulated/0/VIDEOS",
            "/storage/emulated/0/Download",
            "/storage/emulated/0"
        )

        for (path in defaultPaths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                loadVideosFromFolder(dir)
                if (videoList.isNotEmpty()) {
                    return
                }
            }
        }
    }

    private fun loadVideosFromFolder(dir: File) {
        videoList = dir.listFiles()?.filter {
            it.isFile && isVideoFile(it.extension)
        }?.sortedBy { it.name }?.toMutableList() ?: mutableListOf()
    }

    private fun isVideoFile(ext: String): Boolean {
        return ext.lowercase() in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v")
    }

    private fun startPlayback() {
        releasePlayer()

        player = ExoPlayer.Builder(this).build().apply {
            binding.playerView.player = this

            val mediaItems = videoList.map { MediaItem.fromUri(it.toURI().toString()) }
            setMediaItems(mediaItems)

            repeatMode = if (loopPlay) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            playWhenReady = autoPlay

            prepare()
        }

        binding.statusText?.text = "播放本地视频: ${videoList.size}个"
    }

    private fun stopPlayback() {
        Log.d(TAG, "stopPlayback called")
        releasePlayer()
        binding.statusText?.text = "播放已停止"
    }

    private fun releasePlayer() {
        Log.d(TAG, "releasePlayer called, current player: $player")
        player?.release()
        player = null
        binding.playerView.player = null
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 禁用返回键 - 终端模式不允许退出
    }

    override fun onUserLeaveHint() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * 创建20个素材文件夹 (01-20)
     * 与后台文件夹对应
     */
    private fun createMaterialFolders() {
        try {
            val baseDir = File(videoFolderPath)
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            for (i in 1..20) {
                val folderName = String.format("%02d", i)
                val folder = File(baseDir, folderName)
                if (!folder.exists()) {
                    folder.mkdirs()
                    Log.d(TAG, "Created folder: ${folder.absolutePath}")
                }
            }
            Log.d(TAG, "Material folders initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create material folders: ${e.message}")
        }
    }

    /**
     * 检查更新 - 延迟5秒后执行
     */
    // 素材同步 v2: 4线程并行 + ETag条件请求 + 64KB buffer
    /**
     * 带 ETag/Last-Modified 条件请求的文件下载
     * 先发 HEAD 查文件是否有变化，有变化再下载
     * 缓存 ETag 和 Last-Modified 到 SharedPreferences
     * 使用 64KB buffer
     * 返回 true 表示实际下载了文件，false 表示跳过（未变化）
     */
    private fun downloadWithETag(urlStr: String, destFile: File, filename: String): Boolean {
        val cachedEtag = prefs.getString(PREF_ETAG_PREFIX + filename, null)

        // 文件不存在或为空：直接下载，不走任何缓存逻辑
        if (!destFile.exists() || destFile.length() == 0L) {
            Log.d(TAG, "本地文件不存在或为空，强制下载: ${destFile.name}")
        } else if (cachedEtag != null) {
            // 文件存在且有缓存ETag：用HEAD请求验证服务器ETag是否有变化
            try {
                val url = java.net.URL(urlStr)
                val checkConn = url.openConnection() as java.net.HttpURLConnection
                checkConn.requestMethod = "HEAD"
                checkConn.connectTimeout = 8000
                checkConn.readTimeout = 8000
                checkConn.setRequestProperty("If-None-Match", cachedEtag)
                val code = checkConn.responseCode
                checkConn.disconnect()
                if (code == 304) {
                    // BUG FIX: 文件不存在时，即使ETag未变也必须重新下载
                    // 场景：素材曾被添加→下载→从房间移除（文件被删）→重新添加
                    // 此时本地文件不存在但SharedPreferences中ETag仍缓存着旧值
                    // 服务器返回304（文件未变），必须强制重下而非跳过
                    if (!destFile.exists()) {
                        Log.d(TAG, "文件被删，ETag未变但强制重下: ${destFile.name}")
                    } else {
                        Log.d(TAG, "本地文件完整且ETag未变，跳过: ${destFile.name}")
                        return false
                    }
                }
                Log.d(TAG, "本地文件存在但ETag变化，重新下载: ${destFile.name}")
            } catch (e: Exception) {
                Log.w(TAG, "ETag验证失败，继续下载: ${e.message}")
            }
        }

        return try {
            // 文件完整性兜底：本地文件存在时，用HEAD请求验证ETag是否变化
            if (destFile.exists() && destFile.length() > 0 && cachedEtag != null) {
                try {
                    val url = java.net.URL(urlStr)
                    val checkConn = url.openConnection() as java.net.HttpURLConnection
                    checkConn.requestMethod = "HEAD"
                    checkConn.connectTimeout = 8000
                    checkConn.readTimeout = 8000
                    checkConn.setRequestProperty("If-None-Match", cachedEtag)
                    val code = checkConn.responseCode
                    checkConn.disconnect()
                    if (code == 304) {
                        Log.d(TAG, "本地文件完整且ETag未变，跳过: $filename")
                        return false
                    }
                    Log.d(TAG, "本地文件存在但ETag变化，重新下载: $filename")
                } catch (e: Exception) {
                    Log.w(TAG, "ETag验证失败，继续下载: ${e.message}")
                }
            }
            val url = java.net.URL(urlStr)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "HEAD"

            val cachedLm = prefs.getString(PREF_LM_PREFIX + filename, null)
            if (cachedEtag != null) connection.setRequestProperty("If-None-Match", cachedEtag)
            if (cachedLm != null) connection.setRequestProperty("If-Modified-Since", cachedLm)

            val responseCode = connection.responseCode
            connection.disconnect()

            if (responseCode == 304) {
                Log.d(TAG, "文件未变化跳过: $filename")
                return false
            }

            val conn2 = url.openConnection() as java.net.HttpURLConnection
            conn2.connectTimeout = 30000
            conn2.readTimeout = 30000
            if (cachedEtag != null) conn2.setRequestProperty("If-None-Match", cachedEtag)
            if (cachedLm != null) conn2.setRequestProperty("If-Modified-Since", cachedLm)

            val realCode = conn2.responseCode
            if (realCode == 304) {
                Log.d(TAG, "文件未变化跳过: $filename")
                conn2.disconnect()
                return false
            }

            val newEtag = conn2.getHeaderField("ETag")
            val newLm = conn2.getHeaderField("Last-Modified")
            if (newEtag != null) prefs.edit().putString(PREF_ETAG_PREFIX + filename, newEtag).apply()
            if (newLm != null) prefs.edit().putString(PREF_LM_PREFIX + filename, newLm).apply()

            conn2.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
            conn2.disconnect()
            Log.d(TAG, "下载完成: $filename")
            true
        } catch (e: Exception) {
            Log.e(TAG, "下载失败 [${filename}]: ${e.message}")
            false
        }
    }

    /**
     * 兼容旧逻辑的下载（无 ETag，用于 OTA APK 等一次性文件）
     */
    private fun downloadFile(urlStr: String, destFile: File) {
        try {
            val url = java.net.URL(urlStr)
            val connection = url.openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.getInputStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
            Log.d(TAG, "下载完成: ${destFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "下载失败: ${e.message}")
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
     * 验证APK下载URL是否可信
     * 精确匹配IP，防止URL绕过攻击
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
     * 验证filepath是否安全（防止路径穿越）
     */
    private fun isValidFilepath(filepath: String): Boolean {
        // 不允许绝对路径
        if (filepath.startsWith("/")) return false
        // 不允许http/https协议
        if (filepath.startsWith("http://") || filepath.startsWith("https://")) return false
        // 不允许路径穿越
        if (filepath.contains("..")) return false
        // 必须是相对路径且只包含安全字符
        return filepath.matches(Regex("^[a-zA-Z0-9_./-]+$"))
    }


    /**
     * 验证APK签名
     * 检查APK是否由可信证书签名
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
            
            // P2 fix: 必须配置实际证书指纹才可启用生产 OTA
            // 未配置指纹时跳过验证，方便测试
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
                                // 验证filepath安全性（防止路径穿越和绝对路径绕过）
                                if (!isValidFilepath(filepath)) {
                                    Log.w(TAG, "OTA: 拒绝不安全的filepath: $filepath")
                                    return@Thread
                                }
                                val apkUrl = "$APK_URL$filepath"
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

    // 显示更新通知
    private fun showUpdateNotification(version: String) {
        try {
            // Android 13+ 需要请求通知权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
                    logToFile("请求通知权限")
                }
            }

            val channel = android.app.NotificationChannel(
                "update_channel", "更新",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "APK更新通知" }

            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            // 使用显式Intent
            val intent = android.content.Intent(this, MainActivity::class.java)
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notification = android.app.Notification.Builder(this, "update_channel")
                .setContentTitle("发现新版本 $version")
                .setContentText("点击安装")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(1001, notification)
            logToFile("通知已显示")
        } catch (e: Exception) {
            Log.e(TAG, "Show notification error: ${e.message}")
            logToFile("通知错误: ${e.message}")
        }
    }

    /**
     * 下载并安装APK
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

        // 异步下载APK
        Thread {
            try {
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
                val apkFile = File(cacheDir, "xvj-update-$version.apk")

                // 如果已存在，先删除（避免残留问题）
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

                logToFile("APK下载完成: ${apkFile.absolutePath}, 大小: ${apkFile.length()} bytes")

                // 验证APK文件有效性（真实APK通常>1MB）
                if (apkFile.length() < 1_000_000) {
                    logToFile("APK文件过小，可能下载失败")
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
                                try {
                                    logToFile("开始安装APK: ${installFile.absolutePath}, exists=${installFile.exists()}, size=${installFile.length()}")
                                    val apkUri = androidx.core.content.FileProvider.getUriForFile(
                                        this, "${packageName}.fileprovider", installFile
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
                            .setNegativeButton("取消", null)
                            .setCancelable(false)
                            .show()
                    } catch(e: Exception) {
                        Log.e(TAG, "Show install dialog error: ${e.message}")
                        // 回退到Toast
                        android.widget.Toast.makeText(this, "更新已下载，请手动安装", android.widget.Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: Exception) {
                logToFile("下载APK失败: ${e.message}")
                mqttHandler.post {

// 【A-07】 应用场景配置 // private fun applySceneConfigs()
                    try {
                        android.widget.Toast.makeText(this, "更新下载失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    } catch(e: Exception) {}
                }
            }
        }.start()
    }

    // ========== 多窗口系统实现 ==========

    /**
     * 应用场景配置（MQTT同步后或启动时调用）
     * scenes: 完整 scenes JSON（包含 A 和 B 两套）
     * 场景A无窗口时，自动创建全屏窗口1播放文件夹01
     */
    private fun applySceneConfigs(scenes: JSONObject?) {
        if (scenes == null) {
            Log.w(TAG, "applySceneConfigs: scenes 为空")
            return
        }

        val sceneObj = scenes.optJSONObject(currentSceneId)
        if (sceneObj == null) {
            Log.w(TAG, "applySceneConfigs: 场景 $currentSceneId 不存在")
            return
        }

        val windowsArr = sceneObj.optJSONArray("windows") ?: JSONArray()

        // 释放旧的窗口播放器
        windowPlayers.values.forEach { it.release() }
        windowPlayers.clear()

        // 移除旧的窗口视图
        windowViews.values.forEach { flSurface?.removeView(it) }
        windowViews.clear()

        Log.d(TAG, "applySceneConfigs: 场景 $currentSceneId, ${windowsArr.length()} 个窗口")

        // 按 zIndex 升序创建窗口
        val sorted = sortWindowsByZ(windowsArr)
        for (i in 0 until sorted.length()) {
            val w = sorted.getJSONObject(i)
            val winId = w.optString("id", "win_$i")
            createWindowView(winId, w)
        }

        // 场景A/B：若没有窗口配置，自动创建默认全屏窗口1
        // 第一幕播文件夹01，第二幕也播文件夹01（文件夹由485信号切换）
        if (windowsArr.length() == 0) {
            val sceneType = if (currentSceneId == "A") "SCENE_A" else "SCENE_B"
            val defaultWin = JSONObject().apply {
                put("id", DEFAULT_WINDOW_ID)
                put("name", "窗口1")
                put("x", 0)
                put("y", 0)
                put("width", 1920)
                put("height", 1080)
                put("zIndex", 1)
                put("content", JSONObject().apply {
                    put("type", sceneType)
                })
            }
            createWindowView(DEFAULT_WINDOW_ID, defaultWin)
            Log.d(TAG, "场景${currentSceneId}无窗口配置，自动创建默认全屏窗口1 -> type=$sceneType")
        }

        // 每个窗口：根据 content 类型播放对应文件夹（支持 Scene A/B）
        windowPlayers.forEach { (winId, player) ->
            // 找到对应窗口的 content 配置
            val winObj = (0 until windowsArr.length()).map { windowsArr.getJSONObject(it) }.find { it.optString("id") == winId }
            val content = winObj?.optJSONObject("content") ?: JSONObject()
            val type = content.optString("type", "").uppercase()
            val inputIndex = content.optInt("inputIndex", 0)

            val folderId: String? = when (type) {
                "SCENE_A", "SCENE_B" -> {
                    // VIDEO=第一幕(文件夹01), SCENE_B=第二幕(文件夹01)
                    "01"
                }
                "VIDEO_INPUT", "HDMI" -> {
                    // HDMI输入类型：inputIndex 映射场景（0=第一幕, 1=第二幕）
                    when (inputIndex) {
                        0 -> "01"  // 第一幕
                        1 -> "01"  // 第二幕（也播文件夹01）
                        else -> "01"
                    }
                }
                "" -> {
                    // 兼容空类型：默认播 Scene A（文件夹01）
                    "01"
                }
                else -> null // HDMI/COLOR 等类型不播文件夹
            }
            if (folderId != null && player != null) {
                val folderPath = videoFolderPath.ifEmpty { filesDir.absolutePath }
                playFolderInWindow(winId, folderId, folderPath)
            }
        }
    }

    /** 按 zIndex 升序排列 JSONArray */
    private fun sortWindowsByZ(arr: JSONArray): JSONArray {
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        list.sortBy { it.optInt("zIndex", 0) }
        return JSONArray(list)
    }

    /** 创建单个窗口视图并加入 flSurface */
    private fun createWindowView(winId: String, w: JSONObject) {
        if (flSurface == null) {
            Log.e(TAG, "flSurface 为 null，无法创建窗口")
            return
        }

        val x = w.optInt("x", 0)
        val y = w.optInt("y", 0)
        val width = w.optInt("width", 1920).coerceAtLeast(64)
        val height = w.optInt("height", 1080).coerceAtLeast(64)

        val content = w.optJSONObject("content") ?: JSONObject()
        val type = content.optString("type", "SCENE_A").uppercase()

        val view: View = when (type) {
            "COLOR" -> createColorView(w, content)
            "HDMI"  -> createHdmiView(w, content)
            else    -> createVideoWindowView(winId, w, content)  // VIDEO / 默认
        }

        val params = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = x
            topMargin = y
            gravity = Gravity.TOP or Gravity.START
        }

        flSurface?.addView(view, params)
        windowViews[winId] = view
        Log.d(TAG, "创建窗口: id=$winId type=$type size=${width}x${height} pos=($x,$y)")
    }

    /** 纯色背景窗口 */
    private fun createColorView(w: JSONObject, content: JSONObject): View {

// 【A-07b】 创建窗口视图 // private fun createWindowView()
        val color = content.optString("color", "#000000")
        val name = w.optString("name", "")
        return TextView(this).apply {
            setBackgroundColor(Color.parseColor(color))
            text = name
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0.85f
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

    /** 视频播放窗口（每个窗口独立 ExoPlayer）*/
    private fun createVideoWindowView(winId: String, w: JSONObject, content: JSONObject): View {
        val playerView = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
        }
        val player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
        }
        playerView.player = player
        windowPlayers[winId] = player

// 【A-08】 场景切换 // fun switchScene()
        return playerView
    }

    /**
     * 在指定窗口内播放指定文件夹的视频
     * @param winId      窗口 ID
     * @param folderId   文件夹 ID（如 "01"）
     * @param folderPath 设备上文件夹的绝对路径
     */
    private fun playFolderInWindow(winId: String, folderId: String, folderPath: String) {
        val player = windowPlayers[winId] ?: run {
            Log.w(TAG, "playFolderInWindow: 找不到窗口 $winId 的播放器")
            return
        }
        val folder = java.io.File(folderPath, folderId)
        if (!folder.exists()) {
            Log.w(TAG, "playFolderInWindow: 文件夹不存在 $folder")
            return
        }
        val videos = folder.listFiles()
            ?.filter { it.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "webm") }
            ?.sortedBy { it.name } ?: return
        if (videos.isEmpty()) {
            Log.w(TAG, "playFolderInWindow: 文件夹 $folderId 内无视频")
            return
        }

        val items = videos.map { MediaItem.fromUri(Uri.fromFile(it)) }
        player.setMediaItems(items)
        player.prepare()
        Log.d(TAG, "窗口 $winId 开始播放文件夹 $folderId (${videos.size}个视频)")
    }

    /** 停止指定窗口的播放 */
    private fun stopWindow(winId: String) {
        windowPlayers[winId]?.let {
            it.stop()
            it.clearMediaItems()
            Log.d(TAG, "停止窗口 $winId")
        }
    }

    /** 切换到 A 或 B 场景并重新渲染 */
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

    /** 释放所有窗口资源（onDestroy 时调用）*/
    private fun releaseAllWindows() {
        windowPlayers.values.forEach { it.release() }
        windowPlayers.clear()
        windowViews.values.forEach { flSurface?.removeView(it) }
        windowViews.clear()
        Log.d(TAG, "释放所有窗口资源")
    }

    // ========== 多窗口系统实现 ==========
}
