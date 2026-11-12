package com.xvj.app

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 同格共享解码的画面层（任务 #175）。
 *
 * 【它解决什么】同一格挂 N 扇窗时，设备本来起 N 路 ExoPlayer、N 个 MediaCodec 解同一条码流：
 * 交接延迟与凝滞按窗口数线性放大（6 扇同格实测散差 3.5s，混进 4K 那一路凝滞到 4.8s，
 * 同步校正因此在第一次交接就熔断）。这里只留一路解码，解码输出落进一张离屏纹理，
 * 本视图在 GL 线程上把同一张纹理按每扇窗自己的位置/尺寸/裁切/旋转/镜像/透明度/亮度各画一遍 ——
 * 复制发生在 GPU 上，代价与窗口数基本无关，音频也自然只剩一路。
 *
 * 【为什么必须自己画】一块 TextureView 只显示它自己那张 SurfaceTexture，系统没有"把 A 的
 * SurfaceTexture 挂给 B 显示"的公开接口。要多扇窗共用一路解码，就只能自建一层拿同一张外部纹理画多次。
 *
 * 【取舍】整组是一块画布（成员矩形的并集），所以：
 *  - 并集矩形里被盖到的**别组**窗口按本组最上层那扇的 z 一并压过去，跨组 z 交错没了（同组内部仍按各自 z 叠）；
 *  - 取景或旋转让采样越出纹理边界时按夹边取样（边缘拉伸），原来是容器黑底。
 * 单窗房间完全不走这条路，画面链路与改动前逐字节一致。
 *
 * 【线程】一个视图一条 GL HandlerThread + 一个 EGL 上下文；解码纹理 attach 到这个上下文，
 * updateTexImage 只在这条线程上调。几何改动来自主线程（synchronized 交接，下一帧自然生效）。
 * TextureView 那块承载用的 SurfaceTexture 会随后台/失焦销毁、回前台重建，所以 GL 线程与 EGL
 * 跟着它拆了再建；喂解码器的那块离屏纹理不属于 TextureView，只在 shutdown 里交还。
 */
