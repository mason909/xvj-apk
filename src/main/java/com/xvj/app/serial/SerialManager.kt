package com.xvj.app.serial

import android.content.Context
import android_serialport_api.SerialPort
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * XVJ 串口通信管理器
 * 支持 RS485 通信
 * 用于连接灯光控制器、点歌面板等外设
 */
class SerialManager(private val context: Context) {

    private var serialPort: SerialPort? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private val executor = Executors.newSingleThreadExecutor()
    
    private var currentPath: String = "/dev/ttyUSB0"
    private var currentBaudrate: Int = 9600
    
    var onDataReceived: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    companion object {
        // 常用串口路径
        val COMMON_PATHS = listOf(
            "/dev/ttyUSB0",    // USB转串口
            "/dev/ttyUSB1",
            "/dev/ttyUSB2",
            "/dev/ttyS0",     // 主板串口
            "/dev/ttyS1",
            "/dev/ttyAMA0",   // 嵌入式串口
            "/dev/ttyAMA1",
            "/dev/ttysWK0",  // 某些RK3588设备
            "/dev/serial0"    // 新版Android设备
        )
        
        // 常用波特率
        val COMMON_BAUDRATES = listOf(
            9600,   // 标准波特率
            19200,
            38400,
            57600,
            115200,
            230400,
            460800,
            921600
        )
    }
    
    /**
     * 打开串口
     */
    fun open(path: String = currentPath, baudrate: Int = currentBaudrate): Boolean {
        return try {
            serialPort = SerialPort(File(path), baudrate, 0)
            inputStream = serialPort?.inputStream
            outputStream = serialPort?.outputStream
            
            currentPath = path
            currentBaudrate = baudrate
            
            // 开始读取数据
            startReading()
            
            true
        } catch (e: Exception) {
            onError?.invoke("打开串口失败: ${e.message}")
            false
        }
    }
    
    /**
     * 关闭串口
     */
    fun close() {
        try {
            inputStream?.close()
            outputStream?.close()
            serialPort?.close()
        } catch (e: Exception) {
            onError?.invoke("关闭串口失败: ${e.message}")
        } finally {
            inputStream = null
            outputStream = null
            serialPort = null
        }
    }
    
    /**
     * 发送数据
     */
    fun send(data: ByteArray): Boolean {
        return try {
            outputStream?.write(data)
            outputStream?.flush()
            true
        } catch (e: Exception) {
            onError?.invoke("发送失败: ${e.message}")
            false
        }
    }
    
    /**
     * 发送字符串
     */
    fun sendString(text: String): Boolean {
        return send(text.toByteArray(Charsets.UTF_8))
    }
    
    /**
     * 发送十六进制字符串
     * 如: "A5 03 01 FF"
     */
    fun sendHex(hex: String): Boolean {
        val cleanHex = hex.replace(" ", "").replace("\n", "")
        if (cleanHex.length % 2 != 0) return false
        
        val data = ByteArray(cleanHex.length / 2)
        for (i in data.indices) {
            val byteStr = cleanHex.substring(i * 2, i * 2 + 2)
            data[i] = byteStr.toInt(16).toByte()
        }
        return send(data)
    }
    
    /**
     * 发送Modbus RTU命令
     */
    fun sendModbus(deviceId: Int, functionCode: Int, address: Int, value: Int): Boolean {
        // 构建Modbus RTU帧
        val data = ByteArray(8)
        data[0] = deviceId.toByte()      // 设备地址
        data[1] = functionCode.toByte() // 功能码
        data[2] = (address shr 8).toByte() // 寄存器地址高字节
        data[3] = (address and 0xFF).toByte() // 寄存器地址低字节
        data[4] = (value shr 8).toByte()  // 值高字节
        data[5] = (value and 0xFF).toByte() // 值低字节
        
        // 计算CRC16
        val crc = crc16(data, 6)
        data[6] = (crc and 0xFF).toByte()
        data[7] = (crc shr 8).toByte()
        
        return send(data)
    }
    
    /**
     * 读取数据线程
     */
    private fun startReading() {
        executor.execute {
            val buffer = ByteArray(1024)
            while (serialPort != null) {
                try {
                    val len = inputStream?.read(buffer) ?: -1
                    if (len > 0) {
                        val data = buffer.copyOf(len)
                        onDataReceived?.invoke(data)
                    }
                    Thread.sleep(10)
                } catch (e: Exception) {
                    if (serialPort != null) {
                        onError?.invoke("读取失败: ${e.message}")
                    }
                    break
                }
            }
        }
    }
    
    /**
     * CRC16计算 (Modbus)
     */
    private fun crc16(data: ByteArray, length: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                if ((crc and 0x0001) != 0) {
                    crc = (crc shr 1) xor 0xA001
                } else {
                    crc = crc shr 1
                }
            }
        }
        return crc
    }
    
    /**
     * 检查串口是否存在
     */
    fun checkPort(path: String): Boolean {
        return File(path).exists()
    }
    
    /**
     * 自动检测可用串口
     */
    fun autoDetect(baudrate: Int = 9600): String? {
        for (path in COMMON_PATHS) {
            if (checkPort(path)) {
                if (open(path, baudrate)) {
                    return path
                }
            }
        }
        return null
    }
    
    /**
     * 十六进制转字节数组
     */
    static fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "")
        val data = ByteArray(cleanHex.length / 2)
        for (i in data.indices) {
            data[i] = cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return data
    }
    
    /**
     * 字节数组转十六进制
     */
    static fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }
}
