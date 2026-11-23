/**
 * XVJ 云控系统 - Android APP (MainActivity)
 * =========================================
 * 架构：Kotlin + AndroidX + ExoPlayer + MQTT
 *
 * 【代码索引】搜索 "【A-XX】" 快速定位
 * ─────────────────────────────────────────────────
 * 【A-01】生命周期 & 初始化（onCreate / loadConfig / setDebugMode / applyDebugUi / onResume / onPause / onDestroy）
 * 【A-02】MQTT 连接（connectMQTT / onMqttConnected / reconnectMQTT）
 * 【A-03】设备注册 & 授权（registerDevice / sendStatus / handleAuthResponse）
 * 【A-04】MQTT 消息处理 & 分发（handleCommand → when(action)）
 * 【A-06】素材同步（requestRoomMaterialSync / runRoomSyncLoop / doRoomMaterialSync / fetchRoomMaterials /
 *         syncSceneFolders / syncFolderWithIds / runDownloadPlan / downloadWithETag / cachedMd5 /
 *         clearMaterialCache / deleteMaterialFile）
 * 【A-07】窗口配置 & 渲染（applySceneConfigs / scenesFingerprint / applyLiveWindowUpdate / createWindowView / playFolderInWindow；
 *         同格 ≥2 扇共享一路解码那层见 enabledWindowsSorted / createShareGroup / refreshShareGroup / destroyShareGroups，
 *         GL 画布本体在 ShareCanvasView.kt，单窗与共享那一路的日志共用 armWindowLogger）
 * 【A-08】窗口释放（releaseAllWindows）
 * 【A-09】OTA 自更新（checkForUpdate / isTrustedApkUrl / isValidFilepath / verifyApkSignature / downloadAndInstall /
 *         installApk → silentInstallReady + silentInstall + execAsRoot，或退到 installViaSystemInstaller / retryPendingInstall）
 * 【A-10】系统 UI（hideSystemUI）
 * 【A-11】工具方法（logToFile / sha256 / generateDeviceFingerprint / calculateMd5）
 * 【A-12】播放设置（playSettingsOf / playSettingsJson）
 * 【A-13】大屏试播（startGroupPreview / previewTarget / armPreview / endPreview / endPreviewCovering /
 *         abortAllPreviews；账按**格子**记，接管那一刻的正片位置抄进 PreviewState）
 * 【A-14】墙板 485 链路（applyWallPanelEnabled / applyWallPanelFields / wallPanelPath / wallPanelBaud /
 *         wallPanelSerialBits / handleWallPanelEvent / applyPanelSource / panelFolderFor /
 *         startWallPanelLearn / publishFrameSnapshot；
 *         收帧解析与 termios 在 serial/WallPanelLink.kt，帧台账与学习表在 serial/WallPanelFrames.kt，
 *         帧族分帧在 serial/WallPanelParser.kt）
 * 【A-14d】DMX512 副板链路（applyDmxEnabled / applyDmxFields / dmxPath / dmxBaud / handleDmxFrame；
 *         通道 N ↔ 文件夹 NN，值变动即"按了一次"，与 485 共用 applyPanelSource；
 *         收帧与 termios 在 serial/DmxLink.kt，帧头解析在 serial/DmxFrameParser.kt）
 * 【A-15】退出到桌面与唤回（setExited / findDesktop / bringSelfByRoot / rebuildAfterExit +
 *         dispatchTouchEvent 左键连点 5 次；入口两条：云端 action:'exit_desktop' / 'reopen_desktop'，
 *         本机连点只管"退出"，回来靠桌面图标 / HOME 键 / 云端唤回）
 * 【A-16】实时截图（云端令牌回传，见 captureAndUploadScreenshot）
 * 【A-17】LOGO 叠加层（config.logo；applyLogoField / refreshLogoLayer / buildLogoMembers /
 *         startLogoPlayer / startLogoDownload；一路解码 + ShareCanvasView(lumaKey) 按窗画入）
 * （A-05 已随"单播放器点播"旧链路一起删除，编号保留空位）
 * ─────────────────────────────────────────────────
 * 
 * 核心流程：
 *   1. MQTT 连接（持久连接，复用于所有通信；command/auth 两个订阅都订 QoS 1）
 *   2. 设备注册 → 服务端审核 authorized=1 → 回 xvj/auth/response（带 scenes + debug + rev）
 *   3. sync_room_materials 命令 → requestRoomMaterialSync 按房间去重后进对账：
 *      /api/room-materials-v2/{roomId} 拉全量清单
 *      → 按 Scene A/B 逐文件夹对账（多的删、缺的/变的进下载计划；本地文件在且没有可比对凭据时按字节数跳过，见【size 兜底】）
 *      → runDownloadPlan 串行下载整批计划，按云端下发的字节数刷窗口正中的进度条（仅调试模式可见）
 *   4. 播放循环：applySceneConfigs 按 scenes 配置渲染窗口（结构与屏上一致则直接跳过，见幂等闸门）
 *
 * 配置版本对账契约（rev，改同步前必读）：
 *   - rev = 服务端对房间 scenes 算的 12 位 sha1 指纹，随 auth_result / sync_room_materials / update_windows 下发；
 *     本机只在"这份配置真的落地"之后才记进 appliedRev（prefs 存一份，冷启动第一次心跳就能报）
 *   - 心跳 xvj/device/{id}/status 带 rev + syncing；服务端 reconcileDeviceRev 拿它和房间真值比，
 *     落后就补发同步（同一设备 3 分钟最多一次，syncing=true 时跳过）—— 丢消息、离线改配置全靠这条兜底
 *   - 本机不自己算 rev（算不出服务端那套序列化口径），只负责"落地了哪一版就回传哪一版"
 *   - 【唯一的例外】试播 preview_material / preview_stop 完全不参与这套契约：它不改配置、不带 rev、
 *     不落 prefs，所以服务端也不会为它补发 —— 命令丢了就是没播。它的全部生命周期在内存里，
 *     设备重启或任何一次窗口重建都会把它抹掉，画面回到 config 说的样子（见 armPreview）
 * 
 * 房间调试模式（debug_mode）：
 *   - 写入只经 setDebugMode()，三个来源：auth_result 与 sync_room_materials 的 debug 字段、
 *     set_debug 指令；HTTP 侧从不设置它（旧注释里的 /api/room-materials 已废弃）
 *   - SharedPreferences 持久化，重启后由 loadConfig() 读回并 applyDebugUi() 刷一次
 *   - true 时 logToFile() 通过 xvj/device/{id}/log 上报到 device_logs 表
 *     （唯一例外：action=PREVIEW 的试播日志恒上报 —— 试播本身不挂 debug，设备侧拒绝得有回执）
 *   - true 时整层"运维 UI"可见（applyDebugUi）：**每扇在播窗口正中**一份状态文字 + 素材同步进度条，
 *     没有窗口时退回整屏居中面板。关掉后大屏上只剩播放内容，那十几处 setStatus 调用不必各自判 debug——
 *     隐藏面板即可，文字照常更新；现场要排查就开调试
 * 
 * 窗口名浮层（window_names）：
 *   - 写入只经 setWindowNames()，两个来源：auth_result 与 sync_room_materials 的 window_names 字段、
 *     set_window_names 指令（管理台打开「窗口配置」置 true、关掉置 false）
 *   - true 时每扇在播窗口正中常亮该窗口的 name（旁边没有状态文字时它就是正中那一条），
 *     与 debug 无关 —— 名字是改窗口时的对屏辅助，不是运维信息；SharedPreferences 持久化
 * 
 * 测试背景浮层（window_testcard）：
 *   - 写入只经 setWindowTestcard()，来源同上（window_testcard 字段 / set_window_testcard 指令），
 *     与窗口名同开同关：管理台打开「窗口配置」时一起置 true，关掉一起置 false
 *   - true 时每扇在播窗口拉伸铺满测试卡（随窗口矩形变形，不保比例），画在名字/状态面板下面：
 *     点歌窗用网格卡 testcard_hdmi.png（对角线+十字中线对几何），其余窗用彩条卡 testcard.jpg —— 改窗口时每扇窗
 *     的框子和内容一目了然；关掉后图卡撤走，原素材继续播（不重建播放器、视频不中断）
 *   - 与 window_names 一样不进 debug 闸门、不进 rev；SharedPreferences 持久化
 *
 * LOGO 设置辅助网格（logo_grid）：
 *   - 写入只经 setLogoGrid()，来源同上（logo_grid 字段 / set_logo_grid 指令），
 *     管理台打开「LOGO设置」置 true、关掉置 false
 *   - true 时整屏垫一层等像素方格（格边长=屏高 1/12，横竖同像素数 ⇒ 落屏是真正的正方形），
 *     压在窗口之上、LOGO 层之下，用来肉眼判断 LOGO 在每扇窗里有没有被拉变形
 *   - 不进 debug 闸门、不进 rev；SharedPreferences 持久化，重连/同步载荷按云端正值自愈
 * 
 * 文件夹级播放设置（scenes[X].play_settings，三端共用一份口径，详见【A-12】）：
 *   - 每格只有 { order }：'seq' 顺序循环 | 'shuffle' 随机循环；全默认的格子云端不落键 —— 落了会让全库 rev 漂移一次，
 *     部署后每台在线设备都被对账判成"落后"补发同步、视频从头播
 *   - 表键带幕前缀（"A01"），本机按物理目录 id 查；老载荷的裸 "01" 也认（见 playSettingsOf）
 *   - **素材轮播的交接一律硬切**：叠化（第二路预卷 + alpha 淡入）整条链路已于 2026-11-12 删除
 *     （用户定调：现场先要普通切换稳，特效另立项再做）。存量载荷里残留的 dissolve / dissolve_ms
 *     键本机不再读，等于自动忽略，不必清库
 *   - 改设置不动映射 → 服务端发 update_windows 轻通道；但设置不在窗口 content 里，内容签名比不出它，
 *     所以本机另记 appliedWindowOrder + appliedPlaySettingsJson，真变了就判成结构变化回退全量重建（视频会从头播）
 *
 * 同格共享解码（任务 #175，改窗口渲染前必读）：
 *   - 同一格（folderId）挂了 ≥2 扇视频窗时，本机只起 **一路** ExoPlayer：解码输出落进一块离屏
 *     SurfaceTexture，由 ShareCanvasView 在 GL 线程按每扇窗自己的位置/尺寸/取景/旋转/镜像/透明度/亮度
 *     把同一张纹理各画一遍。窗口数不再线性放大解码开销 —— 6 扇同格实测散差 3.5s、混 4K 那路凝滞 4.8s，
 *     同步校正在第一次交接就熔断，就是这个结构问题
 *   - 只有这一种情形走这条路：单窗（含 1 扇窗的格子）链路逐字节照旧，不必担心回归
 *   - 组的基准窗 = 组内 ID 最小那扇（与 groupClock 同一口径），播放器记在 windowPlayers[leaderId]，
 *     成员窗没有播放器；因此不再有"多路解码互相对齐"这回事（散差按构造是 0），试播/墙板/日志都按 winShareGroup 换算成真实扇数
 *   - 代价：整组是一块画布（成员矩形并集），跨组 z 交错没了；音频只剩一路
 *
 * 素材文件校验契约（改同步前必读）：
 *   - 云端 materials.md5 存的是 **faststart 重封装之后** 的字节摘要（服务端上传时就地重写文件），
 *     设备下载到的也正是这份字节，所以下载成功一次 md5 就对齐了；
 *     不要把 md5 理解成"用户原始上传文件的 md5"，拿本地素材目录去比对会永远对不上
 *   - 一轮同步里 md5 判定先于 ETag：md5 不同才进 downloadWithETag，
 *     而它内部按 **相对素材根的路径** 取 If-None-Match（键口径见 materialCacheKey）—— 若服务端 ETag 没变而 md5 变了，
 *     那个条件 GET 会拿回 304 直接 return UNCHANGED，本地仍是旧文件，下一轮再报不一致（表现为"永远同步不完"）
 *   - 三把缓存键（ETag / Last-Modified / md5）都是这条相对路径（"scenea/01/x.mp4"），不是裸文件名：
 *     设备端认文件认的就是 <目录>/<文件名>，键不带目录时"同一素材 cp2p 到 01 和 02"这种常规操作
 *     会让两个目录互刷同一份缓存，症状同上（每轮都重下、进度条永远跑不完）。
 *     升级前的旧键（只有文件名）在 ETag/LM 上读时回落 + 写穿迁移，老设备不必整目录重下；
 *     md5 不做回落 —— 它是纯本地判定，借错值会让人误以为"本地已是最新"而跳过下载
 *   - 因此服务端就地替换素材文件时必须让 ETag/Last-Modified 一起变（nginx 自动重算，正常没问题）
 *   - ETag/Last-Modified 只在文件**流写完并且实到字节 == 云端 size** 之后才写回 prefs：中途断线不会
 *     留下"半截文件 + 已生效 ETag"这种永远修不好的组合。半截文件也不做 Range 续传——素材是同名就地
 *     替换的（faststart 重封装），旧前缀拼新字节必坏，下一轮一律从 0 重下
 * 
 * 重要约定：
 *   - 设备身份 = deviceId：首启用 fingerprint 播种后写进 SharedPreferences 就不再变，
 *     服务端不会改派 uuid（handleAuthResponse 里那段"换 uuid 重订阅"是死分支，见该函数注释）
 *   - 素材落盘在 getFilesDir() 下：无前缀键 "01" → filesDir/01，带幕前缀 "A01" → filesDir/scenea/01、
 *     "B01" → filesDir/sceneb/01（不是旧注释说的 filesDir/videos/xxx，那是单播放器时代的布局）
 *     卸载即清；filesDir/videos 是旧单播放器布局的遗留目录，已无代码引用
 *     folderId → 目录的解析只有 materialDirOf 一个入口（同步/删除/播放共用），folderId 与
 *     filename 都是云端/MQTT 原文，未过校验的路径一律拒绝，不做 File(...) 硬拼
 *   - 所有网络请求在下载线程执行，UI 更新 post 到 mqttHandler
 */

package com.xvj.app


import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.Gravity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import org.json.JSONArray
import com.xvj.app.databinding.ActivityMainBinding
import com.xvj.app.serial.DmxFrame
import com.xvj.app.serial.DmxLink
import com.xvj.app.serial.PanelAction
import com.xvj.app.serial.PanelSource
import com.xvj.app.serial.WallPanelBindings
import com.xvj.app.serial.WallPanelEvent
import com.xvj.app.serial.WallPanelFrameLedger
import com.xvj.app.serial.WallPanelHex
import com.xvj.app.serial.WallPanelLink
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// CI 固定 keystore（XVJ_DEBUG_KEYSTORE）证书 DER 的 SHA-256；改动签名必须同步更新，否则设备端 OTA 校验会拒绝安装
private const val EXPECTED_CERT_FINGERPRINT = "d2b08c51a0bcca1f2bc29f20fd8ff5f3927725a9ce3d8355b770ef03c5c960df"

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
    /** 单播放器（仅供未授权时的欢迎视频等 playerView 链路）；窗口播放用下面的 windowPlayers */
    private var player: ExoPlayer? = null

    // 配置项
    private val prefs by lazy { getSharedPreferences("xvj_prefs", MODE_PRIVATE) }
    // 素材根 = filesDir（内部存储，免权限）；物理目录形如 <root>/01、<root>/scenea/01
    private var videoFolderPath: String = ""
    private var loopPlay: Boolean = true

    // MQTT配置 - 云端地址
    private var mqttServer = "tcp://47.102.106.237:1883"
    private var mqttClientId = ""
    private var deviceId: String = ""

    // ========== 多窗口系统 ==========
    // A/B 两幕的窗口本来就是合并渲染的，每个窗口播什么由自己的 content 决定（没有"当前幕"状态位）。
    // 485 墙板走独立链路：WallPanelLink 收帧 → 帧指纹查学习表 → 重排跟随墙板的窗口
    // （见 applyPanelSource）。两路都在同一根 485 上，中控把点歌机和墙板的流量合并进来，
    // 所以"收到一帧"和"这一帧是什么意思"是两件事：前者进 frameLedger 上报，后者才动画面。
    private var wallPanelLink: WallPanelLink? = null
    /**
     * 本机最近一次**动了画面**的墙板/DMX 触发源；null = 这台机开机至今没收到过学过的帧
     * （跟随窗口退回各自的固定逻辑）。两种源：
     *   [PanelSource.Frame] 学习来的帧指纹，各幕按自己的绑定表各自解析出该切的格子；
     *   [PanelSource.Dmx]   副板通道 1 的值就是格子号（1~30，A/B 各切各的物理目录）。
     * 只在内存里，故意不落 prefs：重启后回到"没按过"，画面落在各窗自己的固定格 ——
     * 现场要的是可预测的开机画面，而不是上一次营业结束时的状态；也因此不用跟 rev 对账。
     * 读写都在主线程（墙板/DMX 事件由链路 post 到主线程，窗口应用也在主线程）。
     */
    private var panelSource: PanelSource? = null
    /** 上一次触发源落地时刻（elapsedRealtime）：同一帧在去抖窗口内只认一次，见 applyPanelSource */
    private var panelSourceAtMs = 0L
    /** DMX512 副板链路：与 485 是两条物理线，总闸/节点/速率各自下发（config.dmx_*） */
    private var dmxLink: DmxLink? = null
    /**
     * 上一帧 20 路通道值；null = 刚开链路还没收到过帧（首帧只当基线，不触发）。
     * 值变动（且新值非零）才算"按了一次"，落到 [PanelSource.Dmx] 与 485 共用同一套切格逻辑。
     * 调格只认 [DMX_FOLDER_CHANNEL] 这一路，其余通道照存不认（副板 20 路都可能变，基线得整帧对比）。
     */
    private var dmxPrev: IntArray? = null
    /** 收到的帧去重台账：心跳带回云端做排障可见，学习模式的候选列表也从它出（主线程读写） */
    private val frameLedger = WallPanelFrameLedger()
    /** 学习来的「帧 → 动作」表：房间 config.wallpanel_bindings 的内存视图，随载荷整体替换 */
    private var wallPanelBindings: WallPanelBindings = WallPanelBindings.EMPTY
    /** 学习窗截止时刻（elapsedRealtime）；> 现在 = 正在学习，帧快照额外单独上报，不等 30s 心跳 */
    private var learnUntilMs = 0L
    private var learnReportRunnable: Runnable? = null
    /** windowId -> ExoPlayer 实例（每个窗口独立播放器）*/
    private val windowPlayers = mutableMapOf<String, ExoPlayer>()
    /** windowId -> View 实例（窗口视图）*/
    private val windowViews = mutableMapOf<String, View>()
    /** windowId -> 内容签名（type|folderId|color|inputIndex|follow），实时更新时判定结构变化用 */
    private val windowContentSigs = mutableMapOf<String, String>()
    /** HDMI 窗口的占位文字（无信号/待授权提示），出流且有信号时隐藏；winId -> label */
    private val hdmiLabels = mutableMapOf<String, TextView>()
    /** HDMI IN 采集管线；第一次有 HDMI 窗口才建（没有 HDMI 的现场不占相机线程） */
    private var hdmiCapture: HdmiCapture? = null
    /** 相机权限只主动要一次：HOME 应用的系统弹窗会盖住整屏，能 root 静默授权就不弹 */
    private var hdmiPermAsked = false
    /**
     * folderKey（格子）-> 进行中的大屏试播（见 armPreview）。
     * 键是格子不是窗口：#175 之后一格只有一路解码，试播天然是"这一格"的事件，
     * 按窗口记会让云端点名的那扇（zIndex 最高，多半是共享组成员、没有播放器）打不中账。
     * 只活在内存里：设备重启、窗口重建都会自然结束它，试播因此不需要任何持久化，
     * 也不可能反过来污染配置 —— 这正是它可以完全绕开 rev 对账的原因。
     */
    private val previews = mutableMapOf<String, PreviewState>()
    /**
     * folderId -> 这一格的轮播清单。**同格的窗口共用一份，不是各建各的**：
     * 随机循环原来是每扇窗自己 shuffle，于是两扇播的是两套顺序 —— 严格同步在这种前提下
     * 永远不可能成立。清单是否在效按 URI 逐个比（见 sameItems），同步增删过文件自然作废。
     */
    private val folderPlaylists = mutableMapOf<String, List<MediaItem>>()
    /** 看门狗二次武装的上限：拿到真实片长后按 duration+15s 重排，最长不超过这个值（长片不该被一刀砍） */
    private val PREVIEW_HARD_CAP_MS = 30 * 60 * 1000L
    /**
     * 连点合并窗口：试播每换一只 4K↔1080p 素材都要重配一套解码缓冲，HAL 的缓冲池跨进程不还；
     * 短窗口内的后几次点击只留最后一次 —— 前几次本来也会被下一次 setMediaItems 打断，一帧都看不到。
     */
    private val PREVIEW_MERGE_MS = 500L
    /** 可用内存低于这条线（与冷启动闸门同一把尺）就别再开新试播：屏上如实说原因，比整机被 lmkd 连杀好 */
    private val PREVIEW_MIN_AVAIL_MB = 400
    /** 上一次真正接管的时间（uptime 毫秒），连点合并用 */
    private var lastPreviewArmAt = 0L
    /** 被合并窗口推到后面的那一次试播（同一时刻最多一个，后到替先到） */
    private var pendingPreview: Runnable? = null
    /**
     * 设备播放链认的视频扩展名 —— 三端那份表的设备侧（云端 server.js 的 VIDEO_EXT 同源，前端 _pvDevExtRe 同源）。
     * 同步比对、清空目录、播放列表、试播定位四处共用：写两份就会分裂成"同目录里一只算素材、一只算杂项"，
     * 表现是同步永远差一只、或试播报"本机没有这只素材"而文件明明在。
     */
    private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm")
    /** 兜底窗口 ID：scenes 里两幕都没有任何窗口时，applySceneConfigs 自动建这一个整屏（基准尺寸）SCENE_A 窗口*/
    private val DEFAULT_WINDOW_ID = "win_1"
    /** 窗口容器 FrameLayout（根视图）*/
    private val flSurface: FrameLayout? get() = binding.root as? FrameLayout
    /**
     * 退出位：true = 窗口已释放、原厂桌面顶在身前，但进程、MQTT 订阅和心跳都还活着（见【A-15】）。
     * 只在内存里，故意不落 prefs：退出是营业途中的一次收摊，不是配置状态 ——
     * 重启该回到开机画面，而不是回到"上次下班时停在桌面上"。
     * 读写都在主线程（命令由 mqttHandler post、手势由 dispatchTouchEvent 进来，两者都是主线程）。
     */
    private var exited = false
    /** 找不到别的桌面（或拉起失败）时的退化黑布；正常退到桌面时恒为 null */
    private var blackout: View? = null
    /**
     * 墙板「全关」盖布：与上面的退化黑布是两条命 —— 那个只在退桌面失败时盖，
     * 这个由墙板开关键开关（全关盖、全开撤）。播放不停，撤掉就回到原画面。
     * 状态只在内存里，重启/进程被杀都回到正常画面：黑屏是一次氛围动作，不是配置。
     */
    private var panelBlackout: View? = null
    private var panelBlackoutOn = false
    /** 界面是否在前台（onResume 起、onPause 落）：云端「唤回」据此决定自己顶回来还是等现场按键 */
    private var activityForeground = false
    /** 还在桌面身后时收到的唤回：先记账，回到前台那次 onResume 补建画面 */
    private var rebuildOnResume = false
    /** 左键连点手势：当前链已计到第几下 */
    private var tapChainCount = 0
    /** 上一次合格左键抬起的时刻（elapsedRealtime），超过间隔就重新起链 */
    private var tapChainAtMs = 0L
    /** 冷启动低内存闸门：第一次发现内存不足的时刻（elapsedRealtime），0 = 闸门没在等 */
    private var coldStartGateSince = 0L
    /** 冷启动闸门挂着的重试；新配置到来时换掉旧的，保证落地的是最新一份 */
    private var coldStartRetry: Runnable? = null
    /** 上一次整屏重建开始的时刻（elapsedRealtime），0 = 本进程还没重建过 */
    private var lastRebuildAtMs = 0L
    /** 被重建节流挂起的应用；新配置到来时换掉旧的，落地永远是最新一份 */
    private var rebuildGateRetry: Runnable? = null
    /** 上次拆窗后回收解码器缓冲的时刻（elapsedRealtime），冷却期内不再动 HAL */
    private var lastDecodeHealAtMs = 0L
    /** 拆窗后正在等解码器缓冲回收：这期间到来的新配置只排队，不再拆第二遍 */
    private var postTeardownHealPending = false
    /** 回收等待期里被挂起的最新配置；新配置到来时替换旧的（落地永远是最新一份） */
    private var healWaitRetry: Runnable? = null
    /** 低水位看护的周期检查；只在 onCreate 起一条，onDestroy 撤掉 */
    private var lowWaterWatchdog: Runnable? = null
    /** 低水位看护已连续量到几次低于红线；回到红线上方立即清零 */
    private var lowWaterLowTicks = 0
    /** 低水位看护的回收正在后台跑：期间不再起第二遍杀 HAL */
    private var lowWaterHealRunning = false

    // ========== LOGO 叠加层（config.logo）==========
    // 房间级一份 LOGO 视频 + 每扇素材窗里的落点。设备端自己画：一路解码 + 一块全屏以外的
    // ShareCanvasView(lumaKey) 按每扇窗自己的矩形各画一遍，纯黑底按亮度键透出下层。
    /** 云端下发的 LOGO 配置（已归一；null = 没设）。prefs 里 logo_json 是它的冷启动缓存 */
    private var logoJson: JSONObject? = null
    private var logoContainer: FrameLayout? = null
    private var logoCanvas: ShareCanvasView? = null
    private var logoPlayer: ExoPlayer? = null
    /** 当前 LOGO 播放器喂的是哪个文件（路径）；换文件要重建播放器 */
    private var logoPlayingFile: String = ""
    /** LOGO 文件正在后台下载：期间不重复排任务，落地后由下载线程回头刷一层 */
    @Volatile private var logoDownloading = false

    // ========== LOGO 设置辅助网格（config.logo_grid）==========
    // 管理台打开「LOGO设置」时整屏垫一层等像素方格的网格，帮人肉眼判断 LOGO 在每扇窗里是否被拉变形。
    // 层级压在窗口之上、LOGO 层之下；不进 rev，和 window_names 同款轻量下发（set_logo_grid）。
    private var logoGridContainer: FrameLayout? = null

    // ========== 运维层（状态文字 + 素材同步进度条）==========
    /** 成功类回执亮一下就走；失败/拒绝类没有限时，停到下一次状态覆盖为止 */
    private val STATUS_ACK_MS = 3_500L
    private val STATUS_TIP_MS = 5_000L
    /** 当前要显示的运维文案；空 + statusShown=false 时整层不占屏 */
    private var statusMsg: String = ""
    private var statusShown = false
    /** 素材同步进度条：只在 runDownloadPlan 这段期间为 true */
    private var statusBarOn = false
    private var statusBarPercent = 0
    /** 状态代次：每次改写 +1，到点的自动收起只对自己那一代生效（后来的文案不会被旧定时器误收） */
    private var statusGen = 0
    /** windowId -> 该窗口正中的那份面板。窗口增删/几何变化时由 refreshWindowStatusOverlays 对齐 */
    private val windowStatusOverlays = HashMap<String, WindowStatusOverlay>()
    /** windowId -> 窗口名（配置里的 name）。window_names 浮层开着时显示在窗口正中，建窗/轻量更新时刷新 */
    private val windowNames = HashMap<String, String>()

    // ==============================
    private var mqttClient: MqttClient? = null
    private val mqttHandler = Handler(Looper.getMainLooper())
    private val statusHandler = Handler(Looper.getMainLooper())
    // 单线程语义：submit 的是"整轮同步任务"，一轮只占 1 个线程；
    // 池给到 4 只是为了让 auth 触发的同步和手动 sync 不互相排队，文件夹内部仍是逐个串行下载。
    private val downloadExecutor = Executors.newFixedThreadPool(4)
    // 实时截图单独一条线程：素材同步常把 4 个下载坑占满，管理台那一下不该排队排到超时；
    // 单线程也顺带保证同一时刻只跑一张 screencap —— 两张 8K PNG 同时进内存没有意义。
    private val shotExecutor = Executors.newSingleThreadExecutor()
    /** MQTT 重连的排程线程：刻意与主线程分开，见 reconnectMQTT() */
    private val reconnectScheduler = Executors.newSingleThreadScheduledExecutor()
    private var statusTimerRunnable: Runnable? = null

    // 设备指纹信息
    private var deviceFingerprint: String = ""

    // 运行时版本号（onCreate 读 packageInfo，OTA 比较与指纹用，勿硬编码）
    private var VERSION_CODE: Int = 0

    // 运行时版本名（onCreate 读 packageInfo，随版本上报）
    private var VERSION_NAME: String = ""

    // 待安装的 APK：无"安装未知应用"权限时暂存，授权回来自动续装
    private var pendingInstallApk: File? = null

    // 续装权限复查用的延时队列（见 retryPendingInstall）， onDestroy 时清空
    private val installRetryHandler = Handler(Looper.getMainLooper())

    // 正在处理的 OTA 版本号：同一版本的下载/确认框只允许一条流程，避免启动+连接+重连三路触发刷屏
    private var otaBusyVersion: String? = null

    // root 静默安装可用性三态缓存（null = 还没探过）：见 silentInstallReady()，一次进程只验一次
    private var rootSuUsable: Boolean? = null

    // ========== 素材同步在飞去重 & 配置版本对账 ==========
    /** 一条同步请求：载荷 + 服务端配置指纹。见 requestRoomMaterialSync */
    private data class SyncRequest(
        val roomId: String,
        val folderMappingsFallback: org.json.JSONObject,
        val scenes: org.json.JSONObject?,
        val rev: String
    )
    /** 正在跑对账的房间；同房间新的一轮只记进 syncPending，不并发下载同一批文件 */
    private val syncInFlight = mutableSetOf<String>()
    /** 在飞期间收到的后指令：每个房间只留最后一条（服务端只会朝一个目标收敛，中间的都没意义） */
    private val syncPending = mutableMapOf<String, SyncRequest>()
    /** syncInFlight / syncPending 的锁；MQTT 回调线程写、下载线程读写 */
    private val syncLock = Any()
    /** 本机已应用的 scenes 结构指纹：同指纹不再全量重建（重建会打断正在播的视频） */
    private var appliedScenesFp: String? = null
    /**
     * 屏上画面所依据的那份 scenes（全量重建/实时更新最后应用的那份载荷，不是"最新收到的配置"）。
     * 墙板按学习帧切窗要拿它查绑定表与 windows —— 现场高频操作，不在主线程重读 prefs 里那份 JSON。
     * 只在应用成功后赋值（与 appliedScenesFp 同步），中途抛异常时它仍是旧的那份，墙板切换照着旧画面走。
     */
    private var appliedScenes: org.json.JSONObject? = null
    /** 本机已应用的房间配置 rev（心跳回传，服务端拿它和房间真值比对，落后就补发同步）；重启后仍以 prefs 为准 */
    private var appliedRev: String = ""
    /**
     * 房间输出基准（config.output）：编辑器画布与这里共用的一把尺，窗口 x/y/width/height 就写在这个坐标系里，
     * [windowGeom] 按 屏宽/基准宽、屏高/基准高 两个轴向分别换算。没设 = [DESIGN_W]×[DESIGN_H] 设计稿（历史口径），
     * 屏不是 16:9 时两个分母不等，数据里的正方形落屏就成 1.25:1 的长方形。
     * 落在 prefs 里是为了冷启动离线摆画面（restoreCachedScenes）时尺子就已经是对的，不等载荷来纠正。
     */
    private var designW = DESIGN_W
    private var designH = DESIGN_H
    /**
     * 屏上那份播放设置的规范化 JSON（两幕的 play_settings 拼一起）。
     * update_windows 轻路径只比窗口 id 与内容签名，播放设置不在窗口 content 里 ——
     * 不单独记一份的话，"只改了顺序（随机/顺序）"会被判成纯几何变化，原地改改 alpha 就返回，设置永远不生效。
     */
    private var appliedPlaySettingsJson: String? = null
    /**
     * windowId -> 视频窗口的画面现场（内层容器 + 主 TextureView + 窗口配置 + 这一格的播放设置）。
     * 播放器实例仍以 windowPlayers 为唯一真值，这里只放"这扇窗此刻在播哪一格"这类画面侧的账，
     * 避免两份播放器状态各漂一份。
     * COLOR/HDMI 窗口没有 TextureView，也就不进这张表（它们本来就没有"素材轮播"这回事）。
     */
    private val videoSurfaces = mutableMapOf<String, VideoSurface>()
    /**
     * folderKey -> 同格共享解码单元（任务 #175）：以前这一格挂几扇窗就起几路解码，
     * 交接延迟与凝滞按窗数线性放大（6 扇实测散差 3.5s），而解出来的画面是一模一样的。
     * 现在 ≥2 扇同格只起一路播放器，画面由一块 GL 画布按每扇窗自己的几何画 N 遍。
     */
    private val shareGroups = mutableMapOf<String, ShareGroup>()
    /** winId -> 它所属的共享单元：跟随窗也在这张表里（它们没有播放器，切格时靠它把账记回整组） */
    private val winShareGroup = mutableMapOf<String, ShareGroup>()
    /**
     * 屏上那份配置的生效窗口次序（按 z 升序、含共享组的跟随窗）。
     * 实时更新的"窗口增删/换序"判据原来是从 flSurface 的孩子反推的 —— 共享组是一块画布代表 N 扇窗，
     * 按孩子数就永远数不齐，每一轮轻量更新都会误判成结构变化而白重建一次（视频从头播）。
     */
    private var appliedWindowOrder: List<String> = emptyList()

    companion object {
        private const val TAG = "XVJPlayer"
        /** 窗口容器内亮度遮罩视图的 tag 标识（实时更新时定位/增删遮罩） */
        const val DIM_TAG = "xvj_dim"
        /**
         * 进程内当前活着的 MainActivity。系统在某些入口组合下会在同一个进程里起两个实例
         * （实例在 standard 栈时按 HOME 键会在 home 栈再起一个），两个实例各连一次 MQTT，
         * broker 认 clientId 不认人 ⇒ 每 10 秒互踢、还各建一套播放器（2026-11-16 台架实测）。
         * 见 onCreate 的去重。
         */
        private var liveInstance: MainActivity? = null
        const val APK_URL = "http://47.102.106.237"
        // 【A-16】实时截图的三个上限：8K 全屏 PNG 是几 MB 量级，超 16MB 就不像"一帧画面"该有的体积；
        // 回传只要"认得出窗口内容、看得清屏上文字"，1280 宽 + q55 就够（一帧几十 KB，现场带宽也轻），
        // 再大只是白带；超时给足 screencap 单线程编码整屏 PNG 的时间（台架 7680×2160 实测 1~2 秒级）。
        private const val SHOT_MAX_PNG_BYTES = 16 * 1024 * 1024
        private const val SHOT_MAX_WIDTH = 1280
        private const val SHOT_JPEG_QUALITY = 55
        private const val SHOT_TIMEOUT_MS = 15000L
        /** HDMI IN 采集要相机权限：申请回调的请求码（见 ensureHdmiPermission / onRequestPermissionsResult） */
        private const val REQ_HDMI_CAMERA = 0x4A31
        // 订阅用的通配符主题在 connectMQTT() 里按 deviceId 拼具体路径，不再用常量
        private const val AUTH_TOPIC = "xvj/auth/response"
        // 素材缓存的 SharedPreferences key 前缀。三个前缀后面的键一律是
        // materialCacheKey(file) = 相对素材根的路径（"scenea/01/x.mp4"），不再是裸文件名，理由见那里。
        // ETag/Last-Modified：条件请求用；md5：值为 "<文件字节数>:<mtime>:<md5>"，尺寸或改动时间变了即失效
        private const val PREF_ETAG_PREFIX = "etag_"
        private const val PREF_LM_PREFIX = "lm_"
        private const val PREF_MD5_PREFIX = "md5_"
        /** 串口节点白名单：与前端 WP_SERIAL_PATH_RE、服务端 isSafeSerialPath 同一把尺 */
        private val WALLPANEL_PATH_RE = Regex("^/dev/[A-Za-z0-9_-]{1,64}$")
        /** 学习窗最长时长：页面给多少都不许超过它 —— 忘了关也不能让设备一直往 broker 灌快照 */
        private const val LEARN_MAX_S = 60
        /** 一次快照上报几种候选帧：与前端学习列表一屏看得下的量对齐（台账本身留 24 种） */
        private const val LEARN_FRAME_LIMIT = 8
        /**
         * 同一帧重复的去抖窗口：墙上按键常见"按下/松开各发一帧"、中控也可能重发，
         * 不挡的话一次按键会连跳两支；超出窗口的重复才按用户定的语义"播下一支"处理。
         */
        private const val WALLPANEL_REPEAT_DEBOUNCE_MS = 300L
        /** DMX512 调格通道：写死第 1 路（下标 0），值 N 就调文件夹 NN；其余 19 路只存不认 */
        private const val DMX_FOLDER_CHANNEL = 0
        /** 通道值 1:1 对应文件夹 01~30，值域外的 31~255 没有格子可落 */
        private const val DMX_FOLDER_MAX = 30
        /** LOGO 文件地址白名单（与服务端 LOGO_URL_RE 同源）：只认本机上传目录里的内容寻址文件名，挡住外链与路径穿越 */
        private val LOGO_PATH_RE = Regex("^/uploads/logos/[A-Za-z0-9_-]{1,64}/logo-[a-f0-9]{6,32}\\.(?:mp4|webm|mov|avi|mkv)$")
        /** LOGO 落盘文件名（url 的最后一段）：同一条白名单的收紧版，落到 filesDir/logo/ 用 */
        private val LOGO_FILE_RE = Regex("^logo-[a-f0-9]{6,32}\\.(?:mp4|webm|mov|avi|mkv)$")
        private val LOGO_WINDOW_ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
        /** 每幕最多记多少扇窗（与服务端 LOGO_WINDOWS_MAX 同一上限） */
        private const val LOGO_WINDOWS_MAX = 64
        /** 逐窗几何最多记多少扇窗（与服务端 LOGO_PER_MAX 同一上限） */
        private const val LOGO_PER_MAX = 128
        /** 输出基准的缺省值 = 历史口径那份 1920×1080 设计稿（房间没设 config.output 时按它换算） */
        private const val DESIGN_W = 1920
        private const val DESIGN_H = 1080
        /** 服务端 outputPx 的域：16~7680，越界或非整数服务端已 400，本机再挡一道写脏 prefs 的路 */
        private const val DESIGN_PX_MIN = 16
        private const val DESIGN_PX_MAX = 7680
        // 测试背景图卡：原始 8000×4500，入包前压成 3840×2160 JPEG（全尺寸解码要 144MB）。
        // 设备端再按 1/2 采样解，1920 宽 ≈ 8MB —— 台架 4GB 闲置内存只有几百 MB，这张图是共享位图不能省这一步。
        private const val TEST_CARD_ASSET = "testcard.jpg"
        private const val HDMI_CARD_ASSET = "testcard_hdmi.png"
        private const val TEST_CARD_SAMPLE = 2
    }

    // 【A-11】 工具方法
    // @tag: logToFile 日志记录 log文件 MQTT上报
    // @tag: generateDeviceFingerprint 设备指纹生成
    // @tag: sha256 签名校验指纹计算（注意：算的是证书 DER 的 SHA-256，不是素材 md5）
    // @tag: calculateMd5 素材文件校验
    /** 本进程启动时刻：补传旧日志时用它把本会话刚写下的行排除掉 */
    private val sessionStartMs = System.currentTimeMillis()
    /** 旧日志每个进程只补一次，断线重连不再刷一遍 */
    private var logTailFlushed = false

    /**
     * 把上一次会话的本地日志尾部补传上来。
     * 设备被系统杀掉（LMK/SIGKILL）或原生崩溃时，Java 崩溃处理器根本没机会跑，而 MQTT 通道又随
     * 进程一起没了 —— 后台只看到一个 socket EOF，一行错误都没有，现场彻底成黑盒（171 在 201 上
     * 连加五个窗口后就是这样死的）。xvj.log 落在 filesDir，进程没了文件还在，所以重连后把本会话
     * 之前的最后几行发一次，才有的查。
     * 不挂 debug 闸门：debug 关掉时这是唯一还能拿到死因的通道，一次几 KB、每进程仅一次，量可控。
     */
    private fun flushPreviousSessionLog() {
        if (logTailFlushed) return
        logTailFlushed = true
        try {
            val lid = deviceId.ifEmpty { deviceFingerprint }
            val client = mqttClient
            if (lid.isEmpty() || client == null || !client.isConnected) return
            val f = File(filesDir, "xvj.log")
            if (!f.exists() || f.length() == 0L) return
            RandomAccessFile(f, "r").use { raf ->
                val from = maxOf(0L, raf.length() - 64 * 1024)
                raf.seek(from)
                val buf = ByteArray((raf.length() - from).toInt())
                raf.readFully(buf)
                // 按字节截的，第一行是半行必须丢掉；再排除本会话自己写的
                val lines = String(buf, Charsets.UTF_8).split('\n').drop(1)
                    .map { it.trim() }.filter { it.isNotEmpty() }
                    .filter { (it.substringBefore(' ').toLongOrNull() ?: 0L) < sessionStartMs }
                if (lines.isEmpty()) return
                val body = lines.takeLast(25).joinToString("\n")
                client.publish(
                    "xvj/device/$lid/log",
                    "${System.currentTimeMillis()} ERROR APP 上次会话尾部日志:\n${body.take(3600)}".toByteArray(),
                    1, false
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "补传旧日志失败: ${e.message}")
        }
    }

    /**
     * 写入文件日志（xvj.log）
     * 若 debug_mode 开启，同时通过 MQTT 上报到 device_logs 表
     * 结构化日志写入
     * @param msg 日志内容
     * @param level 日志级别: ERROR, WARN, INFO, DEBUG（默认 INFO）
     * @param module 模块: APP(默认), MQTT, AUTH, SYNC, WINDOW, PLAYBACK
     * @param action 动作: LOG(默认), COMMAND, CONFIG, CREATE, START, SELECT, SKIP, DOWNLOAD, ERROR, AUTH
     *
     * 调用示例：
     *   logToFile("应用启动")                        // INFO, APP, LOG
     *   logToFile("连接失败", "ERROR", "MQTT")     // ERROR, MQTT, LOG
     * level/module/action 都是自由字符串，服务端 device_logs 只按 level 白名单过滤查询，
     * 加新值不用改服务端；但前端日志页的 level 下拉只认 ERROR/WARN/INFO/DEBUG。
     */
    private fun logToFile(msg: String, level: String = "INFO", module: String = "APP", action: String = "LOG") {
        try {
            val logFile = File(filesDir, "xvj.log")
            // 日志原先无上限：一行开一次 writer，长跑一年多能把板子的存储吃光。
            // 2MB 一轮、留三份（xvj.log + .1 + .2），滚动只在写之前发生，不加锁 —— 多线程撞上
            // 最多丢一份旧档，比让日志无限长下去强。
            if (logFile.length() > 2L * 1024 * 1024) {
                val bak1 = File(filesDir, "xvj.log.1")
                val bak2 = File(filesDir, "xvj.log.2")
                bak2.delete()
                if (bak1.exists()) bak1.renameTo(bak2)
                logFile.renameTo(bak1)
            }
            val logLine = "${System.currentTimeMillis()} $level $module $action $msg"
            PrintWriter(FileWriter(logFile, true)).use { it.println(logLine) }

            // 只有debug_mode开启时才MQTT上报日志。试播（action=PREVIEW）例外：它不挂 debug（见
            // armPreview），而"本机没有这只素材"这类设备侧拒绝只有这一条回执 —— 跟着 debug
            // 一起关掉，就等于单击之后屏上没动静、页面上也查不到原因。
            if (prefs.getBoolean("debug_mode", false) || action == "PREVIEW") {
                // 身份统一用 deviceId：服务端 device_logs.device_id 取自 topic，前端按 devices.id 查询
                val lid = deviceId.ifEmpty { deviceFingerprint }
                if (lid.isNotEmpty()) {
                    mqttHandler.post {
                        try {
                            val topic = "xvj/device/${lid}/log"
                            val payload = "${System.currentTimeMillis()} $level $module $msg"
                            // 局部 val 捕获：mqttClient 是可变属性，Kotlin 禁止判空后 smart cast
                            val client = mqttClient
                            if (client == null) {
                                Log.e(TAG, "MQTT client is null, cannot send log")
                            } else if (!client.isConnected) {
                                Log.e(TAG, "MQTT not connected, cannot send log")
                            } else {
                                client.publish(topic, payload.toByteArray(), 1, false)
                                Log.d(TAG, "Log sent via MQTT: $msg")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "MQTT log failed: ${e.message}", e)
                        }
                    }
                } else {
                    Log.w(TAG, "Device identity empty, cannot send log")
                }
            } else {
                Log.d(TAG, "Debug mode off, log not sent via MQTT: $msg")
            }
        } catch (e: Exception) { 
            Log.e(TAG, "logToFile failed: ${e.message}", e)
        }
    }

    // 【A-01】 生命周期 & 初始化
    // ==================== 生命周期 & 初始化 ====================
    // onCreate 一次性装配：权限/横屏/常亮/全屏 → 崩溃兜底 handler → 建素材目录 → 读版本号 →
    //   生成指纹 → loadConfig（deviceId/mqttServer/debug_mode/applied_rev）→ restoreCachedScenes →
    //   connectMQTT → checkForUpdate。
    // 画面起点有两级：冷启动先按上次授权态留下的 scenes_json 直接开播（不等网络，见 restoreCachedScenes），
    //   云端 auth_result / sync_room_materials 到了再按真值校正 —— 配置没变时幂等闸门会挡掉重建，不会重头播。
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

        // 使用 app 内部存储目录存放视频（不需要权限）
        videoFolderPath = filesDir.absolutePath

        // 全局崩溃捕获：崩溃原因同步写本地并尽力同步 MQTT 上报。
        // 不走 mqttHandler.post（主线程崩溃时队列任务不再执行），直接同步 publish
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashMsg = "APP崩溃: ${throwable.javaClass.simpleName}: ${throwable.message}" +
                    " at " + throwable.stackTrace.take(6).joinToString(" <- ") {
                        "${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                    }
                PrintWriter(FileWriter(File(filesDir, "xvj.log"), true)).use {
                    it.println("${System.currentTimeMillis()} ERROR APP $crashMsg")
                }
                val client = mqttClient
                val lid = deviceId.ifEmpty { deviceFingerprint }
                if (client != null && client.isConnected && lid.isNotEmpty()) {
                    client.publish(
                        "xvj/device/$lid/log",
                        "${System.currentTimeMillis()} ERROR APP $crashMsg".toByteArray(), 1, false
                    )
                }
            } catch (e: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        logToFile("=== XVJ App Starting ===")
        logToFile("Video folder: $videoFolderPath")

        // 预建素材目录（根 + scenea + sceneb 各 01..30，详见 createMaterialFolders）
        createMaterialFolders()

        // 读取真实版本号（与 build.gradle versionCode 一致；旧实现硬编码 161 导致 OTA 误判循环下载）
        try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            VERSION_CODE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt() else pi.versionCode
            VERSION_NAME = pi.versionName ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "读取版本号失败: ${e.message}")
            VERSION_CODE = 0
            VERSION_NAME = ""
        }

        // 生成设备指纹
        deviceFingerprint = generateDeviceFingerprint()
        Log.d(TAG, "Device Fingerprint: $deviceFingerprint")
        logToFile("Fingerprint: $deviceFingerprint")

        // 加载本机配置（deviceId 等），不含窗口/scenes —— 窗口要等云端指令
        loadConfig()

        // 一个进程只留一个实例在管画面与 MQTT：旧实例还活着说明系统把第二个 MainActivity 起进了
        // 另一条任务栈（HOME 键 vs 桌面图标/am start），两边各连一次 MQTT 会被 broker 认成同一个
        // clientId 反复互踢，播放器也会建两套。新实例是现在可见的那一个，所以让旧的先收摊。
        val stale = liveInstance
        if (stale !== null && stale !== this) {
            logToFile("检测到第二个 MainActivity 实例：旧实例先收摊（防 MQTT 互踢与双播放器）",
                "WARN", "APP", "DESKTOP")
            stale.stopPlayback()
            stale.releaseAllWindows()
            stale.finish()
        }
        liveInstance = this

        // 上次是授权态就先按缓存开播，不等 MQTT 往返（见 restoreCachedScenes）
        restoreCachedScenes()

        // 连接MQTT（注册→授权回执里带 scenes 时才第一次 applySceneConfigs）
        connectMQTT()
        checkForUpdate() // 检查更新

        // 画面真在跑了：清掉退出标记，碰一下心跳信标，再把命交给 :keepalive 进程盯着（见 KeepAliveService）。
        // 主进程被 lmkd 收走后，MQTT 跟着没了，远程连 reopen_desktop 都发不进来 —— 只有那个进程能救。
        markExitedFile(false)
        touchAlive()
        startKeepAliveService()
        ensureDefaultHome()
        // 重建自愈只管建窗前那一刻；风暴停了如果水位还趴在红线附近，靠它兜底（见 startLowWaterWatchdog）
        startLowWaterWatchdog()
    }

    /**
     * 默认桌面自愈：XVJ 是本机的 HOME（[A-15] 的退出/唤回、OTA 自启都按这个前提写）。
     * 2026-11-17 台架重启落在系统"选择主屏幕"上，查下来系统里根本没有默认桌面记录 ——
     * 触屏机没有键鼠，这一下就是"开不了机"。这里每次进程启动确认一遍，不是自己就用 root 设回去；
     * 量产的 root 盒子才改得动，没 root 就当装机流程负责，不弹框不重试。
     */
    private fun ensureDefaultHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val current = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        val currentPkg = current?.activityInfo?.packageName
        if (currentPkg == packageName) return
        // 只对 debuggable 盒子动手：零售机型的 su 会弹授权框，装在屏上正好挡住画面
        if (Build.TYPE != "userdebug" && Build.TYPE != "eng") return
        Thread {
            if (!silentInstallReady()) return@Thread
            val r = execAsRoot("cmd package set-home-activity $packageName/.MainActivity", 5000)
            logToFile("默认桌面自愈：原为 ${currentPkg ?: "未设"}，${if (r.first == 0) "已设回 XVJ" else "设置失败 code=${r.first} ${r.second}"}",
                if (r.first == 0) "INFO" else "WARN", "WINDOW", "HOME")
        }.start()
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

    /**
     * 从 SharedPreferences 读回本机态：loop_play / mqtt_server / debug_mode / deviceId。
     * deviceId 为空时用刚算出的 fingerprint 播种并写回 —— 之后即使指纹因硬件或算法改动变了，
     * 身份仍以 prefs 里那份为准（这正是"换 uuid"分支成为死分支的同一层原因）。
     * mqttClientId 必须与 deviceId 完全一致，broker ACL 用 xvj/device/%c/# 做设备间隔离。
     * 不恢复窗口：scenes_json 的回落发生在同步完成的地方，不在这里。
     */
    private fun loadConfig() {
        // 使用 app 内部存储目录，不需要权限
        if (videoFolderPath.isEmpty()) {
            videoFolderPath = filesDir.absolutePath
        }

        // 本地配置
        loopPlay = prefs.getBoolean("loop_play", true)
        mqttServer = prefs.getString("mqtt_server", "tcp://47.102.106.237:1883") ?: "tcp://47.102.106.237:1883"

        // 恢复调试模式标志（只可能由 MQTT 写入：set_debug 指令，或 auth_result / sync_room_materials 的 debug 字段）
        val debugMode = prefs.getBoolean("debug_mode", false)
        Log.d(TAG, "Debug mode: $debugMode")

        // 使用设备指纹作为ID
        deviceId = prefs.getString("device_id", "") ?: ""
        if (deviceId.isEmpty()) {
            deviceId = deviceFingerprint
            prefs.edit().putString("device_id", deviceId).apply()
        }

        // clientId 必须严格等于 deviceId：broker ACL 用 pattern xvj/device/%c/# 做设备间隔离
        mqttClientId = deviceId

        // 回读上次已应用的配置版本：心跳一上来就能报 rev，服务端据此判断房间真值有没有比本机新
        appliedRev = prefs.getString("applied_rev", "") ?: ""

        // 输出基准要在冷启动摆画面前就位：restoreCachedScenes 拿缓存的 scenes 直接建窗，
        // 尺子还没恢复就会按 1920×1080 铺一遍、等载荷来了再重铺（域外值当没设，同 applyOutputField）
        designW = prefs.getInt("output_w", DESIGN_W).takeIf { it in DESIGN_PX_MIN..DESIGN_PX_MAX } ?: DESIGN_W
        designH = prefs.getInt("output_h", DESIGN_H).takeIf { it in DESIGN_PX_MIN..DESIGN_PX_MAX } ?: DESIGN_H

        // LOGO 冷启动恢复：文件真值在 filesDir/logo，这里只回读配置；画面等 restoreCachedScenes 建窗后刷
        logoJson = normalizeLogo(prefs.getString("logo_json", null)?.let {
            try { JSONObject(it) } catch (e: Exception) { null }
        })

        Log.d(TAG, "Device ID: $deviceId")

        // 按 prefs 里的 debug_mode 刷一次运维层（启动即决定窗口正中的文字/进度条是否可见）
        applyDebugUi()

        // 485 墙板是本地硬件链路，离线也要能用：先恢复学习绑定表（不然冷启动按下的第一帧查不到东西），
        // 再按 prefs 收放链路开关
        wallPanelBindings = WallPanelBindings.parse(
            prefs.getString("wallpanel_bindings", null)?.let {
                try { JSONArray(it) } catch (e: Exception) { null }
            }
        )
        applyWallPanelEnabled(prefs.getBoolean("wallpanel_enabled", false))
        // DMX512 副板同口径：本地硬件，离线也要能用，按 prefs 收放（通道表由 play_settings.dmx 决定）
        applyDmxEnabled(prefs.getBoolean("dmx_enabled", false))
    }

    /**
     * 冷启动离线恢复：上一次是授权态就把 scenes_json 直接摆上屏，
     * 不让大屏在"连 MQTT → 注册 → 授权回执"这一两秒（断网时更久）里黑着。
     * 与随后到的 auth_result 不冲突：applySceneConfigs 按结构指纹幂等，配置没变不会二次重建、视频不重头播。
     * ⚠️ 授权真值只在云端：本机若已被废止或改绑，回执落地时会切欢迎视频 / 按新配置重建，最多错这几秒。
     */
    private fun restoreCachedScenes() {
        if (!prefs.getBoolean("last_authorized", false)) return
        val cached = prefs.getString("scenes_json", null) ?: return
        try {
            applySceneConfigs(org.json.JSONObject(cached))
            setStatus("离线恢复上次画面（待云端确认）")
            logToFile("冷启动按缓存恢复窗口（待云端确认）", "INFO", "WINDOW", "CONFIG")
        } catch (e: Exception) {
            Log.e(TAG, "冷启动恢复缓存失败: ${e.message}")
            logToFile("冷启动恢复缓存失败: ${e.message}", "ERROR", "WINDOW", "ERROR")
        }
    }

    /**
     * debug_mode 的唯一写入口：落 prefs + 立刻刷新运维层显隐。
     * 三个来源都走这里：auth_result 与 sync_room_materials 载荷的 debug 字段、set_debug 指令。
     * ⚠️ 调用方必须先判"载荷里到底有没有 debug 字段"再进来（optBoolean 把没带当成 false，
     *    而 update_windows 这类载荷本来就不带）—— 判护在各自的调用点，见 handleCommand。
     */
    private fun setDebugMode(debug: Boolean) {
        prefs.edit().putBoolean("debug_mode", debug).apply()
        applyDebugUi()
    }

    /**
     * window_names 的唯一写入口：落 prefs + 立刻刷新窗口名浮层显隐（口径对齐 setDebugMode）。
     * 两个来源：auth_result / sync_room_materials 载荷里的 window_names 字段、set_window_names 指令
     * （管理台打开「窗口配置」时置 true、关掉置 false）。与 debug 无关 —— 名字是改窗口时的对屏辅助，
     * 不是运维信息，所以不走 applyDebugUi。
     */
    private fun setWindowNames(on: Boolean) {
        prefs.edit().putBoolean("window_names", on).apply()
        mqttHandler.post { applyStatusUi() }
    }

    /**
     * window_testcard 的唯一写入口：落 prefs + 立刻刷新测试背景浮层（口径对齐 setWindowNames）。
     * 来源：auth_result / sync_room_materials 载荷里的 window_testcard 字段、set_window_testcard 指令
     * （管理台打开「窗口配置」时与窗口名一起置 true，关掉一起置 false）。
     */
    private fun setWindowTestcard(on: Boolean) {
        prefs.edit().putBoolean("window_testcard", on).apply()
        mqttHandler.post { applyStatusUi() }
    }

    /**
     * logo_grid 的唯一写入口：落 prefs + 立刻刷辅助网格（口径对齐 setWindowTestcard）。
     * 来源：auth_result / sync_room_materials 载荷里的 logo_grid 字段、set_logo_grid 指令
     * （管理台打开「LOGO设置」时置 true，关掉置 false）。
     */
    private fun setLogoGrid(on: Boolean) {
        prefs.edit().putBoolean("logo_grid", on).apply()
        mqttHandler.post { applyLogoGrid() }
    }

    /**
     * 按 debug_mode 收放"运维层"= 状态文字 + 素材同步进度条。
     * 有在播窗口时每扇窗正中各一份（windowStatusOverlays），没有窗口时退回 XML 里的整屏居中面板，
     * 两者不会同时显示。设备接投影/大屏，调试模式关掉时整层 GONE，
     * 因此全文那十几处 setStatus 调用不必各自判 debug（隐藏面板就够了，文字照常更新）。
     * 任意线程可调（内部 post 到主线程）。
     */
    private fun applyDebugUi() {
        mqttHandler.post { applyStatusUi() }
    }

    /**
     * 状态文字的唯一写入口（任意线程可调）：msg 上屏，holdMs>0 时到点自动收起。
     * 成功类回执传 STATUS_ACK_MS / STATUS_TIP_MS；错误与进行中状态不限时，等下一次覆盖 ——
     * 运维文字现在压在画面正中间，常驻一条"已完成"会一直挡着内容，回执必须会自己走。
     */
    private fun setStatus(msg: String, holdMs: Long = 0L) {
        mqttHandler.post {
            statusMsg = msg
            statusShown = true
            renderStatus(holdMs)
        }
    }

    /** 素材同步进度（任意线程，仅 debug 模式上屏）：条打开、文字换成"进度 + 文件名" */
    private fun setSyncProgress(percent: Int, msg: String) {
        if (!prefs.getBoolean("debug_mode", false)) return
        mqttHandler.post {
            statusMsg = msg
            statusShown = true
            statusBarOn = true
            statusBarPercent = percent.coerceIn(0, 100)
            renderStatus()
        }
    }

    /** 收掉进度条；status 非空时同时换文案（holdMs 口径同 setStatus） */
    private fun stopSyncProgress(status: String? = null, holdMs: Long = 0L) {
        mqttHandler.post {
            statusBarOn = false
            if (status != null) {
                statusMsg = status
                statusShown = true
            }
            renderStatus(holdMs)
        }
    }

    /** 主线程：整层收起。退出到桌面/唤回这类状态没有 holdMs，唤回后不清会一直压在重建的画面上 */
    private fun clearStatus() {
        statusMsg = ""
        statusShown = false
        statusBarOn = false
        renderStatus()
    }

    /** 主线程：换文案/状态后重绘一次，并按需安排自动收起（只对自己这一代生效） */
    private fun renderStatus(holdMs: Long = 0L) {
        statusGen++
        val gen = statusGen
        applyStatusUi()
        if (holdMs > 0) {
            mqttHandler.postDelayed({
                if (gen == statusGen) {
                    statusShown = false
                    statusBarOn = false
                    applyStatusUi()
                }
            }, holdMs)
        }
    }

    /**
     * 主线程：把当前状态与显隐落到所有面板上。
     * 窗口面板只在窗口可见时出；一扇都出不来时（还没建窗 / HDMI 全关 / 退出到桌面）退回整屏居中面板。
     * 同一块面板承担三件事、各自独立判显隐：测试背景图卡（window_testcard，垫在最底层）、
     * 窗口名（window_names，编辑器开着时常亮，不用 debug）与 debug 的状态文字/进度条（名字与文字同出时竖排，名字在上）。
     * 每次刷新都 bringToFront：窗口容器是运行期 addView 到同一个根 FrameLayout 的，后加的会盖住面板。
     */
    private fun applyStatusUi() {
        val debug = prefs.getBoolean("debug_mode", false)
        val names = prefs.getBoolean("window_names", false)
        val testcard = prefs.getBoolean("window_testcard", false)
        var anyWindowPanel = false
        for ((winId, ov) in windowStatusOverlays) {
            val win = windowViews[winId]
            val winVisible = win != null && win.visibility == View.VISIBLE
            val showCard = testcard && winVisible
            val showName = names && winVisible
            val showStatus = debug && statusShown && winVisible
            val show = showCard || showName || showStatus
            ov.holder.visibility = if (show) View.VISIBLE else View.GONE
            if (showCard) {
                // 图卡按窗口类型各解一次、同类全窗共享；解码失败就只留黑底（比整窗透出下层内容更清楚这是"盖住了"）
                val kind = if (windowContentSigs[winId]?.startsWith("HDMI|") == true) "HDMI" else "STD"
                if (ov.cardKind != kind) {
                    ov.cardKind = kind
                    ov.card.setImageDrawable(null)
                }
                if (ov.card.drawable == null) {
                    (if (kind == "HDMI") hdmiCardBitmap() else testCardBitmap())?.let { ov.card.setImageBitmap(it) }
                }
            }
            ov.card.visibility = if (showCard) View.VISIBLE else View.GONE
            if (!show) continue
            ov.name.visibility = if (showName) View.VISIBLE else View.GONE
            if (showName) ov.name.text = windowNames[winId]?.takeIf { it.isNotEmpty() } ?: winId
            ov.text.visibility = if (showStatus) View.VISIBLE else View.GONE
            if (showStatus) {
                applyStatusContent(ov.text, ov.bar)
            } else {
                ov.bar.visibility = View.GONE
            }
            ov.holder.bringToFront()
            anyWindowPanel = true
        }

        val panel = binding.statusPanel ?: return
        val showFallback = debug && statusShown && !anyWindowPanel
        panel.visibility = if (showFallback) View.VISIBLE else View.GONE
        if (!showFallback) return
        styleFallbackStatusPanel()
        binding.statusText?.let { applyStatusContent(it, binding.syncProgressBar) }
        panel.bringToFront()
    }

    /** 文字与进度条内容（两类面板共用） */
    private fun applyStatusContent(text: TextView, bar: ProgressBar?) {
        text.text = statusMsg
        bar?.let {
            it.visibility = if (statusBarOn) View.VISIBLE else View.GONE
            it.progress = statusBarPercent
        }
    }

    /** 整屏兜底面板按真实分辨率缩放（8K 盒子上 14sp 在屏心仍太小） */
    private fun styleFallbackStatusPanel() {
        val screen = screenPx()
        val unit = minOf(screen[0], screen[1]).toFloat()
        val textPx = (unit / 30f).coerceIn(14f, 64f)
        val tv = binding.statusText ?: return
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
        tv.maxWidth = (screen[0] * 0.8f).toInt().coerceAtLeast(240)
        tv.setPadding((textPx * 0.9f).toInt(), (textPx * 0.55f).toInt(),
            (textPx * 0.9f).toInt(), (textPx * 0.55f).toInt())
        tv.background = GradientDrawable().apply {
            setColor(0xB3000000.toInt())
            cornerRadius = textPx * 0.35f
        }
        val bar = binding.syncProgressBar ?: return
        val lp = bar.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.width = (screen[0] * 0.4f).toInt().coerceIn(240, 1400)
        lp.height = maxOf(6, (textPx * 0.16f).toInt())
        lp.topMargin = (textPx * 0.6f).toInt()
        bar.layoutParams = lp
    }

    /**
     * 主线程：让窗口正中面板跟上 windowViews / 窗口几何。
     * 全量重建、原地改几何、释放窗口之后各调一次；多出来的面板当场摘掉。
     * 共享解码组成员共用一块容器，几何按成员自己的配置取（windowGeom），不按成员并集。
     */
    private fun refreshWindowStatusOverlays() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mqttHandler.post { refreshWindowStatusOverlays() }
            return
        }
        val root = flSurface ?: return
        for ((winId, ov) in windowStatusOverlays.toList()) {
            if (!windowViews.containsKey(winId)) {
                root.removeView(ov.holder)
                windowStatusOverlays.remove(winId)
            }
        }
        for (winId in windowViews.keys) {
            val g = overlayGeomOf(winId) ?: continue
            val ov = windowStatusOverlays.getOrPut(winId) {
                WindowStatusOverlay().also {
                    root.addView(it.holder, FrameLayout.LayoutParams(1, 1).apply {
                        gravity = Gravity.TOP or Gravity.START
                    })
                }
            }
            val lp = ov.holder.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(1, 1).apply { gravity = Gravity.TOP or Gravity.START }
            lp.leftMargin = g[0]
            lp.topMargin = g[1]
            lp.width = g[2].coerceAtLeast(1)
            lp.height = g[3].coerceAtLeast(1)
            ov.holder.layoutParams = lp
            styleWindowStatusOverlay(ov, g[2], g[3])
        }
        applyStatusUi()
    }

    /** 这扇窗当前落屏矩形。共享组成员看自己的配置；其余直接读容器 LayoutParams（三类内容都建了容器） */
    private fun overlayGeomOf(winId: String): IntArray? {
        if (winShareGroup.containsKey(winId)) {
            val w = videoSurfaces[winId]?.winJson ?: return null
            return windowGeom(w)
        }
        val c = windowViews[winId] as? FrameLayout ?: return null
        val p = c.layoutParams as? FrameLayout.LayoutParams ?: return null
        return intArrayOf(p.leftMargin, p.topMargin, p.width, p.height)
    }

    /** 窗口面板版式按窗口尺寸缩放：全屏大窗与角落小窗用同一套比例 */
    private fun styleWindowStatusOverlay(ov: WindowStatusOverlay, winW: Int, winH: Int) {
        val unit = minOf(winW, winH).toFloat()
        val textPx = (unit / 22f).coerceIn(24f, 200f)
        val padH = (textPx * 0.8f).toInt()
        val padV = (textPx * 0.5f).toInt()
        ov.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
        ov.name.maxWidth = (winW * 0.86f).toInt().coerceAtLeast(120)
        ov.name.setPadding(padH, padV, padH, padV)
        ov.name.background = GradientDrawable().apply {
            setColor(0xB3000000.toInt())
            cornerRadius = textPx * 0.35f
        }
        ov.text.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
        ov.text.maxWidth = (winW * 0.86f).toInt().coerceAtLeast(120)
        ov.text.setPadding(padH, padV, padH, padV)
        ov.text.background = GradientDrawable().apply {
            setColor(0xB3000000.toInt())
            cornerRadius = textPx * 0.35f
        }
        val barW = (winW * 0.55f).toInt().coerceAtLeast(120)
        val barH = maxOf(6, (textPx * 0.16f).toInt())
        ov.bar.layoutParams = LinearLayout.LayoutParams(barW, barH).apply {
            topMargin = (textPx * 0.55f).toInt()
        }
    }

    /** 一扇窗正中的面板：与窗口同矩形的透明 holder，内容在 holder 内居中，不拦触摸 */
    private inner class WindowStatusOverlay {
        val holder = FrameLayout(this@MainActivity).apply {
            isClickable = false
            isFocusable = false
        }
        /** 测试背景图卡（window_testcard 浮层）：拉伸铺满整块 holder（随窗口矩形变形），垫在名字/状态面板下面 */
        val card = ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.BLACK)
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        /** 当前垫的图卡种类（HDMI=网格卡 / STD=彩条卡）：窗口类型变了要换卡，不能留着旧位图 */
        var cardKind: String? = null
        val panel = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = false
            isFocusable = false
        }
        /** 窗口名（window_names 浮层）：名字只有一行，压在最上面；状态文字收起时它就是正中那一条 */
        val name = TextView(this@MainActivity).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
        }
        val text = TextView(this@MainActivity).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        }
        val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Color.rgb(76, 175, 80))
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        }

        init {
            holder.addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            panel.addView(name)
            panel.addView(text)
            panel.addView(bar)
            holder.addView(panel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    // 【A-14】 墙板 485 链路：串口收帧 → 帧指纹查学习表 → 跟随窗口切文件夹
    // @tag: applyWallPanelEnabled 墙板总闸 串口收放
    // @tag: applyPanelSource 学习帧 跟随窗口
    // @tag: startWallPanelLearn 学习窗 候选帧 frames 上报
    //
    // 一根 485 上跑着两路话（中控把点歌机和墙板的协议合并进来），所以这一段管三件事：
    //   收 —— WallPanelParser 按帧族 profile 定长切帧，族认不出就靠空闲（2 个字符时间）兜底分帧；
    //       每一帧的 hex 都进 frameLedger，随心跳和学习窗上报（xvj/device/{id}/frames）给人看分帧对不对；
    //   判 —— 只认学习表（config.wallpanel_bindings，帧指纹→文件夹）：查不到就只留痕，不猜语义；
    //   切 —— applyPanelSource 重排 content.follow 的窗口，格子归属一律问 resolveWindowFolder；
    //       同一帧再来一次 = 该格播下一支（按播放设置的次序），300ms 内的重复帧当按键去抖。
    // 字段真值全在云端 config 顶层（wallpanel_enabled / wallpanel_serial_path /
    // wallpanel_baud / wallpanel_data_bits / wallpanel_parity / wallpanel_stop_bits / wallpanel_bindings），
    // 本机只落 prefs + 按 prefs 收放链路。这些都故意不进 rev（服务端 roomSceneDigest 只算 scenes），
    // 掉了那一跳靠重连或手工同步自愈；改绑定也不该让整间房重排素材。

    /**
     * wallpanel_enabled 的唯一写入口：落 prefs + 收放串口链路（口径对齐 setDebugMode）。
     * 来源三处，都走服务端 roomWallpanelFields 一份口径：auth_result / sync_room_materials 载荷、
     * set_wallpanel 指令，以及 loadConfig 的 prefs 恢复（离线/重启后本地链路自己起来）。
     * 链路正开着而房间把串口节点或波特率换了，旧 fd 还指着上一套参数 → 按新参数重开一次。
     */
    @Synchronized
    private fun applyWallPanelEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("wallpanel_enabled", enabled).apply()
        if (!enabled) {
            stopWallPanelLearn()
            wallPanelLink?.stop()
            wallPanelLink = null
            return
        }
        val path = wallPanelPath()
        val baud = wallPanelBaud()
        val bits = wallPanelSerialBits()
        wallPanelLink?.let {
            // 字符格式变了必须重开：断帧阈值是按它算的，旧 fd 带着上一套参数会继续用错阈值
            if (it.path == path && it.baud == baud && it.dataBits == bits.first &&
                it.parity == bits.second && it.stopBits == bits.third
            ) return
            logToFile("墙板串口参数改为 $path${baud?.let { " @$it" } ?: "（驱动默认速率）"} ${bitsFormat(bits)}，重开链路",
                "INFO", "WALLPANEL", "CONFIG")
            it.stop()
            wallPanelLink = null
        }
        wallPanelLink = WallPanelLink(
            path = path,
            baud = baud,
            dataBits = bits.first,
            parity = bits.second,
            stopBits = bits.third,
            onEvent = ::handleWallPanelEvent,
            onError = { msg ->
                Log.w(TAG, "墙板串口: $msg")
                logToFile("墙板串口错误: $msg", "WARN", "WALLPANEL", "ERROR")
            },
            onNotice = { msg ->
                Log.i(TAG, "墙板串口: $msg")
                logToFile(msg, "INFO", "WALLPANEL", "LINK")
            },
        ).also { it.start() }
    }

    /** 日志里那串 8N1/8E2：与 WallPanelLink 里同一口径（越界值先回落默认档再显示，别报出一个设备实际没用的组合） */
    private fun bitsFormat(bits: Triple<Int, String, Int>): String {
        val letter = when (bits.second) {
            WallPanelLink.PARITY_ODD -> "O"
            WallPanelLink.PARITY_EVEN -> "E"
            else -> "N"
        }
        val d = if (bits.first in 5..8) bits.first else 8
        val stop = if (bits.third == 2) 2 else 1
        return "$d$letter$stop"
    }

    /**
     * 本机等墙板的串口节点：房间没给或给的不是 /dev 下的设备名就用默认节点。
     * 服务端 findWallpanelFieldError 已在写库时按同一把尺拦过（前端也拦一道），这里只是本机不被脏载荷带跑。
     */
    private fun wallPanelPath(): String {
        val p = (prefs.getString("wallpanel_serial_path", "") ?: "").trim()
        return if (WALLPANEL_PATH_RE.matches(p)) p else WallPanelLink.DEFAULT_PATH
    }

    /**
     * 本机等墙板的波特率：房间没给或不在档内就用驱动默认（null），与服务端 roomWallpanelFields 同口径。
     * prefs 里 0 = 没设（服务端"用驱动默认"下发的是 JSON null，落库统一存 0）。
     */
    private fun wallPanelBaud(): Int? =
        prefs.getInt("wallpanel_baud", 0).takeIf { it in WallPanelLink.BAUDS }

    /**
     * 本机等墙板的字符格式（数据位, 校验, 停止位）：没落 prefs 时用 8N1 —— 这是历史行为，
     * 老房间一个字段都没配也必须照旧工作。档位白名单与服务端/前端同一份，越界当没设。
     */
    private fun wallPanelSerialBits(): Triple<Int, String, Int> {
        val d = prefs.getInt("wallpanel_data_bits", 0).takeIf { it in WallPanelLink.DATA_BITS } ?: 8
        val p = (prefs.getString("wallpanel_parity", "") ?: "").takeIf { it in WallPanelLink.PARITIES }
            ?: WallPanelLink.PARITY_NONE
        val s = prefs.getInt("wallpanel_stop_bits", 0).takeIf { it in WallPanelLink.STOP_BITS } ?: 1
        return Triple(d, p, s)
    }

    /**
     * 墙板硬件字段（串口节点 / 波特率 / 字符格式）与学习表的唯一写入口：载荷真带了那个字段才覆盖本地值
     * （update_windows 这类载荷本来不带墙板字段，optXxx 的兜底值会把本地设置抹掉）。
     * 三个来源同为服务端 roomWallpanelFields 一份口径：auth_result / sync_room_materials 载荷、set_wallpanel。
     * 只写 prefs，不碰链路：收放串口统一由 [applyWallPanelEnabled] 按当前 prefs 决定，
     * 所以调用方必须先调本函数再调它 —— 反过来的话同一跳里改的节点/波特率要等下一次载荷才生效。
     */
    private fun applyWallPanelFields(payload: JSONObject) {
        if (payload.has("wallpanel_serial_path")) {
            // 走 opt() 而不是 optString()：org.json 把 JSON null 读成字符串 "null"，那玩意儿存进 prefs
            // 只会让下一次判重、判白名单都多一个假值（服务端恒下发字符串，本机不赌它）
            val raw = payload.opt("wallpanel_serial_path")
            val p = (if (raw == null || raw === JSONObject.NULL) "" else raw.toString()).trim()
            if (p != (prefs.getString("wallpanel_serial_path", "") ?: "").trim()) {
                prefs.edit().putString("wallpanel_serial_path", p).apply()
                logToFile("墙板串口节点下发为 ${if (p.isEmpty()) "默认" else p}", "INFO", "WALLPANEL", "CONFIG")
            }
        }
        if (payload.has("wallpanel_baud")) {
            // 同样走 opt()：服务端"改回驱动默认"下发的是 null，只认 optInt>0 的话本地那档旧速率永远清不掉
            val raw = payload.opt("wallpanel_baud")
            val n = (if (raw == null || raw === JSONObject.NULL) 0 else raw.toString().toIntOrNull() ?: 0)
            val b = if (n in WallPanelLink.BAUDS) n else 0
            if (b != prefs.getInt("wallpanel_baud", 0)) {
                prefs.edit().putInt("wallpanel_baud", b).apply()
                logToFile("墙板波特率下发为 ${if (b > 0) "$b" else "驱动默认"}", "INFO", "WALLPANEL", "CONFIG")
            }
        }
        // 数据位/校验/停止位：0 / "" 都当"没设"，wallPanelSerialBits 回落 8N1（= 历史行为）
        if (payload.has("wallpanel_data_bits")) {
            val d = payload.opt("wallpanel_data_bits").cellInt()
            if (d != prefs.getInt("wallpanel_data_bits", 0)) {
                prefs.edit().putInt("wallpanel_data_bits", d).apply()
                logToFile("墙板数据位下发为 ${if (d > 0) "$d" else "默认 8"}", "INFO", "WALLPANEL", "CONFIG")
            }
        }
        if (payload.has("wallpanel_parity")) {
            val raw = payload.opt("wallpanel_parity")
            val p = (if (raw == null || raw === JSONObject.NULL) "" else raw.toString().trim())
                .takeIf { it in WallPanelLink.PARITIES } ?: ""
            if (p != (prefs.getString("wallpanel_parity", "") ?: "")) {
                prefs.edit().putString("wallpanel_parity", p).apply()
                logToFile("墙板校验下发为 ${if (p.isEmpty()) "默认 none" else p}", "INFO", "WALLPANEL", "CONFIG")
            }
        }
        if (payload.has("wallpanel_stop_bits")) {
            val s = payload.opt("wallpanel_stop_bits").cellInt()
            if (s != prefs.getInt("wallpanel_stop_bits", 0)) {
                prefs.edit().putInt("wallpanel_stop_bits", s).apply()
                logToFile("墙板停止位下发为 ${if (s > 0) "$s" else "默认 1"}", "INFO", "WALLPANEL", "CONFIG")
            }
        }
        applyWallPanelBindings(payload)
        applyWallPanelCodes(payload)
    }

    /**
     * 全开/全关码（config.wallpanel_on_frame / wallpanel_off_frame）的唯一写入口：
     * 载荷真带了键才覆盖，null / 空 / 非法 = 没配这一键（清掉本机旧码）。
     * 形状闸与服务端同一把：1~64 字节的大写十六进制（WallPanelHex.isValid）。
     */
    private fun applyWallPanelCodes(payload: JSONObject) {
        listOf("wallpanel_on_frame" to "全开码", "wallpanel_off_frame" to "全关码").forEach { (key, label) ->
            if (!payload.has(key)) return@forEach
            val raw = payload.opt(key)
            val hex = (if (raw == null || raw === JSONObject.NULL) "" else raw.toString().trim().uppercase())
                .takeIf { it.isNotEmpty() && WallPanelHex.isValid(it) } ?: ""
            if (hex != (prefs.getString(key, "") ?: "")) {
                prefs.edit().putString(key, hex).apply()
                logToFile("墙板$label 下发为 ${if (hex.isEmpty()) "未配置" else hex}", "INFO", "WALLPANEL", "CONFIG")
            }
        }
    }

    /**
     * 学习绑定表（config.wallpanel_bindings）的唯一写入口：载荷真带了这个键才整体替换，
     * 没带保持本机现有那份 —— 与上面几个硬件字段同一个理由（不带键的载荷不能替本机改设置）。
     * 表不进 rev、走 set_wallpanel 轻枪下发，改一条绑定不该让整间房重排素材。
     */
    private fun applyWallPanelBindings(payload: JSONObject) {
        if (!payload.has("wallpanel_bindings")) return
        val json = payload.optJSONArray("wallpanel_bindings")
        val parsed = WallPanelBindings.parse(json)
        val prev = wallPanelBindings.size()
        wallPanelBindings = parsed
        prefs.edit().putString("wallpanel_bindings", (json ?: JSONArray()).toString()).apply()
        logToFile("墙板学习绑定已更新：$prev → ${parsed.size()} 条", "INFO", "WALLPANEL", "CONFIG")
        // 上一次触发源如果是一条现在已经被解绑的帧，画面不该继续拿它当"当前状态"解析
        val hex = (panelSource as? PanelSource.Frame)?.hex
        if (hex != null && parsed.actionFor(hex) == null) {
            panelSource = null
            logToFile("当前触发帧 $hex 已从绑定表解掉，跟随窗回到各自固定逻辑", "INFO", "WALLPANEL", "CONFIG")
        }
    }

    /**
     * dmx_enabled 的唯一写入口：落 prefs + 收放副板串口链路（口径对齐 applyWallPanelEnabled）。
     * 三个来源同为服务端 roomDmxFields 一份口径：auth_result / sync_room_materials 载荷、set_dmx，
     * 以及 loadConfig 的 prefs 恢复。串口节点/速率变了必须重开（旧 fd 还锁着上一套参数），
     * 重开时清通道基线：新链路的首帧只当"当前值"，不当一次按键。
     */
    @Synchronized
    private fun applyDmxEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("dmx_enabled", enabled).apply()
        if (!enabled) {
            dmxLink?.stop()
            dmxLink = null
            dmxPrev = null
            return
        }
        val path = dmxPath()
        val baud = dmxBaud()
        dmxLink?.let {
            if (it.path == path && it.baud == baud) return
            logToFile("DMX 串口参数改为 $path @$baud，重开链路", "INFO", "DMX", "CONFIG")
            it.stop()
            dmxLink = null
        }
        dmxPrev = null
        dmxLink = DmxLink(
            path = path,
            baud = baud,
            onFrame = ::handleDmxFrame,
            onError = { msg ->
                Log.w(TAG, "DMX 串口: $msg")
                logToFile("DMX 串口错误: $msg", "WARN", "DMX", "ERROR")
            },
            onNotice = { msg ->
                Log.i(TAG, "DMX 串口: $msg")
                logToFile(msg, "INFO", "DMX", "LINK")
            },
        ).also { it.start() }
    }

    /** 本机等 DMX 副板的串口节点：与墙板同一把尺，默认 /dev/ttyS4（小麦 x9/x10 机型的副板口） */
    private fun dmxPath(): String {
        val p = (prefs.getString("dmx_serial_path", "") ?: "").trim()
        return if (WALLPANEL_PATH_RE.matches(p)) p else DmxLink.DEFAULT_PATH
    }

    /** 本机等 DMX 副板的波特率：prefs 0/档外 = 出厂档 115200（与服务端 roomDmxFields 默认一致） */
    private fun dmxBaud(): Int? =
        prefs.getInt("dmx_baud", 0).takeIf { it in DmxLink.BAUDS } ?: DmxLink.DEFAULT_BAUD

    /**
     * DMX 硬件字段的唯一写入口：载荷真带了那个键才覆盖（与 applyWallPanelFields 同一理由，
     * update_windows 这类载荷不带，optXxx 兜底会把本地设置抹掉）。
     * 只写 prefs，收放链路随后由 [applyDmxEnabled] 按 prefs 决定 —— 调用方必须先调本函数。
     */
    private fun applyDmxFields(payload: JSONObject) {
        if (payload.has("dmx_serial_path")) {
            val raw = payload.opt("dmx_serial_path")
            val p = (if (raw == null || raw === JSONObject.NULL) "" else raw.toString()).trim()
            if (p != (prefs.getString("dmx_serial_path", "") ?: "").trim()) {
                prefs.edit().putString("dmx_serial_path", p).apply()
                logToFile("DMX 串口节点下发为 ${if (p.isEmpty()) "默认" else p}", "INFO", "DMX", "CONFIG")
            }
        }
        if (payload.has("dmx_baud")) {
            val raw = payload.opt("dmx_baud")
            val n = (if (raw == null || raw === JSONObject.NULL) 0 else raw.toString().toIntOrNull() ?: 0)
            val b = if (n in DmxLink.BAUDS) n else 0
            if (b != prefs.getInt("dmx_baud", 0)) {
                prefs.edit().putInt("dmx_baud", b).apply()
                logToFile("DMX 波特率下发为 ${if (b > 0) "$b" else "出厂默认 115200"}", "INFO", "DMX", "CONFIG")
            }
        }
    }

    /** 载荷里的一个"档位整数"：null/脏值/小数都当 0（= 没设），别把 JSONException 抛到下发链路上 */
    private fun Any?.cellInt(): Int {
        if (this == null || this === JSONObject.NULL) return 0
        val s = toString().trim()
        val n = s.toDoubleOrNull() ?: return 0
        return if (n == n.toLong().toDouble()) n.toInt() else 0
    }

    /**
     * 开学习窗（云端【S-06f】下发的 wallpanel_learn）：先清台账，让页面看到的候选帧只有
     * "按下学习之后"收到的那几种；每来一帧上报一次快照，到期自己关。
     * 时长夹在 1~[LEARN_MAX_S]：页面刷新、忘了关、甚至有人手搓一条命令，都不能把设备留在长期上报状态。
     * 只在主线程调（命令处理走 mqttHandler.post 进来）。
     */
    private fun startWallPanelLearn(seconds: Int) {
        val ms = seconds.coerceIn(1, LEARN_MAX_S) * 1000L
        learnReportRunnable?.let { mqttHandler.removeCallbacks(it) }
        learnUntilMs = SystemClock.elapsedRealtime() + ms
        frameLedger.clear()
        learnReportRunnable = Runnable { stopWallPanelLearn() }.also { mqttHandler.postDelayed(it, ms) }
        logToFile("墙板学习窗已开 ${ms / 1000}s：现在请依次按要学的按键", "INFO", "WALLPANEL", "LEARN")
        publishFrameSnapshot()
    }

    /** 关学习窗（到期自动、关总闸、页面手工关都走这里）：补一次快照，把最后收到的那几帧交给页面 */
    private fun stopWallPanelLearn() {
        learnReportRunnable?.let { mqttHandler.removeCallbacks(it) }
        learnReportRunnable = null
        if (learnUntilMs == 0L) return
        learnUntilMs = 0L
        logToFile("墙板学习窗已关", "INFO", "WALLPANEL", "LEARN")
        publishFrameSnapshot()
    }

    /**
     * 帧快照上报（xvj/device/{id}/frames）：只在开/关学习窗和学习期间每来一帧时发，
     * 平时靠心跳顺带（sendStatus 里那段 frames）。学习页要的是"按完键马上看到候选帧"，
     * 等 30s 心跳等于没有。服务端把它记在内存（带 TTL，不进库、不进 rev）。
     * 失败只记日志：这一路是给人看的排障辅助，不能因为一次发布失败拖垮收帧链路。
     */
    private fun publishFrameSnapshot() {
        try {
            val id = prefs.getString("device_id", null) ?: return
            val client = mqttClient ?: return
            if (!client.isConnected) return
            val payload = JSONObject().apply {
                put("learning", learnUntilMs > 0L)
                put("frames", frameLedger.toJson(SystemClock.elapsedRealtime(), LEARN_FRAME_LIMIT))
                put("timestamp", System.currentTimeMillis())
            }
            client.publish("xvj/device/$id/frames", payload.toString().toByteArray(), 1, false)
        } catch (e: Exception) {
            Log.w(TAG, "墙板帧快照上报失败: ${e.message}")
        }
    }

    /**
     * 输出基准（config.output）的唯一写入口：载荷真带了 output 键才动本地那把尺 ——
     * 没带（老云端、或者以后某条通道不带）保持原样，否则 optJSONObject 的 null 会把设置好的基准抹回设计稿。
     * 带了但为 null / 越界 = 没设，落回 [DESIGN_W]×[DESIGN_H]（与服务端 roomOutputField、前端 _designOf 同一口径）。
     * 只记账不重排：重排由调用方紧接着走的 applySceneConfigs / applyLiveWindowUpdate 负责，
     * 所以必须在它们之前调 —— 反了这一跳还是按旧分母算，画面得等下一份载荷才纠正。
     */
    private fun applyOutputField(payload: JSONObject) {
        if (!payload.has("output")) return
        val o = payload.optJSONObject("output")
        val w = o?.optInt("w", 0) ?: 0
        val h = o?.optInt("h", 0) ?: 0
        // 域外一律当"没设"：服务端已挡过一道（400），这里挡的是手改过的 prefs 与以后绕过校验的下发
        val nw = if (w in DESIGN_PX_MIN..DESIGN_PX_MAX) w else DESIGN_W
        val nh = if (h in DESIGN_PX_MIN..DESIGN_PX_MAX) h else DESIGN_H
        if (nw == designW && nh == designH) return
        designW = nw
        designH = nh
        prefs.edit().putInt("output_w", nw).putInt("output_h", nh).apply()
        logToFile("输出基准: ${nw}×${nh}", "INFO", "WINDOW", "CONFIG")
    }

    // 【A-17】 LOGO 叠加层（config.logo）
    // @tag: applyLogoField normalizeLogo refreshLogoLayer buildLogoMembers startLogoPlayer startLogoDownload
    //
    // 房间级一只 LOGO 视频，按配置落进若干素材窗。两道闸门同时为真才出：
    //   1) 窗口 id 在 logo.windows[A|B] 名单里（哪幕的名单都行，名单本身只表达"这扇窗允许"）；
    //   2) 这扇窗此刻在播的那一格 play_settings.logo === true（切到没开 LOGO 的格就不显示）。
    // 设备端只起**一路解码**：ExoPlayer 出帧进 ShareCanvasView(lumaKey=true) 的离屏纹理，
    // 画布再按每扇成员窗的 LOGO 矩形各画一遍（纯黑底按亮度键透出下层，取舍见 ShareCanvasView 注释）。
    // 不起 N 路播放器的理由与同格共享解码相同：这台盒子 4K60 已吃满 CPU，多一路解码换不来画质，
    // 只多一份 dma-buf 泄漏风险。
    //
    // 几何与前端预览严格同口径：盒中心 = 窗口中心 + (x/200)*窗宽、(y/200)*窗高；盒尺寸 = 窗宽*w%、窗高*h%；
    // 每扇窗可各有一套（config.logo.per_win，各窗宽高比不同）；没单独设的窗回落到房间默认 x/y/w/h。
    // 盒越出窗口的部分按前端画布的 overflow:hidden 裁掉（换算进 Member.crop 归一化取景区）。

    /**
     * config.logo 的唯一写入口（auth_result / sync_room_materials / update_windows 三处调用）。
     * 没带这个键 = 保持原样（update_windows 不带、老云端也不带）；带了但为 null/脏值 = 撤下。
     * 与 applyOutputField 的"缺键不动"同口径：服务端没设时整个键不落，不能拿缺省把既有配置抹掉。
     */
    private fun applyLogoField(payload: JSONObject) {
        if (!payload.has("logo")) return
        val next = normalizeLogo(payload.optJSONObject("logo"))
        val prevText = logoJson?.toString()
        logoJson = next
        if (next == null) {
            prefs.edit().remove("logo_json").apply()
            if (prevText != null) logToFile("LOGO：已撤下", "INFO", "WINDOW", "LOGO")
        } else {
            prefs.edit().putString("logo_json", next.toString()).apply()
            if (prevText != next.toString()) {
                val wa = logoIdsOf(next.optJSONObject("windows")?.optJSONArray("A")).joinToString("、").ifEmpty { "无" }
                val wb = logoIdsOf(next.optJSONObject("windows")?.optJSONArray("B")).joinToString("、").ifEmpty { "无" }
                logToFile("LOGO：${next.optString("filename", "")} 落在 A=[$wa] B=[$wb]", "INFO", "WINDOW", "LOGO")
            }
        }
        mqttHandler.post { refreshLogoLayer() }
    }

    /**
     * 下发/缓存里的 logo 归一化：地址必须命中上传接口的固定形状，数值夹到与服务端同一值域。
     * 不合法（缺 url / 地址不是 /uploads/logos/...）整体当"没设" —— 与 server.js roomLogoField 同口径。
     */
    private fun normalizeLogo(raw: JSONObject?): JSONObject? {
        if (raw == null) return null
        val url = raw.optString("url", "").trim()
        if (!LOGO_PATH_RE.matches(url)) return null
        fun num(name: String, dflt: Double): Double {
            val v = raw.optDouble(name, dflt)
            return if (v.isNaN()) dflt else v
        }
        val gx = num("x", 0.0).coerceIn(-200.0, 200.0)
        val gy = num("y", 0.0).coerceIn(-200.0, 200.0)
        val gw = num("w", 50.0).coerceIn(1.0, 100.0)
        val gh = num("h", 50.0).coerceIn(1.0, 100.0)
        val wins = raw.optJSONObject("windows")
        // 逐窗几何：id 白名单、数值夹到与房间默认同一值域、上限 128；缺字段跟随默认
        val per = JSONObject()
        val rawPer = raw.optJSONObject("per_win")
        if (rawPer != null) {
            fun geo(g: JSONObject, name: String, dflt: Double, lo: Double, hi: Double): Double {
                val v = g.optDouble(name, dflt)
                return (if (v.isNaN()) dflt else v).coerceIn(lo, hi)
            }
            for (id in rawPer.keys().asSequence().toList().sorted()) {
                if (per.length() >= LOGO_PER_MAX) break
                if (!LOGO_WINDOW_ID_RE.matches(id)) continue
                val g = rawPer.optJSONObject(id) ?: continue
                per.put(id, JSONObject().apply {
                    put("x", geo(g, "x", gx, -200.0, 200.0))
                    put("y", geo(g, "y", gy, -200.0, 200.0))
                    put("w", geo(g, "w", gw, 1.0, 100.0))
                    put("h", geo(g, "h", gh, 1.0, 100.0))
                })
            }
        }
        return JSONObject().apply {
            put("url", url)
            put("filename", raw.optString("filename", url.substringAfterLast('/')))
            put("md5", raw.optString("md5", "").lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(32))
            put("x", gx)
            put("y", gy)
            put("w", gw)
            put("h", gh)
            if (per.length() > 0) put("per_win", per)
            put("windows", JSONObject().apply {
                put("A", JSONArray(logoIdsOf(wins?.optJSONArray("A"))))
                put("B", JSONArray(logoIdsOf(wins?.optJSONArray("B"))))
            })
        }
    }

    /**
     * 一扇窗的 LOGO 几何：per_win 里有这扇窗就用它，否则用房间默认 x/y/w/h。
     * 各扇素材窗宽高比不同，同一套相对几何摆出来观感不一样，所以逐窗可单独定。
     * 返回 [x偏移%, y偏移%, 宽占比, 高占比]（偏移是相对窗口中心的百分比，占比是窗口宽/高的 0~1）。
     */
    private fun logoGeoFor(cfg: JSONObject, winId: String): FloatArray {
        val g = cfg.optJSONObject("per_win")?.optJSONObject(winId)
        fun v(name: String, dflt: Double): Float = (g?.optDouble(name, dflt) ?: dflt).toFloat()
        return floatArrayOf(
            v("x", cfg.optDouble("x", 0.0)).coerceIn(-200f, 200f),
            v("y", cfg.optDouble("y", 0.0)).coerceIn(-200f, 200f),
            (v("w", cfg.optDouble("w", 50.0)) / 100f).coerceIn(0.01f, 1f),
            (v("h", cfg.optDouble("h", 50.0)) / 100f).coerceIn(0.01f, 1f)
        )
    }

    /** 窗口名单过滤：只认合法 id、去重、上限 64（与服务端 logoWinIds 同一把尺） */
    private fun logoIdsOf(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            if (out.size >= LOGO_WINDOWS_MAX) break
            val id = arr.optString(i, "")
            if (LOGO_WINDOW_ID_RE.matches(id)) out.add(id)
        }
        return out.toList()
    }

    /** LOGO 本地文件：filesDir/logo/<url 末段>；末段与 canonical 归属都校验（防御手改 prefs 的路径穿越） */
    private fun logoFileOf(cfg: JSONObject): File? {
        val url = cfg.optString("url", "")
        if (!LOGO_PATH_RE.matches(url)) return null
        val name = url.substringAfterLast('/')
        if (!LOGO_FILE_RE.matches(name)) return null
        val dir = File(filesDir, "logo")
        val f = File(dir, name)
        return try {
            if (f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) f else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 按"屏上这份 scenes + 当前 logo 配置"落一次 LOGO 层。幂等，所有触发点都汇到这里
     * （配置落地 / 实时更新 / 墙板切格 / 下载完成）：
     *  - 没配 / 退出到桌面 / 没有窗口同时过两道闸 → 藏起来（暂停解码）；
     *  - 文件不在 → 藏起来并排一次下载（在飞去重），下完回头再来一遍；
     *  - 有成员 → 起播（同文件复用播放器）+ 显示。
     * 可从任意线程调用（MQTT 线程的处理器直接调）。
     */
    private fun refreshLogoLayer(scenes: JSONObject? = appliedScenes) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mqttHandler.post { refreshLogoLayer(scenes) }
            return
        }
        // 辅助网格与 LOGO 层同一趟收口：窗口重建后网格要重新置顶到 LOGO 之下、窗口之上
        applyLogoGrid()
        val cfg = logoJson
        if (cfg == null || exited) { hideLogoLayer(); return }
        val members = buildLogoMembers(scenes, cfg)
        if (members.isEmpty()) { hideLogoLayer(); return }
        val file = logoFileOf(cfg)
        if (file == null || !file.exists() || file.length() == 0L) {
            hideLogoLayer()
            startLogoDownload(cfg)
            return
        }
        startLogoPlayer(file)
        showLogoLayer(members)
    }

    /**
     * 这份 scenes 里该有哪几个 LOGO 矩形。坐标是屏内像素（与窗口容器同一坐标系），
     * 盒与窗口求交后再把"裁掉了多少"换算成归一化取景区，画布按 crop 采样就不会画到窗口外。
     */
    private fun buildLogoMembers(scenes: JSONObject?, cfg: JSONObject): List<ShareCanvasView.Member> {
        if (scenes == null) return emptyList()
        val wins = cfg.optJSONObject("windows") ?: return emptyList()
        val allowA = logoIdsOf(wins.optJSONArray("A"))
        val allowB = logoIdsOf(wins.optJSONArray("B"))
        if (allowA.isEmpty() && allowB.isEmpty()) return emptyList()
        val out = ArrayList<ShareCanvasView.Member>()
        for ((winId, w) in enabledWindowsSorted(scenes)) {
            if (winId !in allowA && winId !in allowB) continue
            val view = windowViews[winId] ?: continue
            if (view.visibility != View.VISIBLE) continue
            // 第二道闸：这一窗此刻播的那一格开了 LOGO 才出（与云端 playSettingsValue 同一条件）
            val folderKey = videoSurfaces[winId]?.folderKey?.takeIf { it.isNotEmpty() }
                ?: resolveWindowFolder(scenes, w.optJSONObject("content") ?: JSONObject(), winId)
                ?: continue
            if (!playSettingsOf(scenes, folderKey).logo) continue
            val g = windowGeom(w)
            val winLeft = g[0].toFloat()
            val winTop = g[1].toFloat()
            val winW = g[2].toFloat().coerceAtLeast(1f)
            val winH = g[3].toFloat().coerceAtLeast(1f)
            val geo = logoGeoFor(cfg, winId)
            val boxW = (winW * geo[2]).coerceAtLeast(1f)
            val boxH = (winH * geo[3]).coerceAtLeast(1f)
            val boxLeft = winLeft + winW / 2f + (geo[0] / 200f) * winW - boxW / 2f
            val boxTop = winTop + winH / 2f + (geo[1] / 200f) * winH - boxH / 2f
            val il = maxOf(boxLeft, winLeft)
            val it2 = maxOf(boxTop, winTop)
            val ir = minOf(boxLeft + boxW, winLeft + winW)
            val ib = minOf(boxTop + boxH, winTop + winH)
            if (ir - il < 1f || ib - it2 < 1f) continue
            val crop = floatArrayOf(
                (il - boxLeft) / boxW, (it2 - boxTop) / boxH,
                (ir - il) / boxW, (ib - it2) / boxH
            )
            val masterB = w.optDouble("_masterB", 1.0).toFloat().coerceIn(0f, 1f)
            out.add(ShareCanvasView.Member(
                winId = winId,
                left = Math.round(il),
                top = Math.round(it2),
                width = Math.round(ir - il),
                height = Math.round(ib - it2),
                alpha = w.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                brightness = (w.optDouble("brightness", 1.0).toFloat() * masterB).coerceIn(0f, 1f),
                crop = crop,
                rotation = 0,
                flip = "none"
            ))
        }
        return out
    }

    /**
     * 显示 LOGO 层：容器按成员矩形并集定位（画布整块摆过去，成员改画布内坐标）。
     * 层级：窗口之上、墙板黑布与运维面板之下 —— 后两者每次都要重新 bringToFront，
     * 因为容器是运行期子视图，不抬一次就会被新加的窗口/面板压住。
     */
    private fun showLogoLayer(members: List<ShareCanvasView.Member>) {
        if (members.isEmpty()) { hideLogoLayer(); return }
        val canvas = ensureLogoCanvas() ?: return
        val container = logoContainer ?: return
        var minX = members[0].left
        var minY = members[0].top
        var maxX = minX + members[0].width
        var maxY = minY + members[0].height
        for (m in members) {
            if (m.left < minX) minX = m.left
            if (m.top < minY) minY = m.top
            if (m.left + m.width > maxX) maxX = m.left + m.width
            if (m.top + m.height > maxY) maxY = m.top + m.height
        }
        val cw = (maxX - minX).coerceAtLeast(1)
        val ch = (maxY - minY).coerceAtLeast(1)
        val lp = container.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(cw, ch).apply { gravity = Gravity.TOP or Gravity.START }
        if (lp.leftMargin != minX || lp.topMargin != minY || lp.width != cw || lp.height != ch) {
            lp.leftMargin = minX
            lp.topMargin = minY
            lp.width = cw
            lp.height = ch
            container.layoutParams = lp
        }
        canvas.setMembers(members.map { it.copy(left = it.left - minX, top = it.top - minY) })
        container.visibility = View.VISIBLE
        container.bringToFront()
        panelBlackout?.bringToFront()
        logoPlayer?.play()
        applyStatusUi()
    }

    /** 藏起 LOGO 层：视图 GONE（成员清空后画布不清屏，不藏会停在最后一帧）+ 播放器暂停省解码 */
    private fun hideLogoLayer() {
        logoContainer?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
        logoCanvas?.setMembers(emptyList())
        logoPlayer?.pause()
    }

    /** 懒建 LOGO 画布（lumaKey 亮度键）与容器：1×1 占位挂在窗口根上，显隐与几何由 showLogoLayer 管 */
    private fun ensureLogoCanvas(): ShareCanvasView? {
        logoCanvas?.let { return it }
        val root = flSurface ?: return null
        val canvas = ShareCanvasView(this, lumaKey = true)
        val container = FrameLayout(this).apply { visibility = View.GONE }
        container.addView(canvas, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(container, FrameLayout.LayoutParams(1, 1).apply { gravity = Gravity.TOP or Gravity.START })
        panelBlackout?.bringToFront()
        logoContainer = container
        logoCanvas = canvas
        return canvas
    }

    /**
     * 主线程：收放 LOGO 辅助网格（logo_grid）。开着时整屏一块等像素方格，压在窗口之上、LOGO 层之下，
     * 给现场一个绝对参照：LOGO 在方格里的宽高比一眼就能看出有没有被拉变形。
     * 懒建后不再删；关掉只置 GONE，下次打开零开销。每次 refreshLogoLayer 都会再走一遍，
     * 因为窗口重建会往根上后加子视图，网格不重新置顶就会被新窗口盖住。
     */
    private fun applyLogoGrid() {
        val root = flSurface ?: return
        if (!prefs.getBoolean("logo_grid", false)) {
            logoGridContainer?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
            return
        }
        val container = logoGridContainer ?: FrameLayout(this).apply {
            addView(GridOverlayView(this@MainActivity), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            visibility = View.GONE
            root.addView(this, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }.also { logoGridContainer = it }
        if (container.visibility != View.VISIBLE) container.visibility = View.VISIBLE
        container.bringToFront()
        logoContainer?.let { if (it.visibility == View.VISIBLE) it.bringToFront() }
        panelBlackout?.bringToFront()
    }

    /**
     * 等像素方格：格边长取屏高 1/12，横竖同一像素数，落屏就是真正的正方形（与窗口几何同一把尺：
     * 基准=屏真实像素时两轴分母相等）。线宽随分辨率走，8K 上 3px、1080p 上 2px。
     */
    private inner class GridOverlayView(ctx: Context) : View(ctx) {
        private val paint = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.argb(90, 255, 255, 255)
            isAntiAlias = false
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            paint.strokeWidth = (h / 720f).coerceIn(2f, 6f)
            val cell = (h / 12f).coerceAtLeast(24f)
            var x = 0f
            while (x <= w) {
                canvas.drawLine(x, 0f, x, h, paint)
                x += cell
            }
            var y = 0f
            while (y <= h) {
                canvas.drawLine(0f, y, w, y, paint)
                y += cell
            }
        }
    }

    /**
     * 起 LOGO 解码器。同文件复用现有播放器（重复刷新配置不重开流水线）；
     * 解码端 Surface 由画布拿到 TextureView st 之后回调过来（与共享解码组同一条交接）。
     */
    private fun startLogoPlayer(file: File) {
        val path = file.absolutePath
        val canvas = ensureLogoCanvas() ?: return
        logoPlayer?.let { p ->
            if (logoPlayingFile == path) {
                if (!p.isPlaying) p.play()
                return
            }
        }
        releaseLogoPlayer()
        val p = buildExoPlayer().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
            volume = 0f
        }
        logoPlayer = p
        logoPlayingFile = path
        canvas.onDecoderSurface = { surf -> if (logoPlayer === p) p.setVideoSurface(surf) }
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (logoPlayer === p) canvas.setDecoderSize(videoSize.width, videoSize.height)
            }
            override fun onPlayerError(error: PlaybackException) {
                logToFile("LOGO 播放器错误: ${error.errorCodeName} ${error.message ?: ""}", "ERROR", "WINDOW", "LOGO")
            }
        })
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        logToFile("LOGO 起播: ${file.name}（${file.length() / 1024}KB）", "INFO", "WINDOW", "LOGO")
    }

    /** 放掉 LOGO 解码器（换文件 / 窗口全释放 / Activity 销毁）；画布本身另由 shutdown 收 */
    private fun releaseLogoPlayer() {
        logoCanvas?.onDecoderSurface = null
        logoPlayer?.release()
        logoPlayer = null
        logoPlayingFile = ""
    }

    /**
     * 下载 LOGO 文件到 filesDir/logo（不在素材根下，与素材同步两不相干）。
     * 名字内容寻址（带 md5 前 12），tmp+rename 保证半截文件不会顶替正式名；md5 复核不过按失败丢弃。
     * 同目录只留当前这一只；在飞去重，下载完回主线程重刷一层。
     */
    private fun startLogoDownload(cfg: JSONObject) {
        val urlPath = cfg.optString("url", "")
        if (!LOGO_PATH_RE.matches(urlPath)) return
        val name = urlPath.substringAfterLast('/')
        if (!LOGO_FILE_RE.matches(name)) return
        if (logoDownloading) return
        val dir = File(filesDir, "logo")
        if (!dir.exists() && !dir.mkdirs()) {
            logToFile("LOGO：建目录失败 ${dir.absolutePath}", "WARN", "WINDOW", "LOGO")
            return
        }
        val dest = File(dir, name)
        val wantMd5 = cfg.optString("md5", "")
        logoDownloading = true
        logToFile("LOGO：开始下载 $name", "INFO", "WINDOW", "LOGO")
        try {
            downloadExecutor.execute {
                var ok = false
                val tmp = File(dir, "$name.part")
                try {
                    val conn = java.net.URL(APK_URL + urlPath).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        logToFile("LOGO：下载失败 HTTP $code $urlPath", "ERROR", "WINDOW", "LOGO")
                        conn.disconnect()
                    } else {
                        conn.inputStream.use { input -> FileOutputStream(tmp).use { output -> input.copyTo(output, 65536) } }
                        conn.disconnect()
                        if (tmp.length() <= 0L) {
                            logToFile("LOGO：下载到 0 字节，丢弃", "WARN", "WINDOW", "LOGO")
                        } else {
                            val got = calculateMd5(tmp)
                            if (wantMd5.isNotEmpty() && got != wantMd5) {
                                logToFile("LOGO：md5 不符（云端 $wantMd5，实得 $got），丢弃", "WARN", "WINDOW", "LOGO")
                            } else {
                                if (dest.exists()) dest.delete()
                                ok = tmp.renameTo(dest)
                                if (!ok) logToFile("LOGO：落盘改名失败", "ERROR", "WINDOW", "LOGO")
                            }
                        }
                    }
                } catch (e: Exception) {
                    logToFile("LOGO：下载异常 ${e.message}", "ERROR", "WINDOW", "LOGO")
                } finally {
                    if (tmp.exists()) tmp.delete()
                    logoDownloading = false
                }
                if (ok) {
                    // 老文件（上一版 LOGO）清掉：名字带 md5，换一次多一只，不清理会一直堆
                    dir.listFiles()?.forEach { if (it.isFile && it.name != name) it.delete() }
                    logToFile("LOGO：下载完成 $name（${dest.length() / 1024}KB）", "INFO", "WINDOW", "LOGO")
                }
                mqttHandler.post { refreshLogoLayer() }
            }
        } catch (e: Exception) {
            // 线程池已 shutdown（Activity 收摊中）：复位标志，别把下一次下载永远挡在门外
            logoDownloading = false
            logToFile("LOGO：下载任务排队失败 ${e.message}", "WARN", "WINDOW", "LOGO")
        }
    }

    /**
     * 一帧到达要做的三件事：记账、学习期上报、判义。
     * 记账与判义是两回事 —— 485 上点歌机和墙板合并在同一根线，先无条件留痕，现场才看得清
     * 分帧猜得对不对（帧长/断帧阈值错了，上报的 hex 一眼就能看出来）；判义才是动画面。
     * 判义只认学习表（人工学过的那一条说了算）：没学过的帧一律只留痕 —— 语义随厂家变，
     * 本机不猜协议，要切哪一格由人在配置页把这一帧学进那一格。
     * 已在主线程（WallPanelLink 把事件 post 过来了）。
     */
    private fun handleWallPanelEvent(event: WallPanelEvent) {
        val nowMs = SystemClock.elapsedRealtime()
        val hex = WallPanelHex.of(event.frame)
        frameLedger.record(hex, nowMs)
        if (nowMs < learnUntilMs) publishFrameSnapshot()
        // 全开/全关先判：这两个码不是"切到某一格"，命中就不再往下查绑定表
        val onHex = prefs.getString("wallpanel_on_frame", "") ?: ""
        val offHex = prefs.getString("wallpanel_off_frame", "") ?: ""
        if (onHex.isNotEmpty() && hex == onHex) { applyPanelBlackout(false, hex); return }
        if (offHex.isNotEmpty() && hex == offHex) { applyPanelBlackout(true, hex); return }
        if (wallPanelBindings.actionFor(hex) != null) {
            applyPanelSource(PanelSource.Frame(hex), hex)
            return
        }
        when (event) {
            is WallPanelEvent.NumberSelect ->
                logToFile("墙板编号帧 $hex 未绑定只留痕（要切格请在配置页学进那一格）", "INFO", "WALLPANEL", "FRAME")
            is WallPanelEvent.Command ->
                logToFile("墙板功能帧 $hex family=0x${Integer.toHexString(event.family)} key=0x${Integer.toHexString(event.key)}，未绑定只留痕",
                    "INFO", "WALLPANEL", "FRAME")
            is WallPanelEvent.Unknown ->
                logToFile("未识别帧 $hex（${event.frame.size} 字节），未绑定只留痕", "INFO", "WALLPANEL", "FRAME")
        }
    }

    /**
     * DMX512 一帧到达：只认通道 1，值 1:1 调文件夹（值 N = 格子 NN），
     * 与 485 学过的帧共用 [applyPanelSource]。口径：
     *   首帧只当基线 —— 开机时副板已经在报当前值，那不是一次按键，不能无故切格；
     *   值没变不触发；归零只当松手，不触发（推子/按钮回落都会经过 0）；
     *   1~30 之外的值为 0 或 31~255：没有对应文件夹，忽略；
     *   这一格没在任何一幕开 DMX512 控制 → 只记日志，链路照常收帧（别的格可能开着）。
     * 已在主线程（DmxLink 把帧 post 过来了）。
     */
    private fun handleDmxFrame(frame: DmxFrame) {
        val prev = dmxPrev
        dmxPrev = frame.channels.copyOf()
        if (prev == null) {
            logToFile("DMX 首帧已收（基线不触发）: ${frame.summary()}", "INFO", "DMX", "FRAME")
            return
        }
        val v = frame.channels.getOrNull(DMX_FOLDER_CHANNEL) ?: return
        if (v == 0 || v == prev.getOrElse(DMX_FOLDER_CHANNEL) { -1 }) return
        if (v > DMX_FOLDER_MAX) return
        onDmxTrigger(if (v < 10) "0$v" else "$v", v)
    }

    /** 一路通道变动 → 一次墙板式按键（同格再按 = 下一支，见 applyPanelSource） */
    private fun onDmxTrigger(fid: String, value: Int) {
        // 停止态也要能当开机键用（与 485 同一口径）：内存里没配置时拿缓存配置判这一格的闸
        val scenes = appliedScenes ?: prefs.getString("scenes_json", null)?.let { raw ->
            try {
                JSONObject(raw)
            } catch (e: Exception) {
                null
            }
        }
        if (scenes == null) {
            logToFile("DMX 通道 $fid 变动（值 $value）：本机还没应用过窗口配置，忽略", "WARN", "DMX", "SKIP")
            return
        }
        if (!dmxFolderEnabled(scenes, fid)) {
            logToFile("DMX 通道 $fid 变动（值 $value）：A/B 两幕里这一格都没开 DMX512 控制，忽略", "INFO", "DMX", "SKIP")
            return
        }
        applyPanelSource(PanelSource.Dmx(fid), "值 $value")
    }

    /** 这一格在任一幕开了 DMX512 控制没有（物理键 A01/B01 与老载荷的裸键都认） */
    private fun dmxFolderEnabled(scenes: JSONObject, fid: String): Boolean {
        for (k in listOf("A", "B")) {
            val tbl = scenes.optJSONObject(k)?.optJSONObject("play_settings") ?: continue
            val cell = tbl.optJSONObject(k + fid) ?: tbl.optJSONObject(fid) ?: continue
            if (cell.optBoolean("dmx", false)) return true
        }
        return false
    }

    /**
     * DMX 源在这扇窗所在的幕里有没有资格动它（resolveWindowFolder 与 applyPanelSource 共用）：
     * play_settings 的键是物理目录 id（A01/B01），与 applySceneConfigs 的落点口径一致。
     */
    private fun dmxAllowedFor(scenes: JSONObject, prefixedFolder: String, content: JSONObject): Boolean {
        val type = content.optString("type", "").uppercase()
        val key = when (type) {
            "SCENE_A" -> "A"
            "SCENE_B" -> "B"
            else -> return false
        }
        val physical = if (prefixedFolder.startsWith(key)) prefixedFolder else key + prefixedFolder
        return playSettingsOf(scenes, physical).dmx
    }

    /**
     * 触发源的落点：先把这一源记下来，再把所有跟随墙板的窗口切到它对应的那一格；
     * 同一帧再按一次 = 那一格播下一支（顺序/随机按播放设置，见 advancePanelFolder）。
     * 口径：
     *   只动 content.follow == true 的窗口 —— folderId 是人工钉死的，墙板不越权去改它；
     *   播哪一格一律交给 resolveWindowFolder，与全量重建同一个入口，两处不会各漂一份；
     *   解析不出目标格（这一帧这一幕没绑 / 那格不在映射里）→ 那扇窗保持原样，宁可停在旧画面也不切到黑屏；
     *   DMX 源还要过该格 play_settings.dmx 的闸（A/B 各判各的）；
     *   正在试播的那一格先收尾再切 —— 不收的话试播播完的恢复回调会把画面拽回按键之前；
     *   重复帧 [WALLPANEL_REPEAT_DEBOUNCE_MS] 内只认一次（按下/松开的重复帧），超出窗口才算"下一支"。
     * 必须在主线程调用（换 playlist）；WallPanelLink / DmxLink 的事件已经 post 到主线程。
     */
    private fun applyPanelSource(source: PanelSource, hex: String) {
        // 两个源共用这一套切格逻辑，日志里得说清是墙板还是 DMX（类别分开才好按来源筛日志）
        val dmx = source is PanelSource.Dmx
        val noun = if (dmx) "DMX" else "墙板"
        val cat = if (dmx) "DMX" else "WALLPANEL"
        if (exited) {
            // 人已离场、桌面在身前，画面不该被 485/DMX 叫醒 —— 否则"退出"按不动，客人面前挡不住。
            // 不更新 panelSource：唤回时按配置原样重建，那几帧不补播。
            logToFile("${noun}触发 ${describeSource(source)} 未生效：设备已退出到桌面", "WARN", cat, "DESKTOP")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val repeat = panelSource == source
        if (repeat && now - panelSourceAtMs < WALLPANEL_REPEAT_DEBOUNCE_MS) {
            logToFile("${noun}重复帧 ${describeSource(source)}（${now - panelSourceAtMs}ms 内），去抖跳过", "INFO", cat, "SKIP")
            return
        }
        val scenes = appliedScenes
        if (scenes == null) {
            // 停止（云端 action:'stop'）连"已应用配置"一起清掉。墙板按键是现场的开机键：
            // 拿本地缓存整屏重建，并把这一帧当触发源一起落进去 —— 停止只是停播，不是改配置，
            // 所以按任一学过的墙板键（或开了 DMX 的那一路）就该把画面唤回来（2026-11-17 老板定调）。
            val cached = prefs.getString("scenes_json", null)
            if (cached != null) {
                panelSource = source
                panelSourceAtMs = now
                logToFile("${noun}触发 ${describeSource(source)}：设备处于停止态，按缓存配置唤起画面", "INFO", cat, "WAKE")
                try {
                    applySceneConfigs(JSONObject(cached), force = true)
                } catch (e: Exception) {
                    logToFile("停止态唤起失败: ${e.message}", "ERROR", cat, "WAKE")
                }
                return
            }
            logToFile("${noun}触发 ${describeSource(source)} 未生效：本机还没应用过窗口配置", "WARN", cat, "SKIP")
            return
        }
        if (repeat) {
            logToFile("${noun}重复触发 ${describeSource(source)}（$hex）", "INFO", cat, "REPEAT")
        } else {
            logToFile("${noun}触发 ${describeSource(source)}（$hex）", "INFO", cat, "SWITCH")
            panelSource = source
        }
        panelSourceAtMs = now
        val allWindows = mergeSceneWindows(scenes)
        val follows = (0 until allWindows.length()).map { allWindows.getJSONObject(it) }
            .map { Pair(it.optString("id"), it.optJSONObject("content") ?: JSONObject()) }
            .filter { it.second.optBoolean("follow", false) && isVideoContentType(it.second) }
        val resolved = follows.associate { (id, content) -> id to resolveWindowFolder(scenes, content, id) }
        val switching = resolved.any { (id, fid) -> fid != null && videoSurfaces[id]?.folderKey != fid }
        // 共享组必须整组同格才成立：成员被墙板分到不同格子（或某一格解析不出素材）时组的构成就变了，
        // 原地重排做不到（解码路数跟着变），只能回全量重建。换格本来就低频；
        // 重复按的"下一支"原地推进、不换格，所以不走这里。
        if ((!repeat || switching) && shareGroups.isNotEmpty() && follows.isNotEmpty()) {
            val broken = shareGroups.values.any { grp -> grp.members.map { resolved[it] }.distinct().size > 1 }
            if (broken) {
                logToFile("${noun}触发 ${describeSource(source)} 会让共享解码组的成员分家，回退全量重建", "INFO", cat, "REBUILD")
                applySceneConfigs(scenes, force = true)
                return
            }
        }
        // 一格一路解码（#175），同一组的成员窗口只让基准窗推一次，其余窗跟着同一张画布
        val advanced = HashSet<String>()
        // 本次触发真正落到的格（预置前缀）：点歌屏显隐只认这一份，见循环末尾
        val landedFolders = LinkedHashSet<String>()
        // 判"下一支"必须看按键前就在不在那格：切格会同步刷新整组成员的落点账，
        // 边切边判的话，排在后面的成员会把刚到的新格误当成重复按，白白多推一支
        // （起播瞬间 seek，现场看到就是一次凝滞/闪黑）。
        val beforeFolders = follows.associate { (id, _) -> id to videoSurfaces[id]?.folderKey }
        for ((winId, content) in follows) {
            endPreviewCovering(winId, if (repeat) "${noun}重复触发" else "${noun}切换")
            val folderId = resolved[winId]
            if (folderId == null) {
                Log.w(TAG, "窗口 $winId 跟随${noun} ${describeSource(source)} 解析不到可播的文件夹，保持原画面")
                logToFile("窗口 [$winId] ${describeSource(source)} 无可播文件夹，保持原画面", "WARN", cat, "SKIP")
                continue
            }
            // DMX 只动该幕里开了 DMX512 控制的格子：没开的那一幕回到自己的固定逻辑，
            // 不能因为同一路通道就跟着跳格（A/B 是两套节目）。
            if (dmx && !dmxAllowedFor(scenes, folderId, content)) {
                logToFile("DMX 触发 → 文件夹 $folderId 所在幕未开 DMX512 控制，窗口 [$winId] 保持原画面", "INFO", cat, "SKIP")
                continue
            }
            // 判"下一支"只看落点是不是按键前就在播的那一格，不看这一帧和上一帧是否同一个码：
            // 点歌机码与墙板码可能指向同一格，交替按也不该把画面拽回第一支。
            val leader = winShareGroup[winId]?.leaderId ?: winId
            if (beforeFolders[winId] == folderId) {
                if (advanced.add(leader)) advancePanelFolder(leader, folderId, noun, cat)
            } else {
                playFolderInWindow(winId, folderId)
            }
            // 只有真落到这一格的窗才参与点歌屏判定：某一幕没有这一格素材时它保持原画面
            // （老板 2026-11-19 定调"没素材不切就行"），旧格不能替这次切换决定显隐
            if (videoSurfaces[winId]?.folderKey == folderId) landedFolders.add(folderId)
        }
        // 换格后点歌屏显隐跟着这一格的开关走（同一格再按不换格，但重复算一遍无害且幂等）；
        // 一扇都没落到新格（都没素材/被闸门拦下）就不动现状
        if (landedFolders.isNotEmpty()) applyHdmiVisibility(scenes, landedFolders)
        else applyHdmiVisibility(scenes)
        // 换格后这一格的 play_settings.logo 也换了：LOGO 层按新落点重排（同格重复按幂等）
        refreshLogoLayer(scenes)
    }

    /**
     * 同一帧再按一次 = 这一格往下一支走。用播放器自己的"下一支"，清单就是播放设置算好的那份
     * （顺序=按文件名，随机=只洗一次后的固定次序），与自然播完完全同一条路，不另立一套次序。
     * 一格一路解码，这里只动基准窗那一只；组内其余窗跟着同一张画布。
     */
    private fun advancePanelFolder(leaderWinId: String, folderId: String, noun: String, cat: String) {
        val player = windowPlayers[leaderWinId]
        if (player == null || player.mediaItemCount == 0) {
            // 播放器不在了（正在重建/这一格没起来）：补一次起播，别让按键变成哑的
            playFolderInWindow(leaderWinId, folderId)
            return
        }
        val from = player.currentMediaItemIndex
        player.seekToNextMediaItem()
        logToFile("${noun}触发 → 文件夹 $folderId 下一支：第 ${from + 1}/${player.mediaItemCount} 只 → 第 ${player.currentMediaItemIndex + 1} 只",
            "INFO", cat, "NEXT")
    }

    /** 日志里对触发源的唯一称呼 */
    private fun describeSource(source: PanelSource): String = when (source) {
        is PanelSource.Frame -> "学习帧 ${source.hex}"
        is PanelSource.Dmx -> "通道 ${source.folder}"
    }

    /**
     * 当前触发源对应哪一格（跟随窗口在 resolveWindowFolder 里问这一句）：
     *   学习帧 → 绑定表；没学过这一帧（或没按过）就返回 null，窗口退回自己的固定逻辑，不劫持。
     *   DMX   → 通道 1 的值就是格子号（"01".."30"），无表可查。
     * 一格一码、不分幕：A/B 两幕问出来是同一格。返回不带幕前缀的格子编号。
     */
    private fun panelFolderFor(): String? = when (val s = panelSource) {
        is PanelSource.Frame -> (wallPanelBindings.actionFor(s.hex) as? PanelAction.Folder)?.folder
        is PanelSource.Dmx -> s.folder
        null -> null
    }

    // 【A-02】 MQTT 连接
    // @tag: connectMQTT mqtt连接 broker连接
    /**
     * 连接 MQTT Broker
     * - 设置 MqttConnectOptions（持久会话、保活15秒、30秒超时）
     * - 订阅 commandTopic 和 AUTH_TOPIC
     * - 注册设备并请求授权状态同步
     * - 连接成功后触发 checkForUpdate
     * - 支持指数退避重连（5s → 最大300s）
     * 注：两个订阅都订 QoS 1 —— 订阅端的 QoS 才是链路上限，服务端即使按 QoS 1 发布，
     *     只要这里写 0 就仍会降级成"发一次不等确认、断了不补投"。配合 isCleanSession=false，
     *     断线期间服务端以 QoS 1 发的指令由 broker 排队、重连后按序补投（画面最终停在最后一条，
     *     中间态重放一遍也不改变结果：update_windows 幂等、素材同步按房间去重后只对账一次）。
     */
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
                // broker 鉴权：secret 未配置时 BuildConfig 为空串，此时不带凭据（兼容 allow_anonymous 阶段）
                if (BuildConfig.MQTT_DEVICE_PASSWORD.isNotEmpty()) {
                    options.userName = "xvj_device"
                    options.password = BuildConfig.MQTT_DEVICE_PASSWORD.toCharArray()
                    Log.d(TAG, "MQTT: 使用设备凭据连接")
                } else {
                    Log.d(TAG, "MQTT: 未配置设备凭据，匿名连接")
                }
                // P4 fix: 连接成功/失败通过 setCallback + isConnected 标志判断
                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "MQTT Connection Lost: ${cause?.message}")
                        logToFile("MQTT Connection Lost: ${cause?.message}")
                        mqttHandler.post {
                            setStatus("云端连接断开，正在重连…")
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
                            logToFile("消息处理异常: ${e.message}")
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                try {
                    mqttClient?.connect(options)
                    Log.d(TAG, "MQTT Connected! Subscribed to: $commandTopic")
                    logToFile("MQTT Connected OK!")
                    onMqttConnected()  // P4 fix: 重置退避计数器
                    mqttClient?.subscribe(commandTopic, 1)
                    mqttClient?.subscribe(AUTH_TOPIC, 1)
                    registerDevice()
                    // 通道一通就先还旧账：上一次是怎么死的，全靠这几行
                    flushPreviousSessionLog()
                    mqttHandler.post {
                        setStatus("云端已连接", STATUS_ACK_MS)
                    }
                    // P4 fix: 连接成功后执行，不要在闭包外裸调
                    checkForUpdate()
                } catch (e: Exception) {
                    Log.e(TAG, "MQTT Connection Error: ${e.message}")
                    logToFile("MQTT Connection Error: ${e.message}")

                    onMqttReconnectFailed()  // P4 fix: 连接失败也触发退避
                    // ⚠️ 光抬高退避计数器是不够的，必须真的安排一次重试：以前只有 connectionLost
                    // （连上之后断开）会 reconnectMQTT()，首次连接失败——最常见就是"平板先开机、
                    // 路由器还没拨号"——一次都不会再试。设备从此永久离线，且因为不再发心跳，
                    // 服务端的 rev 对账也拿不到任何补发机会，界面上只剩一个"离线"。
                    reconnectMQTT()
                    mqttHandler.post {
                        // 异常原文只进日志（上一行已写）；上屏文案给人看，不贴堆栈/英文
                        setStatus("云端连接失败，正在重试…")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MQTT setup error: ${e.message}")
                logToFile("MQTT setup error: ${e.message}")
                // 同上：MqttClient 构造失败（服务器地址被配坏等）也得留一条重试的路
                onMqttReconnectFailed()
                reconnectMQTT()
            }
        }
    }

    // 【A-03】 设备注册 & 授权
    // @tag: registerDevice 设备注册 authorization授权
    /**
     * 注册设备：QoS1 publish 到 xvj/device/register，载荷同时带 device_id 与 fingerprint
     * （服务端用 "id = ? OR fingerprint = ?" 命中即视为同一台设备，所以指纹变化不会另立新档）
     * 随后立即发一次 online 并起 30s 心跳。
     * 授权结果不在这里回，由服务端另发 xvj/auth/response（见 handleAuthResponse）。
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
            logToFile("注册设备失败: ${e.message}")
        }
    }

    /**
     * 发送设备状态到MQTT
     * 主题 xvj/device/{id}/status，载荷 status/version/version_code/timestamp + rev/syncing：
     *   rev     = 本机已应用的房间配置指纹（appliedRev，从没应用过带 rev 的载荷时不带这个字段）；
     *             服务端【S-06】reconcileDeviceRev 拿它和房间真值比对，落后就补发一次同步 ——
     *             这是 QoS 丢消息 / 设备离线期间改配置的唯一兜底收敛路径。
     *   syncing = 此刻是否有素材对账在飞；为 true 时服务端跳过对账，免得下载中途被再插一刀。
     *   screen_w/screen_h = 本机 windowGeom 换算用的那对像素（screenPx = 屏真实像素，与落屏分母同源，不是 dp）；
     *              编辑器画布拿它当"取本机"的一键基准。内存里 90s 过期，不进库 —— 心跳 30s 一报，够新。
     *   mem_avail_mb / app_pss_mb = 内存水位（#226）：整机可用内存与本进程 PSS 的 MB 数，只进 status_data
     *              原文供后台画曲线；不进 rev、不触发任何对账。
     *   frames  = 最近 1 分钟内收到的 485 帧指纹（[{hex,n,age}]，最多 8 种）；一段都没收到就不带这个字段，
     *              服务端据此清掉这台机的显示值。后台拿它判"分帧对不对"和学习模式的候选帧，
     *              所以它是瞬时状态：只进内存台账，不进 rev，也不进库。
     *   secondary_display = 副屏（1080/eDP）此刻通着没有（读 DRM 连接器 status，以文件为准）；
     *              本机没有这条显示链时整个字段不带，服务端按"不支持"处理，管理台开关显示未知。
     * 服务端把 status 落到 devices.status、整段原文存 devices.status_data（该列没有读侧），
     * 并因带了 version/version_code 而 UPSERT device_versions —— 后台"版本管理"页看的正是这张表。
     * 未授权设备同样在上报，服务端不区分；心跳本身 30s 一次，不写 operation_logs。
     * id 直接读 prefs 而不是成员变量，故用局部 val 遮蔽了同名的 deviceId 成员。
     */
    private fun sendStatus(status: String) {
        try {
            val deviceId = prefs.getString("device_id", null) ?: return
            val statusTopic = "xvj/device/$deviceId/status"
            val syncing = synchronized(syncLock) { syncInFlight.isNotEmpty() }
            // 心跳同时是保活进程的判活信标（见 KeepAliveService）：本进程活着就一直在碰它
            touchAlive()
            val payload = JSONObject().apply {
                put("status", status)
                put("version", VERSION_NAME)
                put("version_code", VERSION_CODE)
                put("syncing", syncing)
                if (appliedRev.isNotEmpty()) put("rev", appliedRev)
                // 与 windowGeom 同源的那对像素（本 Handler 挂在主 looper 上，取窗口度量不必绕线程）
                val screen = screenPx()
                put("screen_w", screen[0])
                put("screen_h", screen[1])
                // 副屏（1080/eDP）此刻通着没有。文件不存在 = 本机没有这条显示链，整个字段不带。
                readSecondaryDisplayOn()?.let { put("secondary_display", it) }
                // 台账为空（这一分钟没帧）就整段不带：服务端把"没带"当清掉显示值，留着上一轮的旧帧反而误导
                val frames = frameLedger.toJson(SystemClock.elapsedRealtime())
                if (frames.length() > 0) put("frames", frames)
                // 内存水位（#226）：availableMem 是整机口径、PSS 是本进程口径，都进 status_data 原文，
                // 后台据此画曲线/置红。dma-buf 只有 root 读得到，设备侧拿不到就整个字段不带。
                val am = getSystemService(android.app.ActivityManager::class.java)
                if (am != null) {
                    val mi = android.app.ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mi)
                    if (mi.availMem > 0) put("mem_avail_mb", (mi.availMem shr 20).toInt())
                    val pssKb = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
                        ?.firstOrNull()?.totalPss ?: 0
                    if (pssKb > 0) put("app_pss_mb", pssKb / 1024)
                }
                put("timestamp", System.currentTimeMillis())
            }
            mqttClient?.publish(statusTopic, payload.toString().toByteArray(), 1, false)
            Log.d(TAG, "Status sent: $status (v$VERSION_CODE)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send status: ${e.message}")
            logToFile("发送状态失败: ${e.message}")
        }
    }

    /**
     * 记一笔"本机已应用到配置版本 rev"：内存里给心跳用，prefs 里留一份给重启后的第一次心跳。
     * 只在对应配置真的应用完才调（服务端按此判断要不要补发同步，报早了会把差异吞掉）。
     */
    private fun markAppliedRev(rev: String) {
        if (rev.isEmpty() || rev == appliedRev) return
        appliedRev = rev
        prefs.edit().putString("applied_rev", rev).apply()
        logToFile("配置版本已应用: rev=$rev", "INFO", "SYNC", "CONFIG")
    }

    /** 起 30s 心跳：先撤掉旧的 Runnable 再挂新的，所以重复调用不会叠加定时器 */
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

    /** 撤心跳并补发一次 offline；onDestroy 里它排在 disconnect 之前，顺序反了这条 offline 就发不出去 */
    private fun stopStatusTimer() {
        statusTimerRunnable?.let {
            statusHandler.removeCallbacks(it)
            // 发送离线状态
            sendStatus("offline")
        }
    }

    /**
     * 处理 xvj/auth/response 上的两类载荷：auth_result（授权结果，成功即带 scenes 直接渲染窗口）
     * 与 deauthorize（远程废止，回到未授权态并播欢迎视频）。
     * ⚠️ AUTH_TOPIC 是全设备共享广播，所以开头那道 device_id 校验是唯一的隔离手段：
     *    targetId 非空且既不是本机 deviceId 也不是指纹 → 直接忽略；deauthorize 缺 targetId 也忽略。
     *    targetId 为空串的 auth_result 例外放行（老服务端不回带 id）。
     * 每次注册后服务端都会回一份。冷启动的画面不等这份回执 —— 上次是授权态时 onCreate 会先用
     * scenes_json 缓存开播（restoreCachedScenes），这里只负责按真值校正；last_authorized 就是那道闸门。
     */
    private fun handleAuthResponse(payload: String) {
        try {
            val resp = JSONObject(payload)
            val action = resp.optString("action", "")
            // AUTH_TOPIC 是全设备共享的广播通道，device_id 才是目标；不校验会把别台设备的废止/授权照单执行
            val targetId = resp.optString("device_id", "")
            if (targetId.isNotEmpty() && targetId != deviceId && targetId != deviceFingerprint) {
                Log.d(TAG, "忽略非本机 auth 广播: $action -> $targetId")
                return
            }
            if (action == "deauthorize" && targetId.isEmpty()) {
                Log.w(TAG, "忽略无目标设备的废止广播")
                return
            }
            when (action) {
                "auth_result" -> {
                    val authorized = resp.getBoolean("authorized")
                    val message = resp.optString("message", "")
                    // 提取房间信息
                    val roomId = resp.optString("room_id", "")
                    val folderMappings = resp.optJSONObject("folder_mappings")
                    // 提取 scenes 配置（包含 A/B 两套窗口配置和各自 folder_mappings）
                    val scenes = resp.optJSONObject("scenes")
                    val rev = resp.optString("rev", "")

                    mqttHandler.post {
                        if (authorized) {
                            prefs.edit().putBoolean("last_authorized", true).apply()
                            setStatus("已授权 · 房间 $roomId", STATUS_ACK_MS)
                            // 停止欢迎视频等旧播放器
                            releasePlayer()
                            // 墙板字段要先于 applySceneConfigs：跟随窗口第一次落屏就要能查学习表，
                            // 排在后面建完窗才落 prefs，这一场同步里跟随窗口就先播别的格、等下次按键才归位。
                            // 也要先于 applyWallPanelEnabled：节点/波特率这一跳可能一起变，先落 prefs 再开链路
                            // 才只开一次（反过来会拿旧参数开一遍、发现变了再重开，端口白闪断一轮）。
                            // auth_result 也带这几个字段（服务端 roomSyncFields 一处收口），
                            // 掉了 set_wallpanel 那一跳的设备靠重连这次自愈。
                            applyWallPanelFields(resp)
                            // DMX 同口径：先落节点/速率，再按总闸收放链路（auth_result 也带 dmx_*）
                            applyDmxFields(resp)
                            // 输出基准同理要先于 applySceneConfigs：那把尺决定每个窗口的落屏矩形，
                            // 建窗之后才纠正的话，这次同步出来的画面仍是按旧分母算的（下一份载荷才归位）。
                            applyOutputField(resp)
                            // LOGO 配置也要先于 applySceneConfigs：建窗收尾会按它刷一层 LOGO 层，
                            // 晚一步落账就会先按旧 LOGO 出一帧、等下一份载荷才纠正（见 refreshLogoLayer）。
                            applyLogoField(resp)
                            if (resp.has("wallpanel_enabled")) {
                                applyWallPanelEnabled(resp.optBoolean("wallpanel_enabled", false))
                            }
                            if (resp.has("dmx_enabled")) {
                                applyDmxEnabled(resp.optBoolean("dmx_enabled", false))
                            }
                            // 授权真值只在云端 devices 表；本机只留两样：scenes_json 缓存（冷启动恢复画面用）
                            // 和 last_authorized（能不能用这份缓存），每次 auth_result 覆盖。
                            if (scenes != null) {
                                prefs.edit().putString("scenes_json", scenes.toString()).apply()
                                Log.d(TAG, "授权成功，已保存 scenes: ${scenes.names()}")
                                // 立即应用窗口配置（离线期间云端可能已改过布局）；rev 要等素材对账跑完才记账
                                applySceneConfigs(scenes)
                                // 死分支，保留仅作前向兼容：服务端 sendAuthResponse 只是把 topic 里的
                                // deviceId 原样回显，不会改派新 uuid；而上面的 targetId 校验已经保证
                                // "要么等于本机 id 要么不处理"，所以这里 newDeviceId != deviceId 恒不成立。
                                // 真要让服务端改派身份，得先加"订阅新 topic + 退订旧 topic"的成对逻辑再启用。
                                val newDeviceId = resp.optString("device_id", "")
                                if (newDeviceId.isNotEmpty() && newDeviceId != deviceId) {
                                    deviceId = newDeviceId
                                    prefs.edit().putString("device_id", deviceId).apply()
                                    mqttClientId = deviceId
                                    val newCommandTopic = "xvj/device/$deviceId/command"
                                    mqttClient?.subscribe(newCommandTopic, 1)
                                    Log.d(TAG, "授权后更新订阅: $newCommandTopic")
                                }
                            }
                            // debug 字段服务端 auth_result 一直在带（sendAuthResponse 取房间 config.debug），
                            // 但本机以前只在 sync_room_materials / set_debug 里读 → 首次授权那一场同步里 prefs
                            // 还是上次遗留值，进度条和日志上报会慢一整轮。这里提前落一次，让同场同步按房间真值显示。
                            // 只在载荷确实带该字段时才覆盖（老服务端不带 → 保留本机现值）。
                            if (resp.has("debug")) {
                                setDebugMode(resp.optBoolean("debug", false))
                            }
                            // 窗口名/测试背景浮层同口径：载荷带了才覆盖（服务端 roomSyncFields 恒带，老服务端不带 → 保留本机现值）
                            if (resp.has("window_names")) {
                                setWindowNames(resp.optBoolean("window_names", false))
                            }
                            if (resp.has("window_testcard")) {
                                setWindowTestcard(resp.optBoolean("window_testcard", false))
                            }
                            // LOGO 辅助网格同口径：载荷带了才覆盖（LOGO 页开着时服务端恒带 true）
                            if (resp.has("logo_grid")) {
                                setLogoGrid(resp.optBoolean("logo_grid", false))
                            }
                            // 触发素材同步（映射真相在 scenes 里，folderMappings 仅作旧服务端兜底）；
                            // rev 随请求走，对账真的跑完才记进 appliedRev 给心跳对账用
                            if (folderMappings != null || scenes != null) {
                                Log.d(TAG, "授权成功: room_id=$roomId")
                                logToFile("开始根据房间配置同步素材...")
                                requestRoomMaterialSync(roomId, folderMappings ?: org.json.JSONObject(), scenes, rev)
                            }
                        } else {
                            prefs.edit().putBoolean("last_authorized", false).apply()
                            setStatus("设备未授权")
                            // 切欢迎视频（playWelcomeVideo 会连旧窗口一起清掉）
                            showUnauthorizedAlert(message)
                        }
                    }
                }
                "deauthorize" -> {
                    // 被远程废掉
                    logToFile("本机被废止: $targetId ${resp.optString("message", "")}", "ERROR", "AUTH")
                    mqttHandler.post {
                        // 撤掉冷启动缓存的放行标志：下次开机不再拿这份已失效的画面抢先播
                        prefs.edit().putBoolean("last_authorized", false).apply()
                        setStatus("设备已被远程废止")
                        showUnauthorizedAlert("设备已被远程废止，请联系管理员")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auth response parse error: ${e.message}")
            logToFile("Auth response 解析失败: ${e.message}")
        }
    }

    /**
     * 显示未授权提示并播放欢迎视频
     */
    private fun showUnauthorizedAlert(message: String) {
        // 交给 playWelcomeVideo：它内部先 releasePlayer + releaseAllWindows，旧窗口当场就清掉了
        playWelcomeVideo()
        Log.w(TAG, "UNAUTHORIZED: $message")
    }

    /**
     * 未授权/被废止时循环播放 res/raw/welcome：先 releasePlayer + releaseAllWindows 清掉窗口画面，
     * 再把单播放器 player 挂到 binding.playerView 上播 raw 资源。
     * 之后要等授权通过（auth_result 带 scenes）或下一次 sync_room_materials 重新 applySceneConfigs，
     * 画面才会回到窗口模式 —— 本函数自身不做"恢复窗口"这件事。
     */
    private fun playWelcomeVideo() {
        mqttHandler.post {
            try {
                // 先释放旧播放器
                releasePlayer()
                // 清除多窗口（含视图），避免欢迎视频被旧窗口遮挡
                releaseAllWindows()

                // 创建新播放器播放欢迎视频
                player = buildExoPlayer().apply {
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
                logToFile("播放欢迎视频失败: ${e.message}")
            }
        }
    }

    // P4 fix: MQTT 重连加指数退避，上限 5 分钟，防止死循环
    private var mqttReconnectDelaySeconds = 5
    private val mqttMaxReconnectDelaySeconds = 300
    private var mqttReconnectAttempts = 0

    // 单飞闸门：connectionLost 与"首次连接失败"两条路都会排重连，没有这道闸门时
    // 一次网络抖动就能排队好几个重连，之后每个又各自再排 —— 越重试越密。
    @Volatile private var mqttReconnectScheduled = false

    private fun reconnectMQTT() {
        if (mqttReconnectScheduled) {
            Log.d(TAG, "重连已在排队（${mqttReconnectDelaySeconds}s 后），忽略重复调度")
            return
        }
        mqttReconnectScheduled = true
        val delayMs = (mqttReconnectDelaySeconds * 1000).toLong()
        // 排程不能挂在 mqttHandler（=主线程 Looper）上：主线程一旦被窗口重建/解码器拖住，
        // 排队里的重连永远轮不到，设备就带着还在播的界面一起离线，直到有人手动点开 app。
        // 201 上 171 断线后 45 分钟不回，就是这个形状。
        // 显式写 Runnable：schedule 有 Runnable/Callable 两个重载，闭包末尾只要留出一个非 Unit
        // 返回值（比如 Handler.post 的 boolean），Kotlin 就会在两个重载之间报歧义。
        reconnectScheduler.schedule(Runnable {
            // 放行闸门：本次尝试若再失败，失败分支需要能重新排下一次
            mqttReconnectScheduled = false
            // Activity 已退场（OTA 安装/系统回收/kiosk 重启）就别再起连接：重连成功后 MQTT 回调
            // 会继续往已销毁的界面 post 状态文本、弹窗，客户端也再没人 disconnect
            if (isFinishing || isDestroyed) {
                Log.w(TAG, "Activity 已退场，放弃本次 MQTT 重连")
            } else {
                try { mqttClient?.close() } catch (e: Exception) {}
                connectMQTT()
                // OTA 轮询要弹权限引导/拉安装器，仍回主线程；它不在"链路先活过来"这条关键路径上
                mqttHandler.post { checkForUpdate() }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
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

    // 【A-04】 MQTT 消息处理 & 分发
    // @tag: handleCommand 命令分发 mqtt命令处理

    /**
     * 处理 MQTT 命令（xvj/device/{deviceId}/command）
     * @param json 命令 JSON，必须含 action 字段
     * 实际支持的 action（与服务端发送方一一对应）：
     *   stop                停掉全部播放
     *   exit_desktop        退出到桌面：释放画面并把原厂桌面顶到身前，进程/MQTT 全活着（与 stop 的差别见【A-15】）
     *   reopen_desktop      唤回画面：root 把自己顶回前台，回到前台那次按 prefs 的 scenes_json 重建
     *   reboot              远程重启整台设备（管理台设备卡「重启设备」；root reboot，无 root 只记日志）
     *   secondary_display   开关 1080/eDP 副屏（管理台设备卡「副屏」；root 写 DRM 连接器，无 root 只记日志，
     *                       实际状态由心跳 secondary_display 回传，见【A-18】）
     *   config              改 mqtt_server / loop（只影响单播放器 player，不影响窗口播放器）
     *   sync_room_materials 房间素材全量对账（服务端【S-07】/授权链路发出）
     *   update_windows      轻量窗口重排，不下素材（编辑器 live 实时预览）
     *   delete_material     删单个本地素材文件
     *   update              OTA：带 url/version/md5 → downloadAndInstall
     *   set_debug           立即开关 debug_mode（前端没有入口，只能手工 POST /api/devices/:id/command；
     *                       正常途径是 sync_room_materials / auth_result 载荷里的 debug 字段）
     *   set_wallpanel       立即开关 485 墙板串口链路 + 串口节点/字符格式/学习表（同 set_debug：
     *                       临时值，房间 config.wallpanel_* 才是真相，下一次带这些字段的载荷会覆盖）
     *   set_dmx             立即开关 DMX512 副板串口链路 + 节点/波特率（同 set_wallpanel 的临时值口径）
     *   set_window_names    立即开关窗口名浮层（管理台开关「窗口配置」时云端补的轻枪，同 set_debug 口径）
     *   set_window_testcard 立即开关测试背景浮层（与窗口名同批下发、同开同关，同 set_debug 口径）
     *   wallpanel_learn     开/关学习窗：开窗清空帧台账并在每一帧到达时上报候选列表，
     *                       页面据此让人选中格子后按键、把那一帧绑上去
     *   preview_material    在播这一格的所有窗口里同步试播一只本机素材，播完同步回到原画面
     *                       （目标窗口由设备按 folder_id 认，见 startGroupPreview）
     *   preview_stop        收掉试播、归还画面（按 folder_id 收那一格，不带则全收）
     * ⚠️ 没有 "sync"：服务端的 action:'sync' 只是 HTTP 入参，publish 前已改写成 sync_room_materials
     */
    private fun handleCommand(json: String) {
        try {
            val cmd = JSONObject(json)
            val action = cmd.getString("action")
            logToFile("收到命令: $action", "INFO", "MQTT", "COMMAND")

            when (action) {
                "stop" -> {
                    // 停单播放器（欢迎视频那条链）+ 释放全部窗口播放器，画面真正黑下去。
                    // 发送方有两处：废止设备（另有 deauthorize 广播会切欢迎视频）、
                    // DELETE /api/rooms/:id 删房间（补上 releaseAllWindows 后才真的停得住）。
                    // 不清 windowContentSigs：下次 applySceneConfigs 自己清，
                    // 而 applyLiveWindowUpdate 发现 flSurface 已无子视图会判定为结构变化并全量重建。
                    // ⚠️ 必须 post 到主线程：这两个函数会碰 PlayerView.setPlayer(null) 和
                    // flSurface.removeView()，在 Paho 回调线程上直接调会抛 IllegalStateException，
                    // 被 handleCommand 的 catch 吞成一条"消息处理异常"日志 —— 结果是云端下发的
                    // stop（删房间/废止）什么都没停住，画面继续播。
                    mqttHandler.post {
                        stopPlayback()
                        releaseAllWindows()
                    }
                }
                "exit_desktop", "reopen_desktop" -> {
                    // 退出到桌面/唤回（云端 + 本机左键连点，见【A-15】）：和 stop 的区别是
                    // 退出不是"停在停止态"，它是一次可逆的离场，回到画面由同一条链自己完成，
                    // 不需要云端重发配置。命令必须回显到 device_logs，否则现场只知道"桌面出来了"不知道是谁叫的。
                    mqttHandler.post { setExited(action == "exit_desktop", "云端命令") }
                }
                // 远程重启整台设备（设备卡「重启设备」）：不是重启 APK 进程，是 reboot 整机。
                // 先落日志再动手 —— reboot 一下去进程就没了，device_logs 里这一行是唯一的回执。
                // 与 OTA 共用 su 通道；没有 root 的机器只记一行拒绝，不去触发 su 授权框把线程吊住。
                "reboot" -> {
                    logToFile("收到重启命令：设备即将重启", "INFO", "MQTT", "REBOOT")
                    Thread {
                        if (!silentInstallReady()) {
                            logToFile("重启命令放弃：本机没有 root，无法执行 reboot（请现场断电或按键重启）",
                                "WARN", "MQTT", "REBOOT")
                            return@Thread
                        }
                        val done = execAsRoot("reboot", 5000)
                        if (done.first != 0) {
                            logToFile("reboot 未生效（code=${done.first}）：${done.second}", "ERROR", "MQTT", "REBOOT")
                        }
                    }.start()
                }
                // 实时截图（见【A-16】）：管理台要一帧当前落屏的画面。载荷由服务端补齐
                // （upload_url + 一次性 token，两者只进这台设备的私有主题），缺任一条就是命令没配好，
                // 记一行日志走人 —— 不去猜地址，也不接受调用方自带 URL。
                // 这里不吃 exited 闸门：退出态下屏上是原厂桌面，那正是远程管理要看的一种画面。
                "screenshot" -> {
                    val uploadUrl = cmd.optString("upload_url")
                    val shotToken = cmd.optString("token")
                    if (uploadUrl.isEmpty() || shotToken.isEmpty()) {
                        logToFile("实时截图：命令缺 upload_url/token（服务端未补全），已忽略", "WARN", "SYNC", "SCREENSHOT")
                    } else if (!isTrustedServerUrl(uploadUrl)) {
                        logToFile("实时截图：上传地址不是本站，已拒绝: $uploadUrl", "ERROR", "SYNC", "SCREENSHOT")
                    } else {
                        shotExecutor.execute { captureAndUploadScreenshot(uploadUrl, shotToken) }
                    }
                }
                // 副屏开关（管理台设备卡「副屏」）：1080/eDP 那条显示链开机默认关（vendor init rc），
                // 这里只做运行时切换。和 reboot 一样要 root，无 root 只记一行拒绝。
                // 执行完补一跳心跳：开关是"点了立刻想看到结果"的动作，不该等下一个 30 秒周期。
                "secondary_display" -> {
                    val on = cmd.optBoolean("on", false)
                    logToFile("副屏开关：${if (on) "开启" else "关闭"} 1080/eDP 显示链", "INFO", "MQTT", "DISPLAY")
                    Thread {
                        if (applySecondaryDisplay(on)) mqttHandler.post { sendStatus("online") }
                    }.start()
                }
                "config" -> {
                    // 更新配置
                    if (cmd.has("mqtt_server")) {
                        mqttServer = cmd.getString("mqtt_server")
                        prefs.edit().putString("mqtt_server", mqttServer).apply()
                    }
                    if (cmd.has("loop")) {
                        loopPlay = cmd.getBoolean("loop")
                        // repeatMode 属于播放器状态，只能在创建 ExoPlayer 的主线程上改
                        mqttHandler.post {
                            player?.repeatMode = if (loopPlay) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                        }
                    }
                }
                "sync_room_materials" -> {
                    // 接收房间素材同步，下载到本地文件夹
                    val roomId = cmd.optString("room_id", "")
                    val folderMappings = cmd.optJSONObject("folder_mappings")
                    // debug 只在命令确实带这个字段时才覆盖：服务端只在 sync_room_materials / auth_result /
                    // set_debug 三种载荷里带它（update_windows 就不带），用 optBoolean 会把"没带"当成 false，
                    // 手工推的指令漏了字段就会把设备调试模式误关掉
                    val hasDebug = cmd.has("debug")
                    val debug = hasDebug && cmd.optBoolean("debug", false)
                    if (hasDebug) setDebugMode(debug)
                    // 窗口名/测试背景浮层同 debug 口径：载荷带了才覆盖（update_windows 不带 → 不动本地状态）
                    if (cmd.has("window_names")) setWindowNames(cmd.optBoolean("window_names", false))
                    if (cmd.has("window_testcard")) setWindowTestcard(cmd.optBoolean("window_testcard", false))
                    if (cmd.has("logo_grid")) setLogoGrid(cmd.optBoolean("logo_grid", false))
                    // 墙板字段与 debug 同口径：只在载荷真带字段时才覆盖（服务端 roomWallpanelFields 恒算出
                    // 确定形状，sync_room_materials / auth_result 两条载荷都带；update_windows 不带 → 不动本地状态）
                    // 字段先落 prefs 再收放链路，同一跳里改了节点/波特率才只开一次（见 auth_result 处注释）
                    applyWallPanelFields(cmd)
                    // DMX 同口径：载荷带了 dmx_* 才覆盖，先落 prefs 再收放链路
                    applyDmxFields(cmd)
                    // 输出基准同样先落账：下面两条 apply 路径都 post 到主线程，改完尺子再排队，
                    // 排到时拿到的已是新分母（基准变了但 scenes 没变也要重排，见 scenesFingerprint）
                    applyOutputField(cmd)
                    // LOGO 与输出基准同口径：先落配置，下面 applySceneConfigs 收尾才会按新 LOGO 刷层
                    applyLogoField(cmd)
                    if (cmd.has("wallpanel_enabled")) {
                        applyWallPanelEnabled(cmd.optBoolean("wallpanel_enabled", false))
                    }
                    if (cmd.has("dmx_enabled")) {
                        applyDmxEnabled(cmd.optBoolean("dmx_enabled", false))
                    }
                    val fmKeys = java.lang.StringBuilder()
                    folderMappings?.keys()?.let { val k = it; while (k.hasNext()) { fmKeys.append(k.next()).append(",") } }
                    logToFile("sync_room_materials: roomId=$roomId, folderMappings=" + fmKeys.toString() + ", debug=$debug")

                    // 先把窗口配置摆上屏（不等素材下载完，改布局时现场要立刻看到），再进去重后的对账。
                    // 对账结束时还会 apply 一次，但那次带 force：真下载了东西才重建，否则按结构指纹跳过，
                    // 所以一条同步命令不再等于两轮全量重建。scenes_json 的落盘统一在同步轮里做。
                    val scenes = cmd.optJSONObject("scenes")
                    if (scenes != null) {
                        Log.d(TAG, "已收到 scenes: ${scenes.names()}")
                        // applySceneConfigs 含 addView/removeView 等 UI 操作，必须在主线程执行
                        mqttHandler.post { applySceneConfigs(scenes) }
                    } else {
                        // 无 scenes 时尝试从本地缓存恢复（离线场景）
                        val cached = prefs.getString("scenes_json", null)
                        if (cached != null) {
                            try {
                                mqttHandler.post { applySceneConfigs(org.json.JSONObject(cached)) }
                                Log.d(TAG, "从本地缓存恢复 scenes 成功")
                            } catch (e: Exception) {
                                Log.e(TAG, "从本地缓存恢复 scenes 失败: ${e.message}")
                                logToFile("从本地缓存恢复 scenes 失败: ${e.message}")
                            }
                        }
                    }

                    // 合并 Scene A + B，一次同步完成（映射真相在 scenes 里，folderMappings 为兜底）
                    if (folderMappings != null || scenes != null) {
                        val foldersStr = java.lang.StringBuilder()
                        val k = folderMappings?.keys()
                        k?.let { while (it.hasNext()) { foldersStr.append(it.next()).append(",") } }
                        logToFile("准备同步素材: roomId=$roomId, folders=" + foldersStr)
                        requestRoomMaterialSync(roomId, folderMappings ?: org.json.JSONObject(), scenes,
                            cmd.optString("rev", ""))
                    } else {
                        logToFile("folderMappings 与 scenes 均为 null，跳过素材同步")
                    }
                }
                "update_windows" -> {
                    // 实时窗口预览（云端编辑器拖动/调参的轻量推送）：
                    // 只更新现有视图的位置/透明度/亮度/变换，不动播放器不触发素材同步；
                    // 结构变化（增删窗口/内容/显隐/z序）内部自动回退全量重建
                    val scenes = cmd.optJSONObject("scenes")
                    if (scenes != null) {
                        prefs.edit().putString("scenes_json", scenes.toString()).apply()
                        val rev = cmd.optString("rev", "")
                        mqttHandler.post {
                            // 基准与窗口在同一份载荷里：先换尺再重排，只改基准时也是这条轻路径原地重算几何
                            applyOutputField(cmd)
                            // LOGO 落账先于 applyLiveWindowUpdate：它收尾按新配置刷 LOGO 层
                            applyLogoField(cmd)
                            applyLiveWindowUpdate(scenes)
                            // 服务端只在"素材映射没变、只有窗口变了"时走这条通道，
                            // 所以几何应用完就等于配置已落地，rev 可以直接记账
                            markAppliedRev(rev)
                        }
                    } else {
                        logToFile("update_windows: scenes 为空，忽略", "WARN", "WINDOW", "CONFIG")
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
                                android.widget.Toast.makeText(this, "正在下载更新: $version", android.widget.Toast.LENGTH_LONG).show()
                            } catch(e: Exception) {}
                        }
                        // 下载并提示用户安装（云端命令现已携带 md5）
                        downloadAndInstall(url, version, cmd.optString("md5", null))
                    } else if (serverCode <= VERSION_CODE) {
                        Log.d(TAG, "OTA: 当前已是最新版本 ($VERSION_CODE >= $serverCode)")
                    }
                }
                "set_debug" -> {
                    // 手动开关，和 auth_result / sync_room_materials 的 debug 字段同走 setDebugMode。
                    // 它是临时值：下一次载荷会按房间配置覆盖（房间 config.debug 才是真相）。
                    val debug = cmd.optBoolean("debug", false)
                    setDebugMode(debug)
                    Log.d(TAG, "set_debug: debug=$debug")
                    logToFile("调试模式变更: debug=$debug")
                }
                "set_wallpanel" -> {
                    // 墙板总闸的手动开关，和 sync_room_materials / auth_result 载荷里的
                    // wallpanel_enabled 同走 applyWallPanelEnabled。同 set_debug 一样是临时值：
                    // 房间 config.wallpanel_enabled 才是真相，下一次载荷会按它覆盖。
                    val enabled = cmd.optBoolean("wallpanel_enabled", false)
                    applyWallPanelFields(cmd)   // 先落节点/速率/字符格式/学习表，再按新参数收放链路（同 auth_result 处口径）
                    applyWallPanelEnabled(enabled)
                    Log.d(TAG, "set_wallpanel: enabled=$enabled")
                    logToFile("墙板开关变更: enabled=$enabled 串口=${wallPanelPath()} 波特率=${wallPanelBaud() ?: "驱动默认"} ${bitsFormat(wallPanelSerialBits())}")
                }
                "set_dmx" -> {
                    // DMX512 副板总闸/串口的手动开关（云端 roomDmxFields 变化时补一枪）。
                    // 同 set_wallpanel：临时值，房间 config.dmx_* 才是真相，下一次载荷按它覆盖。
                    val enabled = cmd.optBoolean("dmx_enabled", false)
                    applyDmxFields(cmd)
                    applyDmxEnabled(enabled)
                    Log.d(TAG, "set_dmx: enabled=$enabled")
                    logToFile("DMX 开关变更: enabled=$enabled 串口=${dmxPath()} 波特率=${dmxBaud()}", "INFO", "DMX", "CONFIG")
                }
                "set_window_names" -> {
                    // 窗口名浮层的手动开关（管理台打开/关掉「窗口配置」时由云端漏斗补一枪）。
                    // 同 set_debug：临时值，房间 config.window_names 才是真相，下一次载荷按它覆盖。
                    val on = cmd.optBoolean("window_names", false)
                    setWindowNames(on)
                    Log.d(TAG, "set_window_names: on=$on")
                    logToFile("窗口名浮层: ${if (on) "开" else "关"}", "INFO", "WINDOW", "CONFIG")
                }
                "set_window_testcard" -> {
                    // 测试背景浮层的手动开关，与窗口名同开同关（云端漏斗同一批补枪）。
                    // 同 set_debug：临时值，房间 config.window_testcard 才是真相，下一次载荷按它覆盖。
                    val on = cmd.optBoolean("window_testcard", false)
                    setWindowTestcard(on)
                    Log.d(TAG, "set_window_testcard: on=$on")
                    logToFile("测试背景浮层: ${if (on) "开" else "关"}", "INFO", "WINDOW", "CONFIG")
                }
                "set_logo_grid" -> {
                    // LOGO 设置辅助网格的手动开关（管理台打开/关掉「LOGO设置」时由云端漏斗补一枪）。
                    // 同 set_window_names：临时值，房间 config.logo_grid 才是真相，下一次载荷按它覆盖。
                    val on = cmd.optBoolean("logo_grid", false)
                    setLogoGrid(on)
                    Log.d(TAG, "set_logo_grid: on=$on")
                    logToFile("LOGO 辅助网格: ${if (on) "开" else "关"}", "INFO", "LOGO", "CONFIG")
                }
                "wallpanel_learn" -> {
                    // 学习窗（云端【S-06f】）：只控制"要不要把每一帧的候选列表即时上报"，
                    // 不动链路、不动画面 —— 学习期间按下的键照常生效（已知帧族仍然切格），
                    // 现场不会因为你正在配绑定而看到两套行为。
                    val on = cmd.optBoolean("on", true)
                    val seconds = cmd.optInt("seconds", 20)
                    mqttHandler.post { if (on) startWallPanelLearn(seconds) else stopWallPanelLearn() }
                }
                "preview_material" -> {
                    // 大屏试播（云端【S-05p】POST /api/rooms/:id/preview）：带幕前缀的物理目录、落盘文件名
                    // 由服务端按房间当前配置算好。win_ids 只是兜底名单 —— 真正的目标窗口由设备按
                    // "谁在播这一格" 定（见 previewTarget）：云端那版配置可能已经比设备旧一次下发。
                    // ⚠️ 必须 post 到主线程：播放器只能在创建它的主线程上换 playlist。
                    val arr = cmd.optJSONArray("win_ids")
                    val winIds = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
                    val single = cmd.optString("win_id", "")
                    val folderId = cmd.optString("folder_id", "")
                    val filename = cmd.optString("filename", "")
                    val maxMs = cmd.optLong("max_ms", 300_000L)
                    mqttHandler.post {
                        startGroupPreview(folderId, if (winIds.isEmpty()) listOf(single) else winIds, filename, maxMs)
                    }
                }
                "preview_stop" -> {
                    // 试播的账按格子记，所以收回也按格子认：folder_id 是判据。
                    // 云端点名的 win_id 是 zIndex 最高那扇，共享解码下它多半是没有播放器的成员，
                    // 照 win_id 找会一声不响地找不到 —— 页面却已经清了状态条，看起来像"提前结束"按了没用。
                    // 不带 folder_id = 这台设备上所有在飞的试播全部收回（配置改动后那一格解析不出窗口时的兜底）
                    val folderId = cmd.optString("folder_id", "")
                    mqttHandler.post {
                        if (folderId.isEmpty()) previews.keys.toList().forEach { endPreview(it, "手工停止") }
                        else endPreview(folderId, "手工停止")
                    }
                }
                else -> {
                    // 白名单外的 action（服务端 DEVICE_ACTIONS 已拦一道，这里只是漏网兜底）：
                    // 不留一行的话，现场"发了命令没反应"只能靠猜
                    Log.w(TAG, "Unknown command action: $action")
                    logToFile("未知命令 action=$action，已忽略", "WARN", "MQTT", "COMMAND")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command parse error: ${e.message}")
            logToFile("命令处理异常: ${e.message}", "ERROR", "MQTT", "COMMAND")
        }
    }

    // 【A-06】 素材同步
    // @tag: requestRoomMaterialSync 素材同步 房间同步 对账 去重
    // @tag: syncFolderWithIds 文件夹同步 下载同步
    // @tag: deleteMaterialFile 删除素材 文件删除
    // @tag: runDownloadPlan 下载进度条 进度
    /**
     * 一轮同步里"确定要下载"的单个文件。
     * 把"比对"和"下载"分成两段（计划 → 执行），是因为边比对边下载时分母未知，进度条只能按文件数粗算；
     * 先凑齐 A/B 两幕的整批任务，才能按云端下发的字节数算出真实百分比。
     * @param size 云端 room-materials-v2 现算的磁盘字节数；取不到时为 0，执行阶段按已知任务的中位数折算
     */
    private data class SyncTask(
        val folderId: String,
        val destFile: File,
        val url: String,
        val filename: String,
        val size: Long
    )

    /**
     * downloadWithETag 的一次落地结果。**必须三态**：以前只回 Boolean，"服务端说没变"和
     * "下载失败"都是 false，调用方无法区分，于是"这一轮到底有没有真拿到东西"只能拿计划条数凑
     * （见 runDownloadPlan 的注释）。
     *  - WRITTEN   真的写完并落盘，字节数与云端 size 相符（或云端没给 size）
     *  - UNCHANGED 条件请求回 304，本地文件原样可用
     *  - FAILED    网络异常 / 写一半断流 / 实到字节与云端 size 不符（宁可当没拿到）
     */
    private enum class DownloadResult { WRITTEN, UNCHANGED, FAILED }

    /** 一轮下载计划的执行汇总：written=真落地了几个，failed=没拿到几个 */
    private data class PlanResult(val written: Int, val failed: Int)

    /**
     * 素材同步的唯一入口，按房间做在飞去重：
     * 该房间已有一轮对账在跑时，这次请求只被记成"最后待补跑的那一份"（同房间互相覆盖，中间态没有意义），
     * 当前轮跑完立刻补一轮。并发的两轮会抢同一批文件、进度条来回刷屏，而且后一轮拿的清单更新，
     * 先一轮的下载纯属浪费。服务端对 {id, fingerprint} 去重双发、心跳对账补发，都会打在这里。
     * @param rev 服务端这次的配置指纹（roomSceneDigest），对账跑完才记进 appliedRev 供心跳回传
     */
    private fun requestRoomMaterialSync(
        roomId: String,
        folderMappingsFallback: org.json.JSONObject,
        scenes: org.json.JSONObject?,
        rev: String
    ) {
        val req = SyncRequest(roomId, folderMappingsFallback, scenes, rev)
        var start = false
        synchronized(syncLock) {
            start = syncInFlight.add(roomId)   // add 返回 false == 这个房间已经有一轮在飞
            if (!start) syncPending[roomId] = req
        }
        if (!start) {
            logToFile("房间 $roomId 同步已在飞，本次指令并入待补跑 (rev=$rev)", "INFO", "SYNC", "SKIP")
            return
        }
        setStatus("正在核对房间素材…")
        downloadExecutor.submit { runRoomSyncLoop(req) }
    }

    /** 工作线程：跑一轮 → 看有没有并进来的后指令 → 有就再跑一轮，直到追平（同一时刻每房间只有一个循环在飞） */
    private fun runRoomSyncLoop(first: SyncRequest) {
        var round = first
        while (true) {
            doRoomMaterialSync(round)
            var next: SyncRequest? = null
            synchronized(syncLock) {
                val n = syncPending.remove(round.roomId)
                // rev 已经应用过了 ⇒ 这条是重复触发，不必再跑；rev 为空的（手工指令/老服务端）无从判断，照常跑。
                // appliedRev 由主线程写、这里读，最坏是多补一轮，不会漏。
                if (n != null && (n.rev.isEmpty() || n.rev != appliedRev)) next = n
                if (next == null) syncInFlight.remove(round.roomId)
            }
            val n = next ?: return
            logToFile("对账期间又收到同步指令 (rev=${n.rev})，立即补跑一轮", "INFO", "SYNC", "SKIP")
            round = n
        }
    }

    /**
     * 一轮房间素材对账（一次 HTTP + A/B 两幕逐目录 diff）：
     * 1) 载荷带 scenes 才落 prefs 缓存（null 是"这次没带"，不是"房间没配置"，写成空串会清掉冷启动缓存）；
     *    房间 id 本机不再缓存（原 current_room_id 只写不读，已删）。
     * 2) fetchRoomMaterials 拉 /api/room-materials-v2/{roomId} 的 A01/B01 全量清单；
     *    返回 null（请求失败）时直接中止本轮，绝不进"清空"分支，rev 也不记账（让心跳对账再补一次）。
     * 3) syncSceneFolders("A"/"B") 按 scenes.A/B.folder_mappings 对账：映射有变的下、多的删，
     *    只**收集**待下载任务不真下载；映射真相只在 scenes 里，
     *    folderMappingsFallback 仅给旧服务端兜底，B 幕缺失时按空映射处理。
     * 4) runDownloadPlan 按字节进度逐个下载（调试模式下窗口正中进度条可见），串行、单线程，
     *    回来的是 PlanResult(written, failed)——"下成了几个、没下成几个"，不是"排了几个任务"。
     * 5) 回主线程 applySceneConfigs：优先用本次载荷的 scenes，没有才回落 prefs 缓存；
     *    新素材落格由 applySceneChangesInPlace 逐窗比磁盘清单（playlistStale）原地换清单，
     *    不再 force 整屏重建 —— 整屏重建一次内核侧净漏 ~97MB dma-buf（台架实测，见该函数注释）。
     * 6) rev 只在 failed==0（整轮真的落地）时才记账；有失败就留给心跳对账下一轮补发。
     */
    private fun doRoomMaterialSync(req: SyncRequest) {
        val roomId = req.roomId
        val scenes = req.scenes
        try {
            if (scenes != null) {
                prefs.edit().putString("scenes_json", scenes.toString()).apply()
            }

            val fmA = scenes?.optJSONObject("A")?.optJSONObject("folder_mappings") ?: req.folderMappingsFallback
            val fmB = scenes?.optJSONObject("B")?.optJSONObject("folder_mappings") ?: org.json.JSONObject()

            // 拉取失败返回 null：保持本地现状中止本轮，绝不进入清空分支
            val allMaterials = fetchRoomMaterials(roomId)
            if (allMaterials == null) {
                hideSyncProgress()   // fetchRoomMaterials 内部已把状态文字改成失败文案，这里只收条
                return
            }

            val plan = ArrayList<SyncTask>()
            plan.addAll(syncSceneFolders("A", fmA, allMaterials))
            plan.addAll(syncSceneFolders("B", fmB, allMaterials))
            if (plan.isEmpty()) logToFile("素材对账完成: 无需下载（映射内素材本地已齐）")
            val res = runDownloadPlan(plan)
            val complete = res.failed == 0
            // 完成是回执，亮一下就走；有失败就常驻到下一次状态覆盖（那才是要人处理的）
            if (complete) hideSyncProgress("素材同步完成", STATUS_ACK_MS)
            else hideSyncProgress("素材同步未完成：${res.failed} 个文件未落地")

            // 下载过东西不再当成"必须整屏重建"的理由：新素材落进哪一格，applySceneChangesInPlace 就
            // 逐窗比一遍磁盘清单（playlistStale），只给那一格换清单，播放器和 surface 一个都不动。
            // 以前这里写的是 force = plan.isNotEmpty()（排了任务就算下载成功），于是网络抖一下、
            // 十个文件全失败也照样把 6 路解码器推倒重来（画面白闪）；后来改成 written>0 才 force，
            // 白闪是不闪了，但每次推素材整屏重建一次 —— 台架实测每重建一次内核侧净漏 ~97MB dma-buf
            // 且不归还，十几次就把 4GB 的板子顶到 lmkd 开杀（现场表现是"推素材时 APP 闪退"）。
            mqttHandler.post {
                val scenesToApply = scenes ?: prefs.getString("scenes_json", null)?.let {
                    try { org.json.JSONObject(it) } catch (e: Exception) { null }
                }
                if (scenesToApply != null) {
                    try {
                        // refreshIfStale 与 written 无关：服务端删素材/清映射时本轮一个字节都不下，
                        // 但清单同样过期。没变的那几格 playlistStale 不响，换来的是"只换 0 路"。
                        applySceneConfigs(scenesToApply, refreshIfStale = true)
                    } catch (e: Exception) {
                        Log.e(TAG, "applySceneConfigs 失败: ${e.message}")
                    }
                }
                // rev 记账 = 向服务端承诺"本机已经追平这个配置指纹"，所以只在整轮真的落地后才报：
                // 素材齐（无失败）、窗口已按新配置重建。有任何一个文件没下来就不报，
                // 让心跳对账（服务端 REV_RECONCILE_MIN_INTERVAL 节流）在下一轮补发时重试。
                // 以前的写法是无条件 markAppliedRev —— 下载全失败也报追平，服务端从此闭嘴，
                // 现场表现就是"房间永远少几个素材，点同步也没反应"，只能改一次配置才会再试。
                if (complete) markAppliedRev(req.rev)
                else logToFile("本轮 ${res.failed} 个素材未落地，rev 不记账，等待对账补发 (rev=${req.rev})",
                    "WARN", "SYNC", "REV")
            }
            logToFile("房间素材同步完成: " + roomId + (if (complete) "" else "（部分失败 ${res.failed}）"))
        } catch (e: Exception) {
            Log.e(TAG, "Room materials sync error: " + e.message)
            logToFile("房间素材同步异常: ${e.message}", "ERROR", "SYNC", "ERROR")
            hideSyncProgress("素材同步失败")
        }
    }

    /**
     * 执行下载计划：串行下载 + 按字节刷新进度（进度只在 debug_mode 下上屏，见 updateSyncProgress）。
     * 返回 PlanResult，由调用方决定"这一轮的 rev 能不能记账"——只要有一个文件没落地，
     * 这轮配置就不算应用完成（见 doRoomMaterialSync）。
     *
     * 分母口径：云端每条素材带 size（服务端 statSync 现算）。size 全为 0（老服务端 / 文件已删）时
     * 回落到"按文件数"计——每个任务按 1 字节算，进度仍是单调的。部分有 size 时，0 大小的任务
     * 按已知任务的中位数折算（不用平均数：一个 2GB 素材会把平均数撑爆、让其余小文件瞬间跑完）。
     *
     * 计划阶段（syncFolderWithIds）已经把 md5 一致的、304 命中的都尽量剔掉了，剩下的是真要下载的；
     * 但 downloadWithETag 仍可能返回 UNCHANGED（这条路径的 ETag 未变 → 304，见 syncFolderWithIds 的判定顺序），
     * 此时按"整只文件大小"计入已完成字节，否则进度条会卡在最后几个文件上下不来。
     * 失败的（FAILED）同样计入已完成：进度条是"跑到哪儿了"，不是"成没成"，成败由返回值交代。
     */
    private fun runDownloadPlan(tasks: List<SyncTask>): PlanResult {
        if (tasks.isEmpty()) return PlanResult(0, 0)

        val known = tasks.map { it.size }.filter { it > 0L }.sorted()
        val fallback = if (known.isEmpty()) 1L else known[known.size / 2]
        val weights = tasks.map { if (it.size > 0L) it.size else fallback }
        val total = weights.fold(0L) { a, b -> a + b }

        // 起头显一次：0% 也要让现场看到"开始同步了"（中位数折算完才知道总字节数）
        updateSyncProgress(0L, total, tasks.size, 0, "准备中")

        var done = 0L
        var written = 0
        var failed = 0
        for (idx in tasks.indices) {
            val t = tasks[idx]
            val w = weights[idx]
            logToFile("准备下载: ${t.folderId}/${t.filename} (${w / 1024}KB)")
            val progressCb: (Long) -> Unit = { downloadedBytes ->
                updateSyncProgress(done + downloadedBytes, total, tasks.size, idx, t.filename)
            }
            when (downloadWithETag(t.url, t.destFile, t.size, progressCb)) {
                DownloadResult.WRITTEN -> written++
                DownloadResult.FAILED -> failed++
                DownloadResult.UNCHANGED -> { /* 本地已是最新，两个计数都不加 */ }
            }
            done += w
            updateSyncProgress(done, total, tasks.size, idx, t.filename)
        }
        return PlanResult(written, failed)
    }

    /**
     * 下载进度的上屏出口（显 + 百分比 + 文案）。
     *
     * 可见范围 = 仅房间调试模式（prefs.debug_mode）：设备接投影/大屏时观众看得到画面，
     * 常态播放页不该冒出进度条；非调试模式直接 return，只留 logToFile。
     * 面板的显隐统一归 applyStatusUi() 管，这里这次判空只是让下载线程不必多绕一趟主线程。
     *
     * 线程：只在 downloadExecutor 线程被调用（开环 0%、256KB 字节回调、每个文件收尾各一次），
     * 百分比在调用线程算好后连文案一起 post 给主线程 —— 不留任何共享可变状态，
     * 理论上两轮同步并发时最坏只是显示上互相覆盖一下，不会算错或崩。
     *
     * 文案分两行：上行"同步素材 3/8 · 45%"是远看的那一眼，下行文件名给现场认出卡在哪只。
     */
    private fun updateSyncProgress(doneBytes: Long, totalBytes: Long, totalFiles: Int, taskIndex: Int, filename: String) {
        if (!prefs.getBoolean("debug_mode", false)) return
        val done = doneBytes.coerceAtMost(totalBytes)
        val percent = if (totalBytes <= 0L) 100 else ((done * 100) / totalBytes).toInt().coerceIn(0, 100)
        val text = "同步素材 ${taskIndex + 1}/$totalFiles · $percent%\n$filename"
        setSyncProgress(percent, text)
    }

    /**
     * 收掉进度条（可选同时改写状态文字）。
     * 进度条只有一处开（updateSyncProgress）和两处关（本函数、自动收起的 renderStatus），别处不要再碰它。
     * 不带 status 时只收条不动文字（失败文案已由 fetchRoomMaterials 等处自己写好）。
     */
    private fun hideSyncProgress(status: String? = null, holdMs: Long = 0L) {
        stopSyncProgress(status, holdMs)
    }

    /**
     * 拉取房间素材清单（/api/room-materials-v2，A01/B01 键）
     * 失败返回 null——与「清单为空」严格区分：只有成功拉取的空清单才允许触发清空
     */
    private fun fetchRoomMaterials(roomId: String): MutableMap<String, org.json.JSONArray>? {
        return try {
            val apiUrl = java.net.URL(APK_URL + "/api/room-materials-v2/" + roomId)
            logToFile("HTTP 请求: $apiUrl")
            val connection = apiUrl.openConnection()
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            val response = connection.inputStream.bufferedReader().readText()
            logToFile("HTTP 响应长度: ${response.length}, 前100字符: ${response.take(100)}")
            val resultJson = org.json.JSONObject(response)
            val allMaterials = mutableMapOf<String, org.json.JSONArray>()
            val keys = resultJson.keys()
            while (keys.hasNext()) {
                val folderId = keys.next()
                if (folderId != "debug") {
                    allMaterials[folderId] = resultJson.getJSONArray(folderId)
                }
            }
            logToFile("获取房间素材成功: " + allMaterials.size + " 个文件夹")
            allMaterials
        } catch (e: Exception) {
            Log.e(TAG, "获取房间素材失败: " + e.message)
            logToFile("获取房间素材失败，中止本轮同步保持本地现状: " + e.message, "ERROR", "SYNC", "ERROR")
            setStatus("素材同步失败：云端不可达")
            null
        }
    }

    /**
     * 对账单幕 30 个文件夹：映射有素材 → 比对出待下载任务、删除多余；未映射或空 → 清空本地目录
     * @return 本幕所有文件夹凑出的下载计划（不在此处下载，交给 runDownloadPlan 统一跑进度）
     */
    private fun syncSceneFolders(scenePrefix: String, folderMappings: org.json.JSONObject, allMaterials: MutableMap<String, org.json.JSONArray>): List<SyncTask> {
        val tasks = ArrayList<SyncTask>()
        for (i in 1..30) {
            val folderNum = String.format("%02d", i)
            val prefixedKey = scenePrefix + folderNum  // "A01", "B02"
            // 键格式兼容：统一带前缀（"A01"）；纯编号（"01"）为旧数据兜底
            val materialIds = folderMappings.optJSONArray(prefixedKey) ?: folderMappings.optJSONArray(folderNum)
            if (materialIds != null && materialIds.length() > 0) {
                tasks.addAll(syncFolderWithIds(prefixedKey, materialIds, allMaterials[prefixedKey]))
            } else {
                deleteFolderFiles(prefixedKey)
            }
        }
        return tasks
    }

    /**
     * 按 material IDs 精确比对单个文件夹 —— **只做计划，不下载**
     * @param folderId   文件夹 ID（支持 scene-prefixed 格式，如 "A01"）
     * @param materialIds 要同步的素材 ID 数组
     * @param cloudList  预获取的云端素材列表（可避免重复请求）
     * @return 该文件夹需要下载的任务（由调用方攒齐后交给 runDownloadPlan，进度条才知道总字节数）
     * 流程：比对本地与云端素材，缺的/变的进计划，多的删掉
     * 判定顺序（这决定了"为什么改了文件设备却不更新"）：
     *   1. 本地存在该文件 && 云端 md5 非空 && 本地 md5 相等 → 跳过，连请求都不发
     *   2. 本地存在 && 云端 md5 为空 && 本机没有该文件的 ETag/Last-Modified 缓存 && 字节数与云端 size 一致
     *      → 跳过（【size 兜底】，见下面的行内注释）。装 APK 清了 prefs 时靠这条免掉整目录重下。
     *   3. 其余情况一律进计划，由 downloadWithETag 先看按<目录/文件>存的 ETag，
     *      命中 304 就直接 return UNCHANGED —— 也就是"云端 md5 变了但 ETag 没变"会被静默吃掉，
     *      本地保持旧文件、下一轮同步再重复报不一致。云端就地替换素材字节时必须让 ETag 一起变。
     *   云端 md5 为空（老记录、图片素材、回填前的预设引用）时平时只靠 ETag 去重；ETag 也没了才退到第 2 条的字节数判定。
     */
    private fun syncFolderWithIds(folderId: String, materialIds: org.json.JSONArray, cloudList: org.json.JSONArray?): List<SyncTask> {
        val tasks = ArrayList<SyncTask>()
        try {
            if (cloudList == null) {
                // 映射说这个文件夹有 N 个素材，清单里却连键都没有 —— 两份视图对不上，
                // 这是信息缺失而不是"云端说这里空了"。以前一律当后者处理，等于清单少一个键
                // 就把本地整个目录删光（服务端一次异常/版本不一致就能清空现场素材）。
                // 现在跳过这个文件夹：不下也不删，等下一轮对账（rev 没记账，一定会再来）。
                logToFile("清单缺少 $folderId 的条目（映射有 ${materialIds.length()} 个），本轮跳过该文件夹",
                    "WARN", "SYNC", "SKIP")
                return tasks
            }
            if (cloudList.length() == 0) {
                // 键在、数组为空 = 云端按这些 id 查过素材表，一只都不存在（多半是素材被删了而映射还留着）。
                // 这是明确指令，本地该文件夹就该清空。
                Log.d(TAG, "文件夹 " + folderId + " 无云端素材")
                deleteFolderFiles(folderId)
                return tasks
            }

            val idsSet = mutableSetOf<String>()
            for (i in 0 until materialIds.length()) {
                idsSet.add(materialIds.getString(i))
            }

            // 解析 scene-prefixed folder ID（"A01" → scenea/01）；目录名也来自云端，一并校验
            val localFolder = materialDirOf(folderId)
            if (localFolder == null) {
                logToFile("清单里 $folderId 的目录名非法，本轮跳过该文件夹", "WARN", "SYNC", "SKIP")
                return tasks
            }
            if (!localFolder.exists()) localFolder.mkdirs()

            val localFiles = localFolder.listFiles()?.filter {
                it.extension.lowercase() in VIDEO_EXTS
            } ?: emptyList()

            val shouldExist = mutableSetOf<String>()
            var sizeSkipped = 0   // 本轮按 size 兜底跳过的只数，>0 时目录级报一行（逐只报会刷屏）

            for (i in 0 until cloudList.length()) {
                val item = cloudList.getJSONObject(i)
                val cloudId = item.optString("id", "")
                if (cloudId.isNotEmpty() && idsSet.contains(cloudId)) {
                    val filename = item.getString("filename")
                    val urlPath = item.optString("url", "")
                    // filename 同样来自云端 JSON：过 materialFileOf，避免 "../" 写到目录外
                    val localFile = materialFileOf(localFolder, filename)
                    if (localFile == null) {
                        Log.w(TAG, "跳过非法素材文件名: $filename")
                        logToFile("清单里文件名非法，跳过: folder=$folderId file=$filename", "WARN", "SYNC", "SKIP")
                        continue
                    }
                    shouldExist.add(localFile.name)
                    val downloadUrl = if (urlPath.startsWith("http")) urlPath else APK_URL + urlPath

                    val md5 = item.optString("md5", "")
                    // 字节数由云端 statSync 现算（老服务端没这个字段时 optLong 给 0，执行阶段按中位数折算）
                    val cloudSize = item.optLong("size", 0L)
                    if (localFile.exists()) {
                        if (md5.isNotEmpty()) {
                            if (cachedMd5(localFile) == md5) {
                                Log.d(TAG, "文件已存在且MD5一致: " + filename)
                                continue
                            }
                        } else if (cloudSize > 0L && localFile.length() == cloudSize &&
                            readHttpCache(PREF_ETAG_PREFIX, localFile) == null &&
                            readHttpCache(PREF_LM_PREFIX, localFile) == null
                        ) {
                            // 【size 兜底】只在"云端给不出内容指纹 + 本机也没有可复核的 ETag/Last-Modified"时启用：
                            // 装 APK 会把 prefs 一起清掉（168 装机实测），缓存空了就只能无条件 GET 全量重下；
                            // 而清单里的 md5 常常是空串 —— 图片素材上传时压根没算摘要（云端写入侧 2026-09-21 才补，
                            // 存量另有一轮回填），空值经 cp2p 原样带进预设行，于是"本地已有同一只文件"
                            // 这件事没有任何可比对的凭据，只能整目录重下一遍。
                            // 判定收紧到"无复核凭据"是必要的：常态下 nginx 的 ETag 能识破"就地替换同尺寸文件"，
                            // 一旦让字节数就当通行证跳过，那种替换就再也发现不了了。
                            sizeSkipped++
                            Log.d(TAG, "无 md5 也无 ETag 缓存，按字节数一致跳过: " + filename)
                            continue
                        }
                    }
                    Log.d(TAG, "计划下载: " + filename)
                    tasks.add(SyncTask(folderId, localFile, downloadUrl, filename, cloudSize))
                }
            }

            for (file in localFiles) {
                if (!shouldExist.contains(file.name)) {
                    Log.d(TAG, "删除不在清单中的文件: " + file.name)
                    logToFile("删除: " + file.name)
                    // 与 deleteMaterialFile/deleteFolderFiles 同一套约定：删本地文件必须一起清缓存，
                    // 否则该素材日后重新加回房间时，downloadWithETag 会拿旧 ETag 换到 304 而永远不重下。
                    clearMaterialCache(file)
                    file.delete()
                }
            }
            Log.d(TAG, "文件夹 " + folderId + " 对账完成: " + tasks.size + " 个待下载")
            if (sizeSkipped > 0) {
                logToFile("$folderId 有 $sizeSkipped 只素材按字节数一致跳过下载（云端无 md5、本机无 ETag 缓存）",
                    "INFO", "SYNC", "SKIP")
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncFolderWithIds " + folderId + " 失败: " + e.message)
            logToFile("同步文件夹" + folderId + " 失败: " + e.message)
        }
        return tasks
    }

    /**
     * folderId → 本地目录的唯一解析入口（"A01" → <素材根>/scenea/01；"01" → <素材根>/01）。
     * 返回 null 表示这个 folderId 不可信，调用方必须跳过它而不是硬拼路径。
     * folderId 来自 MQTT 命令与云端清单，属外部输入：不校验直接 File() 拼接时，
     * "../../sdcard/x" 这类取值能把删除/写入指到应用私有目录之外（OTA 侧有 isValidFilepath，
     * 素材侧此前没有）。这里同时做字符集校验和 canonicalPath 归属校验。
     */
    private fun materialDirOf(folderId: String): File? {
        val prefixChar = if (folderId.length == 3 && folderId[0].isLetter()) folderId[0].lowercaseChar() else null
        val folderNum = if (prefixChar != null) folderId.substring(1) else folderId
        if (prefixChar != null && prefixChar != 'a' && prefixChar != 'b') return null
        if (!folderNum.matches(Regex("^[A-Za-z0-9_-]{1,10}$"))) return null
        val dir = if (prefixChar != null) {
            File(File(videoFolderPath, "scene$prefixChar"), folderNum)
        } else {
            File(videoFolderPath, folderNum)
        }
        return if (isUnderMaterialRoot(dir)) dir else null
    }

    /** filename → 目录内的素材文件；只接受纯文件名（含路径分隔、"." / ".."、越出素材根的一律 null） */
    private fun materialFileOf(dir: File, filename: String): File? {
        if (filename.isEmpty() || filename == "." || filename == "..") return null
        if (filename != File(filename).name) return null
        val file = File(dir, filename)
        return if (isUnderMaterialRoot(file)) file else null
    }

    /** canonicalPath 归属判定：软链与 ".." 都按真实路径算，无法解析时按不安全处理 */
    private fun isUnderMaterialRoot(target: File): Boolean {
        return try {
            val root = File(videoFolderPath).canonicalPath + File.separator
            target.canonicalPath.startsWith(root)
        } catch (e: Exception) {
            Log.e(TAG, "isUnderMaterialRoot 失败: " + e.message)
            false
        }
    }

    /**
     * 删除单个本地素材（由 MQTT action:'delete_material' 触发）。
     * 定位方式是 <素材根>/<folderId 解析出的目录>/<filename>，materialId 只进日志不参与查找；
     * 缓存键就是这条相对路径（见 materialCacheKey），所以删文件前必须先清缓存（见 clearMaterialCache）。
     */
    private fun deleteMaterialFile(folderId: String, filename: String, materialId: String) {
        try {
            // 支持场景前缀（"A01" → scenea/01），与服务端 delete_material 下发格式对齐；
            // folderId/filename 都是 MQTT 原文，必须过 materialDirOf/materialFileOf 两道校验
            val localFolder = materialDirOf(folderId)
            if (localFolder == null) {
                logToFile("delete_material 拒绝：folderId 非法 ($folderId) id=$materialId", "WARN", "SYNC", "SKIP")
                return
            }
            val file = materialFileOf(localFolder, filename)
            if (file == null) {
                logToFile("delete_material 拒绝：filename 非法 ($filename) id=$materialId", "WARN", "SYNC", "SKIP")
                return
            }
            if (!localFolder.exists()) {
                Log.d(TAG, "deleteMaterialFile: folder $folderId not exist")
                return
            }
            if (file.exists()) {
                // 清除 ETag/Last-Modified/md5 缓存，避免删后重加时 APK 因 304 跳过下载
                clearMaterialCache(file)
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

    /**
     * 清空指定文件夹下的所有视频文件
     * @param folderId 文件夹 ID（支持 scene-prefixed 格式）
     * 同时清除本地缓存的 ETag/Last-Modified，避免删文件后重加时 APK 因 304 跳过下载
     */
    private fun deleteFolderFiles(folderId: String) {
        try {
            // 与 syncFolderWithIds 落盘目录一致（走同一个 materialDirOf，杜绝两处口径漂移）；
            // 这是「整目录删除」路径，folderId 非法时必须拒绝而不是猜一个目录
            val localFolder = materialDirOf(folderId)
            if (localFolder == null) {
                logToFile("清空文件夹被拒绝：folderId 非法 ($folderId)", "WARN", "SYNC", "SKIP")
                return
            }
            if (!localFolder.exists()) return
            val files = localFolder.listFiles()?.filter {
                it.extension.lowercase() in VIDEO_EXTS
            } ?: emptyList()
            for (file in files) {
                Log.d(TAG, "清空文件夹" + folderId + "，删除: " + file.name)
                logToFile("清空删除: " + file.name)
                // 清除缓存的 ETag/Last-Modified/md5，避免删文件后重加时 APK 因 304 跳过下载
                clearMaterialCache(file)
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteFolderFiles " + folderId + " 失败: " + e.message)
        }
    }

    // 【A-10】 系统 UI
    // 曾有过一个 checkStoragePermission()：manifest 从未声明 WRITE/MANAGE_EXTERNAL_STORAGE，
    // 而素材/日志全写在 filesDir 内部存储（本就不需要权限），它既不申请到任何东西又会在
    // Android 11+ 拉起系统"所有文件访问"设置页 —— 已整段删除，全链路无外部存储路径依赖。
    /**
     * 隐藏系统 UI，实现全屏沉浸式播放
     * 设置 IMMERSIVE_STICKY 标志，隐藏导航栏和状态栏
     */
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

    // 【A-01】 生命周期回调 & 单播放器（onCreate 在文件靠前处）
    // player 是"单播放器"残留链路：只有未授权时的欢迎视频（showUnauthorizedAlert → playWelcomeVideo）
    // 和 config 命令的 loop 开关会碰它；窗口播放全部走 windowPlayers，两套播放器互不影响。

    /** 停掉单播放器并释放；只改运维文案，也不动窗口播放器 */
    private fun stopPlayback() {
        Log.d(TAG, "stopPlayback called")
        releasePlayer()
        setStatus("播放已停止", STATUS_ACK_MS)
    }

    /** 释放单播放器并从 playerView 上摘下来（可重复调用） */
    private fun releasePlayer() {
        Log.d(TAG, "releasePlayer called, current player: $player")
        player?.release()
        player = null
        binding.playerView.player = null
    }

    /**
     * 回前台：重新隐藏系统 UI + 恢复单播放器播放 + 续装挂起的 OTA 包。
     * pendingInstallApk 是 installApk 跳"安装未知应用"授权页前存下的：授权回来立即继续安装；
     * 没授权就置空放弃、不再挂起（遗留问题 #42：MIUI 授权页秒退时会走到这里，续装直接丢失）。
     */
    override fun onResume() {
        super.onResume()
        hideSystemUI()
        player?.play()
        // 从"安装未知应用"授权页返回：复查权限后续装挂起的 APK（见 retryPendingInstall）
        if (pendingInstallApk != null) {
            installRetryHandler.removeCallbacksAndMessages(null)
            retryPendingInstall(0)
        }
        activityForeground = true
        // 回到前台就等于"画面该回来了"：
        // 云端唤回排在身后的那次重建在这里兑现；退出态还挂着而界面自己回到了身前
        // （现场按 HOME 键或点桌面图标），也当作同一次唤回处理 —— 不然按键的人只看到一行提示，
        // 比不按键的还摸不着头脑。
        if (exited) {
            exited = false
            markExitedFile(false)
            hideBlackout()
            logToFile("退出态由回到前台结束（HOME 键或桌面图标）", "INFO", "WINDOW", "DESKTOP")
            rebuildOnResume = true
            pendingReopenReason = "回到前台"
        }
        if (rebuildOnResume) {
            rebuildOnResume = false
            rebuildAfterExit(pendingReopenReason ?: "回到前台")
            pendingReopenReason = null
        }
    }

    /**
     * 退后台只暂停单播放器：窗口播放器（windowPlayers）不暂停，
     * 所以弹出的系统页面消失后画面是连续的，但也意味着被切走时窗口仍在解码占资源。
     */
    override fun onPause() {
        super.onPause()
        activityForeground = false
        player?.pause()
    }

    /** 真销毁时才走：释放全部窗口 + 单播放器，停状态上报定时器，断开 MQTT，关闭下载线程池 */
    override fun onDestroy() {
        super.onDestroy()
        // 交还"进程内唯一实例"的资格；被新实例顶掉的那一个在这里销毁时，槽位已经归新的，别误清
        if (liveInstance === this) liveInstance = null
        // 撤销挂起的续装轮询（retryPendingInstall），Activity 没了再复查只会崩
        installRetryHandler.removeCallbacksAndMessages(null)
        // 485 墙板串口链路随 Activity 收摊（开关真值在 prefs，重启自恢复）
        // 学习窗只撤定时器、不调 stopWallPanelLearn()：进程都要没了，再补一次"已关"快照是纯噪声
        learnReportRunnable?.let { mqttHandler.removeCallbacks(it) }
        learnReportRunnable = null
        learnUntilMs = 0L
        wallPanelLink?.stop()
        wallPanelLink = null
        // DMX 副板链路同口径收摊（总闸真值在 prefs，重启自恢复）
        dmxLink?.stop()
        dmxLink = null
        dmxPrev = null
        // HDMI 采集管线随 Activity 收摊：相机会话不跨生命周期，下次重建时由窗口的 TextureView 重新挂接
        hdmiCapture?.shutdown()
        hdmiCapture = null
        // 低水位看护也停：下面 removeCallbacksAndMessages 虽会带走它，这里显式收掉可防以后改成别的 Handler
        lowWaterWatchdog?.let { mqttHandler.removeCallbacks(it) }
        lowWaterWatchdog = null
        // mqttHandler 上挂着窗口启播、rev 记账、重连倒计时、进度条刷新，全部按"界面还在"写的，
        // 必须在 releaseAllWindows 之前清空，否则播放器 release 后仍有 post 排队进来
        mqttHandler.removeCallbacksAndMessages(null)
        releaseAllWindows()
        releasePlayer()
        // LOGO 解码器必须在画布 shutdown 之前放：画布收摊要先把解码器的输出端一起收掉
        releaseLogoPlayer()
        logoCanvas?.shutdown()
        logoCanvas = null
        logoContainer = null
        // 停止定时器并发送离线状态
        stopStatusTimer()
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) {}
        downloadExecutor.shutdown()
        shotExecutor.shutdownNow()
        // 排程线程不归主线程管，不收就会在 Activity 退场后继续 connect 一次
        reconnectScheduler.shutdownNow()
    }

    /** 终端模式：吃掉返回键，不允许退出 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 禁用返回键 - 终端模式不允许退出
    }

    /** 用户按 Home / 手势离开时，用启动 Intent 把自己重新拉回前台（kiosk 防退出） */
    override fun onUserLeaveHint() {
        // 退出到桌面那一步本身就是"主动离开前台"（见【A-15】）：exited 在拉桌面之前就已置真，
        // 这里若照常自卫，桌面刚出来就被我们自己顶回去，屏上只剩一行提示。
        if (exited) return
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * 预建素材目录：根下 01..30，外加 scenea/01..30 与 sceneb/01..30（三套各 30 个，
     *   不是旧注释写的"20 个 (01-20)"；scene 目录名统一小写）
     * 三种形态各自对应一条解析路径：无前缀 "01" → 根目录、"A01" → scenea/01、"B01" → sceneb/01
     * 只是 mkdirs 占位，好让播放器直接按 A01→scenea/01 找目录时不撞空路径；
     * 素材真正的增删由 syncFolderWithIds 负责，两者对目录的处理是幂等的。
     */
    private fun createMaterialFolders() {
        try {
            val baseDir = File(videoFolderPath)
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            for (i in 1..30) {
                val folderName = String.format("%02d", i)
                val folder = File(baseDir, folderName)
                if (!folder.exists()) {
                    folder.mkdirs()
                    Log.d(TAG, "Created folder: ${folder.absolutePath}")
                }
            }
            // 创建 sceneA/ 和 sceneB/ 子目录树（01-30）
            for (scene in listOf("scenea", "sceneb")) {
                val sceneDir = File(baseDir, scene)
                if (!sceneDir.exists()) sceneDir.mkdirs()
                for (i in 1..30) {
                    val folderName = String.format("%02d", i)
                    val folder = File(sceneDir, folderName)
                    if (!folder.exists()) {
                        folder.mkdirs()
                        Log.d(TAG, "Created scene folder: ${folder.absolutePath}")
                    }
                }
            }
            Log.d(TAG, "Material folders initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create material folders: ${e.message}")
        }
    }

    /**
     * 带 ETag/Last-Modified 条件请求的文件下载
     * @param destFile     落盘目标；日志用它的文件名，缓存键用它算相对路径（见 materialCacheKey）——
     *                     以前缓存键是调用方传进来的裸文件名，跨目录同名会互刷缓存，故不再收这个参数
     * @param expectedSize 云端下发的完整字节数，用来判定这次到底算不算落地；0 = 未知（老服务端/取不到）
     * @param onProgress   本次写入的字节数回调，每 ≥256KB 一次；null = 不回报
     * @return DownloadResult：WRITTEN 真写完 / UNCHANGED 304 原样保留 / FAILED 没拿到（见枚举注释）
     *
     * 一次调用只发 1 个条件 GET：304 就原样保留本地文件返回 UNCHANGED，2xx 才落盘。历史版本在这里叠了
     * 最多 3 个 HEAD + 1 个 GET，而每个 HEAD 的判定（带 If-None-Match，304 则跳过）都与最后那个条件
     * GET 完全重复，已合并。
     *
     * 两条和进度条直接相关的规矩：
     *   - ETag/Last-Modified 在**流写完之后**才写回 prefs，且实到字节与云端 size 不符时不写。
     *     早先版本收到响应头就写，于是"下到一半断线"会留下【半截文件 + 已生效的 ETag】，
     *     下一轮条件 GET 拿回 304，那个坏文件永远修不好（进度条也会每次都一秒跳完、素材却播不出来）。
     *   - 不做 Range 断点续传：同名素材在云端是"就地替换"的（faststart 重封装就是同名改字节），
     *     续传会把新字节拼在旧前缀后面变成坏文件。宁可下一轮从 0 重下。
     *
     * 注意：md5 不一致但 ETag 没变时，这里会返回 UNCHANGED 而不下载 —— 语义见 syncFolderWithIds 的判定顺序。
     */
    private fun downloadWithETag(
        urlStr: String,
        destFile: File,
        expectedSize: Long = 0L,
        onProgress: ((Long) -> Unit)? = null
    ): DownloadResult {
        val filename = destFile.name
        val cachedEtag = readHttpCache(PREF_ETAG_PREFIX, destFile)
        val cachedLm = readHttpCache(PREF_LM_PREFIX, destFile)
        // 本地文件不在/为空 ⇒ 缓存的 ETag 一律不作数（带了会被 nginx 判成 304，旧文件永远下不回来）
        val force = !destFile.exists() || destFile.length() == 0L
        if (force) Log.d(TAG, "本地文件不存在或为空，强制下载: ${destFile.name}")

        return try {
            val url = java.net.URL(urlStr)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            if (!force) {
                if (cachedEtag != null) conn.setRequestProperty("If-None-Match", cachedEtag)
                if (cachedLm != null) conn.setRequestProperty("If-Modified-Since", cachedLm)
            }

            val realCode = conn.responseCode
            if (realCode == 304) {
                Log.d(TAG, "文件未变化跳过: $filename")
                // 上报一条：否则现场只看得到"准备下载"却没有"下载完成"，无法区分是被跳过还是下坏了
                // 这里报相对路径而不是裸文件名：同名素材在不同目录时，只看文件名分不出是哪一目录被跳过
                logToFile("跳过下载(ETag未变): ${materialCacheKey(destFile)}", "INFO", "SYNC", "SKIP")
                conn.disconnect()
                return DownloadResult.UNCHANGED
            }
            // 非 2xx（404 素材被删、502 nginx 后端抖动…）明确判失败：以前靠 inputStream 抛异常兜住，
            // 日志里只留一句 "HTTP 404 reading..." 之类的 JDK 文案，现场看不出是云端没这个文件
            if (realCode !in 200..299) {
                logToFile("下载失败: $filename HTTP $realCode $urlStr", "ERROR", "SYNC", "DOWNLOAD")
                conn.disconnect()
                return DownloadResult.FAILED
            }

            val newEtag = conn.getHeaderField("ETag")
            val newLm = conn.getHeaderField("Last-Modified")

            var written = 0L
            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    var lastReport = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        val cb = onProgress
                        if (cb != null) {
                            written += bytesRead
                            // 256KB 一节流：5MB 级素材有足够刷新点，又不会把主线程刷爆
                            if (written - lastReport >= 262144L) {
                                lastReport = written
                                cb(written)
                            }
                        }
                    }
                }
            }
            conn.disconnect()

            if (expectedSize > 0L && destFile.length() != expectedSize) {
                // 不存 ETag，并且按失败上报：一只半截/尺寸对不上的文件不能算"配置已落地"。
                // 只告警不判失败的时代，它会连着 rev 一起被记成成功——服务端据此认为设备已追平，
                // 对账不再补发，现场就是一块永远修不好的坏素材。
                logToFile("下载字节数与云端 size 不符: $filename 实到${destFile.length()} 期望$expectedSize",
                    "WARN", "SYNC", "DOWNLOAD")
                return DownloadResult.FAILED
            }
            // 写完了、字节数也对得上，才认这个 ETag/Last-Modified（见上面"两条规矩"）
            if (newEtag != null || newLm != null) {
                val key = materialCacheKey(destFile)
                val ed = prefs.edit()
                if (newEtag != null) ed.putString(PREF_ETAG_PREFIX + key, newEtag)
                if (newLm != null) ed.putString(PREF_LM_PREFIX + key, newLm)
                ed.apply()
            }
            Log.d(TAG, "下载完成: $filename")
            logToFile("下载完成: $filename (${destFile.length() / 1024}KB)", "INFO", "SYNC", "DOWNLOAD")
            DownloadResult.WRITTEN
        } catch (e: Exception) {
            Log.e(TAG, "下载失败 [${filename}]: ${e.message}")
            logToFile("下载失败 [$filename]: ${e.message}", "ERROR", "SYNC", "DOWNLOAD")
            DownloadResult.FAILED
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
     * 素材缓存键 = 相对素材根的路径（"scenea/01/x.mp4"、"01/x.mp4"），不再是裸文件名。
     * 设备端认文件认的就是这个路径，而 ETag/Last-Modified/md5 三个键以前只有文件名：
     * 同一素材 cp2p 到 01 和 02 是常规操作，两边于是互刷同一份缓存 —— 表现是每轮都重下、
     * 进度条永远跑不完（现场最难查的那种"同步不完"）。键跟着路径走，各记各的。
     * 万一不在素材根下（materialFileOf 已挡，理论不会发生）就退回文件名，至少不比以前差。
     */
    private fun materialCacheKey(file: File): String {
        val root = File(videoFolderPath).absolutePath + File.separator
        val abs = file.absolutePath
        return if (abs.startsWith(root)) abs.substring(root.length) else file.name
    }

    /**
     * 读 ETag / Last-Modified：先按新键（带目录）读，读不到再回落到升级前的旧键（只有文件名），
     * 命中就把值搬到新键上 —— 装包升级时现场素材不必整个目录重下一遍。
     * 旧键分不清自己属于哪个目录，跨目录同名的素材可能借到别人的 ETag；借错只多下一遍
     * （nginx 按【本条 URL 指向的那个文件】比 ETag，对不上就 200 全量），不会下错内容。
     * 旧键不主动删：删了另一个目录也白下一遍，留着让新键逐个把它取代掉。
     */
    private fun readHttpCache(prefix: String, file: File): String? {
        val key = prefix + materialCacheKey(file)
        prefs.getString(key, null)?.let { return it }
        val legacy = prefs.getString(prefix + file.name, null) ?: return null
        prefs.edit().putString(key, legacy).apply()
        return legacy
    }

    /**
     * 带缓存的素材 md5：一轮对账要把本地每个文件整只读一遍算摘要，几十 GB 的素材目录就是几十 GB 磁盘读，
     * 而服务端每 30s/每次改动都可能推一轮同步 —— 绝大多数轮次里文件根本没动过。
     * 缓存键值本身带 "<字节数>:<mtime>:" 前缀，文件被替换（下载完成、重新落盘）后前缀对不上自动失效，
     * 所以只需要在**删除**文件时跟着 remove（与 ETag/LM 同一套约定，避免同名文件复活后串味）。
     * 只在下载线程调用，无并发；prefs 自身线程安全。
     */
    private fun cachedMd5(file: File): String {
        val stamp = file.length().toString() + ":" + file.lastModified().toString()
        // md5 不做旧键回落（与 ETag/LM 有意不同）：ETag 由服务端按 URL 复核，借错顶多多下一遍；
        // md5 是纯本地判定，借错会让人"以为本地已是最新"而跳过下载。
        // 代价只是升级后第一轮对每只文件多读一遍算摘要，之后照旧命中缓存。
        val key = PREF_MD5_PREFIX + materialCacheKey(file)
        prefs.getString(key, null)?.let { saved ->
            if (saved.startsWith("$stamp:")) return saved.substring(stamp.length + 1)
        }
        val md5 = calculateMd5(file)
        if (md5.isNotEmpty()) prefs.edit().putString(key, "$stamp:$md5").apply()
        return md5
    }

    /**
     * 素材文件从本机消失时清掉它的三项缓存（ETag / Last-Modified / md5），三处删除点共用。
     * 顺带清掉升级前的那把裸文件名旧键：删文件不清的话，日后同名素材重新落地会被旧 ETag 认成
     * "没变过"（虽然 destFile 不在时 force 已经兜住，但这正是以前"删了又加回来却永远不重下"的现场成因）。
     * 旧键本就分不清属于哪个目录，这里删掉它可能让另一个同名目录白下一遍 —— 一次多余下载，
     * 换一把歧义缓存的清除，比留着划算。新键（带目录）才是长期有效的那一份。
     */
    private fun clearMaterialCache(file: File) {
        val rel = materialCacheKey(file)
        prefs.edit()
            .remove(PREF_ETAG_PREFIX + rel).remove(PREF_LM_PREFIX + rel).remove(PREF_MD5_PREFIX + rel)
            .remove(PREF_ETAG_PREFIX + file.name).remove(PREF_LM_PREFIX + file.name)
            .remove(PREF_MD5_PREFIX + file.name)
            .apply()
    }
    

    // 【A-09】 OTA 自更新
    // ==================== OTA 自更新 ====================
    // 两条入口，最后都汇到 downloadAndInstall()：
    //   1) 设备自查：checkForUpdate()，启动/每次连上 MQTT/重连时各调度一次，拉 /api/version/latest
    //   2) 云端推送：handleCommand 的 action:'update'，命令体自带完整 url + version + version_code + md5
    // downloadAndInstall 的门禁顺序：
    //   isTrustedApkUrl（IP 精确匹配）→ 下载 → 体积 >1MB → md5（云端给了才校）→ 签名证书指纹 → 安装
    // 安装分两条：探到 root（su 能变 uid 0）就 silentInstall 静默装完自启，无人值守；
    // 没有 root 才弹不可取消的确认框 → installApk → FileProvider 系统安装器，
    // 缺"安装未知应用"权限时先跳授权页，回来由 onResume 续装。
    // 2026-11-16 CX3588 台架（userdebug + SELinux Permissive + 镜像带 su）实测：这条静默链原样跑通——
    // root 把包从应用私有 cacheDir 抄到 /data/local/tmp → pm install -r → 同一条 shell 里 am start，
    // 全程不出安装器界面，且 -r 保住 prefs 与素材缓存（重启后仍绑原房、不触发全量重下）。
    // pm install -r 会拒装低 versionCode（INSTALL_FAILED_VERSION_DOWNGRADE），所以这条路只认向上的版本，
    // 与 checkForUpdate 的"远端 > 本机"判定天然一致；链用 && 串，装不上时不会误自启。
    // 另有一条推不掉的次序：**执行探测的是"当前已装的那一版"**，所以改探测口径的包自己证明不了自己，
    // 要等下一版才见效（318/319 两次都因此仍弹了确认框，且 update 分支硬闸 serverCode > 本机，同版本重推也会被拒）。
    // #320 由已装好的 #319 装上，应用自身的静默分支首次全程无人工：日志依次是
    // 「OTA: 检测到 root，静默安装可用」→「走静默安装（版本 320）」→「静默安装 xvj-update-320.apk」，
    // 约 5s 后自启回 MainActivity（topResumedActivity 仍是本应用，没有安装器界面），/data/local/tmp 由链尾清空、素材零重下。

    /**
     * 校验 APK 下载地址是否来自可信服务器。
     * 只认硬编码公网 IP，主机名全等才算可信（只看 URI.host，路径/端口不参与判定）；
     * 换成域名或内网部署时，这里要和服务端 APK_DOWNLOAD_BASE 一起改。
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
     * 回传类地址的可信判定：主机沿用 isTrustedApkUrl 的 IP 精确匹配，再钉住"就是截图回传口"这条路径。
     * 为什么要多钉这一层：upload_url 是 MQTT 载荷里的字符串，拿到就直接往它 POST 二进制加令牌；
     * 只判主机的话，同一台主机上的任意路径（带 query 的、会跳走的）也照收不误。
     */
    private fun isTrustedServerUrl(url: String): Boolean {
        if (!isTrustedApkUrl(url)) return false
        return try {
            val path = java.net.URI(url).path ?: return false
            path.startsWith("/api/device/") && path.endsWith("/screenshot") && !path.contains("..")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * filepath 安全性校验（防路径穿越）：只允许 .apk 落地路径 + 字母数字 _ . / -，禁 ".." 和 http(s) 前缀。
     *
     * 服务端 /api/version/latest 返回的 filepath 就是 "/apk/xxx.apk" 这种根路径形式，
     * 所以单个前导 "/" 必须放行（曾经的旧规则一律拒绝，导致 checkForUpdate 自查路径恒不可达、
     * 线上 OTA 只剩云端推送 action:'update' 一条腿）。
     * 仍然拒绝 "//host/path"：那是协议相对 URL，拼进 "$APK_URL$filepath" 后 host 会被换掉，
     * 绕过 isTrustedApkUrl 的精确 IP 判定。
     */
    private fun isValidFilepath(filepath: String): Boolean {
        // 拒绝协议相对路径（可把请求指向任意主机）
        if (filepath.startsWith("//")) return false
        // 不允许http/https协议
        if (filepath.startsWith("http://") || filepath.startsWith("https://")) return false
        // 不允许路径穿越
        if (filepath.contains("..")) return false
        // 只允许安全字符（前导 "/" 已在字符集里）
        return filepath.matches(Regex("^[a-zA-Z0-9_./-]+$"))
    }


    /**
     * 校验下载到的 APK 是否由预期证书签名：用 getPackageArchiveInfo(GET_SIGNATURES) 取第一张签名证书 DER，算 SHA-256 指纹，
     * 与文件顶部的 EXPECTED_CERT_FINGERPRINT 比对（那是 CI 固定 keystore 的证书指纹，见 @tag: sha256）。
     * 注意这是"签名证书指纹"，不是素材文件的 md5，两者别混。
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
            
            // 测试后门：指纹仍是占位串时直接放行，方便本地未签名/换签名调试。
            // 顶部常量已是真实指纹，该条件现在恒不成立（要临时跳过校验就把常量改回占位串）。
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

    /**
     * 设备侧自查更新：延时 5s 后 GET /api/version/latest，服务端 version_code 比本机大才继续。
     * 调用点有三处（启动、MQTT 连接成功、重连），彼此不去重，所以同版本由 downloadAndInstall
     * 里的 otaBusyVersion 挡住重复下载/重复弹窗。
     * 注意本地判定用 VERSION_CODE（onCreate 从 packageInfo 读），不要写死。
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
                                // 验证filepath安全性（防止路径穿越和协议相对URL绕过）
                                if (!isValidFilepath(filepath)) {
                                    Log.w(TAG, "OTA: 拒绝不安全的filepath: $filepath")
                                    return@Thread
                                }
                                // 服务端给的是 "/apk/x.apk"，这里归一保证恰好一个斜杠
                                val apkUrl = APK_URL.trimEnd('/') + "/" + filepath.trimStart('/')
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

    /**
     * 下载并安装 APK（有 root 就静默装，没有才经用户确认）。apkUrl 必须是完整地址，不是相对路径。
     * 顺序：isTrustedApkUrl → 下到 cacheDir/xvj-update-<version>.apk（已下好则直接复用不重下）→
     *       体积 <1MB 视为下载失败 → 有 expectedMd5 才比对（不等则删包退出）→
     *       verifyApkSignature 比对证书指纹 → silentInstall，装不上才弹不可取消的确认框 → installApk。
     * md5 为空时（老云端命令没带）就少一道校验，只剩签名指纹兜底。
     * Toast/对话框都 post 到 mqttHandler（主线程），下载本身在新起的 Thread 里。
     * otaBusyVersion：同一条流程只允许一个（启动/连接/重连/推送可能并发触发同一版本）。
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

        val busy = otaBusyVersion
        if (busy != null) {
            logToFile("OTA: 已有更新流程在处理(版本 $busy)，忽略本次对 $version 的触发")
            return
        }
        otaBusyVersion = version

        // 异步下载APK
        Thread {
            try {
                val apkFile = File(cacheDir, "xvj-update-$version.apk")
                // 复用上次已下好的包：MIUI 授权页秒退会让整次 14MB 下载白跑（见 retryPendingInstall），
                // 二次触发时不必重下。破损/伪造的包由下游体积+md5+签名三道闸拦下并删包，下次自然重下。
                if (apkFile.exists() && apkFile.length() > 1_000_000) {
                    logToFile("OTA: 复用已下载的 ${apkFile.name} (${apkFile.length()} bytes)，跳过下载")
                } else {
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

                    // 如果已存在（哪怕是半截文件），先删除避免残留
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
                }

                logToFile("APK就绪: ${apkFile.absolutePath}, 大小: ${apkFile.length()} bytes")

                // 验证APK文件有效性（真实APK通常>1MB）
                if (apkFile.length() < 1_000_000) {
                    logToFile("APK文件过小，可能下载失败")
                    otaBusyVersion = null
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
                        otaBusyVersion = null
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
                    otaBusyVersion = null
                    mqttHandler.post {
                        android.widget.Toast.makeText(this, "更新验证失败：APK签名无效", android.widget.Toast.LENGTH_LONG).show()
                    }
                    apkFile.delete()
                    return@Thread
                }
                logToFile("APK签名验证通过")

                // 有 root 的机器（台架/长期测试盒子）直接进静默安装，不占用大屏等人点"确定"；
                // 探不到 root 才落到下面这条人工路径，行为与改动前一致。
                // otaBusyVersion 在这里**不解锁**：静默那条跑起来后本进程多半就被系统回收了，
                // 留着锁可以让安装窗口期内的重连/再推送不再起第二条并发下载。
                if (silentInstallReady()) {
                    logToFile("OTA: 走静默安装（版本 $version）")
                    installApk(finalApkFile)
                    return@Thread
                }

                // 使用对话框让用户确认安装
                val installFile = finalApkFile  // 传递文件引用到Dialog
                mqttHandler.post {
                    try {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("更新已下载")
                            .setMessage("版本: $version\n点击确定开始安装")
                            .setPositiveButton("确定") { _, _ ->
                                installApk(installFile)
                            }
                            .setNegativeButton("取消", null)
                            .setCancelable(false)
                            // 确定/取消都会走到这里：解锁后，下一次触发（重连或云端再推）才能重试
                            .setOnDismissListener { otaBusyVersion = null }
                            .show()
                    } catch(e: Exception) {
                        Log.e(TAG, "Show install dialog error: ${e.message}")
                        otaBusyVersion = null
                        // 回退到Toast
                        android.widget.Toast.makeText(this, "更新已下载，请手动安装", android.widget.Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: Exception) {
                logToFile("下载APK失败: ${e.message}")
                otaBusyVersion = null
                mqttHandler.post {
                    try {
                        android.widget.Toast.makeText(this, "更新下载失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    } catch(e: Exception) {}
                }
            }
        }.start()
    }

    /**
     * 安装已下载的 APK 的唯一入口，内部按环境分两条：
     * 探到 root（长期测试机/盒子）→ 后台线程跑 silentInstall，装完自启，全程无人值守；
     * 没有 root → installViaSystemInstaller（权限闸门 + 系统安装器，需要现场点一下）。
     * pm install 会一直阻塞到系统装完，而本函数的调用点常在主线程（对话框按钮、onResume 续装），
     * 所以静默那条必须自己起线程；失败再 post 回主线程走人工那条。
     */
    private fun installApk(file: File) {
        if (!silentInstallReady()) {
            installViaSystemInstaller(file)
            return
        }
        Thread {
            if (!silentInstall(file)) mqttHandler.post { installViaSystemInstaller(file) }
        }.start()
    }

    /**
     * 人工安装：先过"安装未知应用"权限闸门，无权限时跳系统授权页，授权回来自动续装（onResume）。
     */
    private fun installViaSystemInstaller(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()) {
            logToFile("缺少安装未知应用权限，跳转授权页")
            pendingInstallApk = file
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                mqttHandler.post {
                    try {
                        android.widget.Toast.makeText(this, "请允许本应用安装未知应用，授权后自动继续安装", android.widget.Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                logToFile("跳转安装授权页失败: ${e.message}")
                pendingInstallApk = null
            }
            return
        }
        performInstall(file)
    }

    /**
     * 从"安装未知应用"授权页回来后复查权限，允许就立即续装。
     * MIUI/HyperOS 的授权页会在权限真正生效前就回调 onResume（实测 47ms 即返回），
     * 一次判负就清空 pendingInstallApk 等于白丢已下好的包，所以每 1.5s 复查一次、最多约 30s。
     * 调用方（onResume）先 removeCallbacksAndMessages 再起链，保证同时只有一条轮询。
     */
    private fun retryPendingInstall(attempt: Int) {
        val pending = pendingInstallApk ?: return
        if (isFinishing || isDestroyed) {
            pendingInstallApk = null
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = null
            installApk(pending)
            return
        }
        if (attempt >= 20) {
            pendingInstallApk = null
            logToFile("等待安装权限超时(约30s)，放弃续装；已下载的包保留在缓存目录，下次触发会复用")
            return
        }
        installRetryHandler.postDelayed({ retryPendingInstall(attempt + 1) }, 1500)
    }

    /** 发起系统安装器（FileProvider URI） */
    private fun performInstall(file: File) {
        try {
            logToFile("开始安装APK: ${file.absolutePath}, exists=${file.exists()}, size=${file.length()}")
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
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

    /**
     * 这台机器能不能静默安装。探一次就缓存，探不到一律判负（回退人工安装器），不抛错。
     * 判据是"应用 uid 喊 su 真能变成 uid 0"，不是"su 文件存在"：
     * CX3588 台架实测（userdebug + SELinux Permissive + sudaemon 型 su）对任意 uid 放行；
     * 量产 user 版盒子多半没有 su，或要弹框等人授权。
     */
    private fun silentInstallReady(): Boolean {
        rootSuUsable?.let { return it }
        var why = "/system/bin/su 不可执行"
        val ok = try {
            if (!File("/system/bin/su").canExecute()) {
                false
            } else {
                val probe = execAsRoot("id -u", 3000)
                why = "code=${probe.first} out=\"${probe.second}\""
                // execAsRoot 按行以 "; " 拼接，stdout 的 "0" 到这里是 "0; "，整串比较永远不成立
                probe.first == 0 && probe.second.substringBefore(";").trim() == "0"
            }
        } catch (e: Exception) {
            why = "异常 ${e.message}"
            false
        }
        rootSuUsable = ok
        logToFile(if (ok) "OTA: 检测到 root，静默安装可用" else "OTA: 无 root（$why），安装走系统安装器",
            if (ok) "INFO" else "WARN")
        return ok
    }

    /**
     * root 静默安装：su 把包搬到 /data/local/tmp（应用私有目录 installer 读不到）→ pm install -r → 拉回自己。
     * **自启必须写在同一条 shell 里**：换包时系统会杀掉本应用的全部进程，调用方拿不到返回值；
     * 而这条 su 子进程已经换成 uid 0、不在被杀的进程组里，"装完再启动"这半句只有它替得我们说。
     * 因此成功路径上本函数通常不会返回（进程已经没了），下一条日志靠不到 —— 版本是否真装上，
     * 以重启后心跳回报的 version_code 为准。失败（返回 false）才回退到人工安装器。
     * 只在 silentInstallReady() 判真之后调用（见 installApk），这里不再重复探测。
     */
    private fun silentInstall(file: File): Boolean {
        val tmp = "/data/local/tmp/xvj-update.apk"
        // 开头先 rm：上一次失败留下的包不能被这次的 cp 追加/复用；rm -f 对不存在的文件也返回 0
        // 自启那句带 MAIN+HOME 类目：不带类目会落进 standard 栈，之后按 HOME 键系统要在 home 栈
        // 再起一个实例，两个实例共用同一个 MQTT clientId 互踢（理由见 bringSelfByRoot）
        val sh = "rm -f $tmp && cp ${file.absolutePath} $tmp && chmod 644 $tmp && " +
            "pm install -r $tmp && rm -f $tmp && sleep 3 && " +
            // 覆盖安装后顺手把默认桌面钉回自己：量产的 root 盒子换包后若丢了 HOME 记录，
            // 下次重启会停在系统"选择主屏幕"上（2026-11-17 台架实况），这一句就是开机落屏的保证
            "cmd package set-home-activity $packageName/.MainActivity ; " +
            "am start -a android.intent.action.MAIN -c android.intent.category.HOME" +
            " -n $packageName/.MainActivity"
        logToFile("OTA: 静默安装 ${file.name}（${file.length()} bytes），本机进程随后会被回收并自启")
        val done = execAsRoot(sh, 180000)
        val ok = done.first == 0
        logToFile("OTA: 静默安装${if (ok) "完成" else "失败(code=${done.first})"}: ${done.second}",
            if (ok) "INFO" else "WARN")
        return ok
    }

    /**
     * 以 root 跑一条 shell，超时即杀进程（遇到要弹框授权的那种 su，不能让它把 OTA 线程吊死）。
     * 先起守护线程把 stdout 读空再等退出：子进程输出塞满管道缓冲区会卡在 write，waitFor 就永远不返回。
     * @return (退出码, 用 "; " 拼起的输出)；超时或起不来时退出码为 -1。
     */
    private fun execAsRoot(sh: String, timeoutMs: Long): Pair<Int, String> {
        val proc = try {
            Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c", sh))
        } catch (e: Exception) {
            return Pair(-1, "su 启动失败: ${e.message}")
        }
        val out = StringBuilder()
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line -> out.append(line).append("; ") }
            } catch (e: Exception) {}
        }
        reader.isDaemon = true
        reader.start()
        var code = -1
        val waiter = Thread { code = try { proc.waitFor() } catch (e: Exception) { -1 } }
        waiter.start()
        waiter.join(timeoutMs)
        if (waiter.isAlive) {
            try { proc.destroy() } catch (e: Exception) {}
            return Pair(-1, "超时 ${timeoutMs}ms: $out")
        }
        return Pair(code, out.toString().trim())
    }

    /**
     * 杀掉 c2 解码器 HAL（init 会立刻拉回来）。台架实测：久跑后 4K 解码帧的内核 dma-buf
     * 都挂在 rkvdec/codec2 名下，进程死了内核引用才真还回去，可用内存一次回涨几百 MB。
     * 用 grep 一趟扫 /proc 下各进程的 cmdline：实测 43–57ms；per-pid fork tr 要 1.8s（#354 就是慢在扫描上，
     * kill 还没落地，lmkd 先把应用杀了）。别用 pidof —— comm 只取前 15 字节，会连带误杀别的
     * android.hardware.* 服务。
     * 匹配串写成 [m]edia：su、sh、grep 自己的命令行都原样带着这串模式文本，不加这层会被自己的
     * 正则命中、连脚本一起 kill（#355 实测退出码 137：HAL 死了，日志却报失败）。
     * @return 杀掉的解码器进程数；脚本超时或 su 没起来时 -1
     */
    private fun killDecodeHal(): Int {
        val (_, out) = execAsRoot(
            "k=0; for f in \$(grep -la '[m]edia.c2@1.*-service' /proc/[0-9]*/cmdline 2>/dev/null); do " +
                "p=\${f#/proc/}; p=\${p%/cmdline}; kill -9 \"\$p\" 2>/dev/null && k=\$((k+1)); done; " +
                "echo xvj-killed=\$k",
            8_000)
        return Regex("xvj-killed=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    /**
     * 低水位看护（#357 台架复盘后的补口）：重建自愈只在「拆窗后、建窗前」那一下执行。
     * 编辑风暴一停，若可用内存仍趴在 300–500MB（#357 实测 347–373MB），后面没有重建就再没机会回收，
     * 整夜贴着 lmkd 的 LOW_MEMORY 线（#354 就死在 362MB）。
     * 主线程每 LOW_WATER_TICK_MS 量一次，连续 LOW_WATER_TICKS 次低于 REBUILD_HEAL_URGENT_MB 才动手，
     * 与重建自愈共用同一把冷却锁与同一份回收手法；回收跑后台线程，不挡主线程。
     * 代价与重建自愈相同：画面会跳下一只（#357 实测 123–206ms），采集会话不重启。
     */
    private fun startLowWaterWatchdog() {
        lowWaterWatchdog?.let { mqttHandler.removeCallbacks(it) }
        lowWaterLowTicks = 0
        lowWaterWatchdog = object : Runnable {
            override fun run() {
                checkLowWater()
                mqttHandler.postDelayed(this, LOW_WATER_TICK_MS)
            }
        }
        mqttHandler.postDelayed(lowWaterWatchdog!!, LOW_WATER_TICK_MS)
    }

    /** 主线程：一次低水位检查（回收进行中、重建自愈挂起时都不动手，避免并发杀两遍） */
    private fun checkLowWater() {
        if (lowWaterHealRunning || postTeardownHealPending) return
        val avail = availableMemMb()
        if (avail <= 0 || avail >= REBUILD_HEAL_URGENT_MB) {
            lowWaterLowTicks = 0
            return
        }
        lowWaterLowTicks++
        if (lowWaterLowTicks < LOW_WATER_TICKS) return
        val now = SystemClock.elapsedRealtime()
        // 冷却期只保留计数、不杀；下个周期冷却过了还在低水位就动手
        if (now - lastDecodeHealAtMs < REBUILD_HEAL_COOLDOWN_MS) return
        lastDecodeHealAtMs = now
        lowWaterHealRunning = true
        lowWaterLowTicks = 0
        logToFile("低水位看护：可用 ${avail}MB 已连续 ${LOW_WATER_TICKS * (LOW_WATER_TICK_MS / 1000)}s " +
            "低于 ${REBUILD_HEAL_URGENT_MB}MB，回收解码器缓冲", "WARN", "WINDOW", "REBUILD")
        Thread {
            val killed = killDecodeHal()
            // 与重建自愈同口径：不论杀没杀成，都留沉降时间再量，留一份可对账的回收账
            try { Thread.sleep(REBUILD_HEAL_SETTLE_MS) } catch (e: InterruptedException) {}
            val after = availableMemMb()
            val what = when {
                killed > 0 -> "完成（杀掉 $killed 个解码器进程）"
                killed == 0 -> "未命中解码器进程"
                else -> "脚本失败"
            }
            logToFile("低水位看护$what，可用 ${avail}MB→${after}MB", "WARN", "WINDOW", "REBUILD")
            mqttHandler.post { lowWaterHealRunning = false }
        }.apply { isDaemon = true }.start()
    }

    // ========== 【A-18】副屏（1080/eDP）运行时开关 ==========
    /**
     * 副屏（1080/eDP）此刻是否通着。读 DRM 连接器的 status 文件，不靠内存记账 ——
     * 写 off 之后内核会立刻改成 disconnected，以文件为准才反映真实链路。
     * @return true/false；本机没有这条显示链（文件不存在）或读失败时 null（心跳就不带这个字段）
     */
    private fun readSecondaryDisplayOn(): Boolean? {
        return try {
            val f = File("/sys/class/drm/card0-eDP-1/status")
            if (f.exists()) f.readText().trim() == "connected" else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 写 DRM 连接器状态来开关副屏。写 status 只改内核侧连接状态，
     * 再写 uevent change 才会让 HWC/DisplayManager 重新探一次；台架实测 on 约 4 秒多出一块
     * 1920×1080 扩展屏、off 约 10 秒收回（sysfs 读数立即翻转，显示解绑是异步的）。
     * 与 reboot 共用 su 通道：量产 user 版没有 su 时只记一行拒绝，不弹授权框吊住线程。
     * @return 命令是否执行成功（不代表显示已完全切换，实际状态以心跳读数与 dumpsys 为准）
     */
    private fun applySecondaryDisplay(on: Boolean): Boolean {
        if (!File("/sys/class/drm/card0-eDP-1/status").exists()) {
            logToFile("副屏开关放弃：本机没有 /sys/class/drm/card0-eDP-1（没有 1080/eDP 显示链）",
                "WARN", "MQTT", "DISPLAY")
            return false
        }
        if (!silentInstallReady()) {
            logToFile("副屏开关放弃：本机没有 root，写不了 DRM 状态（量产机需系统签名或厂商特权接口）",
                "WARN", "MQTT", "DISPLAY")
            return false
        }
        val state = if (on) "on" else "off"
        val r = execAsRoot(
            "echo $state > /sys/class/drm/card0-eDP-1/status && echo change > /sys/class/drm/card0-eDP-1/uevent",
            8000)
        val ok = r.first == 0
        logToFile("副屏开关：写入 $state ${if (ok) "成功" else "失败(code=${r.first})"}: ${r.second}",
            if (ok) "INFO" else "ERROR", "MQTT", "DISPLAY")
        return ok
    }

    // ========== 【A-16】实时截图（云端【S-15】的设备侧） ==========
    // 一帧当前落屏画面 → JPEG → POST 回服务端。为什么走 root screencap 而不是 MediaProjection：
    // 后者要点授权框，无人值守的包厢机器弹不出来也没人点；screencap 和静默安装共用同一个 root 前提
    // （量产镜像由厂家带 root 刷全量镜像，2026-11-16 老板定调：关键节点加密码确认即可）。
    // 全程只读不写：不碰播放器、不改状态，失败只留一行日志 —— 管理台等不到图时，那行日志是唯一线索。
    // 不看前台：命令从 MQTT 线程进来，抓屏由 su 起的独立进程做，抓的是整块屏的合成结果。
    // 所以应用被 HOME 键最小化、或已退出到桌面时照样截得到，而且截到的正是当时屏上的原厂桌面 ——
    // 现场要看的"那间房现在屏上是什么"包含这种情况。唯一前提是进程还活着（没被杀），否则命令收不到。

    /**
     * root screencap 出一张全屏 PNG，从 stdout 直接收（不落设备磁盘，省掉临时文件的归属与清理）。
     * 判据用 PNG 魔数：su 或 screencap 不存在时会把错误文本吐到 stdout，那不是图。
     * @return PNG 字节；拿不到就 null
     */
    private fun captureScreenPng(): ByteArray? {
        val proc = try {
            Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c", "screencap -p"))
        } catch (e: Exception) {
            logToFile("实时截图：su 起不来（${e.message}），这台机器大概没 root", "WARN", "SYNC", "SCREENSHOT")
            return null
        }
        var png: ByteArray? = null
        var overSize = false
        val reader = Thread {
            val buf = java.io.ByteArrayOutputStream()
            try {
                val inp = proc.inputStream
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > SHOT_MAX_PNG_BYTES) {
                        overSize = true
                        try { proc.destroy() } catch (e: Exception) {}
                        break
                    }
                }
                png = buf.toByteArray()
            } catch (e: Exception) {
                // 超时或超长被 destroy 时，读线程必然抛在这里；真正的判废在下面的 overSize/退出码
            }
        }
        reader.isDaemon = true
        reader.start()
        var code = -1
        val waiter = Thread { code = try { proc.waitFor() } catch (e: Exception) { -1 } }
        waiter.start()
        waiter.join(SHOT_TIMEOUT_MS)
        if (waiter.isAlive) {
            try { proc.destroy() } catch (e: Exception) {}
            logToFile("实时截图：screencap 超过 ${SHOT_TIMEOUT_MS}ms 没出图，放弃", "WARN", "SYNC", "SCREENSHOT")
            return null
        }
        reader.join(2000)
        if (overSize) {
            logToFile("实时截图：PNG 超过 ${SHOT_MAX_PNG_BYTES / 1024 / 1024}MB，判废", "WARN", "SYNC", "SCREENSHOT")
            return null
        }
        val bytes = png
        if (bytes == null || bytes.size < 1024 || code != 0) {
            logToFile("实时截图：screencap 退出码 $code，收到 ${bytes?.size ?: 0}B", "WARN", "SYNC", "SCREENSHOT")
            return null
        }
        if (!(bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte())) {
            logToFile("实时截图：stdout 不是 PNG（su 存在但跑不了 screencap？）", "WARN", "SYNC", "SCREENSHOT")
            return null
        }
        return bytes
    }

    /**
     * PNG → 宽不超过 SHOT_MAX_WIDTH 的 JPEG。两段缩放是必需的：8K 全屏解成 ARGB 接近 64MB，
     * 直接解会顶到堆上限；先让解码器按 2 的幂采样，再精确缩到目标宽。
     * 采样比取"缩完仍 ≥ 目标宽"的最大 2 的幂，所以最后一跳只会缩小、不会放大糊图。
     */
    private fun encodeShotJpeg(png: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SHOT_MAX_WIDTH) sample *= 2
        val scaled = BitmapFactory.decodeByteArray(png, 0, png.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try {
            val dst = if (scaled.width > SHOT_MAX_WIDTH) {
                val h = (scaled.height.toLong() * SHOT_MAX_WIDTH / scaled.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(scaled, SHOT_MAX_WIDTH, h, true)
            } else scaled
            val out = java.io.ByteArrayOutputStream()
            try {
                if (dst.compress(Bitmap.CompressFormat.JPEG, SHOT_JPEG_QUALITY, out)) out.toByteArray() else null
            } finally {
                if (dst !== scaled) dst.recycle()
            }
        } finally {
            scaled.recycle()
        }
    }

    /**
     * 把 JPEG 送回服务端给的 upload_url，令牌放在 x-shot-token 头里（不进 URL：日志会打 URL，
     * 令牌进了日志就等于进了任何能看到日志的人手里）。
     * @return HTTP 状态码；连不上或抛异常都是 -1
     */
    private fun postScreenshot(url: String, token: String, jpeg: ByteArray): Int {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "image/jpeg")
            conn.setRequestProperty("x-shot-token", token)
            conn.outputStream.use { it.write(jpeg) }
            val code = conn.responseCode
            try { conn.inputStream.readBytes() } catch (e: Exception) { /* 非 2xx 时正文在 errorStream，状态码够用 */ }
            code
        } catch (e: Exception) {
            -1
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    /** 截图一整个回合：拍 → 压 → 传。只在 shotExecutor 上跑（同一时刻最多一张，见【A-16】）。 */
    private fun captureAndUploadScreenshot(url: String, token: String) {
        val png = captureScreenPng() ?: return
        val jpeg = encodeShotJpeg(png)
        if (jpeg == null) {
            logToFile("实时截图：${png.size / 1024}KB PNG 解不动或转 JPEG 失败", "WARN", "SYNC", "SCREENSHOT")
            return
        }
        val code = postScreenshot(url, token, jpeg)
        logToFile("实时截图：${png.size / 1024}KB PNG → ${jpeg.size / 1024}KB JPEG，服务端回 $code",
            if (code in 200..299) "INFO" else "WARN", "SYNC", "SCREENSHOT")
    }

    // ========== 多窗口系统实现 ==========

    // 【A-07】 窗口配置 & 渲染
    /**
     * 应用窗口配置。优先走【原地应用】（见 applySceneChangesInPlace）：窗口清单与共享组构成没动时，
     * 换映射、改播放设置、新素材落地都只是"给那一格换一份清单"，播放器和它的 surface 一个都不释放。
     * 只有原地做不到（窗口增删/启停/换序、视频↔纯色互转、组成员变化、试播中、force）才整屏推倒：
     * release 掉全部旧播放器/视图，按 mergeSceneWindows 拿到的 A+B 窗口合集依 zIndex 升序逐个
     * createWindowView + playFolderInWindow。
     * @param scenes 完整 scenes JSON（A、B 两套都在里面，不是"当前那一幕"）
     * @param force  true = 一律整屏重建（墙板把共享组成员分到两格时，画布的构成必须重来，见 applyPanelSource）
     *               false（默认）= 与上次应用的是同一份配置就直接返回，不打断正在播的视频。
     * @param refreshIfStale true = 指纹没变也跑一遍原地换清单（素材对账专用）。指纹只比配置，
     *               新素材落盘/服务端删素材时它看不出来；不跑这一遍，新文件要等下次改配置才进清单。
     *
     * 兜底：两幕一个窗口都没有时，才建 DEFAULT_WINDOW_ID 那个整屏（尺寸=output 基准）SCENE_A 窗口
     *      （不指定 folderId，于是播 A 幕第一个有素材的文件夹）。
     * 必须在主线程调用（含 addView/removeView），所有调用点都已 post 到 mqttHandler。
     */
    private fun applySceneConfigs(scenes: JSONObject?, force: Boolean = false, refreshIfStale: Boolean = false) {
        if (exited) {
            // 退出期间任何配置落地都不许动画面：退出中云端改了窗口、rev 对账补发 sync、
            // update_windows 因屏上无窗口回落到全量重建 —— 三条都会在桌面身后把解码器拉起来。
            // 出退出只认 setExited(false) 这一条路；配置本身已照常落 prefs，那份 scenes_json 到时见【A-15】。
            logToFile("已退出到桌面，场景配置落地已忽略（唤回后按最新配置重建）", "WARN", "WINDOW", "DESKTOP")
            return
        }
        logToFile("开始应用场景配置", "INFO", "WINDOW", "CONFIG")
        // 有新配置在跑：作废被节流挂起的那份，让落地永远是最新的（旧场景稍后回灌会把画面倒回去）。
        // 万一下发的是不带窗口的局部载荷，丢掉的那次结构变更由 rev 对账在下个心跳补回来。
        rebuildGateRetry?.let { mqttHandler.removeCallbacks(it) }
        rebuildGateRetry = null
        healWaitRetry?.let { mqttHandler.removeCallbacks(it) }
        healWaitRetry = null
        if (scenes == null) {
            Log.w(TAG, "applySceneConfigs: scenes 为空")
            logToFile("scenes 为空，跳过窗口配置", "WARN", "WINDOW", "CONFIG")
            return
        }
        // 幂等闸门：同一条链路常常 apply 两次（指令进来一次、素材对账收尾一次），
        // 冷启动缓存 + auth_result 也是同一份配置。指纹没变就别把视频从头播一遍。
        // 记在末尾：中途抛异常时不算"已应用"，下一份相同载荷还会重试。
        val fp = scenesFingerprint(scenes)
        if (!force && fp == appliedScenesFp) {
            // 指纹只比配置：素材对账新落了文件/服务端删了素材时它一个字都不变，得让原地换清单
            // 跑一遍（playlistStale 才看得到磁盘）。原地做不到（试播中/屏上没账）就保持现状 ——
            // 一次同步绝不因此整屏重建，那正是【#224】94MB/轮的漏。
            if (refreshIfStale && applySceneChangesInPlace(scenes, fp)) return
            logToFile("scenes 与屏上一致（指纹 $fp），跳过全量重建", "INFO", "WINDOW", "SKIP")
            return
        }
        // 差集闸门：能原地做的绝不推倒整屏。台架实测每整屏重建一次，内核侧净留约 97MB dma-buf 且永不归还
        // （4GB 的板子，5 轮从 1.14GB 涨到 1.63GB，关窗也不降），十几轮就够 lmkd 开杀 —— 而日常那些"改了个映射"
        // "推了新素材"根本不需要动播放器，把 6 路解码器全部重来一遍才是漏的源头。
        if (!force && applySceneChangesInPlace(scenes, fp)) return

        // 冷启动低内存闸门（#228）：进程刚起来就要一次性建齐所有窗口 surface + 解码器输出池，
        // lmkd 正好在这个窗口开杀；杀了就重启、重启又倒在同一处，codec2 里没归还的 dma-buf
        // 一轮叠一轮（现场 8 轮连杀后 2.25GB）。可用内存低于 [COLD_START_MIN_MB] 时先挪 3s，
        // 最多等 [COLD_START_HOLD_MS]；等不到也得硬上，配置不能永远落不了地。
        // 只拦冷启动（屏上无窗口），正常运行的配置变更不受影响。
        if (appliedWindowOrder.isEmpty() && windowPlayers.isEmpty()) {
            val avail = availableMemMb()
            if (avail in 1 until COLD_START_MIN_MB) {
                val now = SystemClock.elapsedRealtime()
                if (coldStartGateSince == 0L) coldStartGateSince = now
                if (now - coldStartGateSince < COLD_START_HOLD_MS) {
                    // 等待期间可能又来一份更新的配置：换掉待跑的那份，落地永远用最新参数
                    coldStartRetry?.let { mqttHandler.removeCallbacks(it) }
                    val r = Runnable {
                        coldStartRetry = null
                        applySceneConfigs(scenes, force, refreshIfStale)
                    }
                    coldStartRetry = r
                    logToFile("冷启动可用内存仅 ${avail}MB，延后 3s 建窗口（已等 ${(now - coldStartGateSince) / 1000}s，" +
                        "上限 ${COLD_START_HOLD_MS / 1000}s）", "WARN", "WINDOW", "CONFIG")
                    mqttHandler.postDelayed(r, COLD_START_RETRY_MS)
                    return
                }
            }
        }

        // 重建节流：连着推倒整屏是这台 4GB 机器最贵的操作（每次重建 ~97MB dma-buf，见上方闸门注释）。
        // 只在相距太近时延后，正常单次改配置不受影响；延后期间的新配置会覆盖这份，落地仍是最新。
        val rebuildNow = SystemClock.elapsedRealtime()
        if (!force && lastRebuildAtMs != 0L && rebuildNow - lastRebuildAtMs < REBUILD_MIN_GAP_MS) {
            val r = Runnable {
                rebuildGateRetry = null
                applySceneConfigs(scenes, force, refreshIfStale)
            }
            rebuildGateRetry = r
            logToFile("整屏重建过于频繁（距上次 ${rebuildNow - lastRebuildAtMs}ms），延后 ${REBUILD_DEFER_MS}ms 再应用",
                "WARN", "WINDOW", "REBUILD")
            mqttHandler.postDelayed(r, REBUILD_DEFER_MS)
            return
        }

        // 拆窗后的回收还在路上（后台线程杀 HAL + 等 1.2s）：这份新配置先排队，别在空屏上再拆一遍，
        // 也别抢在旧配置前面建窗；期间到来的新配置会替换这份，落地永远是最新。
        if (postTeardownHealPending) {
            val r = Runnable {
                healWaitRetry = null
                applySceneConfigs(scenes, force, refreshIfStale)
            }
            healWaitRetry = r
            logToFile("解码器缓冲回收中，配置延后 ${REBUILD_HEAL_SETTLE_MS}ms 再应用", "WARN", "WINDOW", "REBUILD")
            mqttHandler.postDelayed(r, REBUILD_HEAL_SETTLE_MS)
            return
        }
        lastRebuildAtMs = rebuildNow

        // 走到这里才是真整屏重建（原地闸门没接住）。这一行是台架复测数"重建次数"的唯一锚点，
        // 别在别处复用：播放设置翻转等在原地路径里完成，不应计入重建。
        logToFile("回退全量重建：释放 ${windowPlayers.size} 路播放器，按新配置重排", "INFO", "WINDOW", "REBUILD")

        // 试播必须先于播放器释放前清账：这些窗口马上就要照新配置重建，
        // 留着旧快照只会让收尾回调对着已释放的实例写状态（见 abortAllPreviews）
        abortAllPreviews("场景配置重建")

        // 释放旧的窗口播放器
        windowPlayers.values.forEach { it.release() }
        windowPlayers.clear()
        videoSurfaces.clear()
        // 共享解码单元紧跟播放器之后拆：那块 Surface 底下就是解码器正在写的纹理，先放播放器再收画布
        destroyShareGroups()

        // 移除旧的窗口视图
        windowViews.values.forEach { flSurface?.removeView(it) }
        windowViews.clear()
        windowContentSigs.clear()
        windowNames.clear()

        // 低内存自愈（拆窗后、建窗前）：每轮全量重建都会在 codec2/rkvdec 侧留下内核 dma-buf 且不归还，
        // 连续改结构能把 4GB 的板子从 1GB 可用一路顶到 100MB 级，再往下就是 adbd 只握手不响应的死机。
        // 放在拆窗之后是因为此时本进程已没有解码客户端，杀 HAL 不会把谁挂在半路；#354 失败取证：
        // 自愈放在拆窗前，1.8s 的 /proc 扫描还没跑完 lmkd 先把进程杀了（HAL 的 onFramesRendered
        // 回调撞上空 listener 才 SIGSEGV——那是进程死亡的后果，不是杀出来的）。回收跑后台线程，
        // 完成后 post 回来接着建窗，主线程不等；失败也照常建窗。
        val healAvail = availableMemMb()
        val healNow = SystemClock.elapsedRealtime()
        val healUrgent = healAvail in 1 until REBUILD_HEAL_URGENT_MB
        if (healAvail in 1 until REBUILD_HEAL_MIN_MB &&
            (healUrgent || healNow - lastDecodeHealAtMs >= REBUILD_HEAL_COOLDOWN_MS)) {
            lastDecodeHealAtMs = healNow
            postTeardownHealPending = true
            logToFile("拆窗后可用内存仅 ${healAvail}MB${if (healUrgent) "（已破硬底线，跳过冷却）" else ""}，" +
                "先回收解码器缓冲再建窗", "WARN", "WINDOW", "REBUILD")
            Thread {
                val killed = killDecodeHal()
                // 不论杀没杀成，都留沉降时间让内核把旧缓冲引用放开，再量一次留证据
                try { Thread.sleep(REBUILD_HEAL_SETTLE_MS) } catch (e: InterruptedException) {}
                val after = availableMemMb()
                val what = when {
                    killed > 0 -> "完成（杀掉 $killed 个解码器进程）"
                    killed == 0 -> "未命中解码器进程"
                    else -> "脚本失败"
                }
                logToFile("解码器缓冲回收$what，可用 ${healAvail}MB→${after}MB，继续建窗", "WARN", "WINDOW", "REBUILD")
                mqttHandler.post {
                    postTeardownHealPending = false
                    healWaitRetry = null
                    // 回收这一两秒里可能已经退出到桌面了：别在桌面身后把窗口建回来
                    if (!exited) createWindowsForScenes(scenes, fp)
                }
            }.apply { isDaemon = true }.start()
            return
        }
        createWindowsForScenes(scenes, fp)
    }

    /**
     * 拆窗之后、按这份配置把窗口建起来（建视图、起播放器、落地应用账）。
     * 从 applySceneConfigs 拆出来，是为了让「拆窗后回收解码器缓冲」能插在两次之间而不动主线程：
     * 回收跑后台线程，完成后 post 回来接着建。必须在主线程调用。
     */
    private fun createWindowsForScenes(scenes: JSONObject, fp: String) {
        // 收集所有场景的窗口，A 和 B 的 windows 合并后统一渲染
        // 每个窗口播什么由自身 content 决定，不需要"当前幕"概念（485 墙板走独立链路，见 applyPanelSource）
        val allWindows = mergeSceneWindows(scenes)

        // 本次配置解析出的在播格：建窗时点歌屏显隐就按它定（分批起播的排队还没跑，读不到屏上落点账）
        var planFolders: List<String> = emptyList()

        // 若所有场景均无窗口，为 Scene A 创建默认全屏窗口
        if (allWindows.length() == 0) {
            val defaultContent = JSONObject().apply { put("type", "SCENE_A") }
            val defaultWin = JSONObject().apply {
                put("id", DEFAULT_WINDOW_ID)
                put("name", "窗口1")
                put("x", 0)
                put("y", 0)
                put("width", designW)
                put("height", designH)
                put("zIndex", 1)
                put("content", defaultContent)
            }
            createWindowView(DEFAULT_WINDOW_ID, defaultWin)
            // ⚠️ 必须把它塞回 allWindows：下面那圈"每个窗口按自身 content.type 选文件夹"的循环
            // 是按 allWindows 查配置的，漏了这一步就查不到 winObj → content 为空 → type="" →
            // 一个播放器都不启，画面全黑（视图建了、素材也在，就是不播）。
            // 表现：房间没配任何窗口时（新建房间/配置被清空）设备黑屏。
            allWindows.put(defaultWin)
            Log.d(TAG, "所有场景无窗口，自动创建默认全屏窗口1 -> SCENE_A")
        } else {
            // 生效窗口按 z 升序（id 口径与实时更新共用 enabledWindowsSorted，两边才不会比出假结构变化）
            val plan = enabledWindowsSorted(scenes).map { (winId, w) ->
                val content = w.optJSONObject("content") ?: JSONObject()
                // 播哪一格先解析出来：同格 ≥2 扇要在起播放器之前就认成一组，等各路解码各建好了再合就晚了
                PlanWindow(winId, w, content, resolveWindowFolder(scenes, content, winId))
            }
            planFolders = plan.mapNotNull { it.folderId }.distinct()
            // 纯色 / HDMI 窗口本来就零解码，不掺进共享组
            val groupsByFolder = plan.filter { isVideoContentType(it.content) && it.folderId != null }
                .groupBy { it.folderId!! }
                .filterValues { it.size >= 2 }
            // 组的画布放在"该组最上层那扇成员"原来的 z 位置：下面的窗口照常透出来，
            // 被并集矩形盖到的别组窗口则按这一层的 z 被压过去（共享解码的唯一取舍，见 ShareCanvasView）
            val lastIdxOfGroup = HashMap<String, Int>()
            for ((idx, p) in plan.withIndex()) p.folderId?.let { if (groupsByFolder.containsKey(it)) lastIdxOfGroup[it] = idx }
            for ((idx, p) in plan.withIndex()) {
                val fk = p.folderId
                if (fk != null && groupsByFolder.containsKey(fk)) {
                    if (lastIdxOfGroup[fk] == idx) createShareGroup(fk, groupsByFolder[fk]!!)
                    continue
                }
                createWindowView(p.winId, p.win)
            }
            // 每个窗口根据自身 content.type 播放对应文件夹（共享组的跟随窗在 playFolderInWindow 里自己让路）
            // 低内存分批起播（#228）：几路解码器的冷缓存尖峰不要挤在同一帧上；具体间隔见 startPacingMs()。
            val pacingMs = startPacingMs()
            if (pacingMs > 0) logToFile("低内存分批起播：每扇间隔 ${pacingMs}ms", "WARN", "PLAYBACK", "START")
            var paced = 0
            for (p in plan) {
                val fk = p.folderId ?: continue
                if (pacingMs > 0 && paced > 0) {
                    val delay = minOf(paced * pacingMs, 3 * pacingMs)
                    // 排上之后这段时间里可能退出到桌面或又被重建：别把解码器再点着一次
                    mqttHandler.postDelayed({
                        if (!exited && windowPlayers.containsKey(p.winId)) playFolderInWindow(p.winId, fk, scenes)
                    }, delay.toLong())
                } else {
                    playFolderInWindow(p.winId, fk, scenes)
                }
                paced++
            }
        }
        applyHdmiVisibility(scenes, planFolders)
        // 窗口齐了才谈得上 LOGO（两名单纯看 id、看窗口可见性与落点格）；先于运维面板刷，
        // applyStatusUi 收尾会把面板与新 LOGO 层的层级一起摆对
        refreshLogoLayer(scenes)
        // 窗口齐了才给每扇窗放运维面板；放完 applyStatusUi 会把当前文案落到新面板上
        refreshWindowStatusOverlays()
        appliedScenes = scenes
        appliedScenesFp = fp
        appliedPlaySettingsJson = playSettingsJson(scenes)
        // 次序账与指纹同步落地（默认窗口那条兜底分支也要走到，否则实时更新会一直误判成结构变化）
        appliedWindowOrder = enabledWindowsSorted(scenes).map { it.first }
        // 闸门收账：这次确实建起来了，下一次冷启动重新给满等待额度
        coldStartGateSince = 0L
        coldStartRetry = null
    }

    /**
     * 原地应用一份新 scenes：只换清单、不换播放器。
     *
     * 判"要不要换清单"用三把尺，任一把响就只重排那一路播放器的清单：
     *   1. 内容签名变了（换了格子 / 勾了跟随 / 改了映射指向的那一格）；
     *   2. 播放设置变了且这一格的随机/顺序确实翻转（设置不在 content 里，签名字比不出来）；
     *   3. 这一格磁盘上的文件和在播的清单对不上了 —— 新素材刚下载完、或同步时删过文件。
     * 三把尺都不响而指纹仍不同，说明变的只是几何/透明度/亮度/取景，交给 applyLiveWindowUpdate 原地改视图。
     *
     * 原地做不到、必须回整屏重建的情况（一律 return false，让调用方走老路）：
     *   - 屏上还没有按配置建起来的现场（appliedWindowOrder 空：冷启动、退出到桌面后）；
     *   - 正在试播（清单归试播管，让路逻辑见 abortAllPreviews）；
     *   - 生效窗口清单变了（增删 / enabled / 换序）；
     *   - 共享解码组的成员构成变了（要拆要建的是那块 GL 画布，不是清单）；
     *   - 视频↔纯色/HDMI 互转（换的是视图）；
     *   - 某一扇窗在 windowContentSigs 里没有账（视图当初就没建起来）。
     * 必须在主线程调用（摸 windowViews / windowPlayers / addView）。
     *
     * @param fp 本次 scenes 的结构指纹，调用方已算好，原地落地后照它记账
     * @return true = 已全部原地应用并记好账，调用方可以直接返回
     */
    private fun applySceneChangesInPlace(scenes: JSONObject, fp: String): Boolean {
        if (appliedWindowOrder.isEmpty() || previews.isNotEmpty()) return false
        val desired = enabledWindowsSorted(scenes)
        if (desired.map { it.first } != appliedWindowOrder) return false

        val plan = desired.map { (winId, w) ->
            val content = w.optJSONObject("content") ?: JSONObject()
            PlanWindow(winId, w, content, resolveWindowFolder(scenes, content, winId))
        }
        // 同格 ≥2 扇才成组：组按"成员构成"比对，不按格子名 —— 墙板换格/勾了跟随是同一组人整组换到另一格，
        // 解码路数与画布都不变，原地换清单就够；只有成员增删（含两组并到一格）才必须重建。
        val desiredGroups = plan.filter { isVideoContentType(it.content) && it.folderId != null }
            .groupBy { it.folderId!! }.filterValues { it.size >= 2 }
            .mapValues { entry -> entry.value.map { it.winId }.toSet() }
        if (shareGroups.values.map { it.members.toSet() }.toSet() != desiredGroups.values.toSet()) return false
        // 构成相同但格子换了：先把组名对齐（目标格已被别组占着就回退，换名等于并组，画布合不了）
        for ((folderKey, winIds) in desiredGroups) {
            val grp = winShareGroup[winIds.first()] ?: continue
            if (grp.folderKey == folderKey) continue
            if (shareGroups.containsKey(folderKey)) return false
            shareGroups.remove(grp.folderKey)
            grp.folderKey = folderKey
            shareGroups[folderKey] = grp
        }

        val settingsChanged = playSettingsJson(scenes) != appliedPlaySettingsJson
        val refresh = ArrayList<PlanWindow>()
        for (p in plan) {
            val oldSig = windowContentSigs[p.winId] ?: return false
            val newSig = contentSignature(p.content)
            if (oldSig != newSig) {
                if (!isVideoContentSig(oldSig) || !isVideoContentSig(newSig)) return false
                refresh.add(p)
                continue
            }
            val folderId = p.folderId
            if (!isVideoContentSig(newSig) || folderId == null) continue
            val surf = videoSurfaces[p.winId] ?: return false
            if ((settingsChanged && surf.settings.shuffle != playSettingsOf(scenes, folderId).shuffle) ||
                playlistStale(folderId)) refresh.add(p)
        }

        // 换清单：播放器和它的 surface 全程不动（共享组的跟随窗在这里只记账，实际那一路由基准窗推）
        for (p in refresh) p.folderId?.let { playFolderInWindow(p.winId, it, scenes) }
        for (p in plan) windowContentSigs[p.winId] = contentSignature(p.content)
        appliedScenes = scenes
        appliedScenesFp = fp
        appliedPlaySettingsJson = playSettingsJson(scenes)
        // 次序与签名上面已对齐，这里判不出结构变化，只会原地改视图几何并把共享画布按并集重算一次
        applyLiveWindowUpdate(scenes)
        // 播放设置里的点歌屏开关也走这条原地路：只改 HDMI 窗显隐，不停不重建视频播放器
        applyHdmiVisibility(scenes, plan.mapNotNull { it.folderId })
        logToFile("配置原地应用：${plan.size} 扇窗只换 ${refresh.size} 路清单，播放器零释放（指纹 $fp）", "INFO", "WINDOW", "INPLACE")
        return true
    }

    /**
     * 这一格磁盘上的文件是不是已经跟在播的清单对不上了（新素材落地 / 同步删了文件）。
     * 比较口径与 playFolderInWindow 用的完全一致（同一份清单缓存 + 同一个 sameItems），两处各写一份时
     * 就会出现"这边认为变了、那边 setMediaItems 又判没变"的白工。
     */
    private fun playlistStale(folderId: String): Boolean {
        val cached = folderPlaylists[folderId] ?: return true
        val dir = materialDirOf(folderId) ?: return false
        val listed = dir.listFiles()?.filter { it.extension.lowercase() in VIDEO_EXTS }
            ?.sortedBy { it.name }?.map { MediaItem.fromUri(Uri.fromFile(it)) } ?: return false
        return !sameItems(cached, listed)
    }

    /** 内容签名（type|folderId|…）里只看类型那一段是不是真素材 —— 纯色/HDMI 本来就没有播放器 */
    private fun isVideoContentSig(sig: String): Boolean {
        val type = sig.substringBefore('|')
        return type != "HDMI" && type != "VIDEO_INPUT" && type != "COLOR"
    }

    /**
     * 一扇窗在这幕里该播哪一格 —— 全量重建（applySceneConfigs）和墙板切换（applyPanelSource）
     * 都走这一个入口：两处各写一份时口径必然漂（folderId 带不带幕前缀就漂过一次）。
     * 解析次序：
     *   跟随墙板（content.follow）→ 当前触发源对应的那一格（panelFolderFor：学习帧查绑定表）；
     *     没按过 / 没学这一帧就退回下面的固定逻辑，不劫持；
     *   content.folderId 非空 → 只播指定那格，不在映射/那格空则 null（不偷偷换播别的格）；
     *   两者都空 → 本幕第一个有素材的文件夹（旧配置行为，也是没配映射时的兜底）。
     * @return 带幕前缀的物理目录 id（playFolderInWindow 的 folderId 入参口径），null = 这一窗不播
     */
    private fun resolveWindowFolder(scenes: JSONObject, content: JSONObject, winId: String): String? {
        val type = content.optString("type", "").uppercase()
        // HDMI 窗没有素材可播：画面来自 createHdmiView 的相机流。返回非 null 会让调用方
        // 拿着一个不存在的播放器去启动，每次重建白打一条"找不到播放器"的 ERROR
        if (type == "HDMI" || type == "VIDEO_INPUT") return null
        if (type != "SCENE_A" && type != "SCENE_B") return null

        // 场景 folder_mappings 的 key 已带场景前缀（A01/B01，见 buildPrefixedScenes）
        val sceneKey = type.removePrefix("SCENE_")  // "SCENE_A" → "A"
        val sceneData = scenes.optJSONObject(sceneKey)
        val mappings = sceneData?.optJSONObject("folder_mappings") ?: JSONObject()
        val allEntries = mappings.keys().asSequence()
            .map { fid -> fid to (mappings.get(fid) as? org.json.JSONArray ?: org.json.JSONArray()) }
            .toList()

        // 跟随的落点只看当前触发源（panelFolderFor：学习帧查绑定表，一格一码不分幕；DMX 通道 1 的值即格子号）。
        // 全量重建和墙板/DMX 切换必须同一个入口：各写一份时，重建会把人工学好的那一格忘掉，
        // 只有墙板正好按下时才切得对。DMX 还要过该幕 play_settings.dmx 的闸 ——
        // 没开的那一幕不认这一路通道，重建时也不该被它劫持，回到自己的固定逻辑。
        val rawPanelFolder = if (content.optBoolean("follow", false)) panelFolderFor() else null
        val panelFolder = rawPanelFolder?.takeIf { fid ->
            panelSource !is PanelSource.Dmx || dmxAllowedFor(scenes, fid, content)
        }
        // 指定了文件夹（content.folderId，纯编号如 "02"）：只播指定目录，
        // 该目录无素材/未映射时告警跳过，不偷偷换播别的文件夹
        val requested = panelFolder ?: content.optString("folderId", "").trim()
        if (panelFolder != null) {
            val srcText = panelSource?.let { describeSource(it) } ?: "未触发"
            logToFile("窗口 [$type] 跟随$srcText → 文件夹 $panelFolder", "INFO", "WALLPANEL", "SELECT")
        }
        if (requested.isEmpty()) {
            // 未指定：自动选场景中第一个有素材的文件夹（旧配置向后兼容）
            val auto = allEntries.firstOrNull { it.second.length() > 0 }?.first
            if (auto == null) {
                Log.d(TAG, "窗口 $winId [$type] 场景 $sceneKey 无素材，跳过播放")
                logToFile("窗口 [$type] 场景 $sceneKey 无素材，跳过播放（黑屏最常见原因）", "WARN", "PLAYBACK", "SKIP")
            } else {
                Log.d(TAG, "窗口 $winId [$type] -> 自动选择文件夹 $auto")
                logToFile("窗口 [$type] -> 自动选择文件夹 $auto", "INFO", "PLAYBACK", "SELECT")
            }
            return auto?.let { prefixedPhysicalFolder(it, sceneKey) }
        }
        val entry = allEntries.find { it.first == sceneKey + requested }
            ?: allEntries.find { it.first == requested }  // 兼容已带前缀的 folderId
        return when {
            entry == null -> {
                Log.w(TAG, "窗口 $winId [$type] 指定文件夹 $requested 不在场景 $sceneKey 的映射中，跳过播放")
                logToFile("窗口 [$type] 指定文件夹 $requested 不在场景 $sceneKey 映射中，跳过播放", "WARN", "PLAYBACK", "SKIP")
                null
            }
            entry.second.length() == 0 -> {
                Log.w(TAG, "窗口 $winId [$type] 指定文件夹 ${entry.first} 无素材，跳过播放")
                logToFile("窗口 [$type] 指定文件夹 ${entry.first} 无素材，跳过播放", "WARN", "PLAYBACK", "SKIP")
                null
            }
            else -> {
                Log.d(TAG, "窗口 $winId [$type] -> 指定文件夹 ${entry.first}")
                logToFile("窗口 [$type] -> 指定文件夹 ${entry.first}", "INFO", "PLAYBACK", "SELECT")
                prefixedPhysicalFolder(entry.first, sceneKey)
            }
        }
    }

    /**
     * scenes 的结构指纹：递归按 key 排序后取 SHA-256 前 16 位，忽略 "_" 前缀的运行时临时标记
     * （mergeSceneWindows 会往窗口对象上打 _masterB）。
     * 排序键是为了让同一份配置不管来自素材同步载荷、窗口载荷还是 prefs 缓存反序列化都算出同一个值；
     * 宁可保守（无关字段变了也重建）也不能漏（该重建不重建 = 画面停在旧配置上，修都修不好）。
     * 输出基准不在 scenes 里（它是 config 顶层键），但同一份 scenes 换一把尺子屏上就是另一套矩形，
     * 所以把基准拼进指纹 —— 漏了它，"只改了基准"的载荷会被这道闸门判成没变，画面停在旧几何上。
     */
    private fun scenesFingerprint(scenes: JSONObject): String =
        sha256(canonicalJson(scenes) + "|$designW x $designH").substring(0, 16)

    /** JSON 规范化成稳定字符串：对象键按字典序、下划线开头的运行时字段剔掉 */
    private fun canonicalJson(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().filter { !it.startsWith("_") }.sorted()
            .joinToString(",", "{", "}") { "\"" + it + "\":" + canonicalJson(v.opt(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonicalJson(v.opt(it)) }
        else -> v.toString()   // 数字/字符串/true/false/JSONObject.NULL，toString 即可区分
    }

    /** 按 zIndex 升序排列 JSONArray */
    private fun sortWindowsByZ(arr: JSONArray): JSONArray {
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        list.sortBy { it.optInt("zIndex", 0) }
        return JSONArray(list)
    }

    /** 合并 A/B 场景窗口并打上各自 master 亮度标记（scenes.A/B.master.brightness → _masterB） */
    private fun mergeSceneWindows(scenes: JSONObject): JSONArray {
        val allWindows = JSONArray()
        scenes.keys().forEach { sceneKey ->
            val sceneObj = scenes.optJSONObject(sceneKey)
            // 此 scenes 为每次 MQTT/启动时新解析的临时对象，打标不会污染持久化数据
            val sceneMasterB = sceneObj?.optJSONObject("master")?.optDouble("brightness", 1.0) ?: 1.0
            sceneObj?.optJSONArray("windows")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val wObj = arr.getJSONObject(i)
                    wObj.put("_masterB", sceneMasterB)
                    allWindows.put(wObj)
                }
            }
        }
        return allWindows
    }

    /**
     * 窗口内容签名：type/folderId/color/inputIndex/follow 任一变化即视为结构变化（需重建播放器）。
     * follow 必须在里面：勾选/取消"跟随墙板"会改变这一窗该播哪一格，
     * 不进签名就会走成 live 轻推（只改几何），画面停在旧文件夹上看不出来。
     */
    private fun contentSignature(c: JSONObject): String =
        c.optString("type", "SCENE_A").uppercase() + "|" + c.optString("folderId", "") +
            "|" + c.optString("color", "") + "|" + c.optInt("inputIndex", 0) +
            "|" + c.optBoolean("follow", false)

    /** 物理文件夹统一带场景前缀：映射键可能是 "01"（纯编号）也可能是 "A01"，这里补齐成 "A01"。
     *  真正的落盘目录由 playFolderInWindow / syncFolderWithIds 再翻成 scenea/01（无前缀的 "01" 才用根目录 01/） */
    private fun prefixedPhysicalFolder(key: String, sceneKey: String): String =
        if (key.startsWith(sceneKey)) key else sceneKey + key

    /**
     * 屏上该有哪些窗：z 升序 → 补缺省 id（"win_位置"，位置按含隐藏窗口的完整排序数组算）→ 只留启用。
     * 全量重建与实时更新共用这一个入口：两边各自算次序时，缺省 id 与 enabled 过滤会算出"假的"结构变化，
     * 每一轮轻量更新都白重建一次（视频从头播）。
     */
    private fun enabledWindowsSorted(scenes: JSONObject): List<Pair<String, JSONObject>> {
        val arr = sortWindowsByZ(mergeSceneWindows(scenes))
        return (0 until arr.length()).map { idx ->
            val w = arr.getJSONObject(idx)
            w.optString("id", "win_$idx") to w
        }.filter { it.second.optBoolean("enabled", true) }
    }

    /**
     * 实时窗口更新（update_windows 轻量路径）：编辑器拖动/调参的亚秒级跟随。
     * 仅几何/视觉属性变化 → 原地改 LayoutParams/alpha/遮罩/矩阵，播放器持续播放不重建；
     * 结构变化（窗口增删/内容签名/显隐/z序/播放设置）→ 回退 applySceneConfigs 全量重建。
     */
    private fun applyLiveWindowUpdate(scenes: JSONObject) {
        val desired = enabledWindowsSorted(scenes)
        val desiredIds = desired.map { it.first }

        // 屏上次序读自己记的那本（applySceneConfigs 成功落地时写的），不从 flSurface 的孩子反推：
        // 共享解码组是一块画布代表 N 扇窗，按孩子数永远数不齐，每轮轻量更新都会误判成结构变化白重建一次
        // 播放设置不在窗口 content 里，内容签名比不出它：只改顺序（随机/顺序）时必须判成结构变化回退重建，
        // 否则轻路径改改几何就返回，playlist 不重排、设置看着保存了却永远不生效
        var structural = desiredIds != appliedWindowOrder || playSettingsJson(scenes) != appliedPlaySettingsJson
        if (!structural) {
            for ((winId, w) in desired) {
                if (windowContentSigs[winId] != contentSignature(w.optJSONObject("content") ?: JSONObject())) {
                    structural = true
                    break
                }
            }
        }
        if (structural) {
            // 只报"交给配置落地"，不报结果：播放设置翻转/换格这类在 applySceneConfigs 里会走原地换清单，
            // 真正释放播放器的全量重建由那边自己打一条（否则复测按这行数重建次数会把原地算成重建）
            logToFile("实时更新含结构变化，交给配置落地判定（原地优先）", "INFO", "WINDOW", "CONFIG")
            applySceneConfigs(scenes)
            return
        }

        for ((winId, w) in desired) {
            // 窗口 JSON 先落到 surf：共享组重排画面、下一次比较都从它取最新配置。
            // 共享组成员的几何/透明度/亮度长在 GL 画布上（一块画布代表整组），这里一律跳过，
            // 组级重排放到循环后面做一次 —— 并集矩形要按全组最新的几何算，逐扇改会改出一半新一半旧
            videoSurfaces[winId]?.winJson = w
            windowNames[winId] = w.optString("name", "")
            if (winShareGroup.containsKey(winId)) continue
            val c = windowViews[winId] as? FrameLayout ?: continue

            val (x, y, wd, ht) = windowGeom(w)
            val p = c.layoutParams as? FrameLayout.LayoutParams ?: continue
            if (p.leftMargin != x || p.topMargin != y || p.width != wd || p.height != ht) {
                p.leftMargin = x
                p.topMargin = y
                p.width = wd
                p.height = ht
                c.layoutParams = p
            }

            c.alpha = w.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f)

            val effB = (w.optDouble("brightness", 1.0).toFloat() * w.optDouble("_masterB", 1.0).toFloat()).coerceIn(0f, 1f)
            var dim: View? = null
            for (i in 0 until c.childCount) {
                val ch = c.getChildAt(i)
                if (ch.tag == DIM_TAG) { dim = ch; break }
            }
            if (effB < 1f) {
                if (dim == null) {
                    val d = DitherDimView(this).apply {
                        setBrightness(effB)
                        tag = DIM_TAG
                    }
                    c.addView(d, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                } else {
                    (dim as DitherDimView).setBrightness(effB)
                }
            } else {
                dim?.let { c.removeView(it) }
            }

            // 布局稳定后重算截取/旋转/镜像矩阵（取景与 90°/270° 预缩放都依赖视图实际尺寸）；
            // 尺寸仍为 0 时由 applyTransformWhenReady 挂一次性布局监听补做。
            val cv = c.getChildAt(0)
            if (cv != null) c.post { applyTransformWhenReady(cv, w) }
        }
        // 共享解码组按全组最新几何重排画面（并集矩形 + 每扇窗自己的矩形）
        for (fk in shareGroups.keys.toList()) refreshShareGroup(fk)
        // 窗口几何/透明度和落点账都换了：LOGO 层按新的窗矩形与 play_settings 重排一次
        refreshLogoLayer(scenes)
        // 窗口几何变了，窗口正中的运维面板跟着挪
        refreshWindowStatusOverlays()
        // 屏上现在就是这份配置了：指纹跟着走，否则下一轮素材对账收尾会白重建一次、视频重头播
        appliedScenes = scenes
        appliedScenesFp = scenesFingerprint(scenes)
        appliedPlaySettingsJson = playSettingsJson(scenes)
        Log.d(TAG, "applyLiveWindowUpdate: 轻量应用 ${desired.size} 个窗口")
    }

    /**
     * 点歌屏（HDMI 采集）窗口的显隐：看给定的在播格的「点歌屏模式」，只显示同一模式的那套点歌窗。
     * 模式 0=关、1~3=模式N（窗口配置里内容属性 content.mode 选的，缺省 1）。
     * 多格同时在播各选了不同模式时取最大那个（A/B 分歧、多点歌屏宁可按大的那套出，不黑屏）；
     * 配置落地时给的是屏上全部在播格；墙板/DMX 触发时只给这次真落到的格：某一幕没这一格素材就保持原画面，
     * 不参与判定（老板 2026-11-19 定调"没素材不切就行"）。
     * 每格默认关（2026-11-18 用户定调），不配置的房间点歌屏窗口不显示。
     * 关闭 = 整扇窗 GONE：TextureView 不可见时会销毁自己那块 Surface，采集侧收到 detach；
     * 最后一路卸完 HdmiCapture 也不立刻关相机，而是留 20s 宽限（整屏重建的 detach→attach
     * 只隔一两百毫秒，一收一开就是一次相机会话重建，HAL 侧会留 dma-buf），确认没人用了才收。
     * 再打开时走原有 attach 链重开。
     * 没有任何视频窗在播（纯色/HDMI 房间）时按默认显示全部：这时没有格子能表达"关"。
     * @param folderIds 参与判定的在播格；null = 读屏上落点账（videoSurfaces.folderKey）
     */
    private fun applyHdmiVisibility(scenes: JSONObject?, folderIds: Collection<String>? = null) {
        val active = (folderIds ?: videoSurfaces.values.map { it.folderKey })
            .filter { it.isNotEmpty() }.distinct()
        val wantMode = active.map { playSettingsOf(scenes, it).hdmiMode }.maxOrNull() ?: 0
        val showAll = active.isEmpty()
        // winId → 这扇点歌窗归属的模式；缺省/非法一律按模式1（与云端"缺键=模式1"同口径）
        val winModes: Map<String, Int> = if (scenes == null) emptyMap() else
            enabledWindowsSorted(scenes).associate { (id, w) ->
                val c = w.optJSONObject("content")
                id to if (c == null || !c.optString("type", "").equals("HDMI", true)) 1
                else c.optInt("mode", 1).let { if (it in 1..3) it else 1 }
            }
        for ((winId, view) in windowViews) {
            // HDMI 窗的身份直接看内容签名（"HDMI|…"），不另记一份集合 —— 重建清账时不会漏删漏建
            if (windowContentSigs[winId]?.startsWith("HDMI|") != true) continue
            val mine = winModes[winId] ?: 1
            val show = showAll || (wantMode > 0 && mine == wantMode)
            val want = if (show) View.VISIBLE else View.GONE
            if (view.visibility == want) continue
            view.visibility = want
            logToFile("点歌屏：在播文件夹 ${active.joinToString("、").ifEmpty { "无" }}（模式 $wantMode）→ " +
                (if (show) "显示" else "关闭") + "窗口 $winId（模式 $mine）", "INFO", "WINDOW", "HDMI")
        }
        // 被关掉的那扇窗，窗口正中的运维面板要一起跟着藏起来
        applyStatusUi()
    }

    /** 创建单个窗口视图并加入 flSurface */
    private fun createWindowView(winId: String, w: JSONObject) {
        logToFile("开始创建窗口: id=$winId", "INFO", "WINDOW", "CREATE")
        if (flSurface == null) {
            Log.e(TAG, "flSurface 为 null，无法创建窗口")
            logToFile("flSurface 为 null，无法创建窗口", "ERROR", "WINDOW", "CREATE")
            return
        }

        // Arena 对标：enabled=false 的窗口不创建视图、不占用 ExoPlayer 实例
        if (!w.optBoolean("enabled", true)) {
            Log.d(TAG, "窗口 $winId enabled=false，跳过创建")
            logToFile("窗口 $winId 已隐藏(enabled=false)，跳过创建", "INFO", "WINDOW", "CREATE")
            return
        }

        // 分辨率适配 + 几何夹取见 windowGeom（分母是这间房的输出基准，编辑器画布同一把尺）
        val (x, y, width, height) = windowGeom(w)

        val content = w.optJSONObject("content") ?: JSONObject()
        val type = content.optString("type", "SCENE_A").uppercase()

        val view: View = when (type) {
            "COLOR" -> createColorView(w, content)
            "HDMI"  -> createHdmiView(winId, w, content)
            else    -> createVideoWindowView(winId, w, content)  // VIDEO / 默认
        }

        // Arena 对标属性：透明度直接作用容器；亮度=窗口亮度×所在屏总亮度，用黑色遮罩实现
        // （ExoPlayer 无直接亮度接口，遮罩法对 COLOR/VIDEO/HDMI 三类内容统一适用）
        val opacity = w.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f)
        val brightness = w.optDouble("brightness", 1.0).toFloat().coerceIn(0f, 1f)
        val masterB = w.optDouble("_masterB", 1.0).toFloat().coerceIn(0f, 1f)
        val effB = (brightness * masterB).coerceIn(0f, 1f)

        val container = FrameLayout(this).apply { alpha = opacity }
        container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (effB < 1f) {
            val dim = DitherDimView(this).apply {
                setBrightness(effB)
                tag = DIM_TAG
            }
            container.addView(dim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        val params = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = x
            topMargin = y
            gravity = Gravity.TOP or Gravity.START
        }

        flSurface?.addView(container, params)
        // 全关黑布必须留在最上层：重建时新窗口是后加进来的，会把盖布压到下面
        panelBlackout?.bringToFront()
        windowViews[winId] = container
        windowContentSigs[winId] = contentSignature(content)
        windowNames[winId] = w.optString("name", "")
        // 布局完成后应用截取/旋转/镜像（取景与矩阵中心都依赖实际视图尺寸），未就绪则等首次布局再补
        container.post { applyTransformWhenReady(view, w) }
        val cr = cropRect(w)
        Log.d(TAG, "创建窗口: id=$winId type=$type size=${width}x${height} pos=($x,$y) opacity=$opacity brightness=$effB flip=${w.optString("flip","none")} rot=${w.optInt("rotation",0)} crop=(${cr[0]},${cr[1]},${cr[2]},${cr[3]})")
    }

    /**
     * 一扇窗在这份配置里的完整现场（applySceneConfigs 两趟共用：先解析、再按解析结果建视图）。
     * folderId 为 null = 这一窗不播（没映射 / 那格空 / 纯色 / HDMI）。
     */
    private class PlanWindow(val winId: String, val win: JSONObject, val content: JSONObject, val folderId: String?)

    /**
     * 同格共享解码单元：一路 ExoPlayer（记在基准窗 id 上）+ 一块 GL 画布（成员矩形的并集），
     * 画布把解码出来的同一张纹理按每扇成员窗的几何各画一遍。
     * leaderId 取组内 ID 最小那扇 —— 与 groupClock 同一口径，不在两处各挑一个。
     * members 按 z 升序，画布照这个次序叠（后画的在上），与原来"后加入者在上层"的观感对齐。
     */
    private class ShareGroup(
        var folderKey: String,
        val leaderId: String,
        val container: FrameLayout,
        val canvas: ShareCanvasView,
        val members: List<String>
    )

    /** 这扇窗的内容是不是真素材轮播 —— 只有它谈得上共享解码（纯色/HDMI 本来就不起解码器）。
     *  口径与原地应用共用 isVideoContentSig：两处各判各的会漏掉 "VIDEO_INPUT" 这种别名 */
    private fun isVideoContentType(content: JSONObject): Boolean = isVideoContentSig(contentSignature(content))

    /**
     * 建一个共享解码单元：同格 ≥2 扇窗只起一路播放器，其余窗口既不建 TextureView 也不建播放器。
     * 只在 applySceneConfigs（全量重建）里调用：组成员变化必然伴随窗口增删或内容变化，那两种都算结构变化，
     * 轻量路径一律回退到这里，所以 update_windows 永远不必改组的构成。
     * 只能在主线程调用（addView + 建播放器）。
     */
    private fun createShareGroup(folderId: String, items: List<PlanWindow>) {
        val ordered = items.sortedBy { it.win.optInt("zIndex", 0) }
        val ids = ordered.map { it.winId }
        val leaderId = ids.sorted().first()
        val canvas = ShareCanvasView(this)
        val container = FrameLayout(this)
        container.addView(canvas, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        flSurface?.addView(container, FrameLayout.LayoutParams(1, 1).apply { gravity = Gravity.TOP or Gravity.START })
        panelBlackout?.bringToFront()
        val grp = ShareGroup(folderId, leaderId, container, canvas, ids)
        shareGroups[folderId] = grp
        for (winId in ids) winShareGroup[winId] = grp
        // 一路解码器喂一块画布。解码端的 Surface 要等画布拿到 TextureView 的 st、建好 GL 上下文
        // 才诞生（比这里晚），所以交接走回调；素材分辨率变了（4K 混 1080p）由 VideoSize 回调报给
        // 离屏纹理，尺寸不落对，MediaCodec 就可能按 1x1 起流水线
        val player = buildExoPlayer().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
        }
        canvas.onDecoderSurface = { surf -> player.setVideoSurface(surf) }
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) { canvas.setDecoderSize(videoSize.width, videoSize.height) }
        })
        armWindowLogger(leaderId, player)
        windowPlayers[leaderId] = player
        for (p in ordered) {
            // 成员共用同一块容器：windowViews 里每扇窗都指到它，摘旧视图时重复 removeView 是无害空转
            windowViews[p.winId] = container
            windowContentSigs[p.winId] = contentSignature(p.content)
            windowNames[p.winId] = p.win.optString("name", "")
            videoSurfaces[p.winId] = VideoSurface(container, canvas, p.win, PlaySettings())
        }
        refreshShareGroup(folderId)
        logToFile("同格 $folderId 共享解码：${ids.size} 扇窗只起 1 路播放器（基准 $leaderId，成员 ${ids.joinToString("、")}）",
            "INFO", "WINDOW", "CREATE")
    }

    /**
     * 按每扇成员窗最新的配置重算共享画布：容器摆到成员矩形的并集，画布内按各自几何与画面参数排 N 个矩形。
     * 全量重建建组时调一次，update_windows 轻量路径每轮也调（编辑器拖窗、改取景、改透明度都走这里，
     * 不必回退重建）。只能在主线程调用（读窗口配置、写 LayoutParams）。
     */
    private fun refreshShareGroup(folderKey: String) {
        val grp = shareGroups[folderKey] ?: return
        val raw = ArrayList<ShareCanvasView.Member>()
        for (winId in grp.members) {
            val surf = videoSurfaces[winId] ?: continue
            val w = surf.winJson
            val g = windowGeom(w)
            val masterB = w.optDouble("_masterB", 1.0).toFloat().coerceIn(0f, 1f)
            raw.add(ShareCanvasView.Member(
                winId = winId,
                left = g[0],
                top = g[1],
                width = g[2],
                height = g[3],
                alpha = w.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                brightness = (w.optDouble("brightness", 1.0).toFloat() * masterB).coerceIn(0f, 1f),
                crop = cropRect(w),
                rotation = w.optInt("rotation", 0),
                flip = w.optString("flip", "none")
            ))
        }
        if (raw.isEmpty()) return
        var minX = raw[0].left
        var minY = raw[0].top
        var maxX = minX + raw[0].width
        var maxY = minY + raw[0].height
        for (m in raw) {
            if (m.left < minX) minX = m.left
            if (m.top < minY) minY = m.top
            if (m.left + m.width > maxX) maxX = m.left + m.width
            if (m.top + m.height > maxY) maxY = m.top + m.height
        }
        val cw = (maxX - minX).coerceAtLeast(1)
        val ch = (maxY - minY).coerceAtLeast(1)
        val p = grp.container.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(cw, ch).apply { gravity = Gravity.TOP or Gravity.START }
        if (p.leftMargin != minX || p.topMargin != minY || p.width != cw || p.height != ch) {
            p.leftMargin = minX
            p.topMargin = minY
            p.width = cw
            p.height = ch
            grp.container.layoutParams = p
        }
        // 成员坐标换成"画布内部像素"，画布因此可以整块挪到并集左上角
        grp.canvas.setMembers(raw.map { it.copy(left = it.left - minX, top = it.top - minY) })
    }

    /**
     * 拆掉所有共享单元。调用点必须紧跟在播放器 release 之后（那块 Surface 就是解码器的输出端），
     * 也在旧视图移除前后都无所谓 —— removeView 会把画布的 SurfaceTexture 拆掉，GL 线程随之不再画，
     * 但线程与 EGL 得在这里收干净，否则每次重建漏一条线程。
     */
    private fun destroyShareGroups() {
        if (shareGroups.isEmpty()) return
        for ((_, grp) in shareGroups.toList()) {
            grp.canvas.shutdown()
            flSurface?.removeView(grp.container)
            for (winId in grp.members) winShareGroup.remove(winId)
        }
        shareGroups.clear()
    }

    /**
     * Arena 对标：窗口内容变换 = 输入截取（InputRect）+ 框内旋转 + 镜像，窗口 x/y/w/h 不变。
     * 截取只能落在真正的 TextureView 上（视频/HDMI 窗口外层还有容器 FrameLayout），故递归查找；
     * 无 TextureView 的内容（纯色占位 View）退化为 View 属性做旋转/镜像。
     * HDMI 采集窗不走自动裁边：整帧按窗口矩形拉伸（不保比例），与素材窗同一语义。
     * 每次调用都完整重设变换（含恒等情况），保证编辑器把截取/旋转改回默认时画面能复位。
     * @return true = 已应用；false = 目标尺寸还是 0（尚未布局），调用方需要重试（见 [applyTransformWhenReady]）
     */
    private fun applyWindowTransform(view: View, w: JSONObject): Boolean {
        val rot = w.optInt("rotation", 0)
        val flip = w.optString("flip", "none")
        val crop = cropRect(w)
        val tvs = if (view is TextureView) listOf(view) else findTextureViews(view)
        if (tvs.isNotEmpty()) {
            // 一个窗口框里可能套着多层视图（画面 + 亮度遮罩），变换只套在 TextureView 上；
            // 它们都是 MATCH_PARENT，尺寸取第一路即可。
            val vw = tvs[0].width.toFloat()
            val vh = tvs[0].height.toFloat()
            if (vw <= 0f || vh <= 0f) return false
            val cx = vw / 2f
            val cy = vh / 2f
            val m = Matrix()
            // 取景框左上角落到原点，再放大到铺满窗口框（允许变形，与 Arena InputRect→OutputRect 同语义）
            m.setTranslate(-crop[0] * vw, -crop[1] * vh)
            m.postScale(1f / crop[2], 1f / crop[3])
            if (rot == 90 || rot == 270) {
                m.postScale(vh / vw, vw / vh, cx, cy)
            }
            m.postRotate(rot.toFloat(), cx, cy)
            if (flip == "h") m.postScale(-1f, 1f, cx, cy)
            if (flip == "v") m.postScale(1f, -1f, cx, cy)
            val transform = if (m.isIdentity) null else m
            tvs.forEach { it.setTransform(transform) }
            return true
        }
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (vw <= 0f || vh <= 0f) return false
        var sx = 1f
        var sy = 1f
        if (rot == 90 || rot == 270) {
            sx = vh / vw
            sy = vw / vh
        }
        if (flip == "h") sx = -sx
        if (flip == "v") sy = -sy
        view.rotation = rot.toFloat()
        view.scaleX = sx
        view.scaleY = sy
        return true
    }

    /**
     * 等目标视图真正拿到尺寸后再应用截取/旋转/镜像，应用成功即摘掉监听。
     * 此前只有一句 container.post { applyWindowTransform(...) }：post 只排到下一轮消息，
     * 冷启动时那一步布局/SurfaceTexture 常常还没跑完，宽度为 0 直接 return，
     * 于是取景裁切、90°/270° 预缩放、镜像整条丢掉且不再补 —— 表现就是"重启后画面没裁"。
     * 监听挂在真正承载画面的 TextureView 上（找不到时退回容器本身），首次非零布局即生效。
     */
    private fun applyTransformWhenReady(view: View, w: JSONObject) {
        if (applyWindowTransform(view, w)) return
        val watched: View = findTextureViews(view).firstOrNull() ?: view
        watched.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int,
                                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                if (applyWindowTransform(v, w)) v.removeOnLayoutChangeListener(this)
            }
        })
    }

    /** 递归取窗口内全部 TextureView（画面层可能嵌在容器里，变换要一起套到每一块） */
    private fun findTextureViews(v: View): List<TextureView> {
        if (v is TextureView) return listOf(v)
        if (v is ViewGroup) return (0 until v.childCount).flatMap { findTextureViews(v.getChildAt(it)) }
        return emptyList()
    }

    /**
     * 落屏换算的分母 = 这块屏的真实像素，不是 resources.displayMetrics。
     * 后者在带系统栏的盒子上报的是"扣掉栏之后"的高度（CX3588 实测 7680×2160 只给 2016），
     * 用它当分母会把整幅画面纵向压到 93.3%，越靠下的窗口越偏。窗口本身是全屏沉浸的，画布就是物理屏。
     */
    @Suppress("DEPRECATION")
    private fun screenPx(): IntArray {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            intArrayOf(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            intArrayOf(dm.widthPixels, dm.heightPixels)
        }
    }

    /**
     * 窗口几何 [x, y, width, height]（设备 px）：编辑器画布与这里共用同一把尺（房间 config.output，
     * 没设时 [DESIGN_W]×[DESIGN_H] 设计稿），坐标与尺寸按 屏宽/基准宽、屏高/基准高 两个轴向分别换算 ——
     * 基准取这台屏的真实像素时两个分母相等，画布上的正方形落屏还是正方形；取设计稿铺到 20:9 的屏上则拉成 1.25:1。
     * 上下限不是为了好看，是为了兜住手写 config 的极端值：width=999999 会真去申请一百万像素的
     * TextureView，平板上是会 OOM 的。界外一律按基准的倍数给（-1×基准 到 2×基准），换一把尺不用改字面量。
     * 全量创建与 update_windows 轻量路径共用本函数，两边不会再夹出两套口径。
     */
    private fun windowGeom(w: JSONObject): IntArray {
        val screen = screenPx()
        val sx = screen[0].toFloat() / designW
        val sy = screen[1].toFloat() / designH
        return intArrayOf(
            Math.round(w.optInt("x", 0).coerceIn(-designW, designW * 2) * sx),
            Math.round(w.optInt("y", 0).coerceIn(-designH, designH * 2) * sy),
            Math.round(w.optInt("width", designW).coerceIn(64, designW * 2) * sx),
            Math.round(w.optInt("height", designH).coerceIn(64, designH * 2) * sy)
        )
    }

    /** 归一化取景区 [x, y, w, h]：缺省/非法一律回退全画面，宽高下限 5% 且不会越出画面（避免零除） */
    private fun cropRect(w: JSONObject): FloatArray {
        val c = w.optJSONObject("crop") ?: return floatArrayOf(0f, 0f, 1f, 1f)
        fun num(name: String, def: Double): Float {
            val v = c.optDouble(name, def)
            return if (v.isNaN()) def.toFloat() else v.toFloat()
        }
        val x = num("x", 0.0).coerceIn(0f, 0.95f)
        val y = num("y", 0.0).coerceIn(0f, 0.95f)
        var wd = num("w", 1.0).coerceIn(0.05f, 1f)
        var ht = num("h", 1.0).coerceIn(0.05f, 1f)
        if (x + wd > 1f) wd = (1f - x).coerceAtLeast(0.05f)
        if (y + ht > 1f) ht = (1f - y).coerceAtLeast(0.05f)
        return floatArrayOf(x, y, wd, ht)
    }

    /** 测试背景窗口：固定图卡拉伸铺满（随窗口矩形变形），图卡读不到才退回 content.color 纯色 */
    private fun createColorView(w: JSONObject, content: JSONObject): View {
        testCardBitmap()?.let { bmp ->
            return ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.BLACK)
                setImageBitmap(bmp)
            }
        }
        return TextView(this).apply {
            setBackgroundColor(runCatching { Color.parseColor(content.optString("color", "#000000")) }.getOrDefault(Color.BLACK))
            text = w.optString("name", "")
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
    }

    /** 彩条卡只解一次：素材窗/存量测试背景窗共享同一张位图（点歌窗走 hdmiCardBitmap 的网格卡） */
    private var testCardBmp: Bitmap? = null
    private var testCardTried = false

    private fun testCardBitmap(): Bitmap? {
        testCardBmp?.let { return it }
        if (testCardTried) return null   // 读失败过就不再每建一扇窗重试一遍
        testCardTried = true
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = TEST_CARD_SAMPLE }
            assets.open(TEST_CARD_ASSET).use { BitmapFactory.decodeStream(it, null, opts) }
                ?.also {
                    testCardBmp = it
                    logToFile("测试背景：图卡 ${it.width}×${it.height} 就绪", "INFO", "WINDOW", "TESTCARD")
                }
                ?: run {
                    logToFile("测试背景：图卡解码失败，退回纯色", "WARN", "SYNC", "WINDOW")
                    null
                }
        } catch (e: Exception) {
            logToFile("测试背景：图卡读取失败 ${e.message}，退回纯色", "WARN", "SYNC", "WINDOW")
            null
        }
    }

    /** 点歌窗网格卡：与彩条卡同一套懒解码口径；网格卡读不到就垫彩条卡，仍表明"盖住了" */
    private var hdmiCardBmp: Bitmap? = null
    private var hdmiCardTried = false

    private fun hdmiCardBitmap(): Bitmap? {
        hdmiCardBmp?.let { return it }
        if (hdmiCardTried) return testCardBitmap()
        hdmiCardTried = true
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = TEST_CARD_SAMPLE }
            assets.open(HDMI_CARD_ASSET).use { BitmapFactory.decodeStream(it, null, opts) }
                ?.also {
                    hdmiCardBmp = it
                    logToFile("测试背景：点歌窗网格卡 ${it.width}×${it.height} 就绪", "INFO", "WINDOW", "TESTCARD")
                }
                ?: run {
                    logToFile("测试背景：点歌窗网格卡解码失败，退回彩条卡", "WARN", "SYNC", "WINDOW")
                    testCardBitmap()
                }
        } catch (e: Exception) {
            logToFile("测试背景：点歌窗网格卡读取失败 ${e.message}，退回彩条卡", "WARN", "SYNC", "WINDOW")
            testCardBitmap()
        }
    }

    /**
     * HDMI 输入窗口：一块 TextureView 直接吃 [HdmiCapture] 的相机流（取景/旋转/镜像与视频窗同一套矩阵），
     * 外加一层占位文字 —— 没权限、没信号、正在接入时屏上看得见原因，而不是一片黑。
     * 台架 CX3588 的 HDMI IN 在固件里被相机 HAL 报成外接相机，这一路就是走它，不需要厂商私有库。
     */
    private fun createHdmiView(winId: String, w: JSONObject, content: JSONObject): View {
        val inputIdx = content.optInt("inputIndex", 0)
        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val textureView = TextureView(this)
        container.addView(textureView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val label = TextView(this).apply {
            text = "HDMI ${inputIdx + 1}"
            setTextColor(Color.WHITE)
            textSize = 36f
            gravity = Gravity.CENTER
        }
        container.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        hdmiLabels[winId] = label

        // 这块窗自己的 Surface 引用：destroy 时带着它去 detach，采集侧据此分辨"这是不是当前挂的那块"
        var attached: Surface? = null
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                // 隐藏再显示的同一块窗会重建 Surface：destroy 时摘掉的标签登记要补回来，
                // 否则 refreshHdmiLabels 看不到它，占位字会一直压在画面上不更新
                hdmiLabels[winId] = label
                // 这块 Surface 是 GL 的渲染目标，缓冲尺寸跟视图走；采集源尺寸由 HdmiGl 自己那块 SurfaceTexture 管
                if (width > 0 && height > 0) st.setDefaultBufferSize(width, height)
                val surf = Surface(st)
                attached = surf
                hdmi().attach(winId, surf)
                ensureHdmiPermission()
                refreshHdmiLabels()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                // 编辑器里拉伸窗口：渲染目标缓冲必须跟着变，否则 GL 只画出旧尺寸一块
                if (width > 0 && height > 0) st.setDefaultBufferSize(width, height)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                attached?.let { hdmiCapture?.detach(winId, it) }
                attached = null
                if (hdmiLabels[winId] === label) hdmiLabels.remove(winId)
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        return container
    }

    private fun hdmi(): HdmiCapture = hdmiCapture ?: HdmiCapture(this) { msg, level ->
        logToFile(msg, level, "WINDOW", "HDMI")
    }.also { cap ->
        cap.onStreamState = { refreshHdmiLabels() }
        cap.onNeedPermission = { runOnUiThread { ensureHdmiPermission() } }
        hdmiCapture = cap
    }

    /**
     * 相机权限：门店镜像有 root，先 `su -c pm grant` 静默授权（HOME 应用的系统弹窗会盖住整屏，
     * 无人值守现场也没人点）；没有 root 才退到系统弹窗。被拒就保持黑屏+提示文字，不再反复弹。
     */
    private fun ensureHdmiPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            hdmi().retryNow()
            return
        }
        if (hdmiPermAsked) return
        hdmiPermAsked = true
        Thread {
            val grantedByRoot = execAsRoot("pm grant $packageName android.permission.CAMERA", 4000).first == 0 &&
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            runOnUiThread {
                if (grantedByRoot) {
                    logToFile("HDMI 输入：已借 root 静默授予相机权限", "INFO", "WINDOW", "HDMI")
                    hdmi().retryNow()
                } else {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_HDMI_CAMERA)
                }
            }
        }.start()
    }

    /** 占位文字随「权限 / 信号 / 出流」三态刷新；出流且有信号时整块藏掉，免得压在画面上 */
    private fun refreshHdmiLabels() {
        if (hdmiLabels.isEmpty()) return
        val granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val cap = hdmiCapture
        val streaming = cap?.streaming == true
        val signal = cap?.signalPresent() ?: false
        for (label in hdmiLabels.values) {
            label.visibility = if (streaming && signal) View.GONE else View.VISIBLE
            label.text = when {
                !granted -> "HDMI 输入待授权（相机权限）"
                !signal -> "HDMI 无信号（检查输入源与线）"
                else -> "HDMI 接入中…"
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_HDMI_CAMERA) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        logToFile("HDMI 输入：相机权限" + if (granted) "已授予" else "被拒绝，HDMI 窗口保持黑屏（可在系统设置里补授）",
            if (granted) "INFO" else "WARN", "WINDOW", "HDMI")
        if (granted) hdmi().retryNow() else refreshHdmiLabels()
    }

    /** 只记一次：每个窗口都建播放器，逐窗刷这条日志会把 xvj.log 冲花 */
    @Volatile private var audioDisabledLogged = false

    /**
     * 本机所有 ExoPlayer 的统一出口：不装音频渲染器 + 收紧缓冲。
     *
     * 【为什么掐掉音频】#348 装机体硬挂的那条链子（ANR trace + audioserver native 栈取证）：
     * 资源吃紧 → 某只素材的 AAC 音轨报 ERROR_CODE_DECODING_FAILED → 换片凝滞 15s、HDMI 出帧塌到 0.6fps
     * → audioserver 主线程死在 TimeCheck::TimeCheckThread::startMonitoring 的互斥上
     * → Media3 建播放器时主线程走进 AudioPortEventHandler.init → registerClient 等 binder
     * → 主线程彻底卡死、心跳停 → 保活进程拉起新实例，依旧死在 WINDOW CREATE → 整机只能断电。
     * VJ 盒子本身不出声（声音走点歌机/外接 PA），音轨对出画面没有任何贡献，却带来两条硬依赖：
     * 解码 AAC（上面那类错误）和 AudioManager 的 binder 调用（audioserver 一僵，主线程跟着僵）。
     * 掐掉后这两类风险直接归零，播放器也不再因音轨解不开而整路停摆。
     */
    private fun buildExoPlayer(): ExoPlayer {
        if (!audioDisabledLogged) {
            audioDisabledLogged = true
            logToFile("播放器：已禁用音频渲染器（本机不出声，规避 audioserver 死锁拖死主线程）", "INFO", "PLAYBACK", "AUDIO")
        }
        return ExoPlayer.Builder(this)
            .setRenderersFactory(noAudioRenderersFactory())
            .setLoadControl(localBufferLoadControl())
            .build()
    }

    /**
     * 只留视频渲染器：media3 的 buildAudioSink 返回 null 时就不会再走 buildAudioRenderers，
     * 这是官方留的口子（见 DefaultRenderersFactory 注释），比覆盖 buildAudioRenderers 少建一个 AudioSink。
     */
    private fun noAudioRenderersFactory(): DefaultRenderersFactory =
        object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? = null
        }

    /**
     * 每路播放器自己的缓冲预算。返回新实例，不能共享：DefaultLoadControl 在 onInitialized 里
     * 认死一个播放器，第二个来初始化会抛 IllegalStateException。
     *
     * 【为什么非改不可】media3 的默认值按网络流设计 —— 缓冲到 50MB 或 50 秒才罢手，而"要攒多久才肯
     * 开播"的门槛是 2.5 秒（首次）/ 5 秒（换片后续播）。素材全在本地文件里，读盘没有网络抖动：
     * 50MB 是纯白占，6 扇 1080p 窗口 = 300MB，顶穿这台手机 256MB 的 Java 堆上限，OOM 崩在解码取帧线程
     * （实测 MediaCodecRenderer.drainOutputBuffer），现场表现就是"点开应用立刻闪退、只回桌面"；
     * 那 5 秒门槛则是"切视频有轻微延迟"的其中一段。
     *
     * 【为什么按毫秒收口、不按字节】setTargetBufferBytes 看着更直观，但它会同时接管停灌条件和起播条件：
     * 起播要等字节灌满，而停灌可能先按时长满足 —— 两个阈值不一致时永远等不齐，画面直接死住。
     * 时长口径下 2500ms 就是硬闸（20Mbps 素材约 6MB/路，6 路 36MB），起播只等 600ms。
     */
    private fun localBufferLoadControl(): DefaultLoadControl {
        val avail = availableMemMb()
        // 内存紧时收紧缓冲（#228）：4K 素材一帧 NV12 就是 24MB，按 2.5s 攒满在 100Mbps 码率下
        // 单路就吃掉 30MB+；解码器输出池一开池水位就直接见底，lmkd 随即开杀。
        // 本地文件读盘没有网络抖动，短缓冲最多换来偶尔一次续播，远比被杀掉重启划算。
        val (minMs, maxMs) = when {
            avail in 1 until 400 -> 600 to 1200
            avail in 400 until 700 -> 800 to 1800
            else -> 1000 to 2500
        }
        if (avail in 1 until 700) {
            logToFile("播放缓冲按内存收紧：可用 ${avail}MB → ${minMs}/${maxMs}ms（默认 1000/2500）",
                "WARN", "PLAYBACK", "BUFFER")
        }
        return DefaultLoadControl.Builder()
            // 1000/2500：攒够 1 秒才算"不缺粮"，最多攒到 2.5 秒（默认是 50 秒，内存根本装不下）
            // 200/250：开播与换片后续播的等待门槛（默认 2500/5000 —— 现场那半秒多的凝滞就是它）。
            // 墙板切格实测（#329，2026-11-17）：4K 换格时 READY 的缓冲恰好 24% = 600ms，卡的就是旧门槛；
            // 本地文件不怕缺粮，降到 250ms 换开画速度，若 4K 起播抖再回调。
            .setBufferDurationsMs(minMs, maxMs, 200, 250)
            .build()
    }

    /** 整机可用内存（MB）：取不到返回 0（按"不知道自己穷"处理，走默认口径） */
    private fun availableMemMb(): Int = try {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        (mi.availMem shr 20).toInt()
    } catch (e: Exception) {
        0
    }

    /**
     * 低内存分批起播的间隔（毫秒），0 = 不分批（正常水位）。
     * 见 applySceneConfigs 里的调用：几路解码器同时起，冷缓存尖峰挤在一帧上最容易触发 lmkd。
     */
    private fun startPacingMs(): Int = when (availableMemMb()) {
        in 1 until 400 -> 600
        in 400 until 700 -> 300
        else -> 0
    }

    /** 视频播放窗口（每个窗口独立 ExoPlayer，TextureView 承载以支持旋转/镜像矩阵变换）*/
    private fun createVideoWindowView(winId: String, w: JSONObject, content: JSONObject): View {
        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // TextureView 而不是 SurfaceView：窗口要套旋转/镜像矩阵（见 applyTransformWhenReady）
        val textureView = TextureView(this)
        container.addView(textureView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val player = buildExoPlayer().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
            setVideoTextureView(textureView)
        }
        armWindowLogger(winId, player)
        windowPlayers[winId] = player
        // 播放设置要等 playFolderInWindow 才知道播哪一格，这里先落默认值（顺序循环）
        videoSurfaces[winId] = VideoSurface(container, textureView, w, PlaySettings())

        return container
    }

    /**
     * 给一路播放器装首帧 / 换片凝滞那本账，并把"看片断流"挂上。
     * 单窗播放器与共享解码组那一路共用同一个入口：两处各写一份时，同格 6 扇窗只剩一路解码反而没人记账。
     */
    private fun armWindowLogger(winId: String, player: ExoPlayer) {
        // 首帧耗时 = 窗口创建 → 第一次 STATE_READY。现场那句"要点一下才出来"要的就是这个数：
        // 高分辨率素材的解码器起流水线 + 读满缓冲池全在这段，以前它是完全没数据的黑盒。
        // 只报第一次 READY（playlist 循环切歌不重复刷）；播放器随窗口重建，计数天然归零。
        val tCreated = android.os.SystemClock.elapsedRealtime()
        var readyLogged = false
        // 换片凝滞 = 进 STATE_BUFFERING 到下一次 STATE_READY 的墙钟差。本地文件里这段时间只可能是
        // "打开下一只 + 解码器重配置 + 攒够 600ms 续播门槛"，跟网络无关。以前这段没有任何数，
        // 只有首次首帧有 —— 所以"切视频慢"到底是慢在攒缓冲还是慢在重开解码器，改之前先分清。
        var bufferingSince = 0L
        // 解码级错误的自愈账：实测一条 AAC 音轨解不开（ERROR_CODE_DECODING_FAILED）会让这一路停在原地、
        // 屏上定格到下次下发配置为止。本地清单里换下一只只要几百毫秒，不该整格陪着冻住。
        // 连错 3 次就收手：坏的多半不是"某一只"而是整格素材或解码器本身，再跳只是刷日志空转。
        var errorStreak = 0
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // 播放器级错误（文件损坏/编码不支持/源丢失）此前只进 logcat，后台完全黑盒
                logToFile("窗口 $winId 播放器错误: ${error.errorCodeName} ${error.message ?: ""}", "ERROR", "PLAYBACK", "ERROR")
                mqttHandler.post {
                    // 全量重建会换掉这路播放器，迟到的监听别再碰旧实例
                    if (windowPlayers[winId] !== player) return@post
                    if (errorStreak >= 3) {
                        logToFile("窗口 $winId 连续 $errorStreak 次播放器错误，停止自动跳片", "ERROR", "PLAYBACK", "ERROR")
                        return@post
                    }
                    errorStreak++
                    // REPEAT_MODE_ALL 下清单非空时 hasNext 恒为真，单只也回到自己重试，同样受连错上限约束
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                    player.prepare()
                    logToFile("窗口 $winId 播放器错误，跳到下一只重试（第 $errorStreak 次）", "WARN", "PLAYBACK", "RECOVER")
                }
            }
            override fun onEvents(p: Player, events: Player.Events) {
                val now = android.os.SystemClock.elapsedRealtime()
                when (p.playbackState) {
                    Player.STATE_BUFFERING -> if (bufferingSince == 0L) bufferingSince = now
                    Player.STATE_READY -> {
                        val since = bufferingSince
                        bufferingSince = 0L
                        errorStreak = 0
                        if (readyLogged) {
                            // 首帧之后才有"换片"可言；不到 100ms 的凝滞人眼看不出来，别刷日志
                            if (since > 0 && now - since >= 100) {
                                val vs = (p as? ExoPlayer)?.videoSize
                                logToFile("窗口 $winId 换片凝滞 ${now - since}ms" +
                                    (if (vs != null) "，输入 ${vs.width}x${vs.height}" else "") +
                                    "，已缓冲 ${p.bufferedPercentage}%", "INFO", "PLAYBACK", "SWITCH")
                            }
                        } else {
                            readyLogged = true
                            val vs = (p as? ExoPlayer)?.videoSize
                            // 只报分辨率，不报帧率：本工程的 media3 1.2.1 里 VideoSize 还没有 frameRate
                            // 字段（run294 编译就是这么断的）。要帧率得升 media3 或改走 currentTracks 的
                            // Format.frameRate，为一条日志不值当，先看分辨率——4K/1080p 才是 CPU 的主要变量。
                            logToFile("窗口 $winId 首帧就绪: 用时 ${now - tCreated}ms" +
                                (if (vs != null) "，输入 ${vs.width}x${vs.height}" else "") +
                                "，已缓冲 ${p.bufferedPercentage}%", "INFO", "PLAYBACK", "READY")
                        }
                    }
                    // IDLE/ENDED：这一轮的等待作废，别再拿旧时间戳去减
                    else -> bufferingSince = 0L
                }
            }
        })
    }

    /**
     * 在指定窗口内播放指定文件夹的视频
     * @param winId    窗口 ID
     * @param folderId 文件夹 ID（"A01" → scenea/01，纯编号 → 根目录；解析统一走 materialDirOf）
     * @param scenes   查播放设置用的那份 scenes。全量重建时必须显式传进来 —— applySceneConfigs 是
     *                 先起播再赋值 appliedScenes 的，用默认值会查到上一份配置的设置。
     */
    private fun playFolderInWindow(winId: String, folderId: String, scenes: JSONObject? = appliedScenes) {
        logToFile("开始播放窗口: winId=$winId, folderId=$folderId", "INFO", "PLAYBACK", "START")
        val grp = winShareGroup[winId]
        if (grp != null && grp.leaderId != winId) {
            // 共享组的跟随窗：画面跟着基准那一路解码走，这里只把自己的账记上
            // （试播认接管范围、墙板认成员都按 folderKey 找人，不认播放器）
            videoSurfaces[winId]?.let {
                it.settings = playSettingsOf(scenes, folderId)
                it.folderKey = grp.folderKey
            }
            return
        }
        // 墙板整组换格（applyPanelSource 直接调这里，不经过原地闸门）：组是按格子键索引的，
        // 组名不跟着换，下一次配置落地就会拿旧名跟新解析比，误判成结构变化 → 整屏重建（白漏一份 dma-buf）。
        // 目标格已被另一组占着则维持原名（换名等于并组），留给下次配置落地按新分组重建。
        if (grp != null && grp.folderKey != folderId && !shareGroups.containsKey(folderId)) {
            val from = grp.folderKey
            shareGroups.remove(from)
            grp.folderKey = folderId
            shareGroups[folderId] = grp
            logToFile("共享解码组 ${grp.members.size} 扇窗整组换格：$from → $folderId", "INFO", "WINDOW", "SHARE")
        }
        val player = windowPlayers[winId] ?: run {
            Log.w(TAG, "playFolderInWindow: 找不到窗口 $winId 的播放器")
            logToFile("找不到窗口 $winId 的播放器", "ERROR", "PLAYBACK", "START")
            return
        }
        // 目录解析与同步/删除用同一个入口：三处各自实现时口径已经漂过一次（这里曾把
        // 任何以 a/A/b/B 开头的 id 都当带前缀处理），非法 id 一律不播并留日志
        val physicalDir = materialDirOf(folderId)
        if (physicalDir == null) {
            Log.w(TAG, "playFolderInWindow: folderId 非法 $folderId")
            logToFile("窗口 $winId 播放失败: folderId 非法 $folderId", "ERROR", "PLAYBACK", "ERROR")
            return
        }
        if (!physicalDir.exists()) {
            Log.w(TAG, "playFolderInWindow: 物理文件夹不存在 $physicalDir")
            logToFile("窗口 $winId 播放失败: 物理文件夹不存在 ${physicalDir.name}", "ERROR", "PLAYBACK", "ERROR")
            return
        }
        val videos = physicalDir.listFiles()
            ?.filter { it.extension.lowercase() in VIDEO_EXTS }
            ?.sortedBy { it.name } ?: return
        if (videos.isEmpty()) {
            Log.w(TAG, "playFolderInWindow: 物理文件夹 $physicalDir 内无视频")
            logToFile("窗口 $winId 播放失败: 文件夹 ${physicalDir.name} 内无视频文件", "WARN", "PLAYBACK", "SKIP")
            return
        }
        val ps = playSettingsOf(scenes, folderId)
        videoSurfaces[winId]?.settings = ps
        videoSurfaces[winId]?.folderKey = folderId
        val listed = videos.map { MediaItem.fromUri(Uri.fromFile(it)) }
        // 不用 ExoPlayer 自带的 shuffleModeEnabled，自己洗一次并按格缓存（folderPlaylists）：
        // 洗牌结果属于"这一格"而不属于"这一路播放器"，窗口重建、切幕再切回来拿到的都是同一份次序。
        val cached = folderPlaylists[folderId]
        val playlist = if (cached != null && sameItems(cached, listed)) cached
            else (if (ps.shuffle && listed.size > 1) listed.shuffled() else listed)
                .also { folderPlaylists[folderId] = it }
        // 共享组的跟随窗没有自己的播放器，这一格的账只能由基准这一路代记：
        // 播放设置与 folderKey 都得到位，否则试播认接管范围、墙板认成员都会漏掉它们
        winShareGroup[winId]?.let { grp ->
            for (mid in grp.members) if (mid != winId) videoSurfaces[mid]?.let {
                it.settings = ps
                it.folderKey = folderId
            }
        }
        // 这一路已经在播同一格（清单逐个对得上）→ 从当前位置接着走，不回第 1 只重头播；
        // 换到别的格时手上那份清单对不上，照旧从头起播
        val clock = groupClock(folderId)?.takeIf { sameItems(it.items, playlist) }
        player.volume = 1f
        if (clock != null) player.setMediaItems(playlist, clock.index.coerceIn(0, playlist.size - 1), clock.positionMs)
        else player.setMediaItems(playlist)
        player.prepare()
        Log.d(TAG, "窗口 $winId 开始播放 $folderId -> ${physicalDir.absolutePath} (${videos.size}个视频)")
        logToFile("窗口 $winId 开始播放 $folderId (${videos.size}个视频，${if (ps.shuffle) "随机" else "顺序"}循环" +
            (if (clock != null) "，沿用本格位置 第${clock.index + 1}/${playlist.size}只 @${clock.positionMs}ms"
             else "，从头起播") + ")", "INFO", "PLAYBACK", "START")
    }

    // ================= 同一格内容的窗口共用一份清单、一把位置基准 =================
    // 用户定调的硬约束：任何情况下所有窗口的播放画面严格同步。
    // 2026-11-13：同格 ≥2 扇已经并成一路解码（任务 #175，见 createShareGroup），
    // "多路播放器互相对齐"那一整层（100ms 巡检 / 减速贴齐 / 摁住等待 / 硬对齐 / 清单指纹 / 熔断，
    // 即 #155 #156 #162 那条链）随之删除 —— 散差按构造就是 0，校正没有对象，巡检只是白烧主线程。
    // 这里只剩两件事要收口：洗牌顺序（一份清单只洗一次）与起播位置（groupClock 跟随这一格已有的那一路）；
    // 试播那条见 startGroupPreview。

    /** 一格的轮播位置基准：第几只 + 第几毫秒；items 是那一格的整份清单（按它判这份基准还作不作数） */
    private class RotationClock(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long
    )

    /** 一只片子的落盘 URI（localConfiguration 在 1.2.1 是可空公开字段，离线/占位 item 取不到就当空串） */
    private fun itemUri(item: MediaItem): String = item.localConfiguration?.uri?.toString() ?: ""

    /** 两份清单算不算同一份内容：MediaItem 的等值比较不可靠（还带元数据、裁剪），只逐个比落盘 URI */
    private fun sameItems(a: List<MediaItem>, b: List<MediaItem>): Boolean =
        a.size == b.size && a.indices.all { itemUri(a[it]) == itemUri(b[it]) }

    /**
     * 这一格此刻的正片位置。基准窗 = 组内 ID 最小、且真有播放器在跑的那扇 —— 固定次序，
     * 不然每次取基准换一扇，基准自己就先漂了。
     * 以前还要排除"正要起播的那扇自己"（多路播放器时它是全新的，拿它当基准会把全组拽回开头）；
     * #175 后一格只有一路播放器，正片位置就长在那一路身上，排除它等于每次都从头播。
     * 正在试播的那格排除（它播的不是正片）。
     */
    private fun groupClock(folderKey: String): RotationClock? {
        if (folderKey.isEmpty() || previews.containsKey(folderKey)) return null
        for ((winId, surf) in videoSurfaces.entries.sortedBy { it.key }) {
            if (surf.folderKey != folderKey) continue
            val p = windowPlayers[winId] ?: continue
            if (p.mediaItemCount == 0) continue
            return RotationClock((0 until p.mediaItemCount).map { p.getMediaItemAt(it) },
                p.currentMediaItemIndex, p.currentPosition)
        }
        return null
    }

    // 【A-12】 文件夹级播放设置：随机/顺序循环（交接一律硬切）
    // @tag: playSettingsOf 播放设置 随机循环 顺序循环
    //
    // 2026-11-12 用户定调：「先删除叠化功能和叠化开关，先专注于普通切换」—— 叠化整条链路
    // （第二路预卷播放器 + alpha 淡入 + 音量交叉 + 整组淡入闸门）连同 tickPlaySettings 一起删除，
    // 需要特效另立项再做（同帧精度那条见任务 #157 的共享解码 + GL 合成）。
    // 现在素材轮播的每一次交接都是硬切：切墙板、切幕、试播接管/恢复同样硬切。
    //
    // 留下来的那件事：顺序与随机 —— 清单按格缓存（folderPlaylists），随机只洗一次，
    // 同一格不管重建几次拿到的都是同一份次序。
    //
    // 2026-11-18 新增 dmx 开关（文件夹是否接受 DMX512 控制，默认关）：副板链路见【A-14d】，
    // 通道 N 的值变动即"按了一次"，落到 applyPanelSource 与 485 同一套切格语义。
    // 闸门按幕判：A 幕开了、B 幕没开的格子，只有 A 幕的跟随窗会动。
    //
    // logo 开关同理（默认关）：这一格在播时是否允许出房间 LOGO，与 logo.windows 名单两道闸同时为真才出（见【A-17】）。

    /** 一格的播放设置；全默认 = 顺序循环 + 点歌屏关 + DMX512 关 + LOGO 关（与云端 playSettingsValue 的"默认不落键"同口径） */
    private class PlaySettings(
        val shuffle: Boolean = false,
        /** true = 这一格在播时显示点歌屏（HDMI 采集）窗口；见 applyHdmiVisibility */
        val hdmi: Boolean = false,
        /** 点歌屏模式 0=关 1~3=模式N（点歌窗有三套配置，选哪套就只显示 content.mode 相同的那套）；见 applyHdmiVisibility */
        val hdmiMode: Int = 0,
        /** true = 这一格接受 DMX512 控制（见 applyPanelSource 的 dmxAllowedFor 闸） */
        val dmx: Boolean = false,
        /** true = 这一格在播时允许出现房间 LOGO（与 logo.windows 名单同时为真才出，见 buildLogoMembers） */
        val logo: Boolean = false
    )

    /**
     * 一扇视频窗口的画面现场。播放器实例不在这儿（唯一真值是 windowPlayers），只放试播/墙板要摸的东西。
     * folderKey = 这扇窗此刻在播哪一格（playFolderInWindow 落的），试播认接管范围、墙板认成员都按它找人。
     * texture = 承载画面的那块视图：单窗是自己的 TextureView，共享解码组的成员是整组共用的 ShareCanvasView
     * （它是 TextureView 的子类，但变换长在 GL 里，不再对它 setTransform，见 refreshShareGroup）。
     */
    private class VideoSurface(
        val container: FrameLayout,
        val texture: TextureView,
        var winJson: JSONObject,
        var settings: PlaySettings,
        var folderKey: String = ""
    )

    /**
     * 查这一格的播放设置（云端 scenes[X].play_settings）。
     * folderId 是物理目录 id（"A01"，见 resolveWindowFolder）；表键服务端 buildPrefixedScenes 已加幕前缀，
     * 老载荷可能是裸 "01"，两种都认。
     * A、B 是两套独立素材目录，所以带前缀时只查本幕，不跨幕兜底；裸 folderId 的老配置才两幕都试。
     */
    private fun playSettingsOf(scenes: JSONObject?, folderId: String): PlaySettings {
        if (scenes == null || folderId.isEmpty()) return PlaySettings()
        val prefix = folderId.substring(0, 1).uppercase()
        val prefixed = prefix == "A" || prefix == "B"
        val sceneKeys = if (prefixed) listOf(prefix) else listOf("A", "B")
        val bare = if (prefixed) folderId.substring(1) else folderId
        for (k in sceneKeys) {
            val tbl = scenes.optJSONObject(k)?.optJSONObject("play_settings") ?: continue
            val cell = tbl.optJSONObject(folderId) ?: tbl.optJSONObject(bare) ?: continue
            val hdmiOn = cell.optBoolean("hdmi", false)
            return PlaySettings(
                shuffle = cell.optString("order", "seq") == "shuffle",
                hdmi = hdmiOn,
                // 老配置只有 hdmi:true，按模式1 处理（云端模式1 不落 hdmi_mode，同一口径）
                hdmiMode = if (!hdmiOn) 0 else cell.optInt("hdmi_mode", 1).let { if (it in 1..3) it else 1 },
                dmx = cell.optBoolean("dmx", false),
                logo = cell.optBoolean("logo", false)
            )
        }
        return PlaySettings()
    }

    /** 两幕播放设置的规范化串：update_windows 轻路径拿它判"设置变没变"（见 appliedPlaySettingsJson） */
    private fun playSettingsJson(scenes: JSONObject): String =
        canonicalJson(scenes.optJSONObject("A")?.opt("play_settings")) + "|" +
            canonicalJson(scenes.optJSONObject("B")?.opt("play_settings"))

    // 【A-13】 大屏试播（preview_material / preview_stop）
    // @tag: startGroupPreview 试播 整格同步接管 恢复画面
    // @tag: armPreview 接管这一格 挂片装钩子 previewTarget 目标窗口
    // @tag: endPreview 试播结束 整格回到正片
    // @tag: endPreviewCovering 墙板切格 按基准窗找回这笔试播
    // @tag: abortAllPreviews 配置变更 试播让路

    /**
     * 一格进行中的试播 = 回到试播前那格画面所需的全部现场（账按格子记，见 previews 的声明）。
     * items 直接持有试播前那份 playlist（media3 的 MediaItem 是不可变描述），**不重新列目录**：
     * 试播期间那一格可能正被同步增删文件，重新列会播一份谁也没打算播的清单。
     * listener/watchdog 要能换（拿到真实片长后得重新武装看门狗），所以留成 var。
     */
    private class PreviewState(
        val winId: String,              // 这一格唯一那一路解码：共享组的基准窗，或单窗自己
        var filename: String,
        val items: List<MediaItem>,
        val itemIndex: Int,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val repeatMode: Int
    ) {
        var listener: Player.Listener? = null
        var watchdog: Runnable? = null
        var rearmed = false
    }

    /**
     * 一次试播的入口 = 把**这一格**换成那只素材（同格的几扇窗本来就是同一张画布），播完再换回来。
     * 为什么不是云端算出的那一扇（2026-11-11 用户定调：任何情况下所有窗口的播放画面严格同步）：
     * 只接管一扇，屏上立刻变成"一扇在播别的"；播完它又回到自己被冻住的那个位置，而别的窗
     * 早滚到下一只去了 —— 这一格的轮播顺序从这一刻起永久错开，现场看到的就是"顺序乱了"。
     * 三条规矩，都是这条链路成立的前提：
     *  1. **只换 playlist，不开第二路播放器** —— 这台盒子 4K60 单窗已吃到 99% CPU，再叠一路解码
     *     会把整屏拖花；而同一台 ExoPlayer、同一块画布才继承得到窗口的位置/尺寸/裁剪/亮度，
     *     现场要判断的正是"这只片子放进这扇窗好不好看"，不是片子本身好不好看。
     *  2. **不碰任何配置** —— scenes_json 缓存、windowContentSigs、appliedScenesFp、appliedRev 一律不动。
     *     试播因此不参与 rev 对账（对账也永远看不到它），不会留下一份"屏上没有"的配置。
     *  3. **一定会回去** —— 播完一遍 / 播放器出错 / 看门狗到点 / 收到 preview_stop / 配置变更，
     *     五路都通向收尾，其中配置变更优先级最高：大屏是营业资产，任何一次下发都比试播说了算。
     *     （第六路是"换了一格试播"：上一格当场还回正片。）
     * 只能在主线程调用（碰播放器）。
     */
    private fun startGroupPreview(folderId: String, winIds: List<String>, filename: String, maxMs: Long) {
        if (filename.isEmpty()) {
            logToFile("试播拒绝：filename 为空", "WARN", "PLAYBACK", "PREVIEW")
            return
        }
        if (exited) {
            // 退出时窗口全放了，previewTarget 本来也认不出目标，但那条日志会写成"这一格没有窗口在播"，
            // 现场据此去查配置就白跑一趟 —— 这里先把真话说出来。
            logToFile("试播拒绝：设备已退出到桌面", "WARN", "PLAYBACK", "DESKTOP")
            previewStatusText("试播已拒绝：设备已退出到桌面", STATUS_TIP_MS)
            return
        }
        // 素材同步在飞时不插队：那一轮正在按清单增删这个目录里的文件，而且它收尾必定 apply 一次场景配置，
        // 真播了也马上会被 abortAllPreviews 抹掉 —— 白费一次解码，还白闪一下屏。整格判一次。
        if (synchronized(syncLock) { syncInFlight.isNotEmpty() }) {
            logToFile("试播拒绝：素材同步进行中，稍后再点一次", "WARN", "PLAYBACK", "PREVIEW")
            previewStatusText("试播已拒绝：素材同步中", STATUS_TIP_MS)
            return
        }
        // 同一只还在试播 = 想重看开头：seekTo 不重配解码器；重走一遍 setMediaItems 只是白烧一套缓冲
        previews[folderId]?.takeIf { it.filename == filename }?.let { st ->
            val p = windowPlayers[st.winId]
            if (p != null) {
                p.seekTo(0)
                logToFile("试播重播（同一只，解码器不重配）: $folderId/$filename", "INFO", "PLAYBACK", "PREVIEW")
                previewStatusText("试播重播：$filename")
                return
            }
        }
        // 内存已到冷启动闸门同一把尺（400MB）就别再叠一路解码：这台机器上"勉强播"的代价是整机被 lmkd 连杀
        val availMb = availableMemMb()
        if (availMb in 1 until PREVIEW_MIN_AVAIL_MB) {
            logToFile("试播拒绝：可用内存仅 ${availMb}MB", "WARN", "PLAYBACK", "PREVIEW")
            previewStatusText("试播已拒绝：内存紧张（${availMb}MB）", STATUS_TIP_MS)
            return
        }
        // 定位与同步/删除同一个入口：folderId 带幕前缀（"A01"），filename 就是清单里那个落盘名
        val file = materialDirOf(folderId)?.let { materialFileOf(it, filename) }
        if (file == null || !file.exists()) {
            logToFile("试播失败：本机没有 $folderId/$filename（这只素材还没到位，先去同步）", "WARN", "PLAYBACK", "PREVIEW")
            previewStatusText("试播失败：本机没有这只素材", STATUS_TIP_MS)
            return
        }
        val winId = previewTarget(folderId, winIds)
        if (winId == null) {
            logToFile("试播失败：没有窗口在播 $folderId（配置刚改过？先同步一次）", "WARN", "PLAYBACK", "PREVIEW")
            previewStatusText("试播失败：这一格没有窗口在播", STATUS_TIP_MS)
            return
        }
        // 连点合并（latest wins）：窗口内再点别的只留最后一次，省掉中间那几次注定被打断的解码重配
        val since = SystemClock.elapsedRealtime() - lastPreviewArmAt
        if (since in 0 until PREVIEW_MERGE_MS) {
            pendingPreview?.let { mqttHandler.removeCallbacks(it) }
            val r = Runnable {
                pendingPreview = null
                startGroupPreview(folderId, winIds, filename, maxMs)
            }
            pendingPreview = r
            mqttHandler.postDelayed(r, PREVIEW_MERGE_MS - since)
            logToFile("试播合并：${PREVIEW_MERGE_MS}ms 内再次点击，只留 $folderId/$filename", "INFO", "PLAYBACK", "PREVIEW")
            return
        }
        lastPreviewArmAt = SystemClock.elapsedRealtime()
        // 换一格试播 = 上一格先正常收尾（回到它自己的正片），不是留着试播画面不管
        previews.keys.filter { it != folderId }.forEach { endPreview(it, "让位给新试播") }
        armPreview(folderId, winId, file, filename, maxMs)
        // 共享解码的组里只有基准那一路在 previews 记账，但它一带 N 扇窗：日志与状态文字报真实扇数
        val panels = winShareGroup[winId]?.members?.size ?: 1
        logToFile("试播接管: $folderId/$filename → $panels 扇窗同步", "INFO", "PLAYBACK", "PREVIEW")
        previewStatusText("试播中：$filename · $panels 扇窗同步")
    }

    /**
     * 这一格的试播落在哪一路解码 —— 认本机当前的 folderKey，不认云端点名的 win_ids：
     * 云端按库里那份配置算窗口，设备却可能刚应用过一版新的，那一刻"谁在播这一格"只有设备知道。
     * #175 之后一格只有一路解码（共享组的基准窗，或单窗自己），成员窗没有播放器，
     * 所以按 folderKey 筛出来的那一路就是答案；正因为它多半不是云端点名的第一扇，
     * 试播的账必须按格子记，不然归还时按窗口名找会找不到。
     * folderKey 还没落上（默认窗口刚建、配置刚改过）才退回云端点名的那几扇兜底，
     * 但兜底也要按幕筛过（2026-11-12 用户定调）：一只片子只有一个物理目录、只属于一幕，
     * 另一幕那几扇窗跟这一格没关系，不该被接管 —— 曾经云端把两幕一起点进来，第二幕来源的窗
     * 也跟着播了第一幕的素材。
     */
    private fun previewTarget(folderId: String, winIds: List<String>): String? {
        videoSurfaces.keys.firstOrNull {
            windowPlayers.containsKey(it) && videoSurfaces[it]?.folderKey == folderId
        }?.let { return it }
        val named = winIds.filter { windowPlayers.containsKey(it) }
        val scene = sceneOfFolder(folderId) ?: return named.firstOrNull()   // 无前缀的 id 认不出归属，不拦
        return named.firstOrNull { sceneOfWindow(it) == scene }
    }

    /** "A02" → "A"；"02" 这类无前缀 id 与非法形态 → null（= 幕次未知，调用方按不拦处理） */
    private fun sceneOfFolder(folderId: String): String? =
        if (folderId.length == 3 && folderId[0].isLetter()) folderId[0].uppercaseChar().toString() else null

    /**
     * 一扇窗属于哪一幕：先认本机正在播的那一格（resolveWindowFolder 出来的 folderKey 必带幕前缀），
     * 还没落上（这一格无素材、HDMI 窗、默认窗刚建）再回配置查 content.type —— 那是设备自己的生效配置，
     * 比云端载荷可信。两边都认不出（不在配置里 / HDMI / 配置没到）返回 null，调用方按"不是这一幕"处理。
     */
    private fun sceneOfWindow(winId: String): String? {
        sceneOfFolder(videoSurfaces[winId]?.folderKey ?: "")?.let { return it }
        val scenes = appliedScenes ?: return null
        for (k in listOf("A", "B")) {
            val arr = scenes.optJSONObject(k)?.optJSONArray("windows") ?: continue
            for (i in 0 until arr.length()) {
                val w = arr.optJSONObject(i) ?: continue
                if (w.optString("id") != winId) continue
                val type = w.optJSONObject("content")?.optString("type", "")?.uppercase()
                return if (type == "SCENE_$k") k else null
            }
        }
        return null
    }

    /**
     * 接管这一格：抄下正片现场 → 挂上那只试播片 → 装好两个收尾钩子（首次试播与"中途换一只"共用）。
     * 不挂 debug_mode（2026-11-08 用户定调：单击素材卡即播，营业中也要能用）。试播是内容操作，
     * 与 stop / update_windows 同级；debug 只决定运维浮层（状态文字/进度条）上不上屏，见 applyDebugUi。
     */
    private fun armPreview(folderKey: String, winId: String, file: File, filename: String, maxMs: Long) {
        val player = windowPlayers[winId] ?: return   // previewTarget 已筛过，这里只兜并发重建
        val st = previews[folderKey] ?: PreviewState(
            winId = winId,
            filename = filename,
            items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) },
            itemIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            playWhenReady = player.playWhenReady,
            repeatMode = player.repeatMode
        ).also { previews[folderKey] = it }
        // 连点第二次是"换一只片子"，不是"把上一只试播当成正片"：上面那份快照一个字段都不许重写，只改日志名
        st.filename = filename
        // 复用同一个 PreviewState 时"是否已按真片长重排过"必须归零：否则上一只留下的 true 会让这一只
        // 永远停在云端那个 5 分钟兜底上，十分钟的片子播到五分钟就被看门狗掐回正片 ——
        // 现场只会看到"后面那半截没播"。
        st.rearmed = false
        st.listener?.let { player.removeListener(it) }
        disarmPreviewWatchdog(st)
        player.setMediaItems(listOf(MediaItem.fromUri(Uri.fromFile(file))))
        player.seekTo(0)
        // repeatMode 用 OFF 而不是 ONE：单只 + OFF 才会真进 STATE_ENDED。
        // 用 ONE 会变成"这只片子永远循环"，试播就再也自己回不去了（只能等看门狗兜，那要等满五分钟）。
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = true
        player.prepare()

        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                logToFile("试播出错: 窗口 $winId ${error.errorCodeName} ${error.message ?: ""}", "ERROR", "PLAYBACK", "PREVIEW")
                mqttHandler.post { endPreview(folderKey, "播放器出错") }
            }
            override fun onPlaybackStateChanged(state: Int) {
                // 跑到片尾就还回正片；共享组里那几扇成员窗跟着同一张画布一起回去
                if (state == Player.STATE_ENDED) mqttHandler.post { endPreview(folderKey, "播完一遍") }
            }
            override fun onEvents(p: Player, events: Player.Events) {
                // 刚 prepare 时 duration 还是 UNKNOWN，看门狗只能按云端给的 max_ms（5 分钟）兜底；
                // 真片长一到位就重排成 duration+15s —— 十分钟的片子不该被五分钟一刀砍掉。
                if (st.rearmed || p.playbackState != Player.STATE_READY) return
                val d = (p as? ExoPlayer)?.duration ?: -1L
                if (d > 0) {
                    st.rearmed = true
                    armPreviewWatchdog(folderKey, minOf(d + 15_000, PREVIEW_HARD_CAP_MS))
                }
            }
        }
        player.addListener(listener)
        st.listener = listener
        armPreviewWatchdog(folderKey, if (maxMs > 0) maxMs else 300_000L)
    }

    /**
     * 看门狗：片长未知、解码卡住、STATE_ENDED 没来 —— 任何一种情况到点都必须把大屏还给正片。
     * 重复武装时先摘掉前一个，免得两只闹钟叠在同一格上。
     */
    private fun armPreviewWatchdog(folderKey: String, ms: Long) {
        val st = previews[folderKey] ?: return
        disarmPreviewWatchdog(st)
        val r = Runnable {
            if (previews.containsKey(folderKey)) {
                logToFile("试播看门狗到点（${ms}ms），强制归还画面: 格子 $folderKey", "WARN", "PLAYBACK", "PREVIEW")
                endPreview(folderKey, "看门狗超时")
            }
        }
        st.watchdog = r
        mqttHandler.postDelayed(r, ms)
    }

    private fun disarmPreviewWatchdog(st: PreviewState) {
        st.watchdog?.let { mqttHandler.removeCallbacks(it) }
        st.watchdog = null
    }

    /**
     * 收回"覆盖到这扇窗"的那笔试播（墙板切格用）。不能只按这扇窗当前的 folderKey 找账：
     * 云端走 named 兜底时（本机还没落上 folderKey），账上的键是云端点名的格子，跟本机认的不是一回事，
     * 找不到就等于没收回 —— 随后播完的那次恢复回调会把画面拽回按号之前。
     * 所以按"这扇窗归哪一路解码"认：共享组找基准，单窗就是它自己。
     */
    private fun endPreviewCovering(winId: String, reason: String) {
        val leader = winShareGroup[winId]?.leaderId ?: winId
        previews.entries.firstOrNull { it.value.winId == leader }?.let { endPreview(it.key, reason) }
    }

    /**
     * 结束一格试播 = 那一路解码还回正片，同格的其余窗跟着同一张画布一起回去（#175 后一格只有一路解码，
     * "整格同步归还"按构造成立，不再需要一轮恢复循环去对齐各处快照）。
     * 只能在主线程调用。
     */
    private fun endPreview(folderKey: String, reason: String) {
        val st = previews.remove(folderKey) ?: run {
            Log.d(TAG, "endPreview: 格子 $folderKey 没有在飞的试播（$reason）")
            return
        }
        disarmPreviewWatchdog(st)
        val player = windowPlayers[st.winId]
        st.listener?.let { player?.removeListener(it) }
        if (player == null) {
            // 窗口已经没了（重建/切幕/废止）：新配置会自己起播，这里再恢复就是往释放过的播放器上写
            logToFile("试播结束（$reason）：窗口 ${st.winId} 已不在屏上，画面交给新配置", "WARN", "PLAYBACK", "PREVIEW")
            return
        }
        if (st.items.isEmpty()) {
            // 试播前这一格本来就没东西可播（空目录/未映射），那就如实还它一份空清单，别硬造进度
            player.setMediaItems(st.items)
        } else {
            player.setMediaItems(st.items, st.itemIndex, st.positionMs)
        }
        player.repeatMode = st.repeatMode
        player.prepare()
        player.playWhenReady = st.playWhenReady
        // 报的是"回到了哪儿"：第几只/共几只 + 毫秒。现场说"画面跳错了"时，这一行就是全部依据。
        val panels = winShareGroup[st.winId]?.members?.size ?: 1
        logToFile("试播结束（$reason）：$panels 扇窗同步回到正片 " +
            (if (st.items.isEmpty()) "（这一格无素材可播）"
             else "第 ${st.itemIndex + 1}/${st.items.size} 只 @${st.positionMs}ms") +
            " → 基准窗 ${st.winId}", "INFO", "PLAYBACK", "PREVIEW")
        previewStatusText("试播结束 · $panels 扇窗已回到正片", STATUS_ACK_MS)
    }

    /**
     * 配置来了 = 试播让路：**只清账不恢复**（旧播放器正要被释放，恢复它等于白做一次解码，
     * 而新那份窗口会照 config 自己起播）。调用点紧跟在播放器释放之前 ——
     * applySceneConfigs 开头与 releaseAllWindows 开头，两处缺一不可。
     */
    private fun abortAllPreviews(reason: String) {
        // 连点合并里还压着一次没落地的试播也一起作废：配置说了算，别等它把画面抢回去
        pendingPreview?.let { mqttHandler.removeCallbacks(it); pendingPreview = null }
        if (previews.isEmpty()) return
        val n = previews.size
        previews.values.forEach { disarmPreviewWatchdog(it) }
        previews.clear()
        logToFile("试播让路：$reason（收回 $n 格）", "INFO", "PLAYBACK", "PREVIEW")
    }

    /** 试播的可见回执：debug 开着时每扇窗正中就是这行字（面板显隐统一在 applyStatusUi） */
    private fun previewStatusText(msg: String, holdMs: Long = 0L) {
        setStatus(msg, holdMs)
    }

    // 【A-08】 窗口释放
    /**
     * 释放所有窗口：播放器 release + 视图从 flSurface 摘掉。
     * 调用点三处 —— onDestroy、playWelcomeVideo 切欢迎视频前（避免旧窗口盖住它）、
     * 以及 action:'stop'（停播 = 画面真的黑下去，不只是停播放器）。
     * 注意不清 windowContentSigs：那由下一次 applySceneConfigs 自己清。
     */
    private fun releaseAllWindows() {
        // 同 applySceneConfigs：先清试播账，再放播放器（否则看门狗/回调会对着已释放实例恢复）
        abortAllPreviews("窗口全部释放")
        windowPlayers.values.forEach { it.release() }
        windowPlayers.clear()
        videoSurfaces.clear()
        // 共享解码单元紧跟播放器之后拆（那块 Surface 是解码器的输出端，先放播放器才谈得上收画布）
        destroyShareGroups()
        windowViews.values.forEach { flSurface?.removeView(it) }
        windowViews.clear()
        windowContentSigs.clear()
        windowNames.clear()
        // 窗口都没了，LOGO 层也就没有落点（暂停解码，播放器实例留着等重建复用）
        hideLogoLayer()
        // 窗口没了，窗口正中那些面板一并摘掉；状态文字退回整屏居中面板（退出到桌面时就是它）
        refreshWindowStatusOverlays()
        // 屏上已经没有窗口了，"已应用配置"的指纹必须一起作废：
        // 否则下次拿同一份 scenes 来 apply 会被幂等闸门挡掉，画面停在欢迎视频上不去
        appliedScenesFp = null
        appliedScenes = null
        appliedPlaySettingsJson = null
        // 屏上次序账同理作废：留着它，stop 之后同一份配置的 update_windows 会被比成"没变"
        appliedWindowOrder = emptyList()
        Log.d(TAG, "释放所有窗口资源")
    }

    // 【A-15】 退出到桌面与唤回：画面交还给原厂桌面，进程、MQTT 订阅、心跳全部保持
    // @tag: setExited findDesktop launchDesktop bringSelfByRoot rebuildAfterExit 退出到桌面 唤回
    // @tag: dispatchTouchEvent registerLeftClick 左键连点 5 下
    //
    // 为什么不是 finishAffinity()：XVJ 现在是默认启动器（HOME），自己 finish 会被系统立刻拉回来，
    // 看着还是"没退"。所以"退出"做成显式把原厂桌面顶到身前 —— 本任务栈退到后面，进程不死，
    // 心跳与 MQTT 照常，云端随时叫得回来。
    // 回来三条路，全汇到 onResume 那一次补建：云端「唤回画面」（靠 root 把自己顶回前台）、
    // 现场按 HOME 键（HOME 仍指向 XVJ）、桌面点 XVJ 图标。
    // 镜像里没有别的桌面、或桌面拉起失败 ⇒ 退化成一层纯黑：语义不变，只是看不到桌面。
    // 两个入口共用一套闸门：退出期间 applySceneConfigs（含 update_windows 回落和墙板回全量重建）、
    // applyPanelSource、startGroupPreview 各自门口挡一下 —— 唤回只走 setExited(false) 这一条路。
    // 状态只在内存里：退出不是配置，重启回到开机画面，而不是回到"上次下班时停在桌面上"。

    /** 连点判定：相邻两下的最大间隔（ms）。定 500 是为了"快点五下"，慢点不算手势 */
    private val TAP_GESTURE_GAP_MS = 500L

    /** 冷启动闸门：整机可用内存低于它就把建窗口往后挪（MB）。3.6GB 的板子，起播尖峰前留 400MB */
    private val COLD_START_MIN_MB = 400

    /** 冷启动闸门最多等这么久；等不到也得硬上，配置不能永远落不了地 */
    private val COLD_START_HOLD_MS = 12_000L

    /** 冷启动闸门的重试间隔 */
    private val COLD_START_RETRY_MS = 3_000L

    /**
     * 整屏重建的最小间隔。台架上「加窗→改尺寸→删窗」连点会接连推倒几次，
     * 每次都在 codec2/Surface 侧留下内核 dma-buf（#224 实测 ~97MB/次且不归还），
     * 4GB 的板子十几轮就够把整机压死。间隔内的新配置只保留最新一份延后跑。
     */
    private val REBUILD_MIN_GAP_MS = 2_500L

    /** 被节流的重建延后这么久再应用 */
    private val REBUILD_DEFER_MS = 1_500L

    /**
     * 拆窗后建窗前的低内存自愈线（MB）：可用内存掉到它以下，先杀 codec2 HAL 把解码帧的内核引用收回来。
     * 台架实测正常水位 1GB+，连推十几轮全量重建会滑到 134MB 且不再回涨（#352）；留 800MB 余量，
     * 是因为一轮 4K 建窗 + 起播本身还要吃掉几百 MB。
     */
    private val REBUILD_HEAL_MIN_MB = 800

    /** 回收后等 HAL 重启、旧缓冲释放的时间（后台线程睡，不占主线程）。回收大头是异步的，2s 才量得准 */
    private val REBUILD_HEAL_SETTLE_MS = 2_000L

    /** 两次回收的最小间隔：冷却期内的重建宁可硬上，也不把每次重建都变成一次黑屏 */
    private val REBUILD_HEAL_COOLDOWN_MS = 15_000L

    /**
     * 回收的硬底线（MB）：低于它就不管冷却直接杀——此时再等冷却，等来的就是 lmkd 的 LOW_MEMORY
     * 杀进程（#354 死在 362MB）。60s 冷却曾被编辑风暴跑赢（#356：一次 745MB 的回收没护住后面 5 轮）。
     */
    private val REBUILD_HEAL_URGENT_MB = 450

    /** 低水位看护的检查周期：每 15s 一次，只读 /proc/meminfo，几乎零开销 */
    private val LOW_WATER_TICK_MS = 15_000L

    /**
     * 连续多少次低于 REBUILD_HEAL_URGENT_MB 才 kill HAL：2 次 ≈ 30s。
     * 要求"持续"是为了避开换片/起播那种十几秒的瞬时低谷，别一低头就黑一下画面。
     */
    private val LOW_WATER_TICKS = 2

    /** 触发退出的左键次数 */
    private val TAP_GESTURE_COUNT = 5

    /** 左键已按下、正等它的抬起沿：一下点击数一次 */
    private var primaryDownPending = false

    /** 已在桌面身后排上的唤回是谁叫的：补建时把原因一起写进日志，现场才查得到源头 */
    private var pendingReopenReason: String? = null

    /**
     * 鼠标左键点击计数（Activity 级 dispatch 能看到全部触点，包括鼠标转成的 touch 事件）。
     * 只认「按下时 buttonState 恰好等于 BUTTON_PRIMARY」这一对沿：手指和触摸笔的 buttonState 是 0，
     * 右键或左右同按也不等于它，所以触摸屏上误点五下不会把营业中的大屏弄下场。
     * 方向性：真退到桌面后鼠标归桌面，本 Activity 收不到事件 ⇒ 连点只管"退出"这一个方向，
     * 回来用 HOME 键、桌面图标或云端唤回（退化黑屏态例外，那时还在前台，连点可以再翻回来）。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> primaryDownPending = ev.buttonState == MotionEvent.BUTTON_PRIMARY
            MotionEvent.ACTION_UP -> {
                if (primaryDownPending) registerLeftClick()
                primaryDownPending = false
            }
            // MOVE / CANCEL：按住左键拖动不是点击，作废这一沿
            else -> primaryDownPending = false
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 把命交给 :keepalive 进程（见 KeepAliveService）。只是"叫它起来"，重复调用无副作用。
     * Android 8+ 必须走 startForegroundService，服务端 5s 内自己前台化；前台化失败它仍以普通服务活着。
     */
    private fun startKeepAliveService() {
        try {
            val i = Intent(this, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            logToFile("保活服务启动失败: ${e.message}", "WARN", "APP", "KEEPALIVE")
        }
    }

    /**
     * 心跳信标：保活进程据此判断本进程是否还活着（30s 一拍，两拍没见才判死）。
     * 写不进去不影响播放，所以失败不刷日志 —— 30s 一次会把日志撑爆。
     */
    private fun touchAlive() {
        try {
            val f = File(filesDir, KeepAliveService.ALIVE_FILE)
            if (!f.exists()) f.createNewFile()
            f.setLastModified(System.currentTimeMillis())
        } catch (e: Exception) {
            // 只影响保活判断，不影响播放：故意不刷日志
        }
    }

    /**
     * 退出到桌面期间让保活进程闭嘴：那是用户显式点的退出，不是崩溃；不标记的话两分钟后画面自己弹回来。
     * 跨进程只能靠文件，SharedPreferences 的 MODE_MULTI_PROCESS 已废弃不可用。
     */
    private fun markExitedFile(on: Boolean) {
        try {
            val f = File(filesDir, KeepAliveService.EXIT_MARKER)
            if (on) f.createNewFile() else f.delete()
        } catch (e: Exception) {
            logToFile("退出标记写入失败: ${e.message}", "WARN", "APP", "KEEPALIVE")
        }
    }

    private fun registerLeftClick() {
        val now = SystemClock.elapsedRealtime()
        tapChainCount = if (tapChainCount > 0 && now - tapChainAtMs <= TAP_GESTURE_GAP_MS) tapChainCount + 1 else 1
        tapChainAtMs = now
        if (tapChainCount < TAP_GESTURE_COUNT) return
        tapChainCount = 0
        setExited(!exited, "左键连点")
    }

    /**
     * 退出/唤回的唯一入口（在主线程调用，命令侧由 mqttHandler post 进来）。
     * 唤回侧的次序不能换：先把 exited 置回 false，再顶前台、重建 ——
     * 反了的话那次 applySceneConfigs 会被它自己的退出闸门挡掉，画面就回不来了。
     */
    private fun setExited(on: Boolean, reason: String) {
        if (exited == on) {
            logToFile("退出状态未变化（$reason 请求${if (on) "退出到桌面" else "唤回画面"}）", "INFO", "WINDOW", "DESKTOP")
            return
        }
        exited = on
        markExitedFile(on)
        if (on) {
            // 释放走的是 action:'stop' 那两下：停单播放器（欢迎视频）+ 放掉全部窗口。
            // 不释放的话桌面在身前、解码器还在后台跑，桌面滑成幻灯片，白占一档 CPU。
            stopPlayback()
            releaseAllWindows()
            val desk = findDesktop()
            val err = if (desk == null) null else launchDesktop(desk)
            if (desk == null || err != null) {
                showBlackout()
                logToFile("退出到桌面（$reason）：${if (desk == null) "本机没有别的桌面" else "桌面 $desk 拉起失败（$err）"}，退化为黑屏",
                    "WARN", "WINDOW", "DESKTOP")
            } else {
                logToFile("退出到桌面（$reason）：画面已交还给 $desk，进程与 MQTT 保持", "INFO", "WINDOW", "DESKTOP")
            }
            setStatus("已退出到桌面 · 管理台点「唤回画面」或按 HOME 键回来")
            return
        }
        hideBlackout()
        if (activityForeground) {
            // 退化黑屏态：一直都在前台，直接重建
            rebuildAfterExit(reason)
            return
        }
        rebuildOnResume = true
        pendingReopenReason = reason
        if (!bringSelfByRoot()) {
            logToFile("唤回（$reason）：没顶回前台，等现场按 HOME 键或点桌面图标", "WARN", "WINDOW", "DESKTOP")
        }
    }

    /**
     * 找"不是我们自己的桌面"：XVJ 现在是默认 HOME，发一条 CATEGORY_HOME 隐式 Intent 只会把自己再叫一次，
     * 所以查全部 HOME 候选、剔掉本包和系统兜底的 FallbackHome，剩下谁就显式拉谁。
     * 一个都不剩（量产镜像没装 launcher）返回 null ⇒ 退化成黑屏。
     */
    private fun findDesktop(): ComponentName? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(home, 0).firstOrNull {
            it.activityInfo.packageName != packageName &&
                it.activityInfo.name != "com.android.settings.FallbackHome"
        }?.let { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }

    /** 显式拉起桌面：NEW_TASK 让系统按正常桌面建/复用它的任务栈。成功返回 null，失败返回异常摘要 */
    private fun launchDesktop(desk: ComponentName): String? = try {
        startActivity(Intent().setComponent(desk).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: Exception) {
        Log.e(TAG, "拉起桌面失败: ${e.message}")
        e.message ?: e.javaClass.simpleName
    }

    /**
     * root 把自己顶回前台：后台起 Activity 从 Android 10 起默认被拦，量产镜像带 su 才走得通。
     * 探不到 root 就返回 false 交给现场按键，不弹框、不重试。
     * 复用 silentInstallReady 的探测结果（同一条 su 通道，探两次浪费；它那句 OTA 措辞只在首次探测时出现）。
     */
    private fun bringSelfByRoot(): Boolean {
        if (!silentInstallReady()) return false
        // 带 MAIN+HOME 类目而不是裸 -n：显式组件只保证起的是我们，栈的归属看类目 ——
        // 不带 HOME 类目会落进 standard 栈，之后现场按 HOME 键时系统要在 home 栈再起一个实例，
        // 两个实例共用同一个 MQTT clientId 会每 10 秒互踢（2026-11-16 台架实测，见 onCreate 去重）。
        val r = execAsRoot("am start -a android.intent.action.MAIN -c android.intent.category.HOME" +
            " -n $packageName/.MainActivity", 8000)
        val ok = r.first == 0
        logToFile("唤回：am start ${if (ok) "已发出" else "失败(code=${r.first}) ${r.second}"}",
            if (ok) "INFO" else "WARN", "WINDOW", "DESKTOP")
        return ok
    }

    /** 退出结束后按本机缓存把画面放回去：授权闸门与 restoreCachedScenes 同一口径 */
    private fun rebuildAfterExit(reason: String) {
        clearStatus()
        // 退出期间被废止过就不该把缓存画面放回去
        if (!prefs.getBoolean("last_authorized", false)) {
            logToFile("唤回（$reason）：本机已非授权态，改播欢迎视频", "WARN", "WINDOW", "DESKTOP")
            playWelcomeVideo()
            return
        }
        val cached = prefs.getString("scenes_json", null)
        if (cached == null) {
            // 从没应用过配置（刚装机还没绑房就退出）：不猜画面。
            // 退出闸门此刻已经放开，下一次 rev 对账或授权回执补发的配置会正常落地。
            logToFile("唤回（$reason）：本机没有 scenes 缓存，等云端下发配置", "WARN", "WINDOW", "DESKTOP")
            return
        }
        try {
            applySceneConfigs(org.json.JSONObject(cached))
            logToFile("唤回（$reason）：按缓存配置重建画面", "INFO", "WINDOW", "DESKTOP")
        } catch (e: Exception) {
            Log.e(TAG, "唤回重建失败: ${e.message}")
            logToFile("唤回失败：scenes 缓存解析异常 ${e.message}", "ERROR", "WINDOW", "ERROR")
        }
    }

    /**
     * 墙板全开/全关：全关 = 给所有窗口盖一层黑布（播放不停，撤掉即回到原画面），全开 = 撤掉。
     * 只在黑布这一层动画面：解码器、播放清单、墙板跟随一概照常跑，所以全开是瞬时的，不用重新起播。
     * 已在主线程（handleWallPanelEvent 的调用点）。
     */
    private fun applyPanelBlackout(on: Boolean, hex: String) {
        if (exited) {
            // 桌面顶在身前时盖黑布没有意义；真记下来反而会在唤回时莫名其妙黑着
            logToFile("墙板${if (on) "全关" else "全开"}帧 $hex：本机在桌面（已退出），忽略", "INFO", "WALLPANEL", "SKIP")
            return
        }
        if (on == panelBlackoutOn) {
            logToFile("墙板${if (on) "全关" else "全开"}帧 $hex：已经是这个状态，忽略", "INFO", "WALLPANEL", "SKIP")
            return
        }
        val root = flSurface ?: return
        panelBlackoutOn = on
        if (on) {
            val v = panelBlackout ?: View(this).apply {
                setBackgroundColor(Color.BLACK)
                root.addView(this, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            panelBlackout = v
            v.bringToFront()
        } else {
            panelBlackout?.let { root.removeView(it) }
            panelBlackout = null
        }
        // 提示文字属于运维层：debug 开着时要压在黑布上面，关着时整层不见
        applyDebugUi()
        logToFile("墙板${if (on) "全关" else "全开"}帧 $hex：${if (on) "所有窗口盖黑，播放继续" else "撤掉黑布，回到原画面"}",
            "INFO", "WALLPANEL", "FRAME")
    }

    /** 黑布盖到根容器最后一层（覆盖顺序只能靠 bringToFront，窗口容器都是 addView 进来的） */
    private fun showBlackout() {
        val root = flSurface ?: return
        if (blackout == null) {
            val v = View(this)
            v.setBackgroundColor(Color.BLACK)
            root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            blackout = v
        }
        blackout?.bringToFront()
        // 提示文字属于运维层：debug 开着时要压在黑布上面，关着时整层不见
        applyDebugUi()
    }

    private fun hideBlackout() {
        blackout?.let { flSurface?.removeView(it) }
        blackout = null
        applyDebugUi()
    }
}

/**
 * 亮度遮罩：黑底 + 4×4 Bayer 抖动 alpha，替代原来的均匀 alpha 黑层。
 *
 * 【为什么改】遮罩靠 SurfaceFlinger 的 PREMULTIPLIED 混合实现"乘法调暗"，混在 8-bit gamma
 * 空间里完成。光晕这类大面积低对比度渐变乘完只剩十几个台阶，现场就是一圈圈色带。
 * 改成逐像素在 floor/ceil 之间交替后，4×4 的平均值仍是 1-brightness，量化误差被摊成
 * 高频噪点，看到的是渐变而不是台阶。代价只是一张 4×4 位图，解码与素材链路一概不碰。
 */
private class DitherDimView(context: Context) : View(context) {
    private val paint = Paint().apply {
        // 必须逐像素落在屏幕上：开插值会把 4×4 抹成一个均匀的灰，白干
        isFilterBitmap = false
        isAntiAlias = false
    }
    private var brightness = -1f

    init { setWillNotDraw(false) }

    fun setBrightness(b: Float) {
        val v = b.coerceIn(0f, 1f)
        if (v == brightness) return
        brightness = v
        // 目标遮罩不透明度（0..255）拆成整数部分 + 小数部分：整数部分给每个像素，
        // 小数部分按 Bayer 阈值分给 4×4 里的一部分像素，平均正好等于 1-brightness
        val a = ((1f - v) * 255f).coerceIn(0f, 255f)
        val lo = a.toInt()
        val frac = a - lo
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        var i = 0
        for (y in 0 until 4) for (x in 0 until 4) {
            val hi = if (BAYER4[i++] < frac * 16f) 1 else 0
            bmp.setPixel(x, y, Color.argb((lo + hi).coerceAtMost(255), 0, 0, 0))
        }
        paint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    companion object {
        /** 标准 4×4 Bayer 阈值，0..15 各出现一次 */
        private val BAYER4 = intArrayOf(
            0, 8, 2, 10,
            12, 4, 14, 6,
            3, 11, 1, 9,
            15, 7, 13, 5
        )
    }
}