class ShareCanvasView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    /** 一扇成员窗在画布里的画面参数。坐标是画布内部像素（不是整屏），由调用方按并集矩形换算 */
    data class Member(
        val winId: String,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val alpha: Float,
        val brightness: Float,
        /** 归一化取景区 [x, y, w, h]，与 MainActivity.cropRect 同一口径 */
        val crop: FloatArray,
        val rotation: Int,
        val flip: String
    )

    /** 解码器写入的那张纹理的产出端。MainActivity 用 decoderSurface 喂唯一那一路 ExoPlayer */
    val decoderTexture: SurfaceTexture = SurfaceTexture()
    val decoderSurface: Surface = Surface(decoderTexture)

    private val lock = Any()
    private var members: List<Member> = emptyList()

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    /** 只在 shutdown 里置真 = 永久收摊；TextureView 的 st 销毁重建不算，画布要能在下一次 available 复活 */
    private var released = false

    // 以下全部只在 GL 线程上碰
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var locPosition = 0
    private var locTexCoord = 0
    private var locParam = 0
    private var locStMatrix = 0
    private var locTexture = 0
    private var oesTexture = 0
    private var attached = false
    private var vertexBuf: FloatBuffer? = null
    private val stMatrix = FloatArray(16)

    private val frameListener = SurfaceTexture.OnFrameAvailableListener { glHandler?.post(drawTick) }
    private val drawTick = Runnable { drawFrame() }

    init {
        // 透明承载：并集矩形里没被成员盖住的区域要透出下层窗口，不能由本层涂黑
        isOpaque = false
        surfaceTextureListener = this
    }

    /** 主线程：整组画面参数换一份（已按 z 升序排好），下一帧生效 */
    fun setMembers(list: List<Member>) {
        synchronized(lock) { members = list }
        glHandler?.post(drawTick)
    }

    /** 主线程：解码分辨率到位后告诉离屏纹理，MediaCodec 按它分配输出缓冲 */
    fun setDecoderSize(width: Int, height: Int) {
        if (width > 0 && height > 0) decoderTexture.setDefaultBufferSize(width, height)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        val t = HandlerThread("xvj-share-gl").also { it.start() }
        glThread = t
        val h = Handler(t.looper)
        glHandler = h
        h.post { initGl(st, width, height) }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        st.setDefaultBufferSize(width, height)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        // 后台/失焦时 TextureView 会收掉自己那块 st，回到前台再给一块新的：这里只跟着拆 GL（线程和 EGL
        // 都绑在这块 st 上），不动解码端 —— 离屏纹理与它的 Surface 属于本视图，一旦 release，
        // 那一路 ExoPlayer 的输出端就永久死了，回前台再也接不上（表现是"切一下后台画面再也不动了"）
        if (!released) teardownGl()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /**
     * 永久收摊（MainActivity 拆共享单元时调用，且必须紧跟在播放器 release 之后 ——
     * 那块 Surface 底下就是解码器正在写的 SurfaceTexture）。可重复调用。
     */
    fun shutdown() {
        if (released) return
        released = true
        teardownGl()
        try { decoderSurface.release() } catch (e: Exception) { }
    }

    /**
     * 收掉 GL 线程上的全部资源再停线程。从主线程调用时用一次同步等待：EGL 上下文只能由创建它的线程销毁，
     * 而解码器那边随时可能又来写帧，不能让它对着一个已经作废的上下文继续跑。
     * 只在主线程调用（TextureView 的两个生命周期回调都在这里）。
     */
    private fun teardownGl() {
        val h = glHandler
        val t = glThread
        glHandler = null
        glThread = null
        if (h != null) {
            val latch = CountDownLatch(1)
            h.post { releaseGl(); latch.countDown() }
            try { latch.await(600, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { }
        }
        t?.quitSafely()
    }

    // ================= GL 线程 =================

    private fun initGl(st: SurfaceTexture, viewWidth: Int, viewHeight: Int) {
        st.setDefaultBufferSize(viewWidth, viewHeight)
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (d == EGL14.EGL_NO_DISPLAY) { Log.e(TAG, "共享画布：eglGetDisplay 失败"); return }
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(d, ver, 0, ver, 1)) { Log.e(TAG, "共享画布：eglInitialize 失败"); return }
        display = d
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val got = IntArray(1)
        if (!EGL14.eglChooseConfig(d, cfgAttrs, 0, cfgs, 0, 1, got, 0) || got[0] < 1) {
            Log.e(TAG, "共享画布：找不到 RGBA8888 ES2 配置"); EGL14.eglTerminate(d); return
        }
        val cfg = cfgs[0]!!
        eglContext = EGL14.eglCreateContext(d, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) { Log.e(TAG, "共享画布：创建上下文失败"); EGL14.eglTerminate(d); return }
        eglSurface = EGL14.eglCreateWindowSurface(d, cfg, st, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "共享画布：创建窗口表面失败 " + EGL14.eglGetError())
            EGL14.eglDestroyContext(d, eglContext); eglContext = EGL14.EGL_NO_CONTEXT; EGL14.eglTerminate(d); return
        }
        if (!EGL14.eglMakeCurrent(d, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "共享画布：eglMakeCurrent 失败 " + EGL14.eglGetError())
            releaseGl(); return
        }
        program = buildProgram()
        if (program == 0) { releaseGl(); return }
        locPosition = GLES20.glGetAttribLocation(program, "aPosition")
        locTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        locParam = GLES20.glGetAttribLocation(program, "aParam")
        locStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        locTexture = GLES20.glGetUniformLocation(program, "sTexture")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTexture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // 旋转 90°/270° 或裁切推到边界外时，采样会越出纹理边缘：夹边，别绕回去取到另一头的画面
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        try {
            decoderTexture.attachToGLContext(oesTexture)
            attached = true
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "共享画布：解码纹理 attach 失败", e)
        }
        decoderTexture.getTransformMatrix(stMatrix)
        // 帧通知直接在 GL 线程落地（API 26 起才有带 Handler 的重载）；24/25 退回旧签名，
        // 由 frameListener 自己 post 到同一条线程 —— 两条路画的都是"最新一帧"，重复触发无害
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            decoderTexture.setOnFrameAvailableListener(frameListener, glHandler)
        } else {
            decoderTexture.setOnFrameAvailableListener(frameListener)
        }
        drawFrame()
    }

    private fun drawFrame() {
        if (eglSurface == EGL14.EGL_NO_SURFACE || program == 0) return
        val list = synchronized(lock) { members }
        // 成员还没排好就什么都不画：清屏会把上一帧涂成透明，白白闪一下黑
        if (list.isEmpty()) return
        val ws = IntArray(1)
        val hs = IntArray(1)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, ws, 0)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, hs, 0)
        val ew = ws[0]
        val eh = hs[0]
        if (ew <= 0 || eh <= 0) return
        if (attached) {
            // 没有新帧时它只是不动，重复调（几何改动触发的补画）因此是安全的
            try { decoderTexture.updateTexImage() } catch (e: Exception) { Log.e(TAG, "共享画布：取帧失败", e) }
            decoderTexture.getTransformMatrix(stMatrix)
        }
        val buf = ensureCapacity(list.size) ?: return
        val efx = 2f / ew
        val efy = 2f / eh
        for (m in list) {
            if (m.width <= 0 || m.height <= 0) continue
            val uv = cornerUv(m)
            val x0 = m.left * efx - 1f
            val x1 = (m.left + m.width) * efx - 1f
            val y0 = 1f - m.top * efy
            val y1 = 1f - (m.top + m.height) * efy
            val a = m.alpha.coerceIn(0f, 1f)
            val b = m.brightness.coerceIn(0f, 1f)
            // 两个三角形：TL TR BR / TL BR BL
            quad(buf, x0, y0, uv[0], uv[1], a, b)
            quad(buf, x1, y0, uv[2], uv[3], a, b)
            quad(buf, x1, y1, uv[4], uv[5], a, b)
            quad(buf, x0, y0, uv[0], uv[1], a, b)
            quad(buf, x1, y1, uv[4], uv[5], a, b)
            quad(buf, x0, y1, uv[6], uv[7], a, b)
        }
        val count = buf.position() / FLOATS_PER_VERTEX
        if (count == 0) return
        buf.position(0)
        GLES20.glViewport(0, 0, ew, eh)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        // 输出按预乘 alpha 写（见 fragment shader），系统层合成也按预乘读
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(locTexture, 0)
        GLES20.glUniformMatrix4fv(locStMatrix, 1, false, stMatrix, 0)
        GLES20.glEnableVertexAttribArray(locPosition)
        GLES20.glEnableVertexAttribArray(locTexCoord)
        GLES20.glEnableVertexAttribArray(locParam)
        buf.position(0)
        GLES20.glVertexAttribPointer(locPosition, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE, buf)
        buf.position(2)
        GLES20.glVertexAttribPointer(locTexCoord, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE, buf)
        buf.position(4)
        GLES20.glVertexAttribPointer(locParam, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE, buf)
        buf.position(0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisableVertexAttribArray(locPosition)
        GLES20.glDisableVertexAttribArray(locTexCoord)
        GLES20.glDisableVertexAttribArray(locParam)
        EGL14.eglSwapBuffers(display, eglSurface)
        // 交回 UI 线程重放这块硬件层：TextureView 不会自己知道画布换了新内容
        postInvalidateOnAnimation()
    }

    /**
     * 四角的内容归一化坐标（TL,TR,BR,BL）。矩阵与 MainActivity.applyWindowTransform 逐条同序，
     * 只是在归一化空间里做：那边 vw/vh 换掉后剩下的就是这份，两条链路才不会画出两种画面。
     * @return 8 个 float = 4 个角的 (s,t)
     */
    private fun cornerUv(m: Member): FloatArray {
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val cm = Matrix()
        cm.setTranslate(-m.crop[0], -m.crop[1])
        cm.postScale(1f / m.crop[2], 1f / m.crop[3])
        if (m.rotation == 90 || m.rotation == 270) {
            cm.postScale(m.height.toFloat() / m.width, m.width.toFloat() / m.height, 0.5f, 0.5f)
        }
        cm.postRotate(m.rotation.toFloat(), 0.5f, 0.5f)
        if (m.flip == "h") cm.postScale(-1f, 1f, 0.5f, 0.5f)
        if (m.flip == "v") cm.postScale(1f, -1f, 0.5f, 0.5f)
        val inv = Matrix()
        if (cm.invert(inv)) inv.mapPoints(corners)
        return corners
    }

    private fun quad(buf: FloatBuffer, x: Float, y: Float, s: Float, t: Float, alpha: Float, bright: Float) {
        buf.put(x).put(y).put(s).put(t).put(alpha).put(bright)
    }

    private fun ensureCapacity(memberCount: Int): FloatBuffer? {
        val need = memberCount * 6 * FLOATS_PER_VERTEX
        var buf = vertexBuf
        if (buf == null || buf.capacity() < need) {
            buf = ByteBuffer.allocateDirect(need * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertexBuf = buf
        }
        buf.limit(need)
        buf.position(0)
        return buf
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SRC) ?: return 0
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SRC) ?: return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "共享画布：着色器链接失败 " + GLES20.glGetProgramInfoLog(p))
            GLES20.glDeleteProgram(p)
            return 0
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, src: String): Int? {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "共享画布：着色器编译失败 " + GLES20.glGetShaderInfoLog(s))
            GLES20.glDeleteShader(s)
            return null
        }
        return s
    }

    /** 只在 GL 线程调用：先趁上下文还在Current 时清 GL 对象，再摘 current、毁 surface/context、terminate */
    private fun releaseGl() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (attached) {
            try { decoderTexture.detachFromGLContext() } catch (e: Exception) { }
            attached = false
        }
        try { decoderTexture.setOnFrameAvailableListener(null) } catch (e: Exception) { }
        if (program != 0) { GLES20.glDeleteProgram(program); program = 0 }
        if (oesTexture != 0) { GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0); oesTexture = 0 }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) { EGL14.eglDestroySurface(display, eglSurface); eglSurface = EGL14.EGL_NO_SURFACE }
        // 上下文必须由"不再 current"的线程销毁，否则 EGL_BAD_ACCESS，毁不掉就一直占着驱动资源
        if (eglContext != EGL14.EGL_NO_CONTEXT) { EGL14.eglDestroyContext(display, eglContext); eglContext = EGL14.EGL_NO_CONTEXT }
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
    }

    companion object {
        private const val TAG = "XVJShare"
        private const val FLOATS_PER_VERTEX = 6
        private const val VERTEX_STRIDE = FLOATS_PER_VERTEX * 4

        private const val VERTEX_SRC = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            attribute vec2 aParam;
            uniform mat4 uStMatrix;
            varying vec2 vTexCoord;
            varying vec2 vParam;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uStMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                vParam = aParam;
            }
        """

        private const val FRAGMENT_SRC = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            varying vec2 vParam;
            uniform samplerExternalOES sTexture;
            void main() {
                vec4 c = texture2D(sTexture, vTexCoord);
                // 亮度是乘在颜色上的（与黑色遮罩 alpha=1-亮度 等效），透明度按预乘 alpha 交给系统层混
                gl_FragColor = vec4(c.rgb * vParam.y * vParam.x, c.a * vParam.x);
            }
        """
    }
}
