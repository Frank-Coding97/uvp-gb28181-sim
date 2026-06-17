package com.uvp.sim.osd

import android.opengl.GLES30
import com.uvp.sim.config.OsdPosition
import com.uvp.sim.config.OsdSize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

        // 1. 算文本总宽(像素) + 字形实际最高点(用于顶部对齐,避免字格上方空白把字压低)
        var pixelWidth = 0f
        var maxGlyphTop = 0f  // max(bearingY + pixelH):字形顶部相对 anchorY 的最大延伸
        for (ch in text) {
            val g = atlas.lookup(ch) ?: continue
            pixelWidth += g.advance * scale
            val top = (g.bearingY + g.pixelH)
            if (top > maxGlyphTop) maxGlyphTop = top
        }
        val pixelHeight = targetPixel.toFloat()
        // 字形顶部实际占的高度(像素,已缩放)。TOP 锚点按它定位,而不是预留整个字格高,
        // 否则字格上方 SDF 留白(本字体约 81% 字格高)会把文字明显压到下方。
        val glyphTopPx = if (maxGlyphTop > 0f) maxGlyphTop * scale else pixelHeight

        // 2. 算锚点起点(NDC)
        // TOP 用 glyphTopPx(字形实际顶高)定位 → 字顶贴 marginPx;不再预留整个字格高。
        val marginPx = 6f
        val anchorX = when (anchor) {
            OsdPosition.TOP_LEFT, OsdPosition.BOTTOM_LEFT -> marginPx
            OsdPosition.TOP_RIGHT, OsdPosition.BOTTOM_RIGHT -> viewportWidth - marginPx - pixelWidth
            OsdPosition.CENTER -> (viewportWidth - pixelWidth) / 2f
        }
        // fbo 内容会被 OsdRenderer 的 blit pass 做一次垂直翻转(为让相机画面正立)。
        // 字形 quad 已通过 UV 交叉补偿了这次翻转,锚点也必须按"翻转后"的目标反算:
        // 屏幕 TOP 对应 fbo 底部、屏幕 BOTTOM 对应 fbo 顶部,blit 翻转后落到正确的屏幕角。
        val anchorY = when (anchor) {
            OsdPosition.TOP_LEFT, OsdPosition.TOP_RIGHT -> viewportHeight - marginPx - glyphTopPx
            OsdPosition.BOTTOM_LEFT, OsdPosition.BOTTOM_RIGHT -> marginPx
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

    /**
     * 全屏斜向平铺水印 — 把 [text] 旋转 [angleDeg] 度后按网格在整屏重复铺满,
     * 类似 PDF 防泄密水印。用于自定义水印层(无单一锚点)。
     *
     * 实现:
     * - 每个瓦片是一行倾斜文本,瓦片中心按行列网格分布(行间错位半格,更自然)。
     * - 网格在"未旋转坐标系"里布满 [-diag, diag] 区间(diag = 屏幕对角线),
     *   保证旋转后任何角度都覆盖整屏,无空缺。
     * - 字形 quad 在瓦片局部坐标生成后,统一过旋转矩阵 + 平移到瓦片中心,再转 NDC。
     * - 顶点接近 scratch 上限时分批 flush,避免溢出。
     *
     * [fillAlpha] 控制水印整体透明度(0..1),叠在 [fillColor] 的 alpha 上。
     */
    fun drawTiled(
        text: String,
        size: OsdSize,
        viewportWidth: Int,
        viewportHeight: Int,
        fillColor: Int,
        outlineColor: Int,
        angleDeg: Float = -30f,
        fillAlpha: Float = 0.28f,
        outlineWidth: Float = 0.12f
    ) {
        check(initialized) { "init() first" }
        if (text.isEmpty()) return
        if (atlas.glTexId == 0) return
        if (viewportWidth <= 0 || viewportHeight <= 0) return

        val targetPixel = pixelSizeForSize(size, viewportHeight)
        val cellSize = if (atlas.cellSize > 0) atlas.cellSize.toFloat() else 64f
        val scale = targetPixel.toFloat() / cellSize

        // 单行文本像素宽 + 高
        var textWidth = 0f
        for (ch in text) {
            val g = atlas.lookup(ch) ?: continue
            textWidth += g.advance * scale
        }
        if (textWidth <= 0f) return
        val textHeight = targetPixel.toFloat()

        // 网格步距:横向 = 文本宽 + 1.5 个文本宽间隔;纵向 = 文本高 × 4(行距)
        val stepX = textWidth * 2.5f
        val stepY = textHeight * 4f
        if (stepX <= 0f || stepY <= 0f) return

        // 旋转矩阵(屏幕像素坐标系,Y 朝下)
        val rad = angleDeg * PI.toFloat() / 180f
        val cosA = cos(rad)
        val sinA = sin(rad)

        // 覆盖范围:用对角线半径,保证旋转后铺满。中心在屏幕中点。
        val cx = viewportWidth / 2f
        val cy = viewportHeight / 2f
        val diag = sqrt((viewportWidth * viewportWidth + viewportHeight * viewportHeight).toFloat())
        val half = diag / 2f + maxOf(stepX, stepY)

        val cols = (half / stepX).toInt() + 1
        val rows = (half / stepY).toInt() + 1

        // 着色 uniform(平铺水印整体半透明)
        val fill = colorToFloat(fillColor).also { it[3] *= fillAlpha }
        val outline = colorToFloat(outlineColor).also { it[3] *= fillAlpha }

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlas.glTexId)
        GLES30.glUniform1i(uAtlasLoc, 0)
        GLES30.glUniform4fv(uFillColorLoc, 1, fill, 0)
        GLES30.glUniform4fv(uOutlineColorLoc, 1, outline, 0)
        GLES30.glUniform1f(uOutlineWidthLoc, outlineWidth)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(aPositionLoc)
        GLES30.glEnableVertexAttribArray(aUvLoc)

        scratchFloat.position(0)
        var vertexCount = 0

        for (row in -rows..rows) {
            // 奇数行错位半格,平铺更自然
            val offsetX = if (row % 2 == 0) 0f else stepX / 2f
            for (col in -cols..cols) {
                // 瓦片中心(未旋转坐标系,相对屏幕中心)
                val tileLocalX = col * stepX + offsetX
                val tileLocalY = row * stepY
                // 文本左端起点(瓦片内,文本绕中心水平居中、竖直居中)
                var cursor = -textWidth / 2f
                val baseY = -textHeight / 2f

                for (ch in text) {
                    val g = atlas.lookup(ch) ?: continue
                    // 字形 quad 四角(瓦片局部坐标,Y 朝下)
                    val lx0 = cursor + g.bearingX * scale
                    val ly0 = baseY + g.bearingY * scale
                    val lx1 = lx0 + g.pixelW * scale
                    val ly1 = ly0 + g.pixelH * scale

                    val u0 = g.u; val u1 = g.u + g.w
                    val v0 = g.v; val v1 = g.v + g.h

                    // 4 角 → 旋转 → 平移到屏幕像素 → NDC
                    val p00 = tileVertex(lx0, ly0, tileLocalX, tileLocalY, cosA, sinA, cx, cy, viewportWidth, viewportHeight)
                    val p10 = tileVertex(lx1, ly0, tileLocalX, tileLocalY, cosA, sinA, cx, cy, viewportWidth, viewportHeight)
                    val p01 = tileVertex(lx0, ly1, tileLocalX, tileLocalY, cosA, sinA, cx, cy, viewportWidth, viewportHeight)
                    val p11 = tileVertex(lx1, ly1, tileLocalX, tileLocalY, cosA, sinA, cx, cy, viewportWidth, viewportHeight)

                    // 两三角形,UV 跟 draw() 一致(v1 配 ly0 顶、v0 配 ly1 底)
                    scratchFloat.put(p00[0]).put(p00[1]).put(u0).put(v1)
                    scratchFloat.put(p10[0]).put(p10[1]).put(u1).put(v1)
                    scratchFloat.put(p01[0]).put(p01[1]).put(u0).put(v0)
                    scratchFloat.put(p10[0]).put(p10[1]).put(u1).put(v1)
                    scratchFloat.put(p11[0]).put(p11[1]).put(u1).put(v0)
                    scratchFloat.put(p01[0]).put(p01[1]).put(u0).put(v0)
                    vertexCount += 6

                    cursor += g.advance * scale

                    if (vertexCount * 4 * 4 >= MAX_VERTEX_BYTES - 96) {
                        flushBatch(vertexCount)
                        scratchFloat.position(0)
                        vertexCount = 0
                    }
                }
            }
        }
        if (vertexCount > 0) flushBatch(vertexCount)

        GLES30.glDisableVertexAttribArray(aPositionLoc)
        GLES30.glDisableVertexAttribArray(aUvLoc)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** 瓦片局部坐标 → 旋转 → 平移到屏幕中心相对 → 屏幕像素 → NDC。返回 [ndcX, ndcY]。 */
    private fun tileVertex(
        localX: Float, localY: Float,
        tileX: Float, tileY: Float,
        cosA: Float, sinA: Float,
        cx: Float, cy: Float,
        vpW: Int, vpH: Int
    ): FloatArray {
        // 先把字形局部点绕瓦片原点旋转
        val rx = localX * cosA - localY * sinA
        val ry = localX * sinA + localY * cosA
        // 平移到瓦片中心,再平移到屏幕中心 → 绝对屏幕像素
        val screenX = cx + tileX + rx
        val screenY = cy + tileY + ry
        return floatArrayOf(pxToNdcX(screenX, vpW), pxToNdcY(screenY, vpH))
    }

    /** 上传当前 scratch 中 [vertexCount] 个顶点并绘制(平铺分批用)。 */
    private fun flushBatch(vertexCount: Int) {
        scratchFloat.position(0)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertexCount * 4 * 4,
            scratch,
            GLES30.GL_DYNAMIC_DRAW
        )
        GLES30.glVertexAttribPointer(aPositionLoc, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glVertexAttribPointer(aUvLoc, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
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
