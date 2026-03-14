package com.xvj.app.serial

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*

/**
 * XVJ 串口服务
 * 后台运行串口通信
 */
class SerialService : LifecycleService() {

    private val binder = LocalBinder()
    private var serialManager: SerialManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val TAG = "SerialService"
        
        // 串口配置
        const val DEFAULT_PATH = "/dev/ttyUSB0"
        const val DEFAULT_BAUDRATE = 9600
        
        // LiveData
        val serialData = MutableLiveData<String>()
        val serialError = MutableLiveData<String>()
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): SerialService = this@SerialService
    }
    
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SerialService created")
    }
    
    /**
     * 初始化串口
     */
    fun init(path: String = DEFAULT_PATH, baudrate: Int = DEFAULT_BAUDRATE): Boolean {
        return try {
            serialManager = SerialManager(this)
            
            serialManager?.onDataReceived = { data ->
                val hex = SerialManager.bytesToHex(data)
                Log.d(TAG, "RX: $hex")
                serialData.postValue(hex)
            }
            
            serialManager?.onError = { error ->
                Log.e(TAG, error)
                serialError.postValue(error)
            }
            
            serialManager?.open(path, baudrate) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}")
            serialError.postValue(e.message ?: "Unknown error")
            false
        }
    }
    
    /**
     * 发送数据
     */
    fun send(data: ByteArray): Boolean {
        return serialManager?.send(data) ?: false
    }
    
    /**
     * 发送字符串
     */
    fun sendString(text: String): Boolean {
        return serialManager?.sendString(text) ?: false
    }
    
    /**
     * 发送十六进制
     */
    fun sendHex(hex: String): Boolean {
        val data = SerialManager.hexToBytes(hex)
        return serialManager?.send(data) ?: false
    }
    
    /**
     * 发送Modbus命令
     * @param deviceId 设备ID (1-247)
     * @param functionCode 功能码 (3=读保持寄存器, 6=写单个寄存器)
     * @param address 寄存器地址
     * @param value 写入的值(写操作时)
     */
    fun sendModbus(deviceId: Int, functionCode: Int, address: Int, value: Int = 0): Boolean {
        return serialManager?.sendModbus(deviceId, functionCode, address, value) ?: false
    }
    
    /**
     * 读取寄存器 (功能码0x03)
     */
    fun readRegisters(deviceId: Int, address: Int, count: Int): Boolean {
        return sendModbus(deviceId, 3, address, count)
    }
    
    /**
     * 写单个寄存器 (功能码0x06)
     */
    fun writeRegister(deviceId: Int, address: Int, value: Int): Boolean {
        return sendModbus(deviceId, 6, address, value)
    }
    
    /**
     * 关闭串口
     */
    fun close() {
        serialManager?.close()
        serialManager = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        close()
        serviceScope.cancel()
        Log.d(TAG, "SerialService destroyed")
    }
    
    /**
     * 主动检测可用串口
     */
    fun autoDetect(): String? {
        return serialManager?.autoDetect(DEFAULT_BAUDRATE)
    }
}
