package com.xvj.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
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
    private var videoFolderPath: String = "/storage/emulated/0"
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
        const val VERSION_CODE = 3
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
        
        // 强制横屏
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        
        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // 隐藏系统UI - 纯播放模式
        hideSystemUI()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        logToFile("=== XVJ App Starting ===")

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
        
        // 扫描本地视频
        scanLocalVideos()
        
        // 播放本地视频（如果有）
        if (videoList.isNotEmpty()) {
            startPlayback()
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
        // 本地配置
        videoFolderPath = prefs.getString("video_folder", "/storage/emulated/0") ?: "/storage/emulated/0"
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
        mqttHandler.post {
            try {
                // 播放欢迎视频循环
                playWelcomeVideo()
                Log.w(TAG, "UNAUTHORIZED: $message")
            } catch (e: Exception) {}
        }
    }
    
    /**
     * 播放欢迎视频（循环）
     */
    private fun playWelcomeVideo() {
        try {
            releasePlayer()
            player = ExoPlayer.Builder(this).build().apply {
                binding.playerView.player = this
                val mediaItem = MediaItem.fromUri("asset://welcome.mpk")
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
    
    private fun reconnectMQTT() {
        mqttHandler.postDelayed({
            try {
                mqttClient?.close()
            } catch (e: Exception) {}
            connectMQTT()
        checkForUpdate() // 检查更新
        }, 5000)
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
                                val apkUrl = "$APK_URL${json.getString("filepath")}"
                                logToFile("发现新版本: $serverVersion, 正在下载...")
                                downloadAndInstall(apkUrl, serverVersion)
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
     * 下载并安装APK
     */
    private fun downloadAndInstall(apkUrl: String, version: String) {
        try {
            val url = java.net.URL(apkUrl)
            val connection = url.openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            val apkFile = File(cacheDir, "xvj-update-$version.apk")
            
            val input = connection.getInputStream()
            val output = java.io.FileOutputStream(apkFile)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
            }
            output.close()
            input.close()
            
            logToFile("APK下载完成: ${apkFile.absolutePath}")
            
            // 安装APK
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            intent.setDataAndType(
                android.net.Uri.fromFile(apkFile),
                "application/vnd.android.package-archive"
            )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            logToFile("下载APK失败: ${e.message}")
        }
    }
}
