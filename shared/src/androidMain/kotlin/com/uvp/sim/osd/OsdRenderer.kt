package com.uvp.sim.osd

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OSD 主渲染器 — 模仿真实 IPC 硬件 ISP region 的"单一画面源 + 多消费者"模型。
 *
 * ```
 *   摄像头 SurfaceTexture
 *         ↓
 *   CameraTexturePass(OES → fbo)
 *         ↓
 *   fbo (RGBA8 离屏纹理,渲染 OSD 三层)
 *         ↓ blit
 *   ┌──────┬──────┬──────┐
 *   ↓      ↓      ↓      ↓
 *  直播    录像   屏幕    ...
 *  encoder encoder SurfaceView
 * ```
 *
 * 跟工业 IPC 同构:一份"已烧 OSD 的画面"分发给所有消费者,
 * 屏幕看到什么 = 录像写下什么 = 直播推出去什么 = WVP 回放看到什么。
 *
 * 消费者注册:
 * - [addEncoderSurface] / [removeEncoderSurface] — 直播 / 录像编码器 InputSurface
 * - [setScreenSurface] — SurfaceView 屏幕预览(仅一个,replace 语义)
 *
 * 各消费者可独立分辨率;OsdRenderer 内部按"全局最大分辨率"渲染 fbo,
 * blit 到消费者时 GL viewport 自动缩放(等价 letterbox)。
 *
 * 失败 fallback:[start] 返回 false 时调用方应回退老路径(无 OSD,流仍能推)。
 */
