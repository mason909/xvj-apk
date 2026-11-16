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
    private val check = object : Runnable {
        override fun run() {
            reviveIfStale()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 主进程可能比这里起得晚；先自己碰一下，免得第一次检查就把"文件还不存在"判成已死
        touchAlive()
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
        if (File(filesDir, EXIT_MARKER).exists()) return
        val seen = File(filesDir, ALIVE_FILE)
        if (seen.exists() && System.currentTimeMillis() - seen.lastModified() < STALE_MS) return
        try {
            // 带 HOME 类目：与同进程双实例去重（liveInstance 收摊）同一条入口，起错了栈也会被收掉
            startActivity(Intent(this, MainActivity::class.java).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            touchAlive()
            Log.w(TAG, "主进程超过 ${STALE_MS / 1000}s 没心跳，已尝试拉起画面")
        } catch (e: Exception) {
            Log.w(TAG, "拉起画面失败: ${e.message}")
        }
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
    }
}
