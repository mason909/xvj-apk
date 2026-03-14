package com.xvj.app.hdmi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import java.io.File

/**
 * XVJ HDMI输入管理器
 * 支持HDMI信号采集和播放
 */
class HdmiManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null
    private var currentSurface: Surface? = null
    
    private val handler = Handler(Looper.getMainLooper())
    
    companion object {
        private const val TAG = "HdmiManager"
        
        // HDMI输入相关Intent
        const val ACTION_HDMI_IN = "android.intent.action.HDMI_IN_SIGNAL"
        const val EXTRA_HDMI_IN_ENABLED = "android.intent.extra.HDMI_IN_ENABLED"
        
        // 常见HDMI输入路径 (RK3588)
        val HDMI_PATHS = listOf(
            "/dev/video0",    // 视频设备
            "/dev/video1",
            "/dev/video8",
            "/dev/media0",
            "/dev/media1"
        )
    }
    
    /**
     * HDMI输入状态回调
     */
    var onHdmiConnected: (() -> Unit)? = null
    var onHdmiDisconnected: (() -> Unit)? = null
    var onHdmiError: ((String) -> Unit)? = null
    
    /**
     * 检查HDMI输入是否可用
     */
    fun isHdmiAvailable(): Boolean {
        // 方法1: 检查系统属性
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val hdmiOn = android.os.SystemProperties.getBoolean("persist.sys.hdmiin.enable", false)
                if (hdmiOn) return true
            } catch (e: Exception) {}
        }
        
        // 方法2: 检查视频设备
        for (path in HDMI_PATHS) {
            if (File(path).exists()) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * 使用SurfaceView播放HDMI输入
     */
    fun playWithSurfaceView(surfaceView: SurfaceView) {
        this.surfaceView = surfaceView
        
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                currentSurface = holder.surface
                startHdmiPlayback()
            }
            
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Surface changed: ${width}x${height}")
            }
            
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopHdmiPlayback()
                currentSurface = null
            }
        })
    }
    
    /**
     * 使用TextureView播放HDMI输入
     */
    fun playWithTextureView(textureView: TextureView) {
        this.textureView = textureView
        
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                currentSurface = Surface(surface)
                startHdmiPlayback()
            }
            
            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
            
            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                stopHdmiPlayback()
                return true
            }
            
            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
        }
    }
    
    /**
     * 开始HDMI播放
     */
    private fun startHdmiPlayback() {
        try {
            // 使用MediaPlayer播放HDMI输入源
            // 某些设备支持直接通过URI播放HDMI
            mediaPlayer = MediaPlayer().apply {
                // 尝试设置HDMI作为源
                // 具体URI取决于设备厂商实现
                try {
                    setDataSource(context, Uri.parse("hdmi://0"))
                } catch (e: Exception) {
                    Log.w(TAG, "HDMI URI not supported, trying video device")
                    // 备用方案：使用本地视频文件模拟
                }
                
                setDisplay(currentSurface)
                
                setOnPreparedListener {
                    Log.d(TAG, "HDMI prepared")
                    start()
                    onHdmiConnected?.invoke()
                }
                
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "HDMI error: $what, $extra")
                    onHdmiError?.invoke("Error: $what")
                    true
                }
                
                setOnCompletionListener {
                    Log.d(TAG, "HDMI playback completed")
                }
                
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start HDMI failed: ${e.message}")
            onHdmiError?.invoke(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 停止HDMI播放
     */
    fun stopHdmiPlayback() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop HDMI failed: ${e.message}")
        } finally {
            mediaPlayer = null
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stopHdmiPlayback()
        surfaceView = null
        textureView = null
        currentSurface = null
    }
}

/**
 * HDMI输入接收器
 * 监听HDMI信号变化
 */
class HdmiReceiver : android.content.BroadcastReceiver() {
    
    companion object {
        private const val TAG = "HdmiReceiver"
        
        const val ACTION_HDMI_IN = "android.intent.action.HDMI_IN_SIGNAL"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_HDMI_IN -> {
                val connected = intent.getBooleanExtra("connected", false)
                if (connected) {
                    Log.d(TAG, "HDMI connected")
                } else {
                    Log.d(TAG, "HDMI disconnected")
                }
            }
        }
    }
    
    /**
     * 注册HDMI接收器
     */
    fun register(context: Context) {
        val filter = android.content.IntentFilter(ACTION_HDMI_IN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(this, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(this, filter)
        }
    }
    
    /**
     * 注销接收器
     */
    fun unregister(context: Context) {
        try {
            context.unregisterReceiver(this)
        } catch (e: Exception) {
            Log.e(TAG, "Unregister failed: ${e.message}")
        }
    }
}
