package com.xvj.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.RandomAccessFile

/**
 * 【A-17】保活：主进程被 lmkd 收走之后，把画面自己拉回来。
 *
 * 为什么要单独一个进程：主进程里再起什么 Service 都跟 MainActivity 同生共死，被杀时一起没；
 * 只有 `android:process=":keepalive"` 才能在主进程没了之后还活着，去把 MainActivity 重新叫起来。
 * 现场的症状正是"进程一死，MQTT 跟着死，远程连 reopen_desktop 都发不进来，只能人到箱子前点图标"。
 *
 * 判活靠文件心跳而不是进程查询：跨进程拿不到对方的内存状态，`getRunningAppProcesses` 在
 * 新系统上也不可靠。主进程每 30s 心跳时把 `alive` 的 mtime 碰一下（见 [MainActivity.sendStatus]），
 * 超过 [STALE_MS] 没碰就认定它死了 —— 两拍余量，正常 GC 卡顿不会误判。
 *
 * 退出到桌面期间必须闭嘴：那是【A-15】用户显式点的退出，不是崩溃；主进程会留下 `exited` 标记文件，
 * 这里看见了就不拉（否则退出两分钟屏上自己弹回画面）。标记只在跨进程可用的文件系统上可靠，
 * SharedPreferences 的 MODE_MULTI_PROCESS 早已废弃，不能用。
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    /** 已经排上延迟复活：等待期间不再重复排（检查每 60s 一拍，会反复看到同一具尸体） */
    private var revivePending = false
    private val check = object : Runnable {
        override fun run() {
            reviveIfStale()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 只在信标文件从没写过时补一个（第一次启动，主进程可能比这里起得晚）；
        // 本服务在主进程死后被系统重新拉起时绝不碰它 —— 那会把"已死"刷成"活着"，
        // 凭空把复活推迟两分钟（#228 现场就是这么被误判的）。
        if (!File(filesDir, ALIVE_FILE).exists()) touchAlive()
        goForeground()
        handler.postDelayed(check, CHECK_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reviveIfStale()
        // 被 lmkd 收走后要回来：AMS 按 sticky 重新拉起，这条就是现场的"自动复活"
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        reviveIfStale()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        super.onDestroy()
    }

    /**
     * 前台化只为把 adj 压到不被优先回收的档位。通知本身是 MIN 重要级、不可点开，
     * 现场不应看到它；Android 13+ 没给 POST_NOTIFICATIONS 时通知不显示，但前台服务照常生效。
     */
    private fun goForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "XVJ 画面守护", NotificationManager.IMPORTANCE_MIN)
                    )
                }
            }
            val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("XVJ")
                .setContentText("画面守护中")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
            // 两参版本即可：类型取自 manifest 的 foregroundServiceType，不必分版本传
            startForeground(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            // Android 12+ 从后台重启时前台化可能被拒（ForegroundServiceStartNotAllowedException）。
            // 这不致命：服务还活着，START_STICKY 会继续把它拉回来，只是 adj 差一档。
            Log.w(TAG, "前台化失败（继续以普通服务运行）: ${e.message}")
        }
    }

    private fun reviveIfStale() {
        if (revivePending) return
        if (File(filesDir, EXIT_MARKER).exists()) return
        val seen = File(filesDir, ALIVE_FILE)
        val now = System.currentTimeMillis()
        if (seen.exists() && now - seen.lastModified() < STALE_MS) return
        val ageSec = if (seen.exists()) (now - seen.lastModified()) / 1000 else -1

        // 连杀退避（#228）：主进程刚被拉起来又很快没心跳，说明内存还没缓过来，立刻再拉只会把
        // codec2 里没归还的 dma-buf 再叠一层（现场 8 轮连杀后累计 2.25GB，越杀越起不来）。
        // 次数存 prefs：本进程自己也被 lmkd 收走过，内存里的计数活不过一次击杀。
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        var streak = sp.getInt(KEY_STREAK, 0)
        val last = sp.getLong(KEY_LAST_REVIVE, 0)
        // 主进程在上次复活后安稳活过 5 分钟 ⇒ 那轮连杀已经过去，清零重来
        if (last > 0 && now - last > STREAK_RESET_MS) streak = 0
        val delayMs = when (streak) {
            0 -> FIRST_DELAY_MS
            1 -> 20_000L
            2 -> 40_000L
            else -> 60_000L
        }

        revivePending = true
        appendLog("保活：主进程心跳已停 ${if (ageSec >= 0) "${ageSec}s" else "（信标不存在）"}，判定已死，" +
            "${delayMs / 1000}s 后拉起（连续第 ${streak + 1} 次）" + lastLogHint())
        handler.postDelayed({ doRevive(streak) }, delayMs)
    }

    private fun doRevive(previousStreak: Int) {
        revivePending = false
        // 等待期间用户点了退出：那是显式动作，不拉
        if (File(filesDir, EXIT_MARKER).exists()) return
        try {
            // 带 HOME 类目：与同进程双实例去重（liveInstance 收摊）同一条入口，起错了栈也会被收掉
            startActivity(Intent(this, MainActivity::class.java).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            touchAlive()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_STREAK, previousStreak + 1)
                .putLong(KEY_LAST_REVIVE, System.currentTimeMillis())
                .apply()
            appendLog("保活：已拉起画面（连续第 ${previousStreak + 1} 次）")
            Log.w(TAG, "主进程超过 ${STALE_MS / 1000}s 没心跳，已尝试拉起画面")
        } catch (e: Exception) {
            revivePending = false
            appendLog("保活：拉起画面失败: ${e.message}")
            Log.w(TAG, "拉起画面失败: ${e.message}")
        }
    }

    /**
     * 死亡现场写进与主进程同一份 xvj.log（格式一致，服务端日志页直接能看）。
     * 主进程死前的最后一条日志对定位 "被杀前在干什么" 最关键 —— exit-info 只给得出
     * "SIGNALED/TRIM EMPTY"，给不出当时正在重建还是正在起解码器。
     */
    private fun appendLog(msg: String) {
        try {
            PrintWriter(FileWriter(File(filesDir, "xvj.log"), true)).use {
                it.println("${System.currentTimeMillis()} WARN APP KEEPALIVE $msg")
            }
        } catch (e: Exception) {
            Log.w(TAG, "日志写入失败: ${e.message}")
        }
    }

    /** xvj.log 的最后一条日志（截 160 字符）：读尾 4KB 就够，别把 2MB 日志整个读进内存 */
    private fun lastLogHint(): String = try {
        val f = File(filesDir, "xvj.log")
        if (!f.exists() || f.length() == 0L) "" else RandomAccessFile(f, "r").use { raf ->
            val len = minOf(4096L, raf.length())
            raf.seek(raf.length() - len)
            val buf = ByteArray(len.toInt())
            raf.readFully(buf)
            val last = String(buf, Charsets.UTF_8).trimEnd().substringAfterLast('\n').trim()
            if (last.isEmpty()) "" else "，末条日志：${last.takeLast(160)}"
        }
    } catch (e: Exception) {
        ""
    }

    private fun touchAlive() {
        try {
            val f = File(filesDir, ALIVE_FILE)
            if (!f.exists()) f.createNewFile()
            f.setLastModified(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "心跳文件写入失败: ${e.message}")
        }
    }

    companion object {
        /** 主进程存活信标，mtime 即最后心跳时刻；由 [MainActivity.sendStatus] 每 30s 碰一次 */
        const val ALIVE_FILE = "alive"

        /** 退出到桌面期间的静默标记（见类注释），只有主进程写 */
        const val EXIT_MARKER = "exited"

        private const val TAG = "KeepAlive"
        private const val CHANNEL_ID = "xvj_keepalive"
        private const val NOTIFICATION_ID = 1001
        private const val CHECK_INTERVAL_MS = 60_000L

        /** 心跳 30s 一拍，两拍没见才判死 */
        private const val STALE_MS = 120_000L

        // 连杀退避：第一次 10s，之后翻倍封顶 60s；主进程安稳活过 5 分钟清零（见 reviveIfStale）
        private const val FIRST_DELAY_MS = 10_000L
        private const val STREAK_RESET_MS = 5 * 60_000L
        private const val PREFS = "keepalive"
        private const val KEY_STREAK = "revive_streak"
        private const val KEY_LAST_REVIVE = "last_revive_at"
    }
}