internal class OsdRenderer(
    private val context: Context,
    private val configFlow: StateFlow<OsdConfig>,
    private val targetWidth: Int = 1280,
    private val targetHeight: Int = 720
) {

    private val tickerSource = OsdTickerSource(configFlow)
    private val atlas = OsdFontAtlas()
    private var cameraPass: CameraTexturePass? = null
    private var textPass: OsdTextPass? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var eglCore: EglCore? = null

    private var fboId: Int = 0
    private var fboTexId: Int = 0
    private var fboWidth: Int = 0
    private var fboHeight: Int = 0

    private var blitProgram: Int = 0
    private var blitVbo: Int = 0
    private var blitTexLoc: Int = 0

    /** 注册的消费者:每个 = (Surface, EGLSurface, width, height)。 */
    private data class Consumer(
        val tag: String,
        val surface: Surface,
        var eglSurface: EGLSurface,
        var width: Int,
        var height: Int,
        /**
         * true = CENTER_CROP(放大顶满、裁掉超出,无黑边);false = letterbox(保持比例、留黑边)。
         * 屏幕预览给人看用 crop 填满,encoder(录像/直播)用 letterbox 保证所录=所见的完整画面。
         */
        val cropToFill: Boolean = false
    )
    private val encoderConsumers: MutableList<Consumer> = mutableListOf()
    private var screenConsumer: Consumer? = null

    private var surfaceTexture: SurfaceTexture? = null
    private var _cameraInputSurface: Surface? = null
    private var cameraBufferWidth: Int = targetWidth
    private var cameraBufferHeight: Int = targetHeight
    private var cameraFrameWidth: Int = targetWidth
    private var cameraFrameHeight: Int = targetHeight

    val cameraInputSurface: Surface? get() = _cameraInputSurface

    private val started = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    private val transformMatrix = FloatArray(16)

    /** 帧渲染时长统计 — 超过 [FRAME_BUDGET_MS] 计入掉帧,达到 [DROP_REPORT_THRESHOLD] 触发一次告警。 */
    private var droppedFrames: Int = 0
    private var lastDropReportNs: Long = 0L

    fun start(): Boolean {
        if (started.get()) return true
        val t = HandlerThread("osd-renderer-gl").apply { start() }
        val h = Handler(t.looper)

        var ok = false
        val latch = Object()
        h.post {
            try {
                eglCore = EglCore().apply {
                    setupDisplay()
                    createConfig()
                    createContext()
                }
                val tmpPbuffer = eglCore!!.createPbufferSurface(1, 1)
                eglCore!!.makeCurrent(tmpPbuffer)

                if (!atlas.load(context)) throw RuntimeException("OsdFontAtlas.load failed")
                cameraPass = CameraTexturePass().apply { init() }
                textPass = OsdTextPass(atlas).apply { init() }

                createFbo(targetWidth, targetHeight)
                createBlitProgram()
                cameraPass!!.setFrameSize(
                    cameraFrameWidth,
                    cameraFrameHeight,
                    fboWidth,
                    fboHeight,
                    cropToFill = true
                )

                surfaceTexture = SurfaceTexture(cameraPass!!.cameraTextureId).apply {
                    setDefaultBufferSize(cameraBufferWidth, cameraBufferHeight)
                    setOnFrameAvailableListener {
                        h.post { onFrameAvailable() }
                    }
                }
                _cameraInputSurface = Surface(surfaceTexture)

                eglCore!!.destroySurface(tmpPbuffer)
                ok = true
                SystemLogger.emit(LogLevel.Info, LogTag.Media, "OSD_INIT_OK",
                    detail = "${targetWidth}x${targetHeight}, atlas=${atlas.charCount} chars")
            } catch (t: Throwable) {
                SystemLogger.emit(LogLevel.Error, LogTag.Media, "OSD_INIT_FAILED",
                    detail = "${t::class.simpleName}: ${t.message}")
                releaseInternal()
            } finally {
                synchronized(latch) { latch.notifyAll() }
            }
        }
        synchronized(latch) { latch.wait(5000) }

        if (!ok) {
            t.quitSafely()
            return false
        }
        thread = t
        handler = h
        started.set(true)
        return true
    }

    /**
     * 注册一个 encoder 消费者(直播 / 录像)。
     *
     * @param tag 消费者标识(用于日志,如 "live" / "record")
     * @param surface MediaCodec encoder.createInputSurface() 拿到的 surface
     * @param width 编码分辨率宽
     * @param height 编码分辨率高
     */
    fun addEncoderSurface(tag: String, surface: Surface, width: Int, height: Int) {
        handler?.post {
            val core = eglCore ?: return@post
            try {
                val eglSurface = core.createWindowSurface(surface)
                encoderConsumers.add(Consumer(tag, surface, eglSurface, width, height))
                SystemLogger.emit(LogLevel.Info, LogTag.Media, "OSD_ENCODER_ADDED",
                    detail = "tag=$tag ${width}x${height}")
            } catch (t: Throwable) {
                SystemLogger.emit(LogLevel.Warning, LogTag.Media, "OSD_ENCODER_ADD_FAIL",
                    detail = "tag=$tag: ${t.message}")
            }
        }
    }

    fun removeEncoderSurface(tag: String) {
        handler?.post {
            val core = eglCore ?: return@post
            val idx = encoderConsumers.indexOfFirst { it.tag == tag }
            if (idx >= 0) {
                val c = encoderConsumers.removeAt(idx)
                runCatching { core.destroySurface(c.eglSurface) }
                SystemLogger.emit(LogLevel.Info, LogTag.Media, "OSD_ENCODER_REMOVED", detail = "tag=$tag")
            }
        }
    }

    /** 设置屏幕预览 SurfaceView 的 surface。null = 解绑。 */
    fun setScreenSurface(surface: Surface?, width: Int, height: Int) {
        handler?.post {
            val core = eglCore ?: return@post
            screenConsumer?.let { runCatching { core.destroySurface(it.eglSurface) } }
            screenConsumer = null
            if (surface != null) {
                try {
                    val eglSurface = core.createWindowSurface(surface)
                    screenConsumer = Consumer("screen", surface, eglSurface, width, height, cropToFill = true)
                    SystemLogger.emit(LogLevel.Info, LogTag.Media, "OSD_SCREEN_SET",
                        detail = "${width}x${height}")
                } catch (t: Throwable) {
                    SystemLogger.emit(LogLevel.Warning, LogTag.Media, "OSD_SCREEN_SET_FAIL",
                        detail = t.message)
                }
            }
        }
    }

    /**
     * SurfaceRequest 要求的 buffer 尺寸必须真实设置到 SurfaceTexture,否则 CameraX 可能
     * 先把画面写变形。frame 尺寸只用于 GL 保比例绘制,处理旋转后宽高互换的情况。
     */
    fun configureCameraInput(
        bufferWidth: Int,
        bufferHeight: Int,
        frameWidth: Int = bufferWidth,
        frameHeight: Int = bufferHeight
    ) {
        if (bufferWidth <= 0 || bufferHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return
        val h = handler
        if (h == null) {
            cameraBufferWidth = bufferWidth
            cameraBufferHeight = bufferHeight
            cameraFrameWidth = frameWidth
            cameraFrameHeight = frameHeight
            return
        }
        val applyConfig = {
            cameraBufferWidth = bufferWidth
            cameraBufferHeight = bufferHeight
            cameraFrameWidth = frameWidth
            cameraFrameHeight = frameHeight
            surfaceTexture?.setDefaultBufferSize(bufferWidth, bufferHeight)
            cameraPass?.setFrameSize(frameWidth, frameHeight, fboWidth, fboHeight, cropToFill = true)
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "OSD_CAMERA_INPUT_CONFIG",
                detail = "buffer=${bufferWidth}x${bufferHeight}, frame=${frameWidth}x${frameHeight}, fbo=${fboWidth}x${fboHeight}")
        }
        if (Looper.myLooper() == h.looper) {
            applyConfig()
        } else {
            val latch = CountDownLatch(1)
            h.post {
                try {
                    applyConfig()
                } finally {
                    latch.countDown()
                }
            }
            latch.await(500, TimeUnit.MILLISECONDS)
        }
    }

    private fun onFrameAvailable() {
        if (released.get()) return
        val core = eglCore ?: return
        val st = surfaceTexture ?: return
        val cam = cameraPass ?: return
        val text = textPass ?: return

        val frameStartNs = System.nanoTime()
        try {
            // === Step 1: 渲染到 fbo(单一画面源,所有消费者共享) ===
            // 任意 EGL surface 上 makeCurrent 都能渲染到 fbo,这里用第一个消费者的 surface,
            // 没消费者用 pbuffer。pbuffer 没存,简化:第一帧 OK 后才确保至少一个消费者。
            val anchorEglSurface: EGLSurface? =
                screenConsumer?.eglSurface
                    ?: encoderConsumers.firstOrNull()?.eglSurface
            if (anchorEglSurface == null) {
                // 没消费者,丢弃帧但仍要 updateTexImage 避免 SurfaceTexture 卡住
                st.updateTexImage()
                return
            }
            core.makeCurrent(anchorEglSurface)

            st.updateTexImage()
            st.getTransformMatrix(transformMatrix)

            // 绑 fbo,渲染相机 + OSD
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
            cam.draw(transformMatrix, fboWidth, fboHeight)
            drawOsdLayers(text)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

            // === Step 2: 将 fbo blit 到所有消费者 ===
            val presentationNs = st.timestamp
            for (consumer in encoderConsumers) {
                blitToConsumer(core, consumer, fboTexId, presentationNs)
            }
            screenConsumer?.let {
                blitToConsumer(core, it, fboTexId, 0L)  // 屏幕走系统时序
            }

            // 帧时长检查 — 超过预算计入掉帧,batched 触发一次告警避免日志风暴
            val elapsedMs = (System.nanoTime() - frameStartNs) / 1_000_000
            if (elapsedMs > FRAME_BUDGET_MS) {
                droppedFrames++
                if (droppedFrames % DROP_REPORT_THRESHOLD == 0) {
                    val lastNs = lastDropReportNs
                    val nowNs = System.nanoTime()
                    if (nowNs - lastNs > DROP_REPORT_COOLDOWN_NS) {
                        lastDropReportNs = nowNs
                        SystemLogger.emit(LogLevel.Warning, LogTag.Media, "OSD_FRAME_DROPPED",
                            detail = "累计 $droppedFrames 帧渲染超过 ${FRAME_BUDGET_MS}ms 预算,本帧 ${elapsedMs}ms")
                    }
                }
            }
        } catch (t: Throwable) {
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "OSD_FRAME_FAIL",
                detail = "${t::class.simpleName}: ${t.message}")
            // 探测是否 GL context 丢失(横竖屏 / 后台恢复 / GPU 驱动重置等)
            val core = eglCore
            if (core != null && core.isContextLost()) {
                SystemLogger.emit(LogLevel.Warning, LogTag.Media, "OSD_CONTEXT_LOST",
                    detail = "GL context 丢失,尝试 GL 内重建")
                // 先尝试 GL 内重建(EGL + atlas + passes 层),保留消费者注册
                handler?.post { recreateGl() }
            }
        }
    }

    /**
     * GL context 丢失后重建整条 pipeline。
     *
     * 在 GL thread 上调,顺序:
     * 1. 释放当前 EGL 资源(已失效)
     * 2. 暂存所有消费者引用(encoder + screen surface 物理 surface 还在,只是 EGL surface 失效了)
     * 3. 重新跑 start() 内部初始化(EglCore + atlas + passes + fbo)
     * 4. 用暂存的 surface 重新创建 EGL surface 注册回去
     */
    private fun recreateGl() {
        if (released.get()) return
        val context = this.context
        val configFlow = this.configFlow

        // 暂存消费者
        val savedEncoders = encoderConsumers.map { Triple(it.tag, it.surface, it.width to it.height) }
        val savedScreen: Triple<Surface, Int, Int>? = screenConsumer?.let {
            Triple(it.surface, it.width, it.height)
        }

        // 释放当前(失败的)资源
        runCatching { surfaceTexture?.release() }
        runCatching { _cameraInputSurface?.release() }
        runCatching { textPass?.release() }
        runCatching { cameraPass?.release() }
        runCatching { atlas.release() }
        runCatching { eglCore?.release() }

        encoderConsumers.clear()
        screenConsumer = null
        eglCore = null
        cameraPass = null
        textPass = null
        surfaceTexture = null
        _cameraInputSurface = null
        fboId = 0
        fboTexId = 0
        blitProgram = 0
        blitVbo = 0

        try {
            eglCore = EglCore().apply {
                setupDisplay()
                createConfig()
                createContext()
            }
            val tmpPbuffer = eglCore!!.createPbufferSurface(1, 1)
            eglCore!!.makeCurrent(tmpPbuffer)

            if (!atlas.load(context)) throw RuntimeException("OsdFontAtlas.load failed in recreate")
            cameraPass = CameraTexturePass().apply { init() }
            textPass = OsdTextPass(atlas).apply { init() }
            createFbo(fboWidth, fboHeight)
            createBlitProgram()
            cameraPass!!.setFrameSize(
                cameraFrameWidth,
                cameraFrameHeight,
                fboWidth,
                fboHeight,
                cropToFill = true
            )

            surfaceTexture = SurfaceTexture(cameraPass!!.cameraTextureId).apply {
                setDefaultBufferSize(cameraBufferWidth, cameraBufferHeight)
                setOnFrameAvailableListener { handler?.post { onFrameAvailable() } }
            }
            _cameraInputSurface = Surface(surfaceTexture)

            eglCore!!.destroySurface(tmpPbuffer)

            // 恢复消费者
            for ((tag, surface, wh) in savedEncoders) {
                runCatching {
                    val eglSurface = eglCore!!.createWindowSurface(surface)
                    encoderConsumers.add(Consumer(tag, surface, eglSurface, wh.first, wh.second))
                }
            }
            savedScreen?.let { (surface, w, h) ->
                runCatching {
                    val eglSurface = eglCore!!.createWindowSurface(surface)
                    screenConsumer = Consumer("screen", surface, eglSurface, w, h, cropToFill = true)
                }
            }
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "OSD_CONTEXT_RECOVERED",
                detail = "encoder=${savedEncoders.size} screen=${if (savedScreen != null) 1 else 0}")
        } catch (t: Throwable) {
            SystemLogger.emit(LogLevel.Error, LogTag.Media, "OSD_RECREATE_FAILED",
                detail = "${t::class.simpleName}: ${t.message}")
            // 重建失败时只能让上层 fallback,不再尝试
            release()
        }
    }

    private fun drawOsdLayers(text: OsdTextPass) {
        val snap = tickerSource.snapshot()
        val cfg = configFlow.value
        snap.timestamp?.let {
            text.draw(it, cfg.timestamp.position, cfg.timestamp.size,
                fboWidth, fboHeight,
                parseColor(cfg.timestamp.fillColor), parseColor(cfg.timestamp.outlineColor))
        }
        snap.channelName?.let {
            text.draw(it, cfg.channelName.position, cfg.channelName.size,
                fboWidth, fboHeight,
                parseColor(cfg.channelName.fillColor), parseColor(cfg.channelName.outlineColor))
        }
        snap.watermark?.let {
            text.draw(it, cfg.watermark.position, cfg.watermark.size,
                fboWidth, fboHeight,
                parseColor(cfg.watermark.fillColor), parseColor(cfg.watermark.outlineColor))
        }
    }

    private fun blitToConsumer(core: EglCore, consumer: Consumer, srcTex: Int, presentationNs: Long) {
        try {
            core.makeCurrent(consumer.eglSurface)

            // viewport 适配:letterbox(留黑边)或 CENTER_CROP(放大裁切填满)。
            // GL viewport 允许负起点 / 超出尺寸,crop 时画面溢出 view 被自动裁掉。
            val srcAspect = fboWidth.toFloat() / fboHeight
            val dstAspect = consumer.width.toFloat() / consumer.height
            val vpX: Int
            val vpY: Int
            val vpW: Int
            val vpH: Int
            if (consumer.cropToFill) {
                // CENTER_CROP:顶满较短边,较长边溢出居中裁掉,无黑边
                if (srcAspect > dstAspect) {
                    // 源更宽 → 顶满高,左右溢出
                    vpH = consumer.height
                    vpW = (consumer.height * srcAspect).toInt()
                    vpX = (consumer.width - vpW) / 2
                    vpY = 0
                } else {
                    // 源更高 → 顶满宽,上下溢出
                    vpW = consumer.width
                    vpH = (consumer.width / srcAspect).toInt()
                    vpX = 0
                    vpY = (consumer.height - vpH) / 2
                }
            } else if (srcAspect > dstAspect) {
                // letterbox:源更宽 → 顶满宽,上下黑边
                vpW = consumer.width
                vpH = (consumer.width / srcAspect).toInt()
                vpX = 0
                vpY = (consumer.height - vpH) / 2
            } else if (srcAspect < dstAspect) {
                // letterbox:源更高 → 顶满高,左右黑边
                vpW = (consumer.height * srcAspect).toInt()
                vpH = consumer.height
                vpX = (consumer.width - vpW) / 2
                vpY = 0
            } else {
                vpX = 0; vpY = 0
                vpW = consumer.width; vpH = consumer.height
            }

            // 先 clear 整个 surface 为黑(letterbox 黑边),再画到居中 viewport
            GLES30.glViewport(0, 0, consumer.width, consumer.height)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            GLES30.glViewport(vpX, vpY, vpW, vpH)

            GLES30.glUseProgram(blitProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
            GLES30.glUniform1i(blitTexLoc, 0)

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, blitVbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)

            if (presentationNs > 0L) {
                core.setPresentationTime(consumer.eglSurface, presentationNs)
            }
            core.swapBuffers(consumer.eglSurface)
        } catch (t: Throwable) {
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "OSD_BLIT_FAIL",
                detail = "tag=${consumer.tag}: ${t.message}")
        }
    }

    private fun createFbo(width: Int, height: Int) {
        fboWidth = width
        fboHeight = height
        val texArr = IntArray(1)
        GLES30.glGenTextures(1, texArr, 0)
        fboTexId = texArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fboArr = IntArray(1)
        GLES30.glGenFramebuffers(1, fboArr, 0)
        fboId = fboArr[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, fboTexId, 0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("FBO incomplete: 0x${status.toString(16)}")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun createBlitProgram() {
        val vs = """#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vTexCoord = aTexCoord;
}
"""
        val fs = """#version 300 es
precision mediump float;
uniform sampler2D uTexture;
in vec2 vTexCoord;
out vec4 fragColor;
void main() {
    fragColor = texture(uTexture, vTexCoord);
}
"""
        blitProgram = GlUtil.createProgram(vs, fs)
        blitTexLoc = GLES30.glGetUniformLocation(blitProgram, "uTexture")

        // 全屏四边形,Y 翻转(fbo 内坐标系 vs encoder/screen surface 默认期望)
        val verts = floatArrayOf(
            // x,    y,    u,   v(Y 翻转使 fbo 不上下颠倒)
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f,
        )
        val buf = java.nio.ByteBuffer.allocateDirect(verts.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(verts)
            .position(0) as java.nio.FloatBuffer

        val vboArr = IntArray(1)
        GLES30.glGenBuffers(1, vboArr, 0)
        blitVbo = vboArr[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, blitVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)
    }

    fun release() {
        if (released.getAndSet(true)) return
        handler?.post { releaseInternal() }
        thread?.quitSafely()
        thread = null
        handler = null
        started.set(false)
    }

    private fun releaseInternal() {
        try {
            _cameraInputSurface?.release()
            surfaceTexture?.release()
            textPass?.release()
            cameraPass?.release()
            atlas.release()
            if (fboId != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            if (fboTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(fboTexId), 0)
            if (blitProgram != 0) GLES30.glDeleteProgram(blitProgram)
            if (blitVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(blitVbo), 0)
            eglCore?.let { core ->
                encoderConsumers.forEach { runCatching { core.destroySurface(it.eglSurface) } }
                screenConsumer?.let { runCatching { core.destroySurface(it.eglSurface) } }
                core.release()
            }
        } catch (t: Throwable) {
            // 释放路径吃异常
        } finally {
            _cameraInputSurface = null
            surfaceTexture = null
            textPass = null
            cameraPass = null
            encoderConsumers.clear()
            screenConsumer = null
            fboId = 0
            fboTexId = 0
            blitProgram = 0
            blitVbo = 0
            eglCore = null
        }
    }

    companion object {
        /** 单帧渲染预算 — 30fps 下每帧 33.3ms,留 33ms 给 OSD pipeline 整体处理。 */
        const val FRAME_BUDGET_MS = 33L

        /** 累计掉帧数达到这个数才触发一次告警(避免日志风暴)。 */
        const val DROP_REPORT_THRESHOLD = 30

        /** 同类告警冷却 — 相邻两次 OSD_FRAME_DROPPED 至少隔 10s。 */
        const val DROP_REPORT_COOLDOWN_NS = 10_000_000_000L
    }
}
