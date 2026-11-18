package com.xvj.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * HDMI 采集的显示侧：相机 HAL 只往一块 SurfaceTexture 里出帧，本类把这一帧画到 N 扇窗的 Surface。
 *
 * 为什么这么做：
 * - 相机 HAL 声明 maxNumOutputStreams=3，每扇窗直连一层输出时，第 3 扇窗一开就配置失败；
 *   单路输出后窗口数不再受相机限制，各窗只多一层离屏纹理。
 * - 顺带修正动态范围：v4l2 报 Quantization: Limited Range，HAL 给 PRIVATE 流的 dataspace 是 0x0
 *   （未知）被显示链当全范围解读，于是黑（Y=16）抬成 16/255 的灰。着色器按 16..235 → 0..255 展开。
 *   实测（2026-11-17 台架）：纯黑窗格落到 16，任务栏 #202020 落 43，符合 v_out = 16 + 0.859·v_in。
 *
 * 线程：全部在 HdmiCapture 的采集线程上调用（EGL 上下文、SurfaceTexture、窗口 EGLSurface 都属于它），
 * 不另起线程；相机回调与 onFrameAvailable 本来就在这条线程的 Handler 上。
 */
class HdmiGlRenderer(private val log: (msg: String, level: String) -> Unit) {

    companion object {
        private const val VERT = """
            uniform mat4 uST;
            attribute vec2 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vTex = (uST * vec4(aTex, 0.0, 1.0)).xy;
            }
        """
        // 有限范围 → 全范围：out = (in − 16/255) × 255/219 ≈ in×1.16438 − 0.07306
        private const val FRAG = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                vec3 c = texture2D(sTex, vTex).rgb;
                c = clamp(c * 1.1643835 - 0.07305936, 0.0, 1.0);
                gl_FragColor = vec4(c, 1.0);
            }
        """
        /** 顶点：位置 + 纹理坐标（左下为 0,0，与 SurfaceTexture 变换矩阵的惯例一致） */
        private val VERTS = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
        )
    }

    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var cfg: EGLConfig? = null
    /** 保活用的 1×1 pbuffer：没有窗在画的时候也得有个 current surface 才能 updateTexImage */
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var tex = 0
    private var program = 0
    private var aPos = -1
    private var aTex = -1
    private var uST = -1
    private var uTex = -1
    private val stMatrix = FloatArray(16)
    private val quad: FloatBuffer =
        ByteBuffer.allocateDirect(VERTS.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(VERTS); position(0) }

    /** 相机往这里出帧 */
    var inputSurface: Surface? = null
        private set
    var surfaceTexture: SurfaceTexture? = null
        private set

    private var ready = false

    private class Win(val surface: Surface) {
        var egl: EGLSurface = EGL14.EGL_NO_SURFACE
        var w = 0
        var h = 0
        var failed = false
    }

    /** winId → 窗口。EGLSurface 在第一次画到它时才建（省掉空窗的缓冲） */
    private val wins = LinkedHashMap<String, Win>()

    /** 初始化 EGL 与相机输出 SurfaceTexture；成功一次后不再重建 */
    fun start(): Boolean {
        if (ready) return true
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (dpy === EGL14.EGL_NO_DISPLAY) return fail("eglGetDisplay 失败")
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return fail("eglInitialize 失败")
            val cfgAttrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            if (!EGL14.eglChooseConfig(dpy, cfgAttrs, 0, cfgs, 0, 1, n, 0) || n[0] < 1) {
                return fail("eglChooseConfig 失败")
            }
            val chosen = cfgs[0] ?: return fail("eglChooseConfig 返回空配置")
            cfg = chosen
            ctx = EGL14.eglCreateContext(
                dpy, chosen, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            if (ctx === EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext 失败")
            pbuffer = EGL14.eglCreatePbufferSurface(
                dpy, chosen,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            if (pbuffer === EGL14.EGL_NO_SURFACE) return fail("eglCreatePbufferSurface 失败")
            if (!EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, ctx)) return fail("eglMakeCurrent 失败")

            program = buildProgram(VERT, FRAG)
            if (program == 0) return fail("着色器编译失败")
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aTex = GLES20.glGetAttribLocation(program, "aTex")
            uST = GLES20.glGetUniformLocation(program, "uST")
            uTex = GLES20.glGetUniformLocation(program, "sTex")

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            tex = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val s = SurfaceTexture(tex)
            s.setDefaultBufferSize(HdmiCapture.W, HdmiCapture.H)
            surfaceTexture = s
            inputSurface = Surface(s)
            ready = true
            log("HDMI 输入：GL 渲染器就绪（单路 ${HdmiCapture.W}x${HdmiCapture.H} → 多窗）", "INFO")
            return true
        } catch (e: Exception) {
            return fail("初始化异常 ${e.message}")
        }
    }

    fun attachWindow(winId: String, surface: Surface) {
        val old = wins[winId]
        if (old != null && old.surface === surface) return // 同一块重复接入：保留已建的 EGLSurface
        wins[winId] = Win(surface)
        if (old != null) destroyWindow(old)
    }

    fun detachWindow(surface: Surface) {
        val it = wins.entries.firstOrNull { it.value.surface === surface } ?: return
        wins.remove(it.key)
        destroyWindow(it.value)
    }

    /** 来一帧：更新纹理，再画到每扇窗。调用方保证在采集线程上 */
    fun drawFrame() {
        if (!ready) return
        val s = surfaceTexture ?: return
        if (!EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, ctx)) return
        try {
            s.updateTexImage()
            s.getTransformMatrix(stMatrix)
        } catch (e: Exception) {
            return
        }
        for (w in wins.values) drawInto(w)
        // 别把 pbuffer 之外的 Surface 一直挂在上下文上，窗口销毁时才不会卡
        EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, ctx)
    }

    private fun drawInto(w: Win) {
        if (w.failed) return
        val conf = cfg ?: return
        if (w.egl === EGL14.EGL_NO_SURFACE) {
            val created: EGLSurface? = try {
                EGL14.eglCreateWindowSurface(dpy, conf, w.surface, intArrayOf(EGL14.EGL_NONE), 0)
            } catch (e: Exception) {
                null
            }
            if (created == null || created === EGL14.EGL_NO_SURFACE) {
                w.failed = true
                log("HDMI 输入：窗口纹理建 Surface 失败", "WARN")
                return
            }
            w.egl = created
            val v = IntArray(1)
            EGL14.eglQuerySurface(dpy, w.egl, EGL14.EGL_WIDTH, v, 0)
            w.w = v[0]
            EGL14.eglQuerySurface(dpy, w.egl, EGL14.EGL_HEIGHT, v, 0)
            w.h = v[0]
        }
        if (!EGL14.eglMakeCurrent(dpy, w.egl, w.egl, ctx)) {
            destroyWindow(w)
            w.failed = true
            return
        }
        // 不等垂直同步：多窗时跟不上就丢帧，别把采集队列越积越久
        EGL14.eglSwapInterval(dpy, 0)
        GLES20.glViewport(0, 0, w.w, w.h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uST, 1, false, stMatrix, 0)
        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        EGL14.eglSwapBuffers(dpy, w.egl)
    }

    private fun destroyWindow(w: Win) {
        if (w.egl !== EGL14.EGL_NO_SURFACE) {
            try { EGL14.eglDestroySurface(dpy, w.egl) } catch (e: Exception) {}
        }
        w.egl = EGL14.EGL_NO_SURFACE
    }

    fun shutdown() {
        for (w in wins.values) destroyWindow(w)
        wins.clear()
        try { inputSurface?.release() } catch (e: Exception) {}
        inputSurface = null
        try { surfaceTexture?.release() } catch (e: Exception) {}
        surfaceTexture = null
        if (dpy !== EGL14.EGL_NO_DISPLAY) {
            try {
                if (ctx !== EGL14.EGL_NO_CONTEXT && pbuffer !== EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, ctx)
                    if (tex != 0) GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
                    if (program != 0) GLES20.glDeleteProgram(program)
                }
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuffer)
                if (ctx !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            } catch (e: Exception) {}
        }
        pbuffer = EGL14.EGL_NO_SURFACE
        ctx = EGL14.EGL_NO_CONTEXT
        dpy = EGL14.EGL_NO_DISPLAY
        tex = 0
        program = 0
        ready = false
    }

    private fun fail(why: String): Boolean {
        log("HDMI 输入：GL 渲染器初始化失败（$why）", "ERROR")
        shutdown()
        return false
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs) ?: return 0
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs) ?: return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        if (ok[0] == 0) {
            log("HDMI 输入：着色器链接失败 ${GLES20.glGetProgramInfoLog(p)}", "ERROR")
            GLES20.glDeleteProgram(p)
            return 0
        }
        return p
    }

    private fun compile(type: Int, src: String): Int? {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            log("HDMI 输入：着色器编译失败 ${GLES20.glGetShaderInfoLog(s)}", "ERROR")
            GLES20.glDeleteShader(s)
            return null
        }
        return s
    }
}
