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
import com.xvj.app.databinding.ActivityMainBinding
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.util.concurrent.Executors

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
    private var mqttClient: MqttClient? = null
    private val mqttHandler = Handler(Looper.getMainLooper())
    private val statusHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var statusTimerRunnable: Runnable? = null

    // 下载的视频缓存目录
    private val downloadDir by lazy { File(filesDir, "videos") }

    // 设备指纹信息
    private var deviceFingerprint: String = ""

    companion object {
        private const val TAG = "XVJPlayer"
        const val VERSION = "1.2.0"
        const val VERSION_CODE = 12
        const val APK_URL = "http://47.102.106.237"
        private const val MQTT_TOPIC = "xvj/device/+/command"
        private const val AUTH_TOPIC = "xvj/auth/response"
    }

    // 文件日志
    private fun logToFile(msg: String) {
        try {
            val logFile = File(filesDir, "xvj.log")
            PrintWriter(FileWriter(logFile, true)).use { it.println("${System.currentTimeMillis()} $msg") }
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

        // 加载配置
        loadConfig()

        // 检查授权状态并播放
        val isAuthorized = prefs.getBoolean("authorized", false)
        if (isAuthorized) {
            // 已授权，播放01文件夹视频
            logToFile("已授权，播放文件夹01")
            playFolderVideos("01")
        } else {
            // 未授权，播放欢迎视频
            logToFile("本地存储为未授权，播放欢迎视频")
            playWelcomeVideo()
        }

        // 连接MQTT
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

    private fun loadConfig() {
        // 使用 app 内部存储目录，不需要权限
        if (videoFolderPath.isEmpty()) {
            videoFolderPath = filesDir.absolutePath
        }

        // 本地配置
        loopPlay = prefs.getBoolean("loop_play", true)
        autoPlay = prefs.getBoolean("auto_play", true)
        mqttServer = prefs.getString("mqtt_server", "tcp://47.102.106.237:1883") ?: "tcp://47.102.106.237:1883"

        // 使用设备指纹作为ID
        deviceId = prefs.getString("device_id", "") ?: ""
        if (deviceId.isEmpty()) {
            deviceId = deviceFingerprint
            prefs.edit().putString("device_id", deviceId).apply()
        }

        mqttClientId = "xvj_device_$deviceId"

        Log.d(TAG, "Device ID: $deviceId")
        Log.d(TAG, "MQTT Server: $mqttServer")
        logToFile("DeviceID: $deviceId, MQTT: $mqttServer")
    }

    private fun connectMQTT() {
        executor.execute {
            try {
                Log.d(TAG, "Connecting to MQTT: $mqttServer")
                logToFile("MQTT connecting to $mqttServer...")
                mqttClient = MqttClient(mqttServer, mqttClientId, null)
                val commandTopic = "xvj/device/$deviceId/command"
                val options = MqttConnectOptions()
                options.isCleanSession = true
                options.connectionTimeout = 30
                options.keepAliveInterval = 60
                // Paho MQTT库的setWill是protected，暂不使用离线消息功能

                mqttClient?.connect(options)
                mqttClient?.subscribe(commandTopic, 0)

                // 订阅授权响应主题
                mqttClient?.subscribe(AUTH_TOPIC, 0)

                Log.d(TAG, "MQTT Connected! Subscribed to: $commandTopic")
                logToFile("MQTT Connected OK!")

                // 发送设备注册/认证请求
                registerDevice()

                mqttHandler.post {
                    binding.statusText?.text = "云端已连接"
                }

                // 设置消息回调
                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "MQTT Connection Lost: ${cause?.message}")
                        mqttHandler.post {
                            binding.statusText?.text = "连接断开,重连中..."
                        }
                        reconnectMQTT()
        checkForUpdate() // 检查更新
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        try {
                            val payload = String(message?.payload ?: ByteArray(0))
                            Log.d(TAG, "Received: $topic -> $payload")

                            // 处理授权响应
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

            } catch (e: Exception) {
                Log.e(TAG, "MQTT Connection Error: ${e.message}")
                logToFile("MQTT Error: ${e.message}")
                mqttHandler.post {
                    binding.statusText?.text = "连接失败: ${e.message}"
                }
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

                    mqttHandler.post {
                        if (authorized) {
                            binding.statusText?.text = "设备已授权: $message"
                            prefs.edit().putBoolean("authorized", true).apply()
                            // 先停止当前播放，再切换到folder01
                            stopPlayback()
                            playFolderVideos("01")
                        } else {
                            binding.statusText?.text = "设备未授权: $message"
                            // 未授权，停止工作
                            stopPlayback()
                            // 可以选择显示全屏提示或自动退出
                            showUnauthorizedAlert(message)
                        }
                    }
                }
                "deauthorize" -> {
                    // 被远程废掉
                    mqttHandler.post {
                        binding.statusText?.text = "设备已被废止"
                        prefs.edit().putBoolean("authorized", false).apply()
                        // 先停止当前播放
                        stopPlayback()
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
        // 停止当前播放并切换到欢迎视频
        stopPlayback()
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

    private fun reconnectMQTT() {
        mqttHandler.postDelayed({
            try {
                mqttClient?.close()
            } catch (e: Exception) {}
            connectMQTT()
        checkForUpdate() // 检查更新
        // 请求同步授权状态
        requestAuthSync()
        }, 5000)
    }

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
                    // 本地播放 - 推送指定文件夹的文件
                    val folder = cmd.optString("folder", "01")
                    val filename = cmd.optString("filename", "")
                    if (filename.isNotEmpty()) {
                        playLocalVideo(folder, filename)
                    }
                }
                "push_file" -> {
                    // 接收文件推送
                    val folder = cmd.optString("folder", "01")
                    val filename = cmd.optString("filename", "")
                    val url = cmd.optString("url", "")
                    if (filename.isNotEmpty() && url.isNotEmpty()) {
                        downloadAndPlay(folder, filename, url)
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
                    syncFromCloud()
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
                    if (folderMappings != null) {
                        syncRoomMaterials(roomId, folderMappings)
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
                        // 验证URL来自可信服务器（使用startsWith防止绕过）
                        if (!url.startsWith("http://47.102.106.237") && !url.startsWith("https://47.102.106.237")) {
                            Log.w(TAG, "OTA: 拒绝不可信的APK URL: $url")
                            logToFile("OTA更新被拒绝：URL来源不明")
                            mqttHandler.post {
                                android.widget.Toast.makeText(this, "更新来源不明，已拒绝", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }

                        logToFile("收到OTA更新推送: $version")
                        // 用Handler在主线程显示Toast
                        mqttHandler.post {
                            try {
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
            binding.statusText?.text = "播放: $url"
        }
    }

    private fun playFromCloud(videoId: String) {
        Log.d(TAG, "Playing from cloud: $videoId")
        mqttHandler.post {
            binding.statusText?.text = "下载中: $videoId"
        }

        executor.execute {
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
                    binding.statusText?.text = "下载失败: ${e.message}"
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
        Log.d(TAG, "Playing local: ${videoFile.absolutePath}")

        if (!videoFile.exists()) {
            Log.e(TAG, "File not found: ${videoFile.absolutePath}")
            mqttHandler.post {
                binding.statusText?.text = "文件不存在: $folder/$filename"
            }
            return
        }

        mqttHandler.post {
            releasePlayer()
            player = ExoPlayer.Builder(this).build().apply {
                binding.playerView.player = this
                val mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(videoFile))
                setMediaItems(listOf(mediaItem))
                repeatMode = if (loopPlay) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                playWhenReady = true
                prepare()
            }
            binding.statusText?.text = "播放: $folder/$filename"
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
        mqttHandler.post {
            binding.statusText?.text = "下载中: $filename"
        }

        executor.execute {
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

                Log.d(TAG, "Download complete: ${localFile.absolutePath}")

                // 下载完成后播放
                mqttHandler.post {
                    binding.statusText?.text = "下载完成，播放: $filename"
                }
                playLocalVideo(folder, filename)

            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}")
                mqttHandler.post {
                    binding.statusText?.text = "下载失败: ${e.message}"
                }
            }
        }
    }

    private fun syncFromCloud() {
        mqttHandler.post {
            binding.statusText?.text = "同步中..."
        }

        executor.execute {
            try {
                val apiUrl = "http://47.102.106.237/api/materials"
                val connection = java.net.URL(apiUrl).openConnection()
                val response = connection.getInputStream().bufferedReader().readText()
                Log.d(TAG, "Materials: $response")

                mqttHandler.post {
                    binding.statusText?.text = "同步完成"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync error: ${e.message}")
                mqttHandler.post {
                    binding.statusText?.text = "同步失败"
                }
            }
        }
    }

    // 同步预设素材文件夹
    private fun syncPresetFolders(folders: org.json.JSONArray) {
        mqttHandler.post {
            binding.statusText?.text = "同步预设素材(${folders.length()}个文件夹)..."
        }

        executor.execute {
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

    // 同步房间素材到本地文件夹
    private fun syncRoomMaterials(roomId: String, folderMappings: org.json.JSONObject) {
        mqttHandler.post {
            binding.statusText?.text = "同步房间素材中..."
        }

        executor.execute {
            try {
                val downloadTasks = mutableListOf<Triple<String, String, String>>() // folderId, filename, url

                // 遍历文件夹映射
                for (i in 0 until folderMappings.length()) {
                    val folderId = folderMappings.names()?.getString(i) ?: continue
                    val materialIds = folderMappings.getJSONArray(folderId)

                    // 获取每个素材的URL
                    for (j in 0 until materialIds.length()) {
                        val materialId = materialIds.getString(j)
                        // TODO: 调用API获取素材URL
                        // 这里先假设素材URL可以从预设素材API获取
                    }
                }

                // 保存房间素材配置
                val prefsEditor = prefs.edit()
                prefsEditor.putString("current_room_id", roomId)
                prefsEditor.putString("room_folder_mappings", folderMappings.toString())
                prefsEditor.apply() // 异步写入

                Log.d(TAG, "Room materials config saved: room=$roomId, mappings=$folderMappings")

                // 开始下载并播放01文件夹
                downloadAndPlayFolder("01")

            } catch (e: Exception) {
                Log.e(TAG, "Room materials sync error: ${e.message}")
                mqttHandler.post {
                    binding.statusText?.text = "素材同步失败"
                }
            }
        }
    }

    // 下载并播放指定文件夹
    private fun downloadAndPlayFolder(folderId: String) {
        executor.execute {
            try {
                // 获取该文件夹的素材列表
                val mappingsStr = prefs.getString("room_folder_mappings", "{}")
                val mappings = org.json.JSONObject(mappingsStr)
                val materialIds = mappings.optJSONArray(folderId)

                if (materialIds == null || materialIds.length() == 0) {
                    Log.d(TAG, "文件夹 $folderId 没有素材")
                    return@execute
                }

                // 获取素材URL并下载
                val videoList = mutableListOf<File>()
                val folderDir = File(videoFolderPath, folderId)
                if (!folderDir.exists()) {
                    folderDir.mkdirs()
                }

                for (i in 0 until materialIds.length()) {
                    val materialId = materialIds.getString(i)
                    // TODO: 从API获取素材详情（URL等）
                    // 这里简化处理
                    Log.d(TAG, "需要下载素材: $materialId 到文件夹 $folderId")
                }

                // 播放01文件夹
                mqttHandler.post {
                    binding.statusText?.text = "播放文件夹: $folderId"
                    playFolderVideos(folderId)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download folder error: ${e.message}")
            }
        }
    }

    // 播放本地文件夹的视频
    private fun playFolderVideos(folderId: String) {
        // 确保在主线程执行
        mqttHandler.post {
            // 先释放旧播放器
            releasePlayer()

            // 确保文件夹存在
            val folderDir = File(videoFolderPath, folderId)
            if (!folderDir.exists()) {
                folderDir.mkdirs()
            }

            if (!folderDir.exists() || !folderDir.isDirectory) {
                binding.statusText?.text = "无法创建文件夹: $folderId"
                return@post
            }

            val videos = folderDir.listFiles()?.filter {
                it.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "webm")
            }?.sortedBy { it.name } ?: return@post

            if (videos.isEmpty()) {
                binding.statusText?.text = "文件夹为空: $folderId"
                return@post
            }

            videoList.clear()
            videoList.addAll(videos)

            if (videoList.isNotEmpty()) {
                // 直接创建新播放器播放
                player = ExoPlayer.Builder(this@MainActivity).build().apply {
                    binding.playerView.player = this
                    val mediaItems = videoList.map { MediaItem.fromUri(android.net.Uri.fromFile(it)) }
                    setMediaItems(mediaItems)
                    repeatMode = Player.REPEAT_MODE_ALL
                    playWhenReady = true
                    prepare()
                }
                binding.statusText?.text = "播放文件夹$folderId: ${videoList.size}个视频"
            }
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
        releasePlayer()
        binding.statusText?.text = "已停止"
    }

    private fun releasePlayer() {
        player?.release()
        player = null
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
        releasePlayer()
        // 停止定时器并发送离线状态
        stopStatusTimer()
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) {}
        executor.shutdown()
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
                                // 验证filepath是相对路径（防止绝对路径绕过）
                                if (filepath.startsWith("/") || filepath.startsWith("http")) {
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
        // 安全校验：验证URL来源（使用startsWith防止绕过）
        if (!apkUrl.startsWith("http://47.102.106.237") && !apkUrl.startsWith("https://47.102.106.237")) {
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

                // MD5校验（如果提供了MD5）
                val finalApkFile = apkFile  // 捕获最终变量供lambda使用
                if (!expectedMd5.isNullOrEmpty()) {
                    val actualMd5 = apkFile.inputStream().use { input ->
                        val digest = java.security.MessageDigest.getInstance("MD5")
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            digest.update(buffer, 0, bytesRead)
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    if (actualMd5 != expectedMd5) {
                        logToFile("APK MD5校验失败！期望: $expectedMd5, 实际: $actualMd5")
                        mqttHandler.post {
                            android.widget.Toast.makeText(this, "更新校验失败，请重新尝试", android.widget.Toast.LENGTH_LONG).show()
                        }
                        apkFile.delete()
                        return@Thread
                    }
                    logToFile("APK MD5校验通过: $actualMd5")
                }

                // 使用对话框让用户确认安装
                val installFile = finalApkFile  // 传递文件引用到Dialog
                mqttHandler.post {
                    try {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("更新已下载")
                            .setMessage("版本: $version\n点击确定开始安装")
                            .setPositiveButton("确定") { _, _ ->
                                // 启动安装
                                val apkUri = androidx.core.content.FileProvider.getUriForFile(
                                    this, "${packageName}.fileprovider", installFile
                                )
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(intent)
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
                    try {
                        android.widget.Toast.makeText(this, "更新下载失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    } catch(e: Exception) {}
                }
            }
        }.start()
    }
}
