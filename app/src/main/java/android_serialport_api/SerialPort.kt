package android_serialport_api

import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.SyncFailedException

/**
 * Android串口API
 * 基于原生Linux串口驱动
 */
class SerialPort {

    private val mFileDescriptor: FileDescriptor
    private val mFileInputStream: FileInputStream
    private val mFileOutputStream: FileOutputStream

    /**
     * 打开串口
     * @param device 串口设备文件
     * @param baudrate 波特率
     * @param flags 标志位
     */
    @Throws(SecurityException::class, IOException::class)
    constructor(device: File, baudrate: Int, flags: Int) {
        
        // 检查权限
        if (!device.canRead() || !device.canWrite()) {
            try {
                // 尝试通过Runtime获取权限
                val p = Runtime.getRuntime().exec("su")
                val cmd = "chmod 666 ${device.absolutePath}\n"
                p.outputStream.write(cmd.toByteArray())
                p.outputStream.flush()
                p.outputStream.close()
                p.waitFor()
            } catch (e: Exception) {
                throw SecurityException()
            }
            
            if (!device.canRead() || !device.canWrite()) {
                throw SecurityException()
            }
        }
        
        // 打开串口设备
        mFileDescriptor = open(device.absolutePath, flags)
        
        if (mFileDescriptor == null) {
            throw IOException("Cannot open port")
        }
        
        // 配置波特率
        native_setup(mFileDescriptor, baudrate, flags)
        
        mFileInputStream = FileInputStream(mFileDescriptor)
        mFileOutputStream = FileOutputStream(mFileDescriptor)
    }

    // 传统构造函数
    @Throws(SecurityException::class, IOException::class)
    constructor(device: File, baudrate: Int) : this(device, baudrate, 0)

    fun getInputStream(): FileInputStream = mFileInputStream
    fun getOutputStream(): FileOutputStream = mFileOutputStream

    @Synchronized
    fun close() {
        try {
            mFileInputStream.close()
            mFileOutputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        try {
            native_close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val TAG = "SerialPort"
        
        // 波特率对照表
        val BAUDRATES = mapOf(
            0 to 0,
            50 to 50,
            75 to 75,
            110 to 110,
            134 to 134,
            150 to 150,
            200 to 200,
            300 to 300,
            600 to 600,
            1200 to 1200,
            1800 to 1800,
            2400 to 2400,
            4800 to 4800,
            9600 to 9600,
            19200 to 19200,
            38400 to 38400,
            57600 to 57600,
            115200 to 115200,
            230400 to 230400,
            460800 to 460800,
            921600 to 921600
        )
        
        private var sDevPath: String? = null
        private var sFd: FileDescriptor? = null

        // JNI方法
        private external fun open(path: String, flags: Int): FileDescriptor
        private external fun native_setup(fd: FileDescriptor, baudrate: Int, flags: Int)
        @Throws(IOException::class)
        private external fun native_close()

        init {
            System.loadLibrary("serial_port")
        }
    }
}

/**
 * 串口符号链接管理器
 * 用于处理串口设备名称
 */
object SerialPortFinder {
    
    /**
     * 获取所有可用的串口设备
     */
    fun getAllDevices(): Array<String> {
        val devices = mutableListOf<String>()
        
        // 常见串口路径
        val paths = arrayOf(
            "/dev/ttyUSB*",
            "/dev/ttyS*", 
            "/dev/ttyAMA*",
            "/dev/ttysWK*",
            "/dev/serial*"
        )
        
        for (path in paths) {
            val dir = File(path.substring(0, path.lastIndexOf("/")))
            val prefix = path.substring(path.lastIndexOf("/") + 1).replace("*", "")
            
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.name.startsWith(prefix)) {
                        devices.add(file.absolutePath)
                    }
                }
            }
        }
        
        return devices.toTypedArray()
    }
    
    /**
     * 获取设备驱动名称
     */
    fun getDeviceDriver(devicePath: String): String? {
        return try {
            val driver = File("/sys/class/tty/$devicePath/driver")
            if (driver.exists()) {
                File(driver.name).absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
