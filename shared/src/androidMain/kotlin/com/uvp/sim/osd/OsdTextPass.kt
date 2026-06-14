package com.uvp.sim.osd

import android.opengl.GLES30
import com.uvp.sim.config.OsdPosition
import com.uvp.sim.config.OsdSize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * SDF 文本渲染 pass — 字符串 → SDF atlas 顶点 → glDrawArrays。
 *
 * 用法(GL thread,先 [CameraTexturePass.draw] 再调本 pass):
 * ```
 * val atlas = OsdFontAtlas().also { it.load(context) }
 * val pass = OsdTextPass(atlas).also { it.init() }
 * // 每帧:
 * pass.draw("2026-06-14 16:14:18.123", anchor=TOP_RIGHT, size=MEDIUM,
 *           viewportWidth, viewportHeight, fillColor=YELLOW, outlineColor=WHITE)
 * pass.release()
 * ```
 *
 * blend:GL_BLEND on,SRC_ALPHA / ONE_MINUS_SRC_ALPHA(常规 over composite)。
 */
internal class OsdTextPass(private val atlas: OsdFontAtlas) {

    private var program: Int = 0
    private var aPositionLoc: Int = 0
    private var aUvLoc: Int = 0
    private var uAtlasLoc: Int = 0
    private var uFillColorLoc: Int = 0
    private var uOutlineColorLoc: Int = 0
    private var uOutlineWidthLoc: Int = 0
    private var vbo: Int = 0
    private var initialized = false

    private val scratch = ByteBuffer.allocateDirect(MAX_VERTEX_BYTES)
        .order(ByteOrder.nativeOrder())
    private val scratchFloat: FloatBuffer = scratch.asFloatBuffer()

    fun init() {
        if (initialized) return
        program = GlUtil.createProgram(OsdShaders.OSD_TEXT_VERTEX, OsdShaders.OSD_TEXT_FRAGMENT_SDF)
        aPositionLoc = GLES30.glGetAttribLocation(program, "aPosition")
        aUvLoc = GLES30.glGetAttribLocation(program, "aUv")
        uAtlasLoc = GLES30.glGetUniformLocation(program, "uAtlas")
        uFillColorLoc = GLES30.glGetUniformLocation(program, "uFillColor")
        uOutlineColorLoc = GLES30.glGetUniformLocation(program, "uOutlineColor")
        uOutlineWidthLoc = GLES30.glGetUniformLocation(program, "uOutlineWidth")

        val vboArr = IntArray(1)
        GLES30.glGenBuffers(1, vboArr, 0)
        vbo = vboArr[0]
        initialized = true
    }

