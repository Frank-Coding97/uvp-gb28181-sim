package com.uvp.sim.osd

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * EGL 1.4 + GL ES 3.0 上下文封装 — 单 context 多 surface。
 *
 * 整套 OSD 渲染管线共享一个 [EGLContext],配多个 [EGLSurface]:
 * - encoder window surface(MediaCodec inputSurface)
 * - screen window surface(SurfaceView 显示)
 * - 临时 pbuffer(测试用)
 *
 * GL 命令调用前必须 [makeCurrent] 切到目标 surface,渲染完 [swapBuffers]。
 *
 * 使用顺序:
 * ```
 * val core = EglCore()
 * core.setupDisplay()                  // 1. 拿 EGLDisplay
 * core.createConfig()                  // 2. 选 RGBA8888 + GL ES 3.0 config
 * core.createContext()                 // 3. 创 EGLContext
 * val s = core.createWindowSurface(surface)
 * core.makeCurrent(s)
 * // GL.glXxx ...
 * core.swapBuffers(s)
 * core.destroySurface(s)
 * core.release()
 * ```
 *
 * 错误处理:任何 EGL 调用返回错误码 / NO_DISPLAY 等都抛 [EglException],
 * 调用方可 catch 后走 GL fallback 路径(emit OSD_INIT_FAILED 旧推流)。
 */
internal class EglCore {

    var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set
    var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private set
    var eglConfig: EGLConfig? = null
        private set

    private var released = false

    fun setupDisplay() {
        require(eglDisplay == EGL14.EGL_NO_DISPLAY) { "display already set up" }
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (d === EGL14.EGL_NO_DISPLAY) throw EglException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(d, version, 0, version, 1)) {
            throw EglException("eglInitialize failed: 0x${EGL14.eglGetError().toString(16)}")
        }
        eglDisplay = d
    }

    fun createConfig() {
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "setupDisplay first" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, configs.size, numConfigs, 0)) {
            throw EglException("eglChooseConfig failed: 0x${EGL14.eglGetError().toString(16)}")
        }
        if (numConfigs[0] <= 0 || configs[0] == null) {
            throw EglException("no matching EGL config(GL ES 3.0 + RGBA8888)")
        }
        eglConfig = configs[0]
    }

    fun createContext() {
        check(eglConfig != null) { "createConfig first" }
        val attribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val ctx = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, attribs, 0
        )
        if (ctx === EGL14.EGL_NO_CONTEXT) {
            throw EglException("eglCreateContext failed: 0x${EGL14.eglGetError().toString(16)}")
        }
        eglContext = ctx
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        check(eglConfig != null) { "createConfig first" }
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val s = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
        if (s === EGL14.EGL_NO_SURFACE) {
            throw EglException("eglCreateWindowSurface failed: 0x${EGL14.eglGetError().toString(16)}")
        }
        return s
    }

    fun createPbufferSurface(width: Int, height: Int): EGLSurface {
        check(eglConfig != null) { "createConfig first" }
        val attribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        val s = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, attribs, 0)
        if (s === EGL14.EGL_NO_SURFACE) {
            throw EglException("eglCreatePbufferSurface failed: 0x${EGL14.eglGetError().toString(16)}")
        }
        return s
    }

    fun makeCurrent(surface: EGLSurface) {
        check(!released) { "EglCore released" }
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            throw EglException("eglMakeCurrent failed: 0x${EGL14.eglGetError().toString(16)}")
        }
    }

    fun makeNothingCurrent() {
        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
    }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(eglDisplay, surface)

    fun setPresentationTime(surface: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, surface, nsecs)
    }

    fun destroySurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, surface)
    }

    fun release() {
        if (released) return
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            makeNothingCurrent()
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
        released = true
    }
}

internal class EglException(message: String) : RuntimeException(message)
