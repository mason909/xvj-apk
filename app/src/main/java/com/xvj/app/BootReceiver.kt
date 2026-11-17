package com.xvj.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机落屏：重启后直接进 XVJ，不停在系统"选择主屏幕"上。
 *
 * 2026-11-17 台架实况：重启后屏上是 ResolverActivity（选择主屏幕），系统里根本没有默认桌面
 * 记录 —— 触屏机没键鼠，这一下就是开不了机。根因不是 App 没自启，而是没有任何东西保证
 * "XVJ 是 HOME"：本文件此前是个只有一行注释的空壳，Manifest 里的 receiver 指向不存在的类，
 * 开机广播到了也只是 ClassNotFoundException。
 *
 * 这里做两件事：先把默认桌面设回自己，再带 MAIN+HOME 类目显式起画面。都走 root ——
 * 开机时从后台起 Activity 本来被 Android 10+ 拦着，量产 root 盒子用 su 才走得通；
 * 没有 root 的机器交给系统默认桌面解析（装机流程负责设好），不弹框、不重试。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // 只对 debuggable 盒子动手：零售机型的 su 会弹授权框，开机就挂在屏上
        if (Build.TYPE != "userdebug" && Build.TYPE != "eng") return
        val pkg = context.packageName
        // 与 OTA 自启同一条形态：类目决定任务栈归属，裸 -n 会在 standard 栈另起实例（见 bringSelfByRoot）
        val sh = "cmd package set-home-activity $pkg/.MainActivity ; " +
            "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $pkg/.MainActivity"
        Thread {
            val proc = try {
                Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c", sh))
            } catch (e: Exception) {
                Log.w(TAG, "无 root，开机落屏交给系统默认桌面: ${e.message}")
                return@Thread
            }
            // su 若在等授权框，别让这条线程永远吊着：到点直接杀掉，画面由系统桌面兜底
            Thread { Thread.sleep(15_000); proc.destroy() }.start()
            try {
                Log.i(TAG, "开机落屏 su code=${proc.waitFor()}")
            } catch (e: Exception) {
                Log.w(TAG, "开机落屏失败: ${e.message}")
            }
        }.start()
    }

    companion object {
        private const val TAG = "XVJBoot"
    }
}