    /**
     * 渲染单层 OSD 文本。
     *
     * 字号档位实际像素由 [pixelSizeForSize] 决定(基于 [size] + [viewportHeight])。
     * 锚点决定文本对齐角,布局算 NDC 坐标。
     *
     * 字符在 [atlas] 中找不到则跳过(不会抛异常)。
     */
    fun draw(
        text: String,
        anchor: OsdPosition,
        size: OsdSize,
        viewportWidth: Int,
        viewportHeight: Int,
        fillColor: Int,
        outlineColor: Int,
        outlineWidth: Float = 0.15f
    ) {
        check(initialized) { "init() first" }
        if (text.isEmpty()) return
        if (atlas.glTexId == 0) return

        val targetPixel = pixelSizeForSize(size, viewportHeight)
        // 字号 scale 基于 atlas cellSize,不写死 64 — 换 atlas 字号自动算对
        val cellSize = if (atlas.cellSize > 0) atlas.cellSize.toFloat() else 64f
        val scale = targetPixel.toFloat() / cellSize

        // 1. 算文本总宽 + 高(像素)
        var pixelWidth = 0f
        for (ch in text) {
            val g = atlas.lookup(ch) ?: continue
            pixelWidth += g.advance * scale
        }
        val pixelHeight = targetPixel.toFloat()

        // 2. 算锚点起点(NDC)
        val marginPx = 16f
        val anchorX = when (anchor) {
            OsdPosition.TOP_LEFT, OsdPosition.BOTTOM_LEFT -> marginPx
            OsdPosition.TOP_RIGHT, OsdPosition.BOTTOM_RIGHT -> viewportWidth - marginPx - pixelWidth
            OsdPosition.CENTER -> (viewportWidth - pixelWidth) / 2f
        }
        val anchorY = when (anchor) {
            OsdPosition.TOP_LEFT, OsdPosition.TOP_RIGHT -> marginPx
            OsdPosition.BOTTOM_LEFT, OsdPosition.BOTTOM_RIGHT -> viewportHeight - marginPx - pixelHeight
            OsdPosition.CENTER -> (viewportHeight - pixelHeight) / 2f
        }

        // 3. 生成顶点 (NDC pos + atlas UV)
        scratchFloat.position(0)
        var cursor = anchorX
        var vertexCount = 0
        for (ch in text) {
            val g = atlas.lookup(ch) ?: continue
            val px0 = cursor + g.bearingX * scale
            val py0 = anchorY + g.bearingY * scale
            val px1 = px0 + g.pixelW * scale
            val py1 = py0 + g.pixelH * scale

            val nx0 = pxToNdcX(px0, viewportWidth)
            val nx1 = pxToNdcX(px1, viewportWidth)
            // GL Y 朝上,屏幕 Y 朝下,这里翻转
            val ny0 = pxToNdcY(py0, viewportHeight)
            val ny1 = pxToNdcY(py1, viewportHeight)

            val u0 = g.u; val u1 = g.u + g.w
            val v0 = g.v; val v1 = g.v + g.h

            // 两个三角形 6 顶点
            scratchFloat.put(nx0).put(ny0).put(u0).put(v1)
            scratchFloat.put(nx1).put(ny0).put(u1).put(v1)
            scratchFloat.put(nx0).put(ny1).put(u0).put(v0)
            scratchFloat.put(nx1).put(ny0).put(u1).put(v1)
            scratchFloat.put(nx1).put(ny1).put(u1).put(v0)
            scratchFloat.put(nx0).put(ny1).put(u0).put(v0)
            vertexCount += 6

            cursor += g.advance * scale
            if (vertexCount * 4 * 4 >= MAX_VERTEX_BYTES - 96) break  // 缓冲区接近满
        }
        if (vertexCount == 0) return
        scratchFloat.position(0)

        // 4. 上传 + 绘制
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlas.glTexId)
        GLES30.glUniform1i(uAtlasLoc, 0)
        GLES30.glUniform4fv(uFillColorLoc, 1, colorToFloat(fillColor), 0)
        GLES30.glUniform4fv(uOutlineColorLoc, 1, colorToFloat(outlineColor), 0)
        GLES30.glUniform1f(uOutlineWidthLoc, outlineWidth)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertexCount * 4 * 4,
            scratch,
            GLES30.GL_DYNAMIC_DRAW
        )
        GLES30.glEnableVertexAttribArray(aPositionLoc)
        GLES30.glVertexAttribPointer(aPositionLoc, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(aUvLoc)
        GLES30.glVertexAttribPointer(aUvLoc, 2, GLES30.GL_FLOAT, false, 16, 8)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)

        GLES30.glDisableVertexAttribArray(aPositionLoc)
        GLES30.glDisableVertexAttribArray(aUvLoc)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        if (program != 0) GLES30.glDeleteProgram(program)
        if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        program = 0
        vbo = 0
        initialized = false
    }

    /**
     * 字号档位 → 像素值(基于视口高度做相对缩放)。
     *
     * - SMALL: 视口高度的 3.5%(720p ≈ 25px,1080p ≈ 38px)
     * - MEDIUM: 5.0%
     * - LARGE: 7.0%
     */
    private fun pixelSizeForSize(size: OsdSize, viewportHeight: Int): Int {
        val ratio = when (size) {
            OsdSize.SMALL -> 0.035f
            OsdSize.MEDIUM -> 0.050f
            OsdSize.LARGE -> 0.070f
        }
        return (viewportHeight * ratio).toInt().coerceAtLeast(16)
    }

    /** 屏幕像素 X(0..width)→ NDC(-1..1) */
    private fun pxToNdcX(px: Float, viewportWidth: Int): Float =
        (px / viewportWidth) * 2f - 1f

    /** 屏幕像素 Y(0..height,Y 朝下)→ NDC(-1..1,Y 朝上) */
    private fun pxToNdcY(py: Float, viewportHeight: Int): Float =
        1f - (py / viewportHeight) * 2f

    /** ARGB int → vec4 (0..1) */
    private fun colorToFloat(argb: Int): FloatArray {
        val a = ((argb ushr 24) and 0xFF) / 255f
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return floatArrayOf(r, g, b, a)
    }

    companion object {
        /** scratch buffer 容量 — 每字符 6 vert × 4 float × 4 byte = 96 byte,1024 字符 ≈ 96KB。 */
        private const val MAX_VERTEX_BYTES = 96 * 1024
    }
}